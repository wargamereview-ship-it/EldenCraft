package dev.eldencraft.mixin;

import dev.eldencraft.link.SkyLink;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guests stand on their own Skyrim's ground, which this server only knows around the host; to it
 * they'd seem to hover and be kicked for flying. Their own clients keep them on the ground.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerFlightMixin {
	@Inject(method = "allowFlight", at = @At("HEAD"), cancellable = true)
	private void eldencraft$guestsStandOnTheirSkyrim(CallbackInfoReturnable<Boolean> cir) {
		if (SkyLink.active()) {
			cir.setReturnValue(true);
		}
	}
}
