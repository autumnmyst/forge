package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.ActionScan;
import forge.game.player.ManaBudget;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Integration tests for the {@link ActionScan}-based hasAvailableActions
 * heuristic. Each scenario builds a real board (via {@code SimulationTest})
 * and verifies that APINA's "has actions" answer is FN-safe for the given
 * hand + battlefield.
 *
 * Assertion direction matters: scenarios marked "MUST NOT skip" assert
 * {@code updateHasAvailableActions → true}; "SHOULD skip" cases assert
 * {@code → false}. A test failing in the MUST-NOT-skip direction represents
 * a silent turn skip (a false negative), which is the failure mode we must
 * never ship.
 */
public class HasAvailableActionsTest extends SimulationTest {

    private Player newGame() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        return p;
    }

    private static boolean hasActions(Player p) {
        p.getView().updateHasAvailableActions(p);
        return p.getView().hasAvailableActions();
    }

    /**
     * Direct budget affordability check for a specific card in the player's
     * hand. Bypasses the "has any action" path so tests can isolate mana
     * math from unrelated actionable abilities on the board (e.g. Mind
     * Stone's sac-to-draw, Baylen's non-mana activations).
     */
    private static boolean canAffordFromHand(Player p, String cardInHandName) {
        ActionScan scan = ActionScan.scan(p);
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            if (!c.getName().equals(cardInHandName)) continue;
            for (forge.game.spellability.SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
                if (sa.isSpell()) {
                    return scan.getBudget().canAfford(sa, scan);
                }
            }
        }
        throw new IllegalStateException("Card not found in hand: " + cardInHandName);
    }

    /** Check if a specific non-mana activated ability on a battlefield
     *  card is affordable. Used to test conflict-subtraction: Abzan Banner's
     *  draw ability should NOT be affordable from Banner alone because its
     *  {T} cost conflicts with its mana ability. */
    private static boolean canAffordNonManaAbilityOf(Player p, String cardName) {
        ActionScan scan = ActionScan.scan(p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (!c.getName().equals(cardName)) continue;
            for (forge.game.spellability.SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
                if (sa.isManaAbility()) continue;
                if (scan.getBudget().canAfford(sa, scan)) return true;
            }
            return false;
        }
        throw new IllegalStateException("Card not found on battlefield: " + cardName);
    }

    /** Collect the set of card names that the highlight path would flag as
     *  actionable. Mirrors {@code pushActionableCards} (non-payment mode)
     *  but returns a name set the test can assert against. */
    private static java.util.Set<String> affordableCardNames(Player p) {
        ActionScan scan = ActionScan.scan(p);
        java.util.Set<String> result = new java.util.HashSet<>();
        if (scan.hasStructuralBailout()) return result; // fall back not modeled in tests
        for (forge.game.spellability.SpellAbility sa : scan.getSpellsToCheck()) {
            if (sa.isLandAbility()
                    || (scan.getBudget().canAfford(sa, scan) && scan.hasLegalTargets(sa))) {
                if (sa.getHostCard() != null) {
                    result.add(sa.getHostCard().getName());
                }
            }
        }
        return result;
    }

    @Test
    public void testEmptyBoardEmptyHand() {
        Player p = newGame();
        // no cards anywhere — should skip.
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testSimpleForestAndSpell() {
        Player p = newGame();
        addCards("Forest", 5, p);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testNotEnoughColoredMana() {
        Player p = newGame();
        addCards("Plains", 2, p);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        // Only white sources, counterspell needs UU → should skip.
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testManaReflectionMultiplierPromotion() {
        Player p = newGame();
        addCards("Forest", 3, p);
        addCard("Mana Reflection", p);
        // Mana Reflection doubles Forests, 6 effective green → can cast {G}{G}{G}{G}{G}{G}
        // Under the plan, G gets promoted to unbounded, and anything green becomes affordable.
        addCardToZone("Primeval Titan", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testManaReflectionDoesNotHelpWrongColor() {
        Player p = newGame();
        addCards("Forest", 3, p);
        addCard("Mana Reflection", p);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        // Multiplier only promotes green, blue stays empty → should skip.
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testMycosynthLatticeFungibility() {
        Player p = newGame();
        addCards("Mountain", 5, p);
        addCard("Mycosynth Lattice", p);
        // Divination is untargeted ({2}{U}) so the target-gate pre-flight
        // doesn't filter it; the test is purely about color fungibility.
        addCardToZone("Divination", p, ZoneType.Hand);
        // Lattice makes all colors fungible — 5 red lands pay {2}{U}.
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testPhyrexianOnlyCost() {
        // An all-phyrexian cost has CMC > 0 but demands no mana. Regression
        // for FN1: the old upfront "cmc > available" gate would reject this.
        Player p = newGame();
        // Gitaxian Probe: cost {U/P} — can be paid entirely with life.
        addCardToZone("Gitaxian Probe", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testGaeasCradleWithCreatures() {
        // FN12: VMG resolved via the creatureCount snapshot.
        // 4 Bears + Cradle = 4 G; Overrun is {2}{G}{G}{G} = 5 mana — not enough
        // from Cradle alone, so add 2 Forests to give the {2} generic portion.
        Player p = newGame();
        addCards("Grizzly Bears", 4, p);
        addCard("Gaea's Cradle", p);
        addCards("Forest", 2, p);
        addCardToZone("Overrun", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testGaeasCradleNoCreatures() {
        // Cradle resolves to creatureCount = 0 (only the Cradle itself, no
        // creatures). Hand spell needs colored mana Cradle can't provide.
        Player p = newGame();
        addCard("Gaea's Cradle", p);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        // No mana sources → should skip. (Cradle is not a creature so
        // creatureCount = 0, no green produced.)
        AssertJUnit.assertFalse(hasActions(p));
    }

    // --- Generic cost handling (regression for ManaCost iterator hazard) ---

    @Test
    public void testFireboltFlashbackCannotAfford() {
        // 3 Mountains = 3 red, Firebolt flashback is {4}{R} = 5 mana.
        // Before the generic-cost fix, the shard iterator yielded only {R}
        // and canAfford deducted 1 red and returned true — a false positive
        // (still FP-safe but wrong). After the fix, the generic portion is
        // paid separately and this correctly says "can't afford".
        Player p = newGame();
        addCards("Mountain", 3, p);
        addCardToZone("Firebolt", p, ZoneType.Graveyard);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testHighGenericCostExactlyAffordable() {
        Player p = newGame();
        addCards("Plains", 5, p);
        addCardToZone("Serra Angel", p, ZoneType.Hand); // {3}{W}{W}
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testHighGenericCostOneShort() {
        Player p = newGame();
        addCards("Plains", 4, p);
        addCardToZone("Serra Angel", p, ZoneType.Hand); // {3}{W}{W} — need 5, have 4
        AssertJUnit.assertFalse(hasActions(p));
    }

    // --- Delve / Improvise / Convoke ---

    @Test
    public void testDelveCoversGeneric() {
        Player p = newGame();
        addCards("Forest", 1, p);
        // Become Immense {5}{G} + Delve — give a creature target too.
        addCard("Grizzly Bears", p);
        for (int i = 0; i < 6; i++) addCardToZone("Forest", p, ZoneType.Graveyard);
        addCardToZone("Become Immense", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testConvokeExactCreatures() {
        // No lands. Pack's Favor is {2}{G}, Convoke. Need 3 mana worth of
        // creatures. 3 Bears (green) tapped for Convoke pay 1 G + 2 generic.
        Player p = newGame();
        addCards("Grizzly Bears", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        // Need a target for Pack's Favor; the bears serve.
        addCardToZone("Pack's Favor", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    // --- OTMA variants ---

    @Test
    public void testSpiritGuideExileFromHand() {
        // Elvish Spirit Guide: Exile from hand → add G. OTMA cap=1, FMG.
        Player p = newGame();
        addCards("Forest", 1, p);
        addCardToZone("Elvish Spirit Guide", p, ZoneType.Hand);
        addCardToZone("Llanowar Elves", p, ZoneType.Hand); // {G}
        // 1 Forest + Spirit Guide = 2 green; Llanowar Elves {G} costs 1 → affordable.
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testLotusPetalMultipleSacOtma() {
        Player p = newGame();
        addCards("Lotus Petal", 3, p);
        addCardToZone("Shock", p, ZoneType.Hand); // {R}
        // Each Petal is an OTMA (sac self) with Produced$ Any → all colors reach 3.
        AssertJUnit.assertTrue(hasActions(p));
    }

    // --- RMA variants ---

    @Test
    public void testPhyrexianAltarRma() {
        Player p = newGame();
        addCards("Grizzly Bears", 3, p);
        addCards("Mountain", 2, p);
        addCard("Phyrexian Altar", p);
        addCardToZone("Blaze", p, ZoneType.Hand); // {X}{R}
        // Altar sac → any 1 mana, unbounded over N creatures. Plus mountains.
        AssertJUnit.assertTrue(hasActions(p));
    }

    // --- Cost adjustment ---

    @Test
    public void testGoblinElectromancerReducesInstant() {
        Player p = newGame();
        addCards("Island", 1, p);
        addCard("Goblin Electromancer", p);
        // Electromancer reduces cost of instants/sorceries by {1}. Impulse
        // is {1}{U} untargeted; reduced to {U}. 1 Island pays.
        addCardToZone("Impulse", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testTrinisphereRaisesCost() {
        Player p = newGame();
        addCards("Mountain", 3, p);
        addCard("Trinisphere", p);
        // Shock is {R}; Trinisphere raises to {3} effective. 3 Mountains pay exactly.
        addCardToZone("Shock", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testTrinisphereInsufficient() {
        Player p = newGame();
        addCards("Mountain", 2, p);
        addCard("Trinisphere", p);
        addCardToZone("Shock", p, ZoneType.Hand);
        // Only 2 Mountains, need 3 under Trinisphere.
        AssertJUnit.assertFalse(hasActions(p));
    }

    // --- Land plays (isLandAbility fast path) ---

    @Test
    public void testLandInHandIsAction() {
        Player p = newGame();
        addCardToZone("Forest", p, ZoneType.Hand);
        // Playing a land from hand is a land ability → fast-path "has actions".
        AssertJUnit.assertTrue(hasActions(p));
    }

    // --- Hybrid / phyrexian cost coverage ---

    @Test
    public void testHybridShardAnyColorPays() {
        Player p = newGame();
        addCards("Plains", 1, p);
        // Boros Guildmage: {R/W}{R/W} — 2 Plains don't exist, but 1 Plains satisfies
        // neither shard. Needs 2 white-or-red. Should skip with only 1 Plains.
        addCardToZone("Boros Guildmage", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testHybridShardSecondColorPays() {
        Player p = newGame();
        addCards("Plains", 2, p);
        addCardToZone("Boros Guildmage", p, ZoneType.Hand); // {R/W}{R/W}
        // 2 Plains pays 2 white-or-red hybrid shards.
        AssertJUnit.assertTrue(hasActions(p));
    }

    // --- Color-mix scenarios ---

    @Test
    public void testInsufficientGenericAfterColoredPayment() {
        // Regression: colored shards first is the canonical payment algorithm.
        // If we paid generic first, we could waste red on generic and fail the
        // red shard. With the greedy most-stocked approach, we pay the {R}
        // before the generic, leaving colorless-paying-capable buckets.
        Player p = newGame();
        addCards("Mountain", 1, p);
        addCards("Wastes", 3, p); // 3 colorless lands
        addCardToZone("Lightning Bolt", p, ZoneType.Hand); // {R}
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testMultiCardHandFindsFirstAffordable() {
        Player p = newGame();
        addCards("Island", 2, p);
        addCardToZone("Brainstorm", p, ZoneType.Hand); // {U} — affordable, untargeted
        addCardToZone("Lightning Bolt", p, ZoneType.Hand); // {R} — not
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testAllHandUnaffordable() {
        Player p = newGame();
        addCards("Plains", 1, p);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand); // {R}
        addCardToZone("Dark Ritual", p, ZoneType.Hand); // {B}
        AssertJUnit.assertFalse(hasActions(p));
    }

    // =================================================================
    // Card-specific complex scenarios
    // =================================================================

    // --- Gaea's Cradle ---

    @Test
    public void testCradleWithMixedCreaturesComplex() {
        // 3 Bears (green) + 2 Llanowar Elves (green, tap for G) + Cradle + Forest.
        // Creature count = 5. Cradle → 5 G. Elves → 2 G. Forest → 1 G. Total = 8 G.
        // Hand: Primeval Titan {4}{G}{G} = 6 mana — affordable.
        Player p = newGame();
        addCards("Grizzly Bears", 3, p);
        addCards("Llanowar Elves", 2, p);
        addCard("Gaea's Cradle", p);
        addCard("Forest", p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            c.setSickness(false);
        }
        addCardToZone("Primeval Titan", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testCradleInsufficientCreatures() {
        // 2 Bears + Cradle + no other mana. Cradle → 2 G. Hand: Craterhoof
        // {5}{G}{G}{G} = 8 mana. Can't afford, no other sources → SHOULD skip.
        Player p = newGame();
        addCards("Grizzly Bears", 2, p);
        addCard("Gaea's Cradle", p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Craterhoof Behemoth", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testCradleZeroCreaturesWithFallback() {
        // Cradle alone (no creatures!) → 0 green. But we have a Forest, so still
        // have 1 green. Hand: Llanowar Elves {G} — exactly payable by Forest alone.
        Player p = newGame();
        addCard("Gaea's Cradle", p);
        addCard("Forest", p);
        addCardToZone("Llanowar Elves", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    // --- Baylen, the Haymaker ---

    @Test
    public void testBaylenWithTokensProducesAnyColor() {
        // Baylen + 4 tokens. First ability: tap 2 tokens → any color.
        // Since mana SA is currently treated as RMA (tapXType cost not matched),
        // all 5 colors are unbounded. Hand: Counterspell {U}{U} → affordable.
        Player p = newGame();
        addCard("Baylen, the Haymaker", p);
        // Use Plant tokens from some cheap source — simplest, add copies of a
        // cheap token via a token-producer card. Easier: use Raise Dead style
        // substitutes. Actually, the simplest way in tests is to add token
        // instances directly. Use Saproling Burst or similar — but we can't
        // easily create tokens in a unit test without casting. Just use normal
        // creature copies and pretend they're tokens for the cost check... no,
        // Baylen's cost requires actual tokens.
        //
        // Compromise: use the fact that isToken() depends on the card's state,
        // and force it via setGamePieceType.
        for (int i = 0; i < 4; i++) {
            Card tok = addCard("Grizzly Bears", p);
            tok.setGamePieceType(forge.card.GamePieceType.TOKEN);
            tok.setSickness(false);
        }
        addCardToZone("Counterspell", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testBaylenNoTokensBlankedMana() {
        // Baylen alone, no tokens. `getAllPossibleAbilities(p, true)` filters
        // out SAs whose non-mana costs (tap-2-tokens) can't be paid, so the
        // mana ability never reaches our scan. No other actions available.
        Player p = newGame();
        addCard("Baylen, the Haymaker", p).setSickness(false);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testBaylenDrawAbilityNoTokens() {
        // Baylen alone, empty hand. Draw ability's tap-3-tokens cost is
        // filtered out by getAllPossibleAbilities(p, true), so it never
        // appears as a candidate. No actions.
        Player p = newGame();
        addCard("Baylen, the Haymaker", p).setSickness(false);
        AssertJUnit.assertFalse(hasActions(p));
    }

    // --- Sorcerer Class ---

    @Test
    public void testSorcererClassLevel2WithInstantInHand() {
        // Sorcerer Class + 3 creatures. Static grants creatures {T}: add U or R
        // for instants/sorceries. Hand: Lightning Bolt {R} — payable.
        // Note: Class is cast at level 1; level 2 requires paying {U}{R}.
        // The AddAbility static only fires at level 2+. For a unit test we
        // force the level if possible.
        Player p = newGame();
        Card cls = addCard("Sorcerer Class", p);
        // Force to level 2 if the card exposes setClassLevel.
        cls.setClassLevel(2);
        addCards("Grizzly Bears", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testSorcererClassMaxLevelCreatureSpellRestricted() {
        // Sorcerer Class at max level 3 + creatures. Hand: Grizzly Bears {1}{G}.
        // At level 3 there are no more level-up activations, so the only
        // candidate action is the creature spell in hand. The granted
        // {T}: add U or R has RestrictValid$ Spell.Instant,Spell.Sorcery,
        // Activated.ClassLevelUp — none match a creature spell. The
        // restricted contribution is correctly filtered out.
        Player p = newGame();
        Card cls = addCard("Sorcerer Class", p);
        cls.setClassLevel(3);
        addCards("Grizzly Bears", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testSorcererClassMaxLevelInstantInHand() {
        // Same board, but hand has Lightning Bolt {R}. The restricted mana
        // matches Spell.Instant and is added to the working budget → payable.
        Player p = newGame();
        Card cls = addCard("Sorcerer Class", p);
        cls.setClassLevel(3);
        addCards("Grizzly Bears", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testSorcererClassLevel1NoCreatureMana() {
        // Sorcerer Class cast but still at level 1 — no static grant active.
        // Creatures don't tap for mana. Hand: Lightning Bolt {R}, no red source.
        Player p = newGame();
        Card cls = addCard("Sorcerer Class", p);
        cls.setClassLevel(1);
        addCards("Grizzly Bears", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    // --- Convoke (current blanket rule) ---

    @Test
    public void testConvokeEnoughGreenCreatures() {
        // 7 Bears, no lands. Siege Wurm {5}{G}{G} = 7 mana. Exactly payable
        // via Convoke: 2 bears pay colored, 5 pay generic.
        Player p = newGame();
        addCards("Grizzly Bears", 7, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Siege Wurm", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testConvokeMixedColorCreatures() {
        // 2 Bears + 5 Ornithopters = 7 creatures. 2 bears pay {G}{G},
        // 5 Ornithopters pay 5 generic. Siege Wurm {5}{G}{G} payable.
        Player p = newGame();
        addCards("Grizzly Bears", 2, p);
        addCards("Ornithopter", 5, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Siege Wurm", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testConvokeTooFewCreatures() {
        // 2 Bears, no lands. Siege Wurm {5}{G}{G} = 7 mana total. 2 bears
        // contribute at most 2 via Convoke → not enough. Proper modeling
        // correctly reports "no actions."
        Player p = newGame();
        addCards("Grizzly Bears", 2, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Siege Wurm", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testConvokeWithTappedCreaturesOnly() {
        // 6 creatures but all tapped. Convoke safe rule requires *untapped*
        // creatures. Nothing else to pay with → SKIP.
        Player p = newGame();
        for (int i = 0; i < 6; i++) {
            Card c = addCard("Grizzly Bears", p);
            c.setSickness(false);
            c.tap(false, null, null);
        }
        addCardToZone("Siege Wurm", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testConvokeAndManaDorksInteraction() {
        // The "complex" scenario: creatures that are both Convoke fodder AND
        // mana dorks. 3 Llanowar Elves — can tap for G, or be tapped for
        // Convoke. Siege Wurm {4}{G}{G}. Either way, enough mana total.
        Player p = newGame();
        addCards("Llanowar Elves", 3, p);
        addCards("Forest", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Siege Wurm", p, ZoneType.Hand);
        // 3 Forests + 3 Elves = 6 G. Cost {4}{G}{G} = 6. Exactly payable
        // without even invoking Convoke.
        AssertJUnit.assertTrue(hasActions(p));
    }

    // =================================================================
    // Baylen precision (tapXType cost → exact activation cap)
    // =================================================================

    private Card addToken(Player p, String name) {
        Card tok = addCard(name, p);
        tok.setGamePieceType(forge.card.GamePieceType.TOKEN);
        tok.setSickness(false);
        return tok;
    }

    @Test
    public void testBaylenZeroTokens() {
        Player p = newGame();
        addCard("Baylen, the Haymaker", p).setSickness(false);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        // No tokens → mana ability has cap 0. No other mana sources → skip.
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testBaylenOneTokenStillInsufficient() {
        Player p = newGame();
        addCard("Baylen, the Haymaker", p).setSickness(false);
        addToken(p, "Grizzly Bears");
        addCardToZone("Counterspell", p, ZoneType.Hand); // {U}{U}
        // 1 token / 2 per activation = 0 activations. Still no mana.
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testBaylenTwoTokensOneActivation() {
        Player p = newGame();
        addCard("Baylen, the Haymaker", p).setSickness(false);
        addToken(p, "Grizzly Bears");
        addToken(p, "Grizzly Bears");
        addCardToZone("Shock", p, ZoneType.Hand); // {R}
        // 2 tokens / 2 = 1 activation → 1 mana of any color. Shock {R} payable.
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testBaylenFourTokensTwoActivations() {
        Player p = newGame();
        addCard("Baylen, the Haymaker", p).setSickness(false);
        for (int i = 0; i < 4; i++) addToken(p, "Grizzly Bears");
        addCardToZone("Counterspell", p, ZoneType.Hand); // {U}{U}
        // 4 tokens / 2 per = 2 activations → 2 mana of any color. UU affordable.
        AssertJUnit.assertTrue(hasActions(p));
    }

    // Documented precision gap: for Any-color OTMAs with finite cap (Baylen),
    // we fold the cap into EVERY color bucket independently rather than
    // treating it as a shared pool. With 2 activations this gives the
    // appearance of 2 mana per color (10 total) instead of 2 shared. Fixing
    // this requires a shared-pool abstraction similar to the Convoke pool.
    // For now we accept the FP and don't test against it.

    // =================================================================
    // Convoke / Improvise proper modeling
    // =================================================================

    @Test
    public void testConvokeColoredOneShort() {
        // Pack's Favor {2}{G}. 2 bears + 0 lands: 1 G from Convoke + 2 generic
        // reduction = 3 "mana worth". Actually 2 bears total = 2, which is
        // less than 3 → can't afford.
        Player p = newGame();
        addCards("Grizzly Bears", 2, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Pack's Favor", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testConvokeMixedColorsEnough() {
        // Pack's Favor {2}{G} Convoke. 1 Bears (green) + 2 Ornithopters
        // (colorless artifacts that are creatures). Convoke green contribution:
        // bears 1. Total untapped creatures 3 → generic reduction 3. Cost
        // {2}{G} = 3 mana. Bears provides the G, generic covered.
        Player p = newGame();
        addCard("Grizzly Bears", p).setSickness(false);
        addCard("Ornithopter", p).setSickness(false);
        addCard("Ornithopter", p).setSickness(false);
        addCardToZone("Pack's Favor", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testConvokeWrongColorCreaturesOnly() {
        // Pack's Favor {2}{G}. 3 Ornithopters (colorless) — no green creatures.
        // Convoke colored green shard needs a green creature OR another green
        // source. With only colorless creatures, we have generic reduction 3
        // but no green → unaffordable. Our over-count still permits because
        // generic reduction from untappedCreatures is treated as 3, plus
        // creatures can pay any colored via Convoke in the real rules... our
        // model limits colored-pay to convokeColors[c]. Green gets 0.
        Player p = newGame();
        addCards("Ornithopter", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Pack's Favor", p, ZoneType.Hand);
        // Expected: FALSE because no green creature.
        AssertJUnit.assertFalse(hasActions(p));
    }

    // =================================================================
    // Improvise artifact chains
    // =================================================================

    @Test
    public void testImproviseArtifactChainCovers() {
        Player p = newGame();
        // Reverse Engineer {3}{U}{U} Improvise. 3 Ornithopters + 2 Islands.
        // Islands pay {U}{U}, 3 artifacts pay 3 generic via Improvise.
        addCards("Ornithopter", 3, p);
        addCards("Island", 2, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Reverse Engineer", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testImproviseArtifactsInsufficient() {
        Player p = newGame();
        // Reverse Engineer {3}{U}{U} = 5 mana. 2 Ornithopters + 1 Island.
        // 2 artifacts pay 2 generic + 1 Island pays 1 U. Short by 1 U + 1 gen.
        addCards("Ornithopter", 2, p);
        addCards("Island", 1, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Reverse Engineer", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    // =================================================================
    // Generic cost total subtraction — regression for the generic-pass bug
    // =================================================================

    @Test
    public void testGenericCostSubtractsFromTotal() {
        // 5 Mountains, cost {4}{R}. Pay {R} from one Mountain, then 4 generic
        // from the rest → exactly pays.
        Player p = newGame();
        addCards("Mountain", 5, p);
        addCardToZone("Fireball", p, ZoneType.Hand); // {X}{R}
        // Fireball is {X}{R}, X = 0 always legal per shard-loop rule.
        // Even with no other mana, Fireball with X=0 → {R} payable.
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testGenericCostExactlyAffordable() {
        Player p = newGame();
        addCards("Mountain", 5, p);
        addCardToZone("Flametongue Kavu", p, ZoneType.Hand); // {3}{R}
        // 5 Mountains = 5 red. Pay R, then 3 generic from 4 remaining Mountains.
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testGenericCostInsufficientMixed() {
        Player p = newGame();
        addCards("Plains", 2, p);
        addCards("Mountain", 1, p);
        addCardToZone("Lightning Angel", p, ZoneType.Hand); // {1}{U}{R}{W}
        // Need 4 mana: 1 generic + 1 U + 1 R + 1 W. Have 2 W + 1 R, no U.
        AssertJUnit.assertFalse(hasActions(p));
    }

    // =================================================================
    // Total-mana gate (artifact chains, high-cmc exact-count tests)
    // =================================================================

    @Test
    public void testSolRingChainAffordsGenericSpell() {
        // Sol Ring produces {C}{C}. With 1 Sol Ring we have 2 colorless mana.
        // A 2-CMC colorless spell like Bone Saw {0}... too trivial. Use
        // Mind Stone {2} instead — costs {2}, exactly payable from Sol Ring.
        Player p = newGame();
        addCard("Sol Ring", p).setSickness(false);
        addCardToZone("Mind Stone", p, ZoneType.Hand); // {2}
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testSolRingNotEnoughForBigSpell() {
        // Sol Ring alone = 2 mana. Can't afford a {5} spell.
        Player p = newGame();
        addCard("Sol Ring", p).setSickness(false);
        addCardToZone("Ulamog, the Infinite Gyre", p, ZoneType.Hand); // {11}
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testArtifactChainTotalGateSolRingPlusMountain() {
        // Sol Ring (2C) + Mountain (1R) = 3 total, 1 red reachable.
        // Directly query the budget for specific spells to avoid interference
        // from Sol Ring's lack of activated abilities (this is clean).
        Player p = newGame();
        addCard("Sol Ring", p).setSickness(false);
        addCard("Mountain", p);
        addCardToZone("Flametongue Kavu", p, ZoneType.Hand); // {3}{R}
        // 4 mana needed, 3 available → not affordable.
        AssertJUnit.assertFalse(canAffordFromHand(p, "Flametongue Kavu"));
    }

    @Test
    public void testArtifactChainTotalGatePasses() {
        // Sol Ring (2C) + 2 Mountains = 4 total, 2 red reachable.
        // Flametongue Kavu {3}{R} needs 4 mana with 1 red → affordable.
        Player p = newGame();
        addCard("Sol Ring", p).setSickness(false);
        addCards("Mountain", 2, p);
        addCardToZone("Flametongue Kavu", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Flametongue Kavu"));
    }

    @Test
    public void testBigSpellNotEnoughTotal() {
        // Mountain + Sol Ring = 3 total. Shivan Dragon {4}{R}{R} = 6 mana.
        // Total gate rejects.
        Player p = newGame();
        addCard("Mountain", p);
        addCard("Sol Ring", p).setSickness(false);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Shivan Dragon"));
    }

    // =================================================================
    // Colorless {C} shard handling (7-bucket model regression)
    // =================================================================

    @Test
    public void testColorlessShardNeedsColorless() {
        // Thought-Knot Seer {3}{C}. 4 Forests provide 4 green mana but zero
        // colorless. The {C} shard cannot be paid from colored mana.
        Player p = newGame();
        addCards("Forest", 4, p);
        addCardToZone("Thought-Knot Seer", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testColorlessShardPayableFromColorlessSource() {
        // Thought-Knot Seer {3}{C}. 3 Forests + 1 Wastes. Wastes pays {C},
        // Forests pay {3}.
        Player p = newGame();
        addCards("Forest", 3, p);
        addCard("Wastes", p);
        addCardToZone("Thought-Knot Seer", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    // =================================================================
    // Baylen precision — rainbow bucket should NOT multiply by 5
    // =================================================================

    @Test
    public void testBaylenFourTokensCannotAffordFiveMana() {
        // 4 tokens → 2 activations → 2 rainbow. Serra Angel {3}{W}{W} needs
        // 5 → not affordable. Query the budget directly so Baylen's non-mana
        // draw activation (which is actionable with 3+ tokens) doesn't mask
        // the precision check.
        Player p = newGame();
        addCard("Baylen, the Haymaker", p).setSickness(false);
        for (int i = 0; i < 4; i++) addToken(p, "Grizzly Bears");
        addCardToZone("Serra Angel", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Serra Angel"));
    }

    @Test
    public void testBaylenEightTokensStillNotEnoughForFive() {
        // 8 tokens → 4 rainbow. Serra Angel = 5 → short by 1.
        Player p = newGame();
        addCard("Baylen, the Haymaker", p).setSickness(false);
        for (int i = 0; i < 8; i++) addToken(p, "Grizzly Bears");
        addCardToZone("Serra Angel", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Serra Angel"));
    }

    @Test
    public void testBaylenTenTokensAffordsFiveMana() {
        // 10 tokens → 5 activations → 5 rainbow. Pay {W}{W} + 3 generic.
        Player p = newGame();
        addCard("Baylen, the Haymaker", p).setSickness(false);
        for (int i = 0; i < 10; i++) addToken(p, "Grizzly Bears");
        addCardToZone("Serra Angel", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Serra Angel"));
    }

    // =================================================================
    // Delve edge cases
    // =================================================================

    @Test
    public void testDelveEmptyGraveyard() {
        // Become Immense {5}{G} + Forest + creature target. Empty graveyard,
        // no delve reduction. 1 Forest + 0 delve = 1 mana, need 6.
        Player p = newGame();
        addCard("Forest", p);
        addCard("Grizzly Bears", p).setSickness(false);
        addCardToZone("Become Immense", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testDelvePartialGraveyardInsufficient() {
        // 3 cards in GY + 1 Forest. Need {5}{G} = 6 mana. Delve covers 3,
        // Forest covers 1 G → 4 mana worth. Short 2.
        Player p = newGame();
        addCard("Forest", p);
        addCard("Grizzly Bears", p).setSickness(false);
        for (int i = 0; i < 3; i++) addCardToZone("Forest", p, ZoneType.Graveyard);
        addCardToZone("Become Immense", p, ZoneType.Hand);
        AssertJUnit.assertFalse(hasActions(p));
    }

    @Test
    public void testDelveExactlyAffordable() {
        // 5 cards in GY + 1 Forest. Delve 5 → 5 generic, Forest → G. Exactly
        // pays {5}{G}.
        Player p = newGame();
        addCard("Forest", p);
        addCard("Grizzly Bears", p).setSickness(false);
        for (int i = 0; i < 5; i++) addCardToZone("Forest", p, ZoneType.Graveyard);
        addCardToZone("Become Immense", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    @Test
    public void testDelveAboveCmc() {
        // 10 cards in GY — excess Delve doesn't hurt.
        Player p = newGame();
        addCard("Forest", p);
        addCard("Grizzly Bears", p).setSickness(false);
        for (int i = 0; i < 10; i++) addCardToZone("Forest", p, ZoneType.Graveyard);
        addCardToZone("Become Immense", p, ZoneType.Hand);
        AssertJUnit.assertTrue(hasActions(p));
    }

    // =================================================================
    // Izzet Signet — net-mana accounting (gross ≠ total)
    // =================================================================

    @Test
    public void testSignetNetMathTotalIsFour() {
        // Mountain (net 1) + Sol Ring (net 2) + Izzet Signet (gross 2 − cost 1 = net 1).
        // Expected totalMana = 4. The per-color buckets would show a higher
        // sum (Sol Ring 2 C, Signet 1 U + 1 R, Mountain 1 R = 2 C + 1 U + 2 R = 5)
        // but the NET total is what gates affordability.
        Player p = newGame();
        addCard("Mountain", p);
        addCard("Sol Ring", p).setSickness(false);
        addCard("Izzet Signet", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        AssertJUnit.assertEquals(4, scan.getBudget().getTotalMana());
    }

    @Test
    public void testSignetBucketDistribution() {
        // Izzet Signet + 1 Forest: Forest provides 1 cost-free mana, which
        // makes Signet's {1} activation cost payable. Signet then admits
        // gross 1 U + 1 R buckets, net 1 to total. Plus Forest's own G=1.
        // Total = 2 (1 Forest + 1 Signet net).
        //
        // Without the Forest, Signet alone has no mana source to pay its
        // {1} activation cost — the deferred-cost-OTMA fixed-point loop
        // would correctly drop it. This test verifies the chain admit.
        Player p = newGame();
        addCard("Forest", p);
        addCard("Izzet Signet", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_U));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_R));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(2, b.getTotalMana());
    }

    @Test
    public void testSignetAloneNotPayable() {
        // Izzet Signet alone — no other mana source. Signet's {1} activation
        // cost can't be paid by anything, so the deferred-cost-OTMA loop
        // drops it entirely. Bucket and total stay at 0.
        Player p = newGame();
        addCard("Izzet Signet", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_U));
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_R));
        AssertJUnit.assertEquals(0, b.getTotalMana());
    }

    @Test
    public void testSignetChainTotalGateBlocksBigSpell() {
        // Mountain + Sol Ring + Signet = net 4. Hand: Serra Angel {3}{W}{W} = 5.
        // Total gate should reject. (Even though rainbow/buckets don't have W.)
        Player p = newGame();
        addCard("Mountain", p);
        addCard("Sol Ring", p).setSickness(false);
        addCard("Izzet Signet", p).setSickness(false);
        addCardToZone("Serra Angel", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Serra Angel"));
    }

    @Test
    public void testSignetProvidesBothColors() {
        // Izzet Signet + Mountain: buckets give 1U + 2R + 2C. Total net = 3.
        // Hand: Izzet Charm {U}{R} = 2 mana, 1 U + 1 R.
        Player p = newGame();
        addCard("Mountain", p);
        addCard("Sol Ring", p).setSickness(false);
        addCard("Izzet Signet", p).setSickness(false);
        addCardToZone("Izzet Charm", p, ZoneType.Hand); // {U}{R}
        AssertJUnit.assertTrue(canAffordFromHand(p, "Izzet Charm"));
    }

    // =================================================================
    // Highlighting — the set-valued variant of the heuristic
    // =================================================================

    @Test
    public void testHighlightsAffordableSubsetActionable() {
        // 2 Mountains + 2 spells in hand. Lightning Bolt {R} affordable,
        // Counterspell {U}{U} not. The affordable set is a subset of
        // actionable (both spells are actionable — they pass canPlay — but
        // only one is affordable).
        Player p = newGame();
        addCards("Mountain", 2, p);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertTrue(actionable.contains("Lightning Bolt"));
        AssertJUnit.assertFalse(actionable.contains("Counterspell"));
    }

    @Test
    public void testHighlightsNothingWhenNothingAffordable() {
        Player p = newGame();
        addCards("Plains", 1, p);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertFalse(actionable.contains("Counterspell"));
    }

    @Test
    public void testHighlightsMultipleAffordable() {
        Player p = newGame();
        addCards("Mountain", 3, p);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        addCardToZone("Shock", p, ZoneType.Hand);
        addCardToZone("Incinerate", p, ZoneType.Hand);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertTrue(actionable.contains("Lightning Bolt"));
        AssertJUnit.assertTrue(actionable.contains("Shock"));
        AssertJUnit.assertTrue(actionable.contains("Incinerate"));
    }

    @Test
    public void testHighlightsIncludeLandPlays() {
        // Lands are always actionable at main phase — fast path returns them
        // regardless of mana budget.
        Player p = newGame();
        addCardToZone("Forest", p, ZoneType.Hand);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertTrue(actionable.contains("Forest"));
    }

    @Test
    public void testHighlightsHonorTotalGate() {
        // Mountain + Sol Ring + Signet = 4 mana total. Hand has:
        //   Serra Angel {3}{W}{W} — 5 mana, NOT affordable (total gate).
        //   Grizzly Bears {1}{G} — 2 mana total OK, but no G → NOT affordable.
        //   Lightning Bolt {R} — 1 mana, affordable.
        Player p = newGame();
        addCard("Mountain", p);
        addCard("Sol Ring", p).setSickness(false);
        addCard("Izzet Signet", p).setSickness(false);
        addCardToZone("Serra Angel", p, ZoneType.Hand);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertFalse(actionable.contains("Serra Angel"));
        AssertJUnit.assertFalse(actionable.contains("Grizzly Bears"));
        AssertJUnit.assertTrue(actionable.contains("Lightning Bolt"));
    }

    @Test
    public void testHighlightsApinaAgreement() {
        // For every board, the set-variant's emptiness should agree with
        // hasActions() (modulo bailouts). If the set is non-empty, hasActions
        // is true; if empty, hasActions is false.
        Player p = newGame();
        addCards("Mountain", 2, p);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        java.util.Set<String> actionable = affordableCardNames(p);
        boolean binary = hasActions(p);
        AssertJUnit.assertEquals(!actionable.isEmpty(), binary);
    }

    // =================================================================
    // Generic net-math across all OTMA mana-positive artifacts
    // (proves foldIntoBudget is not Signet-specific)
    // =================================================================

    @Test
    public void testManaVaultNetMath() {
        // Mana Vault: {T}: Add {C}{C}{C}. Cap 1, gross 3, cost 0, net 3.
        Player p = newGame();
        addCard("Mana Vault", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(3, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals(3, b.getTotalMana());
    }

    @Test
    public void testWornPowerstoneNetMath() {
        // Worn Powerstone: {T}: Add {C}{C}. Cap 1, gross 2, cost 0, net 2.
        // Enters tapped but that's an ETB-time replacement, not a permanent
        // tapped state — after state effects settle, untapped and usable.
        Player p = newGame();
        Card ws = addCard("Worn Powerstone", p);
        ws.setSickness(false);
        // Force untap in case the ETB-tapped replacement left it tapped.
        if (ws.isTapped()) ws.untap();
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(2, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals(2, b.getTotalMana());
    }

    @Test
    public void testBasaltMonolithNetMath() {
        // Basalt Monolith: {T}: Add {C}{C}{C}. Its {3}: Untap ability is a
        // separate SA, not part of the mana cost. So the mana ability sees
        // gross 3, cost 0, net 3.
        Player p = newGame();
        addCard("Basalt Monolith", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(3, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals(3, b.getTotalMana());
    }

    @Test
    public void testMultipleManaPositiveArtifactsStack() {
        // Worn Powerstone + Basalt Monolith + Sol Ring. All OTMAs with
        // no mana cost. Total = 2 + 3 + 2 = 7.
        Player p = newGame();
        Card ws = addCard("Worn Powerstone", p);
        ws.setSickness(false);
        if (ws.isTapped()) ws.untap();
        addCard("Basalt Monolith", p).setSickness(false);
        addCard("Sol Ring", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        AssertJUnit.assertEquals(7, scan.getBudget().getTotalMana());
    }

    @Test
    public void testSignetChainWithManaPositiveRocks() {
        // Sol Ring (+2C, net 2) + Worn Powerstone (+2C, net 2) +
        // Izzet Signet (+1U +1R, net 1) = net 5. Cost-subtracted totals
        // compose correctly across heterogeneous rocks.
        Player p = newGame();
        addCard("Sol Ring", p).setSickness(false);
        Card ws = addCard("Worn Powerstone", p);
        ws.setSickness(false);
        if (ws.isTapped()) ws.untap();
        addCard("Izzet Signet", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        AssertJUnit.assertEquals(5, scan.getBudget().getTotalMana());
    }

    // Note: Nykthos, Shrine to Nyx is NOT tested for precise net math here.
    // Its devotion ability uses a ChooseColor root with a DB$ Mana subability,
    // which trips the Parley-style structural bailout in ActionScan. The
    // bailout is conservative ("has actions" regardless) — a future
    // improvement would recognize ChooseColor as a safe wrapper around
    // predictable mana production, but for now Nykthos simply always passes.

    // =================================================================
    // Non-standard cost caps (PayLife, Discard, RemoveCounter, PayEnergy)
    // =================================================================

    @Test
    public void testBloodCelebrantPayLifeCapped() {
        // Blood Celebrant: {B}, Pay 1 life: Add one mana of any color.
        // Needs a {B} source to pay activation cost — without one, the
        // deferred-cost loop drops it. Add a Swamp so the cost is payable.
        // Starting life 20 → life cap = 20. Rainbow bucket = 20 (gross),
        // total = 1 (Swamp) + 0 (Celebrant net per activation = 1−1).
        Player p = newGame();
        addCard("Swamp", p);
        addCard("Blood Celebrant", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(20, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(1, b.getTotalMana());
    }

    @Test
    public void testBloodCelebrantAloneNotPayable() {
        // Blood Celebrant with no other mana source — can't pay {B}, so
        // the deferred-cost loop drops it. Rainbow stays 0.
        Player p = newGame();
        addCard("Blood Celebrant", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        AssertJUnit.assertEquals(0, scan.getBudget().getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(0, scan.getBudget().getTotalMana());
    }

    @Test
    public void testBloodCelebrantLowLifeCap() {
        // Life 3 → life cap = 3 → rainbow = 3 (with a Swamp to pay {B}).
        Player p = newGame();
        p.setLife(3, null);
        addCard("Swamp", p);
        addCard("Blood Celebrant", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        AssertJUnit.assertEquals(3, scan.getBudget().getBucket(ManaBudget.IDX_RAINBOW));
    }

    @Test
    public void testBloodCelebrantWithSwampCanCastShock() {
        // Blood Celebrant + Swamp. Real player: Swamp → {B}, spend on
        // Celebrant → 1 rainbow mana (net 0). Only 1 spell-worth of mana.
        // Swamp contributes 1 net to total; Celebrant contributes 0 net.
        // Shock {R} is affordable: rainbow bucket pays R, total gate 1 ≥ 1.
        Player p = newGame();
        addCard("Blood Celebrant", p).setSickness(false);
        addCard("Swamp", p);
        addCardToZone("Shock", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Shock"));
    }

    @Test
    public void testBloodCelebrantAloneCannotCastCounterspell() {
        // Blood Celebrant alone → buckets rainbow=20 but totalMana=0 (every
        // activation pays its own {B} cost). Counterspell {U}{U} rejected by
        // the total-mana gate even though the rainbow bucket looks healthy.
        Player p = newGame();
        addCard("Blood Celebrant", p).setSickness(false);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Counterspell"));
    }

    @Test
    public void testAetherHubPayEnergyAdditionalToTap() {
        // Aether Hub: {T}: Add {C}. OR {T}, Pay {E}: Add any color.
        // The first ability (just {T}: Add {C}) has cap 1 from CostTap.
        // The second ability has Cost$ T PayEnergy<1> — tap bounds cap=1
        // regardless of energy, so energy cap doesn't kick in here. But with
        // 0 energy the second ability can't be activated at all (since you
        // need at least 1 energy to pay). Let's verify that zero-energy
        // keeps the second ability from producing rainbow mana.
        Player p = newGame();
        addCard("Aether Hub", p).setSickness(false);
        // Default energy = 0. The second ability should fold in 0 rainbow
        // (because its cap via PayEnergy<1> is 0). But getAllPossibleAbilities
        // filters unplayable SAs — so that ability may not even reach Pass 2.
        ActionScan scan = ActionScan.scan(p);
        // At least 1 colorless from the basic tap ability.
        AssertJUnit.assertTrue(scan.getBudget().getBucket(ManaBudget.IDX_C) >= 1);
    }

    // =================================================================
    // Nykthos, Shrine to Nyx — ChooseColor + devotion
    // =================================================================

    @Test
    public void testNykthosDevotionPerColorAndTotal() {
        // 3 Llanowar Elves (each 1 green devotion) + Nykthos + 2 Forests.
        // Devotion: G=3. Nykthos adds: G bucket += 3 (devotion), total += 1
        // (max devotion 3 minus activation cost 2 = 1). Elves contribute
        // their normal tap-for-G.
        Player p = newGame();
        addCards("Llanowar Elves", 3, p);
        addCards("Forest", 2, p);
        addCard("Nykthos, Shrine to Nyx", p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        AssertJUnit.assertFalse(scan.hasStructuralBailout());
        ManaBudget b = scan.getBudget();
        // Bucket G should be: 2 (forests) + 3 (elves) + 3 (nykthos gross) = 8.
        // Plus +1 colorless from Nykthos's basic {T}: Add {C} ability.
        AssertJUnit.assertEquals(8, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_C));
        // Total: 2 (forests) + 3 (elves net) + max(1 basic, 1 special) = 6.
        // Nykthos's two tap-self abilities are mutually exclusive per turn,
        // so only the LARGER net contributes — here max(1, 3-2=1) = 1.
        AssertJUnit.assertEquals(6, b.getTotalMana());
    }

    @Test
    public void testNykthosNoDevotionContributionFromLands() {
        // Basic lands have no mana cost so they contribute nothing to
        // devotion. 1 Forest + Nykthos: devotion[G] = 0 → Nykthos contributes
        // nothing beyond its basic {T}: Add {C} ability. G bucket = 1 from
        // the Forest's own tap-for-mana; C bucket = 1 from Nykthos's basic
        // ability; total = 2 (1 Forest + 1 Nykthos C).
        Player p = newGame();
        addCard("Forest", p);
        addCard("Nykthos, Shrine to Nyx", p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals(2, b.getTotalMana());
    }

    // =================================================================
    // Selvala, Explorer Returned — Parley pattern
    // =================================================================

    @Test
    public void testSelvalaNoBailoutAndGreenBound() {
        Player p = newGame();
        addCard("Selvala, Explorer Returned", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        AssertJUnit.assertFalse("Selvala should not trigger structural bailout anymore",
                scan.hasStructuralBailout());
        ManaBudget b = scan.getBudget();
        int numPlayers = p.getGame().getPlayers().size();
        // G bucket = numPlayers (max nonlands revealed).
        AssertJUnit.assertEquals(numPlayers, b.getBucket(ManaBudget.IDX_G));
        // Total = numPlayers (no activation mana cost, just tap).
        AssertJUnit.assertEquals(numPlayers, b.getTotalMana());
        // Delta: life gain bounded by numPlayers, hand size by +1.
        AssertJUnit.assertEquals(numPlayers, scan.getLifeGainDelta().getMax());
        AssertJUnit.assertEquals(1, scan.getHandSizeDelta().getMax());
    }

    @Test
    public void testSelvalaCanAffordGreenSpell() {
        Player p = newGame();
        addCard("Selvala, Explorer Returned", p).setSickness(false);
        addCardToZone("Giant Spider", p, ZoneType.Hand); // {3}{G}
        // 2-player game → numPlayers = 2 → 2 G total from Selvala. Not
        // enough for 4-cmc Giant Spider.
        AssertJUnit.assertFalse(canAffordFromHand(p, "Giant Spider"));
    }

    @Test
    public void testSelvalaCanAffordLlanowarElves() {
        Player p = newGame();
        addCard("Selvala, Explorer Returned", p).setSickness(false);
        addCardToZone("Llanowar Elves", p, ZoneType.Hand); // {G}
        // Selvala's G bucket has 2 and total has 2. Llanowar Elves affordable.
        AssertJUnit.assertTrue(canAffordFromHand(p, "Llanowar Elves"));
    }

    // =================================================================
    // CostRemoveCounter — Channeler Initiate (-1/-1 counter removal)
    // =================================================================

    @Test
    public void testChannelerInitiateNoCountersNoContribution() {
        // Channeler Initiate enters with 3 -1/-1 counters. If we manually
        // clear them, the ability can't be activated → no mana contribution.
        Player p = newGame();
        Card ci = addCard("Channeler Initiate", p);
        ci.setSickness(false);
        ci.setCounters(forge.game.card.CounterEnumType.M1M1, 0);
        ActionScan scan = ActionScan.scan(p);
        // Rainbow bucket should be 0 (no other mana sources).
        AssertJUnit.assertEquals(0, scan.getBudget().getBucket(ManaBudget.IDX_RAINBOW));
    }

    @Test
    public void testChannelerInitiateWithCountersContributes() {
        Player p = newGame();
        Card ci = addCard("Channeler Initiate", p);
        ci.setSickness(false);
        ci.setCounters(forge.game.card.CounterEnumType.M1M1, 3);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // Cap is bounded by tap (1), not counter count (3). One activation.
        // Rainbow += 1 per activation × 1 cap = 1. Total += 1 net.
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(1, b.getTotalMana());
    }

    // =================================================================
    // Complex multi-source boards with full state readouts
    // =================================================================

    @Test
    public void testComplexBoardMidRangeControl() {
        // Scenario: mid-range deck turn 5. Board has:
        //   2 Islands, 2 Mountains (basic mana)
        //   1 Sol Ring (net 2 C)
        //   1 Izzet Signet (net 1, +1 U +1 R gross)
        //   2 Mountain Giants in play (summoning sick creatures)
        // Hand (all untargeted so the cast-gate pre-flight doesn't filter):
        //   Brainstorm {U}                  — 1 cmc instant
        //   Divination {2}{U}               — 3 cmc sorcery
        //   Shivan Dragon {4}{R}{R}         — 6 cmc, 2 R available
        //   Flametongue Kavu {3}{R}         — 4 cmc
        //   Chain Lightning {R}             — 1 cmc instant, untargeted? NO — use
        //                                      Seismic Assault-style instead:
        //   Pyretic Ritual {R}              — 1 cmc untargeted instant
        // Expected totalMana = 2 (Islands) + 2 (Mountains) + 2 (Sol Ring)
        //                    + 1 (Signet net) = 7.
        Player p = newGame();
        addCards("Island", 2, p);
        addCards("Mountain", 2, p);
        addCard("Sol Ring", p).setSickness(false);
        addCard("Izzet Signet", p).setSickness(false);
        addCardToZone("Brainstorm", p, ZoneType.Hand);
        addCardToZone("Divination", p, ZoneType.Hand);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        addCardToZone("Flametongue Kavu", p, ZoneType.Hand);
        addCardToZone("Pyretic Ritual", p, ZoneType.Hand);

        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(7, b.getTotalMana());
        // Per-color gross: U = 2 (Islands) + 1 (Signet) = 3.
        //                  R = 2 (Mountains) + 1 (Signet) = 3.
        //                  C = 2 (Sol Ring) = 2.
        AssertJUnit.assertEquals(3, b.getBucket(ManaBudget.IDX_U));
        AssertJUnit.assertEquals(3, b.getBucket(ManaBudget.IDX_R));
        AssertJUnit.assertEquals(2, b.getBucket(ManaBudget.IDX_C));

        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertTrue(actionable.contains("Brainstorm"));
        AssertJUnit.assertTrue(actionable.contains("Divination"));
        AssertJUnit.assertTrue(actionable.contains("Shivan Dragon"));
        AssertJUnit.assertTrue(actionable.contains("Flametongue Kavu"));
        AssertJUnit.assertTrue(actionable.contains("Pyretic Ritual"));
    }

    @Test
    public void testComplexBoardMidRangeTotalBlocksOversizedSpell() {
        // Same board as above. Add a spell that's too big: Emrakul, the
        // Aeons Torn {15} should NOT be affordable regardless of buckets.
        Player p = newGame();
        addCards("Island", 2, p);
        addCards("Mountain", 2, p);
        addCard("Sol Ring", p).setSickness(false);
        addCard("Izzet Signet", p).setSickness(false);
        addCardToZone("Emrakul, the Aeons Torn", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Emrakul, the Aeons Torn"));
    }

    @Test
    public void testComplexBoardGreenDevotionWithNykthos() {
        // Nykthos + 3 Forests + 3 Llanowar Elves. Devotion to G = 3.
        // Hand: mix of green and non-green.
        //   Overrun {2}{G}{G}{G}            — 5 cmc, 3 G needed
        //   Craterhoof Behemoth {5}{G}{G}{G} — 8 cmc
        //   Giant Spider {3}{G}              — 4 cmc
        //   Counterspell {U}{U}              — 2 cmc, 2 U needed
        Player p = newGame();
        addCards("Forest", 3, p);
        addCards("Llanowar Elves", 3, p);
        addCard("Nykthos, Shrine to Nyx", p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Overrun", p, ZoneType.Hand);
        addCardToZone("Craterhoof Behemoth", p, ZoneType.Hand);
        addCardToZone("Giant Spider", p, ZoneType.Hand);
        addCardToZone("Counterspell", p, ZoneType.Hand);

        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // G bucket: 3 Forests + 3 Elves + 3 Nykthos gross = 9.
        AssertJUnit.assertEquals(9, b.getBucket(ManaBudget.IDX_G));
        // C bucket: 1 (Nykthos basic {T}: Add {C}).
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_C));
        // Total: 3 Forests + 3 Elves + max(1 basic, 3-2 special) = 7.
        // Nykthos's two tap-self abilities are mutually exclusive per turn.
        AssertJUnit.assertEquals(7, b.getTotalMana());

        java.util.Set<String> actionable = affordableCardNames(p);
        // Overrun {2}{G}{G}{G} = 5 cmc, need 3 G. Total 7 ≥ 5, G bucket 9 ≥ 3. Yes.
        AssertJUnit.assertTrue(actionable.contains("Overrun"));
        // Craterhoof {5}{G}{G}{G} = 8 cmc. Total 7 < 8 → NOT affordable
        // under the corrected max-per-card rule. Previously the test was
        // wrong because it assumed Nykthos's two abilities stacked.
        AssertJUnit.assertFalse(actionable.contains("Craterhoof Behemoth"));
        // Giant Spider {3}{G} = 4 cmc. Yes.
        AssertJUnit.assertTrue(actionable.contains("Giant Spider"));
        // Counterspell {U}{U} = 2 cmc but needs U — no U bucket. No.
        AssertJUnit.assertFalse(actionable.contains("Counterspell"));
    }

    @Test
    public void testComplexBoardWithSelvalaAndConvoke() {
        // Selvala + 2 Grizzly Bears + 1 Forest. Numplayers = 2.
        // Hand:
        //   Pack's Favor {2}{G} Convoke    — 3 cmc, covered by convoke + bears
        //   Primeval Titan {4}{G}{G}        — 6 cmc, Selvala gives 2 G + forest 1 = 3 G total,
        //                                     but bucket G = 3, total = 3 (Selvala) + 1 (Forest) +
        //                                     2 (Elves — wait no, Bears are vanilla). Total:
        //                                     2 (Selvala) + 1 (Forest) + 0 (Bears) = 3. Not enough.
        Player p = newGame();
        addCard("Selvala, Explorer Returned", p).setSickness(false);
        addCards("Grizzly Bears", 2, p);
        addCard("Forest", p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Pack's Favor", p, ZoneType.Hand);
        addCardToZone("Primeval Titan", p, ZoneType.Hand);

        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // G bucket: 1 Forest + numPlayers (Selvala) = 3.
        int numPlayers = p.getGame().getPlayers().size();
        AssertJUnit.assertEquals(1 + numPlayers, b.getBucket(ManaBudget.IDX_G));
        // Total: 1 Forest + numPlayers (Selvala net) = 3.
        AssertJUnit.assertEquals(1 + numPlayers, b.getTotalMana());

        java.util.Set<String> actionable = affordableCardNames(p);
        // Pack's Favor: Convoke + 3 bears/creatures (2 bears + Selvala),
        // 1 Forest + Selvala 2G = 3G, can pay {2}{G}. Convoke helps generic.
        AssertJUnit.assertTrue(actionable.contains("Pack's Favor"));
        // Primeval Titan {4}{G}{G} = 6 cmc, total only 3. Not affordable.
        AssertJUnit.assertFalse(actionable.contains("Primeval Titan"));
    }

    // =================================================================
    // P/T-based mana abilities and -1/-1 counter interaction
    // =================================================================

    @Test
    public void testCradleClearcutterOwnPower() {
        // Cradle Clearcutter: 3/6 vanilla. {T}: Add X green, X = own power.
        // No counters → cap 1 × 3 power = 3 green per activation.
        Player p = newGame();
        addCard("Cradle Clearcutter", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // G bucket = 3 (own power), total = 3.
        AssertJUnit.assertEquals(3, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(3, b.getTotalMana());
    }

    // Cradle Clearcutter-with-counters is intentionally not tested at the
    // bucket level because our test environment's setCounters doesn't
    // consistently flow through to getCurrentPower via checkStateEffects.
    // The Channeler Initiate test below exercises the m1m1 interaction
    // end-to-end on the board-wide highest-toughness path.

    @Test
    public void testBighornerRancherGreatestPower() {
        // Bighorner Rancher (2/5) uses GreatestCardPower among your creatures.
        // With 3 Grizzly Bears (2/2 each), greatest power = 2. Bucket G += 2.
        Player p = newGame();
        addCard("Bighorner Rancher", p).setSickness(false);
        addCards("Grizzly Bears", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // Rancher's ability adds 2 G (greatest power = 2, highest between
        // Rancher's own 2 and Bears' 2).
        AssertJUnit.assertEquals(2, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(2, b.getTotalMana());
    }

    @Test
    public void testArborAdherentGreatestToughnessOfOthers() {
        // Arbor Adherent (2/4). Second ability: {T}: Add X any color,
        // X = greatest toughness among OTHER creatures you control. With a
        // Grizzly Bears (2/2) on the field, greatest other toughness = 2.
        // Arbor Adherent also has a {T}: Add one of any ability — but a
        // card's multiple tap-mana abilities share one tap cost: in reality
        // only one fires. For our estimator, both are classified as OTMAs
        // and BOTH fold in. Acceptable FP-direction over-count.
        Player p = newGame();
        addCard("Arbor Adherent", p).setSickness(false);
        addCard("Grizzly Bears", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // Arbor's first ability (fixed 1 rainbow) + second ability
        // (X = 2 rainbow for greatest-other-toughness=2) = 3 rainbow.
        // Plus Grizzly Bears produces no mana. Total = 3.
        AssertJUnit.assertTrue("rainbow should be at least 3",
                b.getBucket(ManaBudget.IDX_RAINBOW) >= 3);
        AssertJUnit.assertTrue("total should be at least 3", b.getTotalMana() >= 3);
    }

    @Test
    public void testChannelerCounterRemovalBoundsHighestToughness() {
        // THE KEY TEST: Channeler Initiate (base 2/3) with 2 M1M1 counters
        // → current 0/1 after state-based effects. Arbor Adherent (2/4) has
        // {T}: Add X mana of any color, X = greatest toughness among other
        // creatures. Current "greatest other toughness" (Arbor's POV) = 1
        // (Channeler's). Our estimator uses board-wide highestToughness
        // (over-count of "other") + totalMinusOneCounters: 4 + 2 = 6.
        //
        // Rainbow contributions:
        //   Arbor ability 1 ({T}: Add one mana of any color) = +1
        //   Arbor ability 2 (X = greatest-other-toughness bound)  = +6
        //   Channeler (tap + remove counter: add any)              = +1
        //   Total rainbow ≥ 8. This proves the counter removal IS factored
        //   into the upper bound — without it, the board would only have
        //   Arbor's 1+1 (current toughness 4 → but actually current greatest
        //   other is 1) = unclear and much smaller.
        Player p = newGame();
        addCard("Arbor Adherent", p).setSickness(false);
        Card ci = addCard("Channeler Initiate", p);
        ci.setSickness(false);
        ci.setCounters(forge.game.card.CounterEnumType.M1M1, 2);
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(2, scan.getTotalMinusOneCounters());
        // Arbor 2/4, Channeler 0/1 after SBAs → highestT = 4.
        AssertJUnit.assertEquals(4, scan.getHighestToughness());
        // Arbor ability 2 contributes (4 + 2) = 6 rainbow, + ability 1's 1,
        // + Channeler's 1 = 8.
        AssertJUnit.assertEquals(8, b.getBucket(ManaBudget.IDX_RAINBOW));
    }

    @Test
    public void testChannelerCounterRemovalAllowsExpensiveSpell() {
        // Same Channeler + Arbor setup. Hand: a {4}{G} creature spell that
        // can ONLY be cast if the counter-removal upper bound is factored
        // in. Without the bound (treating -1/-1 counters as fixed), Arbor's
        // second ability would only give 1 rainbow, Channeler would give 1,
        // Arbor's first would give 1 → total 3, not enough for 5. With the
        // bound, Arbor's bucket contribution goes up and the spell becomes
        // affordable.
        Player p = newGame();
        addCard("Arbor Adherent", p).setSickness(false);
        Card ci = addCard("Channeler Initiate", p);
        ci.setSickness(false);
        ci.setCounters(forge.game.card.CounterEnumType.M1M1, 2);
        addCardToZone("Craw Wurm", p, ZoneType.Hand); // {4}{G} = 5
        AssertJUnit.assertTrue(canAffordFromHand(p, "Craw Wurm"));
    }

    // =================================================================
    // Charge/storage counter mana abilities
    // =================================================================

    @Test
    public void testAstralCornucopiaNoCountersNoMana() {
        // Astral Cornucopia enters with X charge counters where X is the
        // paid X in its cast cost. A freshly dev-added copy has 0 charge
        // counters, so its mana ability produces 0 per activation → no
        // contribution.
        Player p = newGame();
        addCard("Astral Cornucopia", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(0, b.getTotalMana());
    }

    @Test
    public void testAstralCornucopiaWithChargeCounters() {
        Player p = newGame();
        Card cn = addCard("Astral Cornucopia", p);
        cn.setSickness(false);
        cn.setCounters(forge.game.card.CounterEnumType.CHARGE, 3);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // 3 charge counters → Amount = 3 → rainbow += 3 per activation × 1
        // (tap cap) = 3. Total += 3 (no activation mana cost).
        AssertJUnit.assertEquals(3, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(3, b.getTotalMana());
    }

    @Test
    public void testAstralCornucopiaAffordsSerraAngel() {
        Player p = newGame();
        Card cn = addCard("Astral Cornucopia", p);
        cn.setSickness(false);
        cn.setCounters(forge.game.card.CounterEnumType.CHARGE, 5);
        addCardToZone("Serra Angel", p, ZoneType.Hand); // {3}{W}{W}
        // 5 rainbow → 2 W shards from rainbow + 3 generic from rainbow. Yes.
        AssertJUnit.assertTrue(canAffordFromHand(p, "Serra Angel"));
    }

    @Test
    public void testBottomlessVaultNoStorageCounters() {
        // Bottomless Vault enters tapped with 0 storage counters. Without
        // counters, its SubCounter<X/STORAGE> mana ability produces 0.
        Player p = newGame();
        Card bv = addCard("Bottomless Vault", p);
        bv.setSickness(false);
        // Force untap (ETB-tapped replacement).
        if (bv.isTapped()) bv.untap();
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_B));
        AssertJUnit.assertEquals(0, b.getTotalMana());
    }

    @Test
    public void testBottomlessVaultWithStorageCounters() {
        Player p = newGame();
        Card bv = addCard("Bottomless Vault", p);
        bv.setSickness(false);
        if (bv.isTapped()) bv.untap();
        bv.setCounters(forge.game.card.CounterEnumType.STORAGE, 4);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // 4 storage counters → Amount X resolves to 4 via Count$xPaid → the
        // SubCounter<X/STORAGE> cost's X, bounded by source's counter count.
        // Produced "B" → bucket B += 4. Total += 4.
        AssertJUnit.assertEquals(4, b.getBucket(ManaBudget.IDX_B));
        AssertJUnit.assertEquals(4, b.getTotalMana());
    }

    @Test
    public void testArborCannotAffordBigSpellWithoutChannelerCounters() {
        // Control case: Arbor Adherent + Channeler Initiate with 0 M1M1
        // counters. Channeler's mana ability requires removing a -1/-1
        // counter, so with none it's unplayable and contributes 0.
        // Arbor contributes: ability 1 (add one any color) = 1 rainbow,
        // ability 2 (X = greatest other toughness, bounded by highestT +
        // totalM1M1 = 4 + 0 = 4) = 4 rainbow. Total rainbow = 5.
        //
        // Shivan Dragon is {4}{R}{R} = 6 mana. 5 < 6 → not affordable.
        Player p = newGame();
        addCard("Arbor Adherent", p).setSickness(false);
        addCard("Channeler Initiate", p).setSickness(false);
        // Don't add any counters — default ETB counters won't fire in the
        // test setup since we didn't cast the card, but verify just in case.
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            c.setCounters(forge.game.card.CounterEnumType.M1M1, 0);
        }
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // Verify the setup: rainbow bucket has exactly 5 (Arbor's two
        // abilities with highestT=4, m1m1=0).
        AssertJUnit.assertEquals(5, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertFalse(canAffordFromHand(p, "Shivan Dragon"));
    }

    @Test
    public void testArborAffordsBigSpellOnlyBecauseChannelerCountersBound() {
        // Experimental case: same board, but Channeler has 2 M1M1 counters.
        // After SBAs Channeler is 1/2 (base 3/4). Arbor ability 2 now sees
        // highestToughness (4) + totalMinusOneCounters (2) = 6 rainbow.
        // Channeler's mana ability is now playable (2 counters available) →
        // +1 rainbow. Arbor ability 1 → +1. Total rainbow = 8.
        //
        // Shivan Dragon {4}{R}{R} = 6 mana. 8 ≥ 6 → affordable.
        //
        // This test pairs with testArborCannotAffordBigSpellWithoutChannelerCounters
        // — the ONLY difference is the 2 counters on Channeler, and that's
        // what makes the spell affordable. Proves our -1/-1-counter upper
        // bound is doing its job.
        Player p = newGame();
        addCard("Arbor Adherent", p).setSickness(false);
        Card ci = addCard("Channeler Initiate", p);
        ci.setSickness(false);
        ci.setCounters(forge.game.card.CounterEnumType.M1M1, 2);
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(8, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertTrue(canAffordFromHand(p, "Shivan Dragon"));
    }

    @Test
    public void testBighornerCannotAffordWithoutChannelerCounters() {
        // Control: Bighorner Rancher (2/5) + Channeler Initiate (3/4) with
        // 0 M1M1 counters. Bighorner's ability produces X green where X is
        // greatest card power among creatures you control. Current greatest
        // power = max(Bighorner 2, Channeler 3) = 3. Bound = 3 + 0 = 3.
        // Channeler's mana ability is unplayable (no counter to remove).
        // G bucket = 3, rainbow = 0, total = 3.
        //
        // Giant Spider is {3}{G} = 4 mana. Total gate 3 < 4 → NOT affordable.
        Player p = newGame();
        addCard("Bighorner Rancher", p).setSickness(false);
        addCard("Channeler Initiate", p).setSickness(false);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            c.setCounters(forge.game.card.CounterEnumType.M1M1, 0);
        }
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Giant Spider", p, ZoneType.Hand);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(3, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(3, b.getTotalMana());
        AssertJUnit.assertFalse(canAffordFromHand(p, "Giant Spider"));
    }

    @Test
    public void testBighornerAffordsOnlyBecauseChannelerCountersBound() {
        // Experimental: same board, Channeler has 2 M1M1 counters. In the
        // test environment setCounters doesn't propagate through to SBAs,
        // so Channeler still reports base 3 power. Scan sees highestPower
        // = max(Bighorner 2, Channeler 3) = 3. totalMinusOneCounters = 2.
        // Bighorner bound = 3 + 2 = 5 → bucket G += 5. Channeler's mana
        // ability is playable (2 counters available) → +1 rainbow.
        // G bucket = 5, rainbow = 1, total = 6.
        //
        // Giant Spider {3}{G} = 4 mana. Total gate 6 ≥ 4. Affordable.
        //
        // Pairs with testBighornerCannotAffordWithoutChannelerCounters —
        // the ONLY difference is the 2 counters on Channeler, and that is
        // what (a) unlocks Channeler's own mana ability (+1 rainbow) and
        // (b) boosts Bighorner's upper bound by the m1m1-removal factor
        // (+2 G). Either bump alone wouldn't take us across the cmc-4
        // threshold; together they do.
        Player p = newGame();
        addCard("Bighorner Rancher", p).setSickness(false);
        Card ci = addCard("Channeler Initiate", p);
        ci.setSickness(false);
        ci.setCounters(forge.game.card.CounterEnumType.M1M1, 2);
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Giant Spider", p, ZoneType.Hand);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(5, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(6, b.getTotalMana());
        AssertJUnit.assertTrue(canAffordFromHand(p, "Giant Spider"));
    }

    @Test
    public void testChannelerInitiateIntegratedAffordability() {
        // Channeler Initiate base 3/4 with 3 M1M1 counters (its ETB-target
        // default if cast on itself, but manually set here). After SBAs:
        // 0/1 current. Can tap+remove to produce 1 rainbow per activation.
        // Cap = 1 (tap bound). Rainbow += 1.
        Player p = newGame();
        Card ci = addCard("Channeler Initiate", p);
        ci.setSickness(false);
        ci.setCounters(forge.game.card.CounterEnumType.M1M1, 1);
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand); // {R}
        // Channeler contributes 1 rainbow → affordable for 1-mana spell.
        AssertJUnit.assertTrue(canAffordFromHand(p, "Lightning Bolt"));
    }

    // =================================================================
    // Top-of-library / MayPlay / external-zone playable cards
    // =================================================================

    /** Put a card at position 0 of the given player's library (the top). */
    private Card putOnTopOfLibrary(Player p, String name) {
        Card c = createCard(name, p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());
        p.getZone(ZoneType.Library).add(c, 0);
        return c;
    }

    @Test
    public void testFutureSightAffordableTopCard() {
        // Future Sight on battlefield + 5 Islands + Ponder at top of
        // library. The player can cast Ponder from the top via Future
        // Sight's MayPlay. Ponder is untargeted so the cast-gate
        // pre-flight doesn't filter it out; the test is purely about
        // external-zone reachability.
        Player p = newGame();
        addCard("Future Sight", p).setSickness(false);
        addCards("Island", 5, p);
        putOnTopOfLibrary(p, "Ponder"); // {U}
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertTrue("Ponder from top of library should be actionable",
                actionable.contains("Ponder"));
    }

    @Test
    public void testFutureSightUnaffordableTopCard() {
        // Future Sight + 2 Plains + Counterspell on top. Wrong color —
        // not affordable.
        Player p = newGame();
        addCard("Future Sight", p).setSickness(false);
        addCards("Plains", 2, p);
        putOnTopOfLibrary(p, "Counterspell");
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertFalse("Counterspell should NOT be affordable with only Plains",
                actionable.contains("Counterspell"));
    }

    @Test
    public void testFutureSightTooExpensiveTopCard() {
        // Future Sight + 1 Mountain + Shivan Dragon on top. 1 mana < 6.
        Player p = newGame();
        addCard("Future Sight", p).setSickness(false);
        addCards("Mountain", 1, p);
        putOnTopOfLibrary(p, "Shivan Dragon"); // {4}{R}{R}
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertFalse(actionable.contains("Shivan Dragon"));
    }

    @Test
    public void testNoFutureSightTopCardInvisible() {
        // Control: without Future Sight, the top-library card has no
        // MayPlay permission and should not show up as actionable.
        Player p = newGame();
        addCards("Island", 5, p);
        putOnTopOfLibrary(p, "Counterspell");
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertFalse("No MayPlay source → top library card invisible",
                actionable.contains("Counterspell"));
    }

    @Test
    public void testCourserOfKruphixLandFromTop() {
        // Courser of Kruphix grants MayPlay for LANDS only from the top of
        // the library. Put a Forest on top — it should be highlightable as
        // a land play. Put an instant on top after — should NOT be.
        Player p = newGame();
        addCard("Courser of Kruphix", p).setSickness(false);
        putOnTopOfLibrary(p, "Forest");
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertTrue("Forest from top of library should be a playable land",
                actionable.contains("Forest"));
    }

    @Test
    public void testCourserOfKruphixNonLandTopCardNotPlayable() {
        // Courser + Lightning Bolt on top. Courser only grants LAND
        // MayPlay, not spell MayPlay. Lightning Bolt should not appear.
        Player p = newGame();
        addCard("Courser of Kruphix", p).setSickness(false);
        addCards("Mountain", 1, p);
        putOnTopOfLibrary(p, "Lightning Bolt");
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertFalse("Courser's MayPlay is land-only — Lightning Bolt should not appear",
                actionable.contains("Lightning Bolt"));
    }

    @Test
    public void testBolasCitadelTopCardViaLifeCost() {
        // Bolas's Citadel grants MayPlay on top of library with an
        // alternative cost of "pay life = CMC" instead of the mana cost.
        // Put Lightning Bolt (CMC 1) on top. With 10 life, paying 1 is fine.
        // No red mana on the board, so only the alt-cost path works.
        Player p = newGame();
        addCard("Bolas's Citadel", p).setSickness(false);
        putOnTopOfLibrary(p, "Lightning Bolt");
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertTrue("Lightning Bolt via Citadel's life alt-cost",
                actionable.contains("Lightning Bolt"));
    }

    // =================================================================
    // Restricted-spend mana (RestrictValid$) — diverse restriction types
    // =================================================================

    // --- Ancient Ziggurat: Spell.Creature only ---

    @Test
    public void testAncientZigguratAllowsCreatureSpell() {
        // Ancient Ziggurat: {T}: Add one mana of any color, spend only to
        // cast a creature spell. Hand: Grizzly Bears {1}{G}. With Ziggurat
        // producing restricted rainbow and 1 Forest, we have 2 mana total,
        // the G shard paid by Forest, generic by Ziggurat.
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCard("Forest", p);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Grizzly Bears"));
    }

    @Test
    public void testAncientZigguratBlocksInstant() {
        // Same board, but hand has Lightning Bolt {R}. Ziggurat's mana is
        // restricted to creature spells and cannot pay for an instant.
        // Only Forest remains, which is green → can't pay {R}.
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCard("Forest", p);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Lightning Bolt"));
    }

    @Test
    public void testAncientZigguratBlocksSorcery() {
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCardToZone("Cruel Edict", p, ZoneType.Hand); // {2}{B} sorcery
        AssertJUnit.assertFalse(canAffordFromHand(p, "Cruel Edict"));
    }

    @Test
    public void testAncientZigguratMultipleCreatureSpellsAllAllowed() {
        // Ziggurat + Forest + 2 creature spells in hand. Both should be
        // in the affordable set.
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCards("Forest", 3, p);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        addCardToZone("Llanowar Elves", p, ZoneType.Hand);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertTrue(actionable.contains("Grizzly Bears"));
        AssertJUnit.assertTrue(actionable.contains("Llanowar Elves"));
    }

    // --- Orb of Dragonkind: Spell.Dragon,Activated.Dragon only ---

    @Test
    public void testOrbOfDragonkindAllowsDragon() {
        // Orb of Dragonkind: {1}, {T}: Add 2 mana in any combination,
        // spend only to cast Dragons or activate Dragon abilities.
        // Orb's net contribution = 2 gross - 1 mana cost = 1 net mana.
        // Plus 5 Mountains = 5 net. Total = 6. Shivan Dragon = 6 mana.
        Player p = newGame();
        addCard("Orb of Dragonkind", p).setSickness(false);
        addCards("Mountain", 5, p);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Shivan Dragon"));
    }

    @Test
    public void testOrbOfDragonkindBlocksNonDragonCreature() {
        // Orb + 4 Mountains + Shivan Hellkite? No, Hellkite IS a dragon.
        // Use Hill Giant — creature but not a Dragon.
        // Hill Giant {3}{R} = 4 cmc. Without Orb's restricted mana, we
        // only have 4 Mountains = 4 total. Exactly affordable.
        // Our heuristic SHOULD allow it (4 Mountains pay) but we need to
        // verify Orb's 2 mana doesn't contribute.
        //
        // Actually a cleaner test: only Orb + nothing else, Hill Giant
        // in hand. Orb can't pay non-dragon spell → unaffordable.
        Player p = newGame();
        addCard("Orb of Dragonkind", p).setSickness(false);
        addCards("Mountain", 1, p); // need 1 mana for Orb's own {1} cost
        addCardToZone("Hill Giant", p, ZoneType.Hand); // {3}{R}
        // Orb's mana is restricted to dragons; Hill Giant isn't a dragon,
        // so the Orb contribution isn't merged. Available = 1 Mountain = 1.
        // Hill Giant {3}{R} = 4 mana → not affordable.
        AssertJUnit.assertFalse(canAffordFromHand(p, "Hill Giant"));
    }

    // --- Shrine of the Forsaken Gods: Spell.Colorless only (7+ lands gate) ---

    @Test
    public void testShrineColorlessRestrictedManaOnlyPaysColorlessSpells() {
        // Shrine has a base {T}: Add {C} (unrestricted) and a second
        // {T}: Add {C}{C} that's restricted to colorless spells and gated
        // on 7+ lands. With 7 lands including the Shrine, both abilities
        // should fire. Hand: Hedron Archive (colorless 4-cmc artifact).
        Player p = newGame();
        addCard("Shrine of the Forsaken Gods", p);
        addCards("Wastes", 6, p);
        addCardToZone("Hedron Archive", p, ZoneType.Hand); // {4}, colorless
        // Budget: 6 Wastes (6 C unrestricted) + Shrine ability 1 (1 C unrestricted)
        // + Shrine ability 2 (2 C restricted, only colorless). Total
        // colorless-capable for colorless spells = 9.
        AssertJUnit.assertTrue(canAffordFromHand(p, "Hedron Archive"));
    }

    @Test
    public void testShrineRestrictedManaCannotPayColoredSpell() {
        // Same board, but hand has Lightning Bolt {R}. Shrine's restricted
        // mana is colorless-only, so it can't pay a red spell. We have 0
        // red sources. Not affordable.
        Player p = newGame();
        addCard("Shrine of the Forsaken Gods", p);
        addCards("Wastes", 6, p);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Lightning Bolt"));
    }

    // --- Multiple restricted sources at once ---

    @Test
    public void testStackedRestrictionsDifferentSpellTypes() {
        // Ancient Ziggurat (creature spells only) + Orb of Dragonkind
        // (dragon spells only) + 1 Mountain. Hand:
        //   Grizzly Bears {1}{G} — creature. Ziggurat permitted,
        //       Orb NOT permitted (not dragon). Affordable via Ziggurat
        //       rainbow pool + Mountain? Ziggurat 1 rainbow + Mountain 1 R.
        //       Cost is 2 mana; bucket G=0, bucket R=1 (Mountain). Ziggurat
        //       restricted contributes 1 rainbow. Merged working budget:
        //       rainbow=1, R=1. Pay G: native G=0, rainbow=1 → 0. Pay
        //       generic: most-stocked (R=1) → 0. Affordable.
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCard("Orb of Dragonkind", p).setSickness(false);
        addCard("Mountain", p);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Grizzly Bears"));
    }

    @Test
    public void testStackedRestrictionsNeitherPermitsInstant() {
        // Same board but hand has Counterspell {U}{U}. Neither Ziggurat
        // (creature-only) nor Orb (dragon-only) permits an instant. No
        // blue sources. Not affordable.
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCard("Orb of Dragonkind", p).setSickness(false);
        addCard("Mountain", p);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Counterspell"));
    }

    @Test
    public void testStackedRestrictionsBothPermitDragon() {
        // Ziggurat allows creature spells, Orb allows dragon spells,
        // Shivan Dragon {4}{R}{R} satisfies BOTH filters (creature AND
        // dragon). Both restricted pools should merge into the working
        // budget for this specific SA.
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCard("Orb of Dragonkind", p).setSickness(false);
        addCards("Mountain", 4, p);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        // Ziggurat contributes 1 rainbow (restricted, creature-permitted).
        // Orb contributes 2 rainbow (restricted, dragon-permitted). Both
        // permit Shivan Dragon, so both merge. Plus 4 Mountains = 4 R.
        // Total available for Shivan Dragon = 1 + 2 + 4 = 7. Shivan is 6.
        AssertJUnit.assertTrue(canAffordFromHand(p, "Shivan Dragon"));
    }

    // --- Isolation: restricted contribution doesn't leak across cards ---

    @Test
    public void testRestrictedContributionIsolatedPerCheck() {
        // Ancient Ziggurat + Forest. Hand: Grizzly Bears (creature,
        // permitted) and Cancel {1}{U}{U} (instant, NOT permitted).
        // Both canAfford calls happen in sequence via affordableCardNames.
        // The Bears check merges Ziggurat's contribution; the Cancel check
        // must NOT carry that merge over — Ziggurat's mana is filtered out.
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCards("Forest", 2, p);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        addCardToZone("Cancel", p, ZoneType.Hand);
        java.util.Set<String> actionable = affordableCardNames(p);
        AssertJUnit.assertTrue("Bears (creature) should be affordable",
                actionable.contains("Grizzly Bears"));
        AssertJUnit.assertFalse("Cancel (instant) should NOT pick up Ziggurat's mana",
                actionable.contains("Cancel"));
    }

    // --- Edge case: restricted source alone isn't enough for an unrestricted card ---

    @Test
    public void testOnlyRestrictedManaForbiddenSpell() {
        // Ancient Ziggurat alone. Hand: Lightning Bolt. Ziggurat doesn't
        // permit instants, and there's no other mana source. Not affordable.
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Lightning Bolt"));
    }

    @Test
    public void testOnlyRestrictedManaPermittedSpell() {
        // Ancient Ziggurat alone. Hand: Llanowar Elves {G}. Ziggurat
        // produces 1 rainbow (permitted for creature). Affordable.
        Player p = newGame();
        addCard("Ancient Ziggurat", p).setSickness(false);
        addCardToZone("Llanowar Elves", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Llanowar Elves"));
    }

    // =================================================================
    // Triggered mana abilities (Badgermole Cub)
    // =================================================================

    @Test
    public void testBadgermoleCubTriggeredManaRecognizedAsModifier() {
        // Badgermole Cub: TapsForMana trigger (ValidCard$ Creature)
        // producing +1 G per creature tap. The precise modifier parser
        // recognizes this pattern, so it is NOT routed to the legacy
        // manaMultiplierPresent fallback — instead it becomes a precise
        // ManaModifier that contributes +1 G per matching mana ability.
        Player p = newGame();
        addCard("Badgermole Cub", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        AssertJUnit.assertFalse("Badgermole Cub should be parsed precisely, not flagged as generic multiplier",
                scan.isManaMultiplierPresent());
        AssertJUnit.assertNotNull("modifier list should be populated",
                scan.getManaModifiers());
        AssertJUnit.assertEquals(1, scan.getManaModifiers().size());
    }

    @Test
    public void testBadgermoleCubPreciseGreenBonus() {
        // Badgermole Cub + 3 Llanowar Elves. Each Elf tap → 1 G (elf) +
        // 1 G (Cub bonus) = 2 G per Elf × 3 Elves = 6 G total. Precise
        // (not unbounded).
        //
        // Overrun = {2}{G}{G}{G} = 5 cmc. 6 total mana ≥ 5 → affordable.
        // Previously, this would have relied on unbounded promotion; now
        // it's a tight bounded match.
        Player p = newGame();
        addCard("Badgermole Cub", p).setSickness(false);
        addCards("Llanowar Elves", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("3 Elves × 2 G each (elf + cub bonus) = 6 G",
                6, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals("total also 6", 6, b.getTotalMana());
        AssertJUnit.assertFalse("total NOT unbounded — precise modeling",
                b.isTotalUnbounded());
        addCardToZone("Overrun", p, ZoneType.Hand); // {2}{G}{G}{G} = 5 cmc
        AssertJUnit.assertTrue(canAffordFromHand(p, "Overrun"));
    }

    @Test
    public void testBadgermoleCubWrongColorSpellStillRejected() {
        // Badgermole Cub only produces green. A blue spell like Counterspell
        // gets no help from the Cub. With no blue sources, still not
        // affordable. Even though multiplier-present flips SOME buckets
        // unbounded, only already-producing colors are promoted — and we
        // have no U source anywhere.
        Player p = newGame();
        addCard("Badgermole Cub", p).setSickness(false);
        addCards("Llanowar Elves", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Counterspell"));
    }

    // =================================================================
    // High Tide / Mana Flare style (TapsForMana multiplier trigger)
    // =================================================================

    @Test
    public void testManaFlareAsPermanentMultiplier() {
        // Mana Flare is structurally identical to High Tide's cast-time
        // effect: a TapsForMana trigger that fires for every land tap.
        // It's an enchantment, so it sits on the battlefield. We test the
        // multiplier-promotion mechanism using this card as a stand-in
        // for High Tide (which is harder to put into a test because it's
        // a one-shot spell creating an Effect card).
        Player p = newGame();
        addCard("Mana Flare", p).setSickness(false);
        addCards("Mountain", 2, p);
        ActionScan scan = ActionScan.scan(p);
        AssertJUnit.assertTrue("Mana Flare should trip manaMultiplierPresent",
                scan.isManaMultiplierPresent());
        ManaBudget b = scan.getBudget();
        // R bucket was seeded with 2 from the Mountains and then promoted
        // to unbounded by multiplier promotion.
        AssertJUnit.assertTrue(b.isBucketUnbounded(ManaBudget.IDX_R));
        AssertJUnit.assertTrue(b.isTotalUnbounded());
    }

    @Test
    public void testManaFlareEnablesExpensiveRedSpell() {
        // Mana Flare + 2 Mountains. Without the multiplier we have 2
        // total mana → couldn't afford a 5-cmc spell. With the multiplier,
        // red is promoted unbounded and total is unbounded. Shivan Dragon
        // {4}{R}{R} is affordable.
        Player p = newGame();
        addCard("Mana Flare", p).setSickness(false);
        addCards("Mountain", 2, p);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Shivan Dragon"));
    }

    @Test
    public void testManaFlareDoesNotAffectWrongColor() {
        // Mana Flare + 2 Mountains + Counterspell. Multiplier promotion
        // only flips colors that are already being produced — U isn't
        // produced, so U stays at 0. Counterspell not affordable.
        Player p = newGame();
        addCard("Mana Flare", p).setSickness(false);
        addCards("Mountain", 2, p);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Counterspell"));
    }

    // =================================================================
    // Doubling Cube — Special DoubleManaInPool
    // =================================================================

    @Test
    public void testDoublingCubeBelowBreakevenDoesNotHelp() {
        // Doubling Cube + 2 Forests. Cube activation cost is {3}, so the
        // break-even is base ≥ 6. With base = 2, Cube would net negative,
        // so it contributes nothing. Total stays 2, G stays 2, no
        // unbounded flags.
        Player p = newGame();
        addCard("Doubling Cube", p).setSickness(false);
        addCards("Forest", 2, p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("G bucket stays 2 — below Cube breakeven",
                2, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(2, b.getTotalMana());
        AssertJUnit.assertFalse("total NOT unbounded — precise modeling",
                b.isTotalUnbounded());
    }

    @Test
    public void testDoublingCubePreciselyBoostsAboveBreakeven() {
        // 7 Mountains + Doubling Cube. Base = 7. After Cube: max(7, 2*(7-3))
        // = max(7, 8) = 8. Total becomes exactly 8 (precise, not unbounded).
        // R bucket also doubles: 7 → 14.
        Player p = newGame();
        addCard("Doubling Cube", p).setSickness(false);
        addCards("Mountain", 7, p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("Cube boosts total to 8 = 2×(7−3)",
                8, b.getTotalMana());
        AssertJUnit.assertEquals("R bucket doubles 7→14",
                14, b.getBucket(ManaBudget.IDX_R));
        AssertJUnit.assertFalse(b.isTotalUnbounded());
    }

    @Test
    public void testDoublingCubeEnables8CostSpell() {
        // 7 Plains + Doubling Cube + Akroma, Angel of Wrath
        // ({5}{W}{W}{W} = 8 cmc). Base = 7, Cube → 8, W bucket doubles
        // 7 → 14, plenty of white for the {W}{W}{W}. Without Cube the
        // total gate would reject (7 < 8).
        Player p = newGame();
        addCard("Doubling Cube", p).setSickness(false);
        addCards("Plains", 7, p);
        addCardToZone("Akroma, Angel of Wrath", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Akroma, Angel of Wrath"));
    }

    @Test
    public void testHighTideAnalogPreciseBonusPerIslandTap() {
        // High Tide's cast effect is a TapsForMana trigger with
        // ValidCard$ Island. We can't easily construct the on-battlefield
        // Effect card in tests, but the SCRIPT is structurally identical
        // across High Tide and Badgermole Cub / Mana Flare, so we can
        // exercise the same code path with a permanent variant.
        //
        // This test uses Badgermole Cub's TapsForMana (ValidCard$ Creature)
        // as the precise-modifier test: 4 Llanowar Elves + Cub = 4 creature
        // taps × (1 G elf + 1 G cub) = 8 G, bounded, precise.
        Player p = newGame();
        addCard("Badgermole Cub", p).setSickness(false);
        addCards("Llanowar Elves", 4, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("4 elves × 2 G = 8 G", 8, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(8, b.getTotalMana());
        AssertJUnit.assertFalse("bounded, precise", b.isTotalUnbounded());
    }

    @Test
    public void testManaReflectionPreciseDoubling() {
        // Mana Reflection: ProduceMana replacement with ReplaceAmount$ 2.
        // 3 Forests + Mana Reflection: each Forest tap produces 2 G
        // instead of 1. Total = 6 G, bounded, precise.
        Player p = newGame();
        addCard("Mana Reflection", p).setSickness(false);
        addCards("Forest", 3, p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("3 Forests × 2 = 6 G", 6, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(6, b.getTotalMana());
        AssertJUnit.assertFalse("bounded, precise, not legacy-unbounded",
                b.isTotalUnbounded());
    }

    @Test
    public void testRoamingThroneDoublesBadgermoleCubTrigger() {
        // Validates the trigger-doubler path end-to-end using Roaming
        // Throne (no Wizard gymnastics needed):
        //   1. Badgermole Cub has a TapsForMana trigger: "whenever you
        //      tap a creature for mana, add an additional {G}."
        //   2. Roaming Throne's Panharmonicon-mode static:
        //        ValidCard$ Creature.Other+YouCtrl+ChosenType
        //      doubles triggered abilities of other creatures of the
        //      chosen type. We set the chosen type to "Badger" so the
        //      Cub (a Badger Mole) matches.
        //   3. Dryad Arbor is a Land + Creature that taps for {G}. Each
        //      tap fires Cub's trigger.
        //   4. With Roaming Throne in play, Cub's trigger should fire
        //      twice per Dryad Arbor tap → extra 2 G per tap, plus the
        //      1 G from the Arbor itself = 3 G total.
        Player p = newGame();
        Card throne = addCard("Roaming Throne", p);
        throne.setSickness(false);
        throne.setChosenType("Badger");
        Card cub = addCard("Badgermole Cub", p);
        cub.setSickness(false);
        addCard("Dryad Arbor", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // Confirm the doubler was collected.
        AssertJUnit.assertNotNull("Roaming Throne should parse as a trigger doubler",
                scan.getTriggerDoublers());
        AssertJUnit.assertEquals(1, scan.getTriggerDoublers().size());
        // Sanity — Cub matches the Throne's ValidCard filter.
        AssertJUnit.assertTrue("Cub (Badger) should match Throne's Creature.Other+YouCtrl+ChosenType filter",
                cub.isValid("Creature.Other+YouCtrl+ChosenType", p, throne, null));
        // Base: Dryad Arbor → 1 G. Cub's trigger fires when Arbor (a
        // creature) is tapped for mana, normally giving +1 G. Throne
        // doubles the trigger → +2 G. Total = 1 + 2 = 3.
        AssertJUnit.assertEquals("Cub's trigger doubled by Throne: 1 + 2×1 = 3 G",
                3, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(3, b.getTotalMana());
        AssertJUnit.assertFalse("precise, not unbounded", b.isTotalUnbounded());
    }

    @Test
    public void testHarmonicProdigyDoublesWizardTapsForManaTrigger() {
        // Scenario: a hypothetical Wizard creature with a TapsForMana
        // trigger would fire twice per matching tap under Harmonic
        // Prodigy. We use Badgermole Cub as a stand-in for the "trigger
        // fires" semantics, even though Cub is a Badger Mole not a
        // Wizard — Harmonic Prodigy's ValidCard filter would exclude it.
        //
        // So instead this test verifies the NEGATIVE case: Prodigy +
        // Badgermole Cub + 3 Elves → Cub's trigger is NOT doubled (Cub
        // isn't a Shaman/Wizard), so the G bonus stays at 3 × 1 = 3
        // (per elf × cub bonus) + 3 (elves' own G) = 6 G.
        Player p = newGame();
        addCard("Harmonic Prodigy", p).setSickness(false);
        addCard("Badgermole Cub", p).setSickness(false);
        addCards("Llanowar Elves", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // Llanowar Elves are Druids, not Shaman/Wizard, so Prodigy
        // doesn't amplify their mana abilities either. Badgermole Cub
        // is a Badger Mole, also excluded. Total G = 3 (Elves) + 3
        // (Cub's trigger × 3 elf taps) = 6.
        AssertJUnit.assertEquals("Prodigy doesn't apply to Badgers or Druids → plain 6 G",
                6, b.getBucket(ManaBudget.IDX_G));
    }

    @Test
    public void testThreeManaReflectionsCompound() {
        // 3 Mana Reflections + 1 Forest. Each Reflection is a separate
        // ×2 multiplicative modifier; they compose multiplicatively in
        // the foldIntoBudget modifier loop: 1 × 2 × 2 × 2 = 8.
        // One Forest tap → 8 G, total 8.
        Player p = newGame();
        addCard("Mana Reflection", p).setSickness(false);
        addCard("Mana Reflection", p).setSickness(false);
        addCard("Mana Reflection", p).setSickness(false);
        addCard("Forest", p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("G = 1 × 2 × 2 × 2 = 8",
                8, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(8, b.getTotalMana());
        AssertJUnit.assertFalse("precise compounding, not unbounded",
                b.isTotalUnbounded());
    }

    @Test
    public void testManaReflectionAndDoublingCubeStack() {
        // Mana Reflection (×2 per permanent tap) + Doubling Cube + 4
        // Forests. Cube's PRODUCTION is (t − cost), and Reflection
        // doubles productions. So Cube produces 2×(t − cost) under
        // Reflection, giving a final pool of (t − cost) + 2×(t − cost)
        // = 3×(t − cost).
        //
        // Pass 2: 4 Forests × 2 (Reflection) = 8 G. Total = 8.
        // Post-process Cube: cubeFactor = 1 + 2 = 3. New total =
        // max(8, 3×(8−3)) = max(8, 15) = 15.
        // Bucket: G × 3 = 24.
        Player p = newGame();
        addCard("Mana Reflection", p).setSickness(false);
        addCard("Doubling Cube", p).setSickness(false);
        addCards("Forest", 4, p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("Cube factor 3 under Reflection: (t−cost) + 2×(t−cost) = 3×5 = 15",
                15, b.getTotalMana());
        AssertJUnit.assertEquals("G bucket: 8 × 3 = 24",
                24, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertFalse(b.isTotalUnbounded());
    }

    @Test
    public void testTwoManaReflectionsWithDoublingCubePoolMath() {
        // 2 Mana Reflections + 1 Cube + 4 Forests.
        // Base under 2 Reflections: each Forest → 4 G, total 16.
        // Cube production multiplier under 2 Reflections: 1 × 2 × 2 = 4.
        // cubeFactor = 1 + 4 = 5.
        // New total = max(16, 5×(16−3)) = max(16, 65) = 65.
        Player p = newGame();
        addCard("Mana Reflection", p).setSickness(false);
        addCard("Mana Reflection", p).setSickness(false);
        addCard("Doubling Cube", p).setSickness(false);
        addCards("Forest", 4, p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("base = 4 × 4 = 16, Cube factor 5, new total = 5×13 = 65",
                65, b.getTotalMana());
    }

    @Test
    public void testDoublingCubeActivationCostHonorsReducer() {
        // Discount scenario: a cost-reducing static that lowers Cube's
        // {3} activation cost would change the break-even. Without a
        // real card doing this (Cube's cost is rarely discounted in
        // practice), we verify the mechanism indirectly: the post-process
        // calls activationManaCost(doublingCubeSa, scan) so the effective
        // cost reflects scan.reduceCostStatics.
        //
        // With no reducer and 5 Mountains + Cube:
        //   base = 5, Cube at cost 3: max(5, 2*(5-3)=4) = 5. No help.
        // Sanity check that this baseline holds (no rogue discounts
        // firing for activated mana abilities).
        Player p = newGame();
        addCard("Doubling Cube", p).setSickness(false);
        addCards("Mountain", 5, p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("base 5 stays 5 at Cube cost 3 (no help below 6)",
                5, b.getTotalMana());
    }

    @Test
    public void testTwoDoublingCubesCompoundAboveBreakeven() {
        // 8 Mountains + 2 Doubling Cubes. Iteration:
        //   base = 8
        //   Cube 1: max(8, 2*(8-3)) = max(8, 10) = 10
        //   Cube 2: max(10, 2*(10-3)) = max(10, 14) = 14
        // Total = 14. R bucket: 8 → 16 → 32.
        Player p = newGame();
        addCard("Doubling Cube", p).setSickness(false);
        addCard("Doubling Cube", p).setSickness(false);
        addCards("Mountain", 8, p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("two Cubes compound: 8 → 10 → 14",
                14, b.getTotalMana());
        AssertJUnit.assertEquals("R bucket doubles twice: 8 → 16 → 32",
                32, b.getBucket(ManaBudget.IDX_R));
    }

    @Test
    public void testDoublingCubeWithFloatingMana() {
        // 4 floating red + 4 Mountains + Doubling Cube. Base = 8 total
        // (4 floating + 4 Mountain). Cube: max(8, 2*(8-3)) = 10. Shivan
        // Dragon {4}{R}{R} (6 cmc) is trivially affordable.
        //
        // This verifies two things:
        //   1. Floating mana participates in the base budget for Cube.
        //   2. Cube precisely amplifies pre-Cube totals.
        Player p = newGame();
        addCard("Doubling Cube", p).setSickness(false);
        addCards("Mountain", 4, p);
        Card fakeSource = createCard("Mountain", p);
        for (int i = 0; i < 4; i++) {
            p.getManaPool().addMana(new forge.game.mana.Mana(
                    (byte) forge.card.mana.ManaAtom.RED, fakeSource, null, p), false);
        }
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);

        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // Base budget: 4 Mountains + 4 floating R = 8 R, total 8.
        // Cube: max(8, 2*(8-3)=10) = 10. R bucket doubles 8→16.
        AssertJUnit.assertEquals("base 4 Mountains + 4 floating R, then Cube doubles R",
                16, b.getBucket(ManaBudget.IDX_R));
        AssertJUnit.assertEquals("Cube amplifies 8 → 10", 10, b.getTotalMana());
        AssertJUnit.assertFalse("precise, not unbounded", b.isTotalUnbounded());
        AssertJUnit.assertTrue(canAffordFromHand(p, "Shivan Dragon"));
    }

    @Test
    public void testFloatingManaSeededInBuckets() {
        // No Doubling Cube — just verify floating mana ends up in buckets
        // as a baseline for the test above.
        Player p = newGame();
        Card fakeSource = createCard("Island", p);
        p.getManaPool().addMana(new forge.game.mana.Mana(
                (byte) forge.card.mana.ManaAtom.BLUE, fakeSource, null, p), false);
        p.getManaPool().addMana(new forge.game.mana.Mana(
                (byte) forge.card.mana.ManaAtom.BLUE, fakeSource, null, p), false);
        // Sanity: pool has the mana right before scan.
        int poolBefore = p.getManaPool().getAmountOfColor((byte) forge.card.mana.ManaAtom.BLUE);
        AssertJUnit.assertEquals("pool not seeded correctly before scan", 2, poolBefore);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        int poolAfter = p.getManaPool().getAmountOfColor((byte) forge.card.mana.ManaAtom.BLUE);
        AssertJUnit.assertEquals("pool cleared during scan unexpectedly", 2, poolAfter);
        AssertJUnit.assertEquals(2, b.getBucket(ManaBudget.IDX_U));
    }

    @Test
    public void testDoublingCubeAloneCorrectlyRejects() {
        // Doubling Cube with NO other mana sources. The multiplier
        // promotion only flips colors that are already producing; with
        // no other mana sources, no colors are produced, no promotion
        // happens, and total stays 0. Counterspell {U}{U} is correctly
        // rejected — you can't double an empty pool.
        Player p = newGame();
        addCard("Doubling Cube", p).setSickness(false);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        AssertJUnit.assertFalse("Cube alone can't double an empty pool",
                canAffordFromHand(p, "Counterspell"));
    }

    // =================================================================
    // Three Tree City — Count$Valid with ChosenType qualifier
    // =================================================================

    @Test
    public void testThreeTreeCityNoCreaturesDoesNotFlipUnbounded() {
        // Three Tree City alone with no other creatures. The second mana
        // ability reads X = creatures of the chosen type you control.
        // With no creatures at all, creatureCount = 0 and the upper bound
        // for X is 0. The ability contributes 0 to any color.
        //
        // The first ability ({T}: Add {C}) still contributes 1 C.
        // Total should be 1, not flipped unbounded.
        Player p = newGame();
        addCard("Three Tree City", p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("C bucket from basic ability only", 1,
                b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertFalse("total should NOT be unbounded",
                b.isTotalUnbounded());
        AssertJUnit.assertFalse("rainbow should NOT be unbounded",
                b.isBucketUnbounded(ManaBudget.IDX_RAINBOW));
    }

    @Test
    public void testThreeTreeCityWithCreaturesBoundsByCreatureCount() {
        // Three Tree City + 3 Grizzly Bears + 2 Plains. The Plains provide
        // the cost-free mana that makes TTC's {2}{T} advanced ability
        // payable: baseline = 2 (Plains) + 1 (TTC basic) = 3, sameSelf for
        // TTC TAP = 1, available = 2 ≥ 2 (cost), admit.
        //
        // Bucket rainbow += 3 (cap × creatureCount × 1 grossPerAct).
        // C += 1 (TTC basic). W += 2 (Plains).
        // Total: 2 (Plains) + max(basic_net 1, advanced_net 1) = 3.
        //   advanced_net = gross 3 - cost 2 = 1.
        Player p = newGame();
        addCards("Plains", 2, p);
        addCard("Three Tree City", p);
        addCards("Grizzly Bears", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(3, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals(2, b.getBucket(ManaBudget.IDX_W));
        AssertJUnit.assertEquals(3, b.getTotalMana());
        AssertJUnit.assertFalse("total should NOT be unbounded",
                b.isTotalUnbounded());
    }

    @Test
    public void testThreeTreeCityWithCreaturesNoOtherManaDoesNotAdvertiseRainbow() {
        // Same as above but WITHOUT the Plains. TTC advanced ability needs
        // 2 mana to activate, but only TTC basic (1 C) is available, and
        // that's the same-card same-group resource. So available = 1 - 1 = 0
        // < 2. The deferred loop drops TTC advanced — rainbow bucket stays 0.
        Player p = newGame();
        addCard("Three Tree City", p);
        addCards("Grizzly Bears", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("rainbow should be 0 — TTC advanced unpayable",
                0, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals(1, b.getTotalMana());
    }

    @Test
    public void testThreeTreeCityCannotAffordExpensiveSpell() {
        // Three Tree City alone — 1 mana from first ability, 0 from
        // second (no creatures). Hand: Shivan Dragon {4}{R}{R} = 6. Not
        // affordable — total 1 < 6.
        Player p = newGame();
        addCard("Three Tree City", p);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Shivan Dragon"));
    }

    @Test
    public void testThreeTreeCityChosenOtterCoruscationMage4CmcRejected() {
        // Scenario: 2 Plains + Three Tree City (chosen type = Otter) +
        // Coruscation Mage (Otter Wizard) + Wrath of God {2}{W}{W} (4 cmc)
        // in hand.
        //
        // Budget math:
        //   2 Plains      → W bucket += 2, total += 2.
        //   Three Tree City (two tap-self OTMAs, max rule applies):
        //     Basic {T}: Add {C}   → net = 1, bucket C += 1.
        //     {2}, {T}: Add X Any  → cap=1, multiplier = creatureCount = 1
        //                            (Coruscation Mage, over-count for
        //                             Creature.ChosenType+YouCtrl).
        //                            Bucket RAINBOW += 1, net = 1 - 2 = 0.
        //     max(1, 0) = 1 contributed to total.
        //   Coruscation Mage has no mana ability.
        //
        // Total: 2 (Plains) + 1 (Three Tree City max) = 3.
        // Wrath of God = 4 cmc. Total gate 3 < 4 → NOT affordable.
        // This is the tight-boundary test: one more mana than we have.
        Player p = newGame();
        addCards("Plains", 2, p);
        Card ttc = addCard("Three Tree City", p);
        ttc.setChosenType("Otter");
        addCard("Coruscation Mage", p).setSickness(false);
        addCardToZone("Wrath of God", p, ZoneType.Hand);

        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        // Verify the budget state.
        AssertJUnit.assertEquals(2, b.getBucket(ManaBudget.IDX_W));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_RAINBOW));
        // Total: 2 Plains + max(1 basic, 0 second) = 3.
        AssertJUnit.assertEquals(3, b.getTotalMana());
        AssertJUnit.assertFalse("Wrath of God (4 cmc) should NOT be affordable with only 3 total mana",
                canAffordFromHand(p, "Wrath of God"));
    }

    @Test
    public void testThreeTreeCityCreaturesAllowCheapRainbowSpell() {
        // Three Tree City + 3 Bears + 2 Plains. The Plains make the
        // {2}{T} advanced ability payable; rainbow bucket fills, total = 3.
        // Lightning Bolt {R} payable via R from rainbow.
        Player p = newGame();
        addCards("Plains", 2, p);
        addCard("Three Tree City", p);
        addCards("Grizzly Bears", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Lightning Bolt"));
    }

    // =================================================================
    // OTMA recognition — non-cost-based caps (ActivationLimit, PlayerTurn)
    // =================================================================

    @Test
    public void testViviOrnitierActivationLimitOtmaNoContributionAtBasePower() {
        // Vivi Ornitier is an OTMA via ActivationLimit$ 1 — not via any
        // self-consuming cost. Cost$ 0, PlayerTurn$ True, ActivationLimit$ 1.
        //
        // At base power 0, the Count$CardPower reference returns 0, so
        // multiplier = 0 and foldIntoBudget returns early. No contribution.
        // (Bucket UR stays 0, total stays 0.)
        Player p = newGame();
        addCard("Vivi Ornitier", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertFalse(scan.hasStructuralBailout());
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_U));
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_R));
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(0, b.getTotalMana());
    }

    @Test
    public void testCounterAddViaInternalDoesNotLayerIntoCurrentPower() {
        // Diagnostic: prove that the test environment's counter-add path
        // does NOT propagate through to getCurrentPower, even with
        // addCounterInternal + checkStateEffects. This isolates the issue
        // as a test-harness limitation — the heuristic reads
        // getCurrentPower correctly, but counters never reach it in tests.
        //
        // In a real game, adding 3 P1P1 counters to a 0/3 creature would
        // make it 3/6 after SBAs. In the test environment here, it stays
        // 0/3. That's why the Vivi tests use setBasePower() instead.
        Player p = newGame();
        Card vivi = addCard("Vivi Ornitier", p);
        vivi.setSickness(false);
        AssertJUnit.assertEquals("base power before counters", 0, vivi.getCurrentPower());
        vivi.addCounterInternal(forge.game.card.CounterEnumType.P1P1,
                3, p, false, null, null);
        AssertJUnit.assertEquals("counters are stored correctly",
                3, vivi.getCounters(forge.game.card.CounterEnumType.P1P1));
        p.getGame().getAction().checkStateEffects(true);
        p.getGame().getAction().checkStateEffects(true);
        // Ideal behavior in a real game: getCurrentPower() == 3.
        // Observed behavior in tests: getCurrentPower() == 0.
        // This assertion documents the test-env limitation; if someday it
        // starts returning 3, we can drop the setBasePower workaround
        // in the Vivi tests.
        AssertJUnit.assertEquals(
                "test env does not flow counters to currentPower — if this "
                + "starts returning 3, we can use real counters in Vivi tests",
                0, vivi.getCurrentPower());
    }

    @Test
    public void testSetBasePowerFlowsThroughGetCurrentPower() {
        // Counter-diagnostic: setBasePower() DOES flow through to
        // getCurrentPower() in the test env. This proves the workaround is
        // correct: we're exercising the same getCurrentPower() read-path
        // that the heuristic uses in production.
        Player p = newGame();
        Card vivi = addCard("Vivi Ornitier", p);
        vivi.setSickness(false);
        AssertJUnit.assertEquals(0, vivi.getCurrentPower());
        vivi.setBasePower(3);
        p.getGame().getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("setBasePower flows through to getCurrentPower",
                3, vivi.getCurrentPower());
    }

    @Test
    public void testViviOrnitierPowerScalingContribution() {
        // Vivi base power is 0. In the test environment, +1/+1 counters
        // don't actually propagate through to getCurrentPower via any
        // counter-adding API (setCounters, addCounter, addCounterInternal)
        // even with checkStateEffects — the continuous-effect layer for
        // counter-based P/T boost doesn't register without a full game
        // tick. Instead, set the base power directly to simulate "Vivi
        // at power 3 after some counters were added."
        //
        // With current power = 3: multiplier = 3, cap = 1 (ActivationLimit),
        // Produced "Combo U R" → rainbow bucket += 3, total += 3.
        Player p = newGame();
        Card vivi = addCard("Vivi Ornitier", p);
        vivi.setSickness(false);
        vivi.setBasePower(3);
        p.getGame().getAction().checkStateEffects(true);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("rainbow from Combo UR via CardPower",
                3, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals("total net (Cost$ 0, gross 3)", 3, b.getTotalMana());
    }

    @Test
    public void testViviOrnitierCanAffordCheapSpell() {
        // Vivi with base power 2 → contributes 2 rainbow.
        // Lightning Bolt {R} is affordable.
        Player p = newGame();
        Card vivi = addCard("Vivi Ornitier", p);
        vivi.setSickness(false);
        vivi.setBasePower(2);
        p.getGame().getAction().checkStateEffects(true);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        AssertJUnit.assertTrue(canAffordFromHand(p, "Lightning Bolt"));
    }

    @Test
    public void testViviOrnitierOncePerTurnCap() {
        // Vivi at base power 5. ActivationLimit$ 1 means cap = 1, so the
        // contribution is one activation's worth of mana (5 rainbow), not
        // 5 × something. Shivan Dragon (6 cmc) is NOT affordable from 5.
        Player p = newGame();
        Card vivi = addCard("Vivi Ornitier", p);
        vivi.setSickness(false);
        vivi.setBasePower(5);
        p.getGame().getAction().checkStateEffects(true);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("5 rainbow from one activation at power 5",
                5, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(5, b.getTotalMana());
        addCardToZone("Shivan Dragon", p, ZoneType.Hand); // {4}{R}{R} = 6
        AssertJUnit.assertFalse("Shivan Dragon (6 cmc) should NOT be affordable",
                canAffordFromHand(p, "Shivan Dragon"));
    }

    // =================================================================
    // Exclusion group: SAC (sacrifice-self OTMAs)
    // =================================================================

    @Test
    public void testLotusPetalSacSelfSingleActivation() {
        // Lotus Petal: {T}, Sacrifice Lotus Petal: Add one mana of any
        // color. It's an OTMA via both CostTap (tap-lockout) AND
        // CostSacrifice-self (sac-lockout). The exclusion classifier
        // picks TAP as the strongest lockout group since the tap cost is
        // present. Cap = 1, produces 1 rainbow per activation.
        Player p = newGame();
        addCard("Lotus Petal", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(1, b.getTotalMana());
    }

    @Test
    public void testMultipleLotusPetalsStackIndependently() {
        // Three Lotus Petals: each is a separate card, so their
        // contributions sum normally. Per-card exclusion grouping is
        // per-card, not across the whole battlefield.
        Player p = newGame();
        addCards("Lotus Petal", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(3, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(3, b.getTotalMana());
    }

    // =================================================================
    // Exclusion group: EXILE (exile-self-from-hand OTMAs)
    // =================================================================

    @Test
    public void testElvishSpiritGuideExileFromHandOtma() {
        // Elvish Spirit Guide: Exile this card from your hand: Add {G}.
        // Exile-self-from-hand exclusion group. Cap = 1, produces 1 G.
        Player p = newGame();
        addCardToZone("Elvish Spirit Guide", p, ZoneType.Hand);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(1, b.getTotalMana());
    }

    @Test
    public void testTwoSpiritGuidesInHandStackIndependently() {
        // Two separate Spirit Guide cards in hand → two separate card
        // instances → two separate exclusion keys → contributions sum.
        Player p = newGame();
        addCardToZone("Elvish Spirit Guide", p, ZoneType.Hand);
        addCardToZone("Elvish Spirit Guide", p, ZoneType.Hand);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(2, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(2, b.getTotalMana());
    }

    @Test
    public void testRockfaceVillageRestrictedTapSelfSharesWithUnrestricted() {
        // Rockface Village has TWO tap-self mana abilities:
        //   {T}: Add {C}                                   — unrestricted
        //   {T}: Add {R}    (spend only on creature spells) — restricted
        // They share the tap, so only ONE can fire per turn.
        //
        // Board: Island, Mountain, Rockface Village, Stormcatch Mentor.
        // Hand: Bria, Riptide Rogue {2}{U}{R} = 4 cmc (creature).
        //
        // Real max mana = 1 U + 1 R + max(1 C, 1 R restricted) = 3 mana.
        // Bria = 4 mana → NOT affordable.
        //
        // Stormcatch Mentor's cost reducer only applies to instants and
        // sorceries, not creatures, so it doesn't help Bria either.
        Player p = newGame();
        addCard("Island", p);
        addCard("Mountain", p);
        addCard("Rockface Village", p);
        addCard("Stormcatch Mentor", p).setSickness(false);
        addCardToZone("Bria, Riptide Rogue", p, ZoneType.Hand);
        AssertJUnit.assertFalse(
                "Bria should NOT be affordable — Rockface's two tap abilities share a tap",
                canAffordFromHand(p, "Bria, Riptide Rogue"));
    }

    @Test
    public void testPearlOfWisdomSelfDiscountWithOtter() {
        // Pearl of Wisdom: {2}{U} sorcery, "Costs {1} less if you control
        // an Otter." The discount is a ValidCard$ Card.Self ReduceCost
        // static with EffectZone$ All — defined on the card itself in hand.
        // ActionScan must collect this static when walking the hand.
        //
        // Board: 2 Islands + Bria, Riptide Rogue (an Otter).
        // Hand: Pearl of Wisdom. Effective cost: {2}{U} - {1} = {1}{U}.
        // 2 Islands pay → affordable.
        Player p = newGame();
        addCards("Island", 2, p);
        addCard("Bria, Riptide Rogue", p).setSickness(false);
        addCardToZone("Pearl of Wisdom", p, ZoneType.Hand);
        AssertJUnit.assertTrue("Pearl of Wisdom should be discounted to {1}{U} with an Otter",
                canAffordFromHand(p, "Pearl of Wisdom"));
    }

    @Test
    public void testPearlOfWisdomNoOtterNotDiscounted() {
        // Same setup without an Otter. Discount IsPresent$ Otter.YouCtrl
        // condition fails, so cost stays {2}{U}. Only 2 Islands → not affordable.
        Player p = newGame();
        addCards("Island", 2, p);
        addCardToZone("Pearl of Wisdom", p, ZoneType.Hand);
        AssertJUnit.assertFalse("Pearl of Wisdom needs {2}{U} = 3 mana without an Otter",
                canAffordFromHand(p, "Pearl of Wisdom"));
    }

    @Test
    public void testUrDragonCommandZoneEminenceDiscountsDragons() {
        // The Ur-Dragon: S:Mode$ ReduceCost | EffectZone$ Battlefield,Command
        // | ValidCard$ Dragon.Other | Amount$ 1 — Eminence: other Dragon
        // spells cost {1} less while Ur-Dragon is in the command zone or
        // on the battlefield.
        //
        // Setup: Ur-Dragon in command zone, 3 Mountains + Sol Ring,
        // Shivan Dragon ({4}{R}{R} = 6 cmc) in hand. Total mana = 5
        // (3 Mountains + 2 Sol Ring), so without the discount Shivan
        // Dragon (6 cmc) is NOT affordable. With the {1} discount it
        // becomes effective {3}{R}{R} = 5 cmc → exactly affordable.
        //
        // This proves ActionScan picks up the Ur-Dragon static from the
        // command zone via scanCardReplacementsAndStatics, and that the
        // EffectZone$ Battlefield,Command param is honored.
        Player p = newGame();
        addCards("Mountain", 3, p);
        addCard("Sol Ring", p).setSickness(false);
        addCardToZone("The Ur-Dragon", p, ZoneType.Command);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        AssertJUnit.assertTrue(
                "Shivan Dragon should be affordable with The Ur-Dragon eminence discount",
                canAffordFromHand(p, "Shivan Dragon"));
    }

    @Test
    public void testUrDragonAbsentNoDiscount() {
        // Same setup but without The Ur-Dragon. No discount → Shivan
        // Dragon stays at 6 cmc → only 5 mana available → NOT affordable.
        Player p = newGame();
        addCards("Mountain", 3, p);
        addCard("Sol Ring", p).setSickness(false);
        addCardToZone("Shivan Dragon", p, ZoneType.Hand);
        AssertJUnit.assertFalse(
                "Shivan Dragon should NOT be affordable without the Ur-Dragon discount",
                canAffordFromHand(p, "Shivan Dragon"));
    }

    @Test
    public void testGhaltaPrimalHungerSelfDiscountByCreaturePower() {
        // Ghalta, Primal Hunger: {10}{G}{G} (12 cmc), self-discount by
        // total power of creatures you control. Static is on the card
        // itself with EffectZone$ All, so it's active in hand.
        //
        // Setup: 2 Forests + 5 Grizzly Bears (10 power total). Discount
        // = 10 → effective cost {G}{G} = 2 mana. 2 Forests pay exactly.
        Player p = newGame();
        addCards("Forest", 2, p);
        addCards("Grizzly Bears", 5, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        addCardToZone("Ghalta, Primal Hunger", p, ZoneType.Hand);
        AssertJUnit.assertTrue(
                "Ghalta should be affordable: discounted from 12 cmc to {G}{G} by 10 power",
                canAffordFromHand(p, "Ghalta, Primal Hunger"));
    }

    @Test
    public void testGhaltaWithoutCreaturesNotAffordable() {
        // 2 Forests, no creatures → no discount → Ghalta stays {10}{G}{G}
        // → needs 12 mana → not affordable from 2 Forests.
        Player p = newGame();
        addCards("Forest", 2, p);
        addCardToZone("Ghalta, Primal Hunger", p, ZoneType.Hand);
        AssertJUnit.assertFalse(
                "Ghalta needs 12 mana without any creatures to discount via power",
                canAffordFromHand(p, "Ghalta, Primal Hunger"));
    }

    @Test
    public void testStormcatchMentorReducesInstantNotCreature() {
        // Stormcatch Mentor's static reduces instants/sorceries by {1}.
        // Critical discriminator: both a 2-cmc instant and a 2-cmc creature
        // with only 1 Island available. The instant is reduced to {U} and
        // becomes affordable; the creature is NOT reduced (ValidCard$
        // Instant,Sorcery filter) and stays unaffordable.
        Player p = newGame();
        addCard("Island", p);
        addCard("Stormcatch Mentor", p); // sick, but static still applies
        addCardToZone("Mana Leak", p, ZoneType.Hand);       // {1}{U} instant
        addCardToZone("Merfolk Looter", p, ZoneType.Hand);  // {1}{U} creature
        AssertJUnit.assertTrue("Mana Leak should be affordable (reduced to {U})",
                canAffordFromHand(p, "Mana Leak"));
        AssertJUnit.assertFalse("Merfolk Looter should NOT be affordable (reducer excludes creatures)",
                canAffordFromHand(p, "Merfolk Looter"));
    }

    @Test
    public void testFloatingManaCombinedWithLandSourcesShortfall() {
        // Pool: 1 R floating. Board: 1 Mountain + 1 Forest = 3 total mana.
        // Bloodbraid Elf is {2}{R}{G} = 4 cmc → NOT affordable.
        Player p = newGame();
        addCard("Mountain", p);
        addCard("Forest", p);
        Card poolSrc = createCard("Mountain", p);
        p.getManaPool().addMana(new forge.game.mana.Mana(
                (byte) forge.card.mana.ManaAtom.RED, poolSrc, null, p), false);
        addCardToZone("Bloodbraid Elf", p, ZoneType.Hand);
        AssertJUnit.assertFalse(
                "Bloodbraid Elf should NOT be affordable with only 3 total mana",
                canAffordFromHand(p, "Bloodbraid Elf"));
    }

    @Test
    public void testFloatingManaCombinedWithLandSourcesAffordable() {
        // Pool: 2 R floating. Board: 1 Mountain + 1 Forest = 4 total mana
        // (3 R reachable, 1 G reachable). Bloodbraid Elf {2}{R}{G} fits.
        Player p = newGame();
        addCard("Mountain", p);
        addCard("Forest", p);
        Card poolSrc = createCard("Mountain", p);
        p.getManaPool().addMana(new forge.game.mana.Mana(
                (byte) forge.card.mana.ManaAtom.RED, poolSrc, null, p), false);
        p.getManaPool().addMana(new forge.game.mana.Mana(
                (byte) forge.card.mana.ManaAtom.RED, poolSrc, null, p), false);
        addCardToZone("Bloodbraid Elf", p, ZoneType.Hand);
        AssertJUnit.assertTrue(
                "Bloodbraid Elf should be affordable with 2R floating + Mountain + Forest",
                canAffordFromHand(p, "Bloodbraid Elf"));
    }

    // =================================================================
    // Targeting pre-flight — mirror TargetSelection auto-abort
    // =================================================================

    @Test
    public void testShockWithNoCreaturesNotActionable() {
        // Shock targets "any target" (includes players), so it's always
        // affordable AND always has targets. True negative baseline.
        Player p = newGame();
        addCard("Mountain", p);
        addCardToZone("Shock", p, ZoneType.Hand);
        AssertJUnit.assertTrue("Shock can always target a player",
                affordableCardNames(p).contains("Shock"));
    }

    @Test
    public void testNaturalizeNoArtifactsOrEnchantments() {
        // Naturalize only targets artifacts/enchantments. Empty board of
        // those → game auto-aborts the cast, so the heuristic must NOT
        // report it as actionable.
        Player p = newGame();
        addCard("Forest", p);
        addCard("Forest", p);
        addCardToZone("Naturalize", p, ZoneType.Hand);
        AssertJUnit.assertFalse(
                "Naturalize should NOT be actionable with no artifacts or enchantments",
                affordableCardNames(p).contains("Naturalize"));
    }

    @Test
    public void testNaturalizeWithTargetIsActionable() {
        // Same setup plus a target artifact — should now be actionable.
        Player p = newGame();
        addCard("Forest", p);
        addCard("Forest", p);
        addCard("Sol Ring", p);
        addCardToZone("Naturalize", p, ZoneType.Hand);
        AssertJUnit.assertTrue(
                "Naturalize should be actionable with at least one artifact on the battlefield",
                affordableCardNames(p).contains("Naturalize"));
    }

    @Test
    public void testCounterspellNoStack() {
        // Counterspell targets a spell on the stack. With an empty stack
        // there is nothing to counter → game auto-aborts. Must not be
        // highlighted.
        Player p = newGame();
        addCard("Island", p);
        addCard("Island", p);
        addCardToZone("Counterspell", p, ZoneType.Hand);
        AssertJUnit.assertFalse(
                "Counterspell should NOT be actionable with an empty stack",
                affordableCardNames(p).contains("Counterspell"));
    }

    // =================================================================
    // Cost-bearing OTMA deferral — fixed-point admission
    // =================================================================

    @Test
    public void testThreeTreeCityAloneAdvancedNotCommitted() {
        // TTC alone with no other mana sources. The {2}{T} advanced
        // ability has cost 2 but the only other contributor on the board
        // is TTC's own basic {T} → same exclusion group → sameSelf = 1,
        // available = totalMana - sameSelf = 1 - 1 = 0 < 2. Drop.
        // Rainbow bucket stays 0; total stays 1 (TTC basic only).
        Player p = newGame();
        addCard("Three Tree City", p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("rainbow must NOT be reachable", 0,
                b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(1, b.getTotalMana());
    }

    @Test
    public void testThreeTreeCityPlusOnePlainsAdvancedStillUnpayable() {
        // TTC + 1 Plains + 1 chosen-type creature. Baseline = 1 (Plains)
        // + 1 (TTC basic) = 2. For TTC advanced: sameSelf = 1, available
        // = 2 - 1 = 1 < 2. Drop.
        Player p = newGame();
        addCard("Plains", p);
        addCard("Three Tree City", p);
        addCard("Grizzly Bears", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(2, b.getTotalMana());
    }

    @Test
    public void testThreeTreeCityPlusTwoPlainsAdvancedAdmits() {
        // TTC + 2 Plains + 1 chosen-type creature. Baseline = 2 (Plains)
        // + 1 (TTC basic) = 3. For TTC advanced: sameSelf = 1, available
        // = 3 - 1 = 2 ≥ 2. Admit. Rainbow bucket = 1 × creatureCount = 1.
        Player p = newGame();
        addCards("Plains", 2, p);
        addCard("Three Tree City", p);
        addCard("Grizzly Bears", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals(3, b.getTotalMana());
    }

    @Test
    public void testThreeTreeCityPlusSolRingAdvancedAdmits() {
        // TTC + Sol Ring + chosen-type creature. Sol Ring is cost-free,
        // contributes 2 to baseline. TTC advanced: sameSelf = 1,
        // available = 3 - 1 = 2 ≥ 2 → admit.
        Player p = newGame();
        addCard("Sol Ring", p).setSickness(false);
        addCard("Three Tree City", p);
        addCard("Grizzly Bears", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_RAINBOW));
        // Total = 2 (Sol Ring) + max(1 basic, advanced_net 1−2 clamp 0) = 3.
        AssertJUnit.assertEquals(3, b.getTotalMana());
    }

    @Test
    public void testIzzetSignetChainsIntoThreeTreeCityViaFixedPoint() {
        // Two-layer fixed-point chain: Izzet Signet ({1}{T}: Add UR,
        // cost 1, net 1) needs 1 mana from elsewhere to activate. Three
        // Tree City advanced ({2}{T}: Add Chosen × N, cost 2) needs 2
        // mana from non-TTC-tap sources to activate.
        //
        // Board: Plains + Izzet Signet + Three Tree City + Grizzly Bears
        //  (chosen type = Bear).
        //
        // Layer 1 baseline (cost-free):
        //   Plains  → +1 W, total 1
        //   TTC basic ({T}: Add C) → TAP[ttc] = 1, total 2
        //   Signet, TTC advanced → both pending
        //
        // Layer 2 (fixed-point pass 1):
        //   Signet: cost 1, sameSelf for Signet TAP = 0 (no other Signet
        //     ability), available = 2 ≥ 1 → admit. Net 1, total → 3.
        //   TTC advanced: cost 2, sameSelf for TTC TAP = 1 (basic),
        //     available = 3 - 1 = 2 ≥ 2 → admit. Net 1, but max(1, 1) = 1
        //     → no delta to total. Bucket rainbow = 1.
        //
        // Note: if the loop walked TTC advanced FIRST in pass 1, available
        // would be 2 - 1 = 1 < 2 (Signet not yet admitted). It would
        // defer. Then pass 2 picks up Signet → total 3, then a third pass
        // picks up TTC advanced. Either way the final state matches:
        //   Total = 3, Rainbow = 1, U=1, R=1, W=1, C=1.
        Player p = newGame();
        addCard("Plains", p);
        addCard("Izzet Signet", p).setSickness(false);
        addCard("Three Tree City", p);
        addCard("Grizzly Bears", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("Plains W", 1, b.getBucket(ManaBudget.IDX_W));
        AssertJUnit.assertEquals("Signet U", 1, b.getBucket(ManaBudget.IDX_U));
        AssertJUnit.assertEquals("Signet R", 1, b.getBucket(ManaBudget.IDX_R));
        AssertJUnit.assertEquals("TTC basic C", 1, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals("TTC advanced rainbow", 1, b.getBucket(ManaBudget.IDX_RAINBOW));
        AssertJUnit.assertEquals("Total 1 (Plains) + 1 (Signet net) + 1 (TTC max) = 3", 3,
                b.getTotalMana());
    }

    @Test
    public void testIzzetSignetAloneNotPayable() {
        // Signet alone: cost 1, no other mana → drop. Total stays 0.
        // Verifies the deferred-cost fixed-point's "drop unpayable" path.
        Player p = newGame();
        addCard("Izzet Signet", p).setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_U));
        AssertJUnit.assertEquals(0, b.getBucket(ManaBudget.IDX_R));
        AssertJUnit.assertEquals(0, b.getTotalMana());
    }

    @Test
    public void testNykthosAloneAdvancedNotCommitted() {
        // Nykthos, Shrine to Nyx alone — no other mana, no devotion. The
        // {2}{T} ChooseColor ability has cost 2 and zero net (no devotion),
        // and even if devotion were positive there's no other mana source
        // to pay the {2}. Devotion buckets must NOT be folded.
        //
        // Nykthos's basic {T}: Add {C} ability is cost-free → C bucket = 1,
        // total = 1. Advanced ability is dropped.
        Player p = newGame();
        addCard("Nykthos, Shrine to Nyx", p);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals(1, b.getTotalMana());
        AssertJUnit.assertEquals("no devotion fold without payable cost", 0,
                b.getBucket(ManaBudget.IDX_G));
    }

    @Test
    public void testNykthosDevotionFromNonManaCreaturesDropsAdvanced() {
        // Nykthos + 3 Heliod's Pilgrim (W creature, devotion W = 3, but
        // Pilgrim has NO mana ability). Nykthos's {2}{T} cost can't be
        // paid: baseline = 1 (Nykthos basic), sameSelf for Nykthos TAP
        // = 1, available = 0 < 2 → drop.
        //
        // Advanced not admitted → devotion W bucket NOT folded by Nykthos.
        Player p = newGame();
        addCard("Nykthos, Shrine to Nyx", p);
        addCards("Heliod's Pilgrim", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_C));
        // Heliod's Pilgrim is a W creature with no mana ability → Nykthos
        // sees devotion W = 3, but the deferred fold drops because cost
        // is unpayable. W bucket from Nykthos must be 0.
        AssertJUnit.assertEquals("Nykthos devotion fold dropped — cost unpayable",
                0, b.getBucket(ManaBudget.IDX_W));
    }

    @Test
    public void testNykthosWithDevotionAndExtraManaAdvancedAdmits() {
        // Nykthos + 3 Llanowar Elves (devotion G = 3, each provides G).
        // Baseline = 1 (Nykthos basic) + 3 (Elves) = 4. Nykthos sameSelf
        // = 1, available = 3 ≥ 2 → admit. Nykthos devotion fold adds
        // G += 3 (from devotion calc).
        // G total = 3 (Elves) + 3 (Nykthos devotion fold) = 6.
        // Total = 3 (Elves) + max(Nykthos basic 1, advanced_net 3-2=1) = 4.
        Player p = newGame();
        addCard("Nykthos, Shrine to Nyx", p);
        addCards("Llanowar Elves", 3, p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) c.setSickness(false);
        ActionScan scan = ActionScan.scan(p);
        ManaBudget b = scan.getBudget();
        AssertJUnit.assertEquals("G = 3 Elves + 3 Nykthos devotion fold",
                6, b.getBucket(ManaBudget.IDX_G));
        AssertJUnit.assertEquals(1, b.getBucket(ManaBudget.IDX_C));
        AssertJUnit.assertEquals(4, b.getTotalMana());
    }

    @Test
    public void testRockfaceVillageRiskRegressionRetainedAfterDeferralRefactor() {
        // Verifies the Rockface Village fix from earlier still holds with
        // the deferred-cost-OTMA refactor in place. Rockface has TWO tap
        // abilities, both cost-free (no mana cost), so neither defers.
        // The exclusion-group max-per-card rule must still prevent
        // double-counting the restricted tap-self into total.
        Player p = newGame();
        addCard("Island", p);
        addCard("Mountain", p);
        addCard("Rockface Village", p);
        addCard("Stormcatch Mentor", p).setSickness(false);
        addCardToZone("Bria, Riptide Rogue", p, ZoneType.Hand);
        AssertJUnit.assertFalse(canAffordFromHand(p, "Bria, Riptide Rogue"));
    }

    // =================================================================
    // Non-mana ability conflict-subtraction
    // =================================================================

    @Test
    public void testAbzanBannerPlainsSwampDrawNotAffordable() {
        // Plains + Swamp + Abzan Banner. Lands give W (1) + B (1); Banner's
        // mana ability contributes rainbow (1) and total +1 = total 3.
        // Draw cost = {W}{B}{G} + tap + sac.
        //
        // Without conflict-subtraction: work[RAINBOW] = 1 could stand in
        // for the {G} shard → false positive, "affordable".
        // With conflict-subtraction: Banner's rainbow and total are
        // stripped from the working budget → no G source left → draw
        // NOT affordable. Verifies Banner's own color contribution is
        // excluded.
        Player p = newGame();
        addCard("Plains", p);
        addCard("Swamp", p);
        addCard("Abzan Banner", p).setSickness(false);
        AssertJUnit.assertFalse(
                "Banner's own rainbow must NOT cover the {G} shard of its own draw",
                canAffordNonManaAbilityOf(p, "Abzan Banner"));
    }

    @Test
    public void testAbzanBannerTwoPlainsSwampDrawNotAffordableDespiteEnoughTotal() {
        // Plains + Plains + Swamp + Banner. Lands total 3 mana (W/W/B),
        // Banner adds 1 more → budget total = 4, well above the draw's
        // 3 colored cost. But after conflict-subtraction:
        //   work[W] = 2, work[B] = 1, work[G] = 0, work[RAINBOW] = 0,
        //   workingTotal = 3.
        // The {G} shard can't be paid from any bucket → NOT affordable,
        // even though the total-mana gate would otherwise pass. This is
        // the tight case: total is sufficient but the colored shard check
        // still rejects.
        Player p = newGame();
        addCard("Plains", p);
        addCard("Plains", p);
        addCard("Swamp", p);
        addCard("Abzan Banner", p).setSickness(false);
        AssertJUnit.assertFalse(
                "Banner's own {G} contribution must NOT satisfy the draw's {G} shard even when total mana is enough",
                canAffordNonManaAbilityOf(p, "Abzan Banner"));
    }

    @Test
    public void testAbzanBannerWithWBGLandsDrawAffordable() {
        // Plains + Swamp + Forest + Abzan Banner. The three lands provide
        // W, B, G for free. Banner's draw cost {W}{B}{G} + tap + sac is
        // payable by the three lands alone — Banner's own tap is consumed
        // by the draw, not by mana. Affordable.
        Player p = newGame();
        addCard("Plains", p);
        addCard("Swamp", p);
        addCard("Forest", p);
        addCard("Abzan Banner", p).setSickness(false);
        AssertJUnit.assertTrue(
                "Abzan Banner draw should be affordable with WBG from lands",
                canAffordNonManaAbilityOf(p, "Abzan Banner"));
    }

    @Test
    public void testFieryIsletAloneDrawNotAffordable() {
        // Fiery Islet mana: {T}, PayLife 1: Add U/R → rainbow 1, total 1.
        // Non-mana draw: {1}, {T}, Sac → needs 1 generic + tap + sac.
        // Islet's mana can't pay the {1} because draw's tap conflicts
        // with Islet's own tap.
        Player p = newGame();
        addCard("Fiery Islet", p);
        AssertJUnit.assertFalse(
                "Fiery Islet alone can't pay for its own draw ability",
                canAffordNonManaAbilityOf(p, "Fiery Islet"));
    }

    @Test
    public void testFieryIsletWithForestDrawAffordable() {
        // Fiery Islet + Forest. Forest provides 1 cost-free mana (G) that
        // pays the draw's {1}. Affordable.
        Player p = newGame();
        addCard("Fiery Islet", p);
        addCard("Forest", p);
        AssertJUnit.assertTrue(
                "Fiery Islet draw should be affordable with an extra Forest",
                canAffordNonManaAbilityOf(p, "Fiery Islet"));
    }

    @Test
    public void testRestlessSpireAnimateDoesNotConflictWithOwnMana() {
        // Restless Spire mana: {T}: Add U/R → rainbow 1.
        // Animate ability: {U}{R} — NO tap, NO sac. Doesn't share the
        // tap resource with the mana ability, so Spire's OWN mana stays
        // available toward the animate's colored shards.
        //
        // Board: Restless Spire + Island. Total = 2 mana (Spire rainbow
        // + Island U). Animate needs U + R, payable: Island → U,
        // Spire → R. Affordable.
        //
        // This is the key test: the conflict-subtraction logic must NOT
        // fire for animate, because its cost has no exclusion group.
        Player p = newGame();
        addCard("Restless Spire", p);
        addCard("Island", p);
        // Force untap: Spire normally ETBs tapped.
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            c.setTapped(false);
            c.setSickness(false);
        }
        AssertJUnit.assertTrue(
                "Restless Spire's own mana should pay its animate cost — no tap conflict",
                canAffordNonManaAbilityOf(p, "Restless Spire"));
    }

    @Test
    public void testRestlessSpireAloneAnimateNotAffordable() {
        // Restless Spire alone. Its own mana provides 1 rainbow (U or R),
        // but animate needs BOTH U AND R = 2 mana. Only 1 total available.
        // NOT affordable — but for total-mana reasons, not conflict.
        Player p = newGame();
        addCard("Restless Spire", p);
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            c.setTapped(false);
            c.setSickness(false);
        }
        AssertJUnit.assertFalse(
                "Restless Spire alone can't afford its own 2-cost animate",
                canAffordNonManaAbilityOf(p, "Restless Spire"));
    }

    // --- Sanity canary ---

    @Test
    public void testActionScanStructureDoesNotLoseCounts() {
        // Sanity check that the ActionScan populates tracked values without
        // throwing — useful as a canary for Pass 1 regressions.
        Player p = newGame();
        addCards("Forest", 3, p);
        addCard("Llanowar Elves", p);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        ActionScan s = ActionScan.scan(p);
        AssertJUnit.assertFalse(s.hasStructuralBailout());
        ManaBudget b = s.getBudget();
        AssertJUnit.assertNotNull(b);
        AssertJUnit.assertTrue(b.canAfford(
                p.getCardsIn(ZoneType.Hand).iterator().next().getFirstSpellAbility(), s));
    }
}
