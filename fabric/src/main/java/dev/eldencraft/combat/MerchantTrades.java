package dev.eldencraft.combat;

import java.util.List;

/**
 * What a merchant sells, by the tier of the region it stands in, priced in experience levels ("runes").
 * Every number here is a starting point to be tuned from play: costs rise with the tier, and so do the goods.
 */
public final class MerchantTrades {
	/** One offer: an item id (a vanilla item), how many, the level cost, and how many times it can be bought between grace rests. */
	public record Trade(String item, int count, int levels, int uses) {
	}

	private static final List<List<Trade>> BY_TIER = List.of(
		List.of(t("bread", 6, 1, 8), t("cooked_beef", 4, 2, 8), t("torch", 16, 1, 6), t("arrow", 16, 2, 6),
			t("coal", 8, 2, 6), t("iron_ingot", 3, 4, 4), t("shield", 1, 4, 2), t("iron_sword", 1, 6, 2)),
		List.of(t("cooked_porkchop", 6, 2, 8), t("arrow", 32, 3, 6), t("bow", 1, 4, 2), t("crossbow", 1, 5, 2),
			t("lapis_lazuli", 8, 3, 6), t("iron_boots", 1, 5, 2), t("iron_leggings", 1, 7, 2), t("iron_chestplate", 1, 8, 2)),
		List.of(t("golden_carrot", 8, 3, 6), t("iron_ingot", 8, 8, 4), t("gold_ingot", 4, 6, 4), t("shield", 1, 5, 2),
			t("golden_apple", 2, 14, 2), t("diamond", 1, 12, 2), t("iron_pickaxe", 1, 6, 2), t("iron_helmet", 1, 6, 2)),
		List.of(t("golden_apple", 3, 18, 2), t("lapis_lazuli", 16, 6, 6), t("diamond", 2, 20, 2), t("diamond_pickaxe", 1, 24, 1),
			t("diamond_sword", 1, 24, 1), t("diamond_boots", 1, 20, 1), t("arrow", 48, 5, 6), t("cooked_beef", 12, 4, 8)),
		List.of(t("diamond_helmet", 1, 26, 1), t("diamond_leggings", 1, 32, 1), t("diamond_chestplate", 1, 36, 1), t("diamond", 4, 36, 2),
			t("golden_apple", 4, 24, 2), t("netherite_scrap", 1, 40, 1), t("arrow", 64, 6, 6), t("cooked_beef", 16, 5, 8)),
		List.of(t("diamond", 6, 50, 2), t("netherite_scrap", 1, 44, 2), t("enchanted_golden_apple", 1, 70, 1), t("golden_apple", 5, 30, 2),
			t("diamond_chestplate", 1, 40, 1), t("diamond_sword", 1, 30, 1), t("arrow", 64, 6, 6), t("cooked_beef", 16, 5, 8)),
		List.of(t("netherite_scrap", 2, 80, 1), t("netherite_ingot", 1, 120, 1), t("enchanted_golden_apple", 1, 60, 1), t("diamond", 8, 60, 2),
			t("golden_apple", 6, 36, 2), t("diamond_leggings", 1, 40, 1), t("arrow", 64, 6, 6), t("cooked_beef", 16, 5, 8)));

	/** Roderika's spawn eggs: animals and helpers you can tame or ride, paid in levels. */
	public static final List<Trade> RODERIKA = List.of(t("wolf_spawn_egg", 1, 6, 4), t("fox_spawn_egg", 1, 4, 4), t("cat_spawn_egg", 1, 4, 4),
		t("parrot_spawn_egg", 1, 4, 4), t("donkey_spawn_egg", 1, 6, 2), t("horse_spawn_egg", 1, 10, 2), t("llama_spawn_egg", 1, 8, 2),
		t("allay_spawn_egg", 1, 14, 2));

	private MerchantTrades() {
	}

	private static Trade t(String item, int count, int levels, int uses) {
		return new Trade(item, count, levels, uses);
	}

	/** The offers of a region tier (1 to 7). */
	public static List<Trade> forTier(int tier) {
		return BY_TIER.get(Math.clamp(tier, 1, BY_TIER.size()) - 1);
	}
}
