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
     * Draw every live spark as a soft dot: an opaque core inside a wider halo, which
     * reads as a glow without the cost of a real blur.
     */
    public void draw(final Graphics2D g) {
        for (int i = 0; i < count; i++) {
            final float t = Ease.clamp01(life[i] / maxLife[i]);
            final float radius = Math.max(0.6f, size[i] * (0.35f + 0.65f * t));
            final Color base = new Color(argb[i], false);
            final int alpha = Math.round(210 * t * t);
            if (alpha <= 2) {
                continue;
            }
            final Color core = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            final Color edge = new Color(base.getRed(), base.getGreen(), base.getBlue(), 0);
            final float halo = radius * 2.6f;
            try {
                g.setPaint(new RadialGradientPaint(new Point2D.Float(x[i], y[i]), halo,
                        new float[] { 0f, 1f }, new Color[] { core, edge }));
                g.fillOval(Math.round(x[i] - halo), Math.round(y[i] - halo),
                        Math.round(halo * 2), Math.round(halo * 2));
            } catch (final IllegalArgumentException e) {
                // RadialGradientPaint rejects a zero radius; such a spark is invisible anyway.
            }
        }
    }

    /** Colour for spark {@code i} of a run, cycling through a card's colours. */
    public static Color pick(final List<Color> palette, final int i) {
        return palette.get(Math.floorMod(i, palette.size()));
    }

    public static Random rng() {
        return RNG;
    }
}
