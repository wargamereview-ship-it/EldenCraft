package dev.eldencraft.combat;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.combat.ElementalRules.Element;
import dev.eldencraft.combat.ElementalRules.Status;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** What a weapon's elemental and status enchantments do when it hits an Elden Ring enemy. */
final class ElementalHit {
	private ElementalHit() {}

	/** The weapon behind a hit: the one the damage source names, else what the attacker holds. */
	static ItemStack weapon(DamageSource source) {
		ItemStack weapon = source.getWeaponItem();
		if ((weapon == null || weapon.isEmpty()) && source.getEntity() instanceof LivingEntity attacker) {
			weapon = attacker.getMainHandItem();
		}
		return weapon == null ? ItemStack.EMPTY : weapon;
	}

	/**
	 * Applies the weapon's enchantments to a hit that dealt {@code damage}: returns the extra
	 * elemental damage, and fills the target's status meters (a status that takes hold queues its own
	 * damage on the target).
	 */
	static float apply(ServerLevel level, SkyrimActorEntity target, DamageSource source, ItemStack weapon, float damage) {
		if (weapon.isEmpty()) return 0.0F;
		float extra = 0.0F;
		for (var entry : weapon.getEnchantments().entrySet()) {
			String id = entry.getKey().unwrapKey()
				.filter(key -> key.identifier().getNamespace().equals(EldenCraft.MOD_ID))
				.map(key -> key.identifier().getPath()).orElse(null);
			if (id == null) continue;
			int enchantLevel = entry.getIntValue();
			Element element = ElementalRules.element(id);
			if (element != null) {
				extra += ElementalRules.bonus(damage, enchantLevel, ElementalRules.affinity(target.family(), element));
				burst(level, target, particle(element), 6);
				continue;
			}
			Status status = ElementalRules.status(id);
			if (status == null) continue;
			var proc = target.meters().add(status, ElementalRules.buildup(enchantLevel), level.getGameTime(), target.maxHp(), target.isBoss());
			if (proc == null) continue;
			target.addStatusDamage(proc.burst());
			burst(level, target, particle(status), 16);
			if (source.getEntity() instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.translatable("eldencraft.status." + status.id), true);
			}
		}
		return extra;
	}

	private static ParticleOptions particle(Element element) {
		return switch (element) {
			case MAGIC -> ParticleTypes.ENCHANT;
			case FIRE -> ParticleTypes.FLAME;
			case LIGHTNING -> ParticleTypes.ELECTRIC_SPARK;
			case HOLY -> ParticleTypes.END_ROD;
		};
	}

	private static ParticleOptions particle(Status status) {
		return switch (status) {
			case HEMORRHAGE -> ParticleTypes.DAMAGE_INDICATOR;
			case FROSTBITE -> ParticleTypes.SNOWFLAKE;
			case POISON -> ParticleTypes.SNEEZE;
			case ROT -> ParticleTypes.CRIMSON_SPORE;
		};
	}

	static void burst(ServerLevel level, SkyrimActorEntity target, ParticleOptions particle, int count) {
		double w = target.getBbWidth() * 0.35, h = target.getBbHeight();
		level.sendParticles(particle, target.getX(), target.getY() + h * 0.6, target.getZ(), count, w, h * 0.2, w, 0.02);
	}
}
