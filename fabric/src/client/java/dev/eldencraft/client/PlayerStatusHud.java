package dev.eldencraft.client;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.combat.NativeStatus;
import dev.eldencraft.link.SkyLink;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Native buildup and exact native durations alongside Minecraft's own effect icons. */
final class PlayerStatusHud {
    private PlayerStatusHud() {}
    static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "player_statuses"), (gui, delta) -> render(gui));
    }
    private static void render(GuiGraphicsExtractor gui) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.gui.hud.isHidden() || mc.gui.screen() != null
            || !SkyLink.active() || !SkyClient.sky().inGame() || SkyClient.sky().loading()) return;
        var life = SkyLink.readNativeLife();
        if (life == null || life.worldId() != SkyClient.sky().worldId) return;
        var snapshot = SkyLink.readPlayerStatuses(life);
        if (snapshot == null) return;
        int rows = 0;
        for (NativeStatus status : NativeStatus.values()) {
            var entry = snapshot.get(status);
            if (entry.active() || entry.buildup() > 0) rows++;
        }
        int y = Math.max(8, gui.guiHeight() - 55 - rows * 25), x = 8;
        for (NativeStatus status : NativeStatus.values()) {
            var entry = snapshot.get(status);
            if (!entry.active() && entry.buildup() == 0) continue;
            gui.fill(x, y, x + 165, y + 23, 0xb0181818);
            gui.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "mob_effect/" + status.id), x + 2, y + 2, 18, 18);
            gui.text(mc.font, Component.translatable(status.translationKey()), x + 24, y + 2, 0xff000000 | status.color);
            String value = entry.active() ? (entry.remaining() == -1 ? "Active" : formatTime(entry.remaining()))
                : Math.round(entry.buildupFraction() * 100) + "% buildup";
            gui.text(mc.font, value, x + 24, y + 12, 0xffe5dfd4);
            float fraction = entry.active() ? entry.timerFraction() : entry.buildupFraction();
            gui.fill(x + 104, y + 15, x + 159, y + 19, 0xff3d3834);
            int width = Math.round(fraction * 55);
            if (width > 0) gui.fill(x + 104, y + 15, x + 104 + width, y + 19, 0xff000000 | status.color);
            y += 25;
        }
    }
    private static String formatTime(float seconds) {
        int total = (int) Math.ceil(seconds);
        return total / 60 + ":" + (total % 60 < 10 ? "0" : "") + total % 60;
    }
}
