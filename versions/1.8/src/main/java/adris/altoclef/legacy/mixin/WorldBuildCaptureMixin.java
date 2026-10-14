package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.LegacyBuildCapture;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Optional;

@Mixin(World.class)
public abstract class WorldBuildCaptureMixin {
    @Unique private static final boolean altoclef$captureEnabled =
            Boolean.getBoolean("altoclef.legacy.manualTower");
    @Shadow public abstract BlockState getBlockState(BlockPos pos);
    @Unique private final ThreadLocal<ArrayDeque<Optional<BlockState>>> altoclef$previous =
            ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("HEAD"))
    private void altoclef$beforeBuildChange(BlockPos pos, BlockState newState, int flags,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!altoclef$captureEnabled) return;
        BlockState old = LegacyBuildCapture.before((World) (Object) this, pos);
        altoclef$previous.get().push(Optional.ofNullable(old));
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("RETURN"))
    private void altoclef$afterBuildChange(BlockPos pos, BlockState newState, int flags,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (!altoclef$captureEnabled) return;
        BlockState old = altoclef$previous.get().pop().orElse(null);
        if (old != null) LegacyBuildCapture.after(pos, old, getBlockState(pos), cir.getReturnValue());
    }
}
