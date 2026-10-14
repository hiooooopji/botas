package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.TasPearlFlight;
import net.minecraft.entity.thrown.ThrowableEntity;
import net.minecraft.entity.thrown.EnderPearlEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Zero launch deviation for the active private TAS pearl only. */
@Mixin(ThrowableEntity.class)
public abstract class TasPearlLaunchMixin {
    @Redirect(method="setVelocity(DDDFF)V",at=@At(value="INVOKE",target="Ljava/util/Random;nextGaussian()D"))
    private double altoclef$tasAim(java.util.Random random) {
        double vanilla=random.nextGaussian();
        return (Object)this instanceof EnderPearlEntity ? TasPearlFlight.launchSpread((EnderPearlEntity)(Object)this,vanilla) : vanilla;
    }
}
