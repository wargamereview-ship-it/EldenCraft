package dev.eldencraft.combat;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.world.SkyCollision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft loot for Elden Ring enemies the player killed. Server thread only.
 *
 * <p>The native bridge reports each death once per enemy life, only when ER's own last-attacker
 * attribution is the player (MC or ER weapons). The enemy's native maximum HP, which already
 * includes ER's area scaling, picks a progression tier; bosses use their own tiers and pay out
 * once per placed boss, recorded in the world folder. Tables are ordinary data-pack loot tables:
 * {@code eldencraft:enemy/tier_N}, {@code eldencraft:boss/tier_N}, and an optional bonus table
 * {@code eldencraft:enemy/model/cNNNN} for one enemy model. ER's own drops and runes are kept.
 */
public final class SkyLoot {
	/** Native max HP upper bounds for enemy tiers 1-4; anything stronger is tier 5. */
	private static final int[] ENEMY_TIERS = { 600, 1500, 4000, 10000 };
	private static final int[] BOSS_TIERS = { 3500, 7000, 12000, 20000 };
	private static final int[] ENEMY_XP = { 2, 4, 8, 16, 30 };
	private static final int[] BOSS_XP = { 60, 120, 220, 350, 500 };
	private static final String CLAIMS_FILE = "eldencraft_boss_rewards.txt";

	/** A death reported by the native bridge; {@code pos} is the corpse's feet in MC coordinates. */
	public record Death(Vec3 pos, int entityId, int worldId, int npcParamId, int maxHp, int characterId, boolean boss) {
	}

	/** Drops waiting for ER collision under them before they may fall. */
	private static final List<ItemEntity> FLOATING = new ArrayList<>();
	private static MinecraftServer claimsServer;
	private static final Set<String> CLAIMED = new HashSet<>();

	private SkyLoot() {
	}

	private static int tier(int maxHp, int[] bounds) {
		for (int i = 0; i < bounds.length; i++) {
			if (maxHp < bounds[i]) {
				return i + 1;
			}
		}
		return bounds.length + 1;
	}

	/** At world start: a table that failed to parse loads as empty, which would silently drop nothing. */
	public static void verify(MinecraftServer server) {
		List<String> missing = new ArrayList<>();
		for (String kind : new String[] { "enemy", "boss" }) {
			for (int tier = 1; tier <= ENEMY_TIERS.length + 1; tier++) {
				if (table(server, kind + "/tier_" + tier) == LootTable.EMPTY) {
					missing.add(kind + "/tier_" + tier);
				}
			}
		}
		if (missing.isEmpty()) {
			EldenCraft.LOG.info("EldenCraft: enemy and boss loot tables loaded (5 tiers each)");
		} else {
			EldenCraft.LOG.error("EldenCraft: loot tables missing or invalid (see data pack errors above): {}", missing);
		}
	}

	private static LootTable table(MinecraftServer server, String path) {
		var key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, path));
		return server.reloadableRegistries().getLootTable(key);
	}

	public static void enemyDied(ServerPlayer player, Death death) {
		if (!player.isAlive() || death.pos() == null || !Double.isFinite(death.pos().x) || !Double.isFinite(death.pos().y)
			|| !Double.isFinite(death.pos().z)) {
			return;
		}
		ServerLevel level = player.level();
		MinecraftServer server = level.getServer();
		boolean bossReward = death.boss() && claimBoss(server, death);
		int tier = bossReward ? tier(death.maxHp(), BOSS_TIERS) : tier(death.maxHp(), ENEMY_TIERS);
		String main = (bossReward ? "boss/tier_" : "enemy/tier_") + tier;
		String model = String.format("enemy/model/c%04d", death.characterId());

		LootParams params = new LootParams.Builder(level)
			.withParameter(LootContextParams.ORIGIN, death.pos())
			.withOptionalParameter(LootContextParams.THIS_ENTITY, player)
			.withLuck(player.getLuck())
			.create(LootContextParamSets.CHEST);
		List<ItemStack> drops = new ArrayList<>(table(server, main).getRandomItems(params));
		LootTable bonus = table(server, model);
		if (bonus != LootTable.EMPTY) {
			drops.addAll(bonus.getRandomItems(params));
		}

		Vec3 at = death.pos();
		boolean grounded = collisionKnown(at);
		for (ItemStack stack : drops) {
			if (stack.isEmpty()) {
				continue;
			}
			var random = level.getRandom();
			ItemEntity item = grounded
				? new ItemEntity(level, at.x, at.y + 0.5, at.z, stack, random.nextGaussian() * 0.05, 0.2, random.nextGaussian() * 0.05)
				: new ItemEntity(level, at.x, at.y + 0.3, at.z, stack, 0.0, 0.0, 0.0);
			item.setDefaultPickUpDelay();
			if (!grounded) {
				// The corpse may lie outside the scanned collision around the player: hold the
				// drop where it is instead of letting it fall into the void below ER's terrain.
				item.setNoGravity(true);
				FLOATING.add(item);
			}
			level.addFreshEntity(item);
		}
		int xp = (bossReward ? BOSS_XP : ENEMY_XP)[tier - 1];
		if (grounded) {
			ExperienceOrb.award(level, at.add(0, 0.5, 0), xp);
		} else {
			player.giveExperiencePoints(xp);
		}
		EldenCraft.LOG.info("EldenCraft: loot for c{} (npc {}, entity {}, {} HP{}): {} tier {}{}, {} xp, {}{}",
			String.format("%04d", death.characterId()), death.npcParamId(), death.entityId(), death.maxHp(),
			death.boss() ? bossReward ? ", boss" : ", boss already rewarded" : "", bossReward ? "boss" : "enemy", tier,
			bonus != LootTable.EMPTY ? " + " + model : "", xp, drops.stream().filter(s -> !s.isEmpty()).map(ItemStack::toString).toList(),
			grounded ? "" : " (held until terrain collision arrives)");
	}

	private static boolean collisionKnown(Vec3 at) {
		return SkyCollision.isKnown((int) Math.floor(at.x), (int) Math.floor(at.y) - 1, (int) Math.floor(at.z));
	}

	/** Called every server tick: release held drops once their terrain has been scanned. */
	public static void tick() {
		if (FLOATING.isEmpty()) {
			return;
		}
		FLOATING.removeIf(item -> {
			if (item.isRemoved()) {
				return true;
			}
			if (collisionKnown(item.position())) {
				item.setNoGravity(false);
				return true;
			}
			return false;
		});
	}

	/**
	 * True once per placed boss in this world. Map-placed bosses carry a map event entity ID;
	 * a boss without one is keyed by its NpcParam so it still cannot pay out repeatedly.
	 */
	private static boolean claimBoss(MinecraftServer server, Death death) {
		Path file = server.getWorldPath(LevelResource.ROOT).resolve(CLAIMS_FILE);
		if (claimsServer != server) {
			claimsServer = server;
			CLAIMED.clear();
			try {
				if (Files.exists(file)) {
					for (String line : Files.readAllLines(file)) {
						if (!line.isBlank() && !line.startsWith("#")) {
							CLAIMED.add(line.trim());
						}
					}
				}
			} catch (IOException e) {
				EldenCraft.LOG.warn("EldenCraft: couldn't read {}", file, e);
			}
		}
		String key = death.entityId() != 0
			? Integer.toUnsignedString(death.worldId(), 16) + ":" + Integer.toUnsignedString(death.entityId())
			: "npc:" + death.npcParamId();
		if (!CLAIMED.add(key)) {
			return false;
		}
		try {
			Files.writeString(file, key + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			EldenCraft.LOG.warn("EldenCraft: couldn't record boss reward {} in {}", key, file, e);
		}
		return true;
	}
}
