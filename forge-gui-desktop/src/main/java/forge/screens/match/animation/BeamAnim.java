package forge.screens.match.animation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.List;
import java.util.function.Supplier;

/**
 * A stream of sparks travelling from a source to a destination, in the source card's
 * colours.
 * <p>
 * Deliberately follows the same source-to-target reading as the targeting arrows: the
 * arrows say what a spell <em>will</em> hit, this says what it just <em>did</em> hit.
 * Sparks are emitted continuously over the first part of the animation and then left
 * to arrive, so the beam has a visible head and tail instead of appearing all at once.
 */
public final class BeamAnim extends Anim {

    /** Fraction of the duration spent emitting; the rest lets the last sparks land. */
    private static final float EMIT_FRACTION = 0.55f;
    private static final int SPARKS_PER_FRAME = 3;

    private final Particles particles;
    private final List<Color> palette;
    private final Supplier<Point> fromSource;
    private final Supplier<Point> toSource;
    private final float impactStrength;

    private Point from;
    private Point to;
    private float lastT;
    private int spawned;
    private boolean burst;

    /**
     * @param impactStrength scales the splash when the beam lands; pass the damage
     *                       amount, or 1 for a plain connection.
     */
    public BeamAnim(final Point from, final Point to, final List<Color> palette,
            final float impactStrength, final long durationMs) {
        this(() -> from, () -> to, palette, impactStrength, durationMs);
    }

    /**
     * A beam whose ends are worked out when it starts rather than when it is built.
     * <p>
     * Needed wherever the thing being drawn to or from does not exist yet at build time.
     * A permanent's enters-the-battlefield trigger is the case in point: the trigger is
     * put on the stack before the permanent has a card panel, so asking then gives
     * nothing and the trail fell back to being drawn out of the player. By the time the
     * step actually plays, the board refresh ahead of it has built the panel.
     * <p>
     * A supplier returning null leaves that end unresolved and the beam simply does not
     * draw, which is the same outcome as never having been given a point.
     */
    public BeamAnim(final Supplier<Point> from, final Supplier<Point> to, final List<Color> palette,
            final float impactStrength, final long durationMs) {
        super(durationMs);
        this.fromSource = from;
        this.toSource = to;
        this.palette = palette;
        this.impactStrength = Math.max(1f, impactStrength);
        this.particles = new Particles(420);
    }

    @Override
    protected void onStart() {
        from = fromSource == null ? null : fromSource.get();
        to = toSource == null ? null : toSource.get();
    }

    @Override
    protected void update(final float t) {
        if (from == null || to == null) {
            return;
        }
        final long deltaMs = Math.max(1L, (long) ((t - lastT) * getDurationMs()));
        lastT = t;

        if (t <= EMIT_FRACTION) {
            emit(t / EMIT_FRACTION);
        } else if (!burst) {
            burst = true;
            splash();
        }
        particles.advance(deltaMs, 0f, 0.06f);
    }

    /** Release sparks from a point sliding along the path, so the beam appears to travel. */
    private void emit(final float head) {
        final float hx = Ease.lerp(from.x, to.x, Ease.out(head));
        final float hy = Ease.lerp(from.y, to.y, Ease.out(head));
        final double angle = Math.atan2(to.y - from.y, to.x - from.x);
        for (int i = 0; i < SPARKS_PER_FRAME; i++) {
            final Color c = CardColors.brighten(Particles.pick(palette, spawned++), 0.35f);
            particles.spawnSpread(hx, hy, angle, 0.22f, 0.9f, 3.2f, 420f, c);
        }
    }

    /** Radial spray at the destination, sized by how hard the effect hit. */
    private void splash() {
        final int n = Math.min(90, 18 + Math.round(impactStrength * 8f));
        final float speed = 0.16f + Math.min(0.22f, impactStrength * 0.02f);
        for (int i = 0; i < n; i++) {
            final Color c = CardColors.brighten(Particles.pick(palette, i), 0.5f);
            particles.spawnSpread(to.x, to.y, (Math.PI * 2 * i) / n, speed, 0.7f, 3.6f, 520f, c);
        }
    }

    @Override
    public void draw(final Graphics2D g) {
        particles.draw(g);
    }
}
