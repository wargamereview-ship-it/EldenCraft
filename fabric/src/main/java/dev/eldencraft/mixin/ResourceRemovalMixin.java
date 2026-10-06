package dev.eldencraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.eldencraft.world.ResourceNodes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Explosions, pistons and other removals deplete nodes just like mining; they cannot auto-refill. */
@Mixin(Level.class)
public abstract class ResourceRemovalMixin {
    @WrapOperation(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState eldencraft$resourceRemoved(LevelChunk chunk,BlockPos pos,BlockState next,int flags,Operation<BlockState> original) {
        BlockState previous=original.call(chunk,pos,next,flags);
        if(previous!=null) ResourceNodes.changed((Level)(Object)this,pos,previous,next);
        return previous;
    }
}
