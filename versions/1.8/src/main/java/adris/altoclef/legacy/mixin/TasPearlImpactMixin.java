package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.TasPearlFlight;
import net.minecraft.entity.thrown.EnderPearlEntity;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderPearlEntity.class)
public abstract class TasPearlImpactMixin {
    @Redirect(method="onCollision",at=@At(value="INVOKE",target="Lnet/minecraft/entity/LivingEntity;damage(Lnet/minecraft/entity/damage/DamageSource;F)Z"))
    private boolean altoclef$tasDamage(net.minecraft.entity.LivingEntity player,net.minecraft.entity.damage.DamageSource source,float amount) {
        return !TasPearlFlight.preventDamage((EnderPearlEntity)(Object)this,player) && player.damage(source,amount);
    }
    @Inject(method="onCollision",at=@At("RETURN"))
    private void altoclef$impact(BlockHitResult hit,CallbackInfo ci) {
        TasPearlFlight.impacted((EnderPearlEntity)(Object)this);
    }
}
