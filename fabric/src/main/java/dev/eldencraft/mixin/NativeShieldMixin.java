package dev.eldencraft.mixin;

import dev.eldencraft.combat.NativeDamageSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class NativeShieldMixin {
	@Inject(method = "applyItemBlocking(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)F", at = @At("RETURN"))
	private void eldencraft$blocked(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Float> cir) {
		if (source instanceof NativeDamageSource nativeSource) nativeSource.recordBlocked(cir.getReturnValueF());
	}
}
