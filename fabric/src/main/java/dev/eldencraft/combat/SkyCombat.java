package dev.eldencraft.combat;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.link.Proto;
import dev.eldencraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft combat against invisible proxies for nearby Elden Ring enemies, server side.
 *
 * <p>Weapons hit these like any living entity. Resulting Minecraft damage is queued for the native
 * ER bridge, which validates the target, changes native HP, and acknowledges accepted hits. The
 * native player hit sensor now routes incoming hits through Minecraft armour and shields.
 * Minecraft's server publishes authoritative hearts back to ER for death and grace respawns.
 */
public final class SkyCombat {
	public static final ResourceKey<EntityType<?>> SKYRIM_ACTOR_KEY =
		ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "skyrim_actor"));
	public static final EntityType<SkyrimActorEntity> SKYRIM_ACTOR = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		SKYRIM_ACTOR_KEY,
		EntityType.Builder.<SkyrimActorEntity>of(SkyrimActorEntity::new, MobCategory.MISC)
			.sized(0.6F, 1.8F)
			.noSave()
			.noSummon()
			.noLootTable()
			.clientTrackingRange(10)
			.updateInterval(1)
			.build(SKYRIM_ACTOR_KEY)
	);

	/** Skyrim damage is divided by this for Minecraft (a 15-damage bandit swing = 3 = 1.5 hearts). */
	public static final float SKYRIM_TO_MC_DAMAGE = 5.0F;

	private static final Map<Integer, SkyrimActorEntity> PROXIES = new HashMap<>();
	private static final List<SkyLink.Actor> ACTORS = new ArrayList<>();
	private static final List<net.minecraft.world.entity.projectile.arrow.AbstractArrow> EMBEDDED = new ArrayList<>();

	/** Bounded visuals: at most 32 per enemy and 128 overall, oldest removed first. Server only. */
	public static void rememberEmbedded(net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) {
		EMBEDDED.removeIf(Entity::isRemoved);
		int actor = ((AttachedArrow) arrow).eldencraft$attachedActor();
		var same = EMBEDDED.stream().filter(a -> ((AttachedArrow) a).eldencraft$attachedActor() == actor).toList();
		if (same.size() >= 32) { same.getFirst().discard(); EMBEDDED.remove(same.getFirst()); }
		if (EMBEDDED.size() >= 128) EMBEDDED.removeFirst().discard();
		EMBEDDED.add(arrow);
	}

	private SkyCombat() {
	}

	public static void init() {
		FabricDefaultAttributeRegistry.register(SKYRIM_ACTOR, LivingEntity.createLivingAttributes());
		ServerTickEvents.END_SERVER_TICK.register(SkyCombat::serverTick);
	}

	public static @Nullable SkyrimActorEntity proxy(int formId) {
		return PROXIES.get(formId);
	}

	/** Wall-clock time of the last completed integrated-server tick, for the client's stall watchdog. */
	public static volatile long lastServerTickMs;

	private static void serverTick(MinecraftServer server) {
		lastServerTickMs = System.currentTimeMillis();
		SkyLoot.tick();
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		SkyLink.NativeLife life = SkyLink.readNativeLife();
		PlayerStatusBridge.tick(server, life);
		XpPile.tick(server, life);
		SetPassives.tick(server);
		if (!SkyLink.active() || players.isEmpty()) {
			removeAll();
			Merchants.clear();
			return;
		}
		if (life != null) {
			for (ServerPlayer player : players) {
				if (dev.eldencraft.net.SkyNet.isHost(player)) {
					SkyLink.writePlayerVitals(life, player);
					break;
				}
			}
		}
		ServerLevel level = players.getFirst().level();
		for (ServerPlayer player : players) {
			pickUpNearby(player);
		}
		if (SkyLink.readActors(ACTORS)) {
			sync(level);
			Merchants.sync(level, ACTORS, life);
		}
		// Hits land during the tick (melee, sweeps, arrows, fire); send one combined hit per actor.
		for (SkyrimActorEntity proxy : PROXIES.values()) {
			float[] hit = proxy.takeHit();
			if (hit != null && (hit[0] > 0.0F || hit[3] > 0.0F)) {
				// Damage lands as a share set by the region's tier; the bridge converts what is left to Elden Ring HP.
				if (life != null) {
					hit[0] *= CombatBalance.damageFactor(LootRules.tierAt(life.worldId(), proxy.position()));
				}
				SkyLink.pushEvent(
					Proto.EV_HIT_ACTOR, proxy.formId(), hit[0], hit[1], hit[2], hit[3], Float.floatToRawIntBits(hit[4]), Float.floatToRawIntBits(hit[5])
				);
				EldenCraft.LOG.info("EldenCraft: hit {} for {} (knockback {})", proxy.getName().getString(), hit[0], hit[3]);
			}
			// Poison, rot and the burst of a hemorrhage or frostbite: straight to health, no stagger.
			float status = proxy.takeStatus();
			if (status > 0.0F) {
				SkyLink.pushEvent(Proto.EV_HIT_ACTOR, proxy.formId(), status, 0.0F, 0.0F, 0.0F, Proto.HIT_STATUS, 0);
				EldenCraft.LOG.info("EldenCraft: status damage {} on {}", status, proxy.getName().getString());
			}
		}
	}

	/** The Elden Ring model number in an enemy's name ("Enemy c4070"), or 0. */
	static int modelOf(String name) {
		int c = name.lastIndexOf('c');
		if (c < 0) return 0;
		int end = c + 1;
		while (end < name.length() && Character.isDigit(name.charAt(end))) end++;
		try {
			return end > c + 1 ? Integer.parseInt(name.substring(c + 1, end)) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static void sync(ServerLevel level) {
		Map<Integer, SkyLink.Actor> live = new HashMap<>();
		for (SkyLink.Actor a : ACTORS) {
			if (a.hostile() && !a.dead()) {
				live.put(a.formId(), a);
			}
		}
		for (Iterator<Map.Entry<Integer, SkyrimActorEntity>> it = PROXIES.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, SkyrimActorEntity> e = it.next();
			SkyrimActorEntity proxy = e.getValue();
			if (!live.containsKey(e.getKey()) || proxy.isRemoved() || proxy.level() != level) {
				proxy.discard();
				it.remove();
			}
		}
		int before = PROXIES.size();
		for (SkyLink.Actor a : live.values()) {
			SkyrimActorEntity proxy = PROXIES.get(a.formId());
			if (proxy == null) {
				proxy = new SkyrimActorEntity(SKYRIM_ACTOR, level);
				proxy.setFormId(a.formId());
				proxy.setProfile(a.level(), a.name().startsWith("Boss"), LootRules.familyOfModel(modelOf(a.name())));
				proxy.setSize(a.width(), a.height());
				proxy.snapTo(a.x(), a.y(), a.z(), a.yaw(), 0.0F);
				if (!a.name().isEmpty()) {
					proxy.setCustomName(Component.literal(NpcNames.display(a.name())));
				}
				if (!level.addFreshEntity(proxy)) {
					continue;
				}
				PROXIES.put(a.formId(), proxy);
				continue;
			}
			proxy.setProfile(a.level(), a.name().startsWith("Boss"), proxy.family());
			proxy.setSize(a.width(), a.height());
			proxy.setPos(a.x(), a.y(), a.z());
			proxy.setYRot(a.yaw());
			proxy.setYHeadRot(a.yaw());
			stepOnTriggers(level, proxy);
		}
		if (PROXIES.size() != before && (PROXIES.size() % 5 == 0 || PROXIES.size() < 5)) {
			EldenCraft.LOG.info("EldenCraft: {} Elden Ring enemies mirrored as hittable stand-ins", PROXIES.size());
		}
	}

	/**
	 * Skyrim's NPCs press pressure plates and trip tripwires. Their stand-ins are placed, not moved
	 * (no physics), so Minecraft never checks what they step into; do it for those blocks here.
	 */
	private static void stepOnTriggers(ServerLevel level, SkyrimActorEntity proxy) {
		var box = proxy.getBoundingBox().deflate(1.0E-5);
		var from = net.minecraft.core.BlockPos.containing(box.minX, box.minY, box.minZ);
		var to = net.minecraft.core.BlockPos.containing(box.maxX, box.maxY, box.maxZ);
		for (var pos : net.minecraft.core.BlockPos.betweenClosed(from, to)) {
			var state = level.getBlockState(pos);
			if (state.getBlock() instanceof net.minecraft.world.level.block.BasePressurePlateBlock
				|| state.getBlock() instanceof net.minecraft.world.level.block.TripWireBlock) {
				state.entityInside(level, pos, proxy, net.minecraft.world.entity.InsideBlockEffectApplier.NOOP, true);
			}
		}
	}

	/**
	 * Items and stuck arrows on Skyrim ground rest on its collision voxels, which on steep or rough
	 * terrain can sit a little off from where the player (on Skyrim's exact triangles) stands.
	 * Touch them over a slightly bigger area than vanilla's so walking over them picks them up.
	 * playerTouch applies all of Minecraft's own rules (pickup delay, owner, inventory space).
	 */
	private static void pickUpNearby(ServerPlayer player) {
		if (!player.isAlive() || player.isSpectator()) {
			return;
		}
		for (Entity entity : player.level().getEntities(player, player.getBoundingBox().inflate(1.25, 1.0, 1.25))) {
			if (!entity.isRemoved() && (entity instanceof net.minecraft.world.entity.item.ItemEntity
				|| entity instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow)) {
				entity.playerTouch(player);
			}
		}
	}

	private static void removeAll() {
		EMBEDDED.forEach(Entity::discard); EMBEDDED.clear();
		if (PROXIES.isEmpty()) {
			return;
		}
		PROXIES.values().forEach(Entity::discard);
		PROXIES.clear();
	}

	/** Local native hit, already scaled to MC points. Vanilla applies armour, absorption,
  * shield raising delay, direction, item durability, cooldowns, sounds and death/totems. */
	public record NativeHurt(float blocked, net.minecraft.world.InteractionHand hand) {}

	public static NativeHurt hurtNativePlayer(ServerPlayer player, int kind, float damage, int attackerId,
		int epoch, net.minecraft.world.phys.Vec3 origin, float[] shares) {
		SkyLink.NativeLife life = SkyLink.readNativeLife();
		if (!dev.eldencraft.net.SkyNet.isHost(player) || life == null || !life.active() || life.epoch() != epoch || !player.isAlive()
			|| !Float.isFinite(damage) || damage <= 0.0F || damage > 1000.0F) return new NativeHurt(0, net.minecraft.world.InteractionHand.OFF_HAND);
		ServerLevel level = player.level();
		SkyrimActorEntity attacker = PROXIES.get(attackerId);
		DamageSources sources = level.damageSources();
		DamageSource source = switch (kind) {
			case Proto.HURT_MELEE -> attacker != null ? sources.mobAttack(attacker) : sources.generic();
			case Proto.HURT_PROJECTILE -> attacker != null ? sources.mobProjectile(attacker, attacker) : sources.generic();
			case Proto.HURT_MAGIC -> attacker != null ? sources.indirectMagic(attacker, attacker) : sources.magic();
			default -> sources.generic();
		};
		// Keep the captured source position AND its proxy attribution. Vanilla uses both for
		// directional shield checks, guard reactions, sounds and shield durability.
		if (origin != null && (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z))) origin = null;
		// A projectile (arrow, spell, thrown object; known to the DLL from its attack) is Minecraft projectile
		// damage even when the shooter is not published, so Projectile Protection meets it.
		var type = kind == Proto.HURT_MELEE && origin != null ? sources.mobAttack(player).typeHolder()
			: kind == Proto.HURT_PROJECTILE ? level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
				.getOrThrow(net.minecraft.world.damagesource.DamageTypes.MOB_PROJECTILE)
			: source.typeHolder();
		NativeDamageSource nativeSource = new NativeDamageSource(type, source.getDirectEntity(), source.getEntity(), origin);
		source = nativeSource;
		// Element wards cut their element's share of the hit, before Minecraft's own armour.
		float unwarded = damage;
		damage = Wards.reduce(player, damage, shares);
		player.setYHeadRot(player.getYRot());
		float before = player.getHealth();
		boolean blocking = player.isBlocking();
		var shieldHand = player.getUsedItemHand();
		boolean hurt = player.hurtServer(level, source, damage);
		SkyLink.writePlayerVitals(life, player);
		double angle = direction(player, source.getSourcePosition());
		EldenCraft.LOG.info("EldenCraft: ER hit {} MC damage from actor {}: hearts HP {} -> {} (shield raised {}, blocked {}, angle {}, source {}, origin {}, accepted {}){}",
			damage, attackerId, before, player.getHealth(), blocking, nativeSource.blocked(), angle,
			source.getMsgId(), source.getSourcePosition(), hurt, shares == null ? "" : String.format(
				" [physical %.0f%% magic %.0f%% fire %.0f%% lightning %.0f%% holy %.0f%%, wards cut %.2f of %.2f]",
				shares[0] * 100, shares[1] * 100, shares[2] * 100, shares[3] * 100, shares[4] * 100, unwarded - damage, unwarded));
		return new NativeHurt(nativeSource.blocked(), shieldHand);
	}

	private static double direction(ServerPlayer player, net.minecraft.world.phys.Vec3 origin) {
		if (origin == null) return Double.NaN;
		var offset = origin.subtract(player.position());
		var toward = new net.minecraft.world.phys.Vec3(offset.x, 0, offset.z).normalize();
		double yaw = Math.toRadians(player.getYRot());
		double cosine = toward.x * -Math.sin(yaw) + toward.z * Math.cos(yaw);
		return Math.toDegrees(Math.acos(Math.clamp(cosine, -1.0, 1.0)));
	}

	/** Existing guest transport; the local bridge supplies damage already scaled to MC points. */
	public static void hurtPlayer(ServerPlayer player, int kind, float damage, int attackerId, int epoch) {
		SkyrimActorEntity actor = PROXIES.get(attackerId);
		hurtNativePlayer(player, kind, damage, attackerId, epoch, actor == null ? null : actor.position(), null);
	}

	/**
	 * Skyrim skills for taking a hit: Block when the shield caught it, otherwise Light or Heavy
	 * Armor by what the player mostly wears (leather, chainmail, gold, copper and turtle count as
	 * light; iron, diamond and netherite as heavy). Only the host's own Skyrim is told.
	 */
	private static void trainDefence(ServerPlayer player, float damage, boolean blocked) {
		if (!dev.eldencraft.net.SkyNet.isHost(player) || damage <= 0.0F) {
			return;
		}
		if (blocked) {
			SkyLink.pushEvent(Proto.EV_SKILL_USE, Proto.SKILL_BLOCK, damage, 0.0F, 0.0F, 0.0F, 0);
			return;
		}
		int light = 0, heavy = 0;
		for (var slot : new net.minecraft.world.entity.EquipmentSlot[] { net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
			net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET }) {
			var stack = player.getItemBySlot(slot);
			if (stack.isEmpty()) {
				continue;
			}
			String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			if (path.startsWith("iron_") || path.startsWith("diamond_") || path.startsWith("netherite_")) {
				heavy++;
			} else {
				light++;
			}
		}
		if (light + heavy > 0) {
			SkyLink.pushEvent(Proto.EV_SKILL_USE, heavy > light ? Proto.SKILL_HEAVY_ARMOR : Proto.SKILL_LIGHT_ARMOR, damage * (light + heavy) / 4.0F, 0.0F, 0.0F,
				0.0F, 0);
		}
	}

	/** Form id of the Skyrim actor behind a damage source, or 0. */
	public static int attackerFormId(DamageSource source) {
		return source.getEntity() instanceof SkyrimActorEntity proxy ? proxy.formId() : 0;
	}
}
