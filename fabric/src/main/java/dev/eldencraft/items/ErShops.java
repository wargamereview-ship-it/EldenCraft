package dev.eldencraft.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.eldencraft.combat.XpMath;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.jspecify.annotations.Nullable;

/**
 * Elden Ring's merchants, as their villagers sell: each merchant's own lineup (ShopLineupParam) at Elden Ring's prices,
 * paid in experience (runes count as experience the way kills pay it, √runes × 0.8). Limited stock stays sold for the
 * character, as in Elden Ring; unlimited items can be bought again and again.
 */
public final class ErShops {
	private ErShops() {
	}

	/**
	 * The lineup a merchant sells, by its NpcParam row: each trading merchant's Bell Bearing names its stock (Kalé's
	 * 100500, the others 100525 onward). 0 for anything else.
	 */
	public static int lineupFor(int npcRow) {
		int base = ErData.merchantLineup(npcRow);
		return base != 0 && ErData.shop(base) != null ? base : 0;
	}

	public static int price(JsonObject entry) {
		int runes = ErData.i(entry, "price");
		return runes <= 0 ? 0 : Math.max(1, (int) Math.round(0.8 * Math.sqrt(runes)));
	}

	public static boolean open(ServerPlayer player, int base, String title) {
		JsonArray lineup = ErData.shop(base);
		if (lineup == null) {
			return false;
		}
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ShopMenu(id, inventory, lineup), Component.literal(title)));
		return true;
	}

	private static @Nullable ItemStack goods(JsonObject entry) {
		ItemStack stack = ErItems.stack(ErData.i(entry, "k"), ErData.i(entry, "id"), 0, ErData.i(entry, "n"));
		return stack.isEmpty() ? null : stack;
	}

	/** Four rows of offers, then a row with what you hold. Click an offer to buy it. */
	private static final class ShopMenu extends ChestMenu {
		private final SimpleContainer shelf;
		private final ServerPlayer buyer;
		private final List<JsonObject> offers = new ArrayList<>();

		ShopMenu(int id, Inventory inventory, JsonArray lineup) {
			this(id, inventory, lineup, new SimpleContainer(45));
		}

		private ShopMenu(int id, Inventory inventory, JsonArray lineup, SimpleContainer shelf) {
			super(MenuType.GENERIC_9x5, id, inventory, shelf, 5);
			this.shelf = shelf;
			this.buyer = (ServerPlayer) inventory.player;
			for (var e : lineup) {
				if (offers.size() < 36 && goods(e.getAsJsonObject()) != null) offers.add(e.getAsJsonObject());
			}
			refresh();
		}

		private int left(JsonObject offer) {
			int stock = ErData.i(offer, "stock");
			return stock < 0 ? -1 : Math.max(0, stock - ErPlayer.of(buyer).bought.getOrDefault(ErData.i(offer, "row"), 0));
		}

		private void refresh() {
			int have = XpMath.points(buyer.experienceLevel, buyer.experienceProgress);
			for (int k = 0; k < 45; k++) shelf.setItem(k, ItemStack.EMPTY);
			for (int k = 0; k < offers.size(); k++) {
				JsonObject offer = offers.get(k);
				ItemStack stack = goods(offer);
				int price = price(offer), left = left(offer);
				List<Component> lore = new ArrayList<>();
				lore.add(Component.literal(price == 0 ? "Free" : "Price: " + price + " experience (" + ErData.i(offer, "price") + " runes)")
					.withStyle(have >= price ? ChatFormatting.GREEN : ChatFormatting.RED));
				lore.add(Component.literal(left < 0 ? "In stock" : left == 0 ? "Sold out" : left + " left")
					.withStyle(left == 0 ? ChatFormatting.RED : ChatFormatting.GRAY));
				lore.add(Component.literal("Click to buy").withStyle(ChatFormatting.DARK_GRAY));
				stack.set(DataComponents.LORE, new ItemLore(lore));
				shelf.setItem(k, stack);
			}
			ItemStack purse = new ItemStack(Items.EXPERIENCE_BOTTLE, Math.clamp(buyer.experienceLevel, 1, 64));
			purse.set(DataComponents.CUSTOM_NAME, Component.literal("You hold " + have + " experience").withStyle(ChatFormatting.AQUA));
			purse.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(buyer.experienceLevel + " levels"))));
			shelf.setItem(44, purse);
		}

		@Override
		public void clicked(int slot, int button, ContainerInput type, Player player) {
			if (slot < 0 || slot >= 45) {
				if (slot >= 45 && type != ContainerInput.QUICK_MOVE) super.clicked(slot, button, type, player);
				return;
			}
			if (type != ContainerInput.PICKUP || slot >= offers.size()) {
				return;
			}
			JsonObject offer = offers.get(slot);
			if (left(offer) == 0) {
				buyer.sendSystemMessage(Component.literal("Sold out."));
				return;
			}
			int price = price(offer);
			if (!ErStats.spendXp(buyer, price)) {
				buyer.sendSystemMessage(Component.literal("You need " + price + " experience."));
				return;
			}
			ItemStack stack = goods(offer);
			if (ErData.i(offer, "stock") >= 0) {
				int row = ErData.i(offer, "row");
				ErPlayer.edit(buyer, p -> p.bought.merge(row, 1, Integer::sum));
			}
			if (ErData.i(offer, "k") == ErRef.GOODS) {
				ErGoods.onReceived(buyer, ErData.i(offer, "id"));
			}
			if (!buyer.getInventory().add(stack)) {
				var drop = new net.minecraft.world.entity.item.ItemEntity(buyer.level(), buyer.getX(), buyer.getY() + 0.5, buyer.getZ(), stack);
				drop.setNoPickUpDelay();
				buyer.level().addFreshEntity(drop);
			}
			buyer.level().playSound(null, buyer.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0F, 1.0F);
			refresh();
			broadcastChanges();
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
			return false;
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}
}
