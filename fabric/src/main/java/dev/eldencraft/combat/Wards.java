package dev.eldencraft.combat;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.combat.WardRules.Ward;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** The ward enchantments on worn armour and in anvils; the rules are in {@link WardRules}. */
public final class Wards {
	private static final EquipmentSlot[] ARMOUR = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

	private Wards() {}

	/** The ward an enchantment is, if it is one of this mod's wards. */
	static Ward ward(Holder<Enchantment> enchantment) {
		return enchantment.unwrapKey()
			.filter(key -> key.identifier().getNamespace().equals(EldenCraft.MOD_ID))
			.map(key -> WardRules.byId(key.identifier().getPath())).orElse(null);
	}

	/** Ward levels by ward ordinal on one item (a book's stored ones included). */
	public static int[] levels(ItemStack stack) {
		int[] levels = new int[Ward.values().length];
		if (stack.isEmpty()) return levels;
		for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
			Ward w = ward(entry.getKey());
			if (w != null) levels[w.ordinal()] = Math.max(levels[w.ordinal()], entry.getIntValue());
		}
		return levels;
	}

	/** The wearer's ward levels: for each ward, its strongest armour piece (they do not add up). */
	public static int[] worn(LivingEntity entity) {
		int[] levels = new int[Ward.values().length];
		for (EquipmentSlot slot : ARMOUR) {
			int[] piece = levels(entity.getItemBySlot(slot));
			for (int i = 0; i < levels.length; i++) levels[i] = Math.max(levels[i], piece[i]);
		}
		return levels;
	}

	/** An Elden Ring hit's damage after the wearer's element wards ({@code shares} null: unknown make-up). */
	public static float reduce(LivingEntity entity, float damage, float[] shares) {
		return shares == null ? damage : WardRules.reduce(damage, shares, worn(entity));
	}

	/** An anvil result with any VII that neither input had brought down to VI. */
	public static void capAnvilResult(ItemStack result, ItemStack left, ItemStack right) {
		int[] have = levels(result), a = levels(left), b = levels(right);
		boolean change = false;
		for (Ward w : Ward.values()) {
			int i = w.ordinal();
			change |= WardRules.anvilLevel(have[i], a[i], b[i]) != have[i];
		}
		if (!change) return;
		EnchantmentHelper.updateEnchantments(result, mutable -> {
			for (var holder : mutable.keySet().toArray(Holder[]::new)) {
				@SuppressWarnings("unchecked") Holder<Enchantment> enchantment = (Holder<Enchantment>) holder;
				Ward w = ward(enchantment);
				if (w == null) continue;
				int i = w.ordinal();
				mutable.set(enchantment, WardRules.anvilLevel(mutable.getLevel(enchantment), a[i], b[i]));
			}
		});
	}
}
