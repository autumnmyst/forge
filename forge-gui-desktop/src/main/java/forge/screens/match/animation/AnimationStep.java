package forge.screens.match.animation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.tinylog.Logger;

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
        if (played) {
            runNow(r);
            return this;
        }
        this.after = r;
        return this;
    }

    /**
     * Add a change to run after the step, keeping any already registered.
     * <p>
     * Distinct from {@link #after} because a step can accumulate consequences from more
     * than one place - a strike carries both its own effect and the bite it takes out of
     * the defending player's life total - and the second must not silently drop the first.
     */
    public AnimationStep then(final Runnable r) {
        if (r == null) {
            return this;
        }
        if (played) {
            runNow(r);
            return this;
        }
        final Runnable existing = after;
        after = existing == null ? r : () -> {
            existing.run();
            r.run();
        };
        return this;
    }

    /**
     * Whether this step's consequences have already been applied.
     * <p>
     * A reservation the queue gave up waiting for is played empty, and whatever was going
     * to fill it arrives afterwards. Its animations are simply lost, which is the accepted
     * cost - but its state change is not optional, and attaching it to a step that has
     * been and gone used to drop it silently. That is how a card entering play could end
     * up permanently invisible: the reveal was the hook nobody ever ran.
     */
    private volatile boolean played;

    /** Called by the queue once the {@code after} hook has run. */
    void markPlayed() {
        played = true;
    }

    private void runNow(final Runnable r) {
        if (r == null) {
            return;
        }
        try {
            r.run();
        } catch (final Exception e) {
            Logger.error(e, "Animation step '" + label + "' failed applying a late change");
        }
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
            longest = Math.max(longest, a.getTotalMs());
        }
        return longest + holdMs;
    }

    public boolean isEmpty() {
        return anims.isEmpty() && holdMs == 0L;
    }
}
