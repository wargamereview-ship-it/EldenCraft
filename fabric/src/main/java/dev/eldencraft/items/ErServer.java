package dev.eldencraft.items;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.eldencraft.EldenCraft;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Server side of the Elden Ring items: applies attributes every half second, sets a new character up from its
 * starting class, refills flasks at graces, and the commands (/levelup, /talismans, /erstats, /flasks, /ergive).
 */
public final class ErServer {
	private ErServer() {
	}

	/** Levelling is offered at a grace: for this long after resting. */
	private static final long LEVEL_WINDOW_TICKS = 20L * 90;
	private static final Map<UUID, Long> RESTED = new HashMap<>();
	/** The Elden Ring character's starting class, once the bridge reports it (0: not yet). */
	private static volatile int nativeClass;
	private static int ticks;

	public static void init() {
		ErData.weapons();
		ErPlayer.init();
		ErItems.init();
		ErStamina.init();
		ServerTickEvents.END_SERVER_TICK.register(ErServer::tick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
			dispatcher.register(Commands.literal("levelup").executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				Long at = RESTED.get(p.getUUID());
				if (!p.hasInfiniteMaterials() && (at == null || p.level().getGameTime() - at > LEVEL_WINDOW_TICKS)) {
					p.sendSystemMessage(Component.literal("Rest at a site of grace to level up."));
					return 0;
				}
				ErMenus.openLevelUp(p);
				return 1;
			}));
			dispatcher.register(Commands.literal("talismans").executes(c -> {
				ErMenus.openTalismans(c.getSource().getPlayerOrException());
				return 1;
			}));
			dispatcher.register(Commands.literal("erstats").executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				ErPlayer d = ErPlayer.of(p);
				ErEffects.Totals t = ErEffects.of(p);
				int[] a = ErStats.effective(p, t);
				StringBuilder s = new StringBuilder("Rune Level " + d.level() + ":");
				for (int k = 0; k < 8; k++) s.append(' ').append(ErPlayer.NAMES[k]).append(' ').append(a[k]);
				p.sendSystemMessage(Component.literal(s.toString()));
				p.sendSystemMessage(Component.literal(String.format("HP %.0f, FP %.0f/%.0f, equip load %.1f/%.1f, poise %d, flasks %d+%d (+%d)",
					ErStats.hp(a[0]) * t.maxHp, d.fp, ErStats.maxFp(p, t), ErStats.weight(p), ErStats.equipLoad(a[2]) * t.equipLoad,
					ErArmour.poise(p), d.flasks - d.cerulean, d.cerulean, d.flaskLevel)));
				return 1;
			}));
			dispatcher.register(Commands.literal("flasks").then(Commands.argument("cerulean", IntegerArgumentType.integer(0, ErFlasks.MAX_CHARGES)).executes(c -> {
				ServerPlayer p = c.getSource().getPlayerOrException();
				Long at = RESTED.get(p.getUUID());
				if (!p.hasInfiniteMaterials() && (at == null || p.level().getGameTime() - at > LEVEL_WINDOW_TICKS)) {
					p.sendSystemMessage(Component.literal("Rest at a site of grace to allocate your flasks."));
					return 0;
				}
				ErFlasks.allocate(p, IntegerArgumentType.getInteger(c, "cerulean"));
				ErPlayer d = ErPlayer.of(p);
				p.sendSystemMessage(Component.literal("Flasks: " + (d.flasks - d.cerulean) + " Crimson, " + d.cerulean + " Cerulean"));
				return 1;
			})));
			dispatcher.register(Commands.literal("ergive").requires(s -> s.getPlayer() != null && s.getPlayer().hasInfiniteMaterials())
				.then(Commands.argument("kind", StringArgumentType.word())
					.then(Commands.argument("id", IntegerArgumentType.integer(0)).executes(c -> give(c.getSource().getPlayerOrException(),
						StringArgumentType.getString(c, "kind"), IntegerArgumentType.getInteger(c, "id"), 0))
						.then(Commands.argument("level", IntegerArgumentType.integer(0, 25)).executes(c -> give(c.getSource().getPlayerOrException(),
							StringArgumentType.getString(c, "kind"), IntegerArgumentType.getInteger(c, "id"), IntegerArgumentType.getInteger(c, "level")))))));
		});
	}

	private static int give(ServerPlayer player, String kind, int id, int level) {
		int k = switch (kind) {
			case "weapon" -> ErRef.WEAPON;
			case "armour", "armor" -> ErRef.ARMOUR;
			case "talisman" -> ErRef.TALISMAN;
			case "ash" -> ErRef.ASH;
			default -> ErRef.GOODS;
		};
		ItemStack stack = ErItems.stack(k, id, level, 1);
		if (stack.isEmpty()) {
			player.sendSystemMessage(Component.literal("No Elden Ring " + kind + " " + id));
			return 0;
		}
		player.getInventory().add(stack);
		return 1;
	}

	private static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isAlive() && ErPlayer.of(player).startClass != 0) {
				ErStamina.tick(player, ErEffects.of(player));
			}
		}
		if (++ticks % 10 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ErPlayer data = ErPlayer.of(player);
			if (data.startClass == 0 && (nativeClass != 0 || player.tickCount > 200)) {
				start(player, nativeClass != 0 ? nativeClass : 3009);
			}
			ErStats.apply(player);
		}
	}

	/** A new character: its starting class's attributes, two flasks, full FP. */
	private static void start(ServerPlayer player, int classId) {
		var row = ErData.classes().get(classId);
		ErPlayer.edit(player, p -> {
			p.startClass = classId;
			if (row != null) {
				int[] stats = ErData.ints(row, "stats", 8);
				for (int k = 0; k < 8; k++) p.attrs[k] = Math.clamp(stats[k], 1, 99);
			}
			p.fp = (float) ErStats.fp(p.attrs[ErPlayer.MIND]);
		});
		ErFlasks.refreshStacks(player);
		player.setHealth(player.getMaxHealth());
		EldenCraft.LOG.info("EldenCraft: Elden Ring character started as {} (level {})", row == null ? classId : ErData.s(row, "n"), ErPlayer.of(player).level());
		player.sendSystemMessage(Component.literal("You begin as a " + (row == null ? "Tarnished" : ErData.s(row, "n"))
			+ ". Rest at a grace and type /levelup to level up; /talismans holds your talismans."));
	}

	/** The bridge reported the Elden Ring character's class (CharaInitParam row). */
	public static void nativeClass(int classId) {
		if (classId >= 3000 && classId <= 3009) nativeClass = classId;
	}

	/** A grace rest: flasks refill, FP restores, and levelling is open for a while. */
	public static void graceRest(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			RESTED.put(player.getUUID(), player.level().getGameTime());
			ErFlasks.rest(player);
			ErStamina.refill(player);
			player.sendOverlayMessage(Component.literal("Flasks refilled. /levelup to level up, /flasks to allocate."));
		}
	}
}
