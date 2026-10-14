package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.EndermanCombatController;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(EndermanEntity.class)
public abstract class EndermanCombatMixin {
    @Redirect(method="dropLoot",at=@At(value="INVOKE",target="Ljava/util/Random;nextInt(I)I"))
    private int altoclef$pearl(java.util.Random random,int bound) {
        return EndermanCombatController.pearlRoll((EndermanEntity)(Object)this,random,bound);
    }
    @Unique private float altoclef$healthBefore;
    @Inject(method="damage",at=@At("HEAD"))
    private void altoclef$beforeDamage(DamageSource source,float amount,CallbackInfoReturnable<Boolean> ci) {
        altoclef$healthBefore=((EndermanEntity)(Object)this).getHealth();
    }
    @Inject(method="damage",at=@At("RETURN"))
    private void altoclef$damage(DamageSource source,float amount,CallbackInfoReturnable<Boolean> ci) {
        EndermanCombatController.damage((EndermanEntity)(Object)this,source,altoclef$healthBefore);
    }
    @Inject(method="tickMovement",at=@At("TAIL"))
    private void altoclef$tick(CallbackInfo ci) {EndermanCombatController.afterTick((EndermanEntity)(Object)this);}
}
