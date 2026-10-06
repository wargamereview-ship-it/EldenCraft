package dev.eldencraft.combat;

/**
 * The rules of the ward enchantments (armour), apart from Minecraft. An element ward cuts its
 * element's share of an Elden Ring hit; a status ward cuts the buildup of its status that Elden Ring
 * applies to the wearer (the DLL scales it in the hit). Each level is 10%, up to 70% at VII. Only the
 * strongest piece of a ward counts: wards do not add up across armour.
 */
public final class WardRules {
	private WardRules() {}

	public static final int MAX_LEVEL = 7;
	/** Boss armour and ordinary ward books stop here; VII is a book from one DLC boss per ward. */
	public static final int GEAR_MAX_LEVEL = 6;
	public static final float PER_LEVEL = 0.10F;

	/** A hit's damage types, in the order the DLL sends their shares. */
	public static final int PHYSICAL = 0, MAGIC = 1, FIRE = 2, LIGHTNING = 3, HOLY = 4, TYPES = 5;

	public enum Ward {
		GLINTSTONE("glintstone_ward", MAGIC, "magic"), FLAME("flame_ward", FIRE, "fire"),
		STORM("storm_ward", LIGHTNING, "lightning"), SACRED("sacred_ward", HOLY, "holy"),
		ROT("rot_ward", -1, "scarlet_rot"), BLEED("bleed_ward", -1, "hemorrhage"),
		FROST("frost_ward", -1, "frostbite"), VENOM("venom_ward", -1, "poison");

		public final String id;
		/** The damage type it cuts, or -1 for a status ward. */
		public final int element;
		/** The weapon enchantment of the same theme (a boss's weapon carries it). */
		public final String weaponEnchantment;

		Ward(String id, int element, String weaponEnchantment) {
			this.id = id;
			this.element = element;
			this.weaponEnchantment = weaponEnchantment;
		}
	}

	public static Ward byId(String id) {
		for (Ward w : Ward.values()) if (w.id.equals(id)) return w;
		return null;
	}

	public static String[] allIds() {
		var ids = new String[Ward.values().length];
		for (Ward w : Ward.values()) ids[w.ordinal()] = w.id;
		return ids;
	}

	/** The fraction a ward of this level cuts (0.1 per level, 0.7 at VII). */
	public static float cut(int level) {
		return Math.max(0, Math.min(level, MAX_LEVEL)) * PER_LEVEL;
	}

	/**
	 * Damage left after the wearer's element wards: each damage type's share is cut by its ward.
	 * {@code shares} are fractions by damage type (PHYSICAL..HOLY, summing to 1), or null when the
	 * hit's make-up is unknown (nothing is cut). {@code levels} are the ward levels by ward ordinal.
	 */
	public static float reduce(float damage, float[] shares, int[] levels) {
		if (shares == null || !(damage > 0.0F)) return damage;
		float kept = 1.0F;
		for (Ward w : Ward.values()) {
			if (w.element >= 0) kept -= shares[w.element] * cut(levels[w.ordinal()]);
		}
		return damage * Math.max(0.0F, kept);
	}

	/**
	 * A hit's shares from the DLL's percentages: {@code packed} holds magic, fire, lightning and holy
	 * (one byte each, low first), {@code physical} the rest. Null if they add up to nothing.
	 */
	public static float[] shares(int packed, int physical) {
		float[] s = new float[TYPES];
		s[PHYSICAL] = Math.max(0, physical);
		for (int i = 0; i < 4; i++) s[MAGIC + i] = (packed >>> (8 * i)) & 0xFF;
		float total = 0.0F;
		for (float v : s) total += v;
		if (!(total > 0.0F)) return null;
		for (int i = 0; i < TYPES; i++) s[i] /= total;
		return s;
	}

	/**
	 * The status ward levels the DLL needs, one byte each, low first: poison, scarlet rot, blood loss,
	 * frostbite ({@code levels} by ward ordinal).
	 */
	public static int packStatus(int[] levels) {
		int venom = clamp(levels[Ward.VENOM.ordinal()]), rot = clamp(levels[Ward.ROT.ordinal()]);
		int bleed = clamp(levels[Ward.BLEED.ordinal()]), frost = clamp(levels[Ward.FROST.ordinal()]);
		return venom | rot << 8 | bleed << 16 | frost << 24;
	}

	/** The ward level on a boss's armour piece of this tier. */
	public static int gearLevel(int tier) {
		return Math.max(1, Math.min(GEAR_MAX_LEVEL, tier));
	}

	/** The ward level of a boss's book: its tier, at most VI, or VII from the one boss that gives it. */
	public static int bookLevel(int tier, boolean seven) {
		return seven ? MAX_LEVEL : gearLevel(tier);
	}

	/**
	 * The ward level an anvil may put on its result: two VI wards do not make a VII; VII only
	 * carries over from an input that already had it.
	 */
	public static int anvilLevel(int result, int left, int right) {
		return result >= MAX_LEVEL && Math.max(left, right) < MAX_LEVEL ? GEAR_MAX_LEVEL : result;
	}

	private static int clamp(int level) {
		return Math.max(0, Math.min(level, MAX_LEVEL));
	}
}
