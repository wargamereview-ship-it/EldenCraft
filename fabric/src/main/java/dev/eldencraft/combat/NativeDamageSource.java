package dev.eldencraft.combat;

import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Preserve attack attribution and the captured position together. Server thread only. */
public final class NativeDamageSource extends DamageSource {
	private final @Nullable Vec3 origin;
	private float blocked;

	public NativeDamageSource(Holder<DamageType> type, @Nullable Entity direct,
		@Nullable Entity attacker, @Nullable Vec3 origin) {
		super(type, direct, attacker);
		this.origin = origin;
	}

	@Override public @Nullable Vec3 getSourcePosition() {
		return origin != null ? origin : super.getSourcePosition();
	}

	/** Recorded after vanilla has applied its shield delay, direction and durability rules. */
	public void recordBlocked(float amount) {
		if (Float.isFinite(amount) && amount > 0) blocked += amount;
	}
	public float blocked() { return blocked; }
}
