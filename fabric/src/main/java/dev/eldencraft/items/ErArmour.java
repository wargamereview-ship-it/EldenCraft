package dev.eldencraft.items;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * Elden Ring armour against Elden Ring's hits: each worn piece's damage negation multiplies what is left of a hit,
 * per element, as the game stacks it; talisman cut rates and "damage taken" rates apply on top.
 */
public final class ErArmour {
	private ErArmour() {
	}

	/** Light, medium or heavy, for the art and the equip sound (by the piece's weight for its slot). */
	public static int weightClass(int slot, double weight) {
		double[][] limits = { { 4, 7 }, { 10, 20 }, { 3.5, 6 }, { 5, 10 } };
		double[] l = limits[Math.clamp(slot, 0, 3)];
		return weight < l[0] ? 0 : weight < l[1] ? 1 : 2;
	}

	/**
	 * The share of an incoming hit that gets through, given its element shares (physical, magic, fire, lightning, holy;
	 * null = all physical). Physical uses Elden Ring's standard physical negation, since the hit's type is not known.
	 */
	public static double passThrough(Player player, ErEffects.Totals totals, float[] shares) {
		double[] keep = { 1, 1, 1, 1, 1 };
		boolean any = false;
		for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			ErRef ref = ErItems.ref(player.getItemBySlot(slot));
			JsonObject a = ref == null || ref.kind() != ErRef.ARMOUR ? null : ErData.armour(ref.id());
			if (a == null) {
				continue;
			}
			any = true;
			double[] neg = ErData.arr(a, "neg", 8);
			keep[0] *= 1 - neg[0] / 100.0;
			for (int e = 1; e < 5; e++) keep[e] *= 1 - neg[e + 3] / 100.0;
		}
		// Talismans and buffs: absorption type 0 (standard physical) and 4-7 (magic to holy), then "damage taken".
		keep[0] *= totals.cut[0] * totals.taken[0];
		for (int e = 1; e < 5; e++) keep[e] *= totals.cut[e + 3] * totals.taken[e];
		if (!any && totals.cut[0] == 1 && totals.taken[0] == 1) {
			boolean plain = true;
			for (int e = 1; e < 5; e++) plain &= keep[e] == 1;
			if (plain) return 1;
		}
		if (shares == null) {
			return keep[0];
		}
		double through = 0, sum = 0;
		for (int e = 0; e < 5; e++) {
			through += shares[e] * keep[e];
			sum += shares[e];
		}
		return sum <= 0 ? keep[0] : through / sum;
	}

	/** Poise of the worn Elden Ring pieces (Elden Ring's displayed number). */
	public static int poise(Player player) {
		int total = 0;
		for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			ErRef ref = ErItems.ref(player.getItemBySlot(slot));
			JsonObject a = ref == null || ref.kind() != ErRef.ARMOUR ? null : ErData.armour(ref.id());
			if (a != null) total += ErData.i(a, "poise");
		}
		return total;
	}
}
