package adris.altoclef.legacy.mixin;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientTickEvent;
import adris.altoclef.legacy.LegacyBootstrap;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class ClientTickMixin {
    // Vanilla gates held attack on window focus. Preserve the bot's held input
    // while it owns a mining task, including background smoke-test runs.
    @ModifyVariable(method = "handleBlockBreaking", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean altoclef$heldMining(boolean held) {
        return held || LegacyBootstrap.holdsMiningInput();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void altoclef$afterTick(CallbackInfo ci) {
        EventBus.publish(new ClientTickEvent());
    }
}
