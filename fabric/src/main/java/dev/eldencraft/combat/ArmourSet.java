package dev.eldencraft.combat;

import java.util.List;

/**
 * The 17 boss armour sets and what wearing them does. A set is told apart by its armour trim pattern (and the piece
 * must carry the reward tag, so a trim from a smithing table is not enough). Two pieces switch the passive on; four
 * also raise every ward the wearer has by one level. Data only: {@link SetPassives} applies it.
 */
public enum ArmourSet {
	WAYFARERS_HIDE("Wayfarer's Hide", "wild", "Light on your feet: 8% faster",
		List.of(new Attr("movement_speed", 0.08, Op.BASE))),
	MORNE_WANDERER("Morne Wanderer", "coast", "Swims faster and holds breath longer",
		List.of(new Attr("water_movement_efficiency", 0.5, Op.VALUE), new Attr("oxygen_bonus", 2.0, Op.VALUE))),
	CARIAN_LAKEGUARD("Carian Lakeguard", "tide", "Glintstone plating: armour toughness +2",
		List.of(new Attr("armor_toughness", 2.0, Op.VALUE))),
	STORMGATE_VANGUARD("Stormgate Vanguard", "sentry", "Stands firm: knockback resistance 40%",
		List.of(new Attr("knockback_resistance", 0.4, Op.VALUE))),
	AMBER_ROADWARDEN("Amber Roadwarden", "raiser", "Heals slowly while standing still",
		List.of(), List.of(new Fx("regeneration", 0, false, true)), Special.NONE),
	REDMANE_EXILE("Redmane Exile", "dune", "Fire resistance",
		List.of(), List.of(new Fx("fire_resistance", 0, false, false)), Special.NONE),
	ETERNAL_PATHFINDER("Eternal Pathfinder", "wayfinder", "Nearby enemies glow, even through walls",
		List.of(), List.of(), Special.GLOW_ENEMIES),
	GILDED_OMENWARD("Gilded Omenward", "host", "Each kill grants a few absorption hearts",
		List.of(), List.of(), Special.KILL_ABSORPTION),
	DUSKBOUND_SEEKER("Duskbound Seeker", "eye", "Kills give bonus experience",
		List.of(), List.of(), Special.KILL_XP),
	CINDER_PILGRIM("Cinder Pilgrim", "snout", "Kills set nearby enemies alight",
		List.of(), List.of(), Special.KILL_BURN),
	WINTERBOUND_SENTINEL("Winterbound Sentinel", "ward", "Cold never slows you",
		List.of(), List.of(), Special.NO_FREEZE),
	BLOODROOT_SOVEREIGN("Bloodroot Sovereign", "vex", "Each kill heals you a little",
		List.of(), List.of(), Special.KILL_HEAL),
	LAST_AGE_CHAMPION("Last Age Champion", "bolt", "Strikes and moves faster",
		List.of(new Attr("attack_speed", 0.2, Op.BASE), new Attr("movement_speed", 0.05, Op.BASE))),
	VEILED_COASTKEEPER("Veiled Coastkeeper", "flow", "Never short of breath underwater",
		List.of(), List.of(new Fx("water_breathing", 0, false, false)), Special.NONE),
	NAMELESS_OATH("Nameless Oath", "silence", "Moves swiftly while sneaking",
		List.of(new Attr("sneaking_speed", 0.4, Op.VALUE))),
	ECLIPSE_SOVEREIGN("Eclipse Sovereign", "spire", "Hits harder the lower your health",
		List.of(), List.of(), Special.LOW_HEALTH_DAMAGE),
	ASHEN_CRUCIBLE("Ashen Crucible", "rib", "Each kill grants a burst of strength",
		List.of(), List.of(), Special.KILL_STRENGTH);

	public enum Op { VALUE, BASE }

	/** An attribute bonus: Minecraft attribute name, amount, and whether it adds a flat value or a share of the base. */
	public record Attr(String attribute, double amount, Op op) {
	}

	/** A status effect kept on the wearer, optionally only in water or only while standing still. */
	public record Fx(String effect, int amplifier, boolean inWater, boolean standingStill) {
	}

	/** Passives that need code of their own rather than an attribute or an effect. */
	public enum Special { NONE, GLOW_ENEMIES, KILL_ABSORPTION, KILL_XP, KILL_BURN, NO_FREEZE, KILL_HEAL, LOW_HEALTH_DAMAGE, KILL_STRENGTH }

	public final String title;
	public final String pattern;
	public final String passive;
	public final List<Attr> attributes;
	public final List<Fx> effects;
	public final Special special;

	ArmourSet(String title, String pattern, String passive, List<Attr> attributes) {
		this(title, pattern, passive, attributes, List.of(), Special.NONE);
	}

	ArmourSet(String title, String pattern, String passive, List<Attr> attributes, List<Fx> effects, Special special) {
		this.title = title;
		this.pattern = pattern;
		this.passive = passive;
		this.attributes = attributes;
		this.effects = effects;
		this.special = special;
	}

	/** Pieces needed for the passive, and for the ward boost. */
	public static final int PASSIVE_PIECES = 2, WARD_PIECES = 4;

	/** The set whose pieces carry this trim pattern, or null. */
	public static ArmourSet byPattern(String pattern) {
		for (ArmourSet set : values()) {
			if (set.pattern.equals(pattern)) {
				return set;
			}
		}
		return null;
	}

	/** The line on a set piece's tooltip. */
	public String lore() {
		return title + " set: 2 pieces - " + passive + "; 4 pieces - every ward you wear +1";
	}
}
