package dev.eldencraft.combat;

import dev.eldencraft.EldenCraft;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.AABB;

/** Applies {@link ArmourSet} bonuses to players wearing boss armour, once a second, and on kills. */
public final class SetPassives {
	private static final EquipmentSlot[] ARMOUR = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
	/** Enemies we made glow, so the glow is taken off when the set is not worn any more. */
	private static final Set<SkyrimActorEntity> GLOWING = new HashSet<>();
	private static final Map<UUID, String> ANNOUNCED = new HashMap<>();
	private static int ticks;

	private SetPassives() {
	}

	/** How many pieces of each set the entity wears. */
	public static Map<ArmourSet, Integer> worn(LivingEntity entity) {
		Map<ArmourSet, Integer> counts = new EnumMap<>(ArmourSet.class);
		for (EquipmentSlot slot : ARMOUR) {
			ArmourSet set = setOf(entity.getItemBySlot(slot));
			if (set != null) {
				counts.merge(set, 1, Integer::sum);
			}
		}
		return counts;
	}

	/** The set a stack belongs to: a reward piece (it carries the reward tag) trimmed with a set's pattern. */
	public static ArmourSet setOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
		var trim = stack.get(DataComponents.TRIM);
		if (trim == null || tag.getStringOr("eldencraft_reward", "").isEmpty()) {
			return null;
		}
		return trim.pattern().unwrapKey().map(key -> ArmourSet.byPattern(key.identifier().getPath())).orElse(null);
	}

	/** Sets worn in full by this entity: their wards go up a level. */
	public static boolean fullSet(LivingEntity entity) {
		return worn(entity).values().stream().anyMatch(n -> n >= ArmourSet.WARD_PIECES);
	}

	public static void tick(MinecraftServer server) {
		ticks++;
		if (ticks % 20 != 0) {
			return;
		}
		boolean anyGlow = false;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Map<ArmourSet, Integer> counts = worn(player);
			List<String> active = new ArrayList<>();
			for (ArmourSet set : ArmourSet.values()) {
				boolean on = counts.getOrDefault(set, 0) >= ArmourSet.PASSIVE_PIECES;
				apply(player, set, on);
				if (on) {
					active.add(set.title);
				}
			}
			announce(player, active, fullSet(player));
			anyGlow |= glow(player, counts.getOrDefault(ArmourSet.ETERNAL_PATHFINDER, 0) >= ArmourSet.PASSIVE_PIECES);
		}
		if (!anyGlow && !GLOWING.isEmpty()) {
			GLOWING.forEach(e -> e.setGlowingTag(false));
			GLOWING.clear();
		}
	}

	private static void apply(ServerPlayer player, ArmourSet set, boolean on) {
		for (ArmourSet.Attr attr : set.attributes) {
			modifier(player, set, attr.attribute(), on ? attr.amount() : 0.0, attr.op(), on);
		}
		if (set.special == ArmourSet.Special.LOW_HEALTH_DAMAGE) {
			double missing = 1.0 - player.getHealth() / Math.max(1.0F, player.getMaxHealth());
			modifier(player, set, "attack_damage", 4.0 * missing, ArmourSet.Op.VALUE, on);
		}
		if (!on) {
			return;
		}
		for (ArmourSet.Fx fx : set.effects) {
			if (fx.inWater() && !player.isInWater()) {
				continue;
			}
			if (fx.standingStill() && (player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4 || player.getHealth() >= player.getMaxHealth())) {
				continue;
			}
			BuiltInRegistries.MOB_EFFECT.get(Identifier.withDefaultNamespace(fx.effect()))
				.ifPresent(effect -> player.addEffect(new MobEffectInstance(effect, 60, fx.amplifier(), true, false, true)));
		}
		if (set.special == ArmourSet.Special.NO_FREEZE) {
			player.setTicksFrozen(0);
		}
	}

	private static void modifier(ServerPlayer player, ArmourSet set, String attribute, double amount, ArmourSet.Op op, boolean on) {
		BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(attribute)).ifPresent(holder -> {
			var instance = player.getAttribute(holder);
			if (instance == null) {
				return;
			}
			Identifier id = Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "set_" + set.name().toLowerCase() + "_" + attribute);
			if (!on || amount == 0.0) {
				instance.removeModifier(id);
				return;
			}
			var operation = op == ArmourSet.Op.BASE ? AttributeModifier.Operation.ADD_MULTIPLIED_BASE : AttributeModifier.Operation.ADD_VALUE;
			instance.addOrReplacePermanentModifier(new AttributeModifier(id, amount, operation));
		});
	}

	/** Eternal Pathfinder: enemies within 24 blocks glow. Returns whether any wearer is doing it. */
	private static boolean glow(ServerPlayer player, boolean on) {
		if (!on) {
			return false;
		}
		var box = new AABB(player.blockPosition()).inflate(24.0);
		for (SkyrimActorEntity enemy : player.level().getEntitiesOfClass(SkyrimActorEntity.class, box)) {
			enemy.setGlowingTag(true);
			GLOWING.add(enemy);
		}
		GLOWING.removeIf(e -> {
			boolean far = e.isRemoved() || e.distanceToSqr(player) > 26.0 * 26.0;
			if (far) {
				e.setGlowingTag(false);
			}
			return far;
		});
		return true;
	}

	private static void announce(ServerPlayer player, List<String> active, boolean full) {
		String now = String.join(", ", active) + (full ? " (full)" : "");
		String before = ANNOUNCED.put(player.getUUID(), now);
		if (before != null && !before.equals(now)) {
			player.sendSystemMessage(Component.literal(active.isEmpty() ? "Set bonus lost." : "Set bonus: " + now + "."));
		}
	}

	/** A kill by the player: kill-triggered passives. {@code position} is where the enemy fell. */
	public static void onKill(ServerPlayer player, net.minecraft.world.phys.Vec3 position, int tier) {
		Map<ArmourSet, Integer> counts = worn(player);
		for (ArmourSet set : ArmourSet.values()) {
			if (counts.getOrDefault(set, 0) < ArmourSet.PASSIVE_PIECES) {
				continue;
			}
			switch (set.special) {
				case KILL_ABSORPTION -> BuiltInRegistries.MOB_EFFECT.get(Identifier.withDefaultNamespace("absorption"))
					.ifPresent(effect -> player.addEffect(new MobEffectInstance(effect, 200, 1)));
				case KILL_XP -> player.giveExperiencePoints(2 + tier * 2);
				case KILL_HEAL -> player.heal(2.0F);
				case KILL_STRENGTH -> BuiltInRegistries.MOB_EFFECT.get(Identifier.withDefaultNamespace("strength"))
					.ifPresent(effect -> player.addEffect(new MobEffectInstance(effect, 120, 0)));
				case KILL_BURN -> {
					var near = new AABB(position, position).inflate(6.0);
					for (SkyrimActorEntity enemy : player.level().getEntitiesOfClass(SkyrimActorEntity.class, near)) {
						enemy.igniteForSeconds(4.0F);
					}
				}
				default -> {
				}
			}
		}
	}
}
