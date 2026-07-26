package forge.screens.match.animation;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;

/**
 * The card that follows the cursor while it is being dragged out of hand.
 * <p>
 * It does not sit on the cursor: it chases it. Position is a spring toward the pointer
 * and tilt is proportional to how fast it is being swung sideways, so the card trails,
 * banks into the movement and rocks back to level when the hand stops. That lag is the
 * whole trick behind a card feeling like it has mass rather than being glued to the
 * mouse.
 * <p>
 * Its duration is nominal - the drag controller adds and removes it explicitly, so it
 * lives exactly as long as the drag does.
 */
public final class DragGhost extends Anim {

    /** Fraction of the remaining gap closed per 16ms; lower trails further behind. */
    private static final float FOLLOW = 0.28f;
    /** Radians of bank per pixel-per-frame of sideways speed. */
    private static final double TILT_PER_SPEED = 0.020;
    private static final double MAX_TILT = Math.toRadians(22);
    /** How fast the tilt itself converges, so direction changes rock rather than snap. */
    private static final float TILT_FOLLOW = 0.18f;

    private final BufferedImage image;
    private final int width, height;

    private double x, y;
    private double targetX, targetY;
    private double tilt, targetTilt;
    private double lastTargetX;
    private float scale = 1f;
    private boolean armed;

    public DragGhost(final CardSnapshot snap, final Point start) {
        super(Long.MAX_VALUE / 4);
        this.image = snap.getImage();
        this.width = snap.getBounds().width;
        this.height = snap.getBounds().height;
        this.x = start.x;
        this.y = start.y;
        this.targetX = start.x;
        this.targetY = start.y;
        this.lastTargetX = start.x;
    }

    /** Move the pointer the card is chasing. */
    public void setTarget(final Point p) {
        this.targetX = p.x;
        this.targetY = p.y;
    }

    /**
     * Whether the card is currently over a legal drop area. An armed card lifts and
     * squares up, which is the feedback that releasing now will actually do something.
     */
    public void setArmed(final boolean armed) {
        this.armed = armed;
    }

    public boolean isArmed() {
        return armed;
    }

    public Point getPosition() {
        return new Point((int) Math.round(x), (int) Math.round(y));
    }

    @Override
    protected void update(final float t) {
        // Frame-rate independence is not worth the complexity here: the clock is fixed
        // at ~60fps and a dropped frame in a drag is imperceptible.
        final double dx = targetX - x;
        final double dy = targetY - y;
        x += dx * FOLLOW;
        y += dy * FOLLOW;

        final double swing = targetX - lastTargetX;
        lastTargetX = targetX;
        // An armed card straightens up; a loose one banks into the swing.
        targetTilt = armed ? 0 : clamp(swing * TILT_PER_SPEED);
        tilt += (targetTilt - tilt) * TILT_FOLLOW;

        final float wanted = armed ? 1.06f : 0.98f;
        scale += (wanted - scale) * 0.2f;
    }

    private static double clamp(final double v) {
        return Math.max(-MAX_TILT, Math.min(MAX_TILT, v));
    }

    @Override
    public void draw(final Graphics2D g) {
        // A soft shadow under the card reads as height above the table.
        final int sx = (int) Math.round(x);
        final int sy = (int) Math.round(y);
        g.setColor(new Color(0, 0, 0, armed ? 90 : 60));
        g.fillOval(sx - width / 3, sy + height / 2 - 6, (2 * width) / 3, 14);

        g.translate(x, y);
        g.rotate(tilt);
        g.scale(scale, scale);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, armed ? 1f : 0.92f));
        g.drawImage(image, -width / 2, -height / 2, null);
    }
}
