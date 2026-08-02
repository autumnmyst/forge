package forge.game.event;

import forge.game.card.CardView;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;

/**
 * A spell or ability is about to resolve, fired before its effects run.
 * <p>
 * The counterpart {@link GameEventSpellResolved} arrives only once everything the
 * resolution did has already been announced, so it cannot be used to attribute those
 * changes. Several events a resolution causes - a player's life total moving, counters
 * being placed - name no source at all, and this is what tells a listener which stack
 * object to credit them to.
 */
public record GameEventSpellResolving(SpellAbilityView spell, CardView source) implements GameEvent {

    public GameEventSpellResolving(SpellAbility spell) {
        this(SpellAbilityView.get(spell), CardView.get(spell.getHostCard()));
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return "Stack resolving " + spell;
    }
}
