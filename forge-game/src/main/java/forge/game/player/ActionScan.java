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

    // ------------------------------------------------------------------
    // Per-card mutual-exclusion OTMA tracking. Categories of OTMAs that
    // compete for card-local resources can only fire one at a time per
    // priority window. For each exclusion group, we take the MAX net
    // across mana abilities in that group on the same card — not sum.
    // Buckets still receive all contributions (bucket accounting tracks
    // color reachability, not net mana).
    //
    // Groups:
    //   TAP   — CostTap / CostExert (share the card's tapped state).
    //   SAC   — CostSacrifice self (once per game, card destroyed).
    //   EXILE — CostExile self from hand (once per game, card removed).
    //
    // Populated during Pass 2 by foldIntoBudget and special-pattern
    // handlers; committed to budget.totalMana at the end of Pass 2.
    // ------------------------------------------------------------------
    /**
     * Per-card mutual-exclusion groups for OTMA contributions.
     *
     * Cards can have multiple mana abilities that compete for a card-local
     * resource. Each ability is classified into ONE group based on which
     * resources its cost consumes:
     *
     *  - TAP           — ability uses CostTap only (no sac-self).
     *  - SAC           — ability uses CostSacrifice-self only (no tap).
     *  - TAP_SAC_COMBO — ability uses BOTH CostTap AND CostSacrifice-self
     *                    in the same cost (e.g. Lotus Petal's `{T}, Sac:
     *                    Add any`).
     *  - EXILE         — ability uses CostExile-self-from-hand (separate
     *                    zone, non-interacting with tap/sac).
     *
     * The per-card commit combines them using the formula
     * {@code contribution = max(COMBO, TAP_only + SAC_only) + EXILE},
     * which correctly handles all combinations including the tricky
     * TAP_SAC_COMBO + SAC case where the combo ability destroys the card
     * and locks out the pure-sac ability.
     */
    public enum ExclusionGroup { TAP, SAC, TAP_SAC_COMBO, EXILE }

    static final class ExclusionKey {
        final Card card;
        final ExclusionGroup group;
        ExclusionKey(Card c, ExclusionGroup g) { this.card = c; this.group = g; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ExclusionKey)) return false;
            ExclusionKey k = (ExclusionKey) o;
            return card == k.card && group == k.group;
        }
        @Override public int hashCode() {
            return System.identityHashCode(card) * 31 + group.ordinal();
        }
    }

    final Map<ExclusionKey, Integer> exclusionMaxNetByKey = new HashMap<>();
    final java.util.Set<ExclusionKey> exclusionUnboundedKeys = new java.util.HashSet<>();

    /** Running per-card contribution to {@code budget.totalMana} from the
     *  exclusion-group formula. Populated by {@link #commitCardContribution}
     *  during the Pass 2 commit step; re-read and updated in-place by the
     *  fixed-point loop when a deferred cost-bearing OTMA gets admitted. */
    final Map<Card, Integer> committedCardTotals = new HashMap<>();
    /** Cards whose contribution has already flipped to unbounded in a prior
     *  commit pass. Prevents the fixed-point loop from accidentally touching
     *  {@code budget.totalMana} for a card that's already been folded into
     *  {@code budget.totalUnbounded}. */
    final java.util.Set<Card> unboundedCards = new java.util.HashSet<>();

    /** Deferred cost-bearing OTMAs staged during Pass 2. Lazy-initialized so
     *  boards without any cost-bearing mana abilities pay zero overhead. The
     *  fixed-point loop in {@link #scan} admits records whose activation
     *  cost is covered by the rest of the budget (excluding the card's own
     *  same-group contribution). See {@link ManaAbilityEstimator.PendingCostOtma}. */
    List<ManaAbilityEstimator.PendingCostOtma> pendingCostOtmas;

    /** Append a deferred cost-bearing OTMA. Lazy-inits the list so the
     *  common "no cost-bearing abilities on the board" case allocates
     *  nothing. */
    void addPendingCostOtma(ManaAbilityEstimator.PendingCostOtma rec) {
        if (pendingCostOtmas == null) pendingCostOtmas = new ArrayList<>();
        pendingCostOtmas.add(rec);
    }

    // Back-compat alias for the old field name — tap-self is the most
    // common exclusion group and some call sites still reference it.
    // (Kept so external callers compile; new code should use the keyed
    // maps directly.)
    final Map<Card, Integer> tapSelfMaxNetPerCard = new HashMap<>();
    final java.util.Set<Card> tapSelfUnboundedCards = new java.util.HashSet<>();

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

    /**
     * Pre-flight mirror of the cast-time auto-abort gate in
     * {@code TargetSelection.chooseTargets()}
     * (forge-gui/src/main/java/forge/player/TargetSelection.java:107-118).
     *
     * When a player clicks a targeted spell that has no legal targets, the
     * game cancels the cast silently and the card stays in hand. This
     * predicate reports the same yes/no so the highlight and APINA paths
     * filter those spells out.
     *
     * Kept as a literal mirror (not a shared helper) so callers of the
     * real cast path are free to evolve their logic without worrying about
     * this heuristic. If the two drift, the heuristic errs toward FP
     * (extra highlights) rather than FN (missed actions). If this ever
     * does drift, re-sync by re-reading TargetSelection.chooseTargets.
     *
     * FN-safe: any unexpected state falls through to {@code true}.
     */
    public boolean hasLegalTargets(SpellAbility sa) {
        try {
            if (!sa.usesTargeting()) return true;
            forge.game.spellability.TargetRestrictions tgt = sa.getTargetRestrictions();
            if (tgt == null) return true;

            int minTargets = sa.getMinTargets();
            int maxTargets = sa.getMaxTargets();
            if (maxTargets == 0 && minTargets == 0) return true;
            // Optional targeting — cast proceeds without targets.
            if (minTargets == 0) return true;

            // Stack-zone targets: chooseCardFromStack handles these at cast
            // time. Empty stack → nothing to target, auto-aborts.
            java.util.List<ZoneType> zones = tgt.getZone();
            if (zones != null && zones.size() == 1 && zones.get(0) == ZoneType.Stack) {
                return !player.getGame().getStack().isEmpty();
            }

            // getAllCandidates dereferences sa.getActivatingPlayer().getGame().
            boolean needsRestore = sa.getActivatingPlayer() == null;
            if (needsRestore) sa.setActivatingPlayer(player);
            try {
                java.util.List<forge.game.GameEntity> candidates = tgt.getAllCandidates(sa, true);
                if (candidates.size() < minTargets) return false;
                if (tgt.isDifferentControllers() || tgt.isForEachPlayer()) {
                    java.util.Set<Player> controllers = new java.util.HashSet<>();
                    for (forge.game.GameEntity ge : candidates) {
                        if (ge instanceof forge.game.card.Card) {
                            controllers.add(((forge.game.card.Card) ge).getController());
                        }
                    }
                    if (controllers.size() < minTargets) return false;
                }
                return true;
            } finally {
                if (needsRestore) sa.setActivatingPlayer(null);
            }
        } catch (Throwable t) {
            return true;
        }
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
        // shards see it. Floating mana counts toward both per-color buckets
        // AND totalMana — it's already in the pool, no activation cost.
        for (int i = 0; i < ManaBudget.NUM_BUCKETS; i++) {
            int amt = s.floatingMana[i];
            if (amt <= 0) continue;
            s.budget.perColor[i] += amt;
            long sum = (long) s.budget.totalMana + (long) amt;
            s.budget.totalMana = sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
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

        // Commit per-card mutual-exclusion contributions via
        // commitCardContribution(). The helper both updates budget.totalMana
        // and stores the card's running contribution in committedCardTotals
        // so the fixed-point loop below can re-commit a card after admitting
        // a deferred cost-bearing OTMA (via delta math: new - old).
        java.util.Set<Card> allCards = new java.util.HashSet<>();
        for (ExclusionKey k : s.exclusionMaxNetByKey.keySet()) allCards.add(k.card);
        for (ExclusionKey k : s.exclusionUnboundedKeys) allCards.add(k.card);
        for (Card card : allCards) {
            s.commitCardContribution(card);
        }
        // Legacy: also commit anything that got routed through the old
        // tap-self-only map (retained for any call sites not yet migrated).
        if (!s.tapSelfUnboundedCards.isEmpty()) {
            s.budget.totalUnbounded = true;
        }
        for (Integer netTotal : s.tapSelfMaxNetPerCard.values()) {
            if (netTotal == null || netTotal <= 0) continue;
            long sum = (long) s.budget.totalMana + (long) netTotal;
            s.budget.totalMana = sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
        }

        // Fixed-point loop over deferred cost-bearing OTMAs. Skipped
        // entirely when pendingCostOtmas is null/empty — the common case
        // pays zero overhead. See the comment block on PendingCostOtma.
        s.admitPendingCostOtmas();

        // Phase 5 post-processing (multiplier promotion, color-converting RMA fixed-point).
        s.postProcess();

        return s;
    }

    /**
     * Re-compute and commit a card's contribution to {@code budget.totalMana}
     * using the exclusion-group formula
     * {@code max(COMBO, TAP + SAC) + EXILE} and record it in
     * {@link #committedCardTotals} for delta-based re-commit later.
     *
     * Unbounded handling: if any relevant exclusion group has been flagged
     * unbounded for this card, set {@code budget.totalUnbounded} and mark
     * the card in {@link #unboundedCards}; subsequent calls for the same
     * card become no-ops (a card that's gone unbounded never comes back).
     */
    void commitCardContribution(Card card) {
        if (unboundedCards.contains(card)) return;

        int tapOnly = 0, sacOnly = 0, combo = 0, exile = 0;
        Integer v;
        v = exclusionMaxNetByKey.get(new ExclusionKey(card, ExclusionGroup.TAP));
        if (v != null) tapOnly = v;
        v = exclusionMaxNetByKey.get(new ExclusionKey(card, ExclusionGroup.SAC));
        if (v != null) sacOnly = v;
        v = exclusionMaxNetByKey.get(new ExclusionKey(card, ExclusionGroup.TAP_SAC_COMBO));
        if (v != null) combo = v;
        v = exclusionMaxNetByKey.get(new ExclusionKey(card, ExclusionGroup.EXILE));
        if (v != null) exile = v;

        boolean tapSacBranchUnbounded =
                exclusionUnboundedKeys.contains(new ExclusionKey(card, ExclusionGroup.TAP))
                || exclusionUnboundedKeys.contains(new ExclusionKey(card, ExclusionGroup.SAC))
                || exclusionUnboundedKeys.contains(new ExclusionKey(card, ExclusionGroup.TAP_SAC_COMBO));
        boolean exileUnbounded =
                exclusionUnboundedKeys.contains(new ExclusionKey(card, ExclusionGroup.EXILE));

        if (tapSacBranchUnbounded || exileUnbounded) {
            budget.totalUnbounded = true;
            unboundedCards.add(card);
            // Leave committedCardTotals alone — any prior finite contribution
            // stays folded in; flipping the unbounded flag subsumes it. No
            // delta subtraction because totalMana never had to "undo" a
            // bounded contribution.
            return;
        }

        int tapSacBest = Math.max(combo, tapOnly + sacOnly);
        int contribution = tapSacBest + exile;
        int previous = committedCardTotals.getOrDefault(card, 0);
        int delta = contribution - previous;
        if (delta != 0) {
            long sum = (long) budget.totalMana + (long) delta;
            if (sum >= Integer.MAX_VALUE) {
                budget.totalMana = Integer.MAX_VALUE;
            } else if (sum < 0) {
                // Should never happen — contributions are monotone non-decreasing
                // within a single card — but clamp defensively.
                budget.totalMana = 0;
            } else {
                budget.totalMana = (int) sum;
            }
        }
        committedCardTotals.put(card, contribution);
    }

    /**
     * Fixed-point loop over {@link #pendingCostOtmas}. Each outer iteration
     * walks the list once, admitting every record whose activation mana
     * cost is covered by the current {@code budget.totalMana} minus the
     * card's own same-group contribution. Admitted records:
     *  - fold their bucket contribution via the captured commit closure,
     *  - update the exclusion tracker,
     *  - trigger a {@link #commitCardContribution} re-commit on their card.
     *
     * Terminates when a pass admits nothing (layers exhausted). Leftover
     * records are truly unpayable and dropped silently — they contribute
     * nothing to the budget. Zero overhead when no deferred records exist.
     */
    void admitPendingCostOtmas() {
        if (pendingCostOtmas == null || pendingCostOtmas.isEmpty()) return;

        boolean progress;
        do {
            progress = false;
            java.util.Iterator<ManaAbilityEstimator.PendingCostOtma> it = pendingCostOtmas.iterator();
            while (it.hasNext()) {
                ManaAbilityEstimator.PendingCostOtma rec = it.next();
                int sameSelf = 0;
                if (rec.hostCard != null && rec.exclusionGroup != null) {
                    ExclusionKey key = new ExclusionKey(rec.hostCard, rec.exclusionGroup);
                    Integer cur = exclusionMaxNetByKey.get(key);
                    if (cur != null) sameSelf = cur;
                    if (exclusionUnboundedKeys.contains(key)) {
                        // Card's same group already unbounded — "rest of
                        // budget" relative to this card is irrelevant.
                        // Admit regardless.
                        admitPending(rec);
                        it.remove();
                        progress = true;
                        continue;
                    }
                }
                long availableForCost = budget.totalUnbounded
                        ? Long.MAX_VALUE
                        : ((long) budget.totalMana - (long) sameSelf);
                if (availableForCost >= rec.manaCostPerActivation) {
                    admitPending(rec);
                    it.remove();
                    progress = true;
                }
            }
        } while (progress);
        // Anything remaining is truly unpayable — drop silently.
        pendingCostOtmas.clear();
    }

    /** Apply a deferred cost-bearing OTMA's contribution: buckets first
     *  (via the captured closure), then exclusion tracker update, then
     *  per-card re-commit via delta math. */
    private void admitPending(ManaAbilityEstimator.PendingCostOtma rec) {
        if (rec.commitBuckets != null) rec.commitBuckets.run();

        if (rec.hostCard != null && rec.exclusionGroup != null) {
            ExclusionKey key = new ExclusionKey(rec.hostCard, rec.exclusionGroup);
            if (rec.unbounded) {
                exclusionUnboundedKeys.add(key);
            } else if (rec.netTotal > 0) {
                Integer cur = exclusionMaxNetByKey.get(key);
                if (cur == null || rec.netTotal > cur) {
                    exclusionMaxNetByKey.put(key, rec.netTotal);
                }
            }
            commitCardContribution(rec.hostCard);
        } else {
            // No exclusion group — direct total add (independent source).
            if (rec.unbounded) {
                budget.totalUnbounded = true;
            } else if (rec.netTotal > 0) {
                long sum = (long) budget.totalMana + (long) rec.netTotal;
                budget.totalMana = sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
            }
        }
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
     *  cross-zone permissions, without doing any per-zone tallying. Also
     *  collects any cost-modifying statics on the card whose EffectZone
     *  includes the card's actual current zone (e.g. a self-discount on a
     *  card sitting on top of the library, reachable via Future Sight). */
    private void visitExternalZoneCard(Player p, Card c) {
        ZoneType currentZone = c.getZone() != null ? c.getZone().getZoneType() : null;
        if (currentZone != null) {
            collectZoneStatics(c, currentZone);
        }
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
        } else {
            // Hand / Graveyard / Exile: collect cost-modifying statics
            // whose EffectZone includes this card's current zone. The
            // canonical case is a "self-discount" — Pearl of Wisdom et al.
            // ("Costs {1} less if you control an Otter", written as a
            // ValidCard$ Card.Self ReduceCost static with EffectZone$ All).
            // ~270 cards use this pattern. Skipped silently for cards with
            // no statics — empty getStaticAbilities() makes this a no-op
            // for the vast majority of hand cards.
            collectZoneStatics(c, zone);
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

    /** Pick up cost-modifying statics on a card sitting in a non-battlefield
     *  zone (hand, graveyard, exile), but only when the static's
     *  {@code EffectZone} includes that zone. Most cards carry no statics
     *  → empty list, no overhead. */
    private void collectZoneStatics(Card c, ZoneType zone) {
        for (StaticAbility sa : c.getStaticAbilities()) {
            java.util.Set<ZoneType> active = sa.getActiveZone();
            if (active != null && !active.contains(zone)) continue;
            var modes = sa.getMode();
            if (modes.contains(StaticAbilityMode.ReduceCost)) reduceCostStatics.add(sa);
            if (modes.contains(StaticAbilityMode.RaiseCost)) raiseCostStatics.add(sa);
            if (modes.contains(StaticAbilityMode.SetCost)) setCostStatics.add(sa);
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
        ZoneType currentZone = c.getZone() != null ? c.getZone().getZoneType() : null;
        // One pass over statics: classify by mode as we go. Each card usually
        // has 0-2 static abilities so this is a tight loop. Cost-modifying
        // statics are gated by EffectZone so a battlefield card with
        // EffectZone$ Hand (rare but possible) doesn't get its reducer
        // collected from the wrong zone.
        for (StaticAbility sa : c.getStaticAbilities()) {
            var modes = sa.getMode();
            if (modes.contains(StaticAbilityMode.ManaConvert)) allColorsFungible = true;
            java.util.Set<ZoneType> active = sa.getActiveZone();
            boolean zoneOk = active == null || currentZone == null || active.contains(currentZone);
            if (!zoneOk) continue;
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
