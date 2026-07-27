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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import forge.card.MagicColor;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.EventValueChangeType;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventManaPool;
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

    /** Distinct targets an untargeted source must hit before its damage reads as a sweep. */
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
    /** Zone each arriving card came out of; null means it was created, i.e. a token. */
    private final Map<Integer, ZoneType> arrivedFrom = new HashMap<>();

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
        clock.setUserSpeed(FModel.getPreferences().getPrefInt(FPref.UI_ANIMATION_SPEED) / 100f);
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
        soundThread.shutdownNow();
        queue.skipAll();
        departing.clear();
        arriving.clear();
        arrivedFrom.clear();
        awaitingOrigin.clear();
        pendingDamage.clear();
        synchronized (pendingCasts) {
            pendingCasts.clear();
        }
    }

    /**
     * Hold a UI refresh back until the animations already queued have played.
     * <p>
     * This is what makes the buffer a buffer. Without it the queue only delayed the
     * <em>animations</em> - the board itself was still repainted the moment the game
     * thread got round to it, so a creature vanished while its death was still playing
     * and an attacker's victim updated before the blow landed.
     * <p>
     * The refresh becomes a step of its own at the back of the queue, so it happens
     * after everything currently in flight and before anything queued later. Ordering is
     * preserved either way, and the skip hotkey runs the whole backlog at once, so the
     * board still converges on the real game state.
     *
     * @return true if the refresh was taken over, meaning the caller must not run it.
     */
    public boolean defer(final String label, final Runnable refresh) {
        if (refresh == null || !isEnabled() || queue.isIdle()) {
            return false;
        }
        queue.enqueue(new AnimationStep(label).after(refresh));
        clock.start();
        return true;
    }

    /**
     * Plays sound off the EDT, in order, one clip at a time.
     * <p>
     * Needed because {@code AudioClip.play} sleeps on whichever thread calls it when the
     * same clip is already running, to keep a burst of identical sounds from merging.
     * That is harmless on the game thread, which is where sound normally comes from, but
     * would stall the display if a queued clip were played from the EDT.
     */
    private final ExecutorService soundThread = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "Animation sound");
        t.setDaemon(true);
        return t;
    });

    /**
     * Hold a sound back until the animation it belongs to is on screen.
     *
     * @return true if the sound was taken over, meaning the caller must not play it.
     */
    public boolean deferSound(final Runnable playSound) {
        if (playSound == null) {
            return false;
        }
        return defer("sound", () -> soundThread.execute(playSound));
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
                // Read here, on the game thread as the blow lands, not at flush time.
                recordDamage(e.source(), e.card(), null, e.amount(),
                        e.source() != null && e.source().isAttacking());
            } else if (ev instanceof GameEventPlayerDamaged e) {
                // This one states outright whether it was combat damage.
                recordDamage(e.source(), null, e.target(), e.amount(), e.combat());
            } else if (ev instanceof GameEventCardChangeZone e) {
                recordZoneChange(e);
            } else if (ev instanceof GameEventSpellAbilityCast e) {
                recordCast(e);
            } else if (ev instanceof GameEventSpellResolved e) {
                resolveCast(e);
            } else if (ev instanceof GameEventSpellRemovedFromStack e) {
                forgetCast(e.sa());
            } else if (ev instanceof GameEventManaPool e) {
                recordManaAdded(e);
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
        if (TRACE_ARRIVALS) {
            System.out.println("[anim] zoneChange " + card.getName() + " id=" + card.getId()
                    + " " + from + " -> " + to);
        }
        synchronized (this) {
            // Only the two ends of the battlefield matter: something arriving gets
            // particles run to it, something leaving fades out.
            if (to == ZoneType.Battlefield) {
                arriving.add(card.getId());
                arrivedFrom.put(card.getId(), from);
                // The panel may already exist - see awaitZoneChange. Settle it on the EDT,
                // which is where panels may be touched.
                FThreads.invokeInEdtLater(() -> claimAwaitingPanel(card, from));
                if (from == ZoneType.Hand) {
                    // A land: its origin is the card still sitting in hand, so the hand
                    // panel has to be measured before the zone refresh takes it away.
                    departing.put(card.getId(), ZoneType.Battlefield);
                }
            } else if (from == ZoneType.Battlefield) {
                departing.put(card.getId(), to);
            }
        }
    }

    // ------------------------------------------------------------------ spell resolution

    /** What a spell was aimed at, remembered from cast time so it can be shown resolving. */
    private static final class CastRecord {
        private final CardView source;
        private final List<CardView> cardTargets = new ArrayList<>(2);
        private final List<PlayerView> playerTargets = new ArrayList<>(1);
        /**
         * Where each targeted card was when the spell was cast.
         * <p>
         * Needed because a spell that destroys what it targets has already removed the
         * card's panel by the time it resolves, so asking then gives nothing to draw to -
         * removal spells drew no beam at all and fell through to the catch-all spark.
         */
        private final Map<Integer, Point> targetPoints = new HashMap<>();

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
        // Show it being put on the stack: out of the permanent whose ability it is, or
        // out of the player who cast it. This is the half of the story the stack itself
        // cannot tell, and it happens once, here, at cast time.
        final CardView host = si.getSourceCard();
        final PlayerView activator = si.getActivatingPlayer();
        FThreads.invokeInEdtLater(() -> {
            enqueueOntoStack(host, activator);
            // Measure the targets now, while they are all still on the board.
            for (final CardView c : rec.cardTargets) {
                final Point at = centreOf(c);
                if (at != null) {
                    rec.targetPoints.put(c.getId(), at);
                }
            }
        });

        // Recorded even with no targets: an untargeted ability still resolves, and gets
        // a pulse at its source rather than a line to anywhere.
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

    /**
     * Show a spell or ability arriving on the stack, out of whatever put it there.
     * <p>
     * A permanent's own activated or triggered ability comes out of that permanent; a
     * spell a player casts comes out of the player, since the card was in a hand nobody
     * else can see. This is the half of the story the stack cannot tell, and it plays
     * once, here, when the thing is put there - after which the stack is the origin for
     * everything the resolution goes on to do.
     */
    private void enqueueOntoStack(final CardView host, final PlayerView activator) {
        final Point to = stackAnchor();
        Point from = centreOf(host);
        if (from == null) {
            from = avatarCentre(activator);
        }
        if (from == null || to == null) {
            return;
        }
        queue.enqueue(new AnimationStep("cast:" + (host != null ? host.getName() : "ability"))
                .add(new BeamAnim(from, to, CardColors.of(host, canShow(host)), 1f, 420)));
        clock.start();
    }

    /**
     * Whether resolving this card puts it onto the battlefield.
     * <p>
     * A creature, artifact, enchantment or planeswalker spell resolves into play, and
     * its arrival animation already draws the stack-to-battlefield beam. Sparking as
     * well would be a second, redundant flourish on top of it - and the spark lands on
     * the card itself, which by then is sitting on the battlefield, so it reads as the
     * effect happening in the wrong place entirely.
     * <p>
     * Read off the card rather than inferred from what appears afterwards, so it needs
     * no correlation between the resolve event and the zone change that follows it.
     */
    private static boolean becomesPermanent(final CardView card) {
        try {
            return card != null && card.getCurrentState() != null
                    && card.getCurrentState().getType() != null
                    && card.getCurrentState().getType().isPermanent();
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private void enqueueResolution(final CastRecord rec) {
        // Everything a resolution does comes out of the stack, because that is where the
        // spell or ability is. Its trip out of its source was already shown when it was
        // put there, so repeating that here would tell the same half of the story twice.
        final Point from = stackAnchor();
        final List<Color> palette = CardColors.of(rec.source, canShow(rec.source));
        final AnimationStep step = new AnimationStep("resolve:" + rec.source.getName());

        // One beam per target however many there are, the way the targeting arrows draw
        // one arrow per target. A spell that names five creatures is still hitting five
        // specific creatures, and sweeping the board instead would claim it hit
        // everything. A sweep is for effects with no targets at all.
        for (final CardView c : rec.cardTargets) {
            // Live position first, so a target that moved is still followed; the position
            // taken at cast time as a fallback, for one the spell has just destroyed.
            Point to = centreOf(c);
            if (to == null) {
                to = rec.targetPoints.get(c.getId());
            }
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
        if (step.isEmpty() && !becomesPermanent(rec.source)) {
            // The catch-all: nothing targeted, and nothing about to enter play either, so
            // the ability simply did its work. Sparked at the source card if it is still
            // on the board - a prowess trigger, say - and at the stack if it is not.
            final Point at = centreOf(rec.source);
            step.add(new ImpactAnim(at != null ? at : from, palette, 2f, 420, 0f));
        }
        if (step.isEmpty()) {
            return; // a permanent spell; its arrival draws the beam to where it lands
        }
        queue.enqueue(step);
        clock.start();
    }

    /**
     * Show a permanent arriving: particles run from wherever it came from to the slot it
     * will occupy, and only when they land does the card itself appear.
     * <p>
     * This replaces carrying the card across the board. The particles say the same thing
     * without a second copy of the card fighting the real panel for the same space, and
     * because it is a queued step the card genuinely waits for them to finish.
     */
    /** Panels built before their zone change arrived, waiting to learn where they came from. */
    private final Map<Integer, CardPanel> awaitingOrigin = new HashMap<>();

    /**
     * Hold a newly built panel until its zone change turns up.
     * <p>
     * Blanked straight away so it cannot flash before its animation, and released by a
     * safety timer if the event never comes - a card must never be left invisible
     * because of a missing animation.
     */
    private void awaitZoneChange(final int cardId, final CardPanel panel) {
        panel.setRenderAlpha(0f);
        panel.repaint();
        synchronized (this) {
            awaitingOrigin.put(cardId, panel);
        }
        clock.addFree(new Anim(400) {
            @Override
            protected void update(final float t) {
            }

            @Override
            protected void onEnd() {
                final CardPanel stranded;
                synchronized (MatchAnimator.this) {
                    stranded = awaitingOrigin.remove(cardId);
                }
                if (stranded != null) {
                    stranded.clearRenderTransform();
                    stranded.repaint();
                }
            }
        });
    }

    /** The zone change caught up with a panel already built; animate it now. */
    private void claimAwaitingPanel(final CardView card, final ZoneType from) {
        final CardPanel panel;
        synchronized (this) {
            panel = awaitingOrigin.remove(card.getId());
            arriving.remove(card.getId());
            arrivedFrom.remove(card.getId());
        }
        if (panel != null && panel.getCard() != null) {
            enqueueArrival(panel, card, originFor(from, card));
        }
    }

    /** Set true to trace why a card entering play did or did not get an arrival beam. */
    private static final boolean TRACE_ARRIVALS = true;

    private void enqueueArrival(final CardPanel panel, final CardView card, final Point origin) {
        if (TRACE_ARRIVALS) {
            System.out.println("[anim] arrival " + card.getName()
                    + " origin=" + origin + " dest=" + centreOf(panel)
                    + " parentShowing=" + (panel.getParent() != null && panel.getParent().isShowing())
                    + " layerShowing=" + layer.isShowing()
                    + " bounds=" + panel.getX() + "," + panel.getY()
                    + " " + panel.getWidth() + "x" + panel.getHeight());
        }
        // Hidden here and now, not when the step reaches the front of the queue. The
        // panel is made visible by the zone refresh on a later EDT pass, so anything
        // later than this lets a frame or two of the card through - it appeared, vanished
        // again, then animated.
        panel.setRenderAlpha(0f);
        panel.repaint();

        // Always a queued step, even with nowhere to draw a beam from. The reveal has to
        // be the step's own ending or a card can be left hidden: the panel was blanked
        // above, and only this puts it back.
        final Point dest = centreOf(panel);
        final AnimationStep step = new AnimationStep("arrive:" + card.getName())
                .after(() -> {
                    panel.clearRenderTransform();
                    clock.addFree(PanelAnim.fadeIn(panel, 320));
                });
        if (origin != null && dest != null) {
            step.add(new BeamAnim(origin, dest, CardColors.of(card, canShow(card)), 1f, 520));
        }
        queue.enqueue(step);
        clock.start();
    }

    // ------------------------------------------------------------------ mana

    /**
     * Pulse the floating-mana readout in the colour that was just added, wherever it came
     * from - a tapped land, a mana ability, or something resolving off the stack.
     * <p>
     * Runs free of the queue rather than through it: mana usually appears while the
     * player is mid-payment, and holding the board back for it would put a pause in the
     * middle of tapping lands.
     */
    private void recordManaAdded(final GameEventManaPool e) {
        if (e.mode() != EventValueChangeType.Added || e.player() == null || e.colors() == null) {
            return;
        }
        final List<MagicColor.Color> colors = new ArrayList<>(e.colors());
        final PlayerView player = e.player();
        FThreads.invokeInEdtLater(() -> {
            for (final MagicColor.Color color : colors) {
                final Point at = manaLabelCentre(player, color);
                if (at != null) {
                    clock.addFree(new ImpactAnim(at, List.of(manaColor(color)), 1f, 380, 0f));
                }
            }
            clock.start();
        });
    }

    /** Centre of a player's readout for one mana colour, in overlay coordinates. */
    private Point manaLabelCentre(final PlayerView player, final MagicColor.Color color) {
        final VField field = fieldFor(player);
        if (field == null) {
            return null;
        }
        final JComponent label = field.getDetailsPanel().getManaLabel(color.ordinal());
        if (label == null || !label.isShowing() || !layer.isShowing()) {
            return null;
        }
        return SwingUtilities.convertPoint(label.getParent(),
                label.getX() + label.getWidth() / 2, label.getY() + label.getHeight() / 2, layer);
    }

    /** The spark colour for a mana colour, matching how cards of it are tinted. */
    private static Color manaColor(final MagicColor.Color color) {
        switch (color) {
            case WHITE: return new Color(254, 253, 244);
            case BLUE: return new Color(90, 146, 202);
            case BLACK: return new Color(140, 130, 140);
            case RED: return new Color(253, 66, 40);
            case GREEN: return new Color(22, 115, 69);
            default: return new Color(160, 166, 164);
        }
    }

    // ------------------------------------------------------------------ damage grouping

    /** One source's damage over a single burst, so area effects can be told from single hits. */
    private static final class DamageGroup {
        private final CardView source;
        private final List<CardView> cardTargets = new ArrayList<>(4);
        private final List<PlayerView> playerTargets = new ArrayList<>(2);
        private int total;
        /**
         * Whether this was a blow struck in combat, captured when the damage happened.
         * <p>
         * It cannot be read later: a {@link CardView} is live and the game thread keeps
         * updating it, so by the time the flush runs on the EDT combat may already have
         * ended and the attacker no longer say it is attacking.
         */
        private boolean combat;

        DamageGroup(final CardView source) {
            this.source = source;
        }

        int targetCount() {
            return cardTargets.size() + playerTargets.size();
        }
    }

    private void recordDamage(final CardView source, final CardView cardTarget,
            final PlayerView playerTarget, final int amount, final boolean combat) {
        if (source == null || amount <= 0) {
            return;
        }
        synchronized (pendingDamage) {
            final DamageGroup g = pendingDamage.computeIfAbsent(source.getId(), k -> new DamageGroup(source));
            g.combat |= combat;
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
            // Only an untargeted effect sweeps. A damage spell that names its victims
            // gets a beam to each of them however many there are; hitting several things
            // is not the same as hitting an area.
            if (g.targetCount() >= AOE_TARGET_THRESHOLD && !isTargeting(g.source)) {
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
        final boolean striking = g.combat;

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
            if (target instanceof PlayerView pv) {
                // A damaged player reacts too. Every player an effect hits gets one, so
                // something that burns all opponents visibly rocks each of them.
                step.add(playerFlinch(pv, palette));
            }
            if (target instanceof CardView cv) {
                final CardPanel tp = findPanel(cv);
                if (tp != null && from != null) {
                    step.add(PanelAnim.flinch(tp, toPanelSpace(tp, from), 260));
                }
                // If that blocker hit back, it recoils together with the attacker rather
                // than in a beat of its own.
                if (striking && sourcePanel != null
                        && counterHits.getOrDefault(g.source.getId(), Set.of()).contains(cv.getId())) {
                    step.add(PanelAnim.flinch(sourcePanel, toPanelSpace(sourcePanel, to), 260));
                }
            }
            if (striking) {
                if (sourcePanel != null) {
                    // Drawn on the overlay rather than by displacing the panel, so the
                    // attacker passes over its neighbours and can leave its own row
                    // instead of being clipped at the edge of its battlefield.
                    final CardSnapshot snap = CardSnapshot.capture(sourcePanel, layer);
                    step.add(snap != null
                            ? OverlayFlight.lunge(sourcePanel, snap, to, LUNGE_REACH, LUNGE_MS)
                            : PanelAnim.lunge(sourcePanel, toPanelSpace(sourcePanel, to), LUNGE_REACH, LUNGE_MS));
                }
                // Enqueued even with no panel to move: combat damage always reads as a
                // strike, never as something travelling across the board.
                queue.enqueue(step);
            }
        }
        if (shared != null && !shared.isEmpty()) {
            queue.enqueue(shared);
        }
        clock.start();
    }

    /**
     * Whether this card currently has something targeted on the stack.
     * <p>
     * Damage events carry no notion of targeting, so this distinguishes a sweeper from a
     * spell that merely names several victims - Pyroclasm from Cone of Flame. The cast
     * record is still present because damage is dealt during resolution, before the
     * resolved event that clears it.
     */
    private boolean isTargeting(final CardView source) {
        if (source == null) {
            return false;
        }
        synchronized (pendingCasts) {
            for (final CastRecord rec : pendingCasts.values()) {
                if (rec.source != null && rec.source.getId() == source.getId() && rec.targetCount() > 0) {
                    return true;
                }
            }
        }
        return false;
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
        // And every player it burned, so an effect aimed at the whole table visibly
        // rocks each of them rather than only sweeping their boards.
        for (final PlayerView p : g.playerTargets) {
            step.add(playerFlinch(p, palette));
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
        if (to == ZoneType.Battlefield || to == ZoneType.Stack) {
            // Beginning a cast, or crossing onto the table. Neither is a departure; both
            // reappear under their own animation.
            return;
        }
        final CardSnapshot snap = CardSnapshot.capture(panel, layer);
        if (snap == null) {
            return;
        }
        // The real panel is about to vanish, so the ghost runs free of the queue rather
        // than holding up the events behind it.
        clock.addFree(GhostAnim.fadeOut(snap, 520));
    }

    /**
     * Called after a zone refresh has created panels, so anything new fades up rather
     * than popping into place.
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
            final ZoneType from;
            final boolean expected;
            synchronized (this) {
                expected = arriving.remove(card.getId());
                from = arrivedFrom.remove(card.getId());
            }
            if (TRACE_ARRIVALS) {
                System.out.println("[anim] panelAdded " + card.getName()
                        + " expected=" + expected + " from=" + from + " token=" + card.isToken());
            }
            if (expected) {
                enqueueArrival(panel, card, originFor(from, card));
            } else {
                // The zone change has not reached us yet. It cannot simply be waited for
                // in order, because which of the two arrives first is a race: the card is
                // put in its new zone before the event announcing it is fired, so a
                // refresh already queued on the EDT can build the panel first. Park the
                // panel and let whichever side finishes second start the animation.
                awaitZoneChange(card.getId(), panel);
            }
        }
    }

    /**
     * Slide a card that layout has just repositioned from where it used to be.
     * <p>
     * Runs free of the queue: a reflow accompanies whatever caused it, so making it wait
     * its turn would show the board rearranging itself well after the card that displaced
     * everything had already settled.
     */
    public void reflow(final CardPanel panel, final int dx, final int dy, final double scale) {
        if (panel == null || !isEnabled()) {
            return;
        }
        // A card being hidden for its own arrival must not be dragged into a reflow; it
        // is already where it belongs and is only waiting for its particles.
        if (panel.getRenderAlpha() <= 0f) {
            return;
        }
        clock.addFree(PanelAnim.reflow(panel, dx, dy, scale, 260));
    }

    /**
     * Where a permanent's arrival particles should start.
     *
     * @param from the zone it came out of, or null if it was created rather than moved.
     */
    private Point originFor(final ZoneType from, final CardView card) {
        if (from == null || from == ZoneType.Stack) {
            // Resolved off the stack, or created by something that was on it - a token.
            // Either way the stack is what produced it.
            return stackAnchor();
        }
        // Entered play directly out of a hand, library, graveyard or exile without ever
        // being on the stack. Those zones are the player's, and drawing it out of the
        // player reads correctly from both sides of the table - which the hand does not,
        // since you cannot see your opponent's.
        final PlayerView owner = card == null ? null
                : (card.getOwner() != null ? card.getOwner() : card.getController());
        final Point avatar = avatarCentre(owner);
        return avatar != null ? avatar : stackAnchor();
    }

    // ------------------------------------------------------------------ geometry

    /** Where a resolving spell's effects originate: the stack it is resolving off. */
    private Point stackAnchor() {
        try {
            final Rectangle bounds = boundsInLayer(matchUI.getCStack().getView().getParentCell());
            if (bounds != null && bounds.width > 0) {
                return new Point(bounds.x + bounds.width / 2, bounds.y + Math.min(90, bounds.height / 2));
            }
        } catch (final RuntimeException e) {
            // The stack panel may not be laid out yet.
        }
        return new Point(layer.getWidth() / 2, layer.getHeight() / 3);
    }

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
        if (panel == null || !layer.isShowing()) {
            return null;
        }
        // Deliberately the parent's isShowing, not the panel's. A component reports
        // itself as not showing until it has a peer, which it only gets when the
        // hierarchy is validated - and a card panel is laid out and handed to us before
        // that happens. Testing the panel meant every newly created card measured as
        // off-screen, so nothing entering play ever got a beam drawn to it.
        final java.awt.Container parent = panel.getParent();
        if (parent == null || !parent.isShowing()) {
            return null;
        }
        return SwingUtilities.convertPoint(parent,
                panel.getX() + panel.getWidth() / 2, panel.getY() + panel.getHeight() / 2, layer);
    }

    /**
     * A struck reaction over a player's avatar, or null if it is not on screen.
     *
     * @param palette the damaging card's colours, so the wash matches the effect.
     */
    private Anim playerFlinch(final PlayerView player, final List<Color> palette) {
        final VField field = fieldFor(player);
        final Rectangle area = field == null ? null : boundsInLayer(field.getAvatarArea());
        if (area == null || area.width <= 0) {
            return null;
        }
        return new RegionFlinch(area, palette.isEmpty() ? Color.RED : palette.get(0), 420);
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

    /** Convert an overlay point into the coordinate space of a panel's parent. */
    public Point toPanelSpace(final CardPanel panel, final Point overlayPoint) {
        if (overlayPoint == null || panel == null || !panel.isShowing()) {
            return new Point(0, 0);
        }
        return SwingUtilities.convertPoint(layer, overlayPoint, panel.getParent());
    }
}
