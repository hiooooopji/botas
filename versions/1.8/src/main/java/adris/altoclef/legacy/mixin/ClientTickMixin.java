package adris.altoclef.legacy.mixin;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.ClientTickEvent;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class ClientTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void altoclef$afterTick(CallbackInfo ci) {
        adris.altoclef.legacy.LegacyTickControl.endClient((MinecraftClient)(Object)this);
        EventBus.publish(new ClientTickEvent());
    }
}
