package dev.eldencraft.world;

import com.google.gson.JsonObject;

/** Pure resource policy: valuable gathering ores are confined to explicit interior mines. */
public final class ResourceRules {
    private ResourceRules() {}
    public static boolean isMine(JsonObject rules, int world) {
        return rules.getAsJsonObject("mines").has(Integer.toUnsignedString(world));
    }
    public static String rockBonus(int tier, int roll) {
        return roll < 10 ? tier >= 2 ? "raw_iron" : "raw_copper" : null;
    }
    public static String ore(int tier, int roll) {
        if (tier >= 5 && roll < 5) return "ancient_debris";
        if (tier >= 4 && roll < 18 || tier == 3 && roll < 5) return "diamond_ore";
        if (tier >= 3 && roll < 35) return "gold_ore";
        if (tier >= 2 && roll < 70) return "iron_ore";
        return roll < 85 ? "copper_ore" : "coal_ore";
    }
    public static long hash(long seed, int world, int x, int z) {
        long v = seed ^ Integer.toUnsignedLong(world) * 0x9e3779b97f4a7c15L
            ^ x * 0x632be59bd9b4e019L ^ z * 0x85157af5L;
        v = (v ^ v >>> 30) * 0xbf58476d1ce4e5b9L;
        v = (v ^ v >>> 27) * 0x94d049bb133111ebL;
        return v ^ v >>> 31;
    }
    /** A harvested node stays depleted until a later grace cycle; reload is not a new cycle. */
    public static boolean depleted(long harvested, long cycle) { return harvested >= cycle; }
    /** Nodes keep this far (blocks) from a grace, so they never sit in its light or on its ground. */
    public static final double GRACE_CLEARANCE = 7.0;
    /** True when (x, y, z) is within {@code GRACE_CLEARANCE} (across, and 6 up or down) of any grace in the list. */
    public static boolean nearGrace(java.util.List<int[]> graces, double x, double y, double z) {
        for (int[] g : graces) {
            if (Math.abs(g[1] + 0.5 - y) <= 6.0 && Math.hypot(g[0] + 0.5 - x, g[2] + 0.5 - z) < GRACE_CLEARANCE) return true;
        }
        return false;
    }
    /**
     * Where the bottom of a deposit rests over ground at {@code ground}. A cube cannot sit at a fractional
     * height: it sinks into the ground (up to 0.6, the rest shows) or, if that would bury it, rises to the next
     * whole block (a float of at most 0.4). A slab has half-block steps, so it takes the nearest one.
     */
    public static double restHeight(double ground, boolean slab) {
        if (slab) return Math.round(ground * 2.0) / 2.0;
        double base = Math.floor(ground);
        return ground - base <= 0.6 ? base : base + 1.0;
    }
}
