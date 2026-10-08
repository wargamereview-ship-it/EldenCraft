package dev.eldencraft.items;

import dev.eldencraft.EldenCraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Items the Elden Ring character receives (pickups, chests, boss drops, shops, quest rewards), arriving from the
 * bridge as Elden Ring item ids and handed to the Minecraft player as the matching item.
 */
public final class ErMirror {
	private ErMirror() {
	}

	public static void receive(ServerPlayer player, int category, int param, int count) {
		if (count <= 0) {
			return;
		}
		int id = param, level = 0;
		if (category == ErRef.WEAPON) {
			// Weapon ids carry the upgrade level in their last two digits (and the infusion in the hundreds).
			level = param % 100;
			id = param - level;
			if (ErData.weapon(id) == null && ErData.weapon(param) != null) {
				id = param;
				level = 0;
			}
		}
		if (category == ErRef.GOODS) {
			// Flasks are the character's own (ErFlasks); only make sure they are in the inventory.
			if (ErGoods.isCrimson(param) || ErGoods.isCerulean(param)) {
				ErFlasks.refreshStacks(player);
				return;
			}
			ErGoods.onReceived(player, param);
		}
		ItemStack stack = ErItems.stack(category, id, level, count);
		if (stack.isEmpty()) {
			EldenCraft.LOG.info("EldenCraft: Elden Ring item category {} id {} has no Minecraft counterpart", category, param);
			return;
		}
		int total = count;
		while (total > 0) {
			ItemStack part = stack.copyWithCount(Math.min(total, stack.getMaxStackSize()));
			total -= part.getCount();
			if (!player.isAlive() || !player.getInventory().add(part)) {
				ItemEntity drop = new ItemEntity(player.level(), player.getX(), player.getY() + 0.5, player.getZ(), part);
				drop.setNoPickUpDelay();
				player.level().addFreshEntity(drop);
			}
		}
		player.sendOverlayMessage(Component.literal("Received " + stack.getHoverName().getString() + (count > 1 ? " ×" + count : "")));
		EldenCraft.LOG.info("EldenCraft: mirrored Elden Ring item {} {} x{} as {}", category, param, count, stack.getHoverName().getString());
	}
}
