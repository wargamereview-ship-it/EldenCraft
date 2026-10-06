package dev.eldencraft.combat;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.link.SkyLink;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

/** Server-owned vanilla effect sync, scoped to the local host and this native life. */
public final class PlayerStatusBridge {
    private static ServerPlayer owner;
    private PlayerStatusBridge() {}
    public static void init() {
        NativeStatusEffects.init();
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { clear(owner); owner = null; });
    }
    public static void tick(MinecraftServer server, SkyLink.NativeLife life) {
        ServerPlayer host = server.getPlayerList().getPlayers().stream()
            .filter(dev.eldencraft.net.SkyNet::isHost).findFirst().orElse(null);
        if (owner != host) { clear(owner); owner = host; }
        if (host == null) return;
        var snapshot = SkyLink.active() && host.isAlive() ? SkyLink.readPlayerStatuses(life) : null;
        for (NativeStatus status : NativeStatus.values()) {
            var effect = NativeStatusEffects.effect(status);
            var current = host.getEffect(effect);
            var entry = snapshot == null ? null : snapshot.get(status);
            int ticks = entry == null ? 0 : entry.effectTicks();
            if (ticks == 0) {
                if (current != null) {
                    host.removeEffect(effect);
                    EldenCraft.LOG.info("EldenCraft: player status {} cleared", status.id);
                }
            } else if (current == null || needsCorrection(current.getDuration(), ticks)) {
                // forceAddEffect allows native cures/shortening; addEffect only extends equal amplifiers.
                host.forceAddEffect(new MobEffectInstance(effect, ticks, 0, false, false, true), null);
                if (current == null) EldenCraft.LOG.info("EldenCraft: player status {} active, {}", status.id,
                    ticks == -1 ? "until native clears it" : entry.remaining() + " seconds remaining");
            }
        }
    }
    public static boolean needsCorrection(int current, int nativeTicks) {
        return (current == -1 || nativeTicks == -1) ? current != nativeTicks : Math.abs((long) current - nativeTicks) > 2;
    }
    private static void clear(ServerPlayer player) {
        if (player == null) return;
        for (NativeStatus status : NativeStatus.values()) player.removeEffect(NativeStatusEffects.effect(status));
    }
}
