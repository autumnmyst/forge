package forge.screens.match.animation;

import java.awt.Graphics2D;

/**
 * One piece of timed motion.
 * <p>
 * An animation is advanced by {@link AnimationClock} on the EDT and may paint itself
 * onto the shared {@link AnimationLayer}. It owns no game state: everything an
 * animation does is cosmetic, and the real state change is applied by the
 * {@link AnimationStep} commit that runs when the animation finishes. That split is
 * what makes animations safe to cut short - see {@link #finish()}.
 */
public abstract class Anim {

    private final long durationMs;
    private long elapsedMs;
    private boolean started;
    private boolean done;

    protected Anim(final long durationMs) {
        // A zero-length animation would divide by zero below and is never useful.
        this.durationMs = Math.max(1L, durationMs);
    }

    public final long getDurationMs() {
        return durationMs;
    }

    public final boolean isDone() {
        return done;
    }

    /**
     * Advance by {@code deltaMs}.
     *
     * @return true once the animation has finished and should be dropped.
     */
    final boolean advance(final long deltaMs) {
        if (done) {
            return true;
        }
        if (!started) {
            started = true;
            onStart();
        }
        elapsedMs += deltaMs;
        if (elapsedMs >= durationMs) {
            finish();
            return true;
        }
        update(elapsedMs / (float) durationMs);
        return false;
    }

    /**
     * Jump straight to the end state and stop.
     * <p>
     * Both natural completion and the skip hotkey come through here, which is what
     * makes the two indistinguishable in their result: the final frame is applied
     * either way, so a cut-short animation cannot strand its subject part-way.
     */
    public final void finish() {
        if (done) {
            return;
        }
        if (!started) {
            // Never rendered a frame; still run the lifecycle so onEnd can clean up.
            started = true;
            onStart();
        }
        elapsedMs = durationMs;
        update(1f);
        done = true;
        onEnd();
    }

    /** Progress through the animation, 0..1, before easing. */
    protected final float getRawProgress() {
        return elapsedMs / (float) durationMs;
    }

    /**
     * Apply the animation at the given linear progress.
     *
     * @param t progress from 0 to 1 inclusive; always called with exactly 1 last.
     */
    protected abstract void update(float t);

    /** Paint onto the overlay. Animations that only move existing components need not override. */
    public void draw(final Graphics2D g) {
    }

    protected void onStart() {
    }

    protected void onEnd() {
    }
}
