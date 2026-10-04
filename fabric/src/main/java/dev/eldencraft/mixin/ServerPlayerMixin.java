package dev.eldencraft.mixin;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.combat.SkyCombat;
import dev.eldencraft.combat.SkyrimActorEntity;
import dev.eldencraft.link.Proto;
import dev.eldencraft.link.SkyLink;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	/** Critical hits on a Skyrim actor are flagged so Skyrim can play them up. */
	@Inject(method = "crit", at = @At("HEAD"))
	private void eldencraft$critSkyrim(Entity entity, CallbackInfo ci) {
		if (entity instanceof SkyrimActorEntity proxy) {
			proxy.markCritical();
		}
	}

	/** Dying in Minecraft is dying in Skyrim: the host's through the link, a guest's through theirs. */
	@Inject(method = "die", at = @At("HEAD"))
	private void eldencraft$diesInSkyrim(DamageSource source, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		int attacker = SkyCombat.attackerFormId(source);
		if (!dev.eldencraft.net.SkyNet.isHost(self)) {
			if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(self, dev.eldencraft.net.SkyNet.Died.TYPE)) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(self, new dev.eldencraft.net.SkyNet.Died(attacker));
			}
			EldenCraft.LOG.info("EldenCraft: guest {} died ({}); telling their Skyrim", self.getPlainTextName(), source.getMsgId());
			return;
		}
		if (SkyLink.active()) {
			var life = SkyLink.readNativeLife();
			if (life != null && life.active()) {
				SkyLink.pushEvent(Proto.EV_PLAYER_DIED, attacker, 0, 0, 0, 0, life.epoch());
				SkyLink.writePlayerVitals(life, self);
			}
			EldenCraft.LOG.info("EldenCraft: Minecraft player died ({}); ER will follow authoritative hearts", source.getMsgId());
		}
	}
}
