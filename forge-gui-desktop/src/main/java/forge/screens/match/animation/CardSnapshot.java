package forge.screens.match.animation;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import forge.view.arcane.CardPanel;

/**
 * A picture of a card panel taken just before it stops existing, together with where
 * it was on screen.
 * <p>
 * Death and bounce animations cannot use the live panel: by the time the card has
 * left the battlefield its panel has already been disposed and removed by
 * {@code PlayArea}'s zone diff. Copying the pixels while the panel is still valid lets
 * the departure play out on the overlay long after the real component is gone.
 */
public final class CardSnapshot {

    private final BufferedImage image;
    private final Rectangle bounds;
    private final Point cardCentre;

    private CardSnapshot(final BufferedImage image, final Rectangle bounds, final Point cardCentre) {
        this.image = image;
        this.bounds = bounds;
        this.cardCentre = cardCentre;
    }

    public BufferedImage getImage() {
        return image;
    }

    /** Where the card was, in the coordinate space of {@code reference}. */
    public Rectangle getBounds() {
        return new Rectangle(bounds);
    }

    /**
     * Centre of the card face, which is not the centre of {@link #getBounds()} - the
     * panel reserves room around the card for its tap rotation, off-centre. Use this for
     * anything aimed at the card and the bounds only for drawing the copy.
     */
    public Point getCenter() {
        return new Point(cardCentre);
    }

    /**
     * Copy a panel's pixels and translate its position into {@code reference}'s space.
     *
     * @return null if the panel is not currently displayable, in which case there is
     *         nothing on screen to animate away.
     */
    public static CardSnapshot capture(final CardPanel panel, final JComponent reference) {
        if (panel == null || reference == null || !panel.isShowing() || !reference.isShowing()) {
            return null;
        }
        final int w = panel.getWidth();
        final int h = panel.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        try {
            final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = img.createGraphics();
            // Copy the card as it really looks, not as an animation is currently posing
            // it. paint() refuses to draw a panel an animation has faded out, and would
            // otherwise bake in that animation's offset and scale as well - so a card
            // being re-captured mid-move came out blank or wrongly placed, and a tapped
            // one could be copied before its rotation had been applied.
            final double offX = panel.getRenderOffsetX();
            final double offY = panel.getRenderOffsetY();
            final double scale = panel.getRenderScale();
            final float alpha = panel.getRenderAlpha();
            panel.clearRenderTransform();
            try {
                // paint() rather than print(): the panel's own paint applies the tap
                // rotation and highlight frames, which should travel with the copy.
                panel.paint(g);
            } finally {
                g.dispose();
                panel.setRenderOffset(offX, offY);
                panel.setRenderScale(scale);
                panel.setRenderAlpha(alpha);
            }
            final Point at = SwingUtilities.convertPoint(panel.getParent(),
                    panel.getX(), panel.getY(), reference);
            final Point face = SwingUtilities.convertPoint(panel.getParent(),
                    panel.getCardX() + panel.getCardWidth() / 2,
                    panel.getCardY() + panel.getCardHeight() / 2, reference);
            return new CardSnapshot(img, new Rectangle(at.x, at.y, w, h), face);
        } catch (final RuntimeException e) {
            // Panels mid-teardown can throw from paint; losing one ghost is harmless.
            return null;
        }
    }
}
