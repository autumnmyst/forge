package forge.screens.match.animation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.List;

/**
 * A spray of sparks at the point where something lands.
 * <p>
 * This is the combat counterpart to {@link BeamAnim}. A beam exists to carry an effect
 * across a gap that nothing else crosses - a spell resolving on a distant target, a
 * burn spell hitting a creature while its own card sits still. In combat the attacker
 * physically lunges into what it hits, so the travel is already on screen and a beam
 * would draw the same journey twice. What combat is missing is the collision, which is
 * all this plays.
 * <p>
 * The burst is held back until {@code triggerAt} so it fires when the lunging card
 * actually arrives rather than as it sets off.
 */
public final class ImpactAnim extends Anim {

    private final Particles particles;
    private final List<Color> palette;
    private final Point at;
    private final float strength;
    private final float triggerAt;

    private float lastT;
    private boolean burst;

    /**
     * @param strength  scales the spray; pass the damage dealt.
     * @param triggerAt progress at which the sparks appear, matched to the moment the
     *                  attacker reaches full extension.
     */
    public ImpactAnim(final Point at, final List<Color> palette, final float strength,
            final long durationMs, final float triggerAt) {
        super(durationMs);
        this.at = new Point(at);
        this.palette = palette;
        this.strength = Math.max(1f, strength);
        this.triggerAt = Ease.clamp01(triggerAt);
        this.particles = new Particles(160);
    }

    @Override
    protected void update(final float t) {
        final long deltaMs = Math.max(1L, (long) ((t - lastT) * getDurationMs()));
        lastT = t;
        if (!burst && t >= triggerAt) {
            burst = true;
            spawn();
        }
        // A touch of gravity so the sparks fall away from the point of contact rather
        // than hanging in a ring.
        particles.advance(deltaMs, 0.00022f, 0.05f);
    }

    private void spawn() {
        final int n = Math.min(80, 20 + Math.round(strength * 7f));
        final float speed = 0.18f + Math.min(0.2f, strength * 0.02f);
        for (int i = 0; i < n; i++) {
            final Color c = CardColors.brighten(Particles.pick(palette, i), 0.55f);
            particles.spawnSpread(at.x, at.y, (Math.PI * 2 * i) / n, speed, 0.8f, 3.4f, 460f, c);
        }
    }

    @Override
    public void draw(final Graphics2D g) {
        particles.draw(g);
    }
}
