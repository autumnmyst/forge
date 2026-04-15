package forge.game.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCostShard;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.cost.Cost;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostUntap;
import forge.game.mana.ManaPool;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Single-pass collector that gathers every value needed to decide
 * "does this player have an available action?" and "which cards can they
 * probably act on?".
 *
 * Used by two consumers:
 *  - APINA (auto-pass if no actions) — {@code PlayerView.updateHasAvailableActions}
 *  - Actionable card highlights — {@code PlayerControllerHuman.pushActionableCards}
 *
 * The class is deliberately self-contained: it depends only on {@code forge-game}
 * types and never reaches back into PlayerView, the GUI layer, or the AI module.
 * Build one instance per priority pass via {@link #scan(Player)}; both consumers
 * share that instance instead of walking the card lists twice.
 *
 * Three logical passes run inside {@link #scan}:
 *  1. Walk all playable zones. Partition SAs into mana vs non-mana, collect
 *     tracked values (permanent counts, P/T aggregates, counters, life, etc.),
 *     and track mutation deltas from mana-ability side effects.
 *  2. Dispatch each mana ability through the estimator to produce per-color
 *     upper bounds, aggregate into the {@link ManaBudget}. (Phase 4.)
 *  3. Consumers walk {@link #spellsToCheck} and call {@link ManaBudget#canAfford}.
 *
 * FN-safety invariant: every field here is an upper bound. Unknown shapes fall
 * through to "unbounded" rather than "zero".
 */
public final class ActionScan {

    private static final ZoneType[] PLAYABLE_ZONES = new ZoneType[] {
        ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command
    };

    /** Bounded growth potential of a single tracked quantity due to mana abilities. */
    public static final class Delta {
        int max;
        boolean unbounded;

        public int getMax() { return max; }
        public boolean isUnbounded() { return unbounded; }

        void addOtma(int amount) {
            if (!unbounded && amount > 0) max += amount;
        }

        void markUnbounded() {
            unbounded = true;
        }
    }

    // ------------------------------------------------------------------
    // Output of Pass 1: SA partitioning
    // ------------------------------------------------------------------
    final List<SpellAbility> manaAbilities = new ArrayList<>();
    final List<SpellAbility> spellsToCheck = new ArrayList<>();
    /** Mana abilities that would otherwise trip the Parley-style bailout but
     *  we recognize as safe patterns (Nykthos ChooseColor, Selvala Parley).
     *  Resolved in Pass 2 after all tracked values are populated. */
    final List<SpellAbility> specialPatternAbilities = new ArrayList<>();

    /** Set when a mana ability has a structurally unboundable shape (CostUntap,
     *  Parley-style subability mana). Caller treats this as "has actions". */
    boolean structuralBailout;

    // ------------------------------------------------------------------
    // Global scans (populated after the walk)
    // ------------------------------------------------------------------
    boolean manaMultiplierPresent;
    boolean allColorsFungible;

    // ------------------------------------------------------------------
    // Permanent / zone counts (immutable via mana abilities)
    // ------------------------------------------------------------------
    int creatureCount;
    int artifactCount;
    int enchantmentCount;
    int landCount;
    int planeswalkerCount;
    int tokenCount;

    int untappedCreatures;
    int untappedArtifacts;
    int untappedLands;

    int handSize;
    int graveyardSize;
    int exileSize;

    // ------------------------------------------------------------------
    // P/T aggregates (synchronous snapshot — counter-removal costs already
    // reflect current P/T)
    // ------------------------------------------------------------------
    int highestPower;
    int highestToughness;
    int totalPower;
    int totalToughness;

    // ------------------------------------------------------------------
    // Counter aggregates
    // ------------------------------------------------------------------
    int totalPlusOneCounters;
    int totalMinusOneCounters;
    final Map<CounterType, Integer> countersByType = new HashMap<>();

    // ------------------------------------------------------------------
    // Static player state
    // ------------------------------------------------------------------
    /** Devotion to each color (WUBRG, indices 0..4). */
    final int[] devotion = new int[5];
    int life;

    /** Floating mana in the player's pool (WUBRGC). */
    final int[] floatingMana = new int[ManaBudget.NUM_BUCKETS];

    /** Current energy counters on the player. Used as a cap source for
     *  {@code CostPayEnergy} mana abilities (e.g. Aether Hub). */
    int energy;

    /** Player the scan was built for; needed by {@link ManaBudget#canAfford}
     *  to set activatingPlayer on candidate SAs before invoking CostAdjustment. */
    Player player;

    // ------------------------------------------------------------------
    // Cached cost-modifying statics — collected once during Pass 1, applied
    // per-spell during Pass 3. Saves O(spells × battlefield) work.
    // ------------------------------------------------------------------
    final List<StaticAbility> raiseCostStatics = new ArrayList<>();
    final List<StaticAbility> reduceCostStatics = new ArrayList<>();
    final List<StaticAbility> setCostStatics = new ArrayList<>();

    // ------------------------------------------------------------------
    // Convoke / Improvise precomputed inputs
    // ------------------------------------------------------------------
    /** Untapped creatures by color index (WUBRG). A gold creature appears in
     *  each of its colors. Used by the Convoke path in canAfford. */
    final int[] convokeColors = new int[5];
    /** Untapped creatures without any color — pay generic via Convoke. */
    int colorlessUntappedCreatures;

    // ------------------------------------------------------------------
    // Permanent-type counts used by OTMA activation caps (e.g. Baylen's
    // tapXType<N/token> cost needs untappedTokens).
    // ------------------------------------------------------------------
    int untappedTokens;

    // ------------------------------------------------------------------
    // Mutation deltas — populated during Pass 1 as each mana ability is
    // inspected for side effects
    // ------------------------------------------------------------------
    final Delta lifeGainDelta = new Delta();
    final Delta handSizeDelta = new Delta();
    final Delta graveyardDelta = new Delta();
    final Delta plusOneCounterDelta = new Delta();

    // ------------------------------------------------------------------
    // Aggregated mana budget (output of Pass 2)
    // ------------------------------------------------------------------
    final ManaBudget budget = new ManaBudget();

    ActionScan() {}

    public List<SpellAbility> getSpellsToCheck() {
        return spellsToCheck;
    }

    public ManaBudget getBudget() {
        return budget;
    }

    public boolean hasStructuralBailout() {
        return structuralBailout;
    }

    public int getUntappedCreatures() {
        return untappedCreatures;
    }

    public int getUntappedArtifacts() {
        return untappedArtifacts;
    }

    public int getGraveyardSize() {
        return graveyardSize;
    }

    public int getLife() { return life; }
    public int getHandSize() { return handSize; }
    public int getEnergy() { return energy; }
    public int getHighestPower() { return highestPower; }
    public int getHighestToughness() { return highestToughness; }
    public int getTotalPower() { return totalPower; }
    public int getTotalToughness() { return totalToughness; }
    public int getTotalMinusOneCounters() { return totalMinusOneCounters; }
    public Delta getLifeGainDelta() { return lifeGainDelta; }
    public Delta getHandSizeDelta() { return handSizeDelta; }
    public int[] getDevotion() { return devotion; }
    public boolean isManaMultiplierPresent() { return manaMultiplierPresent; }

    public Player getPlayer() {
        return player;
    }

    public int getUntappedTokens() {
        return untappedTokens;
    }

    public int[] getConvokeColors() {
        return convokeColors;
    }

    public int getColorlessUntappedCreatures() {
        return colorlessUntappedCreatures;
    }

    public List<StaticAbility> getRaiseCostStatics() {
        return raiseCostStatics;
    }

    public List<StaticAbility> getReduceCostStatics() {
        return reduceCostStatics;
    }

    public List<StaticAbility> getSetCostStatics() {
        return setCostStatics;
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /**
     * Build an ActionScan for the given player. One walk over all playable
     * zones + a handful of global scans, shared by both the APINA heuristic
     * and the actionable-highlights path.
     *
     * @param p the player whose perspective drives the scan
     * @return a populated scan; check {@link #hasStructuralBailout()} before
     *     trusting any counts
     */
    public static ActionScan scan(Player p) {
        ActionScan s = new ActionScan();
        s.player = p;
        s.walk(p);
        if (s.structuralBailout) return s;
        s.foldFloatingMana(p);
        s.budget.manaMultiplierPresent = s.manaMultiplierPresent;
        s.budget.allColorsFungible = s.allColorsFungible;

        // Seed budget with floating mana before estimator pass so hybrid/generic
        // shards see it.
        for (int i = 0; i < ManaBudget.NUM_BUCKETS; i++) {
            s.budget.perColor[i] += s.floatingMana[i];
        }

        // Special patterns first so their deltas (life, hand) are visible to
        // any VMG resolution that runs in the regular estimator below.
        for (SpellAbility sa : s.specialPatternAbilities) {
            ManaAbilityEstimator.applySpecialPattern(sa, s, s.budget);
        }

        // Pass 2: dispatch each mana ability through the estimator.
        for (SpellAbility ma : s.manaAbilities) {
            ManaAbilityEstimator.estimate(ma, s, s.budget);
        }

        // Phase 5 post-processing (multiplier promotion, color-converting RMA fixed-point).
        s.postProcess();

        return s;
    }

    private void postProcess() {
        // Multiplier promotion: if Mana Reflection / High Tide / etc. is in
        // play, every color we're already producing gets promoted to
        // unbounded, AND the total mana also goes unbounded (multipliers
        // create mana, so the overall budget can be arbitrarily large).
        if (manaMultiplierPresent) {
            boolean promotedAny = false;
            for (int i = 0; i < ManaBudget.NUM_BUCKETS; i++) {
                if (budget.perColor[i] > 0 || budget.unbounded[i]) {
                    budget.unbounded[i] = true;
                    promotedAny = true;
                }
            }
            if (promotedAny) budget.totalUnbounded = true;
        }
    }

    // ------------------------------------------------------------------
    // Pass 1
    // ------------------------------------------------------------------

    private void walk(Player p) {
        life = p.getLife();
        energy = p.getCounters(forge.game.card.CounterEnumType.ENERGY);

        // Track which cards we've already visited so the external-zones
        // union below doesn't double-iterate cards we already partitioned.
        java.util.Set<Card> seen = new java.util.HashSet<>();

        for (ZoneType zone : PLAYABLE_ZONES) {
            for (Card c : p.getCardsIn(zone)) {
                seen.add(c);
                visitCard(p, c, zone);
                if (structuralBailout) return;
            }
        }

        // Opponent-controlled battlefield cards can occasionally be actionable
        // (steal effects, shared cards). Pick up non-mana SAs only — mana from
        // opponents' permanents generally isn't usable by the active player.
        for (Card c : p.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (c.getController() == p) continue;
            seen.add(c);
            visitOpponentCard(p, c);
        }

        // Cards the player can cast/activate from external zones via MayPlay
        // permissions: Future Sight / Magus of the Future / Bolas's Citadel /
        // Courser of Kruphix / Etali / Oracle of Mul Daya (top of library),
        // wish effects (sideboard), stealing effects (opponent zones). Forge
        // already collects these via {@link Player#getCardsActivatableInExternalZones}.
        for (Card c : p.getCardsActivatableInExternalZones(true)) {
            if (!seen.add(c)) continue;
            visitExternalZoneCard(p, c);
            if (structuralBailout) return;
        }
    }

    /** Partition SAs from a card the player can play/activate via MayPlay or
     *  cross-zone permissions, without doing any per-zone tallying. */
    private void visitExternalZoneCard(Player p, Card c) {
        for (SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
            classifySa(sa);
            if (structuralBailout) return;
        }
    }

    private void visitCard(Player p, Card c, ZoneType zone) {
        // Zone-specific counters
        if (zone == ZoneType.Hand) handSize++;
        else if (zone == ZoneType.Graveyard) graveyardSize++;
        else if (zone == ZoneType.Exile) exileSize++;

        if (zone == ZoneType.Battlefield) {
            tallyBattlefieldCard(c);
        } else if (zone == ZoneType.Command) {
            // Command zone cards can carry mana-multiplier triggers that
            // originated from a one-shot spell (High Tide creates an
            // Effect card with a TapsForMana trigger that persists until
            // end of turn). The regular battlefield scan would miss those.
            scanCardReplacementsAndStatics(c);
        }

        // Partition SAs for all zones (spells, activated abilities, mana abilities)
        for (SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
            classifySa(sa);
            if (structuralBailout) return;
        }

        // Triggered mana abilities (Lotus Cobra, Badgermole Cub, etc.) only on battlefield.
        if (zone == ZoneType.Battlefield) {
            for (Trigger t : c.getTriggers()) {
                if (!t.isManaAbility()) continue;
                SpellAbility trigSa = t.ensureAbility();
                if (trigSa == null) continue;
                recordManaAbility(trigSa);
                if (structuralBailout) return;
            }
        }
    }

    private void visitOpponentCard(Player p, Card c) {
        for (SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
            if (sa.isManaAbility()) continue;
            spellsToCheck.add(sa);
        }
    }

    private void scanCardReplacementsAndStatics(Card c) {
        // Event=ProduceMana replacements → mana multiplier present (Mana Reflection,
        // Caged Sun, Mirari's Wake, Gauntlet of Power, Extraplanar Lens).
        if (!manaMultiplierPresent) {
            for (ReplacementEffect re : c.getReplacementEffects()) {
                if (re.getMode() == ReplacementType.ProduceMana) {
                    manaMultiplierPresent = true;
                    break;
                }
            }
        }
        // One pass over statics: classify by mode as we go. Each card usually
        // has 0-2 static abilities so this is a tight loop.
        for (StaticAbility sa : c.getStaticAbilities()) {
            var modes = sa.getMode();
            if (modes.contains(StaticAbilityMode.ManaConvert)) allColorsFungible = true;
            if (modes.contains(StaticAbilityMode.ReduceCost)) reduceCostStatics.add(sa);
            if (modes.contains(StaticAbilityMode.RaiseCost)) raiseCostStatics.add(sa);
            if (modes.contains(StaticAbilityMode.SetCost)) setCostStatics.add(sa);
        }
        // TapsForMana triggers whose override is a mana ability (High Tide, Mana Flare).
        if (!manaMultiplierPresent) {
            for (Trigger t : c.getTriggers()) {
                if (t.getMode() == TriggerType.TapsForMana && t.isManaAbility()) {
                    manaMultiplierPresent = true;
                    break;
                }
            }
        }
    }

    private void foldFloatingMana(Player p) {
        ManaPool pool = p.getManaPool();
        floatingMana[ManaBudget.W] += pool.getAmountOfColor(MagicColor.WHITE);
        floatingMana[ManaBudget.U] += pool.getAmountOfColor(MagicColor.BLUE);
        floatingMana[ManaBudget.B] += pool.getAmountOfColor(MagicColor.BLACK);
        floatingMana[ManaBudget.R] += pool.getAmountOfColor(MagicColor.RED);
        floatingMana[ManaBudget.G] += pool.getAmountOfColor(MagicColor.GREEN);
        floatingMana[ManaBudget.C] += pool.getAmountOfColor((byte) ManaAtom.COLORLESS);
    }

    private void tallyBattlefieldCard(Card c) {
        scanCardReplacementsAndStatics(c);
        boolean tapped = c.isTapped();
        if (c.isCreature()) {
            creatureCount++;
            if (!tapped) {
                untappedCreatures++;
                // Convoke colors: count this creature in each of its colors.
                ColorSet col = c.getColor();
                boolean anyColor = false;
                if (col != null) {
                    if (col.hasWhite()) { convokeColors[0]++; anyColor = true; }
                    if (col.hasBlue())  { convokeColors[1]++; anyColor = true; }
                    if (col.hasBlack()) { convokeColors[2]++; anyColor = true; }
                    if (col.hasRed())   { convokeColors[3]++; anyColor = true; }
                    if (col.hasGreen()) { convokeColors[4]++; anyColor = true; }
                }
                if (!anyColor) colorlessUntappedCreatures++;
            }

            int pw = c.getCurrentPower();
            int tg = c.getCurrentToughness();
            totalPower += pw;
            totalToughness += tg;
            // We track only board-wide maximums. "Greatest X among OTHER
            // creatures" references are treated as board-wide greatest —
            // over-count when the source is uniquely the highest, but
            // correct when there are duplicates or something else is tied.
            // FP direction, safe.
            if (pw > highestPower) highestPower = pw;
            if (tg > highestToughness) highestToughness = tg;
        }
        if (c.isArtifact()) {
            artifactCount++;
            if (!tapped) untappedArtifacts++;
        }
        if (c.isEnchantment()) enchantmentCount++;
        if (c.isLand()) {
            landCount++;
            if (!tapped) untappedLands++;
        }
        if (c.isPlaneswalker()) planeswalkerCount++;
        if (c.isToken()) {
            tokenCount++;
            if (!tapped) untappedTokens++;
        }

        // Counter aggregates
        Map<CounterType, Integer> counters = c.getCounters();
        if (counters != null) {
            for (Map.Entry<CounterType, Integer> e : counters.entrySet()) {
                int n = e.getValue() == null ? 0 : e.getValue();
                if (n == 0) continue;
                countersByType.merge(e.getKey(), n, Integer::sum);
                if (e.getKey().is(forge.game.card.CounterEnumType.P1P1)) {
                    totalPlusOneCounters += n;
                } else if (e.getKey().is(forge.game.card.CounterEnumType.M1M1)) {
                    totalMinusOneCounters += n;
                }
            }
        }

        // Devotion snapshot — walk this card's mana cost symbols.
        for (ManaCostShard sh : c.getManaCost()) {
            for (int i = 0; i < 5; i++) {
                byte colorBit = MagicColor.WUBRG[i];
                if (sh.isColor(colorBit)) devotion[i]++;
            }
        }
    }

    private void classifySa(SpellAbility sa) {
        if (sa.isManaAbility()) {
            recordManaAbility(sa);
        } else {
            spellsToCheck.add(sa);
        }
    }

    private void recordManaAbility(SpellAbility sa) {
        // Structural bailout 1: CostUntap anywhere in the cost chain.
        Cost cost = sa.getPayCosts();
        if (cost != null && cost.hasSpecificCostType(CostUntap.class)) {
            structuralBailout = true;
            return;
        }

        // Structural bailout 2: Parley-style subability mana (root API is not
        // Mana, but some descendant subability produces mana). Some known
        // patterns (Nykthos, Selvala) are handled precisely in Pass 2.
        if (sa.getApi() != ApiType.Mana && hasManaSubAbility(sa)) {
            if (isRecognizedSpecialPattern(sa)) {
                specialPatternAbilities.add(sa);
                return;
            }
            structuralBailout = true;
            return;
        }

        manaAbilities.add(sa);

        // Side-effect accounting: walk the subability chain for mutations that
        // affect tracked upper bounds.
        accountSideEffects(sa);
    }

    /** Recognize safe patterns that would otherwise trigger the Parley
     *  bailout. Returns true if the SA matches a known pattern; caller routes
     *  to {@link #specialPatternAbilities} instead of setting bailout. */
    private boolean isRecognizedSpecialPattern(SpellAbility sa) {
        // Nykthos, Shrine to Nyx: ChooseColor root with Mana subability.
        if (sa.getApi() == ApiType.ChooseColor) {
            AbilitySub sub = sa.getSubAbility();
            while (sub != null) {
                if (sub.getApi() == ApiType.Mana) return true;
                sub = sub.getSubAbility();
            }
        }
        // Selvala, Explorer Returned (Parley): PeekAndReveal root with
        // "Parley" in the precost description and a Mana subability.
        if (sa.getApi() == ApiType.PeekAndReveal) {
            String precost = sa.getParam("PrecostDesc");
            if (precost != null && precost.contains("Parley")) {
                AbilitySub sub = sa.getSubAbility();
                while (sub != null) {
                    if (sub.getApi() == ApiType.Mana) return true;
                    sub = sub.getSubAbility();
                }
            }
        }
        return false;
    }

    private static boolean hasManaSubAbility(SpellAbility sa) {
        AbilitySub sub = sa.getSubAbility();
        while (sub != null) {
            if (sub.getApi() == ApiType.Mana) return true;
            sub = sub.getSubAbility();
        }
        return false;
    }

    private void accountSideEffects(SpellAbility sa) {
        boolean isRma = isRepeatable(sa);

        // Cost-driven deltas: sac cost feeds graveyard.
        Cost cost = sa.getPayCosts();
        if (cost != null) {
            for (CostPart part : cost.getCostParts()) {
                if (part instanceof CostSacrifice) {
                    if (isRma) graveyardDelta.markUnbounded();
                    else graveyardDelta.addOtma(1);
                }
            }
        }

        // Subability chain: draw / gain life / put counters.
        SpellAbility node = sa.getSubAbility();
        while (node != null) {
            ApiType api = node.getApi();
            if (api == ApiType.Draw) {
                if (isRma) handSizeDelta.markUnbounded();
                else handSizeDelta.addOtma(readIntParam(node, "NumCards", 1));
            } else if (api == ApiType.GainLife) {
                if (isRma) lifeGainDelta.markUnbounded();
                else lifeGainDelta.addOtma(readIntParam(node, "LifeAmount", 0));
            } else if (api == ApiType.PutCounter) {
                // Synchronous counter add. Most mana abilities don't do this,
                // but if one does, treat as a +1/+1-style grow when the counter
                // is P1P1; otherwise ignore (counters don't affect affordability
                // unless some VMG references them, which is already captured
                // via countersByType).
                String cType = node.getParam("CounterType");
                if ("P1P1".equals(cType)) {
                    int n = readIntParam(node, "CounterNum", 1);
                    if (isRma) plusOneCounterDelta.markUnbounded();
                    else plusOneCounterDelta.addOtma(n);
                }
            }
            node = node.getSubAbility();
        }
    }

    /** True if this mana ability has no finite activation cap (i.e. not an OTMA).
     *  Used only for side-effect accounting (sac cost → graveyard delta).
     *  The real activation cap for Pass 2 is computed in
     *  {@link ManaAbilityEstimator#computeActivationCap}, which handles more
     *  cost shapes (tapXType, sac-other with counts) than this check. */
    private static boolean isRepeatable(SpellAbility sa) {
        Cost cost = sa.getPayCosts();
        if (cost == null) return true;
        if (cost.hasSpecificCostType(CostUntap.class)) return false;
        for (CostPart part : cost.getCostParts()) {
            if (part instanceof forge.game.cost.CostTap) return false;
            if (part instanceof forge.game.cost.CostTapType) return false;
            if (part instanceof CostSacrifice && part.payCostFromSource()) return false;
            if (part instanceof CostExile) {
                CostExile ex = (CostExile) part;
                if (part.payCostFromSource() && ex.getFrom() != null
                        && ex.getFrom().contains(ZoneType.Hand)) {
                    return false;
                }
            }
        }
        if (sa.getRestrictions() != null && sa.getRestrictions().getLimitToCheck() != null) {
            return false;
        }
        return true;
    }

    private static int readIntParam(SpellAbility sa, String key, int fallback) {
        String raw = sa.getParam(key);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

}
