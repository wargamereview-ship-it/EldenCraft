package dev.eldencraft.client;

import dev.eldencraft.combat.SkyCombat;
import dev.eldencraft.link.Proto;
import dev.eldencraft.link.SkyLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;

/**
 * Replays Skyrim-captured input into Minecraft's own input handlers, as if the (hidden) MC
 * window had focus. Keeps a virtual keyboard so InputConstants.isKeyDown() still works.
 */
public final class InputBridge {
	private static final boolean[] KEYS = new boolean[512];
	private static final boolean[] BUTTONS = new boolean[8];
	private static double cursorX, cursorY;
	private static int modifiers;
	private static int clickLogs;
	private static net.minecraft.world.phys.Vec3 hurtOrigin;
	/** The next hit's shares by damage type (WardRules order), when the DLL knows its attack. */
	private static float[] hurtShares;
	private static net.minecraft.world.phys.Vec3 lootPos;
	private static int[] lootId;
	private static int[] lootRegion;

	private InputBridge() {
	}

	public static boolean isKeyDown(int scancode) {
		return scancode >= 0 && scancode < KEYS.length && KEYS[scancode];
	}

	public static void drain(Minecraft minecraft) {
		SkyLink.drainInput((type, code, a, b, c) -> dispatch(minecraft, type, code, a, b, c));
	}

	private static void dispatch(Minecraft minecraft, int type, int code, int a, int b, int c) {
		long handle = minecraft.getWindow().handle();
		switch (type) {
			case Proto.IN_KEY -> key(minecraft, handle, code, a != 0);
			case Proto.IN_MOUSE_BUTTON -> {
				if (code > 0 && code < BUTTONS.length) {
					BUTTONS[code] = a != 0;
				}
				if (a != 0 && clickLogs++ < 20) {
					var hit = minecraft.hitResult;
					dev.eldencraft.EldenCraft.LOG.info("EldenCraft: click {} -> {} {} (grabbed {}, screen {})", code, hit == null ? "null" : hit.getType(),
						hit instanceof net.minecraft.world.phys.EntityHitResult eh ? eh.getEntity().getName().getString() : hit == null ? "" : hit.getLocation(),
						minecraft.mouseHandler.isMouseGrabbed(), minecraft.gui.screen());
				}
				minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(code, modifiers), a != 0 ? 1 : 0);
			}
			case Proto.IN_SCROLL -> minecraft.mouseHandler.onScroll(handle, 0.0, a / 120.0);
			case Proto.IN_CURSOR -> {
				double dx = a - cursorX;
				double dy = b - cursorY;
				cursorX = a;
				cursorY = b;
				minecraft.mouseHandler.onMove(handle, a, b, dx, dy);
			}
			case Proto.IN_TEXT -> {
				if (minecraft.gui.screen() != null) {
					minecraft.keyboardHandler.textInput(handle, new String(Character.toChars(a)));
				}
			}
			case Proto.IN_RELEASE_ALL -> releaseAll();
			case Proto.IN_HURT_ORIGIN -> hurtOrigin = code == 1
				? new net.minecraft.world.phys.Vec3(Float.intBitsToFloat(a), Float.intBitsToFloat(b), Float.intBitsToFloat(c)) : null;
			case Proto.IN_HURT_ELEMENTS -> hurtShares = code == 1 ? dev.eldencraft.combat.WardRules.shares(a, b) : null;
			case Proto.IN_HURT -> {
				hurt(minecraft, code, a / 100.0F, b, c, hurtOrigin, hurtShares);
				hurtOrigin = null;
				hurtShares = null;
			}
			case Proto.IN_LOOT_POS -> lootPos = new net.minecraft.world.phys.Vec3(Float.intBitsToFloat(a), Float.intBitsToFloat(b), Float.intBitsToFloat(c));
			case Proto.IN_LOOT_ID -> lootId = new int[] { a, b, c };
			case Proto.IN_LOOT_REGION -> lootRegion = new int[] { a, b, c };
			case Proto.IN_ENEMY_DIED -> {
				if (lootPos != null && lootId != null && lootRegion != null) {
					enemyDied(minecraft, new dev.eldencraft.combat.SkyLoot.Death(lootPos, lootId[0], lootId[1], lootId[2], a, b,
						(code & Proto.ENEMY_BOSS) != 0, lootRegion[0], lootRegion[1], c, lootRegion[2]));
				}
				lootPos = null;
				lootId = null;
				lootRegion = null;
			}
			case Proto.IN_GRACE_REST -> {
                var current=minecraft.getSingleplayerServer();
                if(current!=null) current.execute(() -> dev.eldencraft.world.ResourceNodes.graceNoticed(current,code==0,a,b,c));
            }
			case Proto.IN_HIT_FEEDBACK -> CombatHud.hit(b, a, c, code);
			case Proto.IN_PLAYER_HEALTH -> CombatHud.playerHealth(a, b);
			case Proto.IN_OPEN_ANVIL -> {
				var server = minecraft.getSingleplayerServer();
				if (server != null && minecraft.player != null && minecraft.gui.screen() == null) {
					releaseAll();
					dev.eldencraft.combat.HewgAnvil.open(server, minecraft.player.getUUID());
				}
			}
			case Proto.IN_SHOP_HINT -> {
				if (minecraft.gui.screen() == null) {
					String who = code == 1 ? "Hewg" : "Roderika";
					String what = code == 1 ? "his anvil" : "her shop";
					minecraft.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal("R: " + what + "     Shift+R: talk to " + who), false);
				}
			}
			case Proto.IN_OPEN_SHOP -> {
				var server = minecraft.getSingleplayerServer();
				if (server != null && minecraft.player != null && minecraft.gui.screen() == null) {
					releaseAll();
					dev.eldencraft.combat.Merchants.openSpecial(server, minecraft.player.getUUID(), code);
				}
			}
			case Proto.IN_ER_ITEM, Proto.IN_ER_CLASS -> {
				var server = minecraft.getSingleplayerServer();
				if (server != null && minecraft.player != null) {
					var uuid = minecraft.player.getUUID();
					int kind = type;
					server.execute(() -> {
						if (kind == Proto.IN_ER_CLASS) {
							dev.eldencraft.items.ErServer.nativeClass(a);
							return;
						}
						ServerPlayer player = server.getPlayerList().getPlayer(uuid);
						if (player != null) dev.eldencraft.items.ErMirror.receive(player, code, a, b);
					});
				}
			}
			case Proto.IN_OPEN_MENU -> {
				if (minecraft.gui.screen() == null && minecraft.player != null) {
					releaseAll();
					minecraft.gui.setScreen(new PauseScreen(true));
				}
			}
			default -> {
			}
		}
	}

	/** An enemy this player killed in ER: the host's integrated server rolls and drops its loot. */
	private static void enemyDied(Minecraft minecraft, dev.eldencraft.combat.SkyLoot.Death death) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null) {
			return; // A guest's kills would need the host's server; not part of the local loop yet.
		}
		var uuid = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) {
				dev.eldencraft.combat.SkyLoot.enemyDied(player, death);
			}
		});
	}

	/** Skyrim hit the player: apply it as Minecraft damage on the integrated server (or the host's). */
	private static void hurt(Minecraft minecraft, int kind, float mcDamage, int attacker, int epoch, net.minecraft.world.phys.Vec3 origin,
		float[] shares) {
		var life = SkyLink.readNativeLife();
		if (life == null || !life.active() || life.epoch() != epoch || life.worldId() != SkyClient.sky().worldId) return;
		var server = minecraft.getSingleplayerServer();
		if (minecraft.player == null) {
			return;
		}
		if (server == null) {
			// A guest in a friend's world: the host's server applies it.
			if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(dev.eldencraft.net.SkyNet.Hurt.TYPE)) {
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.eldencraft.net.SkyNet.Hurt(kind, mcDamage, attacker, epoch));
			}
			return;
		}
		var uuid = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player != null) {
				var result = SkyCombat.hurtNativePlayer(player, kind, mcDamage, attacker, epoch, origin, shares);
				if (result.blocked() > 0) minecraft.execute(() -> {
					var currentLife = SkyLink.readNativeLife();
					if (currentLife != null && currentLife.active() && currentLife.epoch() == epoch
						&& minecraft.player != null && minecraft.player.getUUID().equals(uuid) && minecraft.player.isAlive()) {
						ShieldImpact.blocked(result.hand(), result.blocked());
					}
				});
			}
		});
	}

	private static void key(Minecraft minecraft, long handle, int scancode, boolean down) {
		if (scancode <= 0 || scancode >= KEYS.length) {
			return;
		}
		boolean wasDown = KEYS[scancode];
		KEYS[scancode] = down;
		updateModifiers();
		int action = down ? (wasDown ? -1 : 1) : 0; // -1 = repeat
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) modifiers, true);
		minecraft.keyboardHandler.keyPress(handle, action, new KeyEvent(scancode, keycode, modifiers));
	}

	private static void updateModifiers() {
		int m = 0;
		if (KEYS[225]) m |= 0x0001; // SDL_KMOD_LSHIFT
		if (KEYS[229]) m |= 0x0002; // SDL_KMOD_RSHIFT
		if (KEYS[224]) m |= 0x0040; // SDL_KMOD_LCTRL
		if (KEYS[228]) m |= 0x0080; // SDL_KMOD_RCTRL
		if (KEYS[226]) m |= 0x0100; // SDL_KMOD_LALT
		if (KEYS[230]) m |= 0x0200; // SDL_KMOD_RALT
		modifiers = m;
	}

	/** Lift every key and button we think is held (focus moved to Skyrim, link dropped, ...). */
	public static void releaseAll() {
		Minecraft minecraft = Minecraft.getInstance();
		long handle = minecraft.getWindow().handle();
		for (int sc = 0; sc < KEYS.length; sc++) {
			if (KEYS[sc]) {
				KEYS[sc] = false;
				updateModifiers();
				minecraft.keyboardHandler.keyPress(handle, 0, new KeyEvent(sc, SDLKeyboard.SDL_GetKeyFromScancode(sc, (short) 0, true), modifiers));
			}
		}
		for (int button = 1; button < BUTTONS.length; button++) {
			if (BUTTONS[button]) {
				BUTTONS[button] = false;
				minecraft.mouseHandler.onButton(handle, new MouseButtonInfo(button, 0), 0);
			}
		}
	}
}
