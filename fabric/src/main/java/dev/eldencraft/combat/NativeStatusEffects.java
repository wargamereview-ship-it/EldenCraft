package dev.eldencraft.combat;

import dev.eldencraft.EldenCraft;
import java.util.EnumMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/** Display effects only. ER already applies the status damage through the native HP sensor. */
public final class NativeStatusEffects {
    private static final EnumMap<NativeStatus, Holder<MobEffect>> EFFECTS = new EnumMap<>(NativeStatus.class);
    private NativeStatusEffects() {}
    static final class DisplayEffect extends MobEffect {
        DisplayEffect(int color) { super(MobEffectCategory.HARMFUL, color); }
    }
    public static void init() {
        if (!EFFECTS.isEmpty()) return;
        for (NativeStatus status : NativeStatus.values()) {
            EFFECTS.put(status, Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
                Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, status.id), new DisplayEffect(status.color)));
        }
    }
    public static Holder<MobEffect> effect(NativeStatus status) { return EFFECTS.get(status); }
}
