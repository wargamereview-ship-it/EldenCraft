package dev.eldencraft.items;

import com.google.gson.JsonObject;
import dev.eldencraft.combat.XpMath;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Elden Ring's attribute formulas and what they do to a Minecraft player: Vigor sets max health, Mind max FP,
 * Endurance equip load, and the rest feed weapon attack rating (see {@link ErWeapons}). Health and damage keep the
 * bridge's scale: {@link #HP_PER_HEALTH} Elden Ring HP to one Minecraft health point, as Elden Ring's hits arrive.
 */
public final class ErStats {
	private ErStats() {
	}

	/** Elden Ring HP per Minecraft health point; the native bridge divides incoming hits by the same number. */
	public static final double HP_PER_HEALTH = dev.eldencraft.combat.SkyCombat.SKYRIM_TO_MC_DAMAGE;
	/** Levelling costs Elden Ring's runes, as experience the way kills pay it (√runes), times this. */
	public static final double LEVEL_COST_SCALE = 4.0;

	private static final Identifier HEALTH = id("er_vigor"), SPEED = id("er_equip_load"), DAMAGE = id("er_attack_rating");

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(dev.eldencraft.EldenCraft.MOD_ID, path);
	}

	// ---- Elden Ring's curves -------------------------------------------------------------------------

	public static double hp(int vigor) {
		int v = Math.clamp(vigor, 1, 99);
		if (v <= 25) return 300 + 500 * Math.pow((v - 1) / 24.0, 1.5);
		if (v <= 40) return 800 + 650 * (1 - Math.pow(1 - (v - 25) / 15.0, 1.2));
		if (v <= 60) return 1450 + 450 * (1 - Math.pow(1 - (v - 40) / 20.0, 1.2));
		return 1900 + 200 * (1 - Math.pow(1 - (v - 60) / 39.0, 1.2));
	}

	public static double fp(int mind) {
		int m = Math.clamp(mind, 1, 99);
		if (m <= 15) return 50 + 45 * (m - 1) / 14.0;
		if (m <= 35) return 95 + 105 * (m - 15) / 20.0;
		if (m <= 60) return 200 + 150 * (1 - Math.pow(1 - (m - 35) / 25.0, 1.2));
		return 350 + 100 * (m - 60) / 39.0;
	}

	/** Elden Ring's stamina for an Endurance value. */
	public static double stamina(int endurance) {
		int e = Math.clamp(endurance, 1, 99);
		if (e <= 15) return 80 + 25 * (e - 1) / 14.0;
		if (e <= 30) return 105 + 25 * (e - 15) / 15.0;
		if (e <= 50) return 130 + 25 * (e - 30) / 20.0;
		return 155 + 15 * (e - 50) / 49.0;
	}

	public static double equipLoad(int endurance) {
		int e = Math.clamp(endurance, 1, 99);
		if (e <= 8) return 45;
		if (e <= 25) return 45 + 27 * (e - 8) / 17.0;
		if (e <= 60) return 72 + 48 * (1 - Math.pow(1 - (e - 25) / 35.0, 1.1));
		return 120 + 40 * (e - 60) / 39.0;
	}

	/** Runes to go from {@code level} to the next, as Elden Ring charges them. */
	public static long runeCost(int level) {
		double x = Math.max(0, ((level + 81) - 92) * 0.02);
		return (long) Math.floor((x + 0.1) * Math.pow(level + 81, 2)) + 1;
	}

	/** The same cost in experience points. */
	public static int levelCost(int level) {
		return (int) Math.round(LEVEL_COST_SCALE * 0.8 * Math.sqrt(runeCost(level)));
	}

	// ---- a player's numbers ----------------------------------------------------------------------------

	/** Attributes with equipment bonuses, clamped to 1-99. */
	public static int[] effective(Player player, ErEffects.Totals totals) {
		ErPlayer data = ErPlayer.of(player);
		int[] out = new int[8];
		for (int k = 0; k < 8; k++) out[k] = Math.clamp(data.attrs[k] + totals.attributes[k], 1, 99);
		return out;
	}

	public static double maxFp(Player player, ErEffects.Totals totals) {
		return fp(effective(player, totals)[ErPlayer.MIND]) * totals.maxFp;
	}

	/** Weight of worn armour, held weapons and equipped talismans. */
	public static double weight(Player player) {
		double total = 0;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack stack = player.getItemBySlot(slot);
			ErRef ref = ErItems.ref(stack);
			if (ref != null && ref.kind() != ErRef.GOODS) {
				JsonObject row = ErItems.row(ref);
				if (row != null) total += ErData.d(row, "w");
			}
		}
		for (ItemStack t : ErPlayer.of(player).talismans) {
			ErRef ref = ErItems.ref(t);
			JsonObject row = ref == null ? null : ErData.talisman(ref.id());
			if (row != null) total += ErData.d(row, "w");
		}
		return total;
	}

	/** Equip load ratio: under 0.3 light, 0.7 medium, 1.0 heavy, above that overloaded. */
	public static double loadRatio(Player player, ErEffects.Totals totals) {
		return weight(player) / (equipLoad(effective(player, totals)[ErPlayer.ENDURANCE]) * totals.equipLoad);
	}

	/**
	 * Applies the attributes to the player: max health, movement by equip load, attack rating of the held weapon, and
	 * FP and HP regeneration from equipment. Twice a second from the server tick.
	 */
	public static void apply(ServerPlayer player) {
		ErEffects.Totals totals = ErEffects.of(player);
		int[] attrs = effective(player, totals);
		double maxHealth = hp(attrs[ErPlayer.VIGOR]) * totals.maxHp / HP_PER_HEALTH;
		set(player.getAttribute(Attributes.MAX_HEALTH), HEALTH, maxHealth - 20.0, AttributeModifier.Operation.ADD_VALUE);
		if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());

		double ratio = loadRatio(player, totals);
		// Elden Ring only slows walking when overloaded; lighter loads change the roll, which Minecraft has not got.
		double speed = ratio <= 1.0 ? 0.0 : -0.5;
		set(player.getAttribute(Attributes.MOVEMENT_SPEED), SPEED, speed, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

		JsonObject weapon = ErItems.weaponRow(player.getMainHandItem());
		double damage = 0;
		if (weapon != null && ErData.s(weapon, "b").equals("melee")) {
			ErRef ref = ErItems.ref(player.getMainHandItem());
			double[] ar = ErWeapons.attackRating(weapon, ref.level(), attrs, totals, player.getOffhandItem().isEmpty());
			damage = ErWeapons.toMinecraft(ar);
		}
		// Minecraft's base attack damage is 1: an Elden Ring weapon replaces it with its attack rating.
		set(player.getAttribute(Attributes.ATTACK_DAMAGE), DAMAGE, damage > 0 ? damage - 1.0 : 0, AttributeModifier.Operation.ADD_VALUE);

		double maxFp = fp(attrs[ErPlayer.MIND]) * totals.maxFp;
		ErPlayer data = ErPlayer.of(player);
		float fp = (float) Math.min(maxFp, data.fp + totals.fpRegen * 0.5);
		if (Math.abs(fp - data.fp) > 0.01F) {
			ErPlayer.edit(player, p -> p.fp = fp);
		}
		if (totals.hpRegen > 0 && player.isAlive() && player.getHealth() < player.getMaxHealth()) {
			player.heal((float) (totals.hpRegen * 0.5 / HP_PER_HEALTH));
		}
	}

	private static void set(AttributeInstance attribute, Identifier id, double amount, AttributeModifier.Operation op) {
		if (attribute == null) {
			return;
		}
		AttributeModifier existing = attribute.getModifier(id);
		if (existing != null && Math.abs(existing.amount() - amount) < 1e-4) {
			return;
		}
		attribute.removeModifier(id);
		if (amount != 0) {
			attribute.addTransientModifier(new AttributeModifier(id, amount, op));
		}
	}

	/** Spends experience points; false if the player has too few. */
	public static boolean spendXp(ServerPlayer player, int points) {
		int have = XpMath.points(player.experienceLevel, player.experienceProgress);
		if (have < points && !player.hasInfiniteMaterials()) {
			return false;
		}
		if (!player.hasInfiniteMaterials()) {
			player.giveExperiencePoints(-points);
		}
		return true;
	}
}
