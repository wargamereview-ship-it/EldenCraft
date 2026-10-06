package dev.eldencraft.combat;

/** PlayerGameData order, also used by the native buildup/ward bridge. */
public enum NativeStatus {
    POISON("poison", 0x87a83c), SCARLET_ROT("scarlet_rot", 0xd35a49),
    BLOOD_LOSS("blood_loss", 0xb52d42), DEATHBLIGHT("deathblight", 0x897247),
    FROSTBITE("frostbite", 0x8bcee8), SLEEP("sleep", 0xa692d2), MADNESS("madness", 0xf0ba42);

    public final String id;
    public final int color;
    NativeStatus(String id, int color) { this.id = id; this.color = color; }
    public String translationKey() { return "effect.eldencraft." + id; }
}
