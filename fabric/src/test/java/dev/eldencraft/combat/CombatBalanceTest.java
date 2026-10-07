package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombatBalanceTest {
	@Test
	void laterTiersTakeAShrinkingShareOfTheDamage() {
		for (int tier = 2; tier <= 7; tier++) {
			assertTrue(CombatBalance.damageFactor(tier) < CombatBalance.damageFactor(tier - 1), "tier " + tier);
		}
		assertTrue(CombatBalance.damageFactor(7) > 0.2F, "the last tier must stay killable");
		assertTrue(CombatBalance.damageFactor(1) < 1.0F, "the player no longer hits at full strength");
	}

	@Test
	void unknownTiersAreClamped() {
		assertEquals(CombatBalance.damageFactor(1), CombatBalance.damageFactor(0));
		assertEquals(CombatBalance.damageFactor(7), CombatBalance.damageFactor(99));
	}
}
