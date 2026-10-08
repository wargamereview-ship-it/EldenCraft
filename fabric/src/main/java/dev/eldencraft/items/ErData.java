package dev.eldencraft.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eldencraft.EldenCraft;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Elden Ring's item tables, read from the game's own params by tools/generate_items.py and shipped under
 * data/eldencraft/er. Loaded once, on first use, and never changed.
 */
public final class ErData {
	private ErData() {
	}

	private static Map<Integer, JsonObject> weapons, armour, talismans, goods, spells, ashes, reinforce, graphs, effects, classes;
	private static Map<Integer, JsonArray> elementCorrect, materials, shops;
	private static Map<Integer, Integer> merchants;

	public static Map<Integer, JsonObject> weapons() { load(); return weapons; }
	public static Map<Integer, JsonObject> armour() { load(); return armour; }
	public static Map<Integer, JsonObject> talismans() { load(); return talismans; }
	public static Map<Integer, JsonObject> goods() { load(); return goods; }
	public static Map<Integer, JsonObject> spells() { load(); return spells; }
	public static Map<Integer, JsonObject> ashes() { load(); return ashes; }
	public static Map<Integer, JsonObject> classes() { load(); return classes; }

	public static @Nullable JsonObject weapon(int id) { return weapons().get(id); }
	public static @Nullable JsonObject armour(int id) { return armour().get(id); }
	public static @Nullable JsonObject talisman(int id) { return talismans().get(id); }
	public static @Nullable JsonObject goods(int id) { return goods().get(id); }
	public static @Nullable JsonObject spell(int id) { return spells().get(id); }
	public static @Nullable JsonObject ash(int id) { return ashes().get(id); }
	public static @Nullable JsonObject reinforce(int id) { load(); return reinforce.get(id); }
	public static @Nullable JsonObject graph(int id) { load(); return graphs.get(id); }
	public static @Nullable JsonObject effect(int id) { load(); return id > 0 ? effects.get(id) : null; }
	public static @Nullable JsonArray elementCorrect(int id) { load(); return elementCorrect.get(id); }
	public static @Nullable JsonArray materials(int id) { load(); return materials.get(id); }
	/** A merchant's lineup (ShopLineupParam rows from {@code base}): row, kind, id, price in runes, stock, count. */
	public static @Nullable JsonArray shop(int base) { load(); return shops.get(base); }
	/** The lineup a merchant (NpcParam row) sells, from its Bell Bearing (tools/generate_merchants.py); 0 if none. */
	public static int merchantLineup(int npcRow) { load(); return merchants.getOrDefault(npcRow, 0); }

	private static synchronized void load() {
		if (weapons != null) {
			return;
		}
		long start = System.nanoTime();
		weapons = objects("weapons");
		armour = objects("armour");
		talismans = objects("talismans");
		goods = objects("goods");
		spells = objects("spells");
		ashes = objects("ashes");
		reinforce = objects("reinforce");
		graphs = objects("graphs");
		effects = objects("speffects");
		classes = objects("classes");
		elementCorrect = arrays("element_correct");
		materials = arrays("materials");
		shops = arrays("shops");
		Map<Integer, Integer> lineups = new HashMap<>();
		root("merchants").entrySet().forEach(e -> lineups.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
		merchants = Collections.unmodifiableMap(lineups);
		EldenCraft.LOG.info("EldenCraft: Elden Ring items loaded ({} weapons, {} armour, {} talismans, {} goods, {} spells) in {} ms",
			weapons.size(), armour.size(), talismans.size(), goods.size(), spells.size(), (System.nanoTime() - start) / 1_000_000);
	}

	private static JsonObject root(String name) {
		try (var in = ErData.class.getResourceAsStream("/data/eldencraft/er/" + name + ".json")) {
			if (in == null) {
				EldenCraft.LOG.error("EldenCraft: missing Elden Ring item table {}", name);
				return new JsonObject();
			}
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			EldenCraft.LOG.error("EldenCraft: unreadable Elden Ring item table {}", name, e);
			return new JsonObject();
		}
	}

	private static Map<Integer, JsonObject> objects(String name) {
		Map<Integer, JsonObject> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : root(name).entrySet()) {
			out.put(Integer.parseInt(e.getKey()), e.getValue().getAsJsonObject());
		}
		return Collections.unmodifiableMap(out);
	}

	private static Map<Integer, JsonArray> arrays(String name) {
		Map<Integer, JsonArray> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : root(name).entrySet()) {
			out.put(Integer.parseInt(e.getKey()), e.getValue().getAsJsonArray());
		}
		return Collections.unmodifiableMap(out);
	}

	// ---- field helpers (absent fields are zero, as the generator drops zeros) -----------------------

	public static int i(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null ? 0 : e.getAsInt();
	}

	public static double d(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null ? 0.0 : e.getAsDouble();
	}

	public static String s(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null ? "" : e.getAsString();
	}

	/** An array field as doubles, padded with zeros to {@code size}. */
	public static double[] arr(JsonObject o, String key, int size) {
		double[] out = new double[size];
		JsonElement e = o.get(key);
		if (e != null && e.isJsonArray()) {
			JsonArray a = e.getAsJsonArray();
			for (int k = 0; k < Math.min(size, a.size()); k++) {
				out[k] = a.get(k).getAsDouble();
			}
		}
		return out;
	}

	public static int[] ints(JsonObject o, String key, int size) {
		double[] values = arr(o, key, size);
		int[] out = new int[size];
		for (int k = 0; k < size; k++) {
			out[k] = (int) values[k];
		}
		return out;
	}
}
