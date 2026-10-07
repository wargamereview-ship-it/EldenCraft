package dev.eldencraft.combat;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.Vec3;

/**
 * Elden Ring's open-world merchants become villagers. The native bridge publishes each merchant; a villager with no
 * brain of its own stands where it stands. Using one opens a trade screen paying in experience levels, with the offers
 * of its region's tier. Uses of each offer come back after a rest at a grace.
 */
public final class Merchants {
	private static final String TAG = "eldencraft_merchant";
	private static final Map<Integer, Villager> STAND_INS = new HashMap<>();
	/** Uses left, by merchant, one entry per offer. Cleared (everything restocked) at a grace rest. */
	private static final Map<Integer, int[]> USES = new HashMap<>();
	private static final Map<Integer, Integer> TIERS = new HashMap<>();
	private static final Map<Integer, String> NAMES = new HashMap<>();

	private Merchants() {
	}

	public static void init() {
		// Stand-ins cannot be hurt; they are only a face for the native merchant.
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !entity.entityTags().contains(TAG));
		UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
			if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || !entity.entityTags().contains(TAG) || !(player instanceof ServerPlayer sp)) {
				return InteractionResult.PASS;
			}
			for (var e : STAND_INS.entrySet()) {
				if (e.getValue() == entity) {
					open(sp, e.getKey());
					return InteractionResult.SUCCESS;
				}
			}
			return InteractionResult.PASS;
		});
	}

	/** A grace rest: every offer of every merchant can be bought again. */
	public static void restock() {
		USES.clear();
	}

	/** Keeps one villager per published merchant, in place. */
	public static void sync(ServerLevel level, List<SkyLink.Actor> actors, SkyLink.NativeLife life) {
		Map<Integer, SkyLink.Actor> live = new HashMap<>();
		for (SkyLink.Actor a : actors) {
			if (a.merchant() && !a.dead()) {
				live.put(a.formId(), a);
			}
		}
		for (Iterator<Map.Entry<Integer, Villager>> it = STAND_INS.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (!live.containsKey(e.getKey()) || e.getValue().isRemoved() || e.getValue().level() != level) {
				e.getValue().discard();
				it.remove();
			}
		}
		for (SkyLink.Actor a : live.values()) {
			Villager villager = STAND_INS.get(a.formId());
			if (villager == null) {
				villager = new Villager(net.minecraft.world.entity.EntityTypes.VILLAGER, level);
				villager.setNoAi(true);
				villager.setPersistenceRequired();
				villager.addTag(TAG);
				String name = NpcNames.display(a.name());
				villager.setCustomName(Component.literal(name));
				villager.setCustomNameVisible(true);
				villager.snapTo(a.x(), a.y(), a.z(), a.yaw(), 0.0F);
				if (!level.addFreshEntity(villager)) {
					continue;
				}
				STAND_INS.put(a.formId(), villager);
				NAMES.put(a.formId(), name);
				TIERS.put(a.formId(), life == null ? 1 : LootRules.tierAt(life.worldId(), new Vec3(a.x(), a.y(), a.z())));
				EldenCraft.LOG.info("EldenCraft: merchant {} stands in as a villager (tier {})", name, TIERS.get(a.formId()));
			} else {
				villager.setPos(a.x(), a.y(), a.z());
				villager.setYRot(a.yaw());
				villager.setYHeadRot(a.yaw());
			}
		}
	}

	/** Removes every stand-in (the link went away). */
	public static void clear() {
		STAND_INS.values().forEach(Villager::discard);
		STAND_INS.clear();
	}

	private static void open(ServerPlayer player, int merchant) {
		int tier = TIERS.getOrDefault(merchant, 1);
		List<MerchantTrades.Trade> trades = MerchantTrades.forTier(tier);
		int[] uses = USES.computeIfAbsent(merchant, k -> trades.stream().mapToInt(MerchantTrades.Trade::uses).toArray());
		String title = NAMES.getOrDefault(merchant, "Merchant") + " - Tier " + tier;
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new TradeMenu(id, inventory, trades, uses), Component.literal(title)));
	}

	/**
	 * A shop laid out as a chest screen, one column per offer: the goods on top, their price under them (green when
	 * you can pay, red when you cannot), then how many are left. Clicking anywhere in a column buys it with experience
	 * levels; nothing can be taken out. The right-hand column shows your levels.
	 */
	private static final class TradeMenu extends ChestMenu {
		private final List<MerchantTrades.Trade> trades;
		private final int[] uses;
		private final SimpleContainer shop;
		private final Inventory buyer;
		private final int offset;

		TradeMenu(int id, Inventory inventory, List<MerchantTrades.Trade> trades, int[] uses) {
			this(id, inventory, trades, uses, new SimpleContainer(27));
		}

		private TradeMenu(int id, Inventory inventory, List<MerchantTrades.Trade> trades, int[] uses, SimpleContainer shop) {
			super(net.minecraft.world.inventory.MenuType.GENERIC_9x3, id, inventory, shop, 3);
			this.trades = trades;
			this.uses = uses;
			this.shop = shop;
			this.buyer = inventory;
			this.offset = (9 - trades.size()) / 2;
			refresh();
		}

		private static ItemStack item(String id, int count) {
			return BuiltInRegistries.ITEM.get(Identifier.withDefaultNamespace(id)).map(holder -> new ItemStack(holder.value(), count)).orElse(ItemStack.EMPTY);
		}

		private static ItemStack labelled(String id, int count, Component name, Component... lore) {
			ItemStack stack = item(id, count);
			if (!stack.isEmpty()) {
				stack.set(DataComponents.CUSTOM_NAME, name);
				stack.set(DataComponents.LORE, new ItemLore(List.of(lore)));
			}
			return stack;
		}

		private ItemStack goods(MerchantTrades.Trade trade) {
			return item(trade.item(), trade.count());
		}

		/** Draws the whole shop again: after opening and after every purchase. */
		private void refresh() {
			int levels = buyer.player.experienceLevel;
			ItemStack filler = labelled("gray_stained_glass_pane", 1, Component.literal(" "));
			for (int i = 0; i < 27; i++) {
				shop.setItem(i, filler.copy());
			}
			for (int i = 0; i < trades.size(); i++) {
				var trade = trades.get(i);
				int column = offset + i;
				ItemStack stack = goods(trade);
				if (!stack.isEmpty()) {
					stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Click to buy"))));
					shop.setItem(column, stack);
				}
				boolean affordable = levels >= trade.levels();
				shop.setItem(9 + column, labelled("gold_nugget", Math.min(64, trade.levels()),
					Component.literal("Cost: " + trade.levels() + " levels").withStyle(affordable ? ChatFormatting.GREEN : ChatFormatting.RED),
					Component.literal(affordable ? "You can pay this" : "You need " + (trade.levels() - levels) + " more levels")));
				shop.setItem(18 + column, uses[i] > 0
					? labelled("lime_stained_glass_pane", Math.min(64, uses[i]), Component.literal("Left: " + uses[i]).withStyle(ChatFormatting.GREEN),
						Component.literal("Restocks when you rest at a grace"))
					: labelled("red_stained_glass_pane", 1, Component.literal("Sold out").withStyle(ChatFormatting.RED),
						Component.literal("Rest at a grace to restock")));
			}
			shop.setItem(17, labelled("experience_bottle", Math.max(1, Math.min(64, levels)),
				Component.literal("Your levels: " + levels).withStyle(ChatFormatting.AQUA),
				Component.literal("Levels are what you pay with"), Component.literal("and what you lose when you die")));
		}

		@Override
		public void clicked(int slot, int button, ContainerInput type, Player player) {
			if (slot < 0 || slot >= 27) {
				if (slot >= 27 && type != ContainerInput.QUICK_MOVE) {
					super.clicked(slot, button, type, player); // the player's own inventory behaves as usual
				}
				return;
			}
			int index = slot % 9 - offset;
			if (type != ContainerInput.PICKUP || index < 0 || index >= trades.size() || !(player instanceof ServerPlayer sp)) {
				return;
			}
			var trade = trades.get(index);
			ItemStack goods = goods(trade);
			if (goods.isEmpty()) {
				return;
			}
			if (uses[index] <= 0) {
				sp.sendSystemMessage(Component.literal("Sold out until you rest at a grace."));
			} else if (sp.experienceLevel < trade.levels()) {
				sp.sendSystemMessage(Component.literal("You need " + trade.levels() + " levels (you have " + sp.experienceLevel + ")."));
			} else {
				sp.giveExperienceLevels(-trade.levels());
				if (!sp.getInventory().add(goods)) {
					sp.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(sp.level(), sp.getX(), sp.getY(), sp.getZ(), goods));
				}
				uses[index]--;
				sp.level().playSound(null, sp.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0F, 1.0F);
			}
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
