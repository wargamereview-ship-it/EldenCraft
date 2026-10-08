package dev.eldencraft.client.mixin;

import dev.eldencraft.client.ErHud;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Out of Elden Ring stamina, no sprinting: the same check that stops it when hungry. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintMixin {
	@Inject(method = "isSprintingPossible", at = @At("HEAD"), cancellable = true)
	private void eldencraft$stamina(boolean flying, CallbackInfoReturnable<Boolean> cir) {
		if (ErHud.sprintLocked()) {
			cir.setReturnValue(false);
		}
	}
}
