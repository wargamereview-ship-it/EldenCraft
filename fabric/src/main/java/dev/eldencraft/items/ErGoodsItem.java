package dev.eldencraft.items;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Elden Ring goods: right-click uses one (see {@link ErGoods}). */
public class ErGoodsItem extends Item {
	public ErGoodsItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		ErRef ref = ErItems.ref(stack);
		if (ref == null) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer server) {
			if (!ErGoods.use(server, stack, ref)) {
				return InteractionResult.FAIL;
			}
			player.getCooldowns().addCooldown(stack, 10);
		}
		return InteractionResult.SUCCESS;
	}
}
