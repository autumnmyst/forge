package forge.game.player;

import forge.card.MagicColor;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayEnergy;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostRemoveCounter;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.cost.CostTapType;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Pass 2 of the {@link ActionScan}: convert each classified mana ability into
 * a per-color upper bound and fold the result into the {@link ManaBudget}.
 *
 * The estimator operates on a single {@link SpellAbility} at a time and reads
 * from the tracked values collected in Pass 1. It never mutates anything
 * except the output budget.
 *
 * FN-safety: every unknown shape flips the produced colors to
 * {@code unbounded} rather than contributing zero. Callers get an
 * over-estimate, never an under-estimate.
 */
final class ManaAbilityEstimator {

    /** Sentinel that means "resolve to infinity; flip unbounded". */
    static final int UNBOUNDED = Integer.MAX_VALUE;

    // RestrictValid$ (spend-on-creature-only, etc.) is intentionally NOT
    // honored here. Ignoring the restriction means we treat restricted mana
    // as if it were unrestricted — which turns some false negatives into
    // false positives (we say "affordable" when the spell isn't really the
    // right target for that mana). FP is our accepted direction, so we let
    // it ride. If we ever need precision, a per-bucket restriction flag
    // would hang off ManaBudget and canAfford would filter it per shard.

    private ManaAbilityEstimator() {}

    /**
     * Precise model of a mana modifier (High Tide, Mana Reflection, Badgermole
     * Cub, Caged Sun variants). Applied per-activation during
     * {@link #foldIntoBudget} to mana abilities whose host matches the
     * {@code matches} predicate.
     *
     * Two composition axes:
     *  - {@code extraBucketPerAct}: flat additive bonus per activation
     *    (High Tide: +1 U per Island tap; Badgermole Cub: +1 G per creature tap).
     *  - {@code multiplier}: multiplicative bonus per activation (Mana
     *    Reflection: ×2 on permanent taps). Multiple modifiers with the
     *    same matches compose multiplicatively.
     *
     * When a mana ability matches multiple modifiers, flat bonuses add
     * and multipliers compose via saturating-mul. Stacked Mana Reflections
     * (2 copies → ×4 output) work correctly because each adds its own
     * record with multiplier=2, and we compose all matching multipliers.
     */
    static final class ManaModifier {
        final java.util.function.Predicate<SpellAbility> matches;
        final int[] extraBucketPerAct;
        final int multiplier;
        /** Source trigger, if this modifier came from a {@code TapsForMana}
         *  trigger. Used at application time to look up how many trigger
         *  doublers apply (Harmonic Prodigy etc.) — deferred to read time
         *  so walk order doesn't matter. */
        final forge.game.trigger.Trigger sourceTrigger;
        /** Host card of the source trigger (for doubler filter evaluation). */
        final Card sourceHost;

        ManaModifier(java.util.function.Predicate<SpellAbility> matches,
                     int[] extraBucketPerAct, int multiplier) {
            this(matches, extraBucketPerAct, multiplier, null, null);
        }

        ManaModifier(java.util.function.Predicate<SpellAbility> matches,
                     int[] extraBucketPerAct, int multiplier,
                     forge.game.trigger.Trigger sourceTrigger, Card sourceHost) {
            this.matches = matches;
            this.extraBucketPerAct = extraBucketPerAct;
            this.multiplier = multiplier;
            this.sourceTrigger = sourceTrigger;
            this.sourceHost = sourceHost;
        }
    }

    /**
     * A deferred cost-bearing OTMA. Built during Pass 2 instead of being
     * folded immediately into the budget, then admitted (or dropped) by
     * the fixed-point loop in {@link ActionScan#admitPendingCostOtmas}.
     *
     * The contribution snapshot lives in two parts:
     *  - {@code commitBuckets}: closure that applies the per-color bucket
     *    deltas. Runs unconditionally on admission. Bucket over-count is
     *    safe so we don't try to re-validate at admit time.
     *  - {@code netTotal} / {@code unbounded}: the totalMana contribution.
     *    Routed through the exclusion tracker via the recorded
     *    {@code (hostCard, exclusionGroup)} pair, then re-committed via
     *    {@link ActionScan#commitCardContribution} so the per-card formula
     *    {@code max(combo, tap+sac) + exile} stays correct.
     *
     * Threshold check: the loop admits a record iff
     * {@code budget.totalMana - sameSelf >= manaCostPerActivation}
     * where {@code sameSelf} is the card's current contribution in the
     * same exclusion group (because the cost can't be paid out of mana
     * the activation itself would consume).
     */
    static final class PendingCostOtma {
        final Card hostCard;
        final ActionScan.ExclusionGroup exclusionGroup;
        final int manaCostPerActivation;
        final int netTotal;
        final boolean unbounded;
        final Runnable commitBuckets;

        PendingCostOtma(Card hostCard, ActionScan.ExclusionGroup exclusionGroup,
                        int manaCostPerActivation, int netTotal, boolean unbounded,
                        Runnable commitBuckets) {
            this.hostCard = hostCard;
            this.exclusionGroup = exclusionGroup;
            this.manaCostPerActivation = manaCostPerActivation;
            this.netTotal = netTotal;
            this.unbounded = unbounded;
            this.commitBuckets = commitBuckets;
        }
    }

    // ---------------------------------------------------------------
    // Special pattern handlers — precise bounds for known safe wrappers
    // that would otherwise trigger the Parley-style structural bailout.
    // ---------------------------------------------------------------

    /**
     * Apply a special-pattern mana ability (Nykthos, Selvala-Parley). These
     * are mana abilities whose root SA has a non-Mana API but contains a
     * {@code DB$ Mana} subability — generically a bailout candidate, but
     * handled precisely here when we recognize the shape.
     */
    static void applySpecialPattern(SpellAbility sa, ActionScan scan, ManaBudget budget) {
        if (sa.getApi() == ApiType.ChooseColor) {
            handleNykthosPattern(sa, scan, budget);
            return;
        }
        if (sa.getApi() == ApiType.PeekAndReveal) {
            String precost = sa.getParam("PrecostDesc");
            if (precost != null && precost.contains("Parley")) {
                handleParleyPattern(sa, scan, budget);
                return;
            }
        }
    }

    /**
     * Nykthos, Shrine to Nyx: {2}, {T}: Choose a color. Add X mana of that
     * color where X is your devotion to that color.
     *
     * Bucket fold: add {@code devotion[c]} to each color bucket — an
     * over-count (only one color is actually chosen) but FP-safe because
     * the total gate catches the overstatement.
     *
     * Total fold: add {@code max(devotion) − activationManaCost}, clamped
     * at zero, since the player picks the best color and pays the {2} cost.
     */
    private static void handleNykthosPattern(SpellAbility sa, ActionScan scan, ManaBudget budget) {
        int cap = 1; // CostTap
        int[] devotion = scan.getDevotion();
        int maxDev = 0;
        for (int i = 0; i < 5; i++) {
            int dev = devotion[i];
            if (dev > maxDev) maxDev = dev;
        }
        int manaCost = activationManaCost(sa, scan);
        int netPerAct = Math.max(0, maxDev - manaCost);
        int net = saturatingMul(cap, netPerAct);
        Card host = sa.getHostCard();
        if (host == null || net <= 0) return;

        // Closure that folds the devotion-per-color contributions into the
        // budget. Captured for deferred admission so we don't fold buckets
        // until we know Nykthos's {2} cost is payable. Also mirrors the
        // fold onto the per-card contribution tracker for canAfford's
        // non-mana-ability conflict-subtraction.
        final int[] devSnapshot = new int[] { devotion[0], devotion[1], devotion[2], devotion[3], devotion[4] };
        final Card hostRef = host;
        Runnable commitBuckets = () -> {
            for (int i = 0; i < 5; i++) {
                int dev = devSnapshot[i];
                if (dev <= 0) continue;
                long sum = (long) budget.perColor[i] + (long) dev;
                budget.perColor[i] = sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
                scan.recordCardBucket(hostRef, i, dev, false);
            }
        };

        if (manaCost > 0) {
            // Defer to the fixed-point loop. Nykthos's {2} activation cost
            // can't be paid by Nykthos itself (it's the activating card),
            // and its TAP-group sameSelf may already include a basic
            // {T}: Add {C} ability if Nykthos has one. The loop checks
            // `budget.totalMana - sameSelf >= 2` before admitting.
            scan.addPendingCostOtma(new PendingCostOtma(
                    host, ActionScan.ExclusionGroup.TAP,
                    manaCost, net, false, commitBuckets));
            return;
        }

        // Cost-free path (defensive — Nykthos always has a cost, but
        // hand-built test SAs might not).
        commitBuckets.run();
        ActionScan.ExclusionKey key = new ActionScan.ExclusionKey(
                host, ActionScan.ExclusionGroup.TAP);
        Integer current = scan.exclusionMaxNetByKey.get(key);
        if (current == null || net > current) {
            scan.exclusionMaxNetByKey.put(key, net);
        }
    }

    /**
     * Selvala, Explorer Returned (Parley): {T}: Each player reveals the top
     * of their library. For each nonland card revealed, add {G} and you
     * gain 1 life. Then each player draws a card.
     *
     * Precise bounds:
     * - Max green produced = number of players (every reveal could be a nonland).
     * - Max life gained = number of players (same count).
     * - Hand size delta = +1 (each player draws a card — you are a player).
     */
    private static void handleParleyPattern(SpellAbility sa, ActionScan scan, ManaBudget budget) {
        int numPlayers = scan.getPlayer().getGame().getPlayers().size();
        // Bucket: green production.
        long newG = (long) budget.perColor[ManaBudget.G] + (long) numPlayers;
        budget.perColor[ManaBudget.G] = newG >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) newG;
        // Total: no activation mana cost (just tap), net = numPlayers.
        long newTotal = (long) budget.totalMana + (long) numPlayers;
        budget.totalMana = newTotal >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) newTotal;
        // Side-effect deltas (life gain and hand size).
        scan.getLifeGainDelta().addOtma(numPlayers);
        scan.getHandSizeDelta().addOtma(1);
    }

    /**
     * Resolve {@code ma} and fold its contribution into {@code budget}. Uses
     * {@code scan} for tracked-value reads.
     */
    /** Classify a mana ability's mutual-exclusion group. Each ability is
     *  placed in exactly one group based on which card-local resources its
     *  cost consumes. The commit step in {@code ActionScan.scan} then
     *  computes the per-card contribution using a formula that handles
     *  every combination correctly (see {@link ActionScan.ExclusionGroup}).
     *
     *  Returns {@code null} for abilities that don't share a card-local
     *  resource (including shared-pool OTMAs like PayLife, Discard,
     *  tapXType, etc. — those are handled as independent sums, which
     *  over-counts when the pool is shared but is FP-safe).
     */
    static ActionScan.ExclusionGroup exclusionGroupOf(SpellAbility sa) {
        Cost cost = sa.getPayCosts();
        if (cost == null) return null;
        boolean hasTap = false;
        boolean hasSacSelf = false;
        boolean hasExileSelfHand = false;
        for (CostPart part : cost.getCostParts()) {
            if (part instanceof CostTap) {
                hasTap = true;
            } else if (part instanceof CostSacrifice && part.payCostFromSource()) {
                hasSacSelf = true;
            } else if (part instanceof CostExile) {
                CostExile ex = (CostExile) part;
                if (ex.payCostFromSource() && ex.getFrom() != null
                        && ex.getFrom().contains(ZoneType.Hand)) {
                    hasExileSelfHand = true;
                }
            }
        }
        // Combined tap+sac on a single ability gets its own group — it's
        // not a pure TAP and not a pure SAC, and the commit formula
        // specifically compares it against TAP + SAC.
        if (hasTap && hasSacSelf) return ActionScan.ExclusionGroup.TAP_SAC_COMBO;
        if (hasTap) return ActionScan.ExclusionGroup.TAP;
        if (hasSacSelf) return ActionScan.ExclusionGroup.SAC;
        if (hasExileSelfHand) return ActionScan.ExclusionGroup.EXILE;
        return null;
    }

    static void estimate(SpellAbility ma, ActionScan scan, ManaBudget budget) {
        estimate(ma, scan, budget, true);
    }

    /** Internal entry that allows callers to disable the tap-self-OTMA
     *  max-per-card routing (used by special-pattern handlers that already
     *  manage their own total accounting). */
    static void estimate(SpellAbility ma, ActionScan scan, ManaBudget budget,
                         boolean routeTapSelfThroughScan) {
        AbilityManaPart mp = findManaPart(ma);
        if (mp == null) return;

        // "Special" mana producers are exotic shapes that don't fit the
        // per-color bucket model cleanly. Examples from the card catalog:
        //   - Special DoubleManaInPool (Doubling Cube): handled precisely
        //     by the post-Pass-2 pool-amplification step in ActionScan.
        //     Skipped here so it doesn't flip the legacy multiplier fallback.
        //   - Special EnchantedManaCost (Chromatic Orrery-adjacent): reads
        //     the enchanted card's mana cost.
        //   - Special EachColorAmong_Valid: produces one mana per distinct
        //     color in some filter.
        //   - Special EachColoredManaSymbol_Milled: based on milled cards.
        //   - Special ColorIdentity: commander color identity.
        //
        // Unrecognized Special shapes set the legacy flag (postProcess
        // flips producing colors to unbounded). DoubleManaInPool is
        // detected at Pass 1 time and counted in scan.doublingCubeCount.
        if (mp.isSpecialMana()) {
            String produced = mp.getOrigProduced();
            if (produced == null || !produced.contains("DoubleManaInPool")) {
                scan.manaMultiplierPresent = true;
            }
            return;
        }

        int cap = computeActivationCap(ma, scan);
        int perActivation = resolveAmount(ma, scan);
        int manaCostPerActivation = activationManaCost(ma, scan);
        foldIntoBudget(ma, mp, cap, perActivation, manaCostPerActivation,
                budget, routeTapSelfThroughScan ? scan : null);
    }

    /**
     * Mana-cost CMC of a single activation. Signets cost {1} and produce 2
     * mana → net per activation is 1, not 2. Nykthos costs {2}. We read the
     * ability's own pay costs here so foldIntoBudget can subtract it from
     * the gross production before crediting the budget.
     */
    static int activationManaCost(SpellAbility sa) {
        return activationManaCost(sa, null);
    }

    /** Mana portion of an activation cost, with optional cost-reducing
     *  static application. Passing a non-null {@code scan} threads the
     *  cached reduce/set cost statics through
     *  {@link forge.game.cost.CostAdjustment#applyCachedStatics}, producing
     *  the effective cost the player would actually pay. Used by the
     *  cost-bearing OTMA deferral and Doubling Cube post-process so
     *  discounts like Goblin Electromancer-style reducers (or any static
     *  that matches the activation) are honored.
     *
     *  FN-safe: on any exception or missing activating player the method
     *  falls back to the raw printed cost. */
    static int activationManaCost(SpellAbility sa, ActionScan scan) {
        Cost cost = sa.getPayCosts();
        if (cost == null || cost.hasNoManaCost()) return 0;
        var cpm = cost.getCostMana();
        if (cpm == null) return 0;
        var mana = cpm.getMana();
        if (mana == null) return 0;
        int printedCmc = mana.getCMC();
        if (scan == null) return printedCmc;

        // Apply discounts. Needs activatingPlayer set for CostAdjustment.
        boolean needsRestore = sa.getActivatingPlayer() == null;
        if (needsRestore) sa.setActivatingPlayer(scan.getPlayer());
        try {
            forge.game.mana.ManaCostBeingPaid working =
                    new forge.game.mana.ManaCostBeingPaid(mana);
            forge.game.cost.CostAdjustment.applyCachedStatics(
                    null, working, sa,
                    null, scan.getReduceCostStatics(), scan.getSetCostStatics());
            return working.getConvertedManaCost();
        } catch (Throwable ex) {
            return printedCmc;
        } finally {
            if (needsRestore) sa.setActivatingPlayer(null);
        }
    }

    private static AbilityManaPart findManaPart(SpellAbility sa) {
        AbilityManaPart mp = sa.getManaPart();
        if (mp != null) return mp;
        // Some triggered mana abilities hang the manaPart off a subability.
        var parts = sa.getAllManaParts();
        return parts.isEmpty() ? null : parts.get(0);
    }

    // ---------------------------------------------------------------
    // Activation cap
    // ---------------------------------------------------------------

    /** Returns {@link Integer#MAX_VALUE} for RMAs whose finite bound we can't
     *  compute, or a concrete finite cap for cost shapes we know how to
     *  bound. The tightest cap across all cost parts wins. */
    static int computeActivationCap(SpellAbility sa, ActionScan scan) {
        Cost cost = sa.getPayCosts();
        if (cost == null) return Integer.MAX_VALUE;

        int cap = Integer.MAX_VALUE;
        for (CostPart part : cost.getCostParts()) {
            int partCap = capForCostPart(part, sa, scan);
            if (partCap < cap) cap = partCap;
            if (cap == 0) return 0;
        }

        // ActivationLimit param caps it.
        if (sa.getRestrictions() != null && sa.getRestrictions().getLimitToCheck() != null) {
            String lim = sa.getRestrictions().getLimitToCheck();
            try {
                int n = Integer.parseInt(lim);
                if (n > 0 && n < cap) cap = n;
            } catch (NumberFormatException ignored) {
                // Variable limit — leave cap alone.
            }
        }
        return cap;
    }

    /** Per-cost-part cap contribution. Returns {@link Integer#MAX_VALUE} if
     *  this part doesn't bound the activation count (e.g. a mana cost, or a
     *  cost type we don't recognize). */
    private static int capForCostPart(CostPart part, SpellAbility sa, ActionScan scan) {
        if (part instanceof CostTap) return 1;
        if (part instanceof CostSacrifice) {
            CostSacrifice cs = (CostSacrifice) part;
            if (cs.payCostFromSource()) return 1;
            int available = totalCountForType(scan, cs.getType());
            int n = parseIntOrMax(cs.getAmount());
            if (n <= 0) return Integer.MAX_VALUE;
            if (available <= 0) return 0;
            return available / n;
        }
        if (part instanceof CostExile) {
            CostExile ex = (CostExile) part;
            if (ex.payCostFromSource() && ex.getFrom() != null
                    && ex.getFrom().contains(ZoneType.Hand)) {
                return 1;
            }
            return Integer.MAX_VALUE;
        }
        if (part instanceof CostTapType) {
            int available = untappedCountForType(scan, ((CostTapType) part).getType());
            int n = parseIntOrMax(part.getAmount());
            if (n <= 0) return Integer.MAX_VALUE;
            if (available <= 0) return 0;
            return available / n;
        }
        if (part instanceof CostPayLife) {
            // Cap = floor((life + maxLifeGain) / N). Unbounded if any mana
            // ability can give unbounded life.
            if (scan.getLifeGainDelta().isUnbounded()) return Integer.MAX_VALUE;
            long upperLife = (long) scan.getLife() + (long) scan.getLifeGainDelta().getMax();
            int n = parseIntOrMax(part.getAmount());
            if (n <= 0 || upperLife <= 0) return 0;
            return (int) Math.min(Integer.MAX_VALUE, upperLife / n);
        }
        if (part instanceof CostDiscard) {
            // Cap = floor((handSize + maxHandSizeGrowth) / N). Unbounded if
            // any mana ability can grow hand size unboundedly.
            if (scan.getHandSizeDelta().isUnbounded()) return Integer.MAX_VALUE;
            long upperHand = (long) scan.getHandSize() + (long) scan.getHandSizeDelta().getMax();
            int n = parseIntOrMax(part.getAmount());
            if (n <= 0 || upperHand <= 0) return 0;
            return (int) Math.min(Integer.MAX_VALUE, upperHand / n);
        }
        if (part instanceof CostRemoveCounter) {
            CostRemoveCounter rc = (CostRemoveCounter) part;
            if (!rc.payCostFromSource()) return Integer.MAX_VALUE;
            if (sa.getHostCard() == null || rc.counter == null) return Integer.MAX_VALUE;
            // Variable-amount counter costs (SubCounter<X/STORAGE>) don't
            // bound the activation count — the player chooses X at activation
            // time, and X = 0 is legal. The Amount SVar (Count$xPaid) reads
            // from the same counter pool, so the output is still bounded.
            // We just don't use this cost part for the activation cap.
            String amtStr = rc.getAmount();
            if (amtStr == null || "X".equals(amtStr) || amtStr.isEmpty()) {
                return Integer.MAX_VALUE;
            }
            int n;
            try {
                n = Integer.parseInt(amtStr);
            } catch (NumberFormatException ex) {
                return Integer.MAX_VALUE;
            }
            if (n <= 0) return Integer.MAX_VALUE;
            int have = sa.getHostCard().getCounters(rc.counter);
            if (have <= 0) return 0;
            return have / n;
        }
        if (part instanceof CostPayEnergy) {
            // Cap = floor(energy / N). Energy isn't currently modified by
            // any mana ability side effect we track, so no delta needed.
            int n = parseIntOrMax(part.getAmount());
            if (n <= 0) return Integer.MAX_VALUE;
            if (scan.getEnergy() <= 0) return 0;
            return scan.getEnergy() / n;
        }
        return Integer.MAX_VALUE;
    }

    private static int parseIntOrMax(String s) {
        if (s == null) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    /** Classify a cost-type filter string and return the matching untapped
     *  count from the scan. Unknown filters return {@link Integer#MAX_VALUE}
     *  (FN-safe — treats the ability as repeatable). */
    private static int untappedCountForType(ActionScan scan, String type) {
        if (type == null) return Integer.MAX_VALUE;
        String t = type;
        // Trim trailing "/token" tail when present in tapXType<N/filter/token>.
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        if (t.contains("token")) return scan.getUntappedTokens();
        if (t.startsWith("Permanent.token")) return scan.getUntappedTokens();
        if (t.startsWith("Creature") || t.equals("Creature")) return scan.untappedCreatures;
        if (t.startsWith("Artifact") || t.equals("Artifact")) return scan.untappedArtifacts;
        if (t.startsWith("Land") || t.equals("Land")) return scan.untappedLands;
        return Integer.MAX_VALUE;
    }

    private static int totalCountForType(ActionScan scan, String type) {
        if (type == null) return Integer.MAX_VALUE;
        String t = type;
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        if (t.contains("token")) return scan.tokenCount;
        if (t.startsWith("Creature")) return scan.creatureCount;
        if (t.startsWith("Artifact")) return scan.artifactCount;
        if (t.startsWith("Land")) return scan.landCount;
        if (t.startsWith("Enchantment")) return scan.enchantmentCount;
        return Integer.MAX_VALUE;
    }

    // ---------------------------------------------------------------
    // Amount resolution (FMG vs VMG)
    // ---------------------------------------------------------------

    /**
     * Returns the per-activation mana output as an upper bound, or
     * {@link #UNBOUNDED} if it should flip the produced-color buckets to
     * unbounded.
     */
    static int resolveAmount(SpellAbility sa, ActionScan scan) {
        String amt = sa.getParamOrDefault("Amount", "1");

        // FMG fast path: literal integer.
        try {
            int n = Integer.parseInt(amt);
            return Math.max(n, 0);
        } catch (NumberFormatException ignored) {
            // fall through to VMG
        }

        // VMG: inspect the SVar text.
        String svar = sa.getSVar(amt);
        if (svar == null || svar.isEmpty()) return UNBOUNDED;
        return classifyAndResolve(svar, scan, sa);
    }

    private static int classifyAndResolve(String svar, ActionScan scan, SpellAbility sa) {
        // Mutable-via-mana-abilities quantities — use current + delta or unbounded.
        if (contains(svar, "LifeTotal")) {
            return scan.lifeGainDelta.unbounded ? UNBOUNDED : scan.life + scan.lifeGainDelta.max;
        }
        if (contains(svar, "CardsInYourHand") || contains(svar, "InYourHand")
                || contains(svar, "InHand")) {
            return scan.handSizeDelta.unbounded ? UNBOUNDED : scan.handSize + scan.handSizeDelta.max;
        }
        if (contains(svar, "InYourGraveyard") || contains(svar, "InYourYard")
                || contains(svar, "CardsInGraveyard")) {
            return scan.graveyardDelta.unbounded ? UNBOUNDED
                    : scan.graveyardSize + scan.graveyardDelta.max;
        }
        if (contains(svar, "ManaPool") || contains(svar, "DoubleMana")) {
            return UNBOUNDED;
        }

        // Immutable snapshots — safe to use current value.
        if (contains(svar, "Devotion")) {
            // Find color name after "Devotion."; default to "all colors, pick max."
            int max = 0;
            for (int i = 0; i < 5; i++) max = Math.max(max, scan.devotion[i]);
            return max; // conservative over-count: max across all colors
        }
        // P/T references. Three variants; check in order from most-specific
        // to least-specific because the strings overlap:
        //   1. GreatestCardPower / GreatestCardToughness → board highest.
        //   2. TotalPower / TotalToughness → board sum.
        //   3. CardPower / CardToughness → source card's own value.
        //
        // Upper bound for all three forms adds {@code totalMinusOneCounters}
        // (board-wide for Greatest/Total, source-local for the Card variants)
        // because a mana ability whose cost is "remove a -1/-1 counter" can
        // raise the referenced quantity at activation time.
        boolean isGreatest = contains(svar, "Greatest") || contains(svar, "Highest");
        if (isGreatest && contains(svar, "Power")) {
            return scan.getHighestPower() + scan.getTotalMinusOneCounters();
        }
        if (isGreatest && contains(svar, "Toughness")) {
            return scan.getHighestToughness() + scan.getTotalMinusOneCounters();
        }
        if (contains(svar, "TotalPower")) {
            return scan.getTotalPower() + scan.getTotalMinusOneCounters();
        }
        if (contains(svar, "TotalToughness")) {
            return scan.getTotalToughness() + scan.getTotalMinusOneCounters();
        }
        if (contains(svar, "CardPower")) {
            if (sa != null && sa.getHostCard() != null) {
                int own = sa.getHostCard().getCurrentPower();
                int m1m1 = sa.getHostCard().getCounters(forge.game.card.CounterEnumType.M1M1);
                return own + m1m1;
            }
            return scan.getHighestPower() + scan.getTotalMinusOneCounters();
        }
        if (contains(svar, "CardToughness")) {
            if (sa != null && sa.getHostCard() != null) {
                int own = sa.getHostCard().getCurrentToughness();
                int m1m1 = sa.getHostCard().getCounters(forge.game.card.CounterEnumType.M1M1);
                return own + m1m1;
            }
            return scan.getHighestToughness() + scan.getTotalMinusOneCounters();
        }
        // Creature filters — match any Count$Valid Creature*YouCtrl pattern.
        // This covers plain Creature.YouCtrl AND qualified variants like
        // Creature.ChosenType+YouCtrl (Three Tree City), Creature.Elf+YouCtrl,
        // Creature.nonToken+YouCtrl, etc. The upper bound is creatureCount
        // regardless of qualifier — the qualifier can only narrow the set
        // further, so the unqualified count is a safe over-count.
        if (contains(svar, "YourCreatures") || contains(svar, "Creatures.YouCtrl")
                || isYouCtrlFilter(svar, "Creature")) {
            return scan.creatureCount;
        }
        if (contains(svar, "YourLandsUntapped") || contains(svar, "Lands.YouCtrl+untapped")) {
            return scan.untappedLands;
        }
        if (contains(svar, "YourLands") || isYouCtrlFilter(svar, "Land")) {
            return scan.landCount;
        }
        if (contains(svar, "YourArtifacts") || isYouCtrlFilter(svar, "Artifact")) {
            return scan.artifactCount;
        }
        if (contains(svar, "YourEnchantments") || isYouCtrlFilter(svar, "Enchantment")) {
            return scan.enchantmentCount;
        }
        // Source-card counter references — Astral Cornucopia, Calciform
        // Pools, Bottomless Vault, etc. Pattern: Count$CardCounters.<TYPE>
        // (e.g. CHARGE, STORAGE). Read directly from the host card.
        if (contains(svar, "CardCounters") && sa != null && sa.getHostCard() != null) {
            String counterName = extractAfter(svar, "CardCounters.");
            forge.game.card.CounterType ct = counterName == null ? null
                    : forge.game.card.CounterType.getType(counterName);
            if (ct != null) {
                return sa.getHostCard().getCounters(ct);
            }
            return UNBOUNDED;
        }

        // Count$xPaid — the amount comes from whatever "X" the activation
        // cost bound. For storage-counter batteries the cost is
        // SubCounter<X/TYPE>, so X is bounded by the source's counters.
        // For X-mana costs (Kicker-style) we over-count to UNBOUNDED.
        if (contains(svar, "xPaid") && sa != null && sa.getPayCosts() != null) {
            for (CostPart part : sa.getPayCosts().getCostParts()) {
                if (part instanceof CostRemoveCounter) {
                    CostRemoveCounter rc = (CostRemoveCounter) part;
                    if ("X".equals(rc.getAmount()) && rc.payCostFromSource()
                            && rc.counter != null && sa.getHostCard() != null) {
                        return sa.getHostCard().getCounters(rc.counter);
                    }
                }
            }
            return UNBOUNDED;
        }

        // Generic fallback: for unrecognized Count$Valid shapes, delegate to
        // Forge's own amount calculator. This evaluates the filter predicate
        // against the current game state and returns a concrete number. If
        // anything goes wrong (unresolved context, thrown exception), fall
        // back to UNBOUNDED (FN-safe).
        if (svar.startsWith("Count$Valid ") && sa != null && sa.getHostCard() != null) {
            try {
                String amt = sa.getParamOrDefault("Amount", "1");
                int n = forge.game.ability.AbilityUtils.calculateAmount(
                        sa.getHostCard(), amt, sa);
                if (n >= 0) return n;
            } catch (Throwable ex) {
                // fall through
            }
        }

        // Unknown shape. Unbounded is FN-safe.
        return UNBOUNDED;
    }

    /** Extract the token following a given prefix in the SVar string, up to
     *  the next whitespace, plus, or end-of-string. */
    private static String extractAfter(String svar, String prefix) {
        int idx = svar.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length();
        int end = start;
        while (end < svar.length()) {
            char ch = svar.charAt(end);
            if (Character.isWhitespace(ch) || ch == '+' || ch == '$' || ch == ',') break;
            end++;
        }
        return svar.substring(start, end);
    }

    private static boolean contains(String s, String needle) {
        return s.contains(needle);
    }

    private static boolean matchesValid(String svar, String validExpr) {
        return svar.startsWith("Count$Valid ") && svar.contains(validExpr);
    }

    /** Match any {@code Count$Valid <Type>*YouCtrl} pattern. The type token
     *  can be followed by any sequence of qualifier tokens (separated by
     *  {@code '+'}, {@code '.'}, or words) before reaching {@code YouCtrl}.
     *  Used to recognize {@code Creature.ChosenType+YouCtrl},
     *  {@code Creature.Elf+YouCtrl}, plain {@code Creature.YouCtrl}, etc. */
    private static boolean isYouCtrlFilter(String svar, String typePrefix) {
        if (!svar.startsWith("Count$Valid ")) return false;
        int typeIdx = svar.indexOf(typePrefix);
        if (typeIdx < 0) return false;
        return svar.indexOf("YouCtrl", typeIdx) > typeIdx;
    }

    // ---------------------------------------------------------------
    // Produced-color fold
    // ---------------------------------------------------------------

    /**
     * Fold a mana ability's contribution into the budget.
     *
     * Bucket accounting (never subtracted — these are gross over-estimates
     * for color reachability). Each color in the produced string receives
     * {@code cap × multiplier × occurrencesOfColor}. Izzet Signet
     * (produced {@code "U R"}, multiplier 1, cap 1) adds +1 to U and +1 to R.
     *
     * Total accounting (the net constraint). Gross total production minus
     * the activation's own mana cost, times cap. Izzet Signet: gross 2,
     * cost 1, net 1 per activation × cap 1 = +1 total. Sol Ring: gross 2,
     * cost 0, net 2. Mountain: gross 1, cost 0, net 1. A Mountain + Sol Ring
     * + Signet board now correctly reports totalMana = 4 even though the
     * buckets sum to more.
     *
     * If the ability is an RMA (cap = MAX_VALUE) we flag both the produced
     * bucket(s) and totalMana as unbounded. If the cap is 0 (e.g. Baylen with
     * too few tokens) nothing is added.
     */
    static void foldIntoBudget(SpellAbility sa, AbilityManaPart mp, int cap, int multiplier,
                                int manaCostPerActivation, ManaBudget budget,
                                ActionScan scanForTapSelfRouting) {
        if (cap <= 0 || multiplier == 0) return;

        boolean unbounded = cap == Integer.MAX_VALUE || multiplier == UNBOUNDED;

        // Route to the unrestricted main budget or a per-ability restricted
        // contribution.
        int[] targetPerColor;
        boolean[] targetUnbounded;
        ManaBudget.RestrictedContribution rc = null;
        String restriction = mp.getManaRestrictions();
        if (restriction != null && !restriction.isEmpty()) {
            rc = budget.getOrCreateRestricted(mp);
            targetPerColor = rc.perColor;
            targetUnbounded = rc.unbounded;
        } else {
            targetPerColor = budget.perColor;
            targetUnbounded = budget.unbounded;
        }

        // Parse the produced string into per-color gross production counts.
        int[] producedPerBucket = producedPerBucket(sa, mp);
        // grossPerAct = sum of tokens the produced string generates once.
        int grossPerAct = 0;
        for (int v : producedPerBucket) grossPerAct += v;
        if (grossPerAct == 0) grossPerAct = 1; // safety for unrecognized strings
        // Combo producers over-count in buckets (Scoured Barrens writes
        // 1 to both W and B since the player picks one). Per-unit
        // contribution is 1 (one mana unit), so cap grossPerAct at 1;
        // the caller's `multiplier` (from Amount) scales per-activation.
        if (mp.isComboMana()) {
            grossPerAct = 1;
        }

        // Apply precise mana modifiers (High Tide, Badgermole Cub, Mana
        // Reflection, etc.). Composition rules:
        //  - Flat bonuses add: each matching modifier's extraBucketPerAct
        //    contributes additively to producedPerBucket[i] and grossPerAct.
        //  - Multipliers compose: multiple ×2 modifiers → ×4, ×8, etc.
        //    Applied here to the local `multiplier` variable so all
        //    downstream math (buckets, total, netTotal) sees the composed
        //    effect uniformly.
        // Skipped entirely via null-check when no modifiers are in play —
        // zero overhead on the common fast path.
        if (scanForTapSelfRouting != null
                && scanForTapSelfRouting.getManaModifiers() != null) {
            int[] flatBonus = null;
            for (ManaModifier mod : scanForTapSelfRouting.getManaModifiers()) {
                if (!mod.matches.test(sa)) continue;
                if (mod.multiplier > 1) {
                    multiplier = saturatingMul(multiplier, mod.multiplier);
                }
                if (mod.extraBucketPerAct != null) {
                    if (flatBonus == null) flatBonus = new int[ManaBudget.NUM_BUCKETS];
                    // Scale by trigger doubler count (Harmonic Prodigy
                    // etc.). Lookup happens here, at application time,
                    // so it doesn't matter whether the doubler was
                    // discovered before or after this modifier during
                    // the Pass 1 walk. For modifiers without a source
                    // trigger (e.g. Mana Reflection), factor stays at 1.
                    int factor = 1;
                    if (mod.sourceTrigger != null) {
                        int doublers = scanForTapSelfRouting.countTriggerDoublers(
                                mod.sourceTrigger, mod.sourceHost);
                        factor = 1 + doublers;
                    }
                    for (int i = 0; i < ManaBudget.NUM_BUCKETS; i++) {
                        flatBonus[i] += mod.extraBucketPerAct[i] * factor;
                    }
                }
            }
            if (flatBonus != null) {
                // Fold flat bonuses into producedPerBucket so the existing
                // bucket loop below produces the right output, and into
                // grossPerAct so netTotal's gross accounting is correct.
                for (int i = 0; i < ManaBudget.NUM_BUCKETS; i++) {
                    if (flatBonus[i] > 0) {
                        producedPerBucket[i] += flatBonus[i];
                        grossPerAct += flatBonus[i];
                    }
                }
            }
        }

        // Apply each colored contribution to its bucket. Card-bucket
        // tracking (for non-mana-ability cost subtraction in canAfford)
        // is mirrored into the closure below for the cost-bearing path
        // so dropped records don't leave stale contributions behind.
        for (int i = 0; i < ManaBudget.NUM_BUCKETS; i++) {
            int countPerAct = producedPerBucket[i];
            if (countPerAct <= 0) continue;
            int bucketAdd = unbounded ? Integer.MAX_VALUE
                    : saturatingMul(saturatingMul(cap, multiplier), countPerAct);
            applyToColor(targetPerColor, targetUnbounded, i, bucketAdd, unbounded);
        }

        // Total-mana accounting uses NET (gross - cost). The user-visible
        // rule: buckets over-count, total reflects truth.
        int netTotal;
        if (unbounded) {
            netTotal = UNBOUNDED;
        } else {
            int grossTotal = saturatingMul(saturatingMul(cap, multiplier), grossPerAct);
            int costTotal = manaCostPerActivation > 0
                    ? saturatingMul(cap, manaCostPerActivation) : 0;
            long diff = (long) grossTotal - (long) costTotal;
            netTotal = diff <= 0 ? 0
                    : diff >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) diff;
        }

        // Mutual-exclusion routing. If this OTMA belongs to a card-local
        // exclusion group (TAP, SAC, or EXILE), its total contribution is
        // deferred to the per-card-group max tracker on the scan. Each
        // group gets committed separately at the end of Pass 2.
        //
        // This routing applies to BOTH unrestricted and restricted (RC)
        // contributions: a card with `{T}: Add {C}` and `{T}: Add {R}
        // (creature spells only)` (Rockface Village) shares the tap between
        // the two abilities, so their total contribution must be taken as
        // a max, not summed. Restricted contributions still populate their
        // bucket via rc.perColor for color-reachability; the rc.totalMana
        // sum is deliberately skipped so we don't double-count.
        ActionScan.ExclusionGroup excl = scanForTapSelfRouting == null
                ? null : exclusionGroupOf(sa);

        // Cost-bearing OTMAs (manaCostPerActivation > 0): defer to the
        // fixed-point loop in ActionScan.scan(). The loop will admit this
        // record only if the rest of the budget (excluding this card's
        // same-group contribution) can pay the activation cost — fixing
        // the Three Tree City over-count where TTC alone advertised a
        // rainbow ability whose 2-cost was unpayable.
        //
        // Bucket fold is moved into a closure so admission can apply it
        // cleanly. We undo the bucket fold above and re-do it inside the
        // closure via a captured snapshot — simpler than refactoring the
        // whole bucket loop.
        if (manaCostPerActivation > 0 && scanForTapSelfRouting != null) {
            // Undo the bucket writes we did above so they only land on
            // admission.
            for (int i = 0; i < ManaBudget.NUM_BUCKETS; i++) {
                int countPerAct = producedPerBucket[i];
                if (countPerAct <= 0) continue;
                if (unbounded) {
                    // The corresponding bucket was set to true above; we
                    // can't safely "unset" because another ability may
                    // also have flipped it. Accept the FP — it's only one
                    // bit of bucket overcount, and the total still gets
                    // properly gated below.
                    continue;
                }
                int bucketAdd = saturatingMul(saturatingMul(cap, multiplier), countPerAct);
                long restored = (long) targetPerColor[i] - (long) bucketAdd;
                targetPerColor[i] = restored < 0 ? 0 : (int) restored;
            }

            // Capture for closure.
            final int[] targetPerColorRef = targetPerColor;
            final boolean[] targetUnboundedRef = targetUnbounded;
            final int[] producedSnapshot = producedPerBucket;
            final int capF = cap;
            final int multF = multiplier;
            final boolean unboundedF = unbounded;
            final Card hostF = sa.getHostCard();
            final ActionScan scanF = scanForTapSelfRouting;
            Runnable commitBuckets = () -> {
                for (int i = 0; i < ManaBudget.NUM_BUCKETS; i++) {
                    int countPerAct = producedSnapshot[i];
                    if (countPerAct <= 0) continue;
                    int bucketAdd = unboundedF ? Integer.MAX_VALUE
                            : saturatingMul(saturatingMul(capF, multF), countPerAct);
                    applyToColor(targetPerColorRef, targetUnboundedRef, i, bucketAdd, unboundedF);
                    if (scanF != null && hostF != null) {
                        scanF.recordCardBucket(hostF, i, bucketAdd, unboundedF);
                    }
                }
            };
            scanForTapSelfRouting.addPendingCostOtma(
                    new PendingCostOtma(sa.getHostCard(), excl,
                            manaCostPerActivation, netTotal, unbounded, commitBuckets));
            return;
        }

        // Record per-card bucket contributions (cost-free path). Cost-bearing
        // abilities defer this into the closure above so dropped records
        // don't leave stale contributions. This mirrors the budget.perColor
        // writes we did at the top of foldIntoBudget.
        if (scanForTapSelfRouting != null && sa.getHostCard() != null) {
            for (int i = 0; i < ManaBudget.NUM_BUCKETS; i++) {
                int countPerAct = producedPerBucket[i];
                if (countPerAct <= 0) continue;
                int bucketAdd = unbounded ? Integer.MAX_VALUE
                        : saturatingMul(saturatingMul(cap, multiplier), countPerAct);
                scanForTapSelfRouting.recordCardBucket(sa.getHostCard(), i, bucketAdd, unbounded);
            }
        }

        if (excl != null && sa.getHostCard() != null) {
            Card host = sa.getHostCard();
            ActionScan.ExclusionKey key = new ActionScan.ExclusionKey(host, excl);
            if (unbounded) {
                scanForTapSelfRouting.exclusionUnboundedKeys.add(key);
            } else if (netTotal > 0) {
                Integer current = scanForTapSelfRouting.exclusionMaxNetByKey.get(key);
                if (current == null || netTotal > current) {
                    scanForTapSelfRouting.exclusionMaxNetByKey.put(key, netTotal);
                }
            }
            return;
        }

        if (rc == null) {
            if (unbounded) {
                budget.totalUnbounded = true;
            } else {
                long sum = (long) budget.totalMana + (long) netTotal;
                budget.totalMana = sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
            }
        } else {
            if (unbounded) {
                rc.totalUnbounded = true;
            } else {
                long sum = (long) rc.totalMana + (long) netTotal;
                rc.totalMana = sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
            }
        }
    }

    /**
     * Parse a {@code Produced$} string into per-bucket token counts. One
     * activation of the ability produces this many mana tokens of each type.
     * Numeric tokens go to C. Color symbols go to their native bucket.
     * {@code Any} / {@code Special} / multi-color {@code Combo} all go to
     * RAINBOW as 1 token each.
     */
    static int[] producedPerBucket(SpellAbility sa, AbilityManaPart mp) {
        int[] out = new int[ManaBudget.NUM_BUCKETS];
        if (mp == null) { out[ManaBudget.RAINBOW] = 1; return out; }

        // "Any" / "Combo Any" → 1 mana, pick any color. Rainbow.
        if (mp.isAnyMana()) { out[ManaBudget.RAINBOW] = 1; return out; }
        // Special producers are handled in estimate() by flagging the scan
        // as multiplier-present, which promotes producing colors to unbounded
        // during postProcess. If we somehow reach producedPerBucket for a
        // Special producer it means the caller bypassed that path — fall
        // back to rainbow as a safety net.
        if (mp.isSpecialMana()) { out[ManaBudget.RAINBOW] = 1; return out; }

        String produced;
        if (mp.isComboMana()) {
            // Combo mana — e.g. Scoured Barrens' "Combo W B" → 1 mana,
            // choose from {W, B}. Cascade Bluffs' "Combo U R" Amount 2
            // → 2 mana, each chosen from {U, R}.
            //
            // We store 1 PER COMBO COLOR (per-unit contribution), not
            // per-activation. The caller's `multiplier` (from Amount)
            // scales to per-activation: bucketAdd = cap × Amount × 1 =
            // Amount per color. FP-safe over-count: each color can
            // reach Amount mana if the player commits all units to it.
            //
            // grossPerAct (sum of buckets) is capped at 1 downstream so
            // the netTotal doesn't double-count (combo buckets over-count
            // the sum, but real production is just Amount).
            //
            // We do NOT put combo mana into the RAINBOW bucket, because
            // RAINBOW is spendable on any color including ones not in
            // the combo — that would let Scoured Barrens appear to
            // produce green, which is wrong.
            String combo = mp.getComboColors(sa);
            if (combo == null || combo.isBlank()) {
                out[ManaBudget.RAINBOW] = 1;
                return out;
            }
            boolean recognized = false;
            for (String tok : combo.trim().split("\\s+")) {
                if (tok.isEmpty()) continue;
                switch (tok) {
                    case "W": out[ManaBudget.W] = 1; recognized = true; break;
                    case "U": out[ManaBudget.U] = 1; recognized = true; break;
                    case "B": out[ManaBudget.B] = 1; recognized = true; break;
                    case "R": out[ManaBudget.R] = 1; recognized = true; break;
                    case "G": out[ManaBudget.G] = 1; recognized = true; break;
                    case "C": out[ManaBudget.C] = 1; recognized = true; break;
                    default: break;
                }
            }
            if (!recognized) {
                out[ManaBudget.RAINBOW] = 1;
            }
            return out;
        } else {
            produced = mp.getOrigProduced();
        }

        if (produced == null || produced.isEmpty()) {
            out[ManaBudget.RAINBOW] = 1;
            return out;
        }

        boolean recognized = false;
        for (String tok : produced.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            switch (tok) {
                case "W": out[ManaBudget.W]++; recognized = true; break;
                case "U": out[ManaBudget.U]++; recognized = true; break;
                case "B": out[ManaBudget.B]++; recognized = true; break;
                case "R": out[ManaBudget.R]++; recognized = true; break;
                case "G": out[ManaBudget.G]++; recognized = true; break;
                case "C": out[ManaBudget.C]++; recognized = true; break;
                default:
                    try {
                        int n = Integer.parseInt(tok);
                        if (n > 0) {
                            out[ManaBudget.C] += n;
                            recognized = true;
                        }
                    } catch (NumberFormatException ignored) {
                        // Unknown token (Chosen, ColorID, etc.) — fallback.
                    }
            }
        }
        if (!recognized) {
            out[ManaBudget.RAINBOW] = 1;
        }
        return out;
    }

    private static int saturatingMul(int a, int b) {
        long r = (long) a * (long) b;
        if (r >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) r;
    }

    private static void applyToColor(int[] perColor, boolean[] unbounded, int colorIdx, int total, boolean isUnbounded) {
        if (isUnbounded) {
            unbounded[colorIdx] = true;
            return;
        }
        long sum = (long) perColor[colorIdx] + (long) total;
        if (sum >= Integer.MAX_VALUE) {
            unbounded[colorIdx] = true;
        } else {
            perColor[colorIdx] = (int) sum;
        }
    }

    /**
     * Returns a color mask for what this mana ability can produce. Defaults to
     * {@link MagicColor#ALL_COLORS} for anything exotic we don't recognize (FN-safe).
     */
    static byte resolveProducedColors(SpellAbility sa, AbilityManaPart mp) {
        if (mp == null) return MagicColor.ALL_COLORS;

        String produced = mp.getOrigProduced();
        if (produced == null || produced.isEmpty()) return MagicColor.ALL_COLORS;

        if (mp.isAnyMana()) return MagicColor.ALL_COLORS;
        if (mp.isSpecialMana()) return MagicColor.ALL_COLORS; // DoubleManaInPool et al.

        if (mp.isComboMana()) {
            String combo = mp.getComboColors(sa);
            return parseColorMask(combo);
        }
        return parseColorMask(produced);
    }

    /**
     * Parse a space-separated produced string (e.g. {@code "W"}, {@code "W U"},
     * {@code "1"}, {@code "W U B R G"}). Numbers count as colorless/generic → 0.
     */
    static byte parseColorMask(String produced) {
        if (produced == null || produced.isEmpty()) return MagicColor.ALL_COLORS;
        byte mask = 0;
        boolean sawNumeric = false;
        for (String tok : produced.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            switch (tok) {
                case "W": mask |= MagicColor.WHITE; break;
                case "U": mask |= MagicColor.BLUE; break;
                case "B": mask |= MagicColor.BLACK; break;
                case "R": mask |= MagicColor.RED; break;
                case "G": mask |= MagicColor.GREEN; break;
                case "C": /* colorless mask stays 0 */ break;
                default:
                    try {
                        Integer.parseInt(tok);
                        sawNumeric = true;
                    } catch (NumberFormatException ignored) {
                        // Exotic tokens (Chosen, ColorID, Combo, Special) shouldn't
                        // reach here because callers handle them first — but if
                        // they do, be conservative.
                        return MagicColor.ALL_COLORS;
                    }
            }
        }
        if (mask == 0 && sawNumeric) return 0; // pure generic/colorless
        return mask;
    }
}
