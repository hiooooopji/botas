package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.LegacyBuildCapture;
import adris.altoclef.legacy.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.block.Block;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Optional;

@Mixin(World.class)
public abstract class WorldBuildCaptureMixin {
    @Unique private static final boolean altoclef$captureEnabled = Boolean.getBoolean("altoclef.legacy.manualTower");
    @Unique private final ThreadLocal<ArrayDeque<Optional<BlockState>>> altoclef$previous =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlock(IIILnet/minecraft/block/Block;II)Z", at = @At("HEAD"))
    private void altoclef$beforeBuildChange(int x, int y, int z, Block block, int data, int flags,
                                            CallbackInfoReturnable<Boolean> cir) {
        if(!altoclef$captureEnabled) return;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState old = LegacyBuildCapture.before((World) (Object) this, pos);
        altoclef$previous.get().push(Optional.ofNullable(old));
    }

    @Inject(method = "setBlock(IIILnet/minecraft/block/Block;II)Z", at = @At("RETURN"))
    private void altoclef$afterBuildChange(int x, int y, int z, Block block, int data, int flags,
                                           CallbackInfoReturnable<Boolean> cir) {
        if(!altoclef$captureEnabled) return;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState old = altoclef$previous.get().pop().orElse(null);
        if (old != null) LegacyBuildCapture.after(pos, old, new BlockState(((World) (Object) this).getBlock(x,y,z), ((World) (Object) this).getBlockData(x,y,z)), cir.getReturnValue());
    }
}
