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

    /**
     * Ease a card from where it used to be onto where layout has just put it.
     * <p>
     * Battlefield layout is recomputed wholesale whenever a permanent enters or leaves -
     * cards shift along their row and the whole row can be resized to fit. Applied
     * directly that is a jump. Driven through the render transform the card appears to
     * slide and resize into its new place, while layout keeps owning the real bounds.
     *
     * @param fromDx     old position minus new, in pixels.
     * @param fromScale  old width divided by new; 1 when only the position moved.
     */
    public static Anim reflow(final CardPanel panel, final int fromDx, final int fromDy,
            final double fromScale, final long durationMs) {
        return new Base(panel, durationMs) {
            @Override
            protected void onStart() {
                if (panel != null) {
                    panel.setRenderOffset(fromDx, fromDy);
                    panel.setRenderScale(fromScale);
                }
            }

            @Override
            protected void update(final float t) {
                if (panel == null) {
                    return;
                }
                final float e = 1f - Ease.inOut(t);
                panel.setRenderOffset(fromDx * e, fromDy * e);
                panel.setRenderScale(Ease.lerp((float) fromScale, 1f, Ease.inOut(t)));
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

    /**
     * Turn a permanent between upright and tapped instead of snapping it sideways.
     * <p>
     * The panel already has room for this. {@code CardPanel.setCardBounds} pads the panel
     * by the distance from the rotation centre out to each corner of the card, and those
     * are the extremes the corners reach part-way round rather than at either end - the
     * top corners are furthest out at about 29 and 61 degrees, the bottom ones at 45. So
     * the bounds that hold the finished pose hold every angle on the way to it, and the
     * turn cannot be clipped. The angle was simply never asked for at anything but 0 or 90.
     * <p>
     * Deliberately without overshoot. Sprung past its resting angle a card being untapped
     * would go briefly negative, and {@code CardPanel.paint} only rotates for a positive
     * angle - so the last moment of every untap would be a snap upright and a flick back.
     *
     * @param from the angle to start from, so a card tapped again part-way through being
     *             untapped turns back from where it had got to rather than jumping.
     */
    public static Anim tap(final CardPanel panel, final double from, final double to,
            final long durationMs) {
        return new Anim(durationMs) {
            {
                // Claimed at once rather than in onStart, which does not run until the
                // first frame. A refresh arriving in that gap would find the angle
                // unclaimed and assign it outright, and the turn would never be drawn.
                if (panel != null) {
                    panel.setTapAnimating(true);
                }
            }

            @Override
            protected void update(final float t) {
                if (panel != null) {
                    panel.setTappedAngle(from + (to - from) * Ease.inOut(t));
                    panel.repaint();
                }
            }

            @Override
            protected void onEnd() {
                if (panel != null) {
                    // Released before the final angle is set, so the assignment is the one
                    // thing a refresh arriving in the same breath will not be told to skip.
                    panel.setTapAnimating(false);
                    panel.setTappedAngle(to);
                    panel.repaint();
                }
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
