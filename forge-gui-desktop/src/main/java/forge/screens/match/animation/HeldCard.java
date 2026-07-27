package forge.screens.match.animation;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.List;

import forge.view.arcane.CardPanel;

/**
 * A card in flight for the whole length of a cast.
 * <p>
 * It leaves the hand, settles on the stack, and stays there for as long as the game is
 * asking questions about it - paying mana, choosing targets. Only when the cast is
 * finally decided does it go anywhere: onto the battlefield if it became a permanent,
 * dissolving into its own colours if it resolved as a spell, or back to hand if it was
 * cancelled or countered.
 * <p>
 * Its lifetime is driven from outside rather than by a timer, because the length of a
 * cast is however long the player takes. The one exception is {@link #beginRelease()},
 * which starts a short fuse so a resolution that never produces a permanent still
 * cleans itself up.
 */
public final class HeldCard extends Anim {

    /** Grace period after resolution for a permanent's panel to appear before dissolving. */
    private static final long RELEASE_GRACE_MS = 700L;
    /** Fraction of the remaining gap closed per 16ms; lower drifts more slowly. */
    private static final float FOLLOW = 0.12f;
    private static final long DISSOLVE_MS = 620L;

    private enum Phase { MOVING, DISSOLVING, DONE }

    private final BufferedImage image;
    private final int width, height;
    private final List<Color> palette;
    private final Particles particles = new Particles(220);

    private double x, y;
    private double targetX, targetY;
    private double scale = 1;
    private double targetScale = 1;
    private float alpha = 1f;

    private Phase phase = Phase.MOVING;
    private long releaseFuseMs = -1;
    private long dissolveElapsed;
    private CardPanel landingOn;
    private boolean seeded;

    public HeldCard(final CardSnapshot snap, final List<Color> palette) {
        super(Long.MAX_VALUE / 4);
        this.image = snap.getImage();
        this.width = snap.getBounds().width;
        this.height = snap.getBounds().height;
        this.palette = palette;
        final Point c = snap.getCenter();
        this.x = c.x;
        this.y = c.y;
        this.targetX = c.x;
        this.targetY = c.y;
    }

    /** Place the card immediately, with no travel. */
    public void snapTo(final Point p) {
        this.x = p.x;
        this.y = p.y;
        this.targetX = p.x;
        this.targetY = p.y;
    }

    /** Where the card should drift to, in overlay coordinates. */
    public void moveTo(final Point p, final double scaleTo) {
        this.targetX = p.x;
        this.targetY = p.y;
        this.targetScale = scaleTo;
        this.releaseFuseMs = -1;
    }

    /**
     * The cast has resolved. If a permanent panel turns up shortly the card lands on it;
     * otherwise it dissolves, which is what a spell going to the graveyard looks like.
     */
    public void beginRelease() {
        if (phase == Phase.MOVING && releaseFuseMs < 0) {
            releaseFuseMs = RELEASE_GRACE_MS;
        }
    }

    /**
     * Settle onto the permanent's real panel and hand over to it.
     * <p>
     * The panel is hidden while the card travels so the same card is not drawn twice,
     * and revealed again as this animation ends.
     */
    public void landOn(final CardPanel panel, final Point centre) {
        if (phase != Phase.MOVING) {
            return;
        }
        landingOn = panel;
        if (panel != null) {
            panel.setRenderAlpha(0f);
            panel.repaint();
        }
        moveTo(centre, 1);
        releaseFuseMs = -1;
        landingCountdownMs = 420L;
    }

    private long landingCountdownMs = -1;

    /** Break apart into sparks; used when a spell resolves without leaving a permanent. */
    public void dissolve() {
        if (phase == Phase.MOVING) {
            phase = Phase.DISSOLVING;
        }
    }

    public boolean isFinishing() {
        return phase != Phase.MOVING;
    }

    /** Where the card is right now, for effects that should originate from it. */
    public Point getPosition() {
        return new Point((int) Math.round(x), (int) Math.round(y));
    }

    @Override
    protected void update(final float t) {
        final long dt = getDeltaMs();
        if (phase == Phase.MOVING) {
            // Exponential approach, corrected for the real frame length so the speed
            // setting and any dropped frames change how fast it travels, not how far.
            final double follow = 1 - Math.pow(1 - FOLLOW, dt / 16.0);
            x += (targetX - x) * follow;
            y += (targetY - y) * follow;
            scale += (targetScale - scale) * follow;

            if (landingCountdownMs > 0) {
                landingCountdownMs -= dt;
                if (landingCountdownMs <= 0) {
                    reveal();
                    phase = Phase.DONE;
                    finish();
                    return;
                }
            } else if (releaseFuseMs > 0) {
                releaseFuseMs -= dt;
                if (releaseFuseMs <= 0) {
                    dissolve();
                }
            }
        } else if (phase == Phase.DISSOLVING) {
            if (!seeded) {
                seeded = true;
                seedDissolve();
            }
            dissolveElapsed += dt;
            alpha = 1f - Ease.clamp01(dissolveElapsed / (float) DISSOLVE_MS);
            particles.advance(dt, 0.00012f, 0.05f);
            if (dissolveElapsed >= DISSOLVE_MS && particles.isEmpty()) {
                phase = Phase.DONE;
                finish();
            }
        }
    }

    private void seedDissolve() {
        for (int i = 0; i < 70; i++) {
            final float px = (float) (x - width / 2f + Particles.rng().nextFloat() * width);
            final float py = (float) (y - height / 2f + Particles.rng().nextFloat() * height);
            final Color c = CardColors.brighten(Particles.pick(palette, i), 0.45f);
            particles.spawnSpread(px, py, Math.atan2(py - y, px - x), 0.09f, 1.6f, 3.4f, 520f, c);
        }
    }

    /** Give the real panel its opacity back; safe to call more than once. */
    private void reveal() {
        if (landingOn != null) {
            landingOn.clearRenderTransform();
            landingOn.repaint();
            landingOn = null;
        }
    }

    @Override
    protected void onEnd() {
        reveal();
    }


    @Override
    public void draw(final Graphics2D g) {
        if (alpha > 0.01f) {
            final Graphics2D gg = (Graphics2D) g.create();
            try {
                gg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Ease.clamp01(alpha)));
                gg.translate(x, y);
                gg.scale(scale, scale);
                gg.drawImage(image, -width / 2, -height / 2, null);
            } finally {
                gg.dispose();
            }
        }
        particles.draw(g);
    }
}
