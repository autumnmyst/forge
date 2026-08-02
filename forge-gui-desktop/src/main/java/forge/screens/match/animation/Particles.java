package forge.screens.match.animation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.Random;

/**
 * A flat pool of sparks shared by every particle effect.
 * <p>
 * Particles are stored in parallel primitive arrays rather than objects: a beam plus a
 * burst can be a few thousand sparks, and allocating that many short-lived objects
 * every frame would hand the EDT a steady stream of garbage-collection pauses in the
 * middle of the animation it is trying to keep smooth.
 * <p>
 * Nothing here knows about cards or the game - callers seed positions and velocities,
 * and the pool only integrates and fades.
 */
public final class Particles {

    private static final Random RNG = new Random();

    private final float[] x, y, vx, vy, life, maxLife, size;
    private final int[] argb;
    private int count;

    public Particles(final int capacity) {
        x = new float[capacity];
        y = new float[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        life = new float[capacity];
        maxLife = new float[capacity];
        size = new float[capacity];
        argb = new int[capacity];
    }

    public int getCount() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * Add one spark.
     *
     * @param lifeMs how long it lives; it fades and shrinks to nothing over that span.
     */
    public void spawn(final float px, final float py, final float dx, final float dy,
            final float radius, final float lifeMs, final Color color) {
        if (count >= x.length) {
            return; // pool full: dropping a spark is invisible, growing the array is not
        }
        x[count] = px;
        y[count] = py;
        vx[count] = dx;
        vy[count] = dy;
        size[count] = radius;
        life[count] = lifeMs;
        maxLife[count] = lifeMs;
        argb[count] = color.getRGB();
        count++;
    }

    /** Seed a spark with a random spread around a direction, in pixels per millisecond. */
    public void spawnSpread(final float px, final float py, final double angle, final float speed,
            final double spreadRadians, final float radius, final float lifeMs, final Color color) {
        final double a = angle + (RNG.nextDouble() - 0.5) * spreadRadians;
        final float jitter = 0.6f + RNG.nextFloat() * 0.8f;
        spawn(px, py, (float) Math.cos(a) * speed * jitter, (float) Math.sin(a) * speed * jitter,
                radius * (0.6f + RNG.nextFloat() * 0.8f), lifeMs * (0.7f + RNG.nextFloat() * 0.6f), color);
    }

    /** Integrate positions and retire expired sparks by swapping the last one down. */
    public void advance(final long deltaMs, final float gravity, final float drag) {
        final float dt = deltaMs;
        final float damping = (float) Math.pow(1f - Ease.clamp01(drag), dt / 16f);
        for (int i = 0; i < count;) {
            life[i] -= dt;
            if (life[i] <= 0f) {
                count--;
                x[i] = x[count];
                y[i] = y[count];
                vx[i] = vx[count];
                vy[i] = vy[count];
                life[i] = life[count];
                maxLife[i] = maxLife[count];
                size[i] = size[count];
                argb[i] = argb[count];
                continue; // the swapped-in spark still needs its own step this frame
            }
            vy[i] += gravity * dt;
            vx[i] *= damping;
            vy[i] *= damping;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            i++;
        }
    }

    /**
     * How much of a spark's life it burns at full strength before it starts to fade.
     * <p>
     * The square curve this replaces was already below a quarter opacity by the halfway
     * point, so a spark spent most of its life too faint to notice and an effect could go
     * off without ever catching the eye.
     */
    private static final float HOLD = 0.5f;

    /** How far the middle of a spark is pushed towards white. */
    private static final float CORE_HEAT = 0.8f;

    /** Where the hot centre gives way to the card's own colour. */
    private static final float[] GLOW_STOPS = { 0f, 0.3f, 1f };

    /**
     * Draw every live spark as a soft dot: a near-white centre inside a coloured halo,
     * which reads as a glow without the cost of a real blur.
     * <p>
     * The hot centre is what makes a spark carry. Drawn only in its card's own colour a
     * spark is competing with a dark table and a busy board, and the darker colours - blue
     * and black especially - lost that fight outright. Burning the middle out towards white
     * gives every spark the same brightness to catch the eye with, and leaves the colour to
     * the halo, where there is room for it to be read.
     */
    public void draw(final Graphics2D g) {
        for (int i = 0; i < count; i++) {
            final float t = Ease.clamp01(life[i] / maxLife[i]);
            final float radius = Math.max(0.6f, size[i] * (0.35f + 0.65f * t));
            final int alpha = Math.round(235 * (t >= HOLD ? 1f : t / HOLD));
            if (alpha <= 2) {
                continue;
            }
            final Color base = new Color(argb[i], false);
            final Color core = whiten(base, CORE_HEAT, alpha);
            final Color mid = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            final Color edge = new Color(base.getRed(), base.getGreen(), base.getBlue(), 0);
            final float halo = radius * 3.0f;
            try {
                g.setPaint(new RadialGradientPaint(new Point2D.Float(x[i], y[i]), halo,
                        GLOW_STOPS, new Color[] { core, mid, edge }));
                g.fillOval(Math.round(x[i] - halo), Math.round(y[i] - halo),
                        Math.round(halo * 2), Math.round(halo * 2));
            } catch (final IllegalArgumentException e) {
                // RadialGradientPaint rejects a zero radius; such a spark is invisible anyway.
            }
        }
    }

    /** Blend towards white, keeping the supplied alpha. */
    private static Color whiten(final Color c, final float amount, final int alpha) {
        return new Color(Ease.lerp(c.getRed(), 255, amount), Ease.lerp(c.getGreen(), 255, amount),
                Ease.lerp(c.getBlue(), 255, amount), alpha);
    }

    /** Colour for spark {@code i} of a run, cycling through a card's colours. */
    public static Color pick(final List<Color> palette, final int i) {
        return palette.get(Math.floorMod(i, palette.size()));
    }

    public static Random rng() {
        return RNG;
    }
}
