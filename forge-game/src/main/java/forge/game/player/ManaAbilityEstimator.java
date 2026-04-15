package forge.game.player;

import forge.card.MagicColor;
import forge.game.ability.ApiType;
import forge.game.cost.Cost;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExert;
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
            if (dev <= 0) continue;
            long sum = (long) budget.perColor[i] + (long) dev;
            budget.perColor[i] = sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
            if (dev > maxDev) maxDev = dev;
        }
        int manaCost = activationManaCost(sa);
        int netPerAct = Math.max(0, maxDev - manaCost);
        int net = saturatingMul(cap, netPerAct);
        long newTotal = (long) budget.totalMana + (long) net;
        budget.totalMana = newTotal >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) newTotal;
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
    static void estimate(SpellAbility ma, ActionScan scan, ManaBudget budget) {
        AbilityManaPart mp = findManaPart(ma);
        if (mp == null) return;

        // "Special" mana producers are exotic shapes that don't fit the
        // per-color bucket model cleanly. Examples from the card catalog:
        //   - Special DoubleManaInPool (Doubling Cube): doubles whatever's
        //     in the pool. Output is unbounded relative to current pool.
        //   - Special EnchantedManaCost (Chromatic Orrery-adjacent): reads
        //     the enchanted card's mana cost.
        //   - Special EachColorAmong_Valid: produces one mana per distinct
        //     color in some filter.
        //   - Special EachColoredManaSymbol_Milled: based on milled cards.
        //   - Special ColorIdentity: commander color identity.
        //
        // Rather than pattern-match each variant precisely, we flag the
        // scan as having a mana multiplier present. postProcess will then
        // promote every already-producing color to unbounded and flip
        // totalUnbounded. This is FP-safe (over-count) but correct in the
        // sense that the spell's affordability no longer depends on our
        // ability to predict the exact amount.
        if (mp.isSpecialMana()) {
            scan.manaMultiplierPresent = true;
            return;
        }

        int cap = computeActivationCap(ma, scan);
        int perActivation = resolveAmount(ma, scan);
        int manaCostPerActivation = activationManaCost(ma);
        foldIntoBudget(ma, mp, cap, perActivation, manaCostPerActivation, budget);
    }

    /**
     * Mana-cost CMC of a single activation. Signets cost {1} and produce 2
     * mana → net per activation is 1, not 2. Nykthos costs {2}. We read the
     * ability's own pay costs here so foldIntoBudget can subtract it from
     * the gross production before crediting the budget.
     */
    static int activationManaCost(SpellAbility sa) {
        Cost cost = sa.getPayCosts();
        if (cost == null || cost.hasNoManaCost()) return 0;
        var cpm = cost.getCostMana();
        if (cpm == null) return 0;
        var mana = cpm.getMana();
        return mana == null ? 0 : mana.getCMC();
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
        if (part instanceof CostExert) return 1;
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
        if (contains(svar, "YourCreatures") || contains(svar, "Creatures.YouCtrl")
                || matchesValid(svar, "Creature.YouCtrl")) {
            return scan.creatureCount;
        }
        if (contains(svar, "YourLandsUntapped") || contains(svar, "Lands.YouCtrl+untapped")) {
            return scan.untappedLands;
        }
        if (contains(svar, "YourLands") || matchesValid(svar, "Land.YouCtrl")) {
            return scan.landCount;
        }
        if (contains(svar, "YourArtifacts") || matchesValid(svar, "Artifact.YouCtrl")) {
            return scan.artifactCount;
        }
        if (contains(svar, "YourEnchantments") || matchesValid(svar, "Enchantment.YouCtrl")) {
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

        // Fallback: unknown shape (e.g. Count$Valid with a subtype filter we don't
        // special-case). Unbounded is FN-safe.
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
                                int manaCostPerActivation, ManaBudget budget) {
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

        // Apply each colored contribution to its bucket.
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
            // Combo mana — e.g. "Combo W U" → 1 mana, choose from 2 colors.
            // FP-safe over-count: treat as rainbow.
            String combo = mp.getComboColors(sa);
            if (combo == null || combo.isBlank()) {
                out[ManaBudget.RAINBOW] = 1;
                return out;
            }
            out[ManaBudget.RAINBOW] = 1;
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
