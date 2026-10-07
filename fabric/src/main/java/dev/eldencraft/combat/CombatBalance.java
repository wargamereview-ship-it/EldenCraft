package dev.eldencraft.combat;

/**
 * How hard the player hits Elden Ring enemies. Every Minecraft damage point becomes a fixed number of
 * Elden Ring HP in the native bridge; this scales the points first, by the region's tier, so the same
 * weapon is strong early and has to be upgraded to keep up. Tune the table, not the bridge.
 */
public final class CombatBalance {
	/** Share of a hit's damage that lands, for tiers 1 to 7. */
	private static final float[] DAMAGE_BY_TIER = { 0.60F, 0.52F, 0.46F, 0.42F, 0.38F, 0.36F, 0.34F };

	private CombatBalance() {
	}

	public static float damageFactor(int tier) {
		return DAMAGE_BY_TIER[Math.clamp(tier, 1, DAMAGE_BY_TIER.length) - 1];
	}
}
