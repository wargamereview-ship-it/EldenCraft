package dev.eldencraft.client;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.combat.SkyrimActorEntity;
import dev.eldencraft.link.Proto;
import dev.eldencraft.link.SkyLink;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.EntityHitResult;

/** Displays enemy health and combat acknowledgements; player health uses vanilla hearts. */
final class CombatHud {
	private static int hitId, hitDamage, hitHp, hitFlags;
	private static String hitName = "Enemy";
	private static long hitAt;
	private static final long SECOND = 1_000_000_000L;

	private CombatHud() {
	}

	static void register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "combat"), CombatHud::render);
	}

	static void playerHealth(int hp, int maximum) { /* Legacy DLL feedback is deliberately ignored. */ }

	static void hit(int id, int damage, int remainingHp, int flags) {
		hitId = id;
		hitDamage = Math.max(0, damage);
		hitHp = Math.max(0, remainingHp);
		hitFlags = flags;
		hitAt = System.nanoTime();
		SkyLink.Actor actor = ProxySync.actor(id);
		hitName = actor == null ? "Enemy" : dev.eldencraft.combat.NpcNames.display(actor.name());
		EldenCraft.LOG.info("EldenCraft: ER {} actor {} for {} HP ({} left)",
			(flags & Proto.HIT_REJECTED) != 0 ? "rejected hit on" : "confirmed hit on", id, damage, remainingHp);
	}

	private static void render(GuiGraphicsExtractor gui, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.gui.hud.isHidden() || mc.gui.screen() != null
			|| !SkyLink.active() || !SkyClient.sky().inGame() || SkyClient.sky().loading()) {
			return;
		}
		long now = System.nanoTime();

		SkyLink.Actor target = null;
		if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof SkyrimActorEntity proxy) {
			target = ProxySync.actor(proxy.formId());
		}
		boolean recent = hitAt != 0 && now - hitAt < 2 * SECOND;
		if (target == null && recent) {
			target = ProxySync.actor(hitId);
		}
		int center = gui.guiWidth() / 2;
		float blocked = ShieldImpact.flash();
		if (blocked > 0) {
			int color = Math.round(255 * blocked) << 24 | 0xb9e1e8;
			gui.centeredText(mc.font, "BLOCKED", center, gui.guiHeight() / 2 + 38, color);
		}
		if (target != null && !target.dead()) {
			int width = Math.min(200, gui.guiWidth() - 40);
			gui.centeredText(mc.font, dev.eldencraft.combat.NpcNames.display(target.name()), center, 18, 0xffe8d6b1);
			boolean hitFlash = recent && target.formId() == hitId && now - hitAt < SECOND / 4;
			bar(gui, center - width / 2, 31, width, 6, target.healthFrac(), hitFlash ? 0xfff4bb79 : 0xffb34248);
			gui.centeredText(mc.font, Math.round(target.healthFrac() * 100) + "%", center, 42, 0xffc9c5ba);
		}
		if (!recent) {
			return;
		}
		boolean rejected = (hitFlags & Proto.HIT_REJECTED) != 0;
		boolean critical = (hitFlags & Proto.HIT_CRITICAL) != 0;
		int alpha = (int) (255 * Math.min(1.0, (2 * SECOND - (now - hitAt)) / (double) (SECOND / 2)));
		int color = alpha << 24 | (rejected ? 0xc9c5ba : critical ? 0xffd16b : 0xf4ebd8);
		String text = rejected ? "IMMUNE" : (critical ? "CRITICAL  " : "") + "-" + hitDamage;
		gui.centeredText(mc.font, text, center, gui.guiHeight() / 2 + 18, color);
		if (!rejected && hitHp == 0) {
			gui.centeredText(mc.font, hitName + " defeated", center, 57, color);
		}
		// Short crosshair flash acknowledges only native accepted hits.
		if (!rejected && now - hitAt < SECOND / 5) {
			int y = gui.guiHeight() / 2;
			gui.fill(center - 7, y - 7, center - 4, y - 5, color);
			gui.fill(center + 4, y - 7, center + 7, y - 5, color);
			gui.fill(center - 7, y + 5, center - 4, y + 7, color);
			gui.fill(center + 4, y + 5, center + 7, y + 7, color);
		}
	}

	private static void bar(GuiGraphicsExtractor gui, int x, int y, int width, int height, float fraction, int color) {
		gui.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xcc151515);
		gui.fill(x, y, x + width, y + height, 0xff34272a);
		int filled = Math.round(Math.max(0, Math.min(1, fraction)) * width);
		if (filled > 0) {
			gui.fill(x, y, x + filled, y + height, color);
			gui.fill(x, y, x + filled, y + 1, 0xffcfab8c);
		}
	}
}
