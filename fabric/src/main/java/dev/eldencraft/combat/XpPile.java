package dev.eldencraft.combat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eldencraft.EldenCraft;
import dev.eldencraft.link.SkyLink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Dying costs the player's experience, like losing runes in Elden Ring. All of it spills in one pile where they
 * fell; walking back to it gives it all back. A second death before that replaces the pile (the old one is lost).
 * The inventory is always kept. Piles are saved with the world.
 */
public final class XpPile {
	/** Where a player's pile waits: the map it is on (native world id), the spot, and the points in it. */
	record Pile(int world, double x, double y, double z, int points) {
	}

	/** How close the player has to be to collect it (blocks), across and up or down. */
	static final double REACH = 2.5, REACH_Y = 3.5;

	private static final Map<UUID, Pile> PILES = new HashMap<>();
	/** The last solid ground each player stood on, so a death in a fall puts the pile somewhere reachable. */
	private static final Map<UUID, double[]> LAST_SAFE = new HashMap<>();
	/** Players who died and whose experience is cleared when they respawn. */
	private static final Set<UUID> CLEAR_ON_RESPAWN = new HashSet<>();
	private static Path file;
	private static boolean healthy;
	private static int ticks;

	private XpPile() {
	}

	public static void init() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				died(player);
			}
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> {
			if (CLEAR_ON_RESPAWN.remove(player.getUUID())) {
				player.setExperienceLevels(0);
				player.setExperiencePoints(0);
				player.totalExperience = 0;
			}
		});
	}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("eldencraft_xp_piles_v1.json");
		PILES.clear();
		healthy = true;
		try {
			if (Files.exists(file)) {
				JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				for (var entry : root.getAsJsonObject("piles").entrySet()) {
					JsonObject p = entry.getValue().getAsJsonObject();
					PILES.put(UUID.fromString(entry.getKey()), new Pile(p.get("world").getAsInt(), p.get("x").getAsDouble(),
						p.get("y").getAsDouble(), p.get("z").getAsDouble(), p.get("points").getAsInt()));
				}
			}
		} catch (Exception e) {
			healthy = false;
			PILES.clear();
			EldenCraft.LOG.error("EldenCraft: experience piles unreadable; keeping the file and starting without piles", e);
		}
	}

	private static void save() {
		if (!healthy || file == null) {
			return;
		}
		try {
			JsonObject piles = new JsonObject();
			for (var e : PILES.entrySet()) {
				Pile p = e.getValue();
				JsonObject o = new JsonObject();
				o.addProperty("world", p.world());
				o.addProperty("x", p.x());
				o.addProperty("y", p.y());
				o.addProperty("z", p.z());
				o.addProperty("points", p.points());
				piles.add(e.getKey().toString(), o);
			}
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			root.add("piles", piles);
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			EldenCraft.LOG.error("EldenCraft: experience pile save failed", e);
		}
	}

	private static void died(ServerPlayer player) {
		int points = XpMath.points(player.experienceLevel, player.experienceProgress);
		if (points <= 0) {
			return; // nothing to lose, so an earlier pile stays where it is
		}
		var life = SkyLink.readNativeLife();
		if (life == null) {
			return;
		}
		// A death while falling would leave the pile out of reach: use the last solid ground instead.
		double[] safe = LAST_SAFE.get(player.getUUID());
		boolean grounded = player.onGround();
		double x = grounded || safe == null || (int) safe[3] != life.worldId() ? player.getX() : safe[0];
		double y = grounded || safe == null || (int) safe[3] != life.worldId() ? player.getY() : safe[1];
		double z = grounded || safe == null || (int) safe[3] != life.worldId() ? player.getZ() : safe[2];
		boolean replaced = PILES.containsKey(player.getUUID());
		PILES.put(player.getUUID(), new Pile(life.worldId(), x, y, z, points));
		CLEAR_ON_RESPAWN.add(player.getUUID());
		save();
		player.sendSystemMessage(Component.literal("You lost " + points + " experience where you fell. Return to it to take it back."
			+ (replaced ? " The pile you left before is gone." : "")));
		EldenCraft.LOG.info("EldenCraft: {} points of experience left at {} {} {} (replaced {})", points, x, y, z, replaced);
	}

	/** Server tick: remember safe ground, show the piles, let players collect them. */
	public static void tick(MinecraftServer server, SkyLink.NativeLife life) {
		ticks++;
		if (life == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.onGround() && player.isAlive() && !player.isInWater() && !player.isInLava()) {
				LAST_SAFE.put(player.getUUID(), new double[] { player.getX(), player.getY(), player.getZ(), life.worldId() });
			}
			Pile pile = PILES.get(player.getUUID());
			if (pile == null || pile.world() != life.worldId() || !player.isAlive()) {
				continue;
			}
			if (reaches(player.getX() - pile.x(), player.getY() - pile.y(), player.getZ() - pile.z())) {
				PILES.remove(player.getUUID());
				player.giveExperiencePoints(pile.points());
				save();
				player.sendSystemMessage(Component.literal("You took back " + pile.points() + " experience."));
			} else if (ticks % 6 == 0 && Math.hypot(player.getX() - pile.x(), player.getZ() - pile.z()) < 48.0) {
				player.level().sendParticles(ParticleTypes.END_ROD, pile.x(), pile.y() + 0.6, pile.z(), 3, 0.25, 0.4, 0.25, 0.01);
				player.level().sendParticles(ParticleTypes.ENCHANT, pile.x(), pile.y() + 1.0, pile.z(), 6, 0.5, 0.7, 0.5, 0.2);
			}
		}
	}

	static boolean reaches(double dx, double dy, double dz) {
		return Math.hypot(dx, dz) <= REACH && Math.abs(dy) <= REACH_Y;
	}
}
