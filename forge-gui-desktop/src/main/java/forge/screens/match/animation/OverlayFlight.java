package forge.screens.match.animation;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import forge.view.arcane.CardPanel;

/**
 * Moves a card by drawing a copy of it on the overlay rather than displacing the real
 * panel.
 * <p>
 * A panel driven by its own render transform is still painted by its container, so it
 * is clipped at the container's edge and keeps its place in the z-order - an attacker
 * lunging at a blocker slides underneath its own neighbours and stops at the edge of
 * its battlefield. Drawing on the overlay puts the moving card above everything and
 * lets it cross between zones.
 * <p>
 * The real panel is hidden for the duration and restored at the end, including when the
 * animation is cut short, so nothing is left invisible.
 */
public final class OverlayFlight extends Anim {

    private final CardPanel panel;
    private final BufferedImage image;
    private final Rectangle start;
    private final int dx, dy;
    private final float outFraction;
    private final boolean returns;
    /** What the copy must be scaled to by the end; 1 for anything that returns home. */
    private double toScale = 1;

    private double x, y;
    private double scale = 1;

    /**
     * Strike at something and fall back, for combat.
     *
     * @param toward destination centre in overlay coordinates.
     * @param reach  fraction of the distance actually travelled; a full trip would leave
     *               the attacker's own row looking empty mid-swing.
     */
    public static OverlayFlight lunge(final CardPanel panel, final CardSnapshot snap,
            final Point toward, final float reach, final long durationMs) {
        final Point from = snap.getCenter();
        return new OverlayFlight(panel, snap,
                Math.round((toward.x - from.x) * reach),
                Math.round((toward.y - from.y) * reach),
                0.35f, true, durationMs);
    }

    /**
     * Slide and resize a card from where it used to be onto where layout has just put it.
     * <p>
     * Drawn on the overlay rather than through the panel's own render transform, because
     * Swing clips a component to its bounds - and those bounds are already the new,
     * often smaller, rectangle. Transforming in place therefore shows the card cropped
     * into its destination box and moving inside it, instead of the whole card
     * travelling and shrinking.
     *
     * @param toScale the card's new width over its old one; below 1 when it shrank. The
     *                copy is already the old size, so the ramp runs from 1 to this - not
     *                the other way about, which drew a shrinking card larger than it had
     *                ever been and then snapped it down.
     */
    public static OverlayFlight reflow(final CardPanel panel, final CardSnapshot snap,
            final Point toTopLeft, final double toScale, final long durationMs) {
        // Stated as where the copy is now and where it must end up, rather than as an
        // offset. The copy may have been taken before layout ran or after it, so its own
        // position is not a fixed reference and an offset would mean different things in
        // the two cases.
        final Rectangle at = snap.getBounds();
        final OverlayFlight f = new OverlayFlight(panel, snap,
                at.x - toTopLeft.x, at.y - toTopLeft.y, 1f, false, durationMs);
        f.toScale = toScale;
        f.start.setLocation(toTopLeft);
        return f;
    }

    private OverlayFlight(final CardPanel panel, final CardSnapshot snap, final int dx, final int dy,
            final float outFraction, final boolean returns, final long durationMs) {
        super(durationMs);
        this.panel = panel;
        this.image = snap.getImage();
        this.start = snap.getBounds();
        this.dx = dx;
        this.dy = dy;
        this.outFraction = outFraction;
        this.returns = returns;
        this.x = start.x;
        this.y = start.y;
    }

    @Override
    protected void onStart() {
        if (panel != null) {
            panel.setRenderAlpha(0f);
            panel.repaint();
        }
    }

    @Override
    protected void update(final float t) {
        final float e;
        if (returns) {
            // Out fast, back slow: the recoil is what sells the impact.
            e = t < outFraction ? Ease.out(t / outFraction)
                    : 1f - Ease.inOut((t - outFraction) / (1f - outFraction));
            scale = 1 + 0.06f * e;
        } else {
            // Settling: start where the card was, at the size it was, and ease onto the
            // panel's real position and size. The copy is a picture of the old card, so
            // its scale starts at 1 by definition.
            e = 1f - Ease.inOut(t);
            scale = Ease.lerp(1f, (float) toScale, Ease.inOut(t));
        }
        x = start.x + dx * e;
        y = start.y + dy * e;
    }

    @Override
    protected void onEnd() {
        if (panel != null) {
            panel.clearRenderTransform();
            panel.repaint();
        }
    }

    @Override
    public void draw(final Graphics2D g) {
        // A lunge grows about the card's own centre, so its centre is where the card
        // already is. A reflow instead resizes into a box whose top-left corner is the
        // destination, so its centre has to follow the shrinking image - otherwise the
        // copy ends up half the width difference away from the panel it hands over to,
        // and the card jumps sideways the instant the animation clears.
        final double drawn = returns ? 1 : scale;
        final double cx = x + start.width * drawn / 2.0;
        final double cy = y + start.height * drawn / 2.0;
        g.translate(cx, cy);
        g.scale(scale, scale);
        g.drawImage(image, -start.width / 2, -start.height / 2, null);
    }
}
