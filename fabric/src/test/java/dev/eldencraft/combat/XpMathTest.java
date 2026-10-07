package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class XpMathTest {
	@Test
	void levelsMatchMinecraftsCurve() {
		assertEquals(0, XpMath.toReach(0));
		assertEquals(7, XpMath.toReach(1));
		assertEquals(160, XpMath.toReach(10));
		assertEquals(352, XpMath.toReach(16));
		assertEquals(1395, XpMath.toReach(30));
		assertEquals(2920, XpMath.toReach(40));
		assertEquals(7, XpMath.needed(0));
		assertEquals(37, XpMath.needed(15));
		assertEquals(112, XpMath.needed(30));
	}

	@Test
	void theBarCountsTowardPoints() {
		assertEquals(160 + 10, XpMath.points(10, 10.0F / XpMath.needed(10)));
		assertEquals(0, XpMath.points(0, 0.0F));
	}

	@Test
	void aPileIsCollectedWithinReachOnly() {
		assertTrue(XpPile.reaches(1.0, 0.5, 1.0));
		assertFalse(XpPile.reaches(3.0, 0.0, 0.0));
		assertFalse(XpPile.reaches(0.0, 5.0, 0.0));
	}
}
