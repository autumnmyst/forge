package forge.screens.match.animation;

/**
 * Interpolation curves. Every animation gets its progress as a linear 0..1 and
 * shapes it through one of these, so motion reads as weighted rather than robotic.
 */
public final class Ease {

    private Ease() {
    }

    /** Slow at both ends, quick through the middle. The default for card travel. */
    public static float inOut(final float t) {
        return t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2) / 2f;
    }

    /** Fast start, gentle landing. Good for something arriving at a resting place. */
    public static float out(final float t) {
        return 1f - (1f - t) * (1f - t);
    }

    /** Gentle start, fast finish. Good for something being flung away. */
    public static float in(final float t) {
        return t * t;
    }

    /** Overshoots the target slightly before settling, which reads as momentum. */
    public static float backOut(final float t) {
        final float c1 = 1.70158f;
        final float c3 = c1 + 1f;
        final float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    /** Out and back: 0 at both ends, 1 at the midpoint. Used for lunges. */
    public static float pingPong(final float t) {
        return t < 0.5f ? out(t * 2f) : out((1f - t) * 2f);
    }

    public static float lerp(final float from, final float to, final float t) {
        return from + (to - from) * t;
    }

    public static int lerp(final int from, final int to, final float t) {
        return Math.round(from + (to - from) * t);
    }

    public static float clamp01(final float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
