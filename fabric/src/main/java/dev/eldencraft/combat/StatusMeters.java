package dev.eldencraft.combat;

import dev.eldencraft.combat.ElementalRules.Status;

/**
 * Status buildup on one enemy, after Elden Ring's: each hit fills a meter, the meter drains when
 * hits stop, and when it reaches the enemy's threshold the status takes hold. Every time a status
 * takes hold its threshold rises (a repeat is harder), and drops back after a quiet minute.
 * Times are in server ticks; damage is in Minecraft damage points (25 Elden Ring HP each).
 */
public final class StatusMeters {
	public static final int TPS = 20;
	/** Meter size for an ordinary enemy and for a boss. */
	static final float THRESHOLD = 100.0F, BOSS_THRESHOLD = 250.0F;
	/** A repeat takes this much more, up to MAX_STACK times. */
	static final float REPEAT = 1.6F;
	static final int MAX_STACK = 3;
	/** The meter starts to drain this long after the last hit, then at this rate per second. */
	static final long DRAIN_DELAY = 4L * TPS;
	static final float DRAIN_PER_SECOND = 6.0F;
	static final long CALM = 60L * TPS;
	static final int EFFECT_SECONDS = 18;
	static final float FROST_AMPLIFY = 1.2F;
	static final long FROST_TICKS = 20L * TPS;

	/** What happened when a status took hold: damage now, and what to tell the player. */
	public record Proc(Status status, float burst) {}

	private final float[] meter = new float[Status.values().length];
	private final long[] lastHit = new long[Status.values().length];
	private final long[] lastProc = new long[Status.values().length];
	private final int[] stack = new int[Status.values().length];
	private final float[] dotPerSecond = new float[Status.values().length];
	private final long[] dotUntil = new long[Status.values().length];
	private long frostUntil;

	private static float threshold(boolean boss, int stack) {
		return (boss ? BOSS_THRESHOLD : THRESHOLD) * (float) Math.pow(REPEAT, stack);
	}

	/**
	 * Adds {@code amount} buildup of a status at {@code now}. Returns the proc if this filled the meter.
	 * {@code maxHp} is the enemy's health in damage points.
	 */
	public Proc add(Status status, float amount, long now, float maxHp, boolean boss) {
		int i = status.ordinal();
		if (now - lastProc[i] > CALM) stack[i] = 0;
		drain(i, now);
		meter[i] += amount;
		lastHit[i] = now;
		if (meter[i] < threshold(boss, stack[i])) return null;
		meter[i] = 0.0F;
		stack[i] = Math.min(stack[i] + 1, MAX_STACK);
		lastProc[i] = now;
		float hp = Math.max(maxHp, 1.0F);
		switch (status) {
			case HEMORRHAGE: return new Proc(status, Math.max(1.0F, hp * (boss ? 0.04F : 0.12F)));
			case FROSTBITE:
				frostUntil = now + FROST_TICKS;
				return new Proc(status, Math.max(1.0F, hp * (boss ? 0.02F : 0.06F)));
			case POISON:
				dotPerSecond[i] = Math.max(0.2F, hp * (boss ? 0.0015F : 0.008F));
				dotUntil[i] = now + EFFECT_SECONDS * TPS;
				return new Proc(status, 0.0F);
			default: // scarlet rot
				dotPerSecond[i] = Math.max(0.3F, hp * (boss ? 0.002F : 0.012F));
				dotUntil[i] = now + EFFECT_SECONDS * TPS;
				return new Proc(status, 0.0F);
		}
	}

	private void drain(int i, long now) {
		long idle = now - lastHit[i] - DRAIN_DELAY;
		if (idle > 0 && meter[i] > 0.0F) meter[i] = Math.max(0.0F, meter[i] - idle * DRAIN_PER_SECOND / TPS);
	}

	/** Damage over time due this tick (poison and rot, once a second). */
	public float tick(long now) {
		if (now % TPS != 0) return 0.0F;
		float total = 0.0F;
		for (Status s : new Status[] { Status.POISON, Status.ROT }) {
			if (now < dotUntil[s.ordinal()]) total += dotPerSecond[s.ordinal()];
		}
		return total;
	}

	/** Damage taken is multiplied by this (frostbite leaves an enemy vulnerable). */
	public float amplify(long now) {
		return now < frostUntil ? FROST_AMPLIFY : 1.0F;
	}

	/** Current meter fill 0..1 of a status, for display. */
	public float fill(Status status, long now, boolean boss) {
		int i = status.ordinal();
		drain(i, now);
		return Math.min(1.0F, meter[i] / threshold(boss, stack[i]));
	}
}
