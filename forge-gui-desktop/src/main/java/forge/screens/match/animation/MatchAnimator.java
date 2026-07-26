package forge.screens.match.animation;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventSpellResolved;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gui.FThreads;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.CMatchUI;
import forge.screens.match.views.VField;
import forge.screens.match.views.VHand;
import forge.view.arcane.CardPanel;

/**
 * Owns the animation system for one match and translates game events into motion.
 * <p>
 * It subscribes to the raw, uncoalesced event stream rather than the batched UI
 * updates, because {@code FControlGameEventHandler} exists precisely to collapse
 * intermediate states - exactly the states an animation needs. What arrives here is
 * every event, in order, on the game thread; what leaves is a queue of steps drained
 * on the EDT at watchable speed.
 * <p>
 * Nothing in this class is load-bearing for correctness. If it does nothing at all the
 * match plays exactly as before, which is why every entry point fails soft.
 */
public final class MatchAnimator {

    /** Distinct targets from one source before an effect is treated as area-of-effect. */
    private static final int AOE_TARGET_THRESHOLD = 3;
    /** How far an attacker travels toward what it hits, as a fraction of the gap. */
    private static final float LUNGE_REACH = 0.55f;

    private final CMatchUI matchUI;
    private final AnimationLayer layer = new AnimationLayer();
    private final AnimationQueue queue = new AnimationQueue();
    private final AnimationClock clock = new AnimationClock(queue, layer);

    /**
     * Cards that just left the battlefield, so the panel-removal hook knows a departure
     * is expected and can play it out instead of having the card blink away.
     */
    private final Map<Integer, ZoneType> departing = new HashMap<>();
    /** Cards that just arrived on a battlefield, to be faded in once their panel exists. */
    private final Set<Integer> arriving = new HashSet<>();

    /** Damage grouped by source, flushed on the next EDT pass. See {@link #flushDamage()}. */
    private final Map<Integer, DamageGroup> pendingDamage = new LinkedHashMap<>();
    private boolean damageFlushQueued;

    public MatchAnimator(final CMatchUI matchUI) {
        this.matchUI = matchUI;
        layer.setQueue(queue);
    }

    public JPanel getPanel() {
        return layer;
    }

    public AnimationLayer getLayer() {
        return layer;
    }

    public AnimationClock getClock() {
        return clock;
    }

    public boolean isEnabled() {
        return FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_ANIMATIONS);
    }

    /** Re-read preferences; called when the settings screen changes them. */
    public void refreshPrefs() {
        queue.setUserSpeed(FModel.getPreferences().getPrefInt(FPref.UI_ANIMATION_SPEED) / 100f);
    }

    /**
     * Catch the display up to the real game state at once. Bound to the skip hotkey,
     * and called before the game blocks on a human decision - answering a prompt about
     * a board you have not been shown yet is worse than losing an animation.
     */
    public void skipAll() {
        FThreads.invokeInEdtNowOrLater(clock::skipAll);
    }

    public void dispose() {
        clock.stop();
        queue.skipAll();
        departing.clear();
        arriving.clear();
        pendingDamage.clear();
        synchronized (pendingCasts) {
            pendingCasts.clear();
        }
    }

    // ------------------------------------------------------------------ event intake

    /**
     * Called on the game thread for every event, before the batching handler sees it.
     * Does the minimum here and defers the rest to the EDT, since the game thread is
     * what the player is waiting on.
     */
    public void receiveGameEvent(final GameEvent ev) {
        if (ev == null || !isEnabled()) {
            return;
        }
        try {
            if (ev instanceof GameEventCardDamaged e) {
                recordDamage(e.source(), e.card(), null, e.amount());
            } else if (ev instanceof GameEventPlayerDamaged e) {
                recordDamage(e.source(), null, e.target(), e.amount());
            } else if (ev instanceof GameEventCardChangeZone e) {
                recordZoneChange(e);
            } else if (ev instanceof GameEventSpellAbilityCast e) {
                recordCast(e);
            } else if (ev instanceof GameEventSpellResolved e) {
                resolveCast(e);
            } else if (ev instanceof GameEventSpellRemovedFromStack e) {
                forgetCast(e.sa());
            }
            // Note there is no token-created case: GameEventTokenCreated carries no
            // payload at all, so tokens are recognised by CardView.isToken() when their
            // panel appears instead. See onPanelsAdded.
        } catch (final RuntimeException ignored) {
            // Never let a cosmetic failure propagate into the game thread.
        }
    }

    private void recordZoneChange(final GameEventCardChangeZone e) {
        final CardView card = e.card();
        if (card == null) {
            return;
        }
        final ZoneType from = e.from() == null ? null : e.from().zoneType();
        final ZoneType to = e.to() == null ? null : e.to().zoneType();
        synchronized (this) {
            if (from == ZoneType.Battlefield && to != ZoneType.Battlefield) {
                departing.put(card.getId(), to);
            } else if (to == ZoneType.Battlefield) {
                arriving.add(card.getId());
            }
        }
    }

    // ------------------------------------------------------------------ spell resolution

    /** What a spell was aimed at, remembered from cast time so it can be shown resolving. */
    private static final class CastRecord {
        private final CardView source;
        private final List<CardView> cardTargets = new ArrayList<>(2);
        private final List<PlayerView> playerTargets = new ArrayList<>(1);

        CastRecord(final CardView source) {
            this.source = source;
        }

        int targetCount() {
            return cardTargets.size() + playerTargets.size();
        }
    }

    /**
     * Targets captured at cast time, keyed by the ability's view.
     * <p>
     * They have to be captured then rather than read at resolution, because
     * {@link GameEventSpellResolved} carries only a {@link SpellAbilityView}, which
     * exposes its host card but not what it was pointed at. The stack item that does
     * know is gone by then.
     */
    private final Map<SpellAbilityView, CastRecord> pendingCasts = new LinkedHashMap<>();

    private void recordCast(final GameEventSpellAbilityCast e) {
        final StackItemView si = e.si();
        if (si == null || e.sa() == null) {
            return;
        }
        final CastRecord rec = new CastRecord(si.getSourceCard());
        // Walk the sub-instances too, the way the targeting arrows do, so the targets of
        // a subability are drawn as well as the top-level ones.
        for (StackItemView cur = si; cur != null; cur = cur.getSubInstance()) {
            if (cur.getTargetCards() != null) {
                for (final CardView c : cur.getTargetCards()) {
                    rec.cardTargets.add(c);
                }
            }
            if (cur.getTargetPlayers() != null) {
                for (final PlayerView p : cur.getTargetPlayers()) {
                    rec.playerTargets.add(p);
                }
            }
        }
        if (rec.targetCount() == 0) {
            return; // nothing to draw a line to
        }
        synchronized (pendingCasts) {
            pendingCasts.put(e.sa(), rec);
            // Anything countered or otherwise removed without a resolution event would
            // sit here forever; cap the map rather than trust every path to clean up.
            while (pendingCasts.size() > MAX_PENDING_CASTS) {
                final SpellAbilityView oldest = pendingCasts.keySet().iterator().next();
                pendingCasts.remove(oldest);
            }
        }
    }

    private static final int MAX_PENDING_CASTS = 64;

    private void forgetCast(final SpellAbilityView sa) {
        if (sa == null) {
            return;
        }
        synchronized (pendingCasts) {
            pendingCasts.remove(sa);
        }
    }

    /** Draw the spell reaching its targets, at the moment it actually resolves. */
    private void resolveCast(final GameEventSpellResolved e) {
        final CastRecord rec;
        synchronized (pendingCasts) {
            rec = pendingCasts.remove(e.spell());
        }
        if (rec == null || e.hasFizzled()) {
            // A fizzled spell never reached anything, so showing it connect would lie.
            return;
        }
        FThreads.invokeInEdtLater(() -> enqueueResolution(rec));
    }

    private void enqueueResolution(final CastRecord rec) {
        final Point from = centreOf(rec.source);
        if (from == null) {
            return;
        }
        final List<Color> palette = CardColors.of(rec.source, canShow(rec.source));
        final AnimationStep step = new AnimationStep("resolve:" + rec.source.getName());

        if (rec.targetCount() >= AOE_TARGET_THRESHOLD) {
            // Enough targets that individual beams would be noise; sweep the boards.
            final Set<PlayerView> affected = new HashSet<>();
            for (final CardView c : rec.cardTargets) {
                if (c.getController() != null) {
                    affected.add(c.getController());
                }
            }
            affected.addAll(rec.playerTargets);
            for (final PlayerView p : affected) {
                final Rectangle area = battlefieldBounds(p);
                if (area != null) {
                    step.add(new BurstAnim(area, palette, 140, 620));
                }
            }
        } else {
            for (final CardView c : rec.cardTargets) {
                final Point to = centreOf(c);
                if (to != null) {
                    step.add(new BeamAnim(from, to, palette, 1f, 480));
                }
            }
            for (final PlayerView p : rec.playerTargets) {
                final Point to = avatarCentre(p);
                if (to != null) {
                    step.add(new BeamAnim(from, to, palette, 1f, 480));
                }
            }
        }
        if (!step.isEmpty()) {
            queue.enqueue(step);
            clock.start();
        }
    }

    // ------------------------------------------------------------------ damage grouping

    /** One source's damage over a single burst, so area effects can be told from single hits. */
    private static final class DamageGroup {
        private final CardView source;
        private final List<CardView> cardTargets = new ArrayList<>(4);
        private final List<PlayerView> playerTargets = new ArrayList<>(2);
        private int total;

        DamageGroup(final CardView source) {
            this.source = source;
        }

        int targetCount() {
            return cardTargets.size() + playerTargets.size();
        }
    }

    private void recordDamage(final CardView source, final CardView cardTarget,
            final PlayerView playerTarget, final int amount) {
        if (source == null || amount <= 0) {
            return;
        }
        synchronized (pendingDamage) {
            final DamageGroup g = pendingDamage.computeIfAbsent(source.getId(), k -> new DamageGroup(source));
            if (cardTarget != null) {
                g.cardTargets.add(cardTarget);
            }
            if (playerTarget != null) {
                g.playerTargets.add(playerTarget);
            }
            g.total += amount;
            if (damageFlushQueued) {
                return;
            }
            damageFlushQueued = true;
        }
        // Same latch trick the batching event handler uses: everything the game thread
        // emits before the EDT next runs lands in one group, which is exactly the window
        // a single spell's damage occupies.
        FThreads.invokeInEdtLater(this::flushDamage);
    }

    private void flushDamage() {
        final List<DamageGroup> groups;
        synchronized (pendingDamage) {
            damageFlushQueued = false;
            if (pendingDamage.isEmpty()) {
                return;
            }
            groups = new ArrayList<>(pendingDamage.values());
            pendingDamage.clear();
        }
        for (final DamageGroup g : groups) {
            if (g.targetCount() >= AOE_TARGET_THRESHOLD) {
                enqueueAreaEffect(g);
            } else {
                enqueueDirectHits(g);
            }
        }
    }

    /**
     * One attacker hitting one or two things: a beam per target, plus a lunge if the
     * source is a creature in combat.
     * <p>
     * This is where double strike and trample come out for free. Double strike deals
     * damage in two separate steps, so two groups arrive and the attacker lunges twice.
     * A trampler assigns to its blocker and to the defending player within one step, so
     * a single group carries both targets and the attacker strikes at each.
     */
    private void enqueueDirectHits(final DamageGroup g) {
        final CardPanel sourcePanel = findPanel(g.source);
        final List<Color> palette = CardColors.of(g.source, canShow(g.source));
        final AnimationStep step = new AnimationStep("damage:" + g.source.getName());

        final List<Point> targets = new ArrayList<>(g.targetCount());
        for (final CardView target : g.cardTargets) {
            final Point to = centreOf(target);
            if (to != null) {
                targets.add(to);
                final CardPanel tp = findPanel(target);
                if (tp != null && sourcePanel != null) {
                    step.add(PanelAnim.flinch(tp, toPanelSpace(tp, centreOf(g.source)), 260));
                }
            }
        }
        for (final PlayerView target : g.playerTargets) {
            final Point to = avatarCentre(target);
            if (to != null) {
                targets.add(to);
            }
        }
        if (targets.isEmpty()) {
            return;
        }

        final Point from = centreOf(g.source);
        if (from != null) {
            for (final Point to : targets) {
                step.add(new BeamAnim(from, to, palette, g.total, 460));
            }
        }
        // Only the attacker lunges, never the blocker, even though both deal damage in
        // the same step. Two creatures striking each other would move toward each other
        // at once and overlap in the middle, and several blockers would all converge on
        // one attacker. The blocker's half of the exchange is already legible: it takes
        // a flinch from the attacker's damage above, and the attacker takes one from the
        // blocker's own damage group. Lunging is for the aggressor.
        //
        // A burn spell must not lunge either, hence the combat check rather than just
        // testing for a creature.
        if (sourcePanel != null && g.source.isAttacking()) {
            final Point toward = toPanelSpace(sourcePanel, targets.get(0));
            step.add(PanelAnim.lunge(sourcePanel, toward, LUNGE_REACH, 420));
        }
        queue.enqueue(step);
        clock.start();
    }

    /** An effect that hit enough things to be worth showing as a sweep over each board. */
    private void enqueueAreaEffect(final DamageGroup g) {
        final List<Color> palette = CardColors.of(g.source, canShow(g.source));
        final AnimationStep step = new AnimationStep("aoe:" + g.source.getName());

        final Set<PlayerView> affected = new HashSet<>();
        for (final CardView target : g.cardTargets) {
            if (target.getController() != null) {
                affected.add(target.getController());
            }
        }
        affected.addAll(g.playerTargets);

        for (final PlayerView p : affected) {
            final Rectangle area = battlefieldBounds(p);
            if (area != null) {
                step.add(new BurstAnim(area, palette, Math.min(220, 60 + g.total * 10), 640));
            }
        }
        // Still flinch each victim, so it stays clear which permanents were hit.
        for (final CardView target : g.cardTargets) {
            final CardPanel tp = findPanel(target);
            final Point origin = centreOf(g.source);
            if (tp != null && origin != null) {
                step.add(PanelAnim.flinch(tp, toPanelSpace(tp, origin), 300));
            }
        }
        if (!step.isEmpty()) {
            queue.enqueue(step);
            clock.start();
        }
    }

    // ------------------------------------------------------------------ zone transitions

    /**
     * Called from the panel container immediately before a card panel is disposed. The
     * panel is still valid here, which is the only moment a picture of it can be taken.
     */
    public void onPanelRemoved(final CardPanel panel) {
        if (panel == null || panel.getCard() == null || !isEnabled()) {
            return;
        }
        final ZoneType to;
        synchronized (this) {
            to = departing.remove(panel.getCard().getId());
        }
        if (to == null) {
            // Not a tracked departure - a re-layout or a match teardown, not a death.
            return;
        }
        final CardSnapshot snap = CardSnapshot.capture(panel, layer);
        if (snap == null) {
            return;
        }
        // The real panel is about to vanish either way, so the ghost runs free of the
        // queue rather than holding up the events behind it.
        clock.addFree(to == ZoneType.Hand || to == ZoneType.Library
                ? GhostAnim.flyTo(snap, handAnchor(panel.getCard()), 420)
                : GhostAnim.fadeOut(snap, 520));
    }

    /**
     * Called after a zone refresh has created panels, so newly arrived permanents can
     * fade in rather than pop into place.
     */
    public void onPanelsAdded(final List<CardPanel> panels) {
        if (panels == null || panels.isEmpty() || !isEnabled()) {
            return;
        }
        for (final CardPanel panel : panels) {
            final CardView card = panel.getCard();
            if (card == null) {
                continue;
            }
            final boolean expected;
            synchronized (this) {
                expected = arriving.remove(card.getId());
            }
            // Tokens have no prior zone to leave, so they never raise a change-zone
            // event; treat their first appearance as an arrival too.
            if (expected || card.isToken()) {
                clock.addFree(PanelAnim.fadeIn(panel, 320));
            }
        }
    }

    /**
     * Slide a permanent from where the player dropped it to the slot layout gave it.
     * Called by the drag controller once the card has actually resolved.
     */
    public void slideIntoPlace(final CardView card, final Point fromScreenPoint) {
        if (card == null || fromScreenPoint == null || !isEnabled()) {
            return;
        }
        FThreads.invokeInEdtNowOrLater(() -> {
            final CardPanel panel = findPanel(card);
            if (panel == null || !panel.isShowing()) {
                return;
            }
            final Point now = panel.getLocationOnScreen();
            clock.addFree(PanelAnim.slideFrom(panel,
                    fromScreenPoint.x - now.x - panel.getWidth() / 2,
                    fromScreenPoint.y - now.y - panel.getHeight() / 2, 300));
        });
    }

    // ------------------------------------------------------------------ geometry

    private boolean canShow(final CardView card) {
        return card != null && matchUI.mayView(card);
    }

    /** Locate a card's panel without the tab-switching side effect of the CMatchUI lookup. */
    public CardPanel findPanel(final CardView card) {
        if (card == null) {
            return null;
        }
        final int id = card.getId();
        for (final VField f : matchUI.getFieldViews()) {
            final CardPanel p = f.getTabletop().getCardPanel(id);
            if (p != null) {
                return p;
            }
        }
        for (final VHand h : matchUI.getHandViews()) {
            final CardPanel p = h.getHandArea().getCardPanel(id);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /** Centre of a card, in overlay coordinates; null when it is not on screen. */
    public Point centreOf(final CardView card) {
        return centreOf(findPanel(card));
    }

    public Point centreOf(final CardPanel panel) {
        if (panel == null || !panel.isShowing() || !layer.isShowing()) {
            return null;
        }
        return SwingUtilities.convertPoint(panel.getParent(),
                panel.getX() + panel.getWidth() / 2, panel.getY() + panel.getHeight() / 2, layer);
    }

    /** Centre of a player's avatar, the endpoint used for damage dealt to a player. */
    public Point avatarCentre(final PlayerView player) {
        final VField field = fieldFor(player);
        if (field == null) {
            return null;
        }
        final JPanel avatar = field.getAvatarArea();
        if (avatar == null || !avatar.isShowing() || !layer.isShowing()) {
            return null;
        }
        return SwingUtilities.convertPoint(avatar.getParent(),
                avatar.getX() + avatar.getWidth() / 2, avatar.getY() + avatar.getHeight() / 2, layer);
    }

    /** A player's whole battlefield, in overlay coordinates, for area effects. */
    public Rectangle battlefieldBounds(final PlayerView player) {
        final VField field = fieldFor(player);
        if (field == null) {
            return null;
        }
        return boundsInLayer(field.getTabletop());
    }

    public Rectangle boundsInLayer(final JComponent c) {
        if (c == null || !c.isShowing() || !layer.isShowing()) {
            return null;
        }
        final Point at = SwingUtilities.convertPoint(c.getParent(), c.getX(), c.getY(), layer);
        return new Rectangle(at.x, at.y, c.getWidth(), c.getHeight());
    }

    private VField fieldFor(final PlayerView player) {
        if (player == null) {
            return null;
        }
        try {
            return matchUI.getFieldViewFor(player);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** Where a card returning to hand should fly; falls back to its controller's avatar. */
    private Point handAnchor(final CardView card) {
        final Point avatar = avatarCentre(card.getController());
        return avatar != null ? avatar : new Point(layer.getWidth() / 2, layer.getHeight());
    }

    /** Convert an overlay point into the coordinate space of a panel's parent. */
    public Point toPanelSpace(final CardPanel panel, final Point overlayPoint) {
        if (overlayPoint == null || panel == null || !panel.isShowing()) {
            return new Point(0, 0);
        }
        return SwingUtilities.convertPoint(layer, overlayPoint, panel.getParent());
    }
}
