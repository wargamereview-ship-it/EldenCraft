package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.*;

import dev.eldencraft.combat.ElementalRules.Element;
import dev.eldencraft.combat.ElementalRules.Status;
import org.junit.jupiter.api.Test;

class ElementalRulesTest {
	@Test void everyEnchantmentHasOneIdAndNoneAreShared() {
		var ids = java.util.Set.of(ElementalRules.allIds());
		assertEquals(8, ids.size());
		for (Element e : Element.values()) assertSame(e, ElementalRules.element(e.id));
		for (Status s : Status.values()) assertSame(s, ElementalRules.status(s.id));
		assertNull(ElementalRules.element("poison"));
		assertNull(ElementalRules.status("fire"));
	}

	@Test void elementsAddADamageShareThatGrowsWithLevelAndAffinity() {
		assertEquals(0.12F * 10, ElementalRules.bonus(10, 1, 1.0F), 1e-5);
		assertEquals(0.60F * 10, ElementalRules.bonus(10, 5, 1.0F), 1e-5);
		assertEquals(1.5F * ElementalRules.bonus(10, 3, 1.0F), ElementalRules.bonus(10, 3, 1.5F), 1e-5);
		assertEquals(0.0F, ElementalRules.bonus(0, 5, 1.0F));
		assertEquals(0.0F, ElementalRules.bonus(10, 0, 1.0F));
		assertEquals(0.0F, ElementalRules.bonus(Float.NaN, 3, 1.0F));
	}

	@Test void familiesHaveSensibleWeaknesses() {
		assertTrue(ElementalRules.affinity("undead", Element.HOLY) > 1.2F);
		assertTrue(ElementalRules.affinity("plant", Element.FIRE) > 1.2F);
		assertTrue(ElementalRules.affinity("dragon", Element.FIRE) < 1.0F);
		assertEquals(1.0F, ElementalRules.affinity("unknown", Element.FIRE));
		assertEquals(1.0F, ElementalRules.affinity(null, Element.HOLY));
		for (String family : LootRules.FAMILIES) for (Element e : Element.values()) {
			float a = ElementalRules.affinity(family, e);
			assertTrue(a >= 0.5F && a <= 1.6F, family + " " + e);
		}
	}

	@Test void rewardLevelsFollowTheTier() {
		int[] expected = { 1, 1, 2, 3, 4, 5, 5 };
		for (int tier = 1; tier <= 7; tier++) assertEquals(expected[tier - 1], ElementalRules.levelForTier(tier));
	}

	private static StatusMeters.Proc hitUntilProc(StatusMeters meters, Status status, int level, long start, float maxHp, boolean boss) {
		long now = start;
		for (int hits = 0; hits < 40; hits++, now += 10) {
			var proc = meters.add(status, ElementalRules.buildup(level), now, maxHp, boss);
			if (proc != null) return proc;
		}
		return null;
	}

	@Test void aStatusTakesHoldOnlyWhenTheMeterFills() {
		var meters = new StatusMeters();
		assertNull(meters.add(Status.HEMORRHAGE, ElementalRules.buildup(1), 0, 100, false));
		var proc = hitUntilProc(new StatusMeters(), Status.HEMORRHAGE, 1, 0, 100, false);
		assertNotNull(proc);
		assertEquals(12.0F, proc.burst(), 1e-4); // 12% of 100
	}

	@Test void higherLevelsFillTheMeterInFewerHits() {
		int[] hits = new int[2];
		for (int i = 0; i < 2; i++) {
			var meters = new StatusMeters();
			int level = i == 0 ? 1 : 5;
			while (meters.add(Status.POISON, ElementalRules.buildup(level), hits[i] * 10L, 100, false) == null) hits[i]++;
		}
		assertTrue(hits[1] < hits[0], hits[0] + " vs " + hits[1]);
	}

	@Test void bossesResistAndRepeatsAreHarder() {
		int ordinary = 0, boss = 0;
		var a = new StatusMeters();
		while (a.add(Status.HEMORRHAGE, 22.0F, ordinary * 10L, 100, false) == null) ordinary++;
		var b = new StatusMeters();
		while (b.add(Status.HEMORRHAGE, 22.0F, boss * 10L, 100, true) == null) boss++;
		assertTrue(boss > ordinary * 2);
		// The second proc on the same enemy, soon after, needs more hits than the first.
		var c = new StatusMeters();
		int first = 0;
		while (c.add(Status.HEMORRHAGE, 22.0F, first * 10L, 100, false) == null) first++;
		int second = 0;
		while (c.add(Status.HEMORRHAGE, 22.0F, (first + second + 1) * 10L, 100, false) == null) second++;
		assertTrue(second > first);
	}

	@Test void aMeterDrainsWhenHitsStop() {
		var meters = new StatusMeters();
		assertNull(meters.add(Status.FROSTBITE, 70.0F, 0, 100, false));
		// A minute later 70 has drained away: one more small hit cannot finish it.
		assertNull(meters.add(Status.FROSTBITE, 40.0F, 60L * 20, 100, false));
		assertTrue(meters.fill(Status.FROSTBITE, 60L * 20, false) < 0.5F);
	}

	@Test void poisonAndRotTickOncePerSecondForEighteenSeconds() {
		var meters = new StatusMeters();
		var proc = hitUntilProc(meters, Status.POISON, 5, 20, 1000, false);
		assertNotNull(proc);
		assertEquals(0.0F, proc.burst());
		long start = 40; // just after the second hit (tick 30) filled the meter
		float total = 0.0F;
		int ticks = 0;
		for (long t = start; t < start + 20L * 30; t++) { // 30 s, well past the 18 s effect
			float dot = meters.tick(t);
			if (dot > 0) { ticks++; total += dot; }
			if (t % 20 != 0) assertEquals(0.0F, dot);
		}
		assertTrue(ticks >= 17 && ticks <= 19, "ticks " + ticks);
		assertEquals(0.008F * 1000 * ticks, total, 0.1F * ticks);
		// Rot hits harder than poison.
		var rot = new StatusMeters();
		hitUntilProc(rot, Status.ROT, 5, 20, 1000, false);
		float poison = 0, rotDamage = 0;
		for (long t = start; t < start + 100; t++) { poison += meters.tick(t); rotDamage += rot.tick(t); }
		assertTrue(rotDamage > poison);
	}

	@Test void frostbiteLeavesTheEnemyVulnerableForAWhile() {
		var meters = new StatusMeters();
		assertEquals(1.0F, meters.amplify(0));
		long now = 0;
		StatusMeters.Proc proc = null;
		while (proc == null) { proc = meters.add(Status.FROSTBITE, 34.0F, now, 200, false); now += 10; }
		assertEquals(StatusMeters.FROST_AMPLIFY, meters.amplify(now));
		assertEquals(1.0F, meters.amplify(now + StatusMeters.FROST_TICKS + 1));
		assertEquals(12.0F, proc.burst(), 1e-4); // 6% of 200
	}
}
