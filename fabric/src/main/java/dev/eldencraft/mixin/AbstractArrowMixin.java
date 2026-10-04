package dev.eldencraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.combat.SkyrimActorEntity;
import dev.eldencraft.link.SkyLink;
import dev.eldencraft.world.SkyClip;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Arrows and tridents hit Skyrim's exact surfaces. They then stick where they hit: the block state
 * there is air, the same as what they recorded on impact, so vanilla never makes them fall out.
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin implements dev.eldencraft.combat.AttachedArrow {
	@Unique private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> ELDENCRAFT_ACTOR =
		net.minecraft.network.syncher.SynchedEntityData.defineId(AbstractArrow.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
	@Unique private static final net.minecraft.network.syncher.EntityDataAccessor<org.joml.Vector3fc> ELDENCRAFT_OFFSET =
		net.minecraft.network.syncher.SynchedEntityData.defineId(AbstractArrow.class, net.minecraft.network.syncher.EntityDataSerializers.VECTOR3);
	@Unique private static final net.minecraft.network.syncher.EntityDataAccessor<Float> ELDENCRAFT_YAW =
		net.minecraft.network.syncher.SynchedEntityData.defineId(AbstractArrow.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
	@Unique private static final net.minecraft.network.syncher.EntityDataAccessor<Float> ELDENCRAFT_PITCH =
		net.minecraft.network.syncher.SynchedEntityData.defineId(AbstractArrow.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
	@Unique private int eldencraft$embeddedTicks;
	@org.spongepowered.asm.mixin.Shadow protected abstract void setInGround(boolean value);

	@Inject(method = "defineSynchedData", at = @At("TAIL"))
	private void eldencraft$attachmentData(net.minecraft.network.syncher.SynchedEntityData.Builder builder, CallbackInfo ci) {
		builder.define(ELDENCRAFT_ACTOR, 0); builder.define(ELDENCRAFT_OFFSET, new org.joml.Vector3f());
		builder.define(ELDENCRAFT_YAW, 0.0F); builder.define(ELDENCRAFT_PITCH, 0.0F);
	}

	@Override public int eldencraft$attachedActor() {
		return ((AbstractArrow) (Object) this).getEntityData().get(ELDENCRAFT_ACTOR);
	}

	@Override public void eldencraft$follow(double x, double y, double z, float actorYaw) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		var data = self.getEntityData();
		org.joml.Vector3fc offset = data.get(ELDENCRAFT_OFFSET);
		double angle = Math.toRadians(actorYaw), c = Math.cos(angle), s = Math.sin(angle);
		self.setPos(x + offset.x() * c - offset.z() * s, y + offset.y(), z + offset.x() * s + offset.z() * c);
		self.setYRot(data.get(ELDENCRAFT_YAW) - actorYaw); self.setXRot(data.get(ELDENCRAFT_PITCH));
		self.setDeltaMovement(Vec3.ZERO); self.setNoGravity(true); self.noPhysics = true;
		this.setInGround(true);
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void eldencraft$embeddedTick(CallbackInfo ci) {
		int id = this.eldencraft$attachedActor();
		if (id == 0) return;
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (!self.level().isClientSide()) {
			SkyrimActorEntity actor = dev.eldencraft.combat.SkyCombat.proxy(id);
			if (actor == null || actor.isRemoved() || ++this.eldencraft$embeddedTicks > 1200 || !SkyLink.active()) {
				self.discard();
			} else {
				this.eldencraft$follow(actor.getX(), actor.getY(), actor.getZ(), actor.getYRot());
			}
		}
		ci.cancel(); // No gravity, pickup, second impact or collision detachment on a moving enemy.
	}

	@WrapOperation(method = "onHitEntity", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;discard()V"))
	private void eldencraft$retainEmbedded(AbstractArrow self, Operation<Void> original) {
		if (this.eldencraft$attachedActor() == 0) { original.call(self); return; }
		SkyrimActorEntity actor = dev.eldencraft.combat.SkyCombat.proxy(this.eldencraft$attachedActor());
		if (actor != null) this.eldencraft$follow(actor.getX(), actor.getY(), actor.getZ(), actor.getYRot());
		else original.call(self);
	}

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void eldencraft$noEmbeddedPickup(net.minecraft.world.entity.player.Player player, CallbackInfo ci) {
		if (this.eldencraft$attachedActor() != 0) ci.cancel();
	}

	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void eldencraft$saveTransientAttachment(net.minecraft.world.level.storage.ValueOutput output, CallbackInfo ci) {
		if (this.eldencraft$attachedActor() != 0) output.putBoolean("EldenCraftEmbedded", true);
	}

	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	private void eldencraft$discardOldAttachment(net.minecraft.world.level.storage.ValueInput input, CallbackInfo ci) {
  // Native actor IDs are session-local. A saved arrow must not attach to an unrelated next run.
		if (input.getBooleanOr("EldenCraftEmbedded", false)) ((AbstractArrow) (Object) this).discard();
	}
	@WrapOperation(
		method = "tick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;")
	)
	private BlockHitResult eldencraft$hitSkyrim(Level level, ClipContext context, Operation<BlockHitResult> original) {
		return SkyClip.refine(context.getFrom(), context.getTo(), original.call(level, context), SkyClip.Use.PROJECTILE);
	}

	@Unique
	private Vec3 eldencraft$hitAt;

	@Inject(method = "onHitEntity", at = @At("HEAD"))
	private void eldencraft$rememberHit(EntityHitResult hitResult, CallbackInfo ci) {
		this.eldencraft$hitAt = hitResult.getLocation();
	}

	/**
	 * Where Minecraft counts an arrow as stuck in a creature (it hurt it and didn't pierce): if that
	 * creature is an ER stand-in, retain the arrow and follow the actor's body transform.
	 */
	@WrapOperation(method = "onHitEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setArrowCount(I)V"))
	private void eldencraft$stickInSkyrimActor(LivingEntity mob, int count, Operation<Void> original) {
		original.call(mob, count);
		if (!(mob instanceof SkyrimActorEntity actor) || this.eldencraft$hitAt == null || !SkyLink.active()) {
			return;
		}
		AbstractArrow self = (AbstractArrow) (Object) this;
		Vec3 v = self.getDeltaMovement();
		float yaw = (float) (Mth.atan2(v.x, v.z) * Mth.RAD_TO_DEG);
		float pitch = (float) (Mth.atan2(v.y, v.horizontalDistance()) * Mth.RAD_TO_DEG);
		Vec3 at = this.eldencraft$hitAt;
		if (!(self instanceof Arrow || self instanceof SpectralArrow) || self.level().isClientSide()) return;
		double angle = Math.toRadians(actor.getYRot()), c = Math.cos(angle), s = Math.sin(angle);
		Vec3 delta = at.subtract(actor.position());
		var data = self.getEntityData();
		data.set(ELDENCRAFT_OFFSET, new org.joml.Vector3f((float) (delta.x * c + delta.z * s), (float) delta.y, (float) (-delta.x * s + delta.z * c)));
		data.set(ELDENCRAFT_YAW, yaw + actor.getYRot()); data.set(ELDENCRAFT_PITCH, pitch);
		data.set(ELDENCRAFT_ACTOR, actor.formId());
		dev.eldencraft.combat.SkyCombat.rememberEmbedded(self);
		dev.eldencraft.EldenCraft.LOG.info("EldenCraft: arrow {} embedded in actor {}", self.getId(), actor.formId());
	}
}
