package dev.eldencraft.world;

import dev.eldencraft.EldenCraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ResourceBlocks {
    private ResourceBlocks() {}
    public static final Identifier LOOSE_STONE_ID = Identifier.fromNamespaceAndPath(EldenCraft.MOD_ID, "loose_stone");
    public static final Block LOOSE_STONE = Registry.register(BuiltInRegistries.BLOCK, LOOSE_STONE_ID,
        new SlabBlock(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).sound(SoundType.STONE)
            .strength(0.35f).setId(ResourceKey.create(Registries.BLOCK, LOOSE_STONE_ID))));
    public static void init() {}
}
