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
		HewgMenu(int id, Inventory inventory, ContainerLevelAccess access) {
			super(id, inventory, access);
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}
}
