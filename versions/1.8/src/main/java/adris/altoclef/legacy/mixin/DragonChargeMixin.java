package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.DragonChargeTracker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragonEntity.class)
public abstract class DragonChargeMixin {
    @Shadow private Entity target;
    @Unique private DragonChargeTracker.Snapshot altoclef$previous;
    @Inject(method="tickMovement",at=@At("TAIL"))
    private void altoclef$observeCharge(CallbackInfo ci) {
        EnderDragonEntity dragon=(EnderDragonEntity)(Object)this;
        if(dragon.world.isClient) return;
        double x=dragon.partHead.x,y=dragon.partHead.y,z=dragon.partHead.z;
        DragonChargeTracker.Snapshot previous=altoclef$previous;
        DragonChargeTracker.Snapshot snapshot=new DragonChargeTracker.Snapshot(
                dragon.getEntityId(),dragon.connectedCrystal==null ? -1 : dragon.connectedCrystal.getEntityId(),
                target==null ? null : target.getUuid(),x,y,z,
                previous==null ? 0 : x-previous.x,
                previous==null ? 0 : y-previous.y,
                previous==null ? 0 : z-previous.z,
                dragon.field_3742,dragon.field_3751,dragon.field_3752);
        altoclef$previous=snapshot;
        DragonChargeTracker.publish(snapshot);
    }
}
