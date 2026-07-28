package forge.game.event;

import forge.game.card.Card;
import forge.game.card.CardView;

/**
 * A replacement effect actually applied and changed what happened.
 * <p>
 * Fired alongside the log entry for the replacement, so it covers exactly those effects
 * that describe themselves to the player and not the many internal ones that quietly
 * rewrite events nobody was told about.
 */
public record GameEventReplacementApplied(CardView host) implements GameEvent {

    public GameEventReplacementApplied(Card host) {
        this(CardView.get(host));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return "Replacement applied: " + host;
    }
}
