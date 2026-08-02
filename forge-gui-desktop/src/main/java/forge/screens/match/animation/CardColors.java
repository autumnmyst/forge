package forge.screens.match.animation;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import forge.game.card.CardView;
import forge.gui.card.CardDetailUtil;
import forge.gui.card.CardDetailUtil.DetailColors;

/**
 * Turns a card into the palette its effects are drawn in.
 * <p>
 * Reuses {@link CardDetailUtil}, the same source the card detail panel and the
 * rendered card frames tint themselves from, so a green spell throws the same green
 * everywhere in the UI rather than inventing a second colour vocabulary.
 */
public final class CardColors {

    /** Fallback for anything with no readable colour - matches the colourless frame. */
    private static final Color NEUTRAL = new Color(160, 166, 164);

    private CardColors() {
    }

    /**
     * @param canShow whether the local player may see the card's face. Passing true for
     *                a face-down card would leak its colours through the particles.
     */
    public static List<Color> of(final CardView card, final boolean canShow) {
        final List<Color> out = new ArrayList<>(3);
        if (card != null && card.getCurrentState() != null) {
            try {
                for (final DetailColors dc : CardDetailUtil.getBorderColors(card.getCurrentState(), canShow)) {
                    out.add(new Color(dc.r, dc.g, dc.b));
                }
            } catch (final RuntimeException e) {
                // A card mid-transform can have an inconsistent state; a neutral spark
                // is a better outcome than dropping the animation.
            }
        }
        if (out.isEmpty()) {
            out.add(NEUTRAL);
        }
        return out;
    }

    /** Single representative colour, for effects too small to show a blend. */
    public static Color primary(final CardView card, final boolean canShow) {
        return of(card, canShow).get(0);
    }

    /**
     * Lighten towards white. Particles read better against Forge's dark table when the
     * core of the spark is brighter than the card's own frame colour.
     */
    public static Color brighten(final Color c, final float amount) {
        final float t = Ease.clamp01(amount);
        return new Color(
                Math.round(Ease.lerp(c.getRed(), 255, t)),
                Math.round(Ease.lerp(c.getGreen(), 255, t)),
                Math.round(Ease.lerp(c.getBlue(), 255, t)));
    }

    public static Color withAlpha(final Color c, final float alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.round(255 * Ease.clamp01(alpha)));
    }
}
