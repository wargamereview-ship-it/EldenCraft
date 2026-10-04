package dev.eldencraft.client.mixin;

import dev.eldencraft.client.SkyClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudCrosshairMixin {
	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void eldencraft$mode(GuiGraphicsExtractor gui, DeltaTracker delta, CallbackInfo ci) {
		if (SkyClient.tookOver() && (!SkyClient.linked() || !SkyClient.sky().minecraftHands())) ci.cancel();
	}
}
