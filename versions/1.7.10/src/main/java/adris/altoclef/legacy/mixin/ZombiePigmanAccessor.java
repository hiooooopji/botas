package adris.altoclef.legacy.mixin;

import net.minecraft.entity.mob.ZombiePigmanEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ZombiePigmanEntity.class)
public interface ZombiePigmanAccessor {
    @Accessor("anger") int altoclef$anger();
}
