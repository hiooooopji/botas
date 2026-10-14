package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.LegacyTickControl;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.ClientTickTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class GameClockMixin {
    @Inject(method="runGameLoop",at=@At(value="INVOKE",target="Lnet/minecraft/client/MinecraftClient;updateDisplay()V"))
    private void captureTasFrame(CallbackInfo ci) {adris.altoclef.legacy.LegacyTasRecorder.rendered((MinecraftClient)(Object)this);}
    @Redirect(method="runGameLoop",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/AchievementNotification;tick()V"))
    private void hideRecordingToast(net.minecraft.client.gui.AchievementNotification notification) {
        if(!adris.altoclef.legacy.LegacyTasRecorder.recording())notification.tick();
    }
    @Inject(method="tick",at=@At("HEAD"))
    private void before(CallbackInfo ci) {
        adris.altoclef.legacy.LegacyBootstrap.prepareSimulationTick();
        LegacyTickControl.beginClient((MinecraftClient)(Object)this);
    }
    @Redirect(method="tick",at=@At(value="FIELD",target="Lnet/minecraft/client/MinecraftClient;paused:Z"))
    private boolean freezeSimulation(MinecraftClient client) {return client.isPaused() || LegacyTickControl.freezePhysics();}
    @Redirect(method="runGameLoop",at=@At(value="FIELD",target="Lnet/minecraft/client/render/ClientTickTracker;tickDelta:F",opcode=180))
    private float stableInterpolation(ClientTickTracker tracker) {return LegacyTickControl.active() ? 1 : tracker.tickDelta;}
    @Inject(method={"doAttack","doUse","handleBlockBreaking"},at=@At("HEAD"),cancellable=true)
    private void freezeClicks(CallbackInfo ci) {if(LegacyTickControl.freezePhysics()) ci.cancel();}
}
