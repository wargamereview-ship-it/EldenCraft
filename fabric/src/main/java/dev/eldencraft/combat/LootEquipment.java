package dev.eldencraft.combat;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import java.util.List;

/** Curated compatible enchantments. Named sets use vanilla items until custom visuals are added. */
public final class LootEquipment {
	private LootEquipment() {}
	private static void enchant(HolderLookup.Provider registries, ItemStack stack, String name, int level) {
		boolean ours = java.util.Arrays.asList(ElementalRules.allIds()).contains(name) || WardRules.byId(name) != null;
		var key = ResourceKey.create(Registries.ENCHANTMENT, ours ? Identifier.fromNamespaceAndPath(dev.eldencraft.EldenCraft.MOD_ID, name) : Identifier.withDefaultNamespace(name));
		var found = registries.lookupOrThrow(Registries.ENCHANTMENT).get(key);
		if (found.isEmpty()) {
			// Only this mod's own can be missing (a broken data file); never lose the reward over it.
			if (!ours) throw new IllegalArgumentException("Missing enchantment " + name);
			dev.eldencraft.EldenCraft.LOG.warn("EldenCraft: enchantment {} is not loaded; reward left without it", key.identifier());
			return;
		}
		var holder = found.get();
		if (stack.is(Items.ENCHANTED_BOOK)) {
			var mutable = new ItemEnchantments.Mutable(stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
			mutable.set(holder, level);
			stack.set(DataComponents.STORED_ENCHANTMENTS, mutable.toImmutable());
		} else stack.enchant(holder, level);
	}
	public static void apply(MinecraftServer server, ItemStack stack, int tier, boolean boss, String theme) {
		apply(server.registryAccess(), stack, tier, boss, theme);
	}
	static void apply(HolderLookup.Provider registries, ItemStack stack, int tier, boolean boss, String theme) {
		String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		int primary = boss ? tier : Math.max(1, tier - 1);
		int damage = Math.min(7, primary);
		int armour = Math.min(6, primary <= 4 ? primary : primary - 1);
		int durability = Math.min(3, Math.max(1, (tier + 1) / 2));
		if (!boss && tier < 3) return;
		enchant(registries, stack, "unbreaking", durability);
		if (item.endsWith("helmet") || item.endsWith("chestplate") || item.endsWith("leggings") || item.endsWith("boots")) {
			// Fire and Blast Protection never meet an Elden Ring hit (its fire and explosions arrive as attacks); a
			// projectile set guards against ER's arrows, spells and thrown objects instead of everything.
			String protection = theme.equals("projectile") ? "projectile_protection" : "protection";
			enchant(registries, stack, protection, protection.equals("protection") ? armour : tier <= 5 ? Math.min(4, tier + 1) : tier - 1);
			if (boss && item.endsWith("boots")) {
				enchant(registries, stack, "feather_falling", Math.min(4, tier));
				if (tier >= 4) enchant(registries, stack, "depth_strider", Math.min(3, tier - 2));
			}
			if (boss && item.endsWith("helmet") && tier >= 2) {
				enchant(registries, stack, "respiration", Math.min(3, tier - 1));
			}
		} else if (item.equals("bow")) enchant(registries, stack, "power", damage);
		else if (item.equals("crossbow")) {
			enchant(registries, stack, "quick_charge", Math.min(3, tier));
			if (boss) enchant(registries, stack, "piercing", Math.min(4, tier));
		}
		else if (item.endsWith("pickaxe") || item.endsWith("shovel")) {
			enchant(registries, stack, "efficiency", Math.min(7, primary));
			if (boss && tier >= 3) enchant(registries, stack, "fortune", Math.min(3, tier - 2));
		} else if (item.equals("mace")) enchant(registries, stack, "density", Math.min(5, primary));
		else if (!item.equals("shield")) enchant(registries, stack, "sharpness", damage);
		if (boss && tier >= 6) enchant(registries, stack, "mending", 1);
	}
	/**
	 * Boss gear carries its boss's theme: armour its ward (level = tier, at most VI) and its set's trim,
	 * weapons the elemental or status enchantment of the same theme (none for a physical boss) and the
	 * boss's signature enchantment.
	 */
	static void themed(HolderLookup.Provider registries, ItemStack stack, JsonObject boss) {
		String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		int tier = boss.get("tier").getAsInt();
		var reward = boss.getAsJsonObject("reward");
		var ward = ward(boss);
		if (item.endsWith("helmet") || item.endsWith("chestplate") || item.endsWith("leggings") || item.endsWith("boots")) {
			if (ward != null) enchant(registries, stack, ward.id, WardRules.gearLevel(tier));
			if (reward.has("trim")) {
				trim(registries, stack, reward.getAsJsonObject("trim"));
				ArmourSet set = ArmourSet.byPattern(reward.getAsJsonObject("trim").get("pattern").getAsString());
				if (set != null) {
					stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
						java.util.List.of(net.minecraft.network.chat.Component.literal(set.lore()))));
				}
			}
		} else if (!item.endsWith("pickaxe") && !item.endsWith("shovel")) {
			if (ward != null) enchant(registries, stack, ward.weaponEnchantment, ElementalRules.levelForTier(tier));
			if (reward.has("signature")) {
				String signature = reward.get("signature").getAsString();
				enchant(registries, stack, signature, signatureLevel(signature, tier));
			}
			// Extra enchantments that tell this boss's weapon apart from another boss's otherwise identical one.
			if (reward.has("flair")) {
				for (var flair : reward.getAsJsonArray("flair")) enchant(registries, stack, flair.getAsString(), signatureLevel(flair.getAsString(), tier));
			}
		}
	}

	/** The level of a boss weapon's signature enchantment at a tier. */
	static int signatureLevel(String signature, int tier) {
		return switch (signature) {
			case "smite" -> Math.min(5, tier);
			case "sweeping_edge", "lunge", "wind_burst", "loyalty" -> Math.min(3, (tier + 1) / 2);
			case "efficiency" -> Math.min(5, tier);
			case "fire_aspect" -> tier >= 4 ? 2 : 1;
			case "knockback" -> tier >= 5 ? 2 : 1;
			default -> 1;
		};
	}

	/** A set piece's armour trim (vanilla pattern and material). */
	static void trim(HolderLookup.Provider registries, ItemStack stack, JsonObject trim) {
		var pattern = registries.lookupOrThrow(Registries.TRIM_PATTERN)
			.get(ResourceKey.create(Registries.TRIM_PATTERN, Identifier.withDefaultNamespace(trim.get("pattern").getAsString())));
		var material = registries.lookupOrThrow(Registries.TRIM_MATERIAL)
			.get(ResourceKey.create(Registries.TRIM_MATERIAL, Identifier.withDefaultNamespace(trim.get("material").getAsString())));
		if (pattern.isEmpty() || material.isEmpty()) {
			dev.eldencraft.EldenCraft.LOG.warn("EldenCraft: armour trim {} is not loaded; reward left untrimmed", trim);
			return;
		}
		stack.set(DataComponents.TRIM, new net.minecraft.world.item.equipment.trim.ArmorTrim(material.get(), pattern.get()));
	}

	/** A boss's ward theme, if it has one. */
	static WardRules.Ward ward(JsonObject boss) {
		return boss.has("ward") ? WardRules.byId(boss.get("ward").getAsString()) : null;
	}
	static ItemStack baseBossGear(JsonObject boss) {
		int tier = boss.get("tier").getAsInt();
		var reward = boss.getAsJsonObject("reward");
		String slot = reward.get("slot").getAsString();
		boolean armour = List.of("helmet","chestplate","leggings","boots").contains(slot);
		String material = armour ? switch (tier) { case 1 -> "leather"; case 2 -> "iron"; case 3 -> "iron"; case 4 -> "diamond"; default -> "netherite"; }
			: switch (tier) { case 1 -> "stone"; case 2,3 -> "iron"; case 4 -> "diamond"; default -> "netherite"; };
		// Bows, crossbows, maces, tridents and shields come in one material only.
		String item = List.of("bow", "crossbow", "mace", "trident", "shield").contains(slot) ? slot : material + "_" + slot;
		var id = Identifier.withDefaultNamespace(item);
		var value = BuiltInRegistries.ITEM.getValue(id);
		if (value == null || value == Items.AIR) throw new IllegalArgumentException("Missing boss item " + item);
		ItemStack stack = new ItemStack(value);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(reward.get("name").getAsString()));
		String collection = reward.has("set") ? reward.get("set").getAsString() + " collection" : "Signature reward";
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(collection + " • Tier " + tier),
			Component.literal("Earned from " + boss.get("name").getAsString()))));
		return stack;
	}
	public static ItemStack bossGear(MinecraftServer server, JsonObject boss) {
		var stack = baseBossGear(boss);
		apply(server, stack, boss.get("tier").getAsInt(), true, boss.getAsJsonObject("reward").get("theme").getAsString());
		themed(server.registryAccess(), stack, boss);
		return stack;
	}
	/** A boss's book: its ward (VII from the one boss that gives it), or the random curated book. */
	public static ItemStack bossBook(MinecraftServer server, JsonObject boss, RandomSource random) {
		return bossBook(server.registryAccess(), boss, random);
	}
	static ItemStack bossBook(HolderLookup.Provider registries, JsonObject boss, RandomSource random) {
		int tier = boss.get("tier").getAsInt();
		var ward = ward(boss);
		if (ward == null) return book(registries, tier, random);
		boolean seven = boss.has("ward_seven") && boss.get("ward_seven").getAsBoolean();
		var stack = new ItemStack(Items.ENCHANTED_BOOK);
		enchant(registries, stack, ward.id, WardRules.bookLevel(tier, seven));
		return stack;
	}
	public static ItemStack book(MinecraftServer server, int tier, RandomSource random) {
		return book(server.registryAccess(), tier, random);
	}
	static ItemStack book(HolderLookup.Provider registries, int tier, RandomSource random) {
		String[] names = {"protection","sharpness","power","efficiency","unbreaking","feather_falling","fortune","respiration"};
		String name = names[random.nextInt(names.length)];
		if (tier >= 5 && random.nextInt(tier == 5 ? 10 : 4) == 0) name = "mending";
		int level = switch (name) { case "mending" -> 1; case "unbreaking","fortune","respiration" -> Math.min(3,tier); case "feather_falling" -> Math.min(4,tier); case "protection" -> Math.min(6,tier <= 4 ? tier : tier-1); default -> Math.min(7,tier); };
		// About a third of boss books hold an elemental or status enchantment.
		if (random.nextInt(3) == 0) {
			var ids = ElementalRules.allIds();
			name = ids[random.nextInt(ids.length)];
			level = ElementalRules.levelForTier(tier);
		}
		var stack = new ItemStack(Items.ENCHANTED_BOOK);
		enchant(registries,stack,name,level);
		return stack;
	}
}
