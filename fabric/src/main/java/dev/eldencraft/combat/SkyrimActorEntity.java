package dev.eldencraft.combat;

import dev.eldencraft.link.Proto;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * An invisible stand-in for one Skyrim actor, so Minecraft's own combat (swords, crits, sweeps,
 * enchantments, attack cooldown, bows, tridents) can target and hit Skyrim NPCs. What it receives is
 * collected into one hit per tick and forwarded to the real actor; its own health never drops.
 */
public class SkyrimActorEntity extends LivingEntity {
	private static final EntityDataAccessor<Integer> FORM_ID = SynchedEntityData.defineId(SkyrimActorEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(SkyrimActorEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(SkyrimActorEntity.class, EntityDataSerializers.FLOAT);

	// This tick's hit, flushed to Skyrim by SkyCombat after all attacks for the tick have landed
	// (Player.attack adds its sprint/enchantment knockback after hurtServer returns).
	private float pendingDamage;
	// Damage from Elden Ring weapons and spells: already Elden Ring's own numbers, so the tier factor is not applied.
	private float pendingExact;
	private int pendingFlags;
	private int pendingWeapon;
	private double pushX, pushZ;
	private float pushStrength;
	private boolean hitThisTick;
	// Status buildup on this enemy, and what it is (health in damage points, boss, kind of enemy).
	private final StatusMeters meters = new StatusMeters();
	private float maxHp = 20.0F;
	private boolean boss;
	private String family = "unknown";
	private float pendingStatus;

	public SkyrimActorEntity(EntityType<? extends SkyrimActorEntity> type, Level level) {
		super(type, level);
		this.setNoGravity(true);
		this.noPhysics = true;
		this.setInvisible(true);
		this.setSilent(true);
	}

	public int formId() {
		return this.entityData.get(FORM_ID);
	}

	public void setFormId(int formId) {
		this.entityData.set(FORM_ID, formId);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(FORM_ID, 0);
		builder.define(WIDTH, 0.6F);
		builder.define(HEIGHT, 1.8F);
	}

	public void setSize(float width, float height) {
		if (Math.abs(this.entityData.get(WIDTH) - width) > 0.01F || Math.abs(this.entityData.get(HEIGHT) - height) > 0.01F) {
			this.entityData.set(WIDTH, width);
			this.entityData.set(HEIGHT, height);
			this.refreshDimensions();
		}
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (WIDTH.equals(accessor) || HEIGHT.equals(accessor)) {
			this.refreshDimensions();
		}
	}

	@Override
	protected EntityDimensions getDefaultDimensions(Pose pose) {
		return EntityDimensions.scalable(this.entityData.get(WIDTH), this.entityData.get(HEIGHT));
	}

	@Override
	protected void actuallyHurt(ServerLevel level, DamageSource source, float dmg) {
		// Minecraft has applied everything (crit, sharpness, strength, cooldown, invulnerability
		// frames). Hand the result to Skyrim instead of lowering our own health.
		if (this.isInvulnerableTo(level, source) || dmg <= 0.0F) {
			return;
		}
		if (ErHits.onHurt(level, this, source, dmg * meters.amplify(level.getGameTime()))) {
			this.pendingWeapon = ErHits.weaponClass(source);
			if (source.getDirectEntity() instanceof Projectile) {
				this.pendingFlags |= Proto.HIT_PROJECTILE;
			}
			this.hitThisTick = true;
			this.getCombatTracker().recordDamage(source, dmg);
			return;
		}
		// Frostbite leaves an enemy open; the weapon's elements add to the hit, its statuses fill meters.
		float damage = dmg * meters.amplify(level.getGameTime());
		damage += ElementalHit.apply(level, this, source, ElementalHit.weapon(source), damage);
		this.pendingDamage += damage;
		if (source.getDirectEntity() instanceof Projectile) {
			this.pendingFlags |= Proto.HIT_PROJECTILE;
		}
		this.pendingWeapon = weaponClass(source);
		if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) {
			this.pendingFlags |= Proto.HIT_FIRE;
		}
		this.hitThisTick = true;
		this.getCombatTracker().recordDamage(source, dmg);
	}

	@Override
	public void knockback(double power, double xd, double zd, DamageSource source, float damage, boolean comesFromEffect) {
		// Skyrim owns this actor's position. Remember the strongest push for Skyrim's stagger:
		// Minecraft pushes towards -(xd, zd).
		double len = Math.sqrt(xd * xd + zd * zd);
		if (len > 1e-6 && power > this.pushStrength) {
			this.pushStrength = (float) power;
			this.pushX = -xd / len;
			this.pushZ = -zd / len;
		}
		this.hitThisTick = true;
	}

	public StatusMeters meters() {
		return this.meters;
	}

	public float maxHp() {
		return this.maxHp;
	}

	public boolean isBoss() {
		return this.boss;
	}

	public String family() {
		return this.family;
	}

	/** What kind of enemy this is, from Elden Ring: its health in damage points, boss or not, family. */
	public void setProfile(float maxHp, boolean boss, String family) {
		this.maxHp = maxHp > 0.0F ? maxHp : 20.0F;
		this.boss = boss;
		this.family = family;
	}

	/** Damage from an Elden Ring weapon or spell, sent as it is (no tier factor). */
	public void addExactDamage(float damage) {
		if (damage > 0.0F && Float.isFinite(damage)) this.pendingExact += damage;
	}

	/** Status damage (a burst, or poison and rot ticking) waiting to go to Elden Ring. */
	public void addStatusDamage(float damage) {
		if (damage > 0.0F && Float.isFinite(damage)) this.pendingStatus += damage;
	}

	/** Returns the status damage queued since last time and clears it. */
	public float takeStatus() {
		float damage = this.pendingStatus;
		this.pendingStatus = 0.0F;
		return damage;
	}

	/** Player.crit() was called on us this tick. */
	public void markCritical() {
		this.pendingFlags |= Proto.HIT_CRITICAL;
	}

	/** Which kind of Skyrim weapon impact this hit should look and sound like. */
	private static int weaponClass(DamageSource source) {
		if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident) {
			return Proto.WEAPON_PIERCE;
		}
		if (source.getDirectEntity() instanceof Projectile) {
			return Proto.WEAPON_ARROW;
		}
		ItemStack weapon = source.getWeaponItem();
		if (weapon == null && source.getEntity() instanceof LivingEntity attacker) {
			weapon = attacker.getMainHandItem();
		}
		if (weapon == null || weapon.isEmpty()) {
			return Proto.WEAPON_UNARMED;
		}
		if (weapon.is(ItemTags.SWORDS)) {
			return Proto.WEAPON_BLADE;
		}
		if (weapon.is(ItemTags.AXES)) {
			return Proto.WEAPON_AXE;
		}
		if (weapon.is(Items.TRIDENT)) {
			return Proto.WEAPON_PIERCE;
		}
		if (weapon.is(ItemTags.SPEARS)) {
			return Proto.WEAPON_SPEAR;
		}
		if (weapon.is(Items.MACE)) {
			// Called from actuallyHurt, before the mace resets the attacker's fall distance.
			return source.getEntity() instanceof LivingEntity attacker && net.minecraft.world.item.MaceItem.canSmashAttack(attacker)
				? Proto.WEAPON_MACE_SMASH : Proto.WEAPON_MACE;
		}
		return Proto.WEAPON_BLUNT;
	}

	/** Returns this tick's hit (damage, flags, push, weapon, exact damage) and clears it; null if nothing hit us. */
	public float[] takeHit() {
		if (!this.hitThisTick) {
			return null;
		}
		float[] hit = { this.pendingDamage, (float) this.pushX, (float) this.pushZ, this.pushStrength, Float.intBitsToFloat(this.pendingFlags),
			Float.intBitsToFloat(this.pendingWeapon), this.pendingExact };
		this.pendingDamage = 0.0F;
		this.pendingExact = 0.0F;
		this.pendingFlags = 0;
		this.pushX = this.pushZ = 0.0;
		this.pushStrength = 0.0F;
		this.hitThisTick = false;
		return hit;
	}

	@Override
	public void tick() {
		// Position and rotation come from Skyrim (SkyCombat); keep hurt timers and fire ticking.
		this.baseTick();
		this.setHealth(this.getMaxHealth());
		if (this.level() instanceof net.minecraft.server.level.ServerLevel level) {
			float dot = this.meters.tick(level.getGameTime());
			if (dot > 0.0F) {
				this.addStatusDamage(dot);
				ElementalHit.burst(level, this, net.minecraft.core.particles.ParticleTypes.CRIMSON_SPORE, 5);
			}
		}
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(Entity entity) {
	}

	@Override
	public boolean canBeCollidedWith(@Nullable Entity other) {
		return false;
	}

	@Override
	public boolean shouldShowName() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected @Nullable SoundEvent getHurtSound(DamageSource source) {
		return null; // Skyrim plays the NPC's own pain sounds
	}

	@Override
	protected @Nullable SoundEvent getDeathSound() {
		return null;
	}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}
}
