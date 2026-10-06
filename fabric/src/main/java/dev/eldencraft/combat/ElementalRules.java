package dev.eldencraft.combat;

import java.util.Locale;

/**
 * The rules of the elemental and status enchantments, apart from Minecraft: which enchantment is
 * which, how much bonus damage an element adds against each kind of enemy, and how much
 * buildup a hit applies. Effects on enemies are in {@link StatusMeters}.
 */
public final class ElementalRules {
	private ElementalRules() {}

	/** Extra damage as a fraction of the hit, per enchantment level (level V is +60%). */
	public static final float ELEMENT_PER_LEVEL = 0.12F;

	public enum Element {
		MAGIC("magic"), FIRE("fire"), LIGHTNING("lightning"), HOLY("holy");
		public final String id;
		Element(String id) { this.id = id; }
	}

	public enum Status {
		HEMORRHAGE("hemorrhage"), FROSTBITE("frostbite"), POISON("poison"), ROT("scarlet_rot");
		public final String id;
		Status(String id) { this.id = id; }
	}

	public static Element element(String id) {
		for (Element e : Element.values()) if (e.id.equals(id)) return e;
		return null;
	}

	public static Status status(String id) {
		for (Status s : Status.values()) if (s.id.equals(id)) return s;
		return null;
	}

	/** Every enchantment this mod adds, for rewards and registration checks. */
	public static String[] allIds() {
		var ids = new String[Element.values().length + Status.values().length];
		int i = 0;
		for (Element e : Element.values()) ids[i++] = e.id;
		for (Status s : Status.values()) ids[i++] = s.id;
		return ids;
	}

	/**
	 * How well an element works on an enemy family (see {@link LootRules#FAMILIES}). Approximate: the
	 * game's own resistances are not read, so these follow the usual weaknesses of each kind of enemy.
	 */
	public static float affinity(String family, Element element) {
		if (family == null) return 1.0F;
		return switch (family.toLowerCase(Locale.ROOT)) {
			case "undead" -> switch (element) { case HOLY -> 1.5F; case FIRE -> 1.2F; case MAGIC -> 1.0F; case LIGHTNING -> 0.9F; };
			case "plant" -> switch (element) { case FIRE -> 1.5F; case LIGHTNING -> 0.9F; case HOLY -> 1.0F; case MAGIC -> 1.0F; };
			case "beast" -> switch (element) { case FIRE -> 1.2F; case LIGHTNING -> 1.1F; case HOLY -> 1.0F; case MAGIC -> 1.0F; };
			case "stone" -> switch (element) { case MAGIC -> 1.3F; case LIGHTNING -> 1.1F; case FIRE -> 0.7F; case HOLY -> 1.0F; };
			case "magic" -> switch (element) { case MAGIC -> 0.7F; case HOLY -> 1.2F; case FIRE -> 1.0F; case LIGHTNING -> 1.1F; };
			case "dragon" -> switch (element) { case LIGHTNING -> 1.2F; case HOLY -> 1.1F; case FIRE -> 0.7F; case MAGIC -> 1.0F; };
			case "soldier", "archer", "miner" -> switch (element) { case LIGHTNING -> 1.2F; case FIRE -> 1.1F; case HOLY -> 1.0F; case MAGIC -> 1.0F; };
			default -> 1.0F;
		};
	}

	/** The extra damage an element of this level adds to a hit that dealt {@code damage}. */
	public static float bonus(float damage, int level, float affinity) {
		if (!(damage > 0.0F) || level <= 0) return 0.0F;
		return damage * ELEMENT_PER_LEVEL * Math.min(level, 10) * affinity;
	}

	/** Buildup one hit applies at an enchantment level (a hit adds to a meter that fills at 100). */
	public static float buildup(int level) {
		return level <= 0 ? 0.0F : 14.0F + 8.0F * Math.min(level, 10);
	}

	/** The enchantment level rewards of a tier carry (tier 1-7 gives levels 1,1,2,3,4,5,5). */
	public static int levelForTier(int tier) {
		return Math.max(1, Math.min(5, tier - 1));
	}
}
