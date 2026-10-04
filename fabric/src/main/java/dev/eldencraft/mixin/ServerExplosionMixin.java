package dev.eldencraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.link.Proto;
import dev.eldencraft.link.SkyLink;
import dev.eldencraft.world.SkyDigBlast;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft explosions in Skyrim's world: they blow Skyrim's ground and rock apart like blocks
 * (SkyDigBlast), and Skyrim feels them (loose objects are thrown and people knocked away).
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Unique
	private @Nullable SkyDigBlast eldencraft$blast;

	@Inject(method = "explode", at = @At("HEAD"))
	private void eldencraft$begin(CallbackInfoReturnable<Integer> cir) {
		this.eldencraft$blast = SkyDigBlast.begin((ServerExplosion) (Object) this);
	}

	@WrapOperation(
		method = "calculateExplodedPositions",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;getBlockExplosionResistance(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;"
		)
	)
	private Optional<Float> eldencraft$skyrimResists(
		ExplosionDamageCalculator calculator, Explosion explosion, BlockGetter level, BlockPos pos, BlockState block, FluidState fluid, Operation<Optional<Float>> original
	) {
		Optional<Float> vanilla = original.call(calculator, explosion, level, pos, block, fluid);
		return this.eldencraft$blast != null ? this.eldencraft$blast.resistance(pos, vanilla) : vanilla;
	}

	@ModifyVariable(method = "explode", at = @At("STORE"), ordinal = 0)
	private List<BlockPos> eldencraft$skyrimBreaks(List<BlockPos> targets) {
		if (this.eldencraft$blast != null) {
			ServerExplosion self = (ServerExplosion) (Object) this;
			this.eldencraft$blast.materialize(targets, self.getBlockInteraction() != Explosion.BlockInteraction.KEEP
				&& self.getBlockInteraction() != Explosion.BlockInteraction.TRIGGER_BLOCK);
		}
		return targets;
	}

	@Inject(method = "explode", at = @At("RETURN"))
	private void eldencraft$tellSkyrim(CallbackInfoReturnable<Integer> cir) {
		if (this.eldencraft$blast != null) {
			this.eldencraft$blast.finish();
			this.eldencraft$blast = null;
		}
		if (!SkyLink.active()) {
			return;
		}
		ServerExplosion self = (ServerExplosion) (Object) this;
		var center = self.center();
		SkyLink.pushEvent(Proto.EV_EXPLOSION, 0, (float) center.x, (float) center.y, (float) center.z, self.radius(), 0);
	}
}
