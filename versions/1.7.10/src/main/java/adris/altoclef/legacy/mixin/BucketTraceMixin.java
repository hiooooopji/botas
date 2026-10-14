package adris.altoclef.legacy.mixin;

import net.minecraft.item.BucketItem;
import net.minecraft.world.World;
import net.minecraft.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records vanilla bucket results during the real-client smoke test. */
@Mixin(BucketItem.class)
public abstract class BucketTraceMixin {
    @Inject(method="onStartUse", at=@At("HEAD"))
    private void altoclef$bucketUse(net.minecraft.item.ItemStack stack,World world,net.minecraft.entity.player.PlayerEntity player,
                                    CallbackInfoReturnable<net.minecraft.item.ItemStack> result) {
        if(!Boolean.getBoolean("altoclef.legacy.smoke")) return;
        float yaw=-player.yaw*.017453292F-(float)Math.PI,pitch=-player.pitch*.017453292F;
        float horizontal=-net.minecraft.util.math.MathHelper.cos(pitch);
        net.minecraft.util.math.Vec3d eye=net.minecraft.util.math.Vec3d.of(player.x,player.y+1.62-player.heightOffset,player.z);
        net.minecraft.util.math.Vec3d end=eye.add(net.minecraft.util.math.MathHelper.sin(yaw)*horizontal*5.0,
                net.minecraft.util.math.MathHelper.sin(pitch)*5.0,net.minecraft.util.math.MathHelper.cos(yaw)*horizontal*5.0);
        boolean empty=net.minecraft.item.Item.REGISTRY.getId(stack.getItem()).toString().equals("minecraft:bucket");
        net.minecraft.util.hit.BlockHitResult hit=world.rayTrace(eye,end,empty,!empty,false);
        org.apache.logging.log4j.LogManager.getLogger("Botas/BucketTrace").info("Use {} eye={} ray={} side={}",
                net.minecraft.item.Item.REGISTRY.getId(stack.getItem()),eye,hit==null ? null : new net.minecraft.util.math.BlockPos(hit.x,hit.y,hit.z),hit==null ? -1 : hit.side);
    }
    @Inject(method="method_6320", at=@At("RETURN"))
    private void altoclef$bucketResult(World world,int x,int y,int z,CallbackInfoReturnable<Boolean> result) {
        if(Boolean.getBoolean("altoclef.legacy.smoke"))
            org.apache.logging.log4j.LogManager.getLogger("Botas/BucketTrace").info(
                    "Bucket placement at {} {} {}: success={}, block={}", x,y,z,result.getReturnValue(),Block.REGISTRY.getId(world.getBlock(x,y,z)));
    }
}
