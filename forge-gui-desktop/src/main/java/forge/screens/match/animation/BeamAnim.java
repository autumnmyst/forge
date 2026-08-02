package forge.screens.match.animation;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.function.Supplier;

/**
 * A bolt of light travelling from a source to a destination, in the source card's
 * colours, with a wake of sparks behind it and a splash where it lands.
 * <p>
 * Deliberately follows the same source-to-target reading as the targeting arrows: the
 * arrows say what a spell <em>will</em> hit, this says what it just <em>did</em> hit.
 * The bolt stretches out of the source and gathers into the target rather than appearing
 * along the whole path at once, so it has a direction to read.
 */
public final class BeamAnim extends Anim {

    /** Fraction of the duration spent emitting; the rest lets the last sparks land. */
    private static final float EMIT_FRACTION = 0.55f;
    private static final int SPARKS_PER_FRAME = 5;

    /**
     * When the tail sets off and when it catches up, as fractions of the run.
     * <p>
     * The gap between tail and head is the bolt's length, so starting the tail late gives
     * it something to be: it stretches out of the source while the head runs ahead, then
     * closes into the target once the head has arrived. Both ends moving together would
     * be a fixed-length dash sliding across the board, which reads as a shape being moved
     * rather than as something being fired.
     */
    private static final float TAIL_START = 0.18f;
    private static final float TAIL_END = 0.82f;

    /** Width of the bright inner line, in pixels; the outer glow is a multiple of it. */
    private static final float CORE_WIDTH = 2.6f;
    private static final float GLOW_SCALE = 3.4f;

    /** Radius of the glow carried at the leading end. */
    private static final float HEAD_RADIUS = 11f;

    private static final float[] TRAIL_STOPS = { 0f, 0.45f, 1f };
    private static final float[] HEAD_STOPS = { 0f, 0.35f, 1f };

    private final Particles particles;
    private final List<Color> palette;
    private final Supplier<Point> fromSource;
    private final Supplier<Point> toSource;
    private final float impactStrength;
    private final float width;

    private Point from;
    private Point to;
    private float lastT;
    private float head;
    private float tail;
    private int spawned;
    private boolean burst;

    /**
     * @param impactStrength scales the splash when the beam lands, and thickens the bolt
     *                       a little; pass the damage amount, or 1 for a plain connection.
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
        // A harder hit draws a heavier bolt, but only slightly: the splash at the far end
        // is what is meant to carry the size of the effect, and a beam thick enough to
        // announce six damage on its own would cover the creature taking it.
        this.width = CORE_WIDTH * (1f + Math.min(0.7f, (this.impactStrength - 1f) * 0.12f));
        this.particles = new Particles(480);
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

        head = Ease.out(Ease.clamp01(t / EMIT_FRACTION));
        tail = Ease.in(Ease.clamp01((t - TAIL_START) / (TAIL_END - TAIL_START)));

        if (t <= EMIT_FRACTION) {
            emit();
        } else if (!burst) {
            burst = true;
            splash();
        }
        particles.advance(deltaMs, 0f, 0.06f);
    }

    /**
     * Release sparks from the head as it slides along the path, thrown backwards.
     * <p>
     * Given the beam's own direction they outran the head and scattered ahead of it,
     * leaving the thinnest part of the effect strung along the path it had covered. Cast
     * behind instead, they settle into a wake that goes on marking where the bolt has
     * been after the head has moved on.
     */
    private void emit() {
        final float hx = Ease.lerp((float) from.x, to.x, head);
        final float hy = Ease.lerp((float) from.y, to.y, head);
        final double angle = Math.atan2(from.y - to.y, from.x - to.x);
        for (int i = 0; i < SPARKS_PER_FRAME; i++) {
            final Color c = CardColors.brighten(Particles.pick(palette, spawned++), 0.35f);
            particles.spawnSpread(hx, hy, angle, 0.09f, 1.5f, 3.2f, 460f, c);
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
        if (from != null && to != null) {
            drawBolt(g);
        }
        particles.draw(g);
    }

    /**
     * The bolt itself: an unbroken stretch of light from the tail to the head.
     * <p>
     * Sparks alone were never enough to follow. A few of them a frame, over the fifth of
     * a second the head takes to cross the table, is a scatter of dots with gaps between
     * them, and the eye is given nothing to join up - so a beam could cross the whole
     * window without ever being seen to travel. A drawn segment is continuous by
     * construction, and drawing it twice, a wide dim pass under a narrow bright one, is
     * what makes it read as light rather than as a line.
     */
    private void drawBolt(final Graphics2D g) {
        final Point2D.Float tp = at(tail);
        final Point2D.Float hp = at(head);
        if (tp.distance(hp) < 1.0) {
            return; // a gradient needs two distinct points, and this is a dot either way
        }
        // Ends of the palette rather than one colour, so a gold card shows both of its
        // colours down the length instead of picking whichever happens to come first.
        final Color body = CardColors.brighten(palette.get(0), 0.3f);
        final Color tip = CardColors.brighten(palette.get(palette.size() - 1), 0.75f);
        stroke(g, tp, hp, width * GLOW_SCALE, CardColors.withAlpha(body, 0.20f),
                CardColors.withAlpha(tip, 0.34f));
        stroke(g, tp, hp, width, CardColors.withAlpha(body, 0.55f),
                CardColors.withAlpha(tip, 0.92f));
        if (head < 1f) {
            drawHead(g, hp, tip);
        }
    }

    /** One pass of the bolt, fading from nothing at the tail to full at the head. */
    private static void stroke(final Graphics2D g, final Point2D tailAt, final Point2D headAt,
            final float thickness, final Color bodyColor, final Color tipColor) {
        g.setPaint(new LinearGradientPaint(tailAt, headAt, TRAIL_STOPS,
                new Color[] { CardColors.withAlpha(bodyColor, 0f), bodyColor, tipColor }));
        g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(tailAt, headAt));
    }

    /** A bright point at the leading end, to give the eye something to travel with. */
    private static void drawHead(final Graphics2D g, final Point2D.Float p, final Color tip) {
        g.setPaint(new RadialGradientPaint(p, HEAD_RADIUS, HEAD_STOPS, new Color[] {
                CardColors.withAlpha(CardColors.brighten(tip, 0.85f), 0.95f),
                CardColors.withAlpha(tip, 0.45f),
                CardColors.withAlpha(tip, 0f) }));
        g.fillOval(Math.round(p.x - HEAD_RADIUS), Math.round(p.y - HEAD_RADIUS),
                Math.round(HEAD_RADIUS * 2), Math.round(HEAD_RADIUS * 2));
    }

    /** The point a fraction of the way along the path. */
    private Point2D.Float at(final float f) {
        return new Point2D.Float(Ease.lerp((float) from.x, to.x, f),
                Ease.lerp((float) from.y, to.y, f));
    }
}
