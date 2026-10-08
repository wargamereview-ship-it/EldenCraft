package dev.eldencraft.items;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/**
 * The Flasks of Crimson and Cerulean Tears. Charges and strength live with the character ({@link ErPlayer}); the flask
 * items in the inventory show them (the stack count is the charges left) and refill when resting at a grace.
 * Elden Ring's heal amounts come from each flask level's own SpEffect.
 */
public final class ErFlasks {
	private ErFlasks() {
	}

	public static final int MAX_CHARGES = 14, MAX_LEVEL = 12;

	public static boolean drink(ServerPlayer player, boolean cerulean) {
		ErPlayer data = ErPlayer.of(player);
		int left = cerulean ? data.ceruleanLeft : data.crimsonLeft;
		if (left <= 0) {
			player.sendOverlayMessage(Component.literal("The flask is empty. Rest at a grace to refill it."));
			return false;
		}
		JsonObject effect = ErData.effect(flaskEffect(cerulean, data.flaskLevel));
		if (cerulean) {
			addFp(player, effect == null ? 80 : -ErData.d(effect, "changeMpEstusFlaskPoint"));
		} else {
			player.heal((float) ((effect == null ? 250 : -ErData.d(effect, "changeHpEstusFlaskPoint")) / ErStats.HP_PER_HEALTH));
		}
		if (!player.hasInfiniteMaterials()) {
			ErPlayer.edit(player, p -> {
				if (cerulean) p.ceruleanLeft--; else p.crimsonLeft--;
			});
		}
		player.level().playSound(null, player.blockPosition(), SoundEvents.GENERIC_DRINK.value(), SoundSource.PLAYERS, 0.8F, 1.0F);
		refreshStacks(player);
		return true;
	}

	private static int flaskEffect(boolean cerulean, int level) {
		JsonObject g = ErData.goods((cerulean ? 1051 : 1001) + 2 * Math.clamp(level, 0, MAX_LEVEL));
		return g == null ? 0 : ErData.i(g, "ref");
	}

	public static void addFp(ServerPlayer player, double amount) {
		double max = ErStats.maxFp(player, ErEffects.of(player));
		ErPlayer.edit(player, p -> p.fp = (float) Math.min(max, p.fp + amount));
	}

	public static boolean addCharge(ServerPlayer player) {
		if (ErPlayer.of(player).flasks >= MAX_CHARGES) {
			player.sendOverlayMessage(Component.literal("Your flasks already hold " + MAX_CHARGES + " charges."));
			return false;
		}
		ErPlayer.edit(player, p -> { p.flasks++; p.crimsonLeft++; });
		player.sendOverlayMessage(Component.literal("Flask charges: " + ErPlayer.of(player).flasks));
		refreshStacks(player);
		return true;
	}

	public static boolean addLevel(ServerPlayer player) {
		if (ErPlayer.of(player).flaskLevel >= MAX_LEVEL) {
			player.sendOverlayMessage(Component.literal("Your flasks are already +" + MAX_LEVEL + "."));
			return false;
		}
		ErPlayer.edit(player, p -> p.flaskLevel++);
		player.sendOverlayMessage(Component.literal("Flasks strengthened to +" + ErPlayer.of(player).flaskLevel));
		refreshStacks(player);
		return true;
	}

	/** Moves one charge between the flasks (Elden Ring's "allocate flasks" at a grace). */
	public static void allocate(ServerPlayer player, int cerulean) {
		ErPlayer.edit(player, p -> {
			p.cerulean = Math.clamp(cerulean, 0, p.flasks);
			p.crimsonLeft = p.flasks - p.cerulean;
			p.ceruleanLeft = p.cerulean;
		});
		refreshStacks(player);
	}

	/** Grace rest: both flasks refill, and HP and FP are restored as Elden Ring does. */
	public static void rest(ServerPlayer player) {
		ErPlayer.edit(player, p -> {
			p.crimsonLeft = p.flasks - p.cerulean;
			p.ceruleanLeft = p.cerulean;
			p.fp = (float) ErStats.maxFp(player, ErEffects.of(player));
		});
		ErBuffs.clear(player);
		refreshStacks(player);
	}

	/** Makes the inventory's flask items match the charges (and adds them when missing). */
	public static void refreshStacks(ServerPlayer player) {
		ErPlayer data = ErPlayer.of(player);
		var inventory = player.getInventory();
		boolean crimson = false, cerulean = false;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			ErRef ref = ErItems.ref(stack);
			if (ref == null || ref.kind() != ErRef.GOODS) continue;
			if (ErGoods.isCrimson(ref.id())) {
				inventory.setItem(slot, crimson ? ItemStack.EMPTY : flask(false, data));
				crimson = true;
			} else if (ErGoods.isCerulean(ref.id())) {
				inventory.setItem(slot, cerulean ? ItemStack.EMPTY : flask(true, data));
				cerulean = true;
			}
		}
		if (!crimson && data.flasks - data.cerulean > 0) player.getInventory().add(flask(false, data));
		if (!cerulean && data.cerulean > 0) player.getInventory().add(flask(true, data));
	}

	private static ItemStack flask(boolean cerulean, ErPlayer data) {
		int left = cerulean ? data.ceruleanLeft : data.crimsonLeft;
		int id = (cerulean ? ErGoods.CERULEAN_EMPTY : ErGoods.CRIMSON_EMPTY) + 2 * Math.clamp(data.flaskLevel, 0, MAX_LEVEL) + (left > 0 ? 1 : 0);
		return ErItems.goods(id, Math.max(1, left));
	}
}
