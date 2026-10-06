package dev.eldencraft.mixin;

import dev.eldencraft.combat.SkyrimActorEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Smite counts Elden Ring's undead: an enemy's stand-in has no undead entity type for vanilla Smite to
 * match, so its bonus (2.5 a level, as vanilla's) is added here for an enemy of the undead family. Being
 * part of the enchantment damage, attack cooldown and critical hits treat it as they treat vanilla Smite.
 */
@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentHelperMixin {
	@Inject(method = "modifyDamage", at = @At("RETURN"), cancellable = true)
	private static void eldencraft$smiteUndead(ServerLevel level, ItemStack weapon, Entity target, DamageSource source, float base,
		CallbackInfoReturnable<Float> cir) {
		if (!(target instanceof SkyrimActorEntity enemy) || !"undead".equals(enemy.family()) || weapon.isEmpty()) {
			return;
		}
		var smite = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.SMITE);
		int smiteLevel = smite.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, weapon)).orElse(0);
		if (smiteLevel > 0) {
			cir.setReturnValue(cir.getReturnValueF() + 2.5F * smiteLevel);
		}
	}
}
