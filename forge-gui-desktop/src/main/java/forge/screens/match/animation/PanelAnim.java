package forge.screens.match.animation;

import java.awt.Point;

import forge.view.arcane.CardPanel;

/**
 * Animations that move or fade a card panel that is still on the board, by driving its
 * cosmetic render transform rather than its bounds.
 * <p>
 * Layout stays authoritative throughout: {@code PlayArea} may re-lay-out mid-animation
 * and the card simply continues from wherever it now belongs. Every one of these
 * clears the transform when it ends, including when the skip hotkey cuts it short, so
 * a panel can never be left visually displaced.
 */
public final class PanelAnim {

    private PanelAnim() {
    }

    /** Base class handling the shared teardown: always restore the panel. */
    private abstract static class Base extends Anim {
        protected final CardPanel panel;

        Base(final CardPanel panel, final long durationMs) {
            super(durationMs);
            this.panel = panel;
        }

        @Override
        protected void onEnd() {
            if (panel != null) {
                panel.clearRenderTransform();
                panel.repaint();
            }
        }

        protected void touch() {
            if (panel != null) {
                panel.repaint();
            }
        }
    }

    /**
     * Fade a newly arrived card up from nothing while it settles into size - used when
     * a token is created or a permanent resolves onto the battlefield.
     */
    public static Anim fadeIn(final CardPanel panel, final long durationMs) {
        return new Base(panel, durationMs) {
            @Override
            protected void onStart() {
                if (panel != null) {
                    panel.setRenderAlpha(0f);
                    panel.setRenderScale(0.72);
                }
            }

            @Override
            protected void update(final float t) {
                if (panel == null) {
                    return;
                }
                panel.setRenderAlpha(Ease.out(t));
                panel.setRenderScale(Ease.lerp(0.72f, 1f, Ease.backOut(t)));
                touch();
            }
        };
    }

    /** Fade a card out in place, for a permanent about to be removed. */
    public static Anim fadeOut(final CardPanel panel, final long durationMs) {
        return new Base(panel, durationMs) {
            @Override
            protected void update(final float t) {
                if (panel == null) {
                    return;
                }
                panel.setRenderAlpha(1f - Ease.in(t));
                panel.setRenderScale(Ease.lerp(1f, 0.88f, t));
                touch();
            }
        };
    }

    /**
     * Drive a card from a displacement back to its laid-out position - the settle a
     * permanent makes after being dropped onto the battlefield.
     *
     * @param fromDx starting offset from where layout has put the panel.
     */
    public static Anim slideFrom(final CardPanel panel, final int fromDx, final int fromDy,
            final long durationMs) {
        return new Base(panel, durationMs) {
            @Override
            protected void update(final float t) {
                if (panel == null) {
                    return;
                }
                final float e = Ease.backOut(t);
                panel.setRenderOffset(fromDx * (1 - e), fromDy * (1 - e));
                // Unwind the drag tilt over the first half, so it lands square.
                panel.setRenderRotation(Math.toRadians(6) * (1 - Ease.out(Math.min(1f, t * 2f))));
                touch();
            }
        };
    }

    /**
     * Throw a card toward a point and let it fall back - one attacker striking one
     * target. A double strike plays this twice because the game deals damage twice; a
     * trampler plays it once per thing it damaged.
     *
     * @param toward destination in the panel's own parent coordinates.
     * @param reach  fraction of the distance actually travelled; a full trip would
     *               leave the battlefield looking empty mid-swing.
     */
    public static Anim lunge(final CardPanel panel, final Point toward, final float reach,
            final long durationMs) {
        return new Base(panel, durationMs) {
            private final int dx;
            private final int dy;
            {
                final int cx = panel.getX() + panel.getWidth() / 2;
                final int cy = panel.getY() + panel.getHeight() / 2;
                dx = Math.round((toward.x - cx) * reach);
                dy = Math.round((toward.y - cy) * reach);
            }

            @Override
            protected void update(final float t) {
                if (panel == null) {
                    return;
                }
                // Out fast, back slow: the recoil is what sells the impact.
                final float e = t < 0.35f ? Ease.out(t / 0.35f) : 1f - Ease.inOut((t - 0.35f) / 0.65f);
                panel.setRenderOffset(dx * e, dy * e);
                panel.setRenderScale(1 + 0.06f * e);
                touch();
            }
        };
    }

    /** A short recoil away from a direction, for a creature taking a hit. */
    public static Anim flinch(final CardPanel panel, final Point away, final long durationMs) {
        return new Base(panel, durationMs) {
            private final double angle;
            {
                final int cx = panel.getX() + panel.getWidth() / 2;
                final int cy = panel.getY() + panel.getHeight() / 2;
                angle = Math.atan2(cy - away.y, cx - away.x);
            }

            @Override
            protected void update(final float t) {
                if (panel == null) {
                    return;
                }
                final float e = Ease.pingPong(t);
                final double push = 10 * e;
                panel.setRenderOffset(Math.cos(angle) * push, Math.sin(angle) * push);
                panel.setRenderRotation(Math.toRadians(3) * e);
                touch();
            }
        };
    }
}
