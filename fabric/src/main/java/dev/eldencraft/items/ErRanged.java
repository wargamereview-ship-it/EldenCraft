package dev.eldencraft.items;

import com.google.gson.JsonObject;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared rules for Elden Ring bows and crossbows: which ammunition they take and how hard it hits. */
final class ErRanged {
	private ErRanged() {
	}

	/** Bows shoot arrows (light bows and bows) or greatarrows (greatbows); crossbows bolts, ballistas greatbolts. */
	static Predicate<ItemStack> ammo(int... types) {
		return stack -> {
			JsonObject w = ErItems.weaponRow(stack);
			if (w == null) {
				return types.length > 0 && types[0] != 83 && types[0] != 86 && stack.is(Items.ARROW);
			}
			int t = ErData.i(w, "t");
			for (int type : types) if (t == type) return true;
			return false;
		};
	}

	/** Sets an arrow's base damage from the weapon's and the ammunition's attack rating. */
	static void arm(Projectile projectile, LivingEntity shooter, ItemStack weapon, ItemStack ammo, double velocity) {
		if (!(projectile instanceof AbstractArrow arrow) || !(shooter instanceof Player player)) {
			return;
		}
		JsonObject w = ErItems.weaponRow(weapon);
		if (w == null) {
			return;
		}
		ErEffects.Totals totals = ErEffects.of(player);
		int[] attrs = ErStats.effective(player, totals);
		ErRef ref = ErItems.ref(weapon);
		double ar = ErWeapons.total(ErWeapons.attackRating(w, ref == null ? 0 : ref.level(), attrs, totals, true));
		JsonObject a = ErItems.weaponRow(ammo);
		if (a != null) {
			ar += ErWeapons.total(ErData.arr(a, "atk", 5));
		}
		if (player instanceof net.minecraft.server.level.ServerPlayer server) {
			ErStamina.spend(server, velocity > 3.0 ? 16 : 12);
		}
		// Minecraft multiplies base damage by the arrow's speed (about 3 at full draw).
		arrow.setBaseDamage(ar * ErWeapons.AR_TO_MC / Math.max(1.0, velocity));
	}
}
