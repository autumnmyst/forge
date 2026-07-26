package forge.screens.match.animation;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

/**
 * Full-screen transparent panel the animations paint onto.
 * <p>
 * It sits above the battlefield in the layered pane so flying cards, particles and
 * beams are not clipped by the panel they started in - a card leaving the hand has to
 * cross two docked containers to reach the battlefield, which is impossible to draw
 * from inside either of them.
 * <p>
 * The panel takes no mouse or keyboard listeners on purpose: it covers the whole
 * window, and anything it consumed would be a click the board never sees.
 */
@SuppressWarnings("serial")
public final class AnimationLayer extends JPanel {

    private final List<Anim> extra = new ArrayList<>(4);
    private AnimationQueue queue;

    public AnimationLayer() {
        setOpaque(false);
        setFocusable(false);
        setFocusTraversalKeysEnabled(false);
    }

    void setQueue(final AnimationQueue queue) {
        this.queue = queue;
    }

    /**
     * Register something that paints outside the queue's control - the dragged card
     * and its trail, which follow the cursor rather than a timeline.
     */
    public synchronized void addOverlayAnim(final Anim anim) {
        if (anim != null && !extra.contains(anim)) {
            extra.add(anim);
        }
    }

    public synchronized void removeOverlayAnim(final Anim anim) {
        extra.remove(anim);
    }

    private synchronized List<Anim> snapshotExtra() {
        return new ArrayList<>(extra);
    }

    /** True while something outside the queue still needs frames, such as a live drag. */
    public synchronized boolean hasOverlayAnims() {
        return !extra.isEmpty();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        final Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            if (queue != null) {
                for (final Anim a : queue.getDrawableAnims()) {
                    paintOne(g2d, a);
                }
            }
            for (final Anim a : snapshotExtra()) {
                paintOne(g2d, a);
            }
        } finally {
            g2d.dispose();
        }
    }

    /**
     * Each animation draws through its own copy of the graphics context, so one that
     * leaves a transform or composite behind cannot corrupt the ones after it.
     */
    private static void paintOne(final Graphics2D g2d, final Anim a) {
        final Graphics2D scratch = (Graphics2D) g2d.create();
        try {
            a.draw(scratch);
        } catch (final RuntimeException e) {
            // A painting failure must not take down the EDT or blank the whole overlay.
        } finally {
            scratch.dispose();
        }
    }

    /** The overlay never blocks input; the board underneath handles every click. */
    @Override
    public boolean contains(final int x, final int y) {
        return false;
    }
}
