package forge.game.player;

import java.util.ArrayList;
import java.util.List;

import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.cost.Cost;
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostPartMana;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;

/**
 * Per-bucket mana upper bound used by the hasAvailableActions heuristic and
 * the actionable-card highlight system. Built by {@link ActionScan}. Pure data
 * and pure logic — does not reach into PlayerView or any GUI layer.
 *
 * Invariants:
 *  - Every write must over-count or flip unbounded; never under-count.
 *  - {@code canAfford} is FN-safe: when in doubt it returns {@code true}.
 *
 * Bucket layout (7 buckets):
 *   0 W, 1 U, 2 B, 3 R, 4 G  — specific-color buckets. Each pays its own
 *        colored shards, hybrid shards containing that color, and generic.
 *   5 C                      — colorless bucket. Pays {C} shards and generic.
 *                              Does NOT pay colored shards.
 *   6 RAINBOW                — "any color" mana (Baylen, Chromatic Lantern,
 *                              City of Brass...). Pays any colored shard, any
 *                              hybrid, and generic. Does NOT pay {C} shards.
 *
 * Generic mana is not a separate bucket — any bucket pays generic (fungible).
 * Total mana is tracked alongside for a coarse "can I afford this at all?"
 * gate independent of color reachability; see {@link #totalMana}.
 */
public final class ManaBudget {
    static final int W = 0, U = 1, B = 2, R = 3, G = 4, C = 5, RAINBOW = 6;
    static final int NUM_BUCKETS = 7;
    /** Number of colored buckets (W/U/B/R/G). */
    static final int NUM_WUBRG = 5;

    final int[] perColor = new int[NUM_BUCKETS];
    final boolean[] unbounded = new boolean[NUM_BUCKETS];

    /** Total mana upper bound across all buckets. Tracked separately from the
     *  per-bucket counts so color-converting RMAs can flip a colored bucket
     *  unbounded without claiming infinite total mana. A spell can only be
     *  affordable when {@code totalMana >= cmc || totalUnbounded}. */
    int totalMana;
    boolean totalUnbounded;

    /** Upper bound on snow mana available this priority window. Snow sources
     *  (lands with the Snow supertype, Coldsteel Heart, etc.) also contribute
     *  to their regular color buckets — this counter is a parallel cap used
     *  only when paying {S} shards. A non-snow source cannot pay {S}; our
     *  previous model treated {S} as generic, which over-counted affordability
     *  for snow-requiring spells like Rimefeather Owl's ability. */
    int snowMana;
    boolean snowUnbounded;

    /** Restricted-spend contributions. Each entry's mana is only added to
     *  the working budget for an SA that its {@code RestrictValid$} filter
     *  permits. The filter check uses the existing
     *  {@link AbilityManaPart#meetsManaRestrictions(SpellAbility)}. */
    static final class RestrictedContribution {
        final int[] perColor = new int[NUM_BUCKETS];
        final boolean[] unbounded = new boolean[NUM_BUCKETS];
        final AbilityManaPart sourceMp;
        int totalMana;
        boolean totalUnbounded;
        RestrictedContribution(AbilityManaPart mp) { this.sourceMp = mp; }
    }
    final List<RestrictedContribution> restrictedContribs = new ArrayList<>();

    /** StaticAbility Mode$ ManaConvert (Mycosynth Lattice etc.) — any bucket pays any shard. */
    boolean allColorsFungible;

    /** Event$ProduceMana replacements or TapsForMana→Mana triggers exist on the battlefield. */
    boolean manaMultiplierPresent;

    ManaBudget() {}

    /**
     * Locate or create a restricted contribution matching the given mana
     * ability's restriction string. We key by the source {@code AbilityManaPart}
     * because restriction-equivalence is non-trivial to compute and reusing
     * the same mana part lets us delegate to {@code meetsManaRestrictions}.
     */
    RestrictedContribution getOrCreateRestricted(AbilityManaPart mp) {
        for (RestrictedContribution rc : restrictedContribs) {
            if (rc.sourceMp == mp) return rc;
        }
        RestrictedContribution rc = new RestrictedContribution(mp);
        restrictedContribs.add(rc);
        return rc;
    }

    boolean anyUnbounded() {
        for (boolean u : unbounded) if (u) return true;
        return false;
    }

    /** Exposed for tests. Total mana upper bound across all buckets (net
     *  of each source's own activation cost). */
    public int getTotalMana() { return totalMana; }

    /** Exposed for tests. True if any source is unboundable. */
    public boolean isTotalUnbounded() { return totalUnbounded; }

    /** Exposed for tests. Read a specific bucket's amount. */
    public int getBucket(int idx) { return perColor[idx]; }

    /** Exposed for tests. Read a specific bucket's unbounded flag. */
    public boolean isBucketUnbounded(int idx) { return unbounded[idx]; }

    /** Bucket index constants for tests. */
    public static final int IDX_W = W;
    public static final int IDX_U = U;
    public static final int IDX_B = B;
    public static final int IDX_R = R;
    public static final int IDX_G = G;
    public static final int IDX_C = C;
    public static final int IDX_RAINBOW = RAINBOW;

    /**
     * FN-safe affordability check. Returns {@code true} if the spell/ability
     * could plausibly be paid given this budget. Over-approximates aggressively.
     *
     * @param sa the spell or ability to check
     * @param scan the {@link ActionScan} that produced this budget (needed for
     *     Convoke/Delve/Improvise resource counts)
     */
    public boolean canAfford(SpellAbility sa, ActionScan scan) {
        Cost payCosts = sa.getPayCosts();
        if (payCosts == null || !payCosts.hasManaCost()) return true;

        CostPartMana cpm = payCosts.getCostMana();
        if (cpm == null) return true;
        ManaCost baseCost = cpm.getMana();
        if (baseCost == null || baseCost.isZero()) return true;

        // Apply cost-modifying statics from the cached Pass 1 lists. No
        // battlefield walk, no interactive prompts. Raisers operate on Cost,
        // reducers and SetCost operate on the ManaCostBeingPaid.
        Cost raiseHolder;
        try {
            raiseHolder = payCosts.copy();
        } catch (RuntimeException ex) {
            raiseHolder = payCosts;
        }
        // Commander tax: when casting a commander from the command zone,
        // the cost is increased by {2} for each time it was previously
        // cast from the command zone this game. Mirror of the inline
        // block in CostAdjustment.adjust() (forge-game/src/main/java/
        // forge/game/cost/CostAdjustment.java:56-63). At pre-cast time
        // host.getCastFrom() is null, so we key off "host is commander
        // AND currently in the command zone" instead.
        if (sa.isSpell() && sa.getHostCard() != null
                && sa.getHostCard().isCommander()
                && sa.getHostCard().getZone() != null
                && sa.getHostCard().getZone().getZoneType()
                        == forge.game.zone.ZoneType.Command) {
            forge.game.player.Player owner = sa.getHostCard().getOwner();
            if (owner != null) {
                int casts = owner.getCommanderCast(sa.getHostCard());
                if (casts > 0) {
                    try {
                        raiseHolder.add(new Cost(ManaCost.get(2 * casts), false));
                    } catch (Throwable ignored) {}
                }
            }
        }
        // Offering / Emerge: cast a spell by sacrificing a creature, with
        // the emerge/offering cost reduced by the sacrificed creature's
        // mana value. Applied only to the alt-cost variant SA (the
        // normal-cast variant stays at its full printed cost).
        //
        // sa.isEmerge() / sa.isOffering() return true only when this SA
        // represents the alt-cost path (set during getAllPossibleAbilities'
        // alt-cost enumeration).
        //
        // Optimal discount = highest-CMC qualifying creature the player
        // controls. We don't filter by type (Offering requires same type
        // as the spell; Emerge may have a type filter) — using any
        // creature is FP-safe (over-discount → over-report affordability).
        int offeringEmergeDiscount = 0;
        if (sa.isSpell() && (sa.isEmerge() || sa.isOffering())) {
            int bestSacCmc = 0;
            for (forge.game.card.Card c : scan.getPlayer().getCreaturesInPlay()) {
                int cmc = c.getCMC();
                if (cmc > bestSacCmc) bestSacCmc = cmc;
            }
            offeringEmergeDiscount = bestSacCmc;
        }
        ManaCostBeingPaid working = new ManaCostBeingPaid(baseCost);
        if (offeringEmergeDiscount > 0) {
            working.decreaseGenericMana(offeringEmergeDiscount);
        }
        boolean needsRestore = sa.getActivatingPlayer() == null;
        if (needsRestore) sa.setActivatingPlayer(scan.getPlayer());
        try {
            CostAdjustment.applyCachedStatics(raiseHolder, working, sa,
                    scan.getRaiseCostStatics(), scan.getReduceCostStatics(), scan.getSetCostStatics());
            CostPartMana raisedCpm = raiseHolder.getCostMana();
            if (raisedCpm != null && raisedCpm.getMana() != null
                    && !raisedCpm.getMana().equals(baseCost)) {
                ManaCostBeingPaid raised = new ManaCostBeingPaid(raisedCpm.getMana());
                CostAdjustment.applyCachedStatics(null, raised, sa, null,
                        scan.getReduceCostStatics(), scan.getSetCostStatics());
                working = raised;
            }
        } catch (Throwable ex) {
            working = new ManaCostBeingPaid(baseCost);
        } finally {
            if (needsRestore) sa.setActivatingPlayer(null);
        }

        // Working copy of the unrestricted budget.
        int[] work = new int[NUM_BUCKETS];
        boolean[] workU = new boolean[NUM_BUCKETS];
        for (int i = 0; i < NUM_BUCKETS; i++) {
            work[i] = perColor[i];
            workU[i] = unbounded[i];
        }
        int workingTotal = totalMana;
        boolean workingTotalUnbounded = totalUnbounded;
        int workingSnow = snowMana;
        boolean workingSnowUnbounded = snowUnbounded;

        // Conflict-subtract: if this SA is a non-mana ability whose cost
        // shares a card-local resource (tap / sac-self / exile-self) with
        // the host card's mana abilities, the card can't also contribute
        // mana toward this SA's cost. Example: Abzan Banner's draw is
        // {W}{B}{G}, {T}, Sac — you can't tap Banner for mana AND pay
        // the draw's tap. Subtract the card's own mana contribution.
        // Restless Spire's animate ({U}{R}) has neither tap nor sac-self,
        // so exclusionGroupOf returns null → no subtraction → Spire's
        // mana remains available.
        if (!sa.isManaAbility() && !sa.isSpell() && sa.getHostCard() != null) {
            forge.game.card.Card host = sa.getHostCard();
            ActionScan.ExclusionGroup saGroup = ManaAbilityEstimator.exclusionGroupOf(sa);
            if (saGroup != null) {
                Integer cardTotal = scan.getCommittedCardTotals().get(host);
                int[] cardBuckets = scan.getCardBucketContributions().get(host);
                if (cardTotal != null && cardTotal > 0 && !workingTotalUnbounded) {
                    long newTotal = (long) workingTotal - (long) cardTotal;
                    workingTotal = newTotal < 0 ? 0 : (int) newTotal;
                }
                if (cardBuckets != null) {
                    for (int i = 0; i < NUM_BUCKETS; i++) {
                        if (cardBuckets[i] <= 0) continue;
                        long newBucket = (long) work[i] - (long) cardBuckets[i];
                        work[i] = newBucket < 0 ? 0 : (int) newBucket;
                    }
                }
            }
        }

        // Merge restricted contributions whose filter permits this SA.
        for (RestrictedContribution rc : restrictedContribs) {
            boolean permitted;
            try {
                permitted = rc.sourceMp.meetsManaRestrictions(sa);
            } catch (Throwable ex) {
                permitted = true;
            }
            if (!permitted) continue;
            for (int i = 0; i < NUM_BUCKETS; i++) {
                long sum = (long) work[i] + (long) rc.perColor[i];
                work[i] = sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
                if (rc.unbounded[i]) workU[i] = true;
            }
            long sum = (long) workingTotal + (long) rc.totalMana;
            workingTotal = sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
            if (rc.totalUnbounded) workingTotalUnbounded = true;
        }

        // Delve / Convoke / Improvise top-up.
        int genericReduction = 0;
        int convokePool = 0;
        int[] convokeColorsLeft = null;
        if (sa.isSpell() && sa.getHostCard() != null) {
            if (sa.getHostCard().hasKeyword(Keyword.DELVE)) {
                genericReduction += scan.getGraveyardSize();
            }
            if (sa.getHostCard().hasKeyword(Keyword.IMPROVISE)) {
                genericReduction += scan.getUntappedArtifacts();
            }
            if (sa.getHostCard().hasKeyword(Keyword.CONVOKE)) {
                convokePool = scan.getUntappedCreatures();
                int[] src = scan.getConvokeColors();
                convokeColorsLeft = new int[] { src[0], src[1], src[2], src[3], src[4] };
            }
        }

        // Split shards: colored first, colorless strict second, generic third.
        java.util.List<ManaCostShard> shards = working.getUnpaidShards();

        // Coarse total-mana gate. If we can't produce enough total mana for
        // the effective CMC (shards that actually demand real mana), bail
        // early regardless of colors. Phyrexian and X shards don't demand
        // mana — they're paid with life or chosen to be 0.
        int effectiveCmc = 0;
        for (ManaCostShard shard : shards) {
            if (shard == ManaCostShard.X || shard == ManaCostShard.COLORED_X) continue;
            if (shard.isPhyrexian()) continue;
            effectiveCmc++;
        }
        int delveImproviseConvokeCap = genericReduction + convokePool;
        long availableTotal = (long) workingTotal + (long) delveImproviseConvokeCap;
        if (!workingTotalUnbounded && availableTotal < effectiveCmc) {
            return false;
        }

        // Pass 1: strict-colored shards (W, U, B, R, G and hybrids).
        for (ManaCostShard shard : shards) {
            if (shard == ManaCostShard.X || shard == ManaCostShard.COLORED_X) continue;
            if (shard.isPhyrexian()) continue;
            if (shard.isGeneric() || shard.isColorless() || shard.isSnow()) continue;
            if (allColorsFungible) {
                if (!deductAny(work, workU)) return false;
                continue;
            }
            byte mask = shard.getColorMask();
            if (deductFromMask(mask, work, workU)) continue;
            // Rainbow bucket pays any colored shard.
            if (deductRainbow(work, workU)) continue;
            // Convoke pool with a color-matching creature.
            if (convokeColorsLeft != null && convokePool > 0
                    && deductFromConvokeColored(mask, convokeColorsLeft)) {
                convokePool--;
                continue;
            }
            return false;
        }

        // Pass 2a: strict colorless {C} shards. Colorless mana only — does not
        // accept colored or rainbow.
        for (ManaCostShard shard : shards) {
            if (shard == ManaCostShard.X || shard == ManaCostShard.COLORED_X) continue;
            if (shard.isPhyrexian()) continue;
            if (!shard.isColorless() || shard.isGeneric() || shard.isSnow()) continue;
            if (allColorsFungible) {
                if (!deductAny(work, workU)) return false;
                continue;
            }
            if (workU[C]) continue;
            if (work[C] > 0) { work[C]--; continue; }
            return false;
        }

        // Pass 2b: snow {S} shards. Only snow-typed mana sources can pay
        // these. The parallel snowMana counter tracks the upper bound;
        // each {S} shard consumes 1 from it. ManaConvert (Mycosynth
        // Lattice) lifts the restriction.
        for (ManaCostShard shard : shards) {
            if (shard == ManaCostShard.X || shard == ManaCostShard.COLORED_X) continue;
            if (shard.isPhyrexian()) continue;
            if (!shard.isSnow()) continue;
            if (allColorsFungible) {
                if (!deductAny(work, workU)) return false;
                continue;
            }
            if (workingSnowUnbounded) continue;
            if (workingSnow > 0) { workingSnow--; continue; }
            return false;
        }

        // Pass 3: generic shards. Fully fungible — any bucket,
        // Delve/Improvise reduction, then Convoke pool leftover.
        // (Snow shards were handled in pass 2b.)
        for (ManaCostShard shard : shards) {
            if (shard == ManaCostShard.X || shard == ManaCostShard.COLORED_X) continue;
            if (shard.isPhyrexian()) continue;
            if (shard.isSnow()) continue;
            if (!shard.isGeneric()) continue;
            // Note: COLORLESS shards were handled in Pass 2 and skipped here.
            if (genericReduction > 0) { genericReduction--; continue; }
            if (deductAny(work, workU)) continue;
            if (convokePool > 0) { convokePool--; continue; }
            return false;
        }
        return true;
    }

    /** Try to spend a convoke creature of a color matching {@code mask}. */
    private static boolean deductFromConvokeColored(byte mask, int[] convokeColorsLeft) {
        for (int i = 0; i < NUM_WUBRG; i++) {
            byte colorBit = forge.card.MagicColor.WUBRG[i];
            if ((mask & colorBit) == 0) continue;
            if (convokeColorsLeft[i] > 0) {
                convokeColorsLeft[i]--;
                return true;
            }
        }
        return false;
    }

    /** Deduct 1 mana from any bucket (fungible generic pay). Prefers the
     *  most-stocked bucket so we don't prematurely drain buckets that might
     *  be needed for specific colored shards later. */
    private static boolean deductAny(int[] work, boolean[] workU) {
        for (boolean u : workU) if (u) return true;
        int bestIdx = -1;
        int bestVal = 0;
        for (int i = 0; i < NUM_BUCKETS; i++) {
            if (work[i] > bestVal) {
                bestVal = work[i];
                bestIdx = i;
            }
        }
        if (bestIdx < 0) return false;
        work[bestIdx]--;
        return true;
    }

    /** Deduct 1 mana from a bucket whose color bit is set in {@code mask}.
     *  Only considers the 5 colored buckets, not colorless or rainbow. */
    private static boolean deductFromMask(byte mask, int[] work, boolean[] workU) {
        int chosen = -1;
        int chosenVal = 0;
        for (int i = 0; i < NUM_WUBRG; i++) {
            byte colorBit = forge.card.MagicColor.WUBRG[i];
            if ((mask & colorBit) == 0) continue;
            if (workU[i]) return true;
            if (work[i] > chosenVal) {
                chosen = i;
                chosenVal = work[i];
            }
        }
        if (chosen < 0) return false;
        work[chosen]--;
        return true;
    }

    /** Deduct 1 mana from the rainbow bucket. */
    private static boolean deductRainbow(int[] work, boolean[] workU) {
        if (workU[RAINBOW]) return true;
        if (work[RAINBOW] > 0) {
            work[RAINBOW]--;
            return true;
        }
        return false;
    }
}
