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

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
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
import forge.util.collect.FCollectionView;
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
    private static final long LUNGE_MS = 420L;
    private static final long IMPACT_MS = 560L;
    /**
     * When the collision sparks fire, as a fraction of {@link #IMPACT_MS}. Matched to
     * the point the lunge reaches full extension, so the sparks appear on contact
     * rather than as the attacker sets off.
     */
    private static final float IMPACT_TRIGGER = (LUNGE_MS * 0.35f) / IMPACT_MS;

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
        // A blocker's damage is the other half of a collision the attacker's strike
        // already shows. Rather than replaying it as its own exchange - a second beat,
        // with particles crawling back across a gap the blocker never crossed - it is
        // folded into the attacker's step as a simultaneous recoil.
        final Map<Integer, Set<Integer>> counterHits = new HashMap<>();
        final Set<DamageGroup> folded = new HashSet<>();
        for (final DamageGroup g : groups) {
            if (g.source == null || g.source.isAttacking() || !g.source.isBlocking()
                    || g.cardTargets.isEmpty() || !g.playerTargets.isEmpty()) {
                continue;
            }
            boolean allAttackers = true;
            for (final CardView t : g.cardTargets) {
                allAttackers &= t.isAttacking();
            }
            if (!allAttackers) {
                continue;
            }
            for (final CardView t : g.cardTargets) {
                counterHits.computeIfAbsent(t.getId(), k -> new HashSet<>()).add(g.source.getId());
            }
            folded.add(g);
        }

        for (final DamageGroup g : groups) {
            if (folded.contains(g)) {
                continue;
            }
            if (g.targetCount() >= AOE_TARGET_THRESHOLD) {
                enqueueAreaEffect(g);
            } else {
                enqueueDirectHits(g, counterHits);
            }
        }
    }

    /**
     * One source hitting a small number of things.
     * <p>
     * An attacker is shown working through what it hit one at a time, in damage
     * assignment order, because that is the order the rules actually assign in: it must
     * finish with the first blocker before any damage reaches the second. A single
     * lunge at one of several blockers would misrepresent that, and lunging at them all
     * at once is not something a card can do.
     * <p>
     * Double strike and trample need no special handling. Double strike deals damage in
     * two separate steps, so two groups arrive and the whole sequence plays twice. A
     * trampler assigns to its blockers and to the defending player within one step, and
     * {@link #orderTargets} puts the player last, so the attacker cuts through the
     * blockers and follows through to the player.
     */
    private void enqueueDirectHits(final DamageGroup g, final Map<Integer, Set<Integer>> counterHits) {
        final CardPanel sourcePanel = findPanel(g.source);
        final Point from = centreOf(g.source);
        final List<Color> palette = CardColors.of(g.source, canShow(g.source));
        final List<GameEntityView> targets = orderTargets(g);
        if (targets.isEmpty()) {
            return;
        }

        // Only the attacker moves, never the blocker, even though both deal damage in
        // the same step. Two creatures striking each other would advance at once and
        // overlap in the middle, and several blockers would all converge on one
        // attacker. The blocker's half of the exchange is already legible: it flinches
        // from the attacker's damage, and the attacker flinches from the blocker's own
        // damage group. Lunging is for the aggressor. A burn spell must not lunge
        // either, hence the combat check rather than just testing for a creature.
        final boolean striking = sourcePanel != null && g.source.isAttacking();

        // A striking attacker gets one step per target so they play in sequence;
        // everything else resolves as a single step with its hits shown together.
        AnimationStep shared = striking ? null : new AnimationStep("damage:" + g.source.getName());
        for (final GameEntityView target : targets) {
            final Point to = target instanceof CardView cv ? centreOf(cv) : avatarCentre((PlayerView) target);
            if (to == null) {
                continue;
            }
            final AnimationStep step = striking
                    ? new AnimationStep("strike:" + g.source.getName())
                    : shared;
            if (striking) {
                // The card itself crosses the gap, so sparks only at the point of
                // contact - a beam would be drawing the same journey a second time.
                step.add(new ImpactAnim(to, palette, g.total, IMPACT_MS, IMPACT_TRIGGER));
            } else if (from != null) {
                // Nothing moves, so the effect has to travel on its own.
                step.add(new BeamAnim(from, to, palette, g.total, 460));
            }
            if (target instanceof CardView cv) {
                final CardPanel tp = findPanel(cv);
                if (tp != null && from != null) {
                    step.add(PanelAnim.flinch(tp, toPanelSpace(tp, from), 260));
                }
                // If that blocker hit back, it recoils together with the attacker rather
                // than in a beat of its own.
                if (striking && counterHits.getOrDefault(g.source.getId(), Set.of()).contains(cv.getId())) {
                    step.add(PanelAnim.flinch(sourcePanel, toPanelSpace(sourcePanel, to), 260));
                }
            }
            if (striking) {
                step.add(PanelAnim.lunge(sourcePanel, toPanelSpace(sourcePanel, to), LUNGE_REACH, LUNGE_MS));
                queue.enqueue(step);
            }
        }
        if (shared != null && !shared.isEmpty()) {
            queue.enqueue(shared);
        }
        clock.start();
    }

    /**
     * The things a source damaged, in the order it reached them: blockers in damage
     * assignment order first, then anything else, then players.
     * <p>
     * The damage events themselves cannot supply this - they are read out of a
     * {@code HashBasedTable}, so they arrive in hash order. The real sequence comes from
     * the combat view, which reports a blocked attacker's blockers in the attacking
     * player's chosen damage assignment order.
     */
    private List<GameEntityView> orderTargets(final DamageGroup g) {
        final List<GameEntityView> ordered = new ArrayList<>(g.targetCount());
        final List<CardView> remaining = new ArrayList<>(g.cardTargets);

        final CombatView combat = matchUI.getGameView() == null ? null : matchUI.getGameView().getCombat();
        if (combat != null && g.source.isAttacking()) {
            final FCollectionView<CardView> blockers = combat.getBlockers(g.source);
            if (blockers != null) {
                for (final CardView blocker : blockers) {
                    if (remaining.remove(blocker)) {
                        ordered.add(blocker);
                    }
                }
            }
        }
        // Anything damaged that is not a blocker of this attacker keeps its arrival order.
        ordered.addAll(remaining);
        // Players last: trample overflow only reaches them once the blockers are through.
        ordered.addAll(g.playerTargets);
        return ordered;
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
