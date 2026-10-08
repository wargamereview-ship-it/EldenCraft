package dev.eldencraft.items;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Glintstone staffs and sacred seals: right-click casts the selected spell, sneak + right-click picks the next one. */
public class ErCatalystItem extends Item {
	public ErCatalystItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer server) {
			if (player.isShiftKeyDown()) {
				ErSpells.selectNext(server);
				return InteractionResult.SUCCESS;
			}
			return ErSpells.cast(server, player.getItemInHand(hand)) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
		}
		return InteractionResult.SUCCESS;
	}
}
