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
		var key = ResourceKey.create(Registries.ENCHANTMENT, Identifier.withDefaultNamespace(name));
		var holder = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
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
			String protection = switch (theme) { case "fire" -> "fire_protection"; case "projectile" -> "projectile_protection"; case "blast" -> "blast_protection"; default -> "protection"; };
			enchant(registries, stack, protection, protection.equals("protection") ? armour : tier <= 5 ? Math.min(4, tier + 1) : tier - 1);
			if (boss && item.endsWith("boots")) {
				enchant(registries, stack, "feather_falling", Math.min(4, tier));
				if (tier >= 4) enchant(registries, stack, "depth_strider", Math.min(3, tier - 2));
			}
			if (boss && item.endsWith("helmet") && tier >= 2) {
				enchant(registries, stack, "respiration", Math.min(3, tier - 1));
				enchant(registries, stack, "aqua_affinity", 1);
			}
		} else if (item.equals("bow")) enchant(registries, stack, "power", damage);
		else if (item.equals("crossbow")) enchant(registries, stack, "quick_charge", Math.min(3, tier));
		else if (item.endsWith("pickaxe") || item.endsWith("shovel")) {
			enchant(registries, stack, "efficiency", Math.min(7, primary));
			if (boss && tier >= 3) enchant(registries, stack, "fortune", Math.min(3, tier - 2));
		} else if (!item.equals("shield")) enchant(registries, stack, "sharpness", damage);
		if (boss && tier >= 6) enchant(registries, stack, "mending", 1);
	}
	static ItemStack baseBossGear(JsonObject boss) {
		int tier = boss.get("tier").getAsInt();
		var reward = boss.getAsJsonObject("reward");
		String slot = reward.get("slot").getAsString();
		boolean armour = List.of("helmet","chestplate","leggings","boots").contains(slot);
		String material = armour ? switch (tier) { case 1 -> "leather"; case 2 -> "iron"; case 3 -> "iron"; case 4 -> "diamond"; default -> "netherite"; }
			: switch (tier) { case 1 -> "stone"; case 2,3 -> "iron"; case 4 -> "diamond"; default -> "netherite"; };
		String item = slot.equals("bow") || slot.equals("shield") ? slot : material + "_" + slot;
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
		var stack = new ItemStack(Items.ENCHANTED_BOOK);
		enchant(registries,stack,name,level);
		return stack;
	}
}
