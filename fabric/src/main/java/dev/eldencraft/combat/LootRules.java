package dev.eldencraft.combat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eldencraft.EldenCraft;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/** Data-pack overrides for region progression, enemy families and fixed boss collections. */
public final class LootRules {
	private static JsonObject data;
	private static final Map<Integer, JsonObject> BOSSES = new HashMap<>();
	public static final String[] FAMILIES = { "beast", "soldier", "archer", "miner", "magic", "undead", "plant", "dragon", "stone", "unknown" };
	private LootRules() {}

	public static void load(MinecraftServer server) {
		BOSSES.clear();
		try (var reader = new InputStreamReader(server.getResourceManager()
			.getResourceOrThrow(Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "loot_rules.json")).open(), StandardCharsets.UTF_8)) {
			install(JsonParser.parseReader(reader).getAsJsonObject());
			EldenCraft.LOG.info("EldenCraft: seven-tier loot rules loaded: {} regions, {} families, {} boss encounters",
				data.getAsJsonObject("maps").size(), data.getAsJsonObject("models").size(), BOSSES.size());
		} catch (Exception e) {
			data = null;
			throw new IllegalStateException("Cannot load EldenCraft loot rules", e);
		}
	}

	static void install(JsonObject rules) {
		if (rules.get("version").getAsInt() != 2) throw new IllegalArgumentException("Unsupported loot rules version");
		BOSSES.clear(); data = rules;
		for (var entry : data.getAsJsonArray("bosses")) {
			var boss = entry.getAsJsonObject();
			if (BOSSES.put(boss.get("flag").getAsInt(), boss) != null) throw new IllegalArgumentException("Duplicate boss flag");
		}
	}
	public static int equipmentTier(int tier, int roll) {
		return roll >= 10 ? 0 : roll == 0 ? upgradedTier(tier) : tier;
	}

	public static JsonObject boss(int flag) { return BOSSES.get(flag); }
	private static int lookup(String table, int key, int fallback) {
		if (data == null) return fallback;
		var entry = data.getAsJsonObject(table).get(Integer.toUnsignedString(key));
		return entry == null ? fallback : entry.getAsInt();
	}
	public static int tier(SkyLoot.Death death) {
		int fallback = tierAt(death.worldId(), death.pos());
		int raw = mapTier(death.mapId(), fallback);
		return Math.clamp(lookup("placement_tiers", death.entityId(), lookup("npc_tiers", death.npcParamId(), raw)), 1, 7);
	}
	private static int mapTier(int map, int fallback) {
		if (data == null) return fallback;
		var entry = data.getAsJsonObject("maps").get(Integer.toUnsignedString(map));
		return entry == null ? fallback : entry.getAsJsonObject().get("tier").getAsInt();
	}
	/** Decode stable MC coordinates back to the exact overworld tile; interiors already have a map ID. */
	public static int tierAt(int world, Vec3 pos) {
		int area = world >>> 24;
		if (area == 60 || area == 61) {
			int x = (int) Math.floor((pos.x - (area - 60) * 100_000.0) / 256.0);
			int z = (int) Math.floor(-pos.z / 256.0);
			int tile = (area << 24) | ((x & 255) << 16) | ((z & 255) << 8);
			return mapTier(tile, area == 61 ? 6 : 1);
		}
		return mapTier(world, area >= 20 && area <= 28 || area >= 40 && area <= 43 ? 6 : 1);
	}
	public static String family(SkyLoot.Death death) {
		if (data == null) return "unknown";
		if (death.characterId() == 0) {
			var npc = data.getAsJsonObject("npc_families").get(Integer.toString(death.npcParamId()));
			return npc == null ? "unknown" : npc.getAsString();
		}
		var model = data.getAsJsonObject("models").get(Integer.toString(death.characterId()));
		return model == null ? "unknown" : model.getAsJsonObject().get("family").getAsString();
	}
	public static String role(SkyLoot.Death death, String family) {
		// Only armed skeletal models receive gear; corpses, shades and birds do not.
		if (family.equals("undead") && death.characterId() != 3500 && death.characterId() != 3510
			&& death.characterId() != 5360 && death.characterId() != 5600) return null;
		if (family.equals("magic") && !java.util.Set.of(3702,3704,3370,5880,6232,5311,5312,5320).contains(death.characterId())) return null;
		var role = data.getAsJsonObject("roles").get(family);
		return role == null ? null : role.getAsString();
	}
	public static int upgradedTier(int tier) { return tier == 5 || tier == 7 ? tier : tier + 1; }
}
