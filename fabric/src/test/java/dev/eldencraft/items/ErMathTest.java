package dev.eldencraft.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Elden Ring's formulas against the game's own numbers, and the shipped item tables. */
class ErMathTest {
	@Test
	void attributeCurvesMatchTheGame() {
		assertEquals(414, (int) ErStats.hp(10));
		assertEquals(1450, (int) Math.round(ErStats.hp(40)));
		assertEquals(1900, (int) Math.round(ErStats.hp(60)));
		assertEquals(2100, (int) Math.round(ErStats.hp(99)));
		assertEquals(78, (int) ErStats.fp(10));
		assertEquals(45.0, ErStats.equipLoad(8), 1e-9);
		assertEquals(72.0, ErStats.equipLoad(25), 1e-9);
		assertEquals(673, ErStats.runeCost(1));
		// Stamina: Endurance 10 = 96, 30 = 130, 50 = 155, 99 = 170.
		assertEquals(96, (int) ErStats.stamina(10));
		assertEquals(130.0, ErStats.stamina(30), 1e-9);
		assertEquals(155.0, ErStats.stamina(50), 1e-9);
		assertEquals(170.0, ErStats.stamina(99), 1e-9);
		assertTrue(ErStats.runeCost(100) > ErStats.runeCost(50));
	}

	@Test
	void tablesLoad() {
		assertTrue(ErData.weapons().size() > 3000);
		assertTrue(ErData.armour().size() > 700);
		assertTrue(ErData.talismans().size() > 150);
		assertTrue(ErData.goods().size() > 2000);
		assertNotNull(ErData.weapon(1000000), "Dagger");
		assertEquals("Dagger", ErData.s(ErData.weapon(1000000), "n"));
		assertEquals("Crimson Amber Medallion", ErData.s(ErData.talisman(1000), "n"));
	}

	@Test
	void daggerAttackRating() {
		var dagger = ErData.weapon(1000000);
		ErEffects.Totals none = new ErEffects.Totals();
		int[] tens = { 10, 10, 10, 10, 10, 10, 10, 10 };
		double[] ar = ErWeapons.attackRating(dagger, 0, tens, none, false);
		// 74 physical at +0, plus Strength and Dexterity scaling (E/D): a little over the base.
		assertTrue(ar[0] > 74 && ar[0] < 100, "dagger physical " + ar[0]);
		assertEquals(0.0, ar[1] + ar[2] + ar[3] + ar[4], 1e-9);
		// Upgrades raise it; +25 is the top for a standard weapon.
		assertEquals(25, ErWeapons.maxLevel(dagger));
		assertTrue(ErWeapons.total(ErWeapons.attackRating(dagger, 25, tens, none, false)) > 2 * ErWeapons.total(ar));
		// Below a requirement the element drops to 60% without scaling.
		int[] weak = { 10, 10, 10, 1, 1, 10, 10, 10 };
		assertEquals(74 * 0.6, ErWeapons.attackRating(dagger, 0, weak, none, false)[0], 1e-6);
		// Correction 35 / 65: Elden Ring's letters start D at 25 and C at 60.
		assertEquals("D", ErWeapons.letter(dagger, 0, 0));
		assertEquals("C", ErWeapons.letter(dagger, 0, 1));
	}

	@Test
	void upgradeCostsUseSmithingStones() {
		var dagger = ErData.weapon(1000000);
		var first = ErWeapons.upgradeCost(dagger, 0).get(0).getAsJsonArray();
		assertEquals(10100, first.get(0).getAsInt(), "Smithing Stone [1]");
		assertEquals(2, first.get(1).getAsInt());
		var last = ErWeapons.upgradeCost(dagger, 24).get(0).getAsJsonArray();
		assertEquals(10140, last.get(0).getAsInt(), "Ancient Dragon Smithing Stone");
	}

	@Test
	void talismanEffectsAreRead() {
		ErEffects.Totals t = new ErEffects.Totals();
		t.add(ErData.i(ErData.talisman(1000), "sp"));
		assertEquals(1.06, t.maxHp, 1e-6, "Crimson Amber Medallion");
		assertTrue(ErEffects.describe(ErData.i(ErData.talisman(1000), "sp")).contains("+6% max HP"));
	}

	@Test
	void flasksHealAsTheGame() {
		var full = ErData.goods(1001);
		assertEquals(-250, ErData.i(ErData.effect(ErData.i(full, "ref")), "changeHpEstusFlaskPoint"));
		var best = ErData.goods(1025);
		assertEquals(-810, ErData.i(ErData.effect(ErData.i(best, "ref")), "changeHpEstusFlaskPoint"));
	}

	@Test
	void everyItemHasArt() {
		java.util.Set<String> arts = new java.util.TreeSet<>();
		for (var w : ErData.weapons().values()) arts.add("weapon/" + ErData.s(w, "art"));
		for (var g : ErData.goods().values()) arts.add("goods/" + ErGoods.art(g));
		for (String slot : new String[] { "helm", "chest", "gauntlets", "legs" })
			for (String weight : new String[] { "light", "medium", "heavy" }) arts.add("armour/" + slot + "_" + weight);
		arts.add("misc/talisman");
		arts.add("goods/ash_of_war");
		for (String art : arts) {
			assertNotNull(ErMathTest.class.getResource("/assets/eldencraft/items/er/" + art + ".json"), "item model for " + art);
		}
	}

	@Test
	void merchantsSellRealItems() {
		assertEquals(100500, ErShops.lineupFor(32000000), "Kale");
		assertEquals(100650, ErShops.lineupFor(32001612), "Isolated Merchant, Weeping Peninsula");
		assertEquals(100975, ErShops.lineupFor(32002968), "Imprisoned Merchant");
		assertEquals(100550, ErShops.lineupFor(32003010), "Nomadic Merchant [2], second spot");
		assertEquals(0, ErShops.lineupFor(32009100), "merchant NPC that never trades");
		int missing = 0, total = 0;
		for (int base = 100500; base <= 100975; base += 25) {
			var lineup = ErData.shop(base);
			if (lineup == null) continue;
			for (var e : lineup) {
				var o = e.getAsJsonObject();
				int kind = ErData.i(o, "k"), id = ErData.i(o, "id");
				total++;
				boolean found = switch (kind) {
					case ErRef.WEAPON -> ErData.weapon(id) != null;
					case ErRef.ARMOUR -> ErData.armour(id) != null;
					case ErRef.TALISMAN -> ErData.talisman(id) != null;
					case ErRef.ASH -> ErData.ash(id) != null;
					default -> ErData.goods(id) != null;
				};
				if (!found) missing++;
			}
		}
		assertTrue(total > 200, "lineups " + total);
		assertEquals(0, missing, "shop items without a table row");
		assertEquals(11, ErShops.price(ErData.shop(100500).get(0).getAsJsonObject()), "200 runes");
	}

	@Test
	void spellsCarryTheirAttack() {
		var pebble = ErData.spell(4000);
		assertEquals("Glintstone Pebble", ErData.s(pebble, "n"));
		assertEquals(152.0, ErData.arr(pebble, "dmg", 5)[1], 1e-9);
		assertEquals(7, ErData.i(pebble, "fp"));
	}
}
