package dev.eldencraft.combat;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/**
 * Hewg's upgrade tab, as Minecraft's anvil. R beside him (the native bridge sends IN_OPEN_ANVIL) opens a normal anvil:
 * repair, combine, rename and apply enchanted books, paid in experience levels like everything else here. It needs no
 * anvil block under it, and an anvil block is never damaged by it.
 */
public final class HewgAnvil {
	private HewgAnvil() {
	}

	public static void open(MinecraftServer server, UUID player) {
		server.execute(() -> {
			ServerPlayer p = server.getPlayerList().getPlayer(player);
			if (p == null || !p.isAlive()) {
				return;
			}
			p.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new HewgMenu(id, inventory, ContainerLevelAccess.create(p.level(), p.blockPosition())),
				Component.literal("Hewg's Anvil")));
		});
	}

	private static final class HewgMenu extends AnvilMenu {
		/** Smithing stones the pending Elden Ring upgrade takes from the right slot (0: not an Elden Ring upgrade). */
		private int stones;

		HewgMenu(int id, Inventory inventory, ContainerLevelAccess access) {
			super(id, inventory, access);
		}

		/**
		 * An Elden Ring weapon on the left and the right smithing stones on the right make the next upgrade, as Hewg
		 * does it: the stones Elden Ring asks for that level and nothing else. Everything else is a normal anvil.
		 */
		@Override
		public void createResult() {
			this.stones = 0;
			ItemStack left = this.inputSlots.getItem(0), right = this.inputSlots.getItem(1);
			var ref = dev.eldencraft.items.ErItems.ref(left);
			var row = dev.eldencraft.items.ErItems.weaponRow(left);
			if (ref == null || row == null) {
				super.createResult();
				return;
			}
			this.resultSlots.setItem(0, ItemStack.EMPTY);
			var cost = dev.eldencraft.items.ErWeapons.upgradeCost(row, ref.level());
			var stone = dev.eldencraft.items.ErItems.ref(right);
			if (cost == null || cost.isEmpty() || stone == null || stone.kind() != dev.eldencraft.items.ErRef.GOODS) {
				return;
			}
			var need = cost.get(0).getAsJsonArray();
			int material = need.get(0).getAsInt(), count = need.get(1).getAsInt();
			if (stone.id() != material || right.getCount() < count) {
				return;
			}
			this.stones = count;
			this.resultSlots.setItem(0, dev.eldencraft.items.ErItems.rebuild(left, ref.level() + 1));
			this.broadcastChanges();
		}

		@Override
		protected boolean mayPickup(Player player, boolean hasItem) {
			return this.stones > 0 ? hasItem : super.mayPickup(player, hasItem);
		}

		@Override
		protected void onTake(Player player, ItemStack carried) {
			if (this.stones <= 0) {
				super.onTake(player, carried);
				return;
			}
			this.inputSlots.setItem(0, ItemStack.EMPTY);
			ItemStack right = this.inputSlots.getItem(1);
			right.shrink(this.stones);
			this.inputSlots.setItem(1, right.isEmpty() ? ItemStack.EMPTY : right);
			this.stones = 0;
			player.level().playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.SMITHING_TABLE_USE,
				net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}
}
