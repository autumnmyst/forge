package forge.screens.match.animation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;

/**
 * An area effect sweeping over a whole battlefield: sparks seeded across the region
 * and thrown outward, in the colours of the card that caused it.
 * <p>
 * One of these is played per affected battlefield, so a wrath visibly lands on both
 * players' halves while a one-sided sweep only marks the half it touched.
 */
public final class BurstAnim extends Anim {

    private final Particles particles;
    private final Rectangle area;
    private final List<Color> palette;
    private final int density;

    private float lastT;
    private boolean seeded;

    /**
     * @param area    region in overlay coordinates to cover.
     * @param density roughly how many sparks to throw; scaled down for small regions.
     */
    public BurstAnim(final Rectangle area, final List<Color> palette, final int density,
            final long durationMs) {
        super(durationMs);
        this.area = new Rectangle(area);
        this.palette = palette;
        this.density = Math.max(12, density);
        this.particles = new Particles(Math.min(900, this.density * 2 + 60));
    }

    @Override
    protected void update(final float t) {
        final long deltaMs = Math.max(1L, (long) ((t - lastT) * getDurationMs()));
        lastT = t;
        if (!seeded) {
            seeded = true;
            seed();
        }
        // Slight downward drift so the sweep settles rather than hanging in the air.
        particles.advance(deltaMs, 0.00018f, 0.04f);
    }

    private void seed() {
        final float cx = area.x + area.width / 2f;
        final float cy = area.y + area.height / 2f;
        for (int i = 0; i < density; i++) {
            final float px = area.x + Particles.rng().nextFloat() * area.width;
            final float py = area.y + Particles.rng().nextFloat() * area.height;
            // Push away from the middle, so the effect reads as expanding across the zone.
            final double angle = Math.atan2(py - cy, px - cx);
            final Color c = CardColors.brighten(Particles.pick(palette, i), 0.4f);
            particles.spawnSpread(px, py, angle, 0.10f, 1.4f, 4.0f, 620f, c);
        }
    }

    @Override
    public void draw(final Graphics2D g) {
        particles.draw(g);
    }
}
