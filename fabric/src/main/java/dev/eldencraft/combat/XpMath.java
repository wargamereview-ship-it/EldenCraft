package dev.eldencraft.combat;

/** Minecraft's experience curve, so a death can turn "level + bar" into points and back. */
public final class XpMath {
	private XpMath() {
	}

	/**
	 * The experience for a kill, from the runes Elden Ring pays for it (zero if unknown, then {@code fallback}). Runes
	 * climb steeply with the enemy, so the square root keeps early kills worth having and late ones from flooding.
	 */
	public static int fromRunes(int runes, int fallback) {
		if (runes <= 0) {
			return fallback;
		}
		return Math.clamp(Math.round(0.8 * Math.sqrt(runes)), 1, 150);
	}

	/** Points needed to go from {@code level} to the next one. */
	public static int needed(int level) {
		return level >= 30 ? 112 + (level - 30) * 9 : level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2;
	}

	/** Points needed to reach {@code level} from zero. */
	public static long toReach(int level) {
		long l = Math.max(0, level);
		if (l <= 16) return l * l + 6 * l;
		if (l <= 31) return Math.round(2.5 * l * l - 40.5 * l + 360);
		return Math.round(4.5 * l * l - 162.5 * l + 2220);
	}

	/** The points a player holds: whole levels plus how far through the next one the bar is (0 to 1). */
	public static int points(int level, float progress) {
		long total = toReach(level) + Math.round(Math.clamp(progress, 0.0F, 1.0F) * needed(level));
		return (int) Math.min(total, Integer.MAX_VALUE);
	}
}
