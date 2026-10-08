package dev.eldencraft;

import dev.eldencraft.combat.SkyCombat;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EldenCraft implements ModInitializer {
	public static final String MOD_ID = "eldencraft";
	public static final String WORLD_NAME = "EldenCraft";

	/** The mirror world of one Elden Ring character (its save slot and name): each character plays in a world of its own. */
	public static String worldName(int profile) {
		return profile == 0 ? WORLD_NAME : WORLD_NAME + "-" + Integer.toHexString(profile);
	}

	public static boolean isMirrorWorld(String levelId) {
		return WORLD_NAME.equals(levelId) || levelId.startsWith(WORLD_NAME + "-");
	}
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		dev.eldencraft.items.ErServer.init();
		dev.eldencraft.combat.PlayerStatusBridge.init();
		dev.eldencraft.combat.BossRewards.init();
		dev.eldencraft.combat.XpPile.init();
		dev.eldencraft.combat.Merchants.init();
		dev.eldencraft.combat.FieldGuide.init();
		SkyCombat.init();
		dev.eldencraft.world.ResourceNodes.init();
		dev.eldencraft.net.SkyNet.init();
		dev.eldencraft.world.SkyDig.init();
		ServerLifecycleEvents.SERVER_STARTED.register(EldenCraft::configureServer);

	}

	/** The mirror world is a void that only exists to host the player; Elden Ring drives time and spawning. */
	private static void configureServer(MinecraftServer server) {
		GameRules rules = server.getGameRules();
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.PLAYER_MOVEMENT_CHECK, false, server);
		rules.set(GameRules.KEEP_INVENTORY, true, server);
		rules.set(GameRules.IMMEDIATE_RESPAWN, true, server);
		rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
		LOG.info("EldenCraft: mirror world configured");
		dev.eldencraft.combat.SkyLoot.verify(server);
		dev.eldencraft.world.ResourceNodes.load(server);
		dev.eldencraft.combat.XpPile.load(server);
	}

}
