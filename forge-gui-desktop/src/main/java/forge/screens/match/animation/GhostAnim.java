package forge.screens.match.animation;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * Plays a {@link CardSnapshot} out after its real panel is gone: the card fades while
 * drifting and shrinking slightly, which reads as a permanent leaving the board.
 * <p>
 * Optionally travels to a destination, so the same animation covers a creature dying
 * in place and a card being pulled back to its owner's hand.
 */
public final class GhostAnim extends Anim {

    private final BufferedImage image;
    private final Rectangle start;
    private final Point target;
    private final double spin;
    private final float endScale;

    private float alpha = 1f;
    private double x, y, scale;

    /** Fade in place, sinking a little - the death animation. */
    public static GhostAnim fadeOut(final CardSnapshot snap, final long durationMs) {
        return new GhostAnim(snap, null, 0.12, 0.86f, durationMs);
    }

    private GhostAnim(final CardSnapshot snap, final Point target, final double spin,
            final float endScale, final long durationMs) {
        super(durationMs);
        this.image = snap.getImage();
        this.start = snap.getBounds();
        this.target = target == null ? null : new Point(target);
        this.spin = spin;
        this.endScale = endScale;
        this.x = start.x;
        this.y = start.y;
        this.scale = 1;
    }

    @Override
    protected void update(final float t) {
        final float eased = Ease.inOut(t);
        if (target != null) {
            x = Ease.lerp(start.x, target.x - start.width / 2f, eased);
            y = Ease.lerp(start.y, target.y - start.height / 2f, eased);
        } else {
            // No destination: drift down a fraction of the card's own height.
            y = start.y + start.height * 0.18f * eased;
        }
        scale = Ease.lerp(1f, endScale, eased);
        // Hold opacity briefly before fading, so the card is legible as it starts to go.
        alpha = 1f - Ease.in(Ease.clamp01((t - 0.15f) / 0.85f));
    }

    @Override
    public void draw(final Graphics2D g) {
        if (alpha <= 0.01f) {
            return;
        }
        final double cx = x + start.width / 2.0;
        final double cy = y + start.height / 2.0;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Ease.clamp01(alpha)));
        g.translate(cx, cy);
        g.rotate(spin * (1 - alpha));
        g.scale(scale, scale);
        g.drawImage(image, -start.width / 2, -start.height / 2, null);
    }
}
