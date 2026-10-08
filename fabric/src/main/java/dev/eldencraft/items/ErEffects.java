package dev.eldencraft.items;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Elden Ring's SpEffects as far as passive equipment uses them: the multipliers and bonuses on a talisman, a piece
 * of armour or a weapon's resident effect, added up for a player. Fields mean what they mean in SpEffectParam.
 */
public final class ErEffects {
	private ErEffects() {
	}

	private static final String[] ATTACK = { "physicsAttackRate", "magicAttackRate", "fireAttackRate", "thunderAttackRate", "darkAttackRate" };
	private static final String[] POWER_RATE = { "physicsAttackPowerRate", "magicAttackPowerRate", "fireAttackPowerRate",
		"thunderAttackPowerRate", "darkAttackPowerRate" };
	private static final String[] POWER = { "physicsAttackPower", "magicAttackPower", "fireAttackPower", "thunderAttackPower", "darkAttackPower" };
	private static final String[] STATUS = { "poizonAttackPower", "diseaseAttackPower", "bloodAttackPower", "freezeAttackPower",
		"sleepAttackPower", "madnessAttackPower", "curseAttackPower" };
	private static final String[] ENEMY_DEFENCE = { "defEnemyDmgCorrectRate_Physics", "defEnemyDmgCorrectRate_Magic",
		"defEnemyDmgCorrectRate_Fire", "defEnemyDmgCorrectRate_Thunder", "defEnemyDmgCorrectRate_Dark" };
	private static final String[] CUT = { "neutralDamageCutRate", "blowDamageCutRate", "slashDamageCutRate", "thrustDamageCutRate",
		"magicDamageCutRate", "fireDamageCutRate", "thunderDamageCutRate", "darkDamageCutRate" };
	private static final String[] ADD = { "addLifeForceStatus", "addWillpowerStatus", "addEndureStatus", "addStrengthStatus",
		"addDexterityStatus", "addMagicStatus", "addFaithStatus", "addLuckStatus" };
	private static final String[] RESIST = { "changePoisonResistPoint", "changeDiseaseResistPoint", "changeBloodResistPoint",
		"changeFreezeResistPoint", "changeSleepResistPoint", "changeMadnessResistPoint", "changeCurseResistPoint" };

	/** Everything a player's equipment adds up to. Rates multiply (1 = no change); the rest add. */
	public static final class Totals {
		/** Outgoing damage by element: physical, magic, fire, lightning, holy. */
		public final double[] attack = { 1, 1, 1, 1, 1 };
		/** Incoming damage from enemies by element (Dragoncrest-style talismans). */
		public final double[] taken = { 1, 1, 1, 1, 1 };
		/** Weapon buffs (greases, Flame Grant Me Strength...): attack power rates and flat attack power by element. */
		public final double[] powerRate = { 1, 1, 1, 1, 1 };
		public final double[] power = new double[5];
		/** Status buildup added to each weapon hit: poison, rot, bleed, frost, sleep, madness, deathblight. */
		public final double[] status = new double[7];
		/** Incoming damage by Elden Ring's eight absorption types (Radagon's Soreseal and the like raise it). */
		public final double[] cut = { 1, 1, 1, 1, 1, 1, 1, 1 };
		public final int[] attributes = new int[8];
		public final int[] resist = new int[7];
		public double maxHp = 1, maxFp = 1, maxStamina = 1, equipLoad = 1;
		public double hpRegen, fpRegen;
		/** Extra stamina recovery per second (Green Turtle Talisman and the like). */
		public double staminaRegen;
		/** Effects we read but do not reproduce (conditional or scripted), by name, for the tooltip and logs. */
		public final List<String> unhandled = new ArrayList<>();

		public void add(int effectId) {
			JsonObject e = ErData.effect(effectId);
			if (e == null) {
				return;
			}
			for (int k = 0; k < 5; k++) {
				attack[k] *= rate(e, ATTACK[k]);
				taken[k] *= rate(e, ENEMY_DEFENCE[k]);
				powerRate[k] *= rate(e, POWER_RATE[k]);
				power[k] += ErData.d(e, POWER[k]);
			}
			for (int k = 0; k < 8; k++) {
				cut[k] *= rate(e, CUT[k]);
				attributes[k] += ErData.i(e, ADD[k]);
			}
			for (int k = 0; k < 7; k++) {
				resist[k] += ErData.i(e, RESIST[k]);
				status[k] += ErData.d(e, STATUS[k]);
			}
			maxHp *= rate(e, "maxHpRate");
			maxFp *= rate(e, "maxMpRate");
			maxStamina *= rate(e, "maxStaminaRate");
			equipLoad *= rate(e, "equipWeightChangeRate");
			staminaRegen += ErData.d(e, "staminaRecoverChangeSpeed");
			// Periodic effects: a cycle effect that restores HP or FP every interval (Erdtree's Favor's regen, etc.).
			int cycle = ErData.i(e, "cycleOccurrenceSpEffectId");
			double interval = Math.max(0.05, ErData.d(e, "motionInterval"));
			if (cycle > 0) {
				JsonObject c = ErData.effect(cycle);
				if (c != null) {
					hpRegen += -ErData.d(c, "changeHpPoint") / interval;
					fpRegen += -ErData.d(c, "changeMpPoint") / interval;
				}
			}
			// Conditional effects (on kill, on critical, at low HP...) are scripted by the game.
			if (ErData.i(e, "stateInfo") != 0 || ErData.i(e, "spCategory") == 20 || ErData.i(e, "accumuOverFireId") > 0) {
				unhandled.add(ErData.s(e, "_n"));
			}
		}

		private static double rate(JsonObject e, String key) {
			return e.has(key) ? e.get(key).getAsDouble() : 1.0;
		}
	}

	/** Equipped talismans and worn Elden Ring armour (and the held weapons' resident effects). */
	public static Totals of(Player player) {
		Totals totals = new Totals();
		ErPlayer data = ErPlayer.of(player);
		List<Integer> groups = new ArrayList<>();
		for (int k = 0; k < data.talismanSlots(); k++) {
			ErRef ref = ErItems.ref(data.talismans.get(k));
			JsonObject t = ref == null ? null : ErData.talisman(ref.id());
			if (t == null) {
				continue;
			}
			// Talismans of one group (a talisman and its +1, +2) do not stack: only the first counts.
			int group = ErData.i(t, "grp");
			if (group > 0 && groups.contains(group)) {
				continue;
			}
			groups.add(group);
			totals.add(ErData.i(t, "sp"));
		}
		for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			ErRef ref = ErItems.ref(player.getItemBySlot(slot));
			JsonObject a = ref == null || ref.kind() != ErRef.ARMOUR ? null : ErData.armour(ref.id());
			if (a != null) {
				for (int sp : ErData.ints(a, "sp", 3)) totals.add(sp);
			}
		}
		for (ItemStack held : new ItemStack[] { player.getMainHandItem(), player.getOffhandItem() }) {
			JsonObject w = ErItems.weaponRow(held);
			if (w != null) {
				for (int sp : ErData.ints(w, "res", 3)) totals.add(sp);
			}
		}
		ErBuffs.addActive(player, totals);
		return totals;
	}

	/** One line describing a talisman's or armour piece's effect, from the numbers in its SpEffect. */
	public static List<String> describe(int effectId) {
		List<String> out = new ArrayList<>();
		JsonObject e = ErData.effect(effectId);
		if (e == null) {
			return out;
		}
		Totals t = new Totals();
		t.add(effectId);
		String[] elements = { "physical", "magic", "fire", "lightning", "holy" };
		boolean allAttack = true;
		for (int k = 1; k < 5; k++) allAttack &= t.attack[k] == t.attack[0];
		if (allAttack && t.attack[0] != 1) {
			out.add(percent(t.attack[0]) + " attack");
		} else {
			for (int k = 0; k < 5; k++) if (t.attack[k] != 1) out.add(percent(t.attack[k]) + " " + elements[k] + " attack");
		}
		for (int k = 0; k < 5; k++) if (t.taken[k] != 1) out.add(percent(t.taken[k]) + " " + elements[k] + " damage taken");
		boolean allCut = true;
		for (int k = 1; k < 8; k++) allCut &= t.cut[k] == t.cut[0];
		String[] cuts = { "physical", "strike", "slash", "pierce", "magic", "fire", "lightning", "holy" };
		if (allCut && t.cut[0] != 1) {
			out.add(percent(t.cut[0]) + " damage taken");
		} else {
			for (int k = 0; k < 8; k++) if (t.cut[k] != 1) out.add(percent(t.cut[k]) + " " + cuts[k] + " damage taken");
		}
		for (int k = 0; k < 8; k++) if (t.attributes[k] != 0) out.add((t.attributes[k] > 0 ? "+" : "") + t.attributes[k] + " " + ErPlayer.NAMES[k]);
		String[] resists = { "immunity (poison)", "immunity (rot)", "robustness (bleed)", "robustness (frost)", "focus (sleep)", "focus (madness)", "vitality" };
		for (int k = 0; k < 7; k++) if (t.resist[k] != 0) out.add("+" + t.resist[k] + " " + resists[k]);
		if (t.maxHp != 1) out.add(percent(t.maxHp) + " max HP");
		if (t.maxFp != 1) out.add(percent(t.maxFp) + " max FP");
		if (t.maxStamina != 1) out.add(percent(t.maxStamina) + " max stamina");
		if (t.equipLoad != 1) out.add(percent(t.equipLoad) + " equip load");
		if (t.staminaRegen != 0) out.add(String.format("%+.0f stamina recovery a second", t.staminaRegen));
		if (t.hpRegen > 0) out.add(String.format("Restores %.1f HP a second", t.hpRegen));
		if (t.fpRegen > 0) out.add(String.format("Restores %.1f FP a second", t.fpRegen));
		if (out.isEmpty() || !t.unhandled.isEmpty()) out.add("Special effect (not yet reproduced)");
		return out;
	}

	private static String percent(double rate) {
		long p = Math.round((rate - 1) * 100);
		return (p > 0 ? "+" : "") + p + "%";
	}
}
