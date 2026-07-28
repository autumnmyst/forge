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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import forge.card.MagicColor;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.combat.CombatView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.EventValueChangeType;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.event.GameEventManaPool;
import forge.game.event.GameEventPlayerCounters;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventReplacementApplied;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventSpellResolving;
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
    /**
     * Permanents under one controller that a single effect must touch for it to read as
     * covering that player's board.
     * <p>
     * Lower than {@link #AOE_TARGET_THRESHOLD} because there is no ambiguity to guard
     * against here: damage counts targets, where naming three creatures is not the same
     * as sweeping, whereas this counts permanents an effect actually modified, and an
     * effect that modified two of the three creatures someone controls is a board effect
     * on a board that only had three creatures.
     */
    private static final int BOARD_EFFECT_THRESHOLD = 2;
    /**
     * Damage looks the same whatever dealt it.
     * <p>
     * The card's own colours are reserved for a permanent being modified, so that the two
     * read as different kinds of event: a creature that flashes orange took damage, and a
     * creature that flashes its own colours was changed.
     */
    private static final List<Color> DAMAGE_PALETTE =
            List.of(new Color(255, 96, 32), new Color(255, 172, 64));
    /**
     * A replacement effect applying gets its own unmistakable yellow, distinct from any
     * combination of card colours - something happened other than what was announced,
     * and that is worth reading at a glance.
     */
    private static final List<Color> REPLACEMENT_PALETTE =
            List.of(new Color(255, 226, 74), new Color(255, 250, 190));
    /**
     * How big a spark on a card is. Scales the spray outwards and the number of sparks
     * with it, so a larger value reads as a wider flash rather than a denser one.
     * <p>
     * Unlike a damage impact, which is sized by how hard it hit, a card changing has no
     * magnitude to scale by - these are fixed, and are the dial to turn if the sparks
     * want to be more or less prominent.
     */
    private static final float MODIFY_SPARK = 3.0f;
    /** Slightly bigger again, since a replacement effect is the rarer, louder event. */
    private static final float REPLACEMENT_SPARK = 3.4f;
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
    /** How long a beam takes end to end. */
    private static final long BEAM_MS = 460L;
    /** A permanent's arrival trail, a little longer since it crosses the whole board. */
    private static final long ARRIVAL_BEAM_MS = 520L;
    /** A spell or ability travelling to the stack. */
    private static final long CAST_BEAM_MS = 420L;
    /**
     * Where in a beam its head reaches the destination, as a fraction of its length.
     * Matches {@code BeamAnim.EMIT_FRACTION}: the beam emits over the first part of its
     * run and spends the rest letting the last sparks land, so the effect arrives well
     * before the animation is over.
     */
    private static final float BEAM_ARRIVAL = 0.55f;

    /**
     * Hold an animation back so it fires when the effect reaches it, tolerating a null.
     * <p>
     * The nulls come from the flinch helpers, which return one when the subject is not on
     * screen; threading the delay through every call site instead would repeat that check
     * everywhere.
     */
    private static Anim delayed(final Anim anim, final long delayMs) {
        return anim == null ? null : anim.delayedBy(delayMs);
    }

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
    /**
     * The queued arrival of each card still on its way in, so a change to a card that has
     * not landed yet can be shown when it does. Dropped as each one plays.
     */
    private final Map<Integer, AnimationStep> pendingArrivals = new HashMap<>();

    /** Damage grouped by source, flushed on the next EDT pass. See {@link #flushDamage()}. */
    private final Map<Integer, DamageGroup> pendingDamage = new LinkedHashMap<>();
    private boolean damageFlushQueued;

    public MatchAnimator(final CMatchUI matchUI) {
        this.matchUI = matchUI;
        layer.setQueue(queue);
        clock.setSpeedSource(() -> userSpeed());
        clock.setOnIdle(() -> {
            releaseAllLife();
            // Sparks still waiting for an arrival that never came. Dropping them is right:
            // the display has caught up, so there is nothing left to explain.
            synchronized (this) {
                sparksAwaitingArrival.clear();
            }
            thawAllCards();
            showAllStackItems();
            // The display has caught up, so damage no longer owns anything's appearance
            // and the next change to these cards is a change in its own right.
            damageClaimed.clear();
        });
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
        clock.setUserSpeed(userSpeed());
    }

    private static float userSpeed() {
        try {
            final int pct = FModel.getPreferences().getPrefInt(FPref.UI_ANIMATION_SPEED);
            return pct <= 0 ? 1f : pct / 100f;
        } catch (final RuntimeException e) {
            return 1f;
        }
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
        pendingArrivals.clear();
        sparksAwaitingArrival.clear();
        awaitingOrigin.clear();
        pendingDamage.clear();
        fingerprints.clear();
        stagedLife.clear();
        damageClaimed.clear();
        stackNotYetShown.clear();
        synchronized (stackLingering) {
            stackLingering.clear();
        }
        thawAllCards();
        resolving = null;
        synchronized (pendingMods) {
            pendingMods.clear();
            modSource = null;
            modScope = null;
        }
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
        return defer("sound", () -> {
            // The match can end while clips are still queued behind animations, and the
            // executor is shut down as part of that teardown. A sound nobody will hear
            // is not worth an exception.
            if (!soundThread.isShutdown()) {
                soundThread.execute(playSound);
            }
        });
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
                // Blocking counts as combat just as much as attacking does: this event
                // carries no combat flag, and testing only for attacking meant a
                // blocker's damage was taken for a spell's and drawn as a beam crawling
                // back across a gap the blocker never crossed.
                final CardView src = e.source();
                recordDamage(src, e.card(), null, e.amount(),
                        src != null && (src.isAttacking() || src.isBlocking()));
            } else if (ev instanceof GameEventPlayerDamaged e) {
                // This one states outright whether it was combat damage.
                recordDamage(e.source(), null, e.target(), e.amount(), e.combat());
            } else if (ev instanceof GameEventCardChangeZone e) {
                recordZoneChange(e);
            } else if (ev instanceof GameEventSpellAbilityCast e) {
                recordCast(e);
            } else if (ev instanceof GameEventSpellResolving e) {
                resolving = new Resolution(e.source(), isPermanentSpell(e.spell(), e.source()));
            } else if (ev instanceof GameEventSpellResolved e) {
                resolveCast(e);
            } else if (ev instanceof GameEventSpellRemovedFromStack e) {
                forgetCast(e.sa());
            } else if (ev instanceof GameEventManaPool e) {
                recordManaAdded(e);
            } else if (ev instanceof GameEventCardStatsChanged e) {
                for (final CardView c : e.cards()) {
                    recordModified(c);
                }
            } else if (ev instanceof GameEventCardCounters e) {
                // Counters state their own before and after, so unlike a stats change
                // there is nothing to verify - this is always a real modification.
                recordModified(e.card(), true);
            } else if (ev instanceof GameEventReplacementApplied e) {
                recordReplacement(e.host());
            } else if (ev instanceof GameEventPlayerLivesChanged e) {
                recordPlayerChange(e.player(), e.oldLives(), e.newLives());
            } else if (ev instanceof GameEventPlayerCounters e) {
                recordPlayerChange(e.receiver(), 0, 0);
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
                // Claim the arrival's place in the queue now, on the game thread, while
                // the card is only just entering. Its own animation cannot be built until
                // a panel exists, which happens on a later EDT pass and behind a deferred
                // zone refresh - by which time the permanent's enters-the-battlefield
                // trigger has long since queued its own trail and would play first, so
                // the ability left a card that had not arrived yet.
                //
                // Queued here, in the order the card actually entered, and left to work
                // out where it is going when it plays. Panels for arriving cards are
                // built as soon as the zone update reaches the UI rather than behind the
                // deferred refresh, so by the time this step runs the card has a place on
                // the board even though it has none right now.
                //
                // Everything the arrival goes on to trigger is announced after this, so it
                // queues after this, and the ordering holds however far the game runs
                // ahead of the display.
                final AnimationStep arrival = arrivalStep(card, from);
                // Kept hold of so anything that happened to this card before it got here
                // can be hung off its arrival rather than played over an empty slot.
                final int id = card.getId();
                pendingArrivals.put(id, arrival);
                arrival.then(() -> {
                    synchronized (MatchAnimator.this) {
                        pendingArrivals.remove(id, arrival);
                    }
                });
                // Anything that changed this card before it was announced as entering has
                // been waiting for exactly this step.
                claimHeldSparks(id, arrival);
                queue.enqueue(arrival);
                clock.start();
                FThreads.invokeInEdtLater(() -> claimAwaitingPanel(card, from));
                if (from == ZoneType.Hand) {
                    // A land: its origin is the card still sitting in hand, so the hand
                    // panel has to be measured before the zone refresh takes it away.
                    departing.put(card.getId(), ZoneType.Battlefield);
                    // And taken out of the hand now rather than by the deferred refresh,
                    // which waits behind every animation this play set off. A land with a
                    // trigger could sit in hand for the whole of its own arrival, so the
                    // card was in two places at once - a basic land only looked right
                    // because nothing was queued to hold the refresh up.
                    FThreads.invokeInEdtLater(() -> removeFromHand(card));
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
        /** Other stack entries this was aimed at - what a counterspell names. */
        private final List<Integer> stackTargets = new ArrayList<>(1);
        /** The stack entry this put there, so it can be released if it never resolves. */
        private int stackItemId;
        /**
         * The entry itself, kept so it can still be drawn after the game has taken it off
         * the stack and there is nothing live left to read it from.
         */
        private StackItemView stackItem;
        /**
         * The entry that sat directly beneath this one when it was put there.
         * <p>
         * Recorded at cast time because it is stable: the stack only ever grows and
         * shrinks at the top, so whatever was underneath an entry stays underneath it for
         * as long as the entry exists. That is what lets a resolved entry be drawn back
         * into its old position rather than simply piled on top.
         */
        private Integer belowId;

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
        // Claimed on the game thread, before the EDT can queue anything else. The cost of
        // an activated ability is paid before the ability reaches the stack, so a
        // planeswalker's loyalty counters are announced ahead of this event; without a
        // slot held here the loyalty had already changed by the time the trail explaining
        // it set off.
        //
        // Not for a permanent that is still entering, though. A held slot blocks the
        // board refresh queued behind it, and for a permanent arriving right now that
        // refresh is what builds its panel and draws its arrival - so holding one would
        // stall until it timed out, and the ability would still be leaving a card that
        // had not appeared. Those queue in order behind the arrival instead.
        final boolean hostArriving;
        synchronized (this) {
            hostArriving = host != null && arriving.contains(host.getId());
        }
        final AnimationStep slot;
        if (hostArriving) {
            slot = null;
        } else {
            slot = new AnimationStep(
                    "cast:" + (host != null ? host.getName() : "ability")).reserved();
            queue.enqueue(slot);
            clock.start();
        }
        // Held out of the stack list until its trail gets there, so the stack fills in one
        // entry at a time rather than showing everything the game has already pushed.
        rec.stackTargets.addAll(e.targetStackItems());
        final int stackItemId = si.getId();
        rec.stackItemId = stackItemId;
        rec.stackItem = si;
        rec.belowId = idBelowOnStack(si);
        if (isEnabled()) {
            stackNotYetShown.add(stackItemId);
        }
        FThreads.invokeInEdtLater(() -> {
            enqueueOntoStack(host, activator, slot, stackItemId);
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
        final CastRecord rec;
        synchronized (pendingCasts) {
            rec = pendingCasts.remove(sa);
        }
        if (rec == null) {
            return;
        }
        // Countered, or otherwise taken off the stack without resolving. It still leaves
        // in queue order rather than the instant the game says so, so a counterspell's
        // trail reaches a stack that still has something on it.
        final Resolution scope = resolving;
        if (scope != null) {
            // Something is resolving, and this is what it did - almost always a
            // counterspell naming this one. Handed to that resolution so the entry goes
            // when its trail arrives, rather than in a step of its own queued before the
            // resolution has even been announced, which took it away first and left the
            // trail arriving at a gap.
            lingerStackItem(rec);
            scope.unstacked.add(rec.stackItemId);
            return;
        }
        unstackInOrder(rec, "countered:" + nameOf(rec.source));
    }

    /**
     * Keep an entry in the list past the game removing it, and take it out again when the
     * queue reaches this point.
     * <p>
     * An empty step costs no wall time - the drain loop takes several of them per frame -
     * so this does not slow the stack down. All it does is place the removal among the
     * animations rather than ahead of all of them, which is the difference between an
     * entry that disappears while its trail is still on its way and one that leaves when
     * its trail does.
     */
    private void unstackInOrder(final CastRecord rec, final String label) {
        lingerStackItem(rec);
        final AnimationStep step = new AnimationStep(label);
        popsStackEntry(step, rec.stackItemId);
        queue.enqueue(step);
        clock.start();
    }

    // ------------------------------------------------------------------ resolution scope

    /**
     * The stack object currently resolving.
     * <p>
     * Everything a resolution does is announced between the resolving and resolved
     * events, and much of it names no source: a life total moving, counters being
     * placed. This is what those changes are credited to.
     */
    private static final class Resolution {
        private final CardView source;
        /**
         * Whether this is a permanent being cast, rather than an ability or a spell that
         * does something.
         * <p>
         * A creature spell resolving does not modify anything - it puts a card onto the
         * battlefield, and if other permanents change it is because of that card's own
         * static ability. Nothing travelled, so nothing should be drawn travelling. An
         * ability, or a sorcery like a mass pump, genuinely does reach out from the stack
         * and does get a trail.
         */
        private final boolean permanentSpell;
        /**
         * What this resolution has already run a trail to.
         * <p>
         * A spell that targets a player and then damages it is announced three times -
         * as a target, as damage, and as a life total moving - and each would draw the
         * same stack-to-player trail. Whichever gets there first claims the destination
         * and the others draw nothing.
         * <p>
         * Concurrent because the claims come from both threads: the game thread as the
         * events arrive, the EDT as the queued steps are built.
         */
        private final Set<String> reached = ConcurrentHashMap.newKeySet();
        /**
         * Stack entries this resolution took off the stack - what a counterspell countered.
         * They leave the list when this resolution's own step has played, so the trail is
         * seen reaching them first.
         */
        private final Set<Integer> unstacked = ConcurrentHashMap.newKeySet();

        Resolution(final CardView source, final boolean permanentSpell) {
            this.source = source;
            this.permanentSpell = permanentSpell;
        }
    }

    /** Written and read on the game thread; volatile only so teardown can clear it. */
    private volatile Resolution resolving;

    /**
     * Whether what is resolving is a permanent being cast, as opposed to an ability or a
     * spell that does something on its way past.
     */
    private static boolean isPermanentSpell(final SpellAbilityView sa, final CardView source) {
        try {
            return sa != null && sa.isSpell() && source != null
                    && source.getCurrentState() != null
                    && source.getCurrentState().getType() != null
                    && source.getCurrentState().getType().isPermanent();
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /** Stable key for a trail destination, so the same target cannot be drawn to twice. */
    private static String reachKey(final GameEntityView entity) {
        if (entity instanceof CardView c) {
            return "c" + c.getId();
        }
        if (entity instanceof PlayerView p) {
            return "p" + p.getId();
        }
        return "?";
    }

    /**
     * Claim a destination for the resolution in progress.
     *
     * @return true if nothing has drawn to it yet and the caller should go ahead.
     */
    private boolean claimTrail(final GameEntityView entity) {
        final Resolution r = resolving;
        return r == null || r.reached.add(reachKey(entity));
    }

    /**
     * As {@link #claimTrail}, for a destination that is a whole battlefield.
     *
     * @param scope taken explicitly, because board trails are built on the EDT after the
     *              resolution that caused them has already closed.
     */
    private boolean claimBoardTrail(final Resolution scope, final PlayerView player) {
        return scope == null || player == null || scope.reached.add("b" + player.getId());
    }

    /**
     * Whether what this card is doing right now is being done from the stack.
     * <p>
     * This is the whole of the origin rule. A spell, an activated ability or a triggered
     * ability is an object on the stack, and once it is there the stack is where its
     * effects come from - drawing them out of the card as well tells the same story
     * twice, and the card may not even be there any more. Only an effect that never used
     * the stack at all, which in practice means a mana ability, still acts from the card
     * it is printed on.
     */
    private boolean actsFromStack(final CardView source) {
        if (source == null) {
            return false;
        }
        final Resolution r = resolving;
        if (r != null && r.source != null && r.source.getId() == source.getId()) {
            return true;
        }
        synchronized (pendingCasts) {
            for (final CastRecord rec : pendingCasts.values()) {
                if (rec.source != null && rec.source.getId() == source.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Where a trail for this source should start, resolved on the EDT.
     * <p>
     * The decision itself is not made here - see {@link #actsFromStack}, which has to be
     * asked on the game thread while the stack still holds the object.
     */
    private Point trailOrigin(final CardView source, final boolean fromStack) {
        if (fromStack) {
            return stackAnchor();
        }
        final Point at = centreOf(source);
        // A card that acts outside the stack and is not on screen has nowhere sensible to
        // start from, so fall back rather than drop the effect.
        return at != null ? at : stackAnchor();
    }

    // ------------------------------------------------------------------ modifications

    /** A permanent this burst of events changed, and what kind of change it was. */
    private static final class ModRecord {
        private final CardView card;
        private boolean replacement;

        ModRecord(final CardView card) {
            this.card = card;
        }
    }

    /**
     * Modifications grouped over one burst, the same way damage is.
     * <p>
     * Grouped rather than sparked as they arrive because a single effect that changes
     * several permanents has to be recognised as covering a board, which cannot be told
     * from any one of its events.
     */
    private final Map<Integer, ModRecord> pendingMods = new LinkedHashMap<>();
    private boolean modFlushQueued;
    /** Whose effect this burst of modifications is, for the trail it draws. */
    private CardView modSource;
    private boolean modFromStack;
    /**
     * The resolution the burst belongs to, held so its board trails dedup against the
     * same set the rest of that resolution used. It cannot be read at flush time: the
     * resolution has closed by then.
     */
    private Resolution modScope;
    /** A permanent was joining the board as this burst began - a static taking hold. */
    private boolean modEntering;
    /** A permanent was leaving it - a static letting go. */
    private boolean modLeaving;

    /**
     * Whether anything is on its way off the battlefield right now.
     * <p>
     * Not simply whether {@code departing} has entries: a land being played is recorded
     * there too, so that its origin can be measured in hand before the refresh takes it
     * away, and that is an arrival rather than a departure.
     */
    private boolean anyLeavingBattlefield() {
        for (final ZoneType to : departing.values()) {
            if (to != ZoneType.Battlefield && to != ZoneType.Stack) {
                return true;
            }
        }
        return false;
    }

    /**
     * The characteristics each card was last seen with.
     * <p>
     * Needed because {@code GameEventCardStatsChanged} is not a statement that anything
     * changed. It is fired by {@code GameAction.checkStaticAbilities} for every card
     * touched by any static ability, on every state check - which is constantly, and for
     * most of the board. Sparking on the event itself would leave the table permanently
     * flickering, so each card is compared against how it last looked and only a real
     * difference counts.
     */
    private final Map<Integer, String> fingerprints = new ConcurrentHashMap<>();

    /**
     * What a card looks like, for the purpose of noticing it changed.
     * <p>
     * Damage is deliberately not part of this. Marking damage fires a stats-changed event
     * of its own, and damage already has its own spark in its own colour - counting it
     * here would spark a damaged creature twice, in two different colours, for one blow.
     */
    private static String fingerprint(final CardView card) {
        try {
            final CardStateView s = card.getCurrentState();
            if (s == null) {
                return "";
            }
            return s.getPower() + "/" + s.getToughness()
                    + "|" + s.getLoyalty() + "|" + s.getDefense()
                    + "|" + s.getType() + "|" + s.getKeywords().hashCode()
                    + "|" + card.getClassLevel();
        } catch (final RuntimeException e) {
            // A card mid-transform can report inconsistently; treat it as unchanged
            // rather than sparking on the inconsistency.
            return "";
        }
    }

    private void recordModified(final CardView card) {
        recordModified(card, false);
    }

    /**
     * @param certain true when the event already states a change happened, so the
     *                characteristics need not be compared.
     */
    private void recordModified(final CardView card, final boolean certain) {
        if (card == null || card.getZone() != ZoneType.Battlefield) {
            // Only a permanent on the battlefield has a place to spark.
            return;
        }
        if (!certain) {
            final String now = fingerprint(card);
            final String before = fingerprints.put(card.getId(), now);
            if (now.isEmpty() || now.equals(before)) {
                return;
            }
            if (before == null) {
                // First sight of this card, so there is nothing it can have changed from.
                // Without this every permanent any static ability touches would spark the
                // first time it was mentioned, on top of its own arrival.
                //
                // Cards on the battlefield are given their baseline when they arrive
                // rather than being left to fall in here, which is what made continuous
                // effects spark so unevenly: the first event naming a card was often the
                // one that changed it, and that change was the one being swallowed.
                return;
            }
        } else {
            fingerprints.put(card.getId(), fingerprint(card));
        }
        queueMod(card, false);
    }

    private void recordReplacement(final CardView host) {
        if (host == null || host.getZone() != ZoneType.Battlefield) {
            return;
        }
        queueMod(host, true);
    }

    private void queueMod(final CardView card, final boolean replacement) {
        if (!replacement && isBeingDamaged(card)) {
            // Damage owns the visual for anything it hits. A planeswalker taking damage
            // loses loyalty counters, so it is announced as a modification as well, and
            // would otherwise flash twice in two different colours for one blow.
            return;
        }
        // Held as the change is announced, not when its spark is built, because the panel
        // repaints every frame while anything on the board is animating and paints its
        // counters and icons straight off the live card. Every card in the burst, not
        // just the one that opened it.
        freezeCard(card);
        // Read before taking the other lock, and while they are still true: whether a
        // permanent is joining the board or leaving it is what says whether a static
        // ability is taking hold or letting go.
        final boolean entering;
        final boolean leaving;
        synchronized (this) {
            entering = !arriving.isEmpty();
            leaving = anyLeavingBattlefield();
        }
        final boolean first;
        synchronized (pendingMods) {
            final ModRecord m = pendingMods.computeIfAbsent(card.getId(), k -> new ModRecord(card));
            m.replacement |= replacement;
            first = !modFlushQueued;
            if (first) {
                modFlushQueued = true;
                // The first of a burst decides where the burst's trail comes from, while
                // the resolution that caused it is still open. By flush time it has closed.
                modScope = resolving;
                modSource = modScope != null ? modScope.source : null;
                modFromStack = modSource != null;
                modEntering = entering;
                modLeaving = leaving;
            }
        }
        if (first) {
            FThreads.invokeInEdtLater(this::flushMods);
        }
    }

    /**
     * Cards damage has claimed the visual for, until the display catches up.
     * <p>
     * Kept separately from {@link #pendingDamage} because that is emptied as soon as the
     * damage is turned into steps, while the changes damage causes are announced for some
     * time afterwards - a creature that traded blows is still having its state recomputed
     * well after the blows themselves. Consulting the pending map alone meant an attacker
     * picked up a modification spark of its own, in its own colours, in a step after the
     * strike, when the strike had already shown everything that happened to it.
     */
    private final Set<Integer> damageClaimed = ConcurrentHashMap.newKeySet();

    /** Whether damage has already claimed how this card's change is shown. */
    private boolean isBeingDamaged(final CardView card) {
        return damageClaimed.contains(card.getId());
    }

    /**
     * Spark everything this burst changed, and burst the board of any player who had
     * enough permanents changed for the effect to read as covering it.
     */
    private void flushMods() {
        final List<ModRecord> mods;
        final CardView source;
        final boolean fromStack;
        final Resolution scope;
        final boolean entering;
        final boolean leaving;
        synchronized (pendingMods) {
            modFlushQueued = false;
            if (pendingMods.isEmpty()) {
                return;
            }
            mods = new ArrayList<>(pendingMods.values());
            source = modSource;
            fromStack = modFromStack;
            scope = modScope;
            entering = modEntering;
            leaving = modLeaving;
            pendingMods.clear();
            modSource = null;
            modScope = null;
            modEntering = false;
            modLeaving = false;
        }

        // Whether this was an effect being applied or one wearing off. Only an
        // application sweeps a board: something being put onto everything at once is a
        // single act reaching that board, whereas a continuous effect ending is just the
        // board going back to how it was - several cards changing, no act reaching them.
        // So mass -1/-1 sweeps, and the same creatures reverting at cleanup does not.
        //
        // Something resolving says an effect was applied. So does a permanent joining the
        // board, which is how a static ability takes hold - and it is announced after the
        // spell that put it there has finished resolving, so there is no resolution left
        // to attribute it to. A permanent leaving says the opposite, and wins: a lord
        // dying shrinks a whole team without anything having reached them.
        final boolean applied = (scope != null || entering) && !leaving;

        // Whether anything actually travelled. A trail is for an effect that came from
        // somewhere else - off the stack, as an ability or as a spell that does something
        // on its way past. A permanent's own static ability came from the permanent, which
        // is already sitting on the board in plain view, so there is nowhere to draw from
        // and the sweep alone says it: Valley Floodcaller's trigger gets a trail, Bria's
        // static does not.
        final boolean travelled = applied && scope != null && !scope.permanentSpell;

        // The sparks are what the effect did once it arrived, so when a trail is drawn
        // they wait for it, and the sweep goes between the two.
        final Point origin = travelled ? trailOrigin(source, fromStack) : null;
        final long arriveMs = Math.round(BEAM_MS * BEAM_ARRIVAL);

        final AnimationStep step = new AnimationStep("modify");
        final Map<PlayerView, Integer> perController = new LinkedHashMap<>();
        final List<ModRecord> visible = new ArrayList<>(mods.size());
        final List<ModRecord> notLandedYet = new ArrayList<>(0);
        for (final ModRecord m : mods) {
            // A card can be changed before it is on screen - a planeswalker is given its
            // loyalty on the way in, and anything entering with counters or under a lord
            // is in the same position. Sparking now would flash at an empty slot and only
            // then animate the card turning up, so this waits for it to land. It still
            // counts towards the sweep below: the effect did reach that board.
            //
            // Asked before measuring, not after. A card that has not arrived has nowhere
            // to be measured to, so testing the position first threw these away as
            // unplaceable and the wait never happened.
            final boolean waiting = isAwaitingArrival(m.card);
            if (!waiting && centreOf(m.card) == null) {
                continue;
            }
            (waiting ? notLandedYet : visible).add(m);
            // A replacement effect firing is about the card it is printed on, not about
            // whatever effect happened to be resolving, so it is never counted towards a
            // board sweep and never takes the effect's colours.
            if (m.replacement) {
                continue;
            }
            final PlayerView controller = m.card.getController();
            if (controller != null) {
                perController.merge(controller, 1, Integer::sum);
            }
        }

        long sparkAt = 0L;
        if (applied) {
            final List<Color> palette = source != null
                    ? CardColors.of(source, canShow(source)) : CardColors.of(null, false);
            for (final Map.Entry<PlayerView, Integer> e : perController.entrySet()) {
                if (e.getValue() < BOARD_EFFECT_THRESHOLD) {
                    continue;
                }
                final Rectangle area = battlefieldBounds(e.getKey());
                if (area == null) {
                    continue;
                }
                long burstAt = 0L;
                // One trail per affected board rather than one per permanent, since what
                // happened was a single effect reaching that board. Skipped entirely for a
                // static ability, whose sweep starts straight away.
                if (origin != null && claimBoardTrail(scope, e.getKey())) {
                    step.add(new BeamAnim(origin,
                            new Point(area.x + area.width / 2, area.y + area.height / 2),
                            palette, 2f, BEAM_MS));
                    burstAt = arriveMs;
                }
                step.add(new BurstAnim(area, palette, 150, 640).delayedBy(burstAt));
                sparkAt = Math.max(sparkAt, burstAt);
            }
        }

        for (final ModRecord m : visible) {
            final Point at = centreOf(m.card);
            if (at == null) {
                continue;
            }
            step.add(m.replacement
                    ? new ImpactAnim(at, REPLACEMENT_PALETTE, REPLACEMENT_SPARK, 420, 0f)
                            .delayedBy(sparkAt)
                    : new ImpactAnim(at, CardColors.of(m.card, canShow(m.card)), MODIFY_SPARK, 400, 0f)
                            .delayedBy(sparkAt));
            // The card was frozen when its change was announced; the spark is the moment
            // it is allowed to show what it became.
            step.add(CallbackAnim.at(sparkAt, () -> thawCard(m.card)));
        }

        for (final ModRecord m : notLandedYet) {
            sparkOnceArrived(m);
        }

        if (!step.isEmpty()) {
            queue.enqueue(step);
            clock.start();
        }
    }

    /** Whether a card is still on its way in and has not been shown on the board yet. */
    private boolean isAwaitingArrival(final CardView card) {
        if (card == null) {
            return false;
        }
        synchronized (this) {
            if (pendingArrivals.containsKey(card.getId())) {
                return true;
            }
        }
        final CardPanel panel = findPanel(card);
        return panel != null && panel.getRenderAlpha() <= 0f;
    }

    /**
     * Take a card out of the hand it has just been played from.
     * <p>
     * Goes through the container's own removal so the card is still seen leaving - that
     * hook is what copies the panel for its departure - and the deferred refresh that
     * follows simply finds it already gone.
     */
    private void removeFromHand(final CardView card) {
        for (final VHand h : matchUI.getHandViews()) {
            try {
                final CardPanel p = h.getHandArea().getCardPanel(card.getId());
                if (p != null) {
                    h.getHandArea().removeCardPanel(p);
                    return;
                }
            } catch (final RuntimeException ignored) {
                // A hand mid-relayout; the deferred refresh will still take it away.
            }
        }
    }

    /**
     * The battlefield panel for a card that is arriving, building it if the zone update
     * has not got here yet.
     * <p>
     * The arrival is queued the moment the card enters, and the panel is created when the
     * zone update reaches the UI - two things on the same thread with no order between
     * them. Losing that race meant the arrival had nowhere to aim: it drew to the card
     * still sitting in the hand it came from, and the panel that turned up afterwards was
     * hidden by an arrival that had already finished. Asking the tabletop to take in what
     * has arrived is idempotent and reads the same live model the deferred refresh would,
     * so the panel is simply built a moment early.
     */
    private CardPanel panelForArrival(final CardView card) {
        final CardPanel existing = findPanel(card);
        if (existing != null) {
            return existing;
        }
        for (final VField f : matchUI.getFieldViews()) {
            try {
                f.getTabletop().addArrivedPanels();
            } catch (final RuntimeException ignored) {
                // A tabletop mid-teardown; the others are still worth asking.
            }
        }
        return findPanel(card);
    }

    /**
     * Show a change to a card that has not arrived yet, once it has.
     * <p>
     * Hung off the arrival step rather than queued behind it, because which of the two the
     * game announced first is not fixed - counters go on a planeswalker before it enters,
     * but the zone change can still reach the queue first. Attaching to a step that has
     * already played simply runs now, which is the right answer in that case: the card is
     * on the board.
     */
    private void sparkOnceArrived(final ModRecord m) {
        final AnimationStep arrival;
        synchronized (this) {
            arrival = pendingArrivals.get(m.card.getId());
            if (arrival == null) {
                // The change was announced before the card's zone change was. A
                // planeswalker's loyalty is put on as it enters, and the panel can already
                // exist and be waiting - so there is something to spark and nothing yet to
                // wait for. Sparking now was the whole bug: it fired before the arrival
                // step had even been created, let alone played. Held until the arrival
                // turns up and claimed by it.
                sparksAwaitingArrival.computeIfAbsent(m.card.getId(), k -> new ArrayList<>(1)).add(m);
                return;
            }
        }
        arrival.then(sparkFor(m));
    }

    /**
     * Sparks recorded for a card whose arrival has not been announced yet, keyed by card.
     * Claimed by the arrival when it arrives, and dropped if it never does.
     */
    private final Map<Integer, List<ModRecord>> sparksAwaitingArrival = new HashMap<>();

    /** Hand any sparks held for this card to the arrival that will show it. */
    private void claimHeldSparks(final int cardId, final AnimationStep arrival) {
        final List<ModRecord> held = sparksAwaitingArrival.remove(cardId);
        if (held == null) {
            return;
        }
        for (final ModRecord m : held) {
            arrival.then(sparkFor(m));
        }
    }

    private Runnable sparkFor(final ModRecord m) {
        return () -> {
            final Point at = centreOf(m.card);
            if (at == null) {
                thawCard(m.card);
                return;
            }
            final AnimationStep s = new AnimationStep("modify:landed");
            s.add(m.replacement
                    ? new ImpactAnim(at, REPLACEMENT_PALETTE, REPLACEMENT_SPARK, 420, 0f)
                    : new ImpactAnim(at, CardColors.of(m.card, canShow(m.card)), MODIFY_SPARK, 400, 0f));
            s.then(() -> thawCard(m.card));
            queue.enqueue(s);
            clock.start();
        };
    }

    // ------------------------------------------------------------------ player changes

    /**
     * A player gained or lost life, or received counters, from something resolving.
     * <p>
     * Neither event names a source, which is what {@link #resolving} exists for. A change
     * with nothing resolving behind it - a cost being paid, an upkeep trigger's own
     * bookkeeping - is left alone rather than drawn out of a stack that has nothing on it.
     */
    private void recordPlayerChange(final PlayerView player, final int oldLife, final int newLife) {
        if (player == null) {
            return;
        }
        final int lifeDelta = newLife - oldLife;
        final Resolution r = resolving;
        final boolean drawsTrail = r != null && claimTrail(player);
        if (lifeDelta != 0) {
            // The trail, when there is one, carries the number with it. Staging it
            // separately put the release step in the queue before the trail was even
            // built, so life gained appeared instantly while its beam was still on its
            // way - the opposite of how damage read.
            stageLifeTotal(player, oldLife, newLife, drawsTrail);
        }
        if (!drawsTrail) {
            return;
        }
        final CardView source = r.source;
        FThreads.invokeInEdtLater(() -> {
            final Point from = stackAnchor();
            final Point to = avatarCentre(player);
            final AnimationStep step = new AnimationStep("player:" + player.getName());
            if (from != null && to != null) {
                final List<Color> palette = CardColors.of(source, canShow(source));
                step.add(new BeamAnim(from, to, palette,
                        Math.min(6, 1 + Math.abs(lifeDelta)), BEAM_MS));
                step.add(CallbackAnim.at(Math.round(BEAM_MS * BEAM_ARRIVAL),
                        () -> setStagedLife(player, newLife)));
            } else {
                // Nowhere to draw between, but the number must still be let go of.
                step.then(() -> setStagedLife(player, newLife));
            }
            queue.enqueue(step);
            clock.start();
        });
    }

    // ------------------------------------------------------------------ card visuals

    /**
     * Cards whose appearance is being held back until the effect changing them arrives.
     * <p>
     * Tracked here as well as on the panel so that every freeze can be lifted at once
     * when the display catches up, in the same way life totals are. A card left frozen
     * would show a stale board for the rest of the match, which is far worse than an
     * effect landing a moment early.
     */
    private final Set<CardPanel> frozenPanels = ConcurrentHashMap.newKeySet();

    /**
     * Hold a card looking as it does now.
     * <p>
     * Called as the change is announced rather than when its animation is built, because
     * the panel repaints continuously while anything else on the board is animating and
     * would otherwise show the new counters and icons within a frame.
     */
    private void freezeCard(final CardView card) {
        if (card == null || !isEnabled()) {
            return;
        }
        FThreads.invokeInEdtNowOrLater(() -> {
            final CardPanel panel = findPanel(card);
            if (panel == null || panel.isVisualFrozen()) {
                return;
            }
            // Nothing to hold for a card that has not been shown yet. A planeswalker is
            // given its loyalty before it enters, so the panel being frozen is the blank
            // one waiting for its arrival - and the freeze captures exactly that, a black
            // card, which then stands in for the real one all the way through the fade.
            // A card that arrives already showing its counters is the right answer anyway:
            // they were never on screen without them.
            if (panel.getRenderAlpha() <= 0f) {
                return;
            }
            panel.freezeVisual();
            if (panel.isVisualFrozen()) {
                frozenPanels.add(panel);
            }
        });
    }

    /** Show a card as it really is again. Safe to call for a card that was never frozen. */
    private void thawCard(final CardView card) {
        final CardPanel panel = findPanel(card);
        if (panel != null) {
            thawPanel(panel);
        }
    }

    private void thawPanel(final CardPanel panel) {
        frozenPanels.remove(panel);
        panel.thawVisual();
    }

    /**
     * Lift every freeze. Runs when the queue empties, at which point the display has
     * caught up with the game by definition, so a release that never arrived cannot leave
     * a card showing an old face for the rest of the match.
     */
    private void thawAllCards() {
        if (frozenPanels.isEmpty()) {
            return;
        }
        for (final CardPanel p : new ArrayList<>(frozenPanels)) {
            thawPanel(p);
        }
    }

    // ------------------------------------------------------------------ life totals

    /**
     * What each player's life should be shown as while the blows that changed it play.
     * <p>
     * A player's life total is painted straight off the live {@code PlayerView}, so
     * without this it reports the game's answer rather than the display's: three
     * attackers connecting took the number from 20 to 10 in one jump, before any of the
     * three had visibly landed.
     */
    private final Map<Integer, Integer> stagedLife = new ConcurrentHashMap<>();

    /**
     * The life total to paint for a player.
     *
     * @param actual the live value, returned unchanged when nothing is being held back.
     */
    public int displayedLife(final PlayerView player, final int actual) {
        if (player == null || !isEnabled()) {
            return actual;
        }
        final Integer staged = stagedLife.get(player.getId());
        return staged != null ? staged : actual;
    }

    /**
     * Freeze a player's displayed life at what it was before this change, and queue the
     * step that lets it go.
     * <p>
     * Enqueued from the EDT rather than here, so it lands behind the strikes that explain
     * it: the damage events that produced those strikes are announced first but reach the
     * queue via an EDT hop, whereas a life change enqueued on the game thread would jump
     * the whole burst and drop the number before anything had hit.
     */
    /**
     * @param carriedByTrail true when a trail to this player is being built for the same
     *                       change and will release the number as it lands, so no
     *                       reconciling step of its own is wanted.
     */
    private void stageLifeTotal(final PlayerView player, final int oldLife, final int newLife,
            final boolean carriedByTrail) {
        if (!isEnabled()) {
            return;
        }
        final int id = player.getId();
        stagedLife.putIfAbsent(id, oldLife);
        FThreads.invokeInEdtLater(() -> {
            refreshLife(player);
            if (carriedByTrail) {
                return;
            }
            // The authoritative value, as opposed to the running subtraction each strike
            // applies. Anything the strikes could not know about - damage prevented,
            // a replacement effect redirecting it - is reconciled here.
            queue.enqueue(new AnimationStep("life:" + player.getName())
                    .after(() -> setStagedLife(player, newLife)));
            clock.start();
        });
    }

    private void setStagedLife(final PlayerView player, final int life) {
        stagedLife.put(player.getId(), life);
        refreshLife(player);
    }

    /**
     * Take a bite out of a player's displayed life as one blow lands, without waiting for
     * the life change the game reports for the whole damage step.
     * <p>
     * Combat damage is simultaneous in the rules and the game reports it that way - three
     * attackers produce one life change, not three - so stepping the number down per
     * attacker means doing the subtraction here, from what each blow was worth.
     */
    private void applyLifeHit(final PlayerView player, final int amount) {
        final int id = player.getId();
        stagedLife.computeIfPresent(id, (k, v) -> v - amount);
        refreshLife(player);
    }

    private void refreshLife(final PlayerView player) {
        final VField field = fieldFor(player);
        if (field != null) {
            field.updateDetails();
        }
    }

    /**
     * Stop holding any life total back. Called when the queue runs dry, at which point
     * the display has caught up with the game by definition and the live values are the
     * right ones - so a release that never arrived cannot leave a number wrong for the
     * rest of the match.
     */
    private void releaseAllLife() {
        if (stagedLife.isEmpty()) {
            return;
        }
        stagedLife.clear();
        // Every field rather than only the ones held: repainting a detail panel is cheap,
        // and this runs once when the queue empties, not per frame.
        for (final VField f : matchUI.getFieldViews()) {
            f.updateDetails();
        }
    }

    /** Draw the spell reaching its targets, at the moment it actually resolves. */
    private void resolveCast(final GameEventSpellResolved e) {
        final CastRecord rec;
        synchronized (pendingCasts) {
            rec = pendingCasts.remove(e.spell());
        }
        // Read before the scope closes, so the targets drawn below dedup against whatever
        // the resolution itself already drew while it was running.
        final Resolution scope = resolving;
        if (rec != null) {
            // Kept in the list until the resolution itself has played, rather than dropped
            // the moment the game takes it off. The game can resolve a whole stack faster
            // than one trail crosses the board, and an entry that vanishes on its way to
            // being shown means a stack that is never visible at all.
            //
            // Claimed here, on the game thread, and not posted to the EDT: this event is
            // fired before the entry is taken off the stack, and the removal refresh that
            // follows would otherwise land in the gap - the entry blinking out and back as
            // the claim caught up with it.
            lingerStackItem(rec);
        }
        if (rec == null || e.hasFizzled()) {
            // A fizzled spell never reached anything, so showing it connect would lie -
            // but it still has to leave the list, in order, like anything else.
            final AnimationStep step = new AnimationStep("fizzle:" + nameOf(rec == null ? null : rec.source));
            if (rec != null) {
                popsStackEntry(step, rec.stackItemId);
            }
            // Whatever it managed to counter on its way still has to leave, or it would be
            // held in the list until the queue next ran dry.
            if (scope != null) {
                for (final Integer countered : scope.unstacked) {
                    popsStackEntry(step, countered);
                }
            }
            if (!step.isEmpty() || rec != null || scope != null) {
                queue.enqueue(step);
                clock.start();
            }
            resolving = null;
            return;
        }
        // Claim the slot here, on the game thread, before returning. A resolution's own
        // board changes - the creature it destroyed leaving play - are announced before
        // this event, so their refresh is already queued for the EDT. Reserving now puts
        // this animation ahead of that refresh; filling it in afterwards would put it
        // behind, which is why the beam used to arrive after the creature had gone.
        final AnimationStep step = new AnimationStep("resolve:" + rec.source.getName()).reserved();
        queue.enqueue(step);
        clock.start();
        FThreads.invokeInEdtLater(() -> {
            try {
                enqueueResolution(rec, step, scope);
            } finally {
                step.seal();
            }
        });
        resolving = null;
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
    /**
     * @param slot a place already held for this in the queue, sealed once filled in, or
     *             null to simply append.
     */
    private void enqueueOntoStack(final CardView host, final PlayerView activator,
            final AnimationStep slot, final int stackItemId) {
        final AnimationStep step = slot != null
                ? slot
                : new AnimationStep("cast:" + (host != null ? host.getName() : "ability"));
        // The entry appears when its trail gets there, the way a permanent does. Doing it
        // at the start of the step instead just refreshed the whole stack, which paints
        // every entry the game has pushed - so a flurry of triggers still arrived in one
        // lump, whatever order their trails played in.
        step.add(CallbackAnim.at(Math.round(CAST_BEAM_MS * BEAM_ARRIVAL),
                () -> showStackItem(stackItemId)));
        // And unconditionally at the end, so an entry cannot be left out of the list
        // because its step was cut short.
        step.then(() -> showStackItem(stackItemId));
        // The origin is worked out when the beam starts rather than now. A permanent's
        // enters-the-battlefield trigger goes on the stack before the permanent has a
        // card panel, so asking at this point gives nothing and the trail was drawn out
        // of the player instead - which is wrong, the ability is the permanent's. By the
        // time this plays, the board refresh and the arrival ahead of it have run.
        step.add(new BeamAnim(
                () -> {
                    final Point at = centreOf(host);
                    return at != null ? at : avatarCentre(activator);
                },
                this::stackAnchor,
                CardColors.of(host, canShow(host)), 1f, CAST_BEAM_MS));
        if (slot != null) {
            slot.seal();
        } else {
            queue.enqueue(step);
        }
        clock.start();
    }

    /**
     * Fill in the slot reserved when the resolution was announced.
     *
     * @param step  already in the queue, ahead of the board refresh this effect caused.
     * @param scope the resolution that has just finished, holding what it already drew.
     */
    private void enqueueResolution(final CastRecord rec, final AnimationStep step,
            final Resolution scope) {
        // The entry leaves the list when this step has played, not when the game removed
        // it. For a permanent spell the step is empty and the arrival queued ahead of it
        // is what the player is watching; either way the entry outlasts its own resolution.
        popsStackEntry(step, rec.stackItemId);
        // Everything a resolution does comes out of the stack, because that is where the
        // spell or ability is. Its trip out of its source was already shown when it was
        // put there, so repeating that here would tell the same half of the story twice.
        final Point from = stackAnchor();
        final List<Color> palette = CardColors.of(rec.source, canShow(rec.source));

        // One beam per target however many there are, the way the targeting arrows draw
        // one arrow per target. A spell that names five creatures is still hitting five
        // specific creatures, and sweeping the board instead would claim it hit
        // everything. A sweep is for effects with no targets at all.
        for (final CardView c : rec.cardTargets) {
            if (scope != null && !scope.reached.add(reachKey(c))) {
                // Already drawn to while the spell was resolving - a burn spell's damage
                // reaches its target before the resolution is announced, and a second
                // identical trail on top of it is the duplication this guards against.
                continue;
            }
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
            if (scope != null && !scope.reached.add(reachKey(p))) {
                continue;
            }
            final Point to = avatarCentre(p);
            if (to != null) {
                step.add(new BeamAnim(from, to, palette, 1f, 480));
            }
        }
        // What a counterspell is pointed at is another entry in the list, so the trail runs
        // down the stack from one row to the other. The entry it names is still displayed
        // at this point even though the game has taken it away, because a resolution does
        // not remove an entry from the list until its own step has played - so there is
        // something there for this to reach.
        for (final Integer targetId : rec.stackTargets) {
            if (scope != null && !scope.reached.add("s" + targetId)) {
                continue;
            }
            final Point to = stackRowCentre(targetId);
            if (to != null) {
                step.add(new BeamAnim(from, to, palette, 1f, 480));
            }
        }
        // Anything this resolution countered leaves the list now, having been reached.
        if (scope != null) {
            for (final Integer countered : scope.unstacked) {
                popsStackEntry(step, countered);
            }
        }

        // Nothing else. A resolution draws a trail when it targets something, when it
        // damages or drains a player, when it puts a permanent onto the battlefield or
        // when it covers a board - and each of those is drawn by whichever part of the
        // system saw it happen. An ability that simply did its work, drawing a card or
        // filtering the top of a library, gets no flourish at all: it never used to be
        // clear what the spark at the source meant, because it meant nothing in
        // particular.
        //
        // Left empty for a permanent spell, whose arrival draws the beam to where it
        // lands; an empty step simply drains.
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
            if (panel == null) {
                // The panel has not been built yet, so this event won the race and
                // onPanelsAdded will do the work. Leaving the bookkeeping alone is the
                // whole point: consuming it here made that later call see an unexpected
                // arrival and park a card nothing would ever come back for.
                return;
            }
            arriving.remove(card.getId());
            arrivedFrom.remove(card.getId());
        }
        if (panel.getCard() != null) {
            revealArrival(panel, card, from);
        }
    }

    /** Set true to trace why a card entering play did or did not get an arrival beam. */
    private static final boolean TRACE_ARRIVALS = false;

    /**
     * A panel has just been built for a card that is entering play: hide it, so nothing
     * shows before the arrival step queued when the card entered gets to reveal it.
     */
    private void revealArrival(final CardPanel panel, final CardView card, final ZoneType from) {
        // A card must never be left invisible because an animation did not run. This runs
        // regardless and is harmless when the reveal below got there first.
        clock.addFree(new Anim(5000) {
            @Override
            protected void update(final float t) {
            }

            @Override
            protected void onEnd() {
                if (panel.getRenderAlpha() <= 0f) {
                    panel.clearRenderTransform();
                    panel.repaint();
                }
            }
        });

        // Establish what this card looks like on arrival, so the next change to it is
        // measured against the card as it entered - including whatever static abilities
        // were already applying to it, which are in force by the time a panel exists.
        // A card that has to wait for its first stats event to be measured has that event
        // consumed as its baseline, and the change it was reporting is never shown.
        fingerprints.put(card.getId(), fingerprint(card));

        final boolean stillComing;
        synchronized (this) {
            stillComing = pendingArrivals.containsKey(card.getId());
        }
        if (!stillComing) {
            // The arrival has already played, so there is no step left to reveal this and
            // hiding it would leave the card invisible until the safety timer above. Fade
            // it up now instead: the particles are gone, but the card still arrives rather
            // than appearing out of nothing.
            panel.setRenderAlpha(0f);
            clock.addFree(PanelAnim.fadeIn(panel, 320));
            return;
        }

        // Nothing queued here: the arrival was queued when the card entered, which is the
        // only position that keeps it ahead of whatever the arrival triggered. All this
        // has to do is make sure the card is not visible before that step reveals it.
        panel.setRenderAlpha(0f);
        panel.repaint();
    }

    /**
     * The step that carries a permanent onto the battlefield, built when it enters.
     * <p>
     * Both ends are resolved when the step plays rather than now, because the card has no
     * panel yet - it is created by the zone update a moment later. That is the whole
     * reason this can be queued in event order at all: nothing about it needs to be known
     * until it is drawn.
     */
    private AnimationStep arrivalStep(final CardView card, final ZoneType from) {
        // Idempotent, because it is asked for twice: once as the trail lands, and again
        // when the step ends in case the first never happened.
        final Runnable reveal = () -> {
            final CardPanel panel = panelForArrival(card);
            if (panel == null || panel.getRenderAlpha() > 0f) {
                return;
            }
            panel.clearRenderTransform();
            // Straight back to invisible. Clearing the transform restores the card to
            // fully opaque, and the fade does not get to set its own starting alpha until
            // the clock's next tick - so the card was painted once at full strength before
            // fading up from nothing. With no image loaded yet, which is the common case
            // for a card that has only just arrived, that one frame is a black card-shaped
            // hole sitting in the slot it is about to animate into.
            panel.setRenderAlpha(0f);
            clock.addFree(PanelAnim.fadeIn(panel, 320));
        };
        final AnimationStep step = new AnimationStep("arrive:" + card.getName()).after(reveal);
        step.add(new BeamAnim(
                () -> originFor(from, card),
                () -> centreOf(panelForArrival(card)),
                CardColors.of(card, canShow(card)), 1f, ARRIVAL_BEAM_MS));
        // As the trail arrives, not once it has finished. A beam spends its last stretch
        // letting the trailing sparks catch up, and waiting for that left a visible pause
        // between the effect reaching the slot and the card appearing in it.
        step.add(CallbackAnim.at(Math.round(ARRIVAL_BEAM_MS * BEAM_ARRIVAL), reveal));
        return step;
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
        /**
         * What each player took, as opposed to {@link #total}, which is everything this
         * source dealt. A trampler's total covers the blockers as well, so it is not the
         * bite to take out of the defending player's life.
         */
        private final Map<Integer, Integer> playerAmounts = new HashMap<>();
        private int total;
        /**
         * Whether this was a blow struck in combat, captured when the damage happened.
         * <p>
         * It cannot be read later: a {@link CardView} is live and the game thread keeps
         * updating it, so by the time the flush runs on the EDT combat may already have
         * ended and the attacker no longer say it is attacking.
         */
        private boolean combat;
        /**
         * Whether the source was the attacker, as opposed to a blocker. Captured at event
         * time for the same reason {@link #combat} is: combat may be over by flush time.
         * Only the attacker lunges - see {@link #enqueueDirectHits}.
         */
        private boolean attacking;
        /**
         * Whether the source had this on the stack when it struck, decided on the game
         * thread while the stack still held the object. By flush time it is gone.
         */
        private boolean fromStack;

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
        // Claimed here, on the game thread, so damage takes precedence over the other two
        // announcements of the same blow - the spell's target list and the victim's life
        // total - both of which would otherwise draw a second trail along the same line.
        // Damage is the right one to win: it is the only one that knows how hard it hit.
        if (cardTarget != null) {
            claimTrail(cardTarget);
        }
        if (playerTarget != null) {
            claimTrail(playerTarget);
        }
        final boolean onStack = actsFromStack(source);
        if (cardTarget != null) {
            // Hold the victim looking undamaged until the blow reaches it. Everything the
            // card shows is held together, so a planeswalker's loyalty and a creature's
            // damage number both wait for the same moment.
            freezeCard(cardTarget);
            damageClaimed.add(cardTarget.getId());
        }
        // The source too: a creature that struck is about to have its own state
        // recomputed, and whatever that produces was already shown by the exchange.
        damageClaimed.add(source.getId());
        if (cardTarget != null) {
            // The other half of the rule in queueMod: the counters a planeswalker loses
            // may be announced before the damage that caused them, in which case the
            // modification spark is already queued and has to be taken back out. A
            // replacement effect firing on the same card is its own event and stays.
            synchronized (pendingMods) {
                final ModRecord queued = pendingMods.get(cardTarget.getId());
                if (queued != null && !queued.replacement) {
                    pendingMods.remove(cardTarget.getId());
                }
            }
        }
        synchronized (pendingDamage) {
            final DamageGroup g = pendingDamage.computeIfAbsent(source.getId(), k -> new DamageGroup(source));
            g.combat |= combat;
            g.attacking |= source.isAttacking();
            g.fromStack |= onStack;
            if (cardTarget != null) {
                g.cardTargets.add(cardTarget);
            }
            if (playerTarget != null) {
                g.playerTargets.add(playerTarget);
                g.playerAmounts.merge(playerTarget.getId(), amount, Integer::sum);
            }
            g.total += amount;
            if (damageFlushQueued) {
                return;
            }
            damageFlushQueued = true;
            // Claim the slot now, on the game thread, before anything else can be queued.
            // A creature dying to this damage is announced as a zone change whose refresh
            // reaches the EDT independently, and with an empty queue that refresh runs
            // straight away - so the creature faded out before the attacker had visibly
            // reached it. Reserving here means the refresh piles up behind the strikes
            // instead, and the blows land before their consequences.
            damageBarrier = new AnimationStep("damage").reserved();
            queue.enqueue(damageBarrier);
        }
        clock.start();
        // Same latch trick the batching event handler uses: everything the game thread
        // emits before the EDT next runs lands in one group, which is exactly the window
        // a single spell's damage occupies.
        FThreads.invokeInEdtLater(this::flushDamage);
    }

    /** The slot the current damage burst will be spliced into; see {@link #recordDamage}. */
    private AnimationStep damageBarrier;

    private void flushDamage() {
        final List<DamageGroup> groups;
        final AnimationStep barrier;
        synchronized (pendingDamage) {
            damageFlushQueued = false;
            barrier = damageBarrier;
            damageBarrier = null;
            if (pendingDamage.isEmpty()) {
                // Nothing to show after all, but the slot still has to be released or the
                // queue sits on it until it times out.
                if (barrier != null) {
                    barrier.seal();
                }
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
            // Read from what was captured as the blow landed, not from the live view:
            // combat may already be over by the time this runs, in which case nothing
            // reports itself as blocking any more and no blocker would ever be folded.
            if (g.source == null || !g.combat || g.attacking
                    || g.cardTargets.isEmpty() || !g.playerTargets.isEmpty()) {
                continue;
            }
            // No further check that the victims are attackers. It was there to be sure
            // this really was a blocker striking back, but it asked the live view - and
            // once combat has ended nothing reports itself as attacking, so the fold
            // silently stopped happening and the blocker's damage became a beat of its
            // own after the attacker's. Combat damage from something that is not the
            // attacker is the blocker hitting what it blocked; there is nothing else it
            // can be.
            for (final CardView t : g.cardTargets) {
                counterHits.computeIfAbsent(t.getId(), k -> new HashSet<>()).add(g.source.getId());
            }
            folded.add(g);
        }

        final List<AnimationStep> steps = new ArrayList<>(4);
        for (final DamageGroup g : groups) {
            if (folded.contains(g)) {
                continue;
            }
            // Only an untargeted effect sweeps. A damage spell that names its victims
            // gets a beam to each of them however many there are; hitting several things
            // is not the same as hitting an area.
            if (g.targetCount() >= AOE_TARGET_THRESHOLD && !isTargeting(g.source)) {
                enqueueAreaEffect(g, steps);
            } else {
                enqueueDirectHits(g, counterHits, steps);
            }
        }
        // Into the slot claimed when the first blow was recorded, so these play ahead of
        // the board refresh that has been waiting behind it, and then release the queue.
        queue.enqueueBehind(barrier, steps);
        if (barrier != null) {
            barrier.seal();
        }
        clock.start();
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
    private void enqueueDirectHits(final DamageGroup g, final Map<Integer, Set<Integer>> counterHits,
            final List<AnimationStep> out) {
        final CardPanel sourcePanel = findPanel(g.source);
        // Off the stack while the spell or ability is on it, and out of the card only for
        // damage that never used the stack at all - a mana ability's own ping, which is
        // the one case where the card really is the thing acting.
        final Point from = trailOrigin(g.source, g.fromStack);
        final List<Color> palette = DAMAGE_PALETTE;
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
        final boolean striking = g.combat && g.attacking;

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
            // When the blow actually connects. A strike lands as the attacker reaches
            // full extension; a beam lands when its head arrives. Everything the damage
            // causes is hung off this one moment, so the flinch, the number and the
            // card's new face all happen together and none of them happen as the effect
            // merely sets off.
            final long contactMs;
            if (striking) {
                contactMs = Math.round(IMPACT_MS * IMPACT_TRIGGER);
                // The card itself crosses the gap, so sparks only at the point of
                // contact - a beam would be drawing the same journey a second time.
                step.add(new ImpactAnim(to, palette, g.total, IMPACT_MS, IMPACT_TRIGGER));
            } else if (g.combat) {
                // A blocker that was not folded into the attacker's strike. It still must
                // not draw a trail: combat damage is dealt by contact, never by something
                // crossing the board, so it gets the collision alone.
                contactMs = Math.round(IMPACT_MS * IMPACT_TRIGGER);
                step.add(new ImpactAnim(to, palette, g.total, IMPACT_MS, IMPACT_TRIGGER));
            } else if (from != null) {
                contactMs = Math.round(BEAM_MS * BEAM_ARRIVAL);
                // Nothing moves, so the effect has to travel on its own.
                step.add(new BeamAnim(from, to, palette, g.total, BEAM_MS));
            } else {
                contactMs = 0L;
            }
            if (target instanceof PlayerView pv) {
                // A damaged player reacts too. Every player an effect hits gets one, so
                // something that burns all opponents visibly rocks each of them.
                step.add(delayed(playerFlinch(pv, palette), contactMs));
                // And their life falls with this blow rather than at the end of the step,
                // so three attackers connecting reads as three drops, each one landing
                // with its own flinch instead of all of them after the last recoil.
                final int hit = g.playerAmounts.getOrDefault(pv.getId(), 0);
                if (hit > 0) {
                    step.add(CallbackAnim.at(contactMs, () -> applyLifeHit(pv, hit)));
                }
            }
            if (target instanceof CardView cv) {
                final CardPanel tp = findPanel(cv);
                if (tp != null && from != null) {
                    step.add(delayed(PanelAnim.flinch(tp, toPanelSpace(tp, from), 260), contactMs));
                }
                // The card was frozen when the damage was announced; this is the moment it
                // is allowed to show what happened to it.
                step.add(CallbackAnim.at(contactMs, () -> thawCard(cv)));
                // If that blocker hit back, it recoils together with the attacker rather
                // than in a beat of its own - and the attacker takes its damage at the
                // same instant, because in the rules the two blows are simultaneous.
                if (striking && sourcePanel != null
                        && counterHits.getOrDefault(g.source.getId(), Set.of()).contains(cv.getId())) {
                    step.add(delayed(PanelAnim.flinch(sourcePanel, toPanelSpace(sourcePanel, to), 260), contactMs));
                    step.add(CallbackAnim.at(contactMs, () -> thawCard(g.source)));
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
                // Collected even with no panel to move: combat damage always reads as a
                // strike, never as something travelling across the board.
                out.add(step);
            }
        }
        if (shared != null && !shared.isEmpty()) {
            out.add(shared);
        }
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
    private void enqueueAreaEffect(final DamageGroup g, final List<AnimationStep> out) {
        final List<Color> palette = DAMAGE_PALETTE;
        final AnimationStep step = new AnimationStep("aoe:" + g.source.getName());

        final Set<PlayerView> affected = new HashSet<>();
        for (final CardView target : g.cardTargets) {
            if (target.getController() != null) {
                affected.add(target.getController());
            }
        }
        affected.addAll(g.playerTargets);

        // The sweep is what the effect did once it got there, so it waits for the trail
        // to arrive. Playing them together read as the board erupting and the effect then
        // travelling towards the mess it had already made.
        final Point origin = trailOrigin(g.source, g.fromStack);
        final long contactMs = Math.round(BEAM_MS * BEAM_ARRIVAL);
        for (final PlayerView p : affected) {
            final Rectangle area = battlefieldBounds(p);
            if (area == null) {
                continue;
            }
            if (origin != null && claimBoardTrail(resolving, p)) {
                step.add(new BeamAnim(origin,
                        new Point(area.x + area.width / 2, area.y + area.height / 2),
                        palette, 2f, BEAM_MS));
            }
            step.add(new BurstAnim(area, palette, Math.min(220, 60 + g.total * 10), 640)
                    .delayedBy(contactMs));
        }
        // Still flinch each victim, so it stays clear which permanents were hit.
        for (final CardView target : g.cardTargets) {
            final CardPanel tp = findPanel(target);
            if (tp != null && origin != null) {
                step.add(delayed(PanelAnim.flinch(tp, toPanelSpace(tp, origin), 300), contactMs));
            }
            step.add(CallbackAnim.at(contactMs, () -> thawCard(target)));
        }
        // And every player it burned, so an effect aimed at the whole table visibly
        // rocks each of them rather than only sweeping their boards.
        for (final PlayerView p : g.playerTargets) {
            step.add(delayed(playerFlinch(p, palette), contactMs));
            final int hit = g.playerAmounts.getOrDefault(p.getId(), 0);
            if (hit > 0) {
                step.add(CallbackAnim.at(contactMs, () -> applyLifeHit(p, hit)));
            }
        }
        if (!step.isEmpty()) {
            out.add(step);
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
        // Whatever it was doing here, the panel is going. Drop what it looked like and
        // let it be measured afresh if it ever comes back - a card returning from a
        // graveyard is a new object as far as its appearance is concerned.
        fingerprints.remove(panel.getCard().getId());
        frozenPanels.remove(panel);
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
                revealArrival(panel, card, from);
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
    /**
     * @param before the card as it looked before layout moved it, or null to copy it as
     *               it is now. Supplied on a resize, where copying it now would catch the
     *               panel already resized but not yet redrawn - an empty black frame.
     */
    public void reflow(final CardPanel panel, final int dx, final int dy, final double scale,
            final CardSnapshot before) {
        if (panel == null || !isEnabled()) {
            return;
        }
        // A card being hidden for its own arrival must not be dragged into a reflow; it
        // is already where it belongs and is only waiting for its particles.
        if (panel.getRenderAlpha() <= 0f) {
            return;
        }
        // Retire the previous one first. Two reflows on the same card both write its
        // offset every frame, so they fight and the card stutters between them. The
        // replacement already starts from where the card visually is, because the
        // capture that produced this delta included the old animation's offset.
        final Anim previous = runningReflows.put(panel, null);
        if (previous != null) {
            previous.finish();
        }
        // Drawn on the overlay so the card is not clipped to its new bounds while it is
        // still travelling and resizing into them. The panel transform is kept only as a
        // fallback for when the card cannot be copied.
        final CardSnapshot snap = before != null ? before : CardSnapshot.capture(panel, layer);
        final Point destination = snap == null ? null : panelTopLeft(panel);
        final Anim reflow;
        if (destination != null) {
            reflow = OverlayFlight.reflow(panel, snap, destination,
                    panel.getWidth() / (double) Math.max(1, snap.getBounds().width), 260);
            // Hidden here rather than left to the animation's own first frame. Layout has
            // already put the panel where it is going, and an animation does not get to
            // set up until the clock's next tick - so the card was painted once, sitting
            // finished at the destination, and only then vanished to travel there. The
            // capture above has to come first, or the copy is of an invisible card.
            panel.setRenderAlpha(0f);
            panel.repaint();
        } else {
            reflow = PanelAnim.reflow(panel, dx, dy, scale, 260);
            // Same again for the in-place fallback: displaced now, not a frame later.
            panel.setRenderOffset(dx, dy);
            panel.setRenderScale(scale);
            panel.repaint();
        }
        runningReflows.put(panel, reflow);
        clock.addFree(reflow);
    }

    /**
     * The reflow currently animating each card, so a new one can replace it cleanly.
     * <p>
     * Weak-keyed so a card leaving the battlefield does not keep its panel alive.
     */
    private final Map<CardPanel, Anim> runningReflows = new java.util.WeakHashMap<>();

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

    /**
     * Where a resolving spell's effects originate, and where a new one lands: the top of
     * the stack list, since that is the end things are added to and taken from.
     */
    private Point stackAnchor() {
        try {
            final Rectangle listed = boundsInLayer(matchUI.getCStack().getView().getScroller());
            if (listed != null && listed.width > 0) {
                // Half an entry down from the top, so the trail ends on the first row
                // rather than on the border above it.
                return new Point(listed.x + listed.width / 2,
                        listed.y + Math.min(STACK_ENTRY_HEIGHT / 2, listed.height / 2));
            }
            final Rectangle bounds = boundsInLayer(matchUI.getCStack().getView().getParentCell());
            if (bounds != null && bounds.width > 0) {
                return new Point(bounds.x + bounds.width / 2, bounds.y + Math.min(90, bounds.height / 2));
            }
        } catch (final RuntimeException e) {
            // The stack panel may not be laid out yet.
        }
        return new Point(layer.getWidth() / 2, layer.getHeight() / 3);
    }

    /** Roughly one stack entry, for placing the anchor on the topmost row. */
    private static final int STACK_ENTRY_HEIGHT = 76;

    /** The middle of one entry's row in the list, or null if it is not being drawn. */
    private Point stackRowCentre(final int itemId) {
        try {
            final Rectangle row = boundsInLayer(matchUI.getCStack().getView().getRow(itemId));
            return row == null ? null : new Point(row.x + row.width / 2, row.y + row.height / 2);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ stack contents

    /**
     * Stack entries the game has pushed but whose trail has not arrived yet.
     * <p>
     * The stack view still reads the real stack; this only says which of it has been
     * shown. Holding back a whole snapshot instead would let the display disagree with
     * the game about what is on the stack, whereas the worst this can do is lag it - and
     * anything still held when the queue empties is released.
     */
    private final Set<Integer> stackNotYetShown = ConcurrentHashMap.newKeySet();

    /**
     * Entries the game has taken off the stack whose resolution has not been animated yet.
     * <p>
     * The display therefore lags the stack at both ends: an entry appears when its trail
     * lands and leaves when its resolution plays, so the sequence the player watches is
     * the one the animations tell. Everything here is released when the queue empties, so
     * the lag is bounded and the list always ends up agreeing with the game.
     */
    private final Map<Integer, LingeringEntry> stackLingering = new LinkedHashMap<>();

    /** Bound on entries kept past their removal, so a missed hook cannot accumulate. */
    private static final int MAX_LINGERING = 16;

    /** A stack entry outliving its own removal, and where it sat while it was real. */
    private static final class LingeringEntry {
        private final StackItemView item;
        private final Integer belowId;

        LingeringEntry(final StackItemView item, final Integer belowId) {
            this.item = item;
            this.belowId = belowId;
        }
    }

    /**
     * The entries to draw: the live stack, minus what has not arrived yet, plus what has
     * gone but not yet been seen to go.
     * <p>
     * Built from the live list rather than kept as a snapshot, so a stack the animator
     * knows nothing about still displays exactly as the game has it.
     */
    public List<StackItemView> displayStack(final Iterable<StackItemView> live) {
        final List<StackItemView> out = new ArrayList<>();
        if (!isEnabled()) {
            for (final StackItemView item : live) {
                out.add(item);
            }
            return out;
        }
        final List<LingeringEntry> held;
        synchronized (stackLingering) {
            held = new ArrayList<>(stackLingering.values());
        }
        final Set<Integer> emitted = new HashSet<>();
        for (final StackItemView item : live) {
            final int id = item.getId();
            // Whatever used to sit on top of this one goes back on top of it.
            emitLingering(id, held, emitted, out);
            if (emitted.add(id) && !stackNotYetShown.contains(id)) {
                out.add(item);
            }
        }
        // Entries that were the last thing on the stack have nothing to sit above, so
        // they go underneath what has been played since; and anything whose neighbour has
        // itself gone is shown at the bottom rather than quietly dropped.
        emitLingering(null, held, emitted, out);
        for (final LingeringEntry e : held) {
            final int id = e.item.getId();
            if (emitted.add(id) && !stackNotYetShown.contains(id)) {
                out.add(e.item);
            }
        }
        return out;
    }

    /**
     * Place the entries that sat directly above {@code anchorId}, and recursively those
     * above them, so a run of entries resolved in quick succession comes back in the order
     * it was in rather than the order it left.
     */
    private void emitLingering(final Integer anchorId, final List<LingeringEntry> held,
            final Set<Integer> emitted, final List<StackItemView> out) {
        for (final LingeringEntry e : held) {
            final int id = e.item.getId();
            if (emitted.contains(id) || !Objects.equals(e.belowId, anchorId)) {
                continue;
            }
            emitted.add(id);
            emitLingering(id, held, emitted, out);
            if (!stackNotYetShown.contains(id)) {
                out.add(e.item);
            }
        }
    }

    /** Show an entry, once the trail that carried it has arrived. */
    private void showStackItem(final int id) {
        if (stackNotYetShown.remove(id)) {
            refreshStack();
        }
    }

    /**
     * Keep an entry in the list after the game has removed it from the stack.
     * <p>
     * Called on the game thread, before the removal it is guarding against; only the
     * repaint goes to the EDT. The claim itself has to be taken first, because the
     * refresh that draws the entry gone is triggered by the removal and would find
     * nothing holding it.
     */
    private void lingerStackItem(final CastRecord rec) {
        if (!isEnabled() || rec.stackItem == null) {
            return;
        }
        synchronized (stackLingering) {
            stackLingering.put(rec.stackItemId, new LingeringEntry(rec.stackItem, rec.belowId));
            while (stackLingering.size() > MAX_LINGERING) {
                stackLingering.remove(stackLingering.keySet().iterator().next());
            }
        }
        FThreads.invokeInEdtLater(this::refreshStack);
    }

    /** Beat given to a step that takes an entry off the stack, so pops read one at a time. */
    private static final long STACK_POP_MS = 120;

    /**
     * Make a step responsible for taking one entry off the stack when it has played.
     * <p>
     * The beat is what keeps the pops apart. A resolution that draws nothing - a pump, a
     * counter being placed - is an empty step, and the queue plays empty steps back to
     * back within a single frame rather than stalling the board on pure state changes. So
     * a trigger that resolved into nothing lost its entry in the same frame as the spell
     * ahead of it, and two entries vanished together. Holding for a moment says that an
     * entry leaving the stack is itself something to be seen, whether or not the
     * resolution had anything to draw.
     * <p>
     * Only when there is actually an entry being held for this, and it scales with the
     * backlog like everything else, so a long stack still pops briskly.
     */
    private void popsStackEntry(final AnimationStep step, final int id) {
        step.then(() -> hideStackItem(id));
        final boolean showing;
        synchronized (stackLingering) {
            showing = stackLingering.containsKey(id);
        }
        if (showing) {
            step.hold(STACK_POP_MS);
        }
    }

    /** Take an entry out of the list, its resolution having now been played. */
    private void hideStackItem(final int id) {
        final boolean lingered;
        synchronized (stackLingering) {
            lingered = stackLingering.remove(id) != null;
        }
        // Released from the other side too: an entry whose trail never landed would
        // otherwise stay held for a spell that has already come and gone.
        final boolean held = stackNotYetShown.remove(id);
        if (lingered || held) {
            refreshStack();
        }
    }

    /**
     * The entry directly beneath one just put on the stack.
     * <p>
     * Read here, on the game thread, while the stack still has both of them. The tail of
     * the list is walked rather than assumed because a spell that has not been pushed yet
     * would otherwise be recorded as sitting on itself.
     */
    private Integer idBelowOnStack(final StackItemView si) {
        try {
            final GameView game = matchUI.getGameView();
            if (game == null) {
                return null;
            }
            Integer first = null;
            boolean seen = false;
            for (final StackItemView item : game.getStack()) {
                final int id = item.getId();
                if (seen) {
                    return id;
                }
                if (id == si.getId()) {
                    seen = true;
                } else if (first == null) {
                    first = id;
                }
            }
            return seen ? null : first;
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    private static String nameOf(final CardView card) {
        return card == null ? "spell" : card.getName();
    }

    /**
     * Redraw the list, without the tab switch {@code CStack.update()} performs.
     * <p>
     * Showing the stack tab is right when the game puts something on the stack, and the
     * update that announced it has already done so. These refreshes are the animator
     * spacing that same content out, and doing it once per entry would pull the tab
     * forward over and over while the player was reading something else.
     */
    private void refreshStack() {
        try {
            matchUI.getCStack().getView().updateStack();
        } catch (final RuntimeException ignored) {
            // Cosmetic; the next real refresh will put it right.
        }
    }

    /**
     * Give up every hold on the stack list. Called when the display has caught up with
     * the game, which is the point at which lagging it would only be lying about it.
     */
    private void showAllStackItems() {
        final boolean lingered;
        synchronized (stackLingering) {
            lingered = !stackLingering.isEmpty();
            stackLingering.clear();
        }
        final boolean held = !stackNotYetShown.isEmpty();
        stackNotYetShown.clear();
        if (lingered || held) {
            refreshStack();
        }
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
        // A card on its way to the battlefield is not to be found in the hand it is
        // leaving. The hand copy is only still there because zone refreshes are deferred,
        // and answering with it sent the arrival beam back into the player's hand and
        // sparked changes on a card that had already left it.
        synchronized (this) {
            if (pendingArrivals.containsKey(id)) {
                return null;
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
        // The centre of the card face, not of the component. A card panel is far larger
        // than the card it draws - it reserves room on every side for the tap rotation -
        // and the face is not centred in that box, so using the component's middle puts
        // every beam and impact noticeably off the card.
        return SwingUtilities.convertPoint(parent,
                panel.getCardX() + panel.getCardWidth() / 2,
                panel.getCardY() + panel.getCardHeight() / 2, layer);
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

    /** A panel's component origin in overlay coordinates, for placing a copy of it. */
    private Point panelTopLeft(final CardPanel panel) {
        final java.awt.Container parent = panel.getParent();
        if (parent == null || !parent.isShowing() || !layer.isShowing()) {
            return null;
        }
        return SwingUtilities.convertPoint(parent, panel.getX(), panel.getY(), layer);
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
