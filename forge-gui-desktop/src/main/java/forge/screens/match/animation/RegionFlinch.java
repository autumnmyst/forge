package forge.screens.match.animation;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * A struck reaction over a fixed area of the screen, used for a player taking damage.
 * <p>
 * The card version of this nudges the card panel through its render transform, but a
 * player has no card - only an avatar panel that Swing lays out, and moving that would
 * be undone by the next revalidate. So instead of shifting the component this shakes a
 * translucent wash drawn over it on the overlay, which reads the same and cannot fight
 * the layout.
 */
public final class RegionFlinch extends Anim {

    private static final int SHAKE_PIXELS = 5;
    /** Oscillations over the life of the flinch. */
    private static final double CYCLES = 3;

    private final Rectangle area;
    private final Color tint;

    private double offsetX;
    private float strength;

    public RegionFlinch(final Rectangle area, final Color tint, final long durationMs) {
        super(durationMs);
        this.area = new Rectangle(area);
        this.tint = tint;
    }

    @Override
    protected void update(final float t) {
        // Hardest at the moment of impact, shaking itself out from there.
        strength = 1f - Ease.out(t);
        offsetX = Math.sin(t * Math.PI * 2 * CYCLES) * SHAKE_PIXELS * strength;
    }

    @Override
    public void draw(final Graphics2D g) {
        if (strength <= 0.02f) {
            return;
        }
        g.translate(offsetX, 0);
        g.setColor(CardColors.withAlpha(tint, 0.34f * strength));
        g.fillRoundRect(area.x, area.y, area.width, area.height, 12, 12);
        // A brighter rim so the shape stays legible against a dark avatar.
        g.setColor(CardColors.withAlpha(CardColors.brighten(tint, 0.5f), 0.5f * strength));
        g.drawRoundRect(area.x, area.y, area.width, area.height, 12, 12);
    }
}
