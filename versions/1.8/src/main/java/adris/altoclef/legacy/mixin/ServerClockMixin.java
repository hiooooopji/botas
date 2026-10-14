package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.LegacyTickControl;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Queue;
import java.util.concurrent.FutureTask;

@Mixin(MinecraftServer.class)
public abstract class ServerClockMixin {
    @Shadow @Final protected Queue queue;
    @Inject(method="setupWorld()V",at=@At("HEAD"),cancellable=true)
    private void before(CallbackInfo ci) {
        if(!LegacyTickControl.applies((MinecraftServer)(Object)this)) return;
        // Drain jobs/packets even while frozen, including the ordered client tick fence.
        synchronized(queue) {while(!queue.isEmpty()) ((FutureTask)queue.poll()).run();}
        if(!LegacyTickControl.clock().beginServer()) ci.cancel();
    }
    @Inject(method="setupWorld()V",at=@At("RETURN"))
    private void after(CallbackInfo ci) {
        if(LegacyTickControl.applies((MinecraftServer)(Object)this)) LegacyTickControl.clock().finishServer();
    }
}
