package adris.altoclef.legacy.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.recipe.ShapedRecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShapedRecipeType.class)
public interface ShapedRecipeAccessor {
    @Accessor("width") int altoclef$width();
    @Accessor("height") int altoclef$height();
    @Accessor("ingredients") ItemStack[] altoclef$ingredients();
}
