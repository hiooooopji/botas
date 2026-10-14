package adris.altoclef.legacy;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

final class LegacyInteraction {
    private LegacyInteraction() { }

    static boolean rightClick(ClientPlayerInteractionManager manager, PlayerEntity player, World world,
                              ItemStack stack, BlockPos pos, Direction face, Vec3d hit) {
        return manager.method_1229(player, world, stack, pos.x, pos.y, pos.z, face.ordinal(), hit);
    }

    static boolean attackBlock(ClientPlayerInteractionManager manager, BlockPos pos, Direction face) {
        // Start normal mining and send START_DESTROY_BLOCK to the server.
        // method_1223 only removes the client block after mining has completed.
        manager.method_1235(pos.x, pos.y, pos.z, face.ordinal());
        return true;
    }

    static void continueBreaking(ClientPlayerInteractionManager manager, BlockPos pos, Direction face) {
        manager.method_1239(pos.x, pos.y, pos.z, face.ordinal());
    }
}
