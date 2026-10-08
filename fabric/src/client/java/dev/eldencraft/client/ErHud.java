package dev.eldencraft.client;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.items.ErData;
import dev.eldencraft.items.ErEffects;
import dev.eldencraft.items.ErPlayer;
import dev.eldencraft.items.ErStamina;
import dev.eldencraft.items.ErStats;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * Elden Ring's HUD: HP, FP and stamina bars at the top left, each as long as its maximum (so they grow as you level),
 * in place of Minecraft's hearts. Damage taken lingers as a yellow stretch before it drains, as in Elden Ring. The
 * selected spell sits under them. Also holds the client's half of the stamina sprint lock.
 */
public final class ErHud {
	private ErHud() {
	}

	private static final int HP = 0xffa3201c, HP_TOP = 0xffcf4a3c, FP = 0xff2c56c6, FP_TOP = 0xff5d86e8,
		STAMINA = 0xff2f8a3e, STAMINA_TOP = 0xff5bb866, TRAIL = 0xffd9b54a, BACK = 0xd0141212, FRAME = 0xff8c7449, ABSORB = 0xffe8d27a;

	/** The lingering damage trail: where the HP bar stood, and when it last dropped. */
	private static float trail = -1;
	private static long trailHold;
	private static boolean dry;

	static void init() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "er_bars"), (gui, delta) -> render(gui));
		HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, vanilla -> (gui, delta) -> {
			if (!active()) vanilla.extractRenderState(gui, delta);
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null) return;
			float s = ErStamina.current(mc.player);
			if (s == 0.0F) dry = true;
			if (s < 0 || s >= ErStamina.SPRINT_AGAIN) dry = false;
		});
	}

	/** True when sprinting is locked for running out of stamina (until some has come back). */
	public static boolean sprintLocked() {
		var mc = Minecraft.getInstance();
		return dry && mc.player != null && !mc.player.hasInfiniteMaterials();
	}

	/** The Elden Ring HUD is up once the character has its attributes. */
	private static boolean active() {
		var mc = Minecraft.getInstance();
		return mc.player != null && ErPlayer.of(mc.player).startClass != 0 && !mc.player.hasInfiniteMaterials();
	}

	private static void render(GuiGraphicsExtractor gui) {
		var mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null || !active()) {
			return;
		}
		var player = mc.player;
		ErPlayer data = ErPlayer.of(player);
		ErEffects.Totals totals = ErEffects.of(player);
		int room = gui.guiWidth() - 24;
		int x = 10, y = 10;

		// HP, in Elden Ring's numbers (health points times the bridge's scale).
		float maxHp = player.getMaxHealth(), hp = Math.max(0, player.getHealth());
		int hpWidth = width(50 + maxHp * ErStats.HP_PER_HEALTH * 0.085, room);
		float frac = maxHp <= 0 ? 0 : Math.min(1, hp / maxHp);
		long now = System.currentTimeMillis();
		if (trail < 0 || frac > trail) {
			trail = frac;
		} else if (frac < trail) {
			if (trailHold == 0) trailHold = now + 700;
			if (now > trailHold) {
				trail = Math.max(frac, trail - 0.012F);
				if (trail == frac) trailHold = 0;
			}
		}
		frame(gui, x, y, hpWidth, 6);
		fill(gui, x, y, hpWidth, 6, trail, TRAIL, TRAIL);
		fill(gui, x, y, hpWidth, 6, frac, HP, HP_TOP);
		float absorb = player.getAbsorptionAmount();
		if (absorb > 0) {
			int end = Math.round(hpWidth * frac);
			int extra = Math.min(hpWidth - end, Math.round(hpWidth * absorb / maxHp));
			gui.fill(x + end, y + 1, x + end + extra, y + 5, ABSORB);
		}
		y += 10;

		double maxFp = ErStats.maxFp(player, totals);
		int fpWidth = width(40 + maxFp * 0.32, room);
		frame(gui, x, y, fpWidth, 4);
		fill(gui, x, y, fpWidth, 4, (float) Math.clamp(data.fp / Math.max(1, maxFp), 0, 1), FP, FP_TOP);
		y += 8;

		double maxSt = ErStamina.max(player, totals);
		float st = ErStamina.current(player);
		int stWidth = width(50 + maxSt * 0.75, room);
		frame(gui, x, y, stWidth, 4);
		fill(gui, x, y, stWidth, 4, st < 0 ? 1 : (float) Math.clamp(st / Math.max(1, maxSt), 0, 1), STAMINA, STAMINA_TOP);
		y += 8;

		if (!data.spells.isEmpty()) {
			var spell = ErData.spell(data.spells.get(Math.clamp(data.selected, 0, data.spells.size() - 1)));
			if (spell != null) {
				gui.text(mc.font, ErData.s(spell, "n"), x, y + 1, 0xffe5dfd4);
			}
		}
	}

	private static int width(double w, int room) {
		return (int) Math.clamp(Math.round(w), 30, Math.max(30, room));
	}

	/** Elden Ring's thin bronze frame around a dark trough. */
	private static void frame(GuiGraphicsExtractor gui, int x, int y, int w, int h) {
		gui.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xff0c0a08);
		gui.fill(x - 1, y - 1, x + w + 1, y, FRAME);
		gui.fill(x - 1, y + h, x + w + 1, y + h + 1, FRAME);
		gui.fill(x - 1, y - 1, x, y + h + 1, FRAME);
		gui.fill(x + w, y - 1, x + w + 1, y + h + 1, FRAME);
		gui.fill(x, y, x + w, y + h, BACK);
	}

	private static void fill(GuiGraphicsExtractor gui, int x, int y, int w, int h, float frac, int colour, int top) {
		int filled = Math.round(w * Math.clamp(frac, 0, 1));
		if (filled <= 0) return;
		gui.fill(x, y, x + filled, y + h, colour);
		gui.fill(x, y, x + filled, y + 1, top);
	}
}
