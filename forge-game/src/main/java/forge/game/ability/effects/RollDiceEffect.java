package forge.game.ability.effects;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.SpellAbilityEffect;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.event.GameEventRollDie;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerController.*;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.MyRandom;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;


public class RollDiceEffect extends SpellAbilityEffect {

    public static String makeFormatedDescription(SpellAbility sa) {
        StringBuilder sb = new StringBuilder();
        final String key = "ResultSubAbilities";
        if (sa.hasParam(key)) {
            String [] diceAbilities = sa.getParam(key).split(",");
            for (String ab : diceAbilities) {
                String [] kv = ab.split(":");
                SpellAbility subAbility = sa.getAdditionalAbility(kv[0]);
                if (subAbility != null) {
                    String desc = subAbility.getDescription();
                    if (!desc.isEmpty()) {
                        sb.append("\n").append(desc);
                    }
                }
            }
        }

        return sb.toString();
    }

    /* (non-Javadoc)
     * @see forge.card.abilityfactory.SpellEffect#getStackDescription(java.util.Map, forge.card.spellability.SpellAbility)
     */
    @Override
    protected String getStackDescription(SpellAbility sa) {
        final PlayerCollection player = getTargetPlayers(sa);

        if(sa.hasParam("ToVisitYourAttractions")) {
            if (player.size() == 1 && player.get(0).equals(sa.getActivatingPlayer()))
                return "Roll to Visit Your Attractions.";
            else
                return String.format("%s %s to visit their Attractions.", Lang.joinHomogenous(player), Lang.joinVerb(player, "roll"));
        }

        StringBuilder stringBuilder = new StringBuilder();
        if (player.size() == 1 && player.get(0).equals(sa.getActivatingPlayer())) {
            stringBuilder.append("Roll ");
        } else {
            stringBuilder.append(player).append(" rolls ");
        }
        stringBuilder.append(sa.getParamOrDefault("Amount", "a")).append(" d");
        stringBuilder.append(sa.getParamOrDefault("Sides", "6"));
        if (sa.hasParam("IgnoreLower")) {
            stringBuilder.append(" and ignore the lower roll");
        }
        stringBuilder.append(".");
        return stringBuilder.toString();
    }

    public static int rollDiceForPlayer(SpellAbility sa, Player player, int amount, int sides) {
        boolean toVisitAttractions = sa != null && sa.hasParam("ToVisitYourAttractions");
        // Pass 0 for modifier, rollsResult starts null (will be populated)
        return rollDiceForPlayer(sa, player, amount, sides, 0, 0, null, toVisitAttractions);
    }
    public static int rollDiceForPlayerToVisitAttractions(Player player) {
        // Pass 0 for modifier, rollsResult starts null
        return rollDiceForPlayer(null, player, 1, 6, 0, 0, null, true);
    }

    /**
     * Handles the complete process of a player rolling dice, including replacements,
     * ignoring, rerolling, modification, and triggering.
     *
     * @param sa                 The SpellAbility causing the roll (can be null).
     * @param player             The player rolling the dice.
     * @param amount             The base number of dice to roll.
     * @param sides              The number of sides on each die.
     * @param ignore             The base number of lowest dice to ignore.
     * @param modifier           (DEPRECATED - No longer used for calculation, use Modification phase) Base modifier, passed as 0.
     * @param rollsResult        An optional list to populate with the final results. If null, a new list is created.
     * @param toVisitAttractions True if this roll is specifically for visiting attractions.
     * @return The sum of the final die roll results after all modifications.
     */
    private static int rollDiceForPlayer(SpellAbility sa, Player player, int amount, int sides, int ignore, int modifier, List<Integer> rollsResult, boolean toVisitAttractions) {
        if (amount <= 0) { // Allow amount = 0 initially, replacement might change it
            return 0;
        }

        // ========== 1. Initial Replacement Phase ==========
        Map<Player, Integer> ignoreChosenMap = Maps.newHashMap();
        final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(player);
        repParams.put(AbilityKey.Number, amount);
        repParams.put(AbilityKey.Ignore, ignore); // How many lowest to ignore automatically
        repParams.put(AbilityKey.IgnoreChosen, ignoreChosenMap); // Map<Player, Integer> for chosen ignores

        switch (player.getGame().getReplacementHandler().run(ReplacementType.RollDice, repParams)) {
            case NotReplaced:
                break;
            case Updated: {
                amount = (int) repParams.get(AbilityKey.Number);
                ignore = (int) repParams.get(AbilityKey.Ignore);
                //noinspection unchecked
                ignoreChosenMap = (Map<Player, Integer>) repParams.get(AbilityKey.IgnoreChosen);
                break;
            }
        }

        // If replacement reduced amount to 0 or less, stop here.
        if (amount <= 0) {
            return 0;
        }

        // ========== 2. Natural Roll Phase ==========
        List<Integer> naturalRolls = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            int roll = MyRandom.getRandom().nextInt(sides) + 1;
            // Play the die roll sound/animation
            player.getGame().fireEvent(new GameEventRollDie());
            player.roll(); // Increment player's internal roll count for the turn *here*? No, later with final rolls.
            naturalRolls.add(roll);
        }
        naturalRolls.sort(Comparator.naturalOrder()); // Sort ascending for ignore lowest

        // ========== 3. Ignore Phase ==========
        List<Integer> ignored = new ArrayList<>();
        // Ignore lowest N rolls automatically
        int countToIgnore = Math.min(ignore, naturalRolls.size());
        if (countToIgnore > 0) {
            ignored.addAll(naturalRolls.subList(0, countToIgnore));
            naturalRolls.subList(0, countToIgnore).clear(); // Remove ignored rolls
        }

        // Player chooses which rolls to ignore (e.g., Bamboozling Beeble)
        for (Player chooser : ignoreChosenMap.keySet()) {
            int numToChoose = Math.min(ignoreChosenMap.get(chooser), naturalRolls.size());
            for (int ig = 0; ig < numToChoose; ig++) {
                if (naturalRolls.isEmpty()) break; // Stop if no more rolls left to ignore
                Integer chosenToIgnore = chooser.getController().chooseRollToIgnore(naturalRolls);
                if (chosenToIgnore != null) { // Player might cancel? Assume they must choose if possible.
                    ignored.add(chosenToIgnore);
                    naturalRolls.remove(chosenToIgnore);
                } else {
                    // Handle case where choice is unexpectedly null (e.g., AI cannot choose)
                    // For now, break to avoid infinite loops, but might need better handling.
                    System.err.println("Warning: Player " + chooser.getName() + " failed to choose a roll to ignore.");
                    break;
                }
            }
        }

        // Special case: Use only the highest roll (Iron Mastiff)
        if (sa != null && sa.hasParam("UseHighestRoll") && naturalRolls.size() > 1) {
             naturalRolls.sort(Comparator.reverseOrder()); // Sort descending
             ignored.addAll(naturalRolls.subList(1, naturalRolls.size())); // Add all but the first (highest) to ignored
             naturalRolls.subList(1, naturalRolls.size()).clear(); // Keep only the highest
        }


        // ========== 4. Reroll Phase ==========
        boolean potentiallyCanReroll = true; // Assume possible initially, refine with actual checks later
        while (potentiallyCanReroll && !naturalRolls.isEmpty()) {
            // Get available reroll effects (e.g., Monitor Monitor)
            List<RerollOption> availableRerolls = player.getController().getAvailableRerollOptions(naturalRolls, sa);
            boolean playerCanReroll = !availableRerolls.isEmpty();
        
            if (!playerCanReroll) {
                potentiallyCanReroll = false;
                break;
            }
        
            // Ask player if they want to use any reroll effect
            boolean wantsToReroll = player.getController().confirmUseRerollEffect(availableRerolls);
        
            if (!wantsToReroll) {
                potentiallyCanReroll = false;
                break;
            }
        
            // Player chooses which reroll effect to use (if multiple)
            RerollOption chosenEffect = player.getController().chooseRerollEffect(availableRerolls);
            
            if (chosenEffect == null) {
                potentiallyCanReroll = false;
                break;
            }

            // TODO: Player pays costs for the chosen effect (e.g., mana, tapping)
            // boolean paid = player.getController().payCosts(chosenEffect.cost); if (!paid) continue; // Skip if cost can't be paid

            // Player chooses which dice results from naturalRolls to reroll
            int maxDice = chosenEffect != null ? chosenEffect.getMaxDice() : naturalRolls.size();
            int minDice = chosenEffect != null ? chosenEffect.getMinDice() : 0;
            List<Integer> chosenIndices = player.getController().chooseDiceToReroll(naturalRolls, maxDice, minDice);

            if (chosenIndices.isEmpty()) {
                // Player decided not to reroll any specific dice this time, or cancelled. Stop asking for this roll event.
                potentiallyCanReroll = false;
                break;
            }

            // Perform the rerolls for the chosen indices
            List<Integer> newRolls = new ArrayList<>();
            // Sort indices descending to avoid index shifting issues when removing/setting
            chosenIndices.sort(Comparator.reverseOrder());
            for (int index : chosenIndices) {
                 if (index >= 0 && index < naturalRolls.size()) {
                     int oldRoll = naturalRolls.get(index);
                     int newRoll = MyRandom.getRandom().nextInt(sides) + 1;
                     player.getGame().fireEvent(new GameEventRollDie()); // Fire event for the reroll itself
                     naturalRolls.set(index, newRoll);
                     newRolls.add(newRoll);
                     // TODO: Notify player of the specific reroll (e.g., "[Player] rerolled X into Y using [Card Name]")
                     player.getGame().getAction().notifyOfValue(sa, player, "Rerolled " + oldRoll + " into " + newRoll, player); // Basic notification
                 }
            }
            // Rerolling might necessitate re-sorting if subsequent effects depend on order, but usually not.
            // naturalRolls.sort(Comparator.naturalOrder()); // Re-sort if needed, currently skipped.

            // TODO: Mark the used reroll effect as used for the turn/roll if necessary
            // chosenEffect.markAsUsed();

            // Loop continues: Check again if player has other reroll effects or can use the same one again (unlikely for current cards)
             potentiallyCanReroll = false; // TEMP: Prevent infinite loop in placeholder state. Set based on actual available effects logic.
        }


        // ========== 5. Modification Phase ==========
        boolean potentiallyCanModify = true; // Assume possible initially
        while (potentiallyCanModify && !naturalRolls.isEmpty()) {
            // Get available modification effects (Xenosquirrels, Night Shift, Squirrel-Whacker)
            List<ModificationOption> availableModifications = player.getController().getAvailableModificationOptions(naturalRolls, sa, sides);
            boolean playerCanModify = !availableModifications.isEmpty();
        
            if (!playerCanModify) {
                potentiallyCanModify = false;
                break;
            }
        
            // Ask player if they want to use any modification effect
            boolean wantsToModify = player.getController().confirmUseModificationEffect(availableModifications);
        
            if (!wantsToModify) {
                potentiallyCanModify = false;
                break;
            }
        
            // Player chooses which modification effect to use
            ModificationOption chosenEffect = player.getController().chooseModificationEffect(availableModifications);
            
            if (chosenEffect == null) {
                potentiallyCanModify = false;
                break;
            }
        
            // TODO: Player pays costs (remove counter, pay life, etc.)
            //Cost cost = chosenEffect.getSpellAbility().getPayCosts();
            //if player.payLife()
            //boolean paid = player.getController().getPlayer() if (!paid) continue;
        
            // Player chooses which die result from naturalRolls to modify (usually one)
            int indexToModify = player.getController().chooseDieToModify(naturalRolls, chosenEffect);
        
            if (indexToModify < 0 || indexToModify >= naturalRolls.size()) {
                // Player decided not to modify or cancelled. Stop asking for this roll event.
                potentiallyCanModify = false;
                break;
            }
        
            int currentRoll = naturalRolls.get(indexToModify);
            int newRoll = currentRoll;
        
            // Apply the specific modification based on chosenEffect type
            if (chosenEffect.isTypePlusMinus()) {
                // Plus/Minus 1 modification (Xenosquirrels, etc.)
                int detail = player.getController().chooseModificationDetailPlusMinus(currentRoll);
                newRoll = currentRoll + detail; // Will be +1 or -1
            } else if (chosenEffect.isTypeSetTo()) {
                // Set to specific value (Squirrel-Whacker)
                Card effectCard = chosenEffect.getSourceCard();
                int power = effectCard != null ? effectCard.getBasePower() : 0;
                int toughness = effectCard != null ? effectCard.getBaseToughness() : 0;
                newRoll = player.getController().chooseModificationDetailSquirrelWhacker(currentRoll, power, toughness);
            }

            if (newRoll != currentRoll) {
                naturalRolls.set(indexToModify, newRoll);
                // TODO: Notify player of the specific modification (e.g., "[Player] modified X to Y using [Card Name]")
                 player.getGame().getAction().notifyOfValue(sa, player, "Modified " + currentRoll + " to " + newRoll, player); // Basic notification
            }

            // TODO: Mark the used modification effect as used for the turn if necessary (e.g., Night Shift)
            // chosenEffect.markAsUsed();

            // Loop continues: Check again for more modification effects.
            potentiallyCanModify = false; // TEMP: Prevent infinite loop in placeholder state.
        }


        // ========== 6. Finalization and Trigger Phase ==========

        // The list 'naturalRolls' now contains the final results after ignores, rerolls, and modifications.
        List<Integer> finalRolls = new ArrayList<>(naturalRolls);

        // Populate the output list if provided
        if (rollsResult != null) {
            rollsResult.clear();
            rollsResult.addAll(finalRolls);
        }

        // Notify of final results
        if (!finalRolls.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            String rollResultsStr = finalRolls.stream().map(String::valueOf).collect(Collectors.joining(", "));
            String resultMessageKey = toVisitAttractions ? "lblAttractionRollResult" : "lblPlayerRolledResult";
            // Use Localizer.format to handle player names correctly
            sb.append(Localizer.getInstance().getMessage(resultMessageKey, player.getName(), rollResultsStr));

            if (!ignored.isEmpty()) {
                 String ignoredRollsStr = ignored.stream().map(String::valueOf).collect(Collectors.joining(", "));
                 // Format message for ignored rolls
                 sb.append("\n").append(Localizer.getInstance().getMessage("lblIgnoredRolls", ignoredRollsStr));
            }
            player.getGame().getAction().notifyOfValue(sa, player, sb.toString(), null);

            // Track the *final* rolls for the turn
            player.addDieRollThisTurn(finalRolls);
        } else if (!ignored.isEmpty()) {
            // If all rolls were ignored, still notify about the ignored rolls
            StringBuilder sb = new StringBuilder();
            String ignoredRollsStr = ignored.stream().map(String::valueOf).collect(Collectors.joining(", "));
            sb.append(Localizer.getInstance().getMessage("lblPlayerRolledResult", player.getName(), "(All rolls ignored)"));
            sb.append("\n").append(Localizer.getInstance().getMessage("lblIgnoredRolls", ignoredRollsStr));
            player.getGame().getAction().notifyOfValue(sa, player, sb.toString(), null);
             // Track empty list of final rolls
             player.addDieRollThisTurn(Collections.emptyList());
        } else {
             // Edge case: 0 rolls initially and no replacements. Track empty list.
             player.addDieRollThisTurn(Collections.emptyList());
        }


        // Calculate derived stats based on *final* results
        int oddResults = 0;
        int evenResults = 0;
        Set<Integer> distinctResults = new HashSet<>();
        int countMaxRolls = 0; // Count how many dice showed the max possible value (sides)

        for (Integer finalRoll : finalRolls) {
            distinctResults.add(finalRoll);
            if (finalRoll % 2 == 0) {
                evenResults++;
            } else {
                oddResults++;
            }
            if (finalRoll == sides) { // Check against the original 'sides' value
                countMaxRolls++;
            }
        }
        int differentResults = distinctResults.size();

        // Store SVars if the SpellAbility requires them
        if (sa != null) {
            if (sa.hasParam("EvenOddResults")) {
                sa.setSVar("EvenResults", Integer.toString(evenResults));
                sa.setSVar("OddResults", Integer.toString(oddResults));
            }
            if (sa.hasParam("DifferentResults")) {
                sa.setSVar("DifferentResults", Integer.toString(differentResults));
            }
            if (sa.hasParam("MaxRollsResults")) {
                sa.setSVar("MaxRolls", Integer.toString(countMaxRolls));
            }
        }

        // Fire Triggers based on *final* results
        // Determine the starting roll number for this batch
        int startingRollNum = player.getNumRollsThisTurn() - finalRolls.size() + 1;
        int currentRollNum = startingRollNum;

        for (Integer finalRoll : finalRolls) {
            final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(player);
            runParams.put(AbilityKey.Sides, sides);
            // Modifier is no longer applied here or relevant for the trigger context
            runParams.put(AbilityKey.Result, finalRoll); // The final result after all changes
            runParams.put(AbilityKey.RolledToVisitAttractions, toVisitAttractions);
            runParams.put(AbilityKey.Number, currentRollNum); // The sequential number of this roll within the turn
            player.getGame().getTriggerHandler().runTrigger(TriggerType.RolledDie, runParams, false);
            currentRollNum++;
        }

        // Fire RolledDieOnce trigger with the list of final results
        final Map<AbilityKey, Object> runParamsOnce = AbilityKey.mapFromPlayer(player);
        runParamsOnce.put(AbilityKey.Sides, sides);
        runParamsOnce.put(AbilityKey.Result, finalRolls); // Pass the list of final results
        runParamsOnce.put(AbilityKey.RolledToVisitAttractions, toVisitAttractions);
        player.getGame().getTriggerHandler().runTrigger(TriggerType.RolledDieOnce, runParamsOnce, false);

        // ========== 7. Return Value ==========
        // Return the sum of the final rolls
        return finalRolls.stream().mapToInt(Integer::intValue).sum();
    }


    private static void resolveSub(SpellAbility sa, int num) {
        Map<String, SpellAbility> diceAbilities = sa.getAdditionalAbilities();
        SpellAbility resultAbility = null;
        for (Map.Entry<String, SpellAbility> e : diceAbilities.entrySet()) {
            String diceKey = e.getKey();
            try {
                 if (diceKey.contains("-")) {
                    String[] ranges = diceKey.split("-");
                    if (ranges.length == 2 && StringUtils.isNumeric(ranges[0]) && StringUtils.isNumeric(ranges[1])) {
                         int min = Integer.parseInt(ranges[0]);
                         int max = Integer.parseInt(ranges[1]);
                         if (min <= num && max >= num) {
                            resultAbility = e.getValue();
                            break;
                         }
                    }
                 } else if (StringUtils.isNumeric(diceKey)) {
                    if (Integer.parseInt(diceKey) == num) {
                         resultAbility = e.getValue();
                         break;
                    }
                 }
            } catch (NumberFormatException ex) {
                 System.err.println("Warning: Invalid numeric key for dice sub-ability: " + diceKey + " in card " + sa.getHostCard().getName());
            }
        }
        if (resultAbility != null) {
            AbilityUtils.resolve(resultAbility);

        } else if (sa.hasAdditionalAbility("Else")) {
            AbilityUtils.resolve(sa.getAdditionalAbility("Else"));
        }
    }

    // This internal method calls rollDiceForPlayer and handles subsequent actions like storing results, SVar setting, and sub-ability resolution.
    private int rollDice(SpellAbility sa, Player player, int amount, int sides) {
        final Card host = sa.getHostCard();
        // Modifier is handled *inside* rollDiceForPlayer's modification phase, no longer needed here.
        // final int modifier = AbilityUtils.calculateAmount(host, sa.getParamOrDefault("Modifier", "0"), sa);
        final int ignore = AbilityUtils.calculateAmount(host, sa.getParamOrDefault("IgnoreLower", "0"), sa);

        List<Integer> finalRollsList = new ArrayList<>(); // This list will be populated by rollDiceForPlayer with final results
        // Pass 0 for modifier, pass the list to be populated
        int total = rollDiceForPlayer(sa, player, amount, sides, ignore, 0, finalRollsList, sa.hasParam("ToVisitYourAttractions"));

        // -- Post-Roll Processing --

        // UseDifferenceBetweenRolls needs the final list
        if (sa.hasParam("UseDifferenceBetweenRolls")) {
             if (finalRollsList.size() >= 2) {
                  total = Collections.max(finalRollsList) - Collections.min(finalRollsList);
             } else if (finalRollsList.size() == 1) {
                  total = finalRollsList.get(0); // Difference with itself is 0? Or just the value? Use the value for now.
             } else {
                  total = 0; // No rolls, difference is 0
             }
        }

        // StoreResults uses the final list
        if (sa.hasParam("StoreResults")) {
            host.addStoredRolls(finalRollsList); // Store the final, potentially modified/rerolled results
        }

        // ResultSVar uses the calculated total (which might be sum or difference)
        if (sa.hasParam("ResultSVar")) {
            sa.setSVar(sa.getParam("ResultSVar"), Integer.toString(total));
        }

        // ChosenSVar lets player choose from the final list
        if (sa.hasParam("ChosenSVar") && !finalRollsList.isEmpty()) {
            int chosen = player.getController().chooseNumber(sa, Localizer.getInstance().getMessage("lblChooseAResult"), finalRollsList, player);
            String message = Localizer.getInstance().getMessage("lblPlayerChooseValue", player.getName(), chosen);
            player.getGame().getAction().notifyOfValue(sa, player, message, player);
            sa.setSVar(sa.getParam("ChosenSVar"), Integer.toString(chosen));

            // OtherSVar finds another value from the final list
            if (sa.hasParam("OtherSVar")) {
                 Integer other = null;
                 for (Integer roll : finalRollsList) {
                     if (roll != chosen) {
                         other = roll;
                         break;
                     }
                 }
                 // If only one distinct value was rolled, 'other' might still be null or the same as 'chosen'.
                 // SVar should handle null or store the chosen value again if no 'other' exists.
                 sa.setSVar(sa.getParam("OtherSVar"), other != null ? Integer.toString(other) : Integer.toString(chosen));
            }
        }

        // Sub-ability resolution based on total or individual final rolls
        if (sa.hasParam("SubsForEach")) {
            for (Integer roll : finalRollsList) { // Use the final rolls list
                resolveSub(sa, roll);
            }
        } else {
            resolveSub(sa, total); // Use the calculated total
        }

        // NoteDoubles checks the final list for duplicates
        if (sa.hasParam("NoteDoubles") && finalRollsList.size() >= 2) {
            Set<Integer> unique = new HashSet<>();
            boolean foundDouble = false;
            for (Integer roll : finalRollsList) {
                if (!unique.add(roll)) {
                    foundDouble = true;
                    break; // Found one double, no need to check further
                }
            }
            if (foundDouble) {
                 sa.setSVar("Doubles", "1");
            }
        }

        return total;
    }

    /* (non-Javadoc)
     * @see forge.card.ability.SpellAbilityEffect#resolve(forge.card.spellability.SpellAbility)
     */
    @Override
    public void resolve(SpellAbility sa) {
        final Card host = sa.getHostCard();

        int amount = AbilityUtils.calculateAmount(host, sa.getParamOrDefault("Amount", "1"), sa);
        int sides = AbilityUtils.calculateAmount(host, sa.getParamOrDefault("Sides", "6"), sa);
        boolean rememberHighest = sa.hasParam("RememberHighestPlayer");

        final PlayerCollection playersToRoll = getTargetPlayers(sa);
        Map<Player, Integer> playerResults = new HashMap<>(); // Store result per player for comparison

        for (Player player : playersToRoll) {
            // RerollResults uses a different flow (rerolling stored dice, not performing a new roll event)
            if (sa.hasParam("RerollResults")) {
                // Check if the host card actually has stored rolls for this player
                if (!host.getStoredRolls().isEmpty()) {
                     rerollDice(sa, host, player, sides);
                } else {
                    // No stored rolls to reroll, do nothing for this player.
                    player.getGame().getAction().notifyOfValue(sa, player, "No stored results on " + host.getName() + " to reroll.", player);
                }
            } else {
                // Perform a new roll event for the player
                int result = rollDice(sa, player, amount, sides); // rollDice now handles the full process internally
                playerResults.put(player, result);

                // Handle attraction visiting based on the final sum result
                if (sa.hasParam("ToVisitYourAttractions")) {
                    player.visitAttractions(result); // Visit based on the total sum (consistent with previous logic)
                }
            }
        }

        // Remember the player(s) who had the highest result *if* new rolls were performed
        if (rememberHighest && !playerResults.isEmpty()) {
            int highest = Integer.MIN_VALUE;
            // Find the highest result among players who rolled
            for (Integer result : playerResults.values()) {
                if (result > highest) {
                    highest = result;
                }
            }
            // Remember all players who achieved the highest result
            for (Map.Entry<Player, Integer> entry : playerResults.entrySet()) {
                if (entry.getValue() == highest) {
                    host.addRemembered(entry.getKey());
                }
            }
        }
    }

    // Handles rerolling dice previously stored on a card (e.g., Centaur of Attention)
    private void rerollDice(SpellAbility sa, Card host, Player roller, int sides) {
        List<Integer> currentStoredRolls = host.getStoredRolls();
        if (currentStoredRolls.isEmpty()) {
            roller.getGame().getAction().notifyOfValue(sa, roller, "No stored results on " + host.getName() + " to reroll.", roller);
            return;
        }

        List<Integer> toReroll = Lists.newArrayList();
        List<Integer> availableToReroll = new ArrayList<>(currentStoredRolls); // Copy to modify during choice

        // Let player choose which stored results to reroll using the dedicated controller method
        List<Integer> chosenToRerollIndices = roller.getController().chooseDiceToRerollStored(sa, availableToReroll);

        if (chosenToRerollIndices.isEmpty()) {
            roller.getGame().getAction().notifyOfValue(sa, roller, "Chose not to reroll any stored results.", roller);
            return; // Player chose not to reroll any
        }

        Map<Integer, Integer> replaceMap = Maps.newHashMap(); // Map<Original Index, New Roll Value>
        List<Integer> rerolledValues = new ArrayList<>();

        for (int index : chosenToRerollIndices) {
             if (index >= 0 && index < currentStoredRolls.size()) {
                 int originalRoll = currentStoredRolls.get(index);
                 // Perform a *single* die roll for each chosen stored result
                 // Use rollDice which triggers everything, but for amount=1, no ignore/mods directly here.
                 // We need the *result* of the roll, not complex interactions on this sub-roll.
                 // Let's call a simpler roll directly.
                 int newRoll = MyRandom.getRandom().nextInt(sides) + 1;
                 roller.getGame().fireEvent(new GameEventRollDie()); // Event for the reroll
                 roller.roll(); // Count this as a roll? Yes, Centaur implies these are rolls.

                 // Store the mapping using the index to handle duplicate stored values correctly
                 replaceMap.put(index, newRoll);
                 rerolledValues.add(newRoll); // Track the new values obtained

                 roller.getGame().getAction().notifyOfValue(sa, roller, "Rerolled stored " + originalRoll + " into " + newRoll, roller);
             }
        }

        // Replace the chosen rolls on the host card using the index map
        host.replaceStoredRollsByIndex(replaceMap);

        // If the reroll action itself has SubsForEach/etc., handle them based on the *newly rolled* values.
        // This requires passing 'rerolledValues' to relevant handlers if needed.
        // Current Centaur script doesn't seem to have sub-abilities on the reroll trigger itself.
         if (sa.hasParam("SubsForEach")) {
             for (Integer roll : rerolledValues) {
                 resolveSub(sa, roll);
             }
         }
         // Note: We probably don't need to resolveSub based on the *sum* of rerolls here.

         // Track these new rolls for the turn
         roller.addDieRollThisTurn(rerolledValues);

         // Fire triggers for these individual rerolls
         int startingRollNum = roller.getNumRollsThisTurn() - rerolledValues.size() + 1;
         int currentRollNum = startingRollNum;
         for (Integer rerollVal : rerolledValues) {
            final Map<AbilityKey, Object> runParams = AbilityKey.mapFromPlayer(roller);
            runParams.put(AbilityKey.Sides, sides);
            runParams.put(AbilityKey.Result, rerollVal);
            runParams.put(AbilityKey.RolledToVisitAttractions, false); // Rerolling stored results is not for attractions
            runParams.put(AbilityKey.Number, currentRollNum);
            roller.getGame().getTriggerHandler().runTrigger(TriggerType.RolledDie, runParams, false);
            currentRollNum++;
         }
         final Map<AbilityKey, Object> runParamsOnce = AbilityKey.mapFromPlayer(roller);
         runParamsOnce.put(AbilityKey.Sides, sides);
         runParamsOnce.put(AbilityKey.Result, rerolledValues);
         runParamsOnce.put(AbilityKey.RolledToVisitAttractions, false);
         roller.getGame().getTriggerHandler().runTrigger(TriggerType.RolledDieOnce, runParamsOnce, false);

    }
}