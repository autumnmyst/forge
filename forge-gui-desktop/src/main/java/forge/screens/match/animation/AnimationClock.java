package forge.screens.match.animation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.swing.Timer;

/**
 * Drives every animation from a single Swing timer.
 * <p>
 * One timer rather than one per animation matters for two reasons. Swing components
 * may only be touched from the EDT, and {@link Timer} fires there, unlike the
 * {@link java.util.Timer} the older {@link forge.view.arcane.util.Animation} uses -
 * that one mutates card panels from a background thread and gets away with it only
 * because the mutations are small. And a shared clock gives every animation the same
 * frame boundary, so a card and the particles trailing it stay in step.
 * <p>
 * The clock stops itself the moment nothing is animating. An idle match should cost
 * no repaints at all.
 */
public final class AnimationClock {

    /** ~60fps. Fast enough that motion reads as smooth without flooding the EDT. */
    private static final int FRAME_MS = 16;
    /** Ignore gaps longer than this (debugger pauses, the window being dragged). */
    private static final long MAX_DELTA_MS = 120L;

    private final Timer timer;
    private final AnimationQueue queue;
    private final AnimationLayer layer;
    private final List<Anim> free = new ArrayList<>(4);

    private long lastTickNanos;
    /** Playback rate from preferences; below 1 slows everything down. */
    private volatile float userSpeed = 1f;

    /**
     * Set the playback rate. Applied here rather than inside the queue so it reaches
     * every animation - the queued steps, the free ones, and the cards in flight alike.
     */
    public void setUserSpeed(final float speed) {
        this.userSpeed = Math.max(0.1f, Math.min(5f, speed));
    }

    public AnimationClock(final AnimationQueue queue, final AnimationLayer layer) {
        this.queue = queue;
        this.layer = layer;
        this.timer = new Timer(FRAME_MS, e -> tick());
        this.timer.setCoalesce(true);
    }

    /**
     * Animations that play independently of the queue - cosmetic flourishes that must
     * not delay the game state behind them, such as a particle beam trailing a spell
     * that has already resolved.
     */
    public synchronized void addFree(final Anim anim) {
        if (anim != null) {
            free.add(anim);
            start();
        }
    }

    /** Begin ticking. Cheap to call repeatedly; the timer ignores a redundant start. */
    public void start() {
        if (!timer.isRunning()) {
            lastTickNanos = System.nanoTime();
            timer.start();
        }
    }

    public void stop() {
        timer.stop();
    }

    /** Finish everything in flight immediately, including the free animations. */
    public void skipAll() {
        queue.skipAll();
        final List<Anim> pending;
        synchronized (this) {
            pending = new ArrayList<>(free);
            free.clear();
        }
        for (final Anim a : pending) {
            a.finish();
        }
        layer.repaint();
    }

    private void tick() {
        final long now = System.nanoTime();
        long deltaMs = (now - lastTickNanos) / 1_000_000L;
        lastTickNanos = now;
        if (deltaMs <= 0L) {
            deltaMs = 1L;
        } else if (deltaMs > MAX_DELTA_MS) {
            // A long stall should not teleport everything; treat it as one frame.
            deltaMs = FRAME_MS;
        }

        // Scale once, here, so nothing downstream has to remember to honour the setting.
        deltaMs = Math.max(1L, (long) (deltaMs * userSpeed));
        boolean active = queue.tick(deltaMs);

        final long dt = deltaMs;
        synchronized (this) {
            for (final Iterator<Anim> it = free.iterator(); it.hasNext();) {
                if (it.next().advance(dt)) {
                    it.remove();
                }
                active = true;
            }
            active |= !free.isEmpty();
        }

        // A drag has no timeline of its own but still needs a frame every tick.
        layer.pruneFinished();
        active |= layer.hasOverlayAnims();

        if (active) {
            layer.repaint();
        } else if (queue.isIdle()) {
            // Nothing left to show - go quiet rather than burn a repaint every 16ms.
            timer.stop();
            layer.repaint();
        }
    }
}
