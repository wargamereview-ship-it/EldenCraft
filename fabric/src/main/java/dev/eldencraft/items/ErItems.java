package dev.eldencraft.items;

import com.google.gson.JsonObject;
import dev.eldencraft.EldenCraft;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * Elden Ring's items as Minecraft items. A handful of registered items (one per behaviour) carry an {@link ErRef}
 * component; the stack's name, art, stack size, equipment slot and blocking all come from the Elden Ring row it names.
 */
public final class ErItems {
	private ErItems() {
	}

	public static final DataComponentType<ErRef> REF = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
		id("er_item"), DataComponentType.<ErRef>builder().persistent(ErRef.CODEC).networkSynchronized(ErRef.STREAM_CODEC).build());

	public static final Item WEAPON = item("er_weapon", ErWeaponItem::new, 1);
	public static final Item BOW = item("er_bow", ErBowItem::new, 1);
	public static final Item CROSSBOW = item("er_crossbow", ErCrossbowItem::new, 1);
	public static final Item AMMO = item("er_ammo", ErAmmoItem::new, 99);
	public static final Item SHIELD = item("er_shield", Item::new, 1);
	public static final Item CATALYST = item("er_catalyst", ErCatalystItem::new, 1);
	public static final Item ARMOUR = item("er_armour", Item::new, 1);
	public static final Item TALISMAN = item("er_talisman", Item::new, 1);
	public static final Item GOODS = item("er_goods", ErGoodsItem::new, 99);
	public static final Item ASH = item("er_ash", Item::new, 1);

	private static final String[] WEIGHT_ART = { "light", "medium", "heavy" };
	private static final String[] SLOT_ART = { "helm", "chest", "gauntlets", "legs" };
	/** ER's four armour slots on Minecraft's: gauntlets go where boots would (Minecraft has no arm slot). */
	private static final EquipmentSlot[] SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.FEET, EquipmentSlot.LEGS };

	public static void init() {
		tab("er_weapons", "Elden Ring: Weapons", () -> weapon(1000000, 0), output -> sorted(ErData.weapons(), w -> ErData.i(w, "base") == 0)
			.forEach(e -> output.accept(weapon(e.getKey(), 0))));
		tab("er_armour", "Elden Ring: Armour", () -> armour(140100), output -> sorted(ErData.armour(), a -> true)
			.forEach(e -> output.accept(armour(e.getKey()))));
		tab("er_talismans", "Elden Ring: Talismans", () -> talisman(1000), output -> sorted(ErData.talismans(), t -> true)
			.forEach(e -> output.accept(talisman(e.getKey()))));
		tab("er_goods", "Elden Ring: Items", () -> goods(1001, 1), output -> {
			sorted(ErData.goods(), g -> ErData.i(g, "same") == 0 && !ErData.s(g, "k").equals("sorcery") && !ErData.s(g, "k").equals("incantation"))
				.forEach(e -> output.accept(goods(e.getKey(), 1)));
			sorted(ErData.ashes(), a -> true).forEach(e -> output.accept(ash(e.getKey())));
		});
		tab("er_spells", "Elden Ring: Spells", () -> goods(4000, 1), output -> sorted(ErData.goods(),
			g -> ErData.i(g, "same") == 0 && (ErData.s(g, "k").equals("sorcery") || ErData.s(g, "k").equals("incantation")))
			.forEach(e -> output.accept(goods(e.getKey(), 1))));
	}

	private static List<Map.Entry<Integer, JsonObject>> sorted(Map<Integer, JsonObject> table, java.util.function.Predicate<JsonObject> keep) {
		List<Map.Entry<Integer, JsonObject>> out = new ArrayList<>();
		for (var e : table.entrySet()) {
			if (keep.test(e.getValue())) {
				out.add(e);
			}
		}
		out.sort(Comparator.<Map.Entry<Integer, JsonObject>>comparingInt(e -> ErData.i(e.getValue(), "sort")).thenComparingInt(Map.Entry::getKey));
		return out;
	}

	private static void tab(String name, String title, java.util.function.Supplier<ItemStack> icon, java.util.function.Consumer<CreativeModeTab.Output> items) {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id(name), FabricCreativeModeTab.builder()
			.title(Component.literal(title)).icon(icon).displayItems((params, output) -> items.accept(output)).build());
	}

	private static Item item(String name, Function<Item.Properties, Item> make, int stack) {
		var key = ResourceKey.create(Registries.ITEM, id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, make.apply(new Item.Properties().setId(key).stacksTo(stack)));
	}

	static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, path);
	}

	// ---- reading stacks ----------------------------------------------------------------------------

	public static @Nullable ErRef ref(ItemStack stack) {
		return stack.isEmpty() ? null : stack.get(REF);
	}

	public static @Nullable JsonObject row(ErRef ref) {
		return switch (ref.kind()) {
			case ErRef.WEAPON -> ErData.weapon(ref.id());
			case ErRef.ARMOUR -> ErData.armour(ref.id());
			case ErRef.TALISMAN -> ErData.talisman(ref.id());
			case ErRef.GOODS -> ErData.goods(ref.id());
			case ErRef.ASH -> ErData.ash(ref.id());
			default -> null;
		};
	}

	public static @Nullable JsonObject weaponRow(ItemStack stack) {
		ErRef ref = ref(stack);
		return ref != null && ref.kind() == ErRef.WEAPON ? ErData.weapon(ref.id()) : null;
	}

	// ---- making stacks -----------------------------------------------------------------------------

	/** Any Elden Ring item by its inventory category and param row; empty when the row is not one we port. */
	public static ItemStack stack(int kind, int id, int level, int count) {
		ItemStack stack = switch (kind) {
			case ErRef.WEAPON -> weapon(id, level);
			case ErRef.ARMOUR -> armour(id);
			case ErRef.TALISMAN -> talisman(id);
			case ErRef.GOODS -> goods(id, count);
			case ErRef.ASH -> ash(id);
			default -> ItemStack.EMPTY;
		};
		if (!stack.isEmpty() && kind != ErRef.GOODS) {
			stack.setCount(Math.min(count, stack.getMaxStackSize()));
		}
		return stack;
	}

	public static ItemStack weapon(int id, int level) {
		JsonObject w = ErData.weapon(id);
		if (w == null) {
			return ItemStack.EMPTY;
		}
		String behaviour = ErData.s(w, "b");
		Item item = switch (behaviour) {
			case "bow" -> BOW;
			case "crossbow" -> CROSSBOW;
			case "ammo" -> AMMO;
			case "shield" -> SHIELD;
			case "staff", "seal" -> CATALYST;
			default -> WEAPON;
		};
		int max = ErWeapons.maxLevel(w);
		level = Math.clamp(level, 0, max);
		ItemStack stack = base(item, new ErRef(ErRef.WEAPON, id, level), ErData.s(w, "n") + (level > 0 ? " +" + level : ""),
			"weapon/" + ErData.s(w, "art"), ErData.i(w, "tint"), ErData.i(w, "rar"));
		if (behaviour.equals("ammo")) {
			stack.set(DataComponents.MAX_STACK_SIZE, 99);
			return stack;
		}
		ItemAttributeModifiers.Builder mods = ItemAttributeModifiers.builder();
		double speed = ErWeapons.attackSpeed(ErData.i(w, "t"));
		mods.add(Attributes.ATTACK_SPEED, new AttributeModifier(id("weapon_speed"), speed - 4.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
		double reach = ErWeapons.reach(ErData.i(w, "t"));
		if (reach != 0) {
			mods.add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(id("weapon_reach"), reach, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
		}
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, mods.build());
		hide(stack, DataComponents.ATTRIBUTE_MODIFIERS);
		if (behaviour.equals("shield")) {
			stack.set(DataComponents.BLOCKS_ATTACKS, ErWeapons.blocking(w, level));
		}
		return stack;
	}

	public static ItemStack armour(int id) {
		JsonObject a = ErData.armour(id);
		if (a == null) {
			return ItemStack.EMPTY;
		}
		int slot = ErData.i(a, "slot");
		int weight = ErArmour.weightClass(slot, ErData.d(a, "w"));
		ItemStack stack = base(ARMOUR, new ErRef(ErRef.ARMOUR, id, 0), ErData.s(a, "n"),
			"armour/" + SLOT_ART[slot] + "_" + WEIGHT_ART[weight], ErData.i(a, "tint"), ErData.i(a, "rar"));
		// Gauntlets are worn invisibly (the chest piece already covers the arms).
		ResourceKey<EquipmentAsset> asset = ResourceKey.create(EquipmentAssets.ROOT_ID,
			id(slot == 2 ? "er_hidden" : "er_" + WEIGHT_ART[weight]));
		stack.set(DataComponents.EQUIPPABLE, Equippable.builder(SLOTS[slot]).setAsset(asset)
			.setEquipSound(weight == 2 ? SoundEvents.ARMOR_EQUIP_IRON : weight == 1 ? SoundEvents.ARMOR_EQUIP_CHAIN : SoundEvents.ARMOR_EQUIP_LEATHER)
			.setDamageOnHurt(false).build());
		stack.set(DataComponents.DYED_COLOR, new DyedItemColor(ErData.i(a, "tint")));
		hide(stack, DataComponents.DYED_COLOR);
		return stack;
	}

	public static ItemStack talisman(int id) {
		JsonObject t = ErData.talisman(id);
		return t == null ? ItemStack.EMPTY
			: base(TALISMAN, new ErRef(ErRef.TALISMAN, id, 0), ErData.s(t, "n"), "misc/talisman", ErData.i(t, "tint"), ErData.i(t, "rar"));
	}

	public static ItemStack goods(int id, int count) {
		JsonObject g = ErData.goods(id);
		if (g == null) {
			return ItemStack.EMPTY;
		}
		int canonical = ErData.i(g, "same");
		if (canonical != 0) {
			return goods(canonical, count);
		}
		ItemStack stack = base(GOODS, new ErRef(ErRef.GOODS, id, ErData.i(g, "lv")), ErData.s(g, "n"), "goods/" + ErGoods.art(g),
			ErData.i(g, "tint"), ErData.i(g, "rar"));
		int max = Math.clamp(ErData.i(g, "max"), 1, 99);
		stack.set(DataComponents.MAX_STACK_SIZE, max);
		stack.setCount(Math.clamp(count, 1, max));
		return stack;
	}

	public static ItemStack ash(int id) {
		JsonObject a = ErData.ash(id);
		return a == null ? ItemStack.EMPTY
			: base(ASH, new ErRef(ErRef.ASH, id, 0), "Ash of War: " + ErData.s(a, "n"), "goods/ash_of_war", ErData.i(a, "tint"), 1);
	}

	private static ItemStack base(Item item, ErRef ref, String name, String art, int tint, int rarity) {
		ItemStack stack = new ItemStack(item);
		stack.set(REF, ref);
		stack.set(DataComponents.ITEM_NAME, Component.literal(name));
		stack.set(DataComponents.ITEM_MODEL, id("er/" + art));
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(), List.of(tint == 0 ? 0xB9BDC4 : tint)));
		stack.set(DataComponents.RARITY, switch (rarity) {
			case 1 -> Rarity.UNCOMMON;
			case 2 -> Rarity.RARE;
			case 3 -> Rarity.EPIC;
			default -> Rarity.COMMON;
		});
		return stack;
	}

	private static void hide(ItemStack stack, DataComponentType<?> type) {
		TooltipDisplay display = stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
		var hidden = new LinkedHashSet<>(display.hiddenComponents());
		hidden.add(type);
		stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(display.hideTooltip(), hidden));
	}

	/** A stack rebuilt from its row, keeping its count: used after upgrades and when data changes. */
	public static ItemStack rebuild(ItemStack old, int level) {
		ErRef ref = ref(old);
		if (ref == null) {
			return old;
		}
		ItemStack fresh = stack(ref.kind(), ref.id(), level, old.getCount());
		return fresh.isEmpty() ? old : fresh;
	}
}
