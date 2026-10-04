package dev.eldencraft.client.mixin;

import dev.eldencraft.client.SkyClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void eldencraft$beginFrame(boolean advanceGameTime, CallbackInfo ci) {
		SkyClient.beginFrame();
	}

	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER)
	)
	private void eldencraft$afterRender(boolean advanceGameTime, CallbackInfo ci) {
		SkyClient.afterRender();
	}

	@Inject(method = "renderFrame", at = @At("TAIL"))
	private void eldencraft$pace(boolean advanceGameTime, CallbackInfo ci) {
		SkyClient.paceFrame();
	}
}
