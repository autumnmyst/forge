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
    private static final long RELEASE_GRACE_MS = 500L;
    private static final float FOLLOW = 0.18f;
    private static final long DISSOLVE_MS = 420L;

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
    private long ageMs;
    private boolean awaitingPayment;
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

    /** Place the card immediately, with no travel - used to take over from the drag. */
    public void snapTo(final Point p) {
        this.x = p.x;
        this.y = p.y;
        this.targetX = p.x;
        this.targetY = p.y;
    }

    /**
     * Mark the card as still owing something. It pulses while it waits, which is what
     * distinguishes "hanging here because you have mana to tap" from a card in transit.
     */
    public void setAwaitingPayment(final boolean awaiting) {
        this.awaitingPayment = awaiting;
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
        landingCountdownMs = 260L;
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

    @Override
    protected void update(final float t) {
        final long dt = 16L;
        ageMs += dt;
        if (phase == Phase.MOVING) {
            x += (targetX - x) * FOLLOW;
            y += (targetY - y) * FOLLOW;
            scale += (targetScale - scale) * FOLLOW;

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

    /**
     * A slow pulse behind a card that is still owed something, so a card parked mid-air
     * reads as waiting on the player rather than as a stuck animation.
     */
    private void drawWaitingGlow(final Graphics2D g) {
        // Held back briefly: a card that is only passing through - a land on its way to
        // the battlefield - should never flash a glow on the way past. Only one that is
        // actually sitting and waiting picks it up.
        final float strength = Ease.clamp01((ageMs - 350f) / 350f);
        if (strength <= 0f) {
            return;
        }
        final double pulse = 0.5 + 0.5 * Math.sin(ageMs / 320.0);
        final Color tint = CardColors.brighten(palette.get(0), 0.5f);
        for (int ring = 2; ring >= 1; ring--) {
            final int pad = (int) (ring * 3 + pulse * 3);
            g.setColor(CardColors.withAlpha(tint,
                    (float) (0.13 * ring * (0.55 + 0.45 * pulse)) * strength));
            g.fillRoundRect(-width / 2 - pad, -height / 2 - pad,
                    width + pad * 2, height + pad * 2, 12 + pad, 12 + pad);
        }
    }

    @Override
    public void draw(final Graphics2D g) {
        if (alpha > 0.01f) {
            final Graphics2D gg = (Graphics2D) g.create();
            try {
                gg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Ease.clamp01(alpha)));
                gg.translate(x, y);
                gg.scale(scale, scale);
                if (awaitingPayment && phase == Phase.MOVING) {
                    drawWaitingGlow(gg);
                }
                gg.drawImage(image, -width / 2, -height / 2, null);
            } finally {
                gg.dispose();
            }
        }
        particles.draw(g);
    }
}
