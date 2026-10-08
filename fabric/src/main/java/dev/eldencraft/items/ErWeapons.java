package dev.eldencraft.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlocksAttacks;

/**
 * Elden Ring's attack rating, computed as the game does: the weapon's base damage times its upgrade level's rate, plus
 * scaling from each attribute that element scales with, read off the weapon's correction graph. An unmet requirement
 * cuts that element to 60% with no scaling. Two-handing counts Strength one and a half times.
 */
public final class ErWeapons {
	private ErWeapons() {
	}

	/**
	 * Minecraft damage per point of attack rating. The bridge turns each Minecraft damage point into 25 Elden Ring HP;
	 * Elden Ring's defences take roughly a fifth of a light attack's rating off ordinary enemies, so 0.8 / 25.
	 */
	public static final double AR_TO_MC = 0.8 / 25.0;
	/** Scaling letters, as Elden Ring shows them, from the weapon's correction value. */
	private static final String[] LETTERS = { "S", "A", "B", "C", "D", "E" };
	private static final double[] LETTER_AT = { 175, 140, 90, 60, 25, 1 };
	public static final String[] ELEMENTS = { "Physical", "Magic", "Fire", "Lightning", "Holy" };
	public static final String[] STATS = { "Str", "Dex", "Int", "Fai", "Arc" };

	public static int maxLevel(JsonObject w) {
		JsonObject r = ErData.reinforce(ErData.i(w, "rt"));
		if (ErData.s(w, "b").equals("ammo")) {
			return 0;
		}
		int max = 0;
		for (int level = 1; level <= 25; level++) {
			if (ErData.reinforce(ErData.i(w, "rt") + level) == null) break;
			max = level;
		}
		return r == null ? 0 : max;
	}

	/** The five attributes weapons scale with (Str, Dex, Int, Fai, Arc), from the player's eight. */
	public static int[] scalingStats(int[] attrs, boolean twoHanded, JsonObject w) {
		int str = attrs[ErPlayer.STRENGTH];
		if (twoHanded && ErData.i(w, "dual") == 0) {
			str = Math.min(99, (int) Math.floor(str * 1.5));
		}
		return new int[] { str, attrs[ErPlayer.DEXTERITY], attrs[ErPlayer.INTELLIGENCE], attrs[ErPlayer.FAITH], attrs[ErPlayer.ARCANE] };
	}

	/** Attack rating by element (physical, magic, fire, lightning, holy). */
	public static double[] attackRating(JsonObject w, int level, int[] attrs, ErEffects.Totals totals, boolean twoHanded) {
		int rt = ErData.i(w, "rt");
		JsonObject r = ErData.reinforce(rt + Math.clamp(level, 0, maxLevel(w)));
		if (r == null) r = ErData.reinforce(rt);
		double[] baseAtk = ErData.arr(w, "atk", 5), rateAtk = r == null ? new double[] { 1, 1, 1, 1, 1 } : ErData.arr(r, "atk", 5);
		double[] cor = ErData.arr(w, "cor", 5), rateCor = r == null ? new double[] { 1, 1, 1, 1, 1 } : ErData.arr(r, "cor", 5);
		int[] req = ErData.ints(w, "req", 5), graphs = ErData.ints(w, "ct", 9);
		int[] stats = scalingStats(attrs, twoHanded, w);
		JsonArray aec = ErData.elementCorrect(ErData.i(w, "aec"));
		double[] out = new double[5];
		for (int e = 0; e < 5; e++) {
			double base = baseAtk[e] * rateAtk[e];
			if (base <= 0) {
				continue;
			}
			double bonus = 0;
			boolean unmet = false;
			for (int s = 0; s < 5; s++) {
				if (aec == null || aec.get(e).getAsJsonArray().get(s).getAsInt() == 0) {
					continue;
				}
				if (stats[s] < req[s]) {
					unmet = true;
				}
				bonus += base * cor[s] * rateCor[s] / 100.0 * graph(graphs[e], stats[s]) / 100.0;
			}
			out[e] = unmet ? base * 0.6 : base + bonus;
			out[e] = (out[e] * totals.powerRate[e] + totals.power[e]) * totals.attack[e];
		}
		return out;
	}

	/** A correction graph (CalcCorrectGraph) at an attribute value, in percent. */
	public static double graph(int id, int stat) {
		JsonObject g = ErData.graph(id);
		if (g == null) {
			return 0;
		}
		double[] x = ErData.arr(g, "x", 5), y = ErData.arr(g, "y", 5), exp = ErData.arr(g, "e", 5);
		if (stat <= x[0]) return y[0];
		for (int i = 0; i < 4; i++) {
			if (stat <= x[i + 1]) {
				double ratio = x[i + 1] == x[i] ? 1 : (stat - x[i]) / (x[i + 1] - x[i]);
				double grow = exp[i] > 0 ? Math.pow(ratio, exp[i]) : 1 - Math.pow(1 - ratio, -exp[i]);
				return y[i] + (y[i + 1] - y[i]) * grow;
			}
		}
		return y[4];
	}

	public static double total(double[] ar) {
		double t = 0;
		for (double v : ar) t += v;
		return t;
	}

	public static double toMinecraft(double[] ar) {
		return total(ar) * AR_TO_MC;
	}

	/** Which requirements the attributes miss (bit per Str, Dex, Int, Fai, Arc). */
	public static int unmet(JsonObject w, int[] attrs, boolean twoHanded) {
		int[] req = ErData.ints(w, "req", 5), stats = scalingStats(attrs, twoHanded, w);
		int out = 0;
		for (int s = 0; s < 5; s++) if (stats[s] < req[s]) out |= 1 << s;
		return out;
	}

	/** Scaling letter for one attribute at an upgrade level ("-" when the weapon does not scale with it). */
	public static String letter(JsonObject w, int level, int stat) {
		JsonObject r = ErData.reinforce(ErData.i(w, "rt") + level);
		double value = ErData.arr(w, "cor", 5)[stat] * (r == null ? 1 : ErData.arr(r, "cor", 5)[stat]);
		for (int k = 0; k < LETTERS.length; k++) if (value >= LETTER_AT[k]) return LETTERS[k];
		return "-";
	}

	/**
	 * Status buildup a hit adds (poison, rot, bleed, frost, sleep, madness, deathblight), from the weapon's on-hit
	 * effects at its upgrade level, with Arcane scaling for the statuses that scale.
	 */
	public static double[] buildup(JsonObject w, int level, int[] attrs) {
		double[] out = new double[7];
		JsonObject r = ErData.reinforce(ErData.i(w, "rt") + level);
		int[] offsets = r == null ? new int[3] : ErData.ints(r, "sp", 3);
		int[] effects = ErData.ints(w, "sp", 3);
		ErEffects.Totals t = new ErEffects.Totals();
		for (int k = 0; k < 3; k++) {
			if (effects[k] > 0) t.add(effects[k] + Math.max(0, offsets[k]));
		}
		double arcane = ErData.arr(w, "cor", 5)[4] * (r == null ? 1 : ErData.arr(r, "cor", 5)[4]);
		int[] graphs = ErData.ints(w, "ct", 9);
		// Poison, bleed, sleep and madness scale with Arcane through their own graphs (correctType_Poison...).
		int[][] scaled = { { 0, 5 }, { 2, 6 }, { 4, 7 }, { 5, 8 } };
		for (int k = 0; k < 7; k++) out[k] = t.status[k];
		if (arcane > 0) {
			for (int[] s : scaled) {
				out[s[0]] *= 1 + arcane / 100.0 * graph(graphs[s[1]], attrs[ErPlayer.ARCANE]) / 100.0;
			}
		}
		return out;
	}

	public static double attackSpeed(int type) {
		return switch (type) {
			case 1, 91 -> 2.0;
			case 35, 37, 88, 95 -> 2.2;
			case 3, 92 -> 1.6;
			case 9 -> 1.7;
			case 13, 14 -> 1.5;
			case 15 -> 1.8;
			case 16, 17, 21, 24, 93 -> 1.25;
			case 25, 39, 87 -> 1.3;
			case 5, 11, 29, 31, 94 -> 1.0;
			case 19, 23, 28 -> 0.85;
			case 7 -> 0.75;
			case 41 -> 0.65;
			default -> 1.2;
		};
	}

	/** Extra reach in blocks over Minecraft's 3, by weapon class. */
	public static double reach(int type) {
		return switch (type) {
			case 1, 35, 37, 88, 91, 95 -> -0.5;
			case 5, 11, 14, 93, 94 -> 0.5;
			case 7, 41, 25, 31 -> 1.0;
			case 29 -> 1.25;
			case 28 -> 1.5;
			case 39 -> 2.0;
			default -> 0;
		};
	}

	/** A shield's block: its physical guard share stops that much of a blocked hit. */
	public static BlocksAttacks blocking(JsonObject w, int level) {
		BlocksAttacks shield = Items.SHIELD.getDefaultInstance().get(DataComponents.BLOCKS_ATTACKS);
		JsonObject r = ErData.reinforce(ErData.i(w, "rt") + level);
		double guard = ErData.arr(w, "guard", 5)[0] * (r == null ? 1 : ErData.arr(r, "guard", 5)[0]);
		float factor = (float) Math.clamp(guard / 100.0, 0.0, 1.0);
		var reduction = new BlocksAttacks.DamageReduction(90.0F, Optional.empty(), 0.0F, factor);
		return shield == null
			? new BlocksAttacks(0.25F, 1.0F, List.of(reduction), BlocksAttacks.ItemDamageFunction.DEFAULT, Optional.empty(), Optional.empty(), Optional.empty())
			: new BlocksAttacks(shield.blockDelaySeconds(), shield.disableCooldownScale(), List.of(reduction), shield.itemDamage(),
				shield.bypassedBy(), shield.blockSound(), shield.disableSound());
	}

	/** Smithing stones for the next upgrade: [goods id, count] pairs, or null at the top level. */
	public static JsonArray upgradeCost(JsonObject w, int level) {
		JsonObject r = ErData.reinforce(ErData.i(w, "rt") + level + 1);
		if (r == null || level + 1 > maxLevel(w)) {
			return null;
		}
		return ErData.materials(ErData.i(w, "ms") + ErData.i(r, "ms"));
	}
}
