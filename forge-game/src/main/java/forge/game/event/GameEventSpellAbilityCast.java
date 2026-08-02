package forge.game.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.game.spellability.TargetChoices;

/**
 * @param targetStackItems ids of the stack entries this was aimed at, for the things a
 *                         spell can target that are neither cards on a battlefield nor
 *                         players - a counterspell naming a spell, a trigger redirected
 *                         at another trigger. {@link StackItemView} carries a spell's
 *                         card and player targets but not these, and by the time the
 *                         spell resolves the stack instance it named is gone.
 */
public record GameEventSpellAbilityCast(SpellAbilityView sa, StackItemView si, int stackIndex, String targetDescription,
        List<Integer> targetStackItems) implements GameEvent {

    public GameEventSpellAbilityCast(SpellAbility sa, SpellAbilityStackInstance si, int stackIndex) {
        this(SpellAbilityView.get(sa), StackItemView.get(si), stackIndex, computeTargetDescription(sa),
                computeTargetStackItems(sa));
    }

    private static List<Integer> computeTargetStackItems(SpellAbility sa) {
        if (sa.getTargetRestrictions() == null || sa.getHostCard() == null) {
            return Collections.emptyList();
        }
        final List<Integer> ids = new ArrayList<>(1);
        for (TargetChoices ch : sa.getAllTargetChoices()) {
            if (ch == null) {
                continue;
            }
            for (SpellAbility target : ch.getTargetSpells()) {
                final SpellAbilityStackInstance si =
                        sa.getHostCard().getGame().getStack().getInstanceMatchingSpellAbilityID(target);
                if (si != null) {
                    ids.add(si.getId());
                }
            }
        }
        return ids.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(ids);
    }

    private static String computeTargetDescription(SpellAbility sa) {
        if (sa.getTargetRestrictions() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (TargetChoices ch : sa.getAllTargetChoices()) {
            if (ch != null) { if (sb.length() > 0) sb.append(" "); sb.append(ch); }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /* (non-Javadoc)
     * @see forge.game.event.GameEvent#visit(forge.game.event.IGameEventVisitor)
     */
    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "" + si.getActivatingPlayer() + (sa.isSpell() ? " cast " : si.isTrigger() ? " triggered " : " activated ") + sa;
    }
}
