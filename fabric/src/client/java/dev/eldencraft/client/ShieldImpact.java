package dev.eldencraft.client;

import net.minecraft.world.InteractionHand;

/** Actual server-confirmed shield impacts, consumed by the client renderer. */
public final class ShieldImpact {
	private static long at;
	private static float strength;
	private static InteractionHand hand = InteractionHand.OFF_HAND;
	private ShieldImpact() {}

	public static void blocked(InteractionHand shieldHand, float amount) {
		hand = shieldHand;
		strength = Math.clamp(amount / 10.0F, 0.35F, 1.0F);
		at = System.nanoTime();
	}

	public static float recoil(InteractionHand shieldHand) {
		if (shieldHand != hand || !SkyClient.linked() || !SkyClient.sky().minecraftHands()) return 0;
		double t = (System.nanoTime() - at) / 260_000_000.0;
		// Sharp impact, then a smooth return; never accumulates movement or camera offsets.
		return at == 0 || t < 0 || t >= 1 ? 0 : (float) (Math.sin(Math.PI * Math.sqrt(t)) * Math.exp(-3 * t)) * strength;
	}

	public static float flash() {
		double t = (System.nanoTime() - at) / 500_000_000.0;
		return at == 0 || t < 0 || t >= 1 ? 0 : (float) (1 - t);
	}
}
