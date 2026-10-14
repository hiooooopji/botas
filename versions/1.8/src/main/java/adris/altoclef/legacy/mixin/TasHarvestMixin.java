package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.TasLootRng;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public abstract class TasHarvestMixin {
    @Inject(method="harvest",at=@At("HEAD"))
    private void altoclef$begin(World w,PlayerEntity p,BlockPos pos,BlockState s,BlockEntity be,CallbackInfo ci) {TasLootRng.beginHarvest(w,p,pos);}
    @Inject(method="harvest",at=@At("RETURN"))
    private void altoclef$end(World w,PlayerEntity p,BlockPos pos,BlockState s,BlockEntity be,CallbackInfo ci) {TasLootRng.endHarvest();}
}
