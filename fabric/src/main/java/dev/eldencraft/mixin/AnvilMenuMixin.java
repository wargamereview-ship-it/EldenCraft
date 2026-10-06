package dev.eldencraft.mixin;

import dev.eldencraft.combat.Wards;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.ItemCombinerMenuSlotDefinition;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Two ward VI books or pieces do not make a VII on the anvil: that level only comes from its one boss. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin extends ItemCombinerMenu {
	private AnvilMenuMixin(MenuType<?> type, int id, Inventory inventory, ContainerLevelAccess access, ItemCombinerMenuSlotDefinition slots) {
		super(type, id, inventory, access, slots);
	}

	@Inject(method = "createResult", at = @At("RETURN"))
	private void eldencraft$capWards(CallbackInfo ci) {
		ItemStack result = this.resultSlots.getItem(0);
		if (result.isEmpty()) {
			return;
		}
		ItemStack left = this.inputSlots.getItem(0);
		ItemStack capped = result.copy();
		Wards.capAnvilResult(capped, left, this.inputSlots.getItem(1));
		if (EnchantmentHelper.getEnchantmentsForCrafting(capped).equals(EnchantmentHelper.getEnchantmentsForCrafting(result))) {
			return;
		}
		// When the cap undoes the whole combination there is nothing to make.
		this.resultSlots.setItem(0, ItemStack.isSameItemSameComponents(capped, left) ? ItemStack.EMPTY : capped);
	}
}
