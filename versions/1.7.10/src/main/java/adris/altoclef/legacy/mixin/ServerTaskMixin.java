package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.LegacyServerTasks;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class ServerTaskMixin {
    @Inject(method = "stopServer", at = @At("RETURN"))
    private void altoclef$serverStopped(CallbackInfo ci) {
        LegacyServerTasks.stopped((MinecraftServer) (Object) this);
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void altoclef$runQueuedTasks(CallbackInfo ci) {
        LegacyServerTasks.tick((MinecraftServer) (Object) this);
    }
}
