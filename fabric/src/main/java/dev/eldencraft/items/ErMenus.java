package dev.eldencraft.items;

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

/** The talisman pouch and the level-up screen, as chest screens (they need nothing on the client). */
public final class ErMenus {
	private ErMenus() {
	}

	public static void openTalismans(ServerPlayer player) {
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new TalismanMenu(id, inventory), Component.literal("Talismans")));
	}

	public static void openLevelUp(ServerPlayer player) {
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new LevelMenu(id, inventory), Component.literal("Level Up")));
	}

	private static net.minecraft.world.item.Item vanilla(String id) {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(id));
	}

	static ItemStack label(net.minecraft.world.item.Item item, int count, Component name, Component... lore) {
		ItemStack stack = new ItemStack(item, Math.clamp(count, 1, 64));
		stack.set(DataComponents.CUSTOM_NAME, name);
		stack.set(DataComponents.LORE, new ItemLore(List.of(lore)));
		return stack;
	}

	/**
	 * One row: the talisman slots (one, plus one per Talisman Pouch), then locked panes. Only talismans go in; closing
	 * the screen saves them to the character.
	 */
	private static final class TalismanMenu extends ChestMenu {
		private final SimpleContainer pouch;
		private final Player owner;
		private final int open;

		TalismanMenu(int id, Inventory inventory) {
			this(id, inventory, new SimpleContainer(9));
		}

		private TalismanMenu(int id, Inventory inventory, SimpleContainer pouch) {
			super(MenuType.GENERIC_9x1, id, inventory, pouch, 1);
			this.pouch = pouch;
			this.owner = inventory.player;
			ErPlayer data = ErPlayer.of(owner);
			this.open = data.talismanSlots();
			for (int k = 0; k < 9; k++) {
				pouch.setItem(k, k < open ? data.talismans.get(k).copy() : k < ErPlayer.TALISMAN_SLOTS
					? label(vanilla("gray_stained_glass_pane"), 1, Component.literal("Locked").withStyle(ChatFormatting.GRAY),
						Component.literal("A Talisman Pouch opens this slot"))
					: label(vanilla("black_stained_glass_pane"), 1, Component.literal(" ")));
			}
		}

		private static boolean isTalisman(ItemStack stack) {
			ErRef ref = ErItems.ref(stack);
			return stack.isEmpty() || ref != null && ref.kind() == ErRef.TALISMAN;
		}

		@Override
		public void clicked(int slot, int button, ContainerInput type, Player player) {
			if (slot >= 0 && slot < 9) {
				if (slot >= open || !isTalisman(getCarried()) || type == ContainerInput.QUICK_MOVE) {
					return;
				}
			}
			super.clicked(slot, button, type, player);
		}

		@Override
		public ItemStack quickMoveStack(Player player, int index) {
			if (index < 9) {
				return index < open ? super.quickMoveStack(player, index) : ItemStack.EMPTY;
			}
			Slot from = this.slots.get(index);
			ItemStack stack = from.getItem();
			if (!isTalisman(stack) || stack.isEmpty()) {
				return ItemStack.EMPTY;
			}
			for (int k = 0; k < open; k++) {
				if (pouch.getItem(k).isEmpty()) {
					pouch.setItem(k, stack.split(1));
					from.setChanged();
					return ItemStack.EMPTY;
				}
			}
			return ItemStack.EMPTY;
		}

		@Override
		public void removed(Player player) {
			super.removed(player);
			ErPlayer.edit(player, p -> {
				for (int k = 0; k < ErPlayer.TALISMAN_SLOTS; k++) p.talismans.set(k, k < open ? pouch.getItem(k).copy() : ItemStack.EMPTY);
			});
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	/**
	 * Elden Ring's level up: one column per attribute. Click an attribute to raise it by one, paying the next level's
	 * cost in experience; the bottom row shows the cost and what you hold.
	 */
	private static final class LevelMenu extends ChestMenu {
		private static final net.minecraft.world.item.Item[] ICONS = { vanilla("red_dye"), vanilla("light_blue_dye"), vanilla("lime_dye"), Items.IRON_INGOT,
			Items.FEATHER, Items.AMETHYST_SHARD, Items.GOLD_INGOT, Items.ENDER_EYE };
		private static final String[] WHAT = { "Max HP", "Max FP", "Equip load and stamina", "Strength weapons", "Dexterity weapons",
			"Sorceries and Int weapons", "Incantations and Faith weapons", "Status buildup and Arcane weapons" };
		private final SimpleContainer grid;
		private final ServerPlayer buyer;

		LevelMenu(int id, Inventory inventory) {
			this(id, inventory, new SimpleContainer(27));
		}

		private LevelMenu(int id, Inventory inventory, SimpleContainer grid) {
			super(MenuType.GENERIC_9x3, id, inventory, grid, 3);
			this.grid = grid;
			this.buyer = (ServerPlayer) inventory.player;
			refresh();
		}

		private void refresh() {
			ErPlayer data = ErPlayer.of(buyer);
			int level = data.level();
			int cost = ErStats.levelCost(level);
			int have = XpMath.points(buyer.experienceLevel, buyer.experienceProgress);
			boolean afford = have >= cost || buyer.hasInfiniteMaterials();
			for (int k = 0; k < 27; k++) grid.setItem(k, label(vanilla("gray_stained_glass_pane"), 1, Component.literal(" ")));
			for (int k = 0; k < 8; k++) {
				int value = data.attrs[k];
				grid.setItem(9 + k, label(ICONS[k], value, Component.literal(ErPlayer.NAMES[k] + ": " + value).withStyle(ChatFormatting.GOLD),
					Component.literal(WHAT[k]).withStyle(ChatFormatting.GRAY),
					Component.literal(value >= 99 ? "At its maximum" : "Click to raise to " + (value + 1)).withStyle(afford ? ChatFormatting.GREEN : ChatFormatting.RED)));
			}
			List<Component> lore = new ArrayList<>();
			lore.add(Component.literal("Next level costs " + cost + " experience").withStyle(afford ? ChatFormatting.GREEN : ChatFormatting.RED));
			lore.add(Component.literal("You hold " + have + " (" + buyer.experienceLevel + " levels)").withStyle(ChatFormatting.AQUA));
			lore.add(Component.literal(String.format("HP %.0f  FP %.0f  Equip load %.1f", ErStats.hp(data.attrs[0]), ErStats.fp(data.attrs[1]),
				ErStats.equipLoad(data.attrs[2]))).withStyle(ChatFormatting.GRAY));
			grid.setItem(22, label(Items.EXPERIENCE_BOTTLE, Math.max(1, level), Component.literal("Rune Level " + level).withStyle(ChatFormatting.YELLOW),
				lore.toArray(Component[]::new)));
		}

		@Override
		public void clicked(int slot, int button, ContainerInput type, Player player) {
			if (slot < 0 || slot >= 27) {
				if (slot >= 27 && type != ContainerInput.QUICK_MOVE) super.clicked(slot, button, type, player);
				return;
			}
			int attr = slot - 9;
			if (type != ContainerInput.PICKUP || attr < 0 || attr >= 8) {
				return;
			}
			ErPlayer data = ErPlayer.of(buyer);
			if (data.attrs[attr] >= 99) {
				return;
			}
			int cost = ErStats.levelCost(data.level());
			if (!ErStats.spendXp(buyer, cost)) {
				buyer.sendSystemMessage(Component.literal("You need " + cost + " experience to level up."));
				return;
			}
			ErPlayer.edit(buyer, p -> p.attrs[attr]++);
			ErStats.apply(buyer);
			buyer.level().playSound(null, buyer.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7F, 1.4F);
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

	/** For tooltips: a row's name. */
	static String name(JsonObject row) {
		return ErData.s(row, "n");
	}
}
