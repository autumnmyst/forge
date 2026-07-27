package forge.screens.match.animation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One entry in the {@link AnimationQueue}: the motion that depicts a single game
 * event, bracketed by the UI state changes that event actually causes.
 * <p>
 * The two hooks exist because animations need their subject to be present at the
 * right time. A dying creature must still have a card panel while it fades, so its
 * removal runs {@link #getAfter() after}; a token must already exist before it can
 * fade in, so its zone refresh runs {@link #getBefore() before}.
 * <p>
 * Both hooks run exactly once and in queue order whether the step played out in full
 * or was cut short by the skip hotkey. That is the invariant that keeps the display
 * converging on real game state no matter what the animations do.
 */
public final class AnimationStep {

    private final String label;
    private final List<Anim> anims = new ArrayList<>(2);
    private Runnable before;
    private Runnable after;
    private long holdMs;
    private volatile boolean sealed = true;

    public AnimationStep(final String label) {
        this.label = label;
    }

    /**
     * Claim a place in the queue before knowing what will go in it.
     * <p>
     * Lets a slot be taken on the game thread the instant something starts happening,
     * and filled in later once the display can be measured. Anything queued afterwards -
     * notably the board refresh caused by that same effect - therefore lands behind it,
     * which is the only way an animation can precede the board change it depicts when
     * the change is announced first.
     * <p>
     * The queue will not start an unsealed step, so a reservation must always be
     * {@link #seal()}ed. It is force-sealed after a timeout rather than trusted.
     */
    public AnimationStep reserved() {
        this.sealed = false;
        return this;
    }

    /** Mark a reservation ready to play. */
    public void seal() {
        this.sealed = true;
    }

    boolean isSealed() {
        return sealed;
    }

    public AnimationStep add(final Anim anim) {
        if (anim != null) {
            anims.add(anim);
        }
        return this;
    }

    /** UI change applied when the step starts, before any of its animations run. */
    public AnimationStep before(final Runnable r) {
        this.before = r;
        return this;
    }

    /** UI change applied once every animation in the step has finished. */
    public AnimationStep after(final Runnable r) {
        this.after = r;
        return this;
    }

    /** Extra dwell after the animations finish, so back-to-back events stay readable. */
    public AnimationStep hold(final long ms) {
        this.holdMs = Math.max(0L, ms);
        return this;
    }

    public String getLabel() {
        return label;
    }

    public List<Anim> getAnims() {
        return Collections.unmodifiableList(anims);
    }

    Runnable getBefore() {
        return before;
    }

    Runnable getAfter() {
        return after;
    }

    long getHoldMs() {
        return holdMs;
    }

    /** Longest animation in the step, plus the dwell; what the step costs in wall time. */
    public long getTotalMs() {
        long longest = 0L;
        for (final Anim a : anims) {
            longest = Math.max(longest, a.getDurationMs());
        }
        return longest + holdMs;
    }

    public boolean isEmpty() {
        return anims.isEmpty() && holdMs == 0L;
    }
}
