package dev.eldencraft.items;

import com.google.gson.JsonObject;
import dev.eldencraft.EldenCraft;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Elden Ring's stamina. Endurance sets the pool; sprinting, attacks, bow shots, spells and blocked hits spend it, and it
 * comes back after a short pause. With none left you cannot attack or sprint, and a block that empties it breaks the
 * guard. Not saved (a fresh load starts full); sent to the player's own client for the HUD and the sprint lock.
 */
public final class ErStamina {
	private ErStamina() {
	}

	/** Elden Ring's recovery, per second, and the pause after spending before it starts. */
	public static final double REGEN_PER_SECOND = 45.0, SPRINT_PER_SECOND = 12.0;
	private static final int DELAY_TICKS = 18;
	/** After running dry, sprinting waits until this much is back. */
	public static final double SPRINT_AGAIN = 12.0;

	public static final AttachmentType<Float> TYPE = AttachmentRegistry.<Float>builder()
		.initializer(() -> -1.0F)
		.syncWith(ByteBufCodecs.FLOAT, AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "er_stamina"));

	private static final Map<UUID, Integer> DELAY = new HashMap<>();
	private static final Map<UUID, Boolean> DRY = new HashMap<>();

	public static void init() {
		// Attacks cost stamina; with none left, the swing does not happen (checked on the client too, which has the value).
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (player.isSpectator() || player.hasInfiniteMaterials()) {
				return InteractionResult.PASS;
			}
			if (current(player) <= 0.0F && current(player) != -1.0F) {
				return InteractionResult.FAIL;
			}
			if (player instanceof ServerPlayer server) {
				spend(server, attackCost(player.getMainHandItem()));
			}
			return InteractionResult.PASS;
		});
	}

	public static double max(Player player, ErEffects.Totals totals) {
		return ErStats.stamina(ErStats.effective(player, totals)[ErPlayer.ENDURANCE]) * totals.maxStamina;
	}

	/** Stamina left (-1 before the first tick has set it). */
	public static float current(Player player) {
		Float value = player.getAttached(TYPE);
		return value == null ? -1.0F : value;
	}


	/** Stamina for one swing, by Elden Ring weapon class (Minecraft weapons by kind). */
	public static double attackCost(ItemStack weapon) {
		JsonObject w = ErItems.weaponRow(weapon);
		if (w != null) {
			return switch (ErData.i(w, "t")) {
				case 1, 35, 37, 88, 91, 95 -> 9;
				case 9 -> 11;
				case 3, 15, 39, 87, 92 -> 12;
				case 13, 14, 25 -> 13;
				case 17, 21, 24, 93 -> 14;
				case 16, 31 -> 15;
				case 5, 11, 94 -> 16;
				case 29 -> 17;
				case 28 -> 18;
				case 19, 23 -> 19;
				case 7 -> 21;
				case 41 -> 24;
				default -> 12;
			};
		}
		if (weapon.is(ItemTags.SWORDS)) return 12;
		if (weapon.is(ItemTags.AXES)) return 14;
		if (weapon.is(Items.MACE)) return 18;
		if (weapon.is(Items.TRIDENT) || weapon.is(ItemTags.SPEARS)) return 13;
		return 8;
	}

	/** Spends stamina (Elden Ring lets the last action take it to zero) and pauses recovery. */
	public static void spend(ServerPlayer player, double amount) {
		if (amount <= 0 || player.hasInfiniteMaterials()) {
			return;
		}
		float now = Math.max(0.0F, current(player));
		float left = (float) Math.max(0.0, now - amount);
		player.setAttached(TYPE, left);
		DELAY.put(player.getUUID(), DELAY_TICKS);
		if (left <= 0.0F) {
			DRY.put(player.getUUID(), true);
		}
	}

	/** A shield took a hit worth {@code blockedHp} Elden Ring HP: guard costs stamina, and running dry breaks the guard. */
	public static void blocked(ServerPlayer player, ItemStack shield, double blockedHp) {
		JsonObject w = ErItems.weaponRow(shield);
		double stability = w == null ? 35 : ErData.i(w, "stab");
		double cost = blockedHp * 0.4 * Math.clamp(1.0 - stability / 100.0, 0.05, 1.0) + 4;
		spend(player, cost);
		if (current(player) <= 0.0F && !player.hasInfiniteMaterials()) {
			// Guard broken: the shield drops and cannot be raised for a moment.
			player.stopUsingItem();
			player.getCooldowns().addCooldown(shield, 40);
			player.level().playSound(null, player.blockPosition(), SoundEvents.SHIELD_BREAK.value(), SoundSource.PLAYERS, 0.9F, 0.9F);
		}
	}

	/** Every tick: sprinting drains, then recovery after the pause (slower behind a raised shield). */
	public static void tick(ServerPlayer player, ErEffects.Totals totals) {
		UUID id = player.getUUID();
		double max = max(player, totals);
		float before = current(player);
		double s = before < 0 ? max : before;
		int delay = DELAY.getOrDefault(id, 0);
		boolean moving = player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4;
		if (player.isSprinting() && moving && !player.hasInfiniteMaterials() && !player.isPassenger()) {
			s -= SPRINT_PER_SECOND / 20.0;
			delay = Math.max(delay, 6);
			if (s <= 0) {
				s = 0;
				DRY.put(id, true);
			}
		} else if (delay > 0) {
			delay--;
		} else {
			double regen = (REGEN_PER_SECOND + totals.staminaRegen) / 20.0;
			s += player.isBlocking() ? regen * 0.3 : regen;
		}
		s = Math.clamp(s, 0, max);
		if (s >= SPRINT_AGAIN) {
			DRY.remove(id);
		}
		if (DRY.containsKey(id)) {
			player.setSprinting(false); // the client holds the same lock (ErHud) so it does not start again
		}
		DELAY.put(id, delay);
		if (Math.abs(s - before) >= 0.25 || s == max && before != max || s == 0 && before != 0) {
			player.setAttached(TYPE, (float) s);
		}
	}

	/** Grace rests and respawns refill it. */
	public static void refill(ServerPlayer player) {
		player.setAttached(TYPE, (float) max(player, ErEffects.of(player)));
		DRY.remove(player.getUUID());
		DELAY.remove(player.getUUID());
	}
}
