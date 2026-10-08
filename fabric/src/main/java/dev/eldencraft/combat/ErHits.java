package dev.eldencraft.combat;

import com.google.gson.JsonObject;
import dev.eldencraft.combat.ElementalRules.Status;
import dev.eldencraft.items.ErData;
import dev.eldencraft.items.ErEffects;
import dev.eldencraft.items.ErItems;
import dev.eldencraft.items.ErRef;
import dev.eldencraft.items.ErSpellProjectile;
import dev.eldencraft.items.ErStats;
import dev.eldencraft.items.ErWeapons;
import dev.eldencraft.link.Proto;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;

/**
 * Hits on Elden Ring enemies from Elden Ring weapons and spells. Their damage is already Elden Ring's attack rating
 * (see {@link ErWeapons}), so it goes to the enemy without the tier factor vanilla weapons get; the weapon's own
 * status effects fill the enemy's meters.
 */
public final class ErHits {
	private ErHits() {
	}

	/** Elden Ring buildup to the meters' scale (they fill at 100; ordinary enemies resist about 220 in Elden Ring). */
	static final float BUILDUP_SCALE = 0.45F;
	private static final Status[] STATUS = { Status.POISON, Status.ROT, Status.HEMORRHAGE, Status.FROSTBITE, null, null, null };

	/** True when the hit was an Elden Ring weapon's or spell's and has been queued. */
	static boolean onHurt(ServerLevel level, SkyrimActorEntity target, DamageSource source, float damage) {
		if (source.getDirectEntity() instanceof ErSpellProjectile) {
			target.addExactDamage(damage);
			return true;
		}
		ItemStack weapon = ElementalHit.weapon(source);
		ErRef ref = ErItems.ref(weapon);
		JsonObject row = ref == null || ref.kind() != ErRef.WEAPON ? null : ErData.weapon(ref.id());
		if (row == null) {
			return false;
		}
		target.addExactDamage(damage);
		if (source.getEntity() instanceof ServerPlayer player) {
			int[] attrs = ErStats.effective(player, ErEffects.of(player));
			double[] buildup = ErWeapons.buildup(row, ref.level(), attrs);
			ErEffects.Totals totals = ErEffects.of(player);
			for (int k = 0; k < STATUS.length; k++) {
				double amount = (buildup[k] + totals.status[k]) * BUILDUP_SCALE;
				if (STATUS[k] == null || amount <= 0) continue;
				var proc = target.meters().add(STATUS[k], (float) amount, level.getGameTime(), target.maxHp(), target.isBoss());
				if (proc == null) continue;
				target.addStatusDamage(proc.burst());
				ElementalHit.burst(level, target, STATUS[k] == Status.HEMORRHAGE ? ParticleTypes.DAMAGE_INDICATOR
					: STATUS[k] == Status.FROSTBITE ? ParticleTypes.SNOWFLAKE : STATUS[k] == Status.POISON ? ParticleTypes.SNEEZE : ParticleTypes.CRIMSON_SPORE, 16);
				player.sendSystemMessage(Component.translatable("eldencraft.status." + STATUS[k].id), true);
			}
		}
		return true;
	}

	/** The native impact for an Elden Ring weapon: by its damage type (slash, strike, pierce) and class. */
	static int weaponClass(DamageSource source) {
		if (source.getDirectEntity() instanceof Projectile) {
			return Proto.WEAPON_ARROW;
		}
		JsonObject row = ErItems.weaponRow(ElementalHit.weapon(source));
		if (row == null) {
			return Proto.WEAPON_BLUNT;
		}
		int type = ErData.i(row, "t"), dmg = ErData.i(row, "dmg");
		if (type == 17 || type == 19) return Proto.WEAPON_AXE;
		if (type == 25 || type == 28 || type == 29) return Proto.WEAPON_SPEAR;
		if (type == 35 || type == 88) return Proto.WEAPON_UNARMED;
		if ((dmg & 2) != 0) return Proto.WEAPON_BLADE;
		if ((dmg & 8) != 0) return Proto.WEAPON_PIERCE;
		return Proto.WEAPON_BLUNT;
	}
}
