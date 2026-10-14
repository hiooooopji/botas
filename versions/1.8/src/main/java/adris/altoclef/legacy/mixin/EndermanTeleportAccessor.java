package adris.altoclef.legacy.mixin;
import net.minecraft.entity.mob.EndermanEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(EndermanEntity.class)
public interface EndermanTeleportAccessor {
    @Invoker("teleportTo") boolean altoclef$teleportTo(double x,double y,double z);
}
