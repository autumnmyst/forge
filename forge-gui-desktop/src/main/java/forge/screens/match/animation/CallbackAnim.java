package forge.screens.match.animation;

/**
 * A state change scheduled part-way through a step, rather than at its end.
 * <p>
 * A step's {@code after} hook fires once every animation in it has finished, which is
 * too late for anything that should coincide with a moment inside the step - a player's
 * life falling as the attacker connects, a card's new state appearing as the beam that
 * changed it arrives. Those read as consequences of the impact, so they have to happen
 * at the impact and not after the recoil has played out.
 * <p>
 * Carries no visuals of its own. Because it is an {@link Anim} it inherits the guarantee
 * that matters: {@link Anim#finish()} runs it whether the step played out in full, was
 * accelerated, or was cut short by the skip hotkey, so the change is never lost.
 */
public final class CallbackAnim extends Anim {

    private final Runnable action;

    /** @param atMs how far into the step the change should be applied. */
    public static CallbackAnim at(final long atMs, final Runnable action) {
        final CallbackAnim c = new CallbackAnim(action);
        c.delayedBy(atMs);
        return c;
    }

    private CallbackAnim(final Runnable action) {
        // As short as an animation may be: all the timing lives in the delay, and the
        // body is a single instant.
        super(1L);
        this.action = action;
    }

    @Override
    protected void update(final float t) {
    }

    @Override
    protected void onEnd() {
        if (action != null) {
            action.run();
        }
    }
}
