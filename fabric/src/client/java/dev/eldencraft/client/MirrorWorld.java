package dev.eldencraft.client;

import dev.eldencraft.EldenCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** Opens (or creates) the void "mirror" world automatically once Skyrim is connected. */
public final class MirrorWorld {
	private static final ResourceKey<WorldPreset> PRESET =
		ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "mirror"));
	private static boolean attempted;
	private static long lastLog;
	// /join: a friend's world for this session (the e4mc link their "Open to LAN" shows); null: our own.
	private static @org.jspecify.annotations.Nullable String sessionJoin;
	// Shown in chat once the player is in a world again (why they're back in their own, ...).
	private static @org.jspecify.annotations.Nullable String pendingNote;
	// The Elden Ring character whose world we are in (0: not tied to one). Each character has a world of its own.
	private static int ownProfile;
	private static long waitingSince;
	/** How long to wait for Elden Ring to report a character before opening the plain world (no Elden Ring around). */
	private static final long PROFILE_WAIT_MS = 45_000;

	private MirrorWorld() {
	}

	/**
	 * The address in config/eldencraft.properties ({@code join=abc-def.e4mc.link}), if any. Written
	 * with the template below the first time, so there's something to fill in.
	 */
	private static @org.jspecify.annotations.Nullable String joinAddress(Minecraft minecraft) {
		java.nio.file.Path file = minecraft.gameDirectory.toPath().resolve("config").resolve("eldencraft.properties");
		java.util.Properties props = new java.util.Properties();
		try {
			if (!java.nio.file.Files.exists(file)) {
				java.nio.file.Files.createDirectories(file.getParent());
				java.nio.file.Files.writeString(file, """
					# EldenCraft
					# To play in a friend's world instead of your own: put their address after join=
					# (the link e4mc shows them when they open their world to LAN), then restart Minecraft.
					join=
					""");
			}
			try (var in = java.nio.file.Files.newBufferedReader(file)) {
				props.load(in);
			}
		} catch (java.io.IOException e) {
			EldenCraft.LOG.warn("EldenCraft: couldn't read {}", file, e);
			return null;
		}
		String join = props.getProperty("join", "").trim();
		return join.isEmpty() ? null : join;
	}

	/** /join: leave this world and play in a friend's (their e4mc link, or any server address). */
	public static void joinFriend(Minecraft minecraft, String link) {
		// People paste all sorts: "https://abc-def.e4mc.link/", " abc-def.e4mc.link ".
		String address = link.trim().replaceFirst("^[A-Za-z]+://", "").replaceAll("/+$", "");
		if (address.isEmpty()) {
			return;
		}
		EldenCraft.LOG.info("EldenCraft: /join {}", address);
		sessionJoin = address;
		leaveWorld(minecraft);
	}

	/** The friend's world we're in (the address we joined), or null in our own. */
	public static @org.jspecify.annotations.Nullable String friendAddress(Minecraft minecraft) {
		if (sessionJoin != null) {
			return sessionJoin;
		}
		var server = minecraft.isLocalServer() ? null : minecraft.getCurrentServer();
		return server != null ? server.ip : null;
	}

	/** /leave: back to our own world. */
	public static void leaveFriend(Minecraft minecraft) {
		if (sessionJoin == null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("You're already in your own world."));
			return;
		}
		EldenCraft.LOG.info("EldenCraft: /leave {}", sessionJoin);
		sessionJoin = null;
		pendingNote = "Back in your own world.";
		leaveWorld(minecraft);
	}

	private static void leaveWorld(Minecraft minecraft) {
		attempted = false;
		minecraft.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
		minecraft.gui.setScreen(new TitleScreen());  // openWhenReady takes it from the title screen
	}

	/** Every client tick: a note for the player once they're in a world again. */
	public static void tick(Minecraft minecraft) {
		// A different Elden Ring character (another save slot, or a new game) has loaded: its own world, not this one.
		if (sessionJoin == null && minecraft.level != null && minecraft.isLocalServer() && dev.eldencraft.link.SkyLink.active()) {
			int profile = dev.eldencraft.link.SkyLink.profileId();
			if (profile != 0 && profile != ownProfile) {
				EldenCraft.LOG.info("EldenCraft: Elden Ring character {} loaded (this world is for {}); switching worlds",
					Integer.toHexString(profile), Integer.toHexString(ownProfile));
				leaveWorld(minecraft);
			}
		}
		if (pendingNote != null && minecraft.player != null) {
			minecraft.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal(pendingNote));
			pendingNote = null;
		}
	}

	/**
	 * The world made before characters had worlds of their own becomes the first character's, so existing progress is
	 * kept. Only while no character world exists yet.
	 */
	private static void adoptOldWorld(Minecraft minecraft, String name) {
		var levels = minecraft.getLevelSource();
		if (levels.levelExists(name) || !levels.levelExists(EldenCraft.WORLD_NAME)) {
			return;
		}
		java.nio.file.Path base = levels.getBaseDir();
		try (var folders = java.nio.file.Files.list(base)) {
			if (folders.anyMatch(f -> f.getFileName().toString().startsWith(EldenCraft.WORLD_NAME + "-"))) {
				return;
			}
			java.nio.file.Files.move(base.resolve(EldenCraft.WORLD_NAME), base.resolve(name));
			EldenCraft.LOG.info("EldenCraft: the existing world now belongs to this character ({})", name);
		} catch (java.io.IOException e) {
			EldenCraft.LOG.warn("EldenCraft: could not hand the existing world to this character", e);
		}
	}

	public static void openWhenReady(Minecraft minecraft) {
		// Couldn't reach a friend's world, or it closed under us: back to our own, and say why.
		if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.DisconnectedScreen && minecraft.level == null) {
			pendingNote = sessionJoin != null
				? "Couldn't stay in " + sessionJoin + " (check the link, and that your friend's world is still open to LAN). You're back in your own world."
				: "Disconnected. You're back in your own world.";
			EldenCraft.LOG.info("EldenCraft: disconnected; back to the mirror world");
			sessionJoin = null;
			attempted = false;
			minecraft.gui.setScreen(new TitleScreen());
			return;
		}
		if (attempted && minecraft.level == null && minecraft.gui.screen() != null && System.currentTimeMillis() - lastLog > 5000) {
			lastLog = System.currentTimeMillis();
			EldenCraft.LOG.info("EldenCraft: still not in the mirror world; current screen {}", minecraft.gui.screen().getClass().getName());
		}
		if (attempted || minecraft.level != null || minecraft.gui.overlay() != null) {
			return;
		}
		// Wait for the menu to settle on the title screen; skip any first-launch prompts in front of it.
		if (!(minecraft.gui.screen() instanceof TitleScreen)) {
			if (minecraft.gui.screen() != null && System.currentTimeMillis() - lastLog > 5000) {
				lastLog = System.currentTimeMillis();
				EldenCraft.LOG.info("EldenCraft: waiting on screen {} before opening the mirror world", minecraft.gui.screen().getClass().getName());
			}
			if (minecraft.gui.screen() == null || minecraft.gui.screen().getClass().getName().contains("Onboarding")) {
				minecraft.gui.setScreen(new TitleScreen());
			}
			return;
		}
		TitleScreen title = (TitleScreen) minecraft.gui.screen();
		// Multiplayer: join a friend's world (their e4mc link, or any server address) instead.
		String join = sessionJoin != null ? sessionJoin : joinAddress(minecraft);
		// Our own world belongs to the Elden Ring character playing, so wait until one has loaded. Without Elden Ring
		// (or if none ever reports) the plain world opens after a while.
		int profile = dev.eldencraft.link.SkyLink.active() ? dev.eldencraft.link.SkyLink.profileId() : 0;
		if (join == null && profile == 0) {
			if (waitingSince == 0) {
				waitingSince = System.currentTimeMillis();
			}
			if (System.currentTimeMillis() - waitingSince < PROFILE_WAIT_MS) {
				return;
			}
		}
		waitingSince = 0;
		attempted = true;
		if (join != null) {
			EldenCraft.LOG.info("EldenCraft: joining {}", join);
			pendingNote = "Joined " + join + ". Type /leave to go back to your own world.";
			net.minecraft.client.gui.screens.ConnectScreen.startConnecting(title, minecraft, net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(join),
				new net.minecraft.client.multiplayer.ServerData("EldenCraft", join, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
			return;
		}
		ownProfile = profile;
		String name = EldenCraft.worldName(profile);
		if (profile != 0) {
			adoptOldWorld(minecraft, name);
		}
		if (minecraft.getLevelSource().levelExists(name)) {
			EldenCraft.LOG.info("EldenCraft: opening mirror world {}", name);
			minecraft.createWorldOpenFlows().openWorld(name, () -> minecraft.gui.setScreen(title));
			return;
		}
		EldenCraft.LOG.info("EldenCraft: creating mirror world {} (a new character starts fresh)", name);
		LevelSettings settings = new LevelSettings(
			name,
			GameType.SURVIVAL,
			new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
			true,
			WorldDataConfiguration.DEFAULT
		);
		minecraft.createWorldOpenFlows().createFreshLevel(
			name,
			settings,
			new WorldOptions(0L, false, false),
			registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(PRESET).value().createWorldDimensions(),
			title
		);
	}
}
