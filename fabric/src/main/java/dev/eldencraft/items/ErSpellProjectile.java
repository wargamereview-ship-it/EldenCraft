package dev.eldencraft.items;

import dev.eldencraft.EldenCraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** A cast spell in flight: the spell's own icon, a trail of its element, and Elden Ring's damage on whatever it hits. */
public class ErSpellProjectile extends ThrowableItemProjectile {
	public static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE,
		Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "er_spell"));
	public static final EntityType<ErSpellProjectile> TYPE = net.minecraft.core.Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY,
		EntityType.Builder.<ErSpellProjectile>of(ErSpellProjectile::new, MobCategory.MISC).sized(0.4F, 0.4F)
			.clientTrackingRange(8).updateInterval(1).noSave().build(KEY));

	private float damage;
	private int element;
	private int life = 60;

	public ErSpellProjectile(EntityType<? extends ErSpellProjectile> type, Level level) {
		super(type, level);
	}

	public ErSpellProjectile(Level level, LivingEntity owner, ItemStack icon, float damage, int element, int life) {
		super(TYPE, owner, level, icon);
		this.damage = damage;
		this.element = element;
		this.life = life;
		this.setNoGravity(true);
	}

	@Override
	protected Item getDefaultItem() {
		return ErItems.GOODS;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level() instanceof ServerLevel level) {
			level.sendParticles(particle(), this.getX(), this.getY(), this.getZ(), 2, 0.05, 0.05, 0.05, 0.0);
			if (--this.life <= 0) {
				this.discard();
			}
		}
	}

	private ParticleOptions particle() {
		return switch (this.element) {
			case 2 -> ParticleTypes.FLAME;
			case 3 -> ParticleTypes.ELECTRIC_SPARK;
			case 4 -> ParticleTypes.END_ROD;
			case 0 -> ParticleTypes.CRIT;
			default -> ParticleTypes.ENCHANT;
		};
	}

	@Override
	protected void onHitEntity(EntityHitResult hit) {
		super.onHitEntity(hit);
		if (this.level() instanceof ServerLevel level && hit.getEntity() != this.getOwner()) {
			var owner = this.getOwner();
			hit.getEntity().hurtServer(level, this.damageSources().indirectMagic(this, owner instanceof LivingEntity l ? l : null), this.damage);
		}
	}

	@Override
	protected void onHit(HitResult hit) {
		super.onHit(hit);
		if (!this.level().isClientSide()) {
			this.discard();
		}
	}
}
