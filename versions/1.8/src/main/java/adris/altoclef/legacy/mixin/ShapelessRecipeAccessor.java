package adris.altoclef.legacy.mixin;

import java.util.List;
import net.minecraft.recipe.ShapelessRecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShapelessRecipeType.class)
public interface ShapelessRecipeAccessor {
    @Accessor("stacks") List<?> altoclef$ingredients();
}
