package forge.screens.match.animation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import org.tinylog.Logger;

/**
 * The animation buffer: a queue of {@link AnimationStep}s played one at a time while
 * the game itself runs ahead unblocked.
 * <p>
 * Forge's game loop does not wait for the display. By the time a creature's death
 * animation is halfway through, the game may already have resolved three more spells.
 * This queue absorbs that gap - events are appended as they arrive and drained at
 * animation speed, so the player watches a coherent sequence instead of a board that
 * snaps between states.
 * <p>
 * Falling behind is bounded from both ends. Once the backlog passes
 * {@link #CATCHUP_DEPTH} steps playback accelerates in proportion to the backlog, and
 * past {@link #MAX_DEPTH} the oldest steps stop being animated at all. Either way
 * every step's {@code before}/{@code after} hook still runs, in order, so the visible
 * board always converges on the real one. That invariant is what makes it safe to
 * animate at all: the worst an animation bug can do is look wrong, never desync.
 */
public final class AnimationQueue {

    /** Backlog beyond which playback accelerates. */
    private static final int CATCHUP_DEPTH = 3;
    /** Backlog beyond which older steps are committed without being shown. */
    private static final int MAX_DEPTH = 20;
    /** Ceiling on the catch-up multiplier, so motion stays legible rather than a flicker. */
    private static final float MAX_SCALE = 5f;
    /** Guards the drain loop against a pathological run of empty steps. */
    private static final int MAX_STEPS_PER_TICK = 64;

    private final Deque<AnimationStep> pending = new ArrayDeque<>();
    private final List<Anim> currentAnims = new ArrayList<>(2);

    private AnimationStep current;
    private long holdRemainingMs;
    private boolean paused;

    /**
     * Append a step. Safe to call from the game thread - the step is not touched until
     * the next tick, which happens on the EDT.
     */
    public synchronized void enqueue(final AnimationStep step) {
        if (step != null) {
            pending.addLast(step);
        }
    }

    public synchronized boolean isIdle() {
        return current == null && pending.isEmpty();
    }

    public synchronized int getDepth() {
        return pending.size() + (current != null ? 1 : 0);
    }

    /** Hold playback without dropping anything, e.g. while a modal dialog covers the board. */
    public synchronized void setPaused(final boolean paused) {
        this.paused = paused;
    }

    /**
     * Advance the head of the queue by one frame.
     *
     * @param deltaMs wall time since the previous tick.
     * @return true if anything moved, meaning the overlay needs repainting.
     */
    synchronized boolean tick(final long deltaMs) {
        if (paused) {
            return false;
        }
        trimBacklog();

        final long scaled = Math.max(1L, (long) (deltaMs * speedScale()));
        boolean changed = false;

        for (int guard = 0; guard < MAX_STEPS_PER_TICK; guard++) {
            if (current == null && !startNext()) {
                break;
            }
            changed = true;

            // Animations within a step are concurrent, so they all see the same elapsed time.
            for (final Iterator<Anim> it = currentAnims.iterator(); it.hasNext();) {
                if (it.next().advance(scaled)) {
                    it.remove();
                }
            }
            if (!currentAnims.isEmpty()) {
                break;
            }
            if (holdRemainingMs > 0L) {
                holdRemainingMs -= scaled;
                if (holdRemainingMs > 0L) {
                    break;
                }
            }
            finishCurrent();

            // Steps that carry no motion are pure state changes; draining them one per
            // frame would stall the board for no visual benefit, so take them now.
            final AnimationStep next = pending.peekFirst();
            if (next == null || !next.isEmpty()) {
                break;
            }
        }
        return changed;
    }

    /**
     * Play everything still queued instantly: animations jump to their end state and
     * every pending hook runs in order.
     * <p>
     * This backs the skip hotkey, and also runs before the game asks the player a
     * question - being prompted to act on a board you have not been shown yet would be
     * worse than losing the animation.
     */
    public synchronized void skipAll() {
        finishCurrent();
        int guard = 0;
        while (!pending.isEmpty() && guard++ < 10000) {
            startNext();
            finishCurrent();
        }
    }

    /** Commit the oldest steps without showing them once the backlog is hopeless. */
    private void trimBacklog() {
        int guard = 0;
        while (animatedDepth() > MAX_DEPTH && guard++ < 10000) {
            if (current == null) {
                startNext();
            }
            finishCurrent();
        }
    }

    /**
     * How far behind the display actually is, counted in steps that have something to
     * show.
     * <p>
     * Most of the queue is not motion at all: a sound to play, a set of cards to refresh,
     * a zone to rebuild. Those are ordering markers that cost no time - the drain loop
     * takes them several at a tick - so counting them as backlog made a single land
     * being played look like a display ten steps behind. Playback would then accelerate
     * to catch up with a backlog that did not exist, or discard the one animation in the
     * queue that mattered.
     */
    private int animatedDepth() {
        int n = 0;
        for (final AnimationStep s : pending) {
            if (!s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** Playback rate from the backlog alone; the user setting is applied by the clock. */
    private float speedScale() {
        final int depth = animatedDepth();
        if (depth <= CATCHUP_DEPTH) {
            return 1f;
        }
        return Math.min(MAX_SCALE, 1f + (depth - CATCHUP_DEPTH) * 0.5f);
    }

    /** Promote the next queued step to current and run its {@code before} hook. */
    private boolean startNext() {
        final AnimationStep next = pending.pollFirst();
        if (next == null) {
            return false;
        }
        current = next;
        currentAnims.clear();
        currentAnims.addAll(next.getAnims());
        holdRemainingMs = next.getHoldMs();
        run(next.getBefore(), next.getLabel(), "before");
        return true;
    }

    /** Force the current step to its end state and run its {@code after} hook. */
    private void finishCurrent() {
        if (current == null) {
            return;
        }
        for (final Anim a : currentAnims) {
            a.finish();
        }
        currentAnims.clear();
        holdRemainingMs = 0L;
        final AnimationStep done = current;
        current = null;
        run(done.getAfter(), done.getLabel(), "after");
    }

    /**
     * Hooks mutate the live UI, so one throwing must not wedge the queue - every later
     * step would be stuck behind it and the board would stop updating for the rest of
     * the game.
     */
    private static void run(final Runnable r, final String label, final String phase) {
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (final Exception e) {
            Logger.error(e, "Animation step '" + label + "' failed in its " + phase + " hook");
        }
    }

    /** Snapshot of the animations to paint this frame. */
    synchronized List<Anim> getDrawableAnims() {
        return new ArrayList<>(currentAnims);
    }
}
