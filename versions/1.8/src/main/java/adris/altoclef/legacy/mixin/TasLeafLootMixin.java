package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.TasLootRng;
import java.util.Random;
import net.minecraft.block.*;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin({Leaves1Block.class,Leaves2Block.class})
public abstract class TasLeafLootMixin {
    @Redirect(method="dropApple",at=@At(value="INVOKE",target="Ljava/util/Random;nextInt(I)I"))
    private int altoclef$apple(Random random,int bound,World world,BlockPos pos,BlockState state,int chance) {
        return TasLootRng.appleRoll(random,bound,world,pos);
    }
}
