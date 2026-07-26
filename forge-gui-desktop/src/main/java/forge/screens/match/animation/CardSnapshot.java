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

    private CardSnapshot(final BufferedImage image, final Rectangle bounds) {
        this.image = image;
        this.bounds = bounds;
    }

    public BufferedImage getImage() {
        return image;
    }

    /** Where the card was, in the coordinate space of {@code reference}. */
    public Rectangle getBounds() {
        return new Rectangle(bounds);
    }

    public Point getCenter() {
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
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
            try {
                // paint() rather than print(): the panel's own paint applies the tap
                // rotation and highlight frames, which should travel with the ghost.
                panel.paint(g);
            } finally {
                g.dispose();
            }
            final Point at = SwingUtilities.convertPoint(panel.getParent(),
                    panel.getX(), panel.getY(), reference);
            return new CardSnapshot(img, new Rectangle(at.x, at.y, w, h));
        } catch (final RuntimeException e) {
            // Panels mid-teardown can throw from paint; losing one ghost is harmless.
            return null;
        }
    }
}
