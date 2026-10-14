package adris.altoclef.legacy.mixin;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SendChatEvent;
import net.minecraft.entity.player.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ChatInputMixin {
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void altoclef$beforeChat(String message, CallbackInfo ci) {
        SendChatEvent event = new SendChatEvent(message);
        EventBus.publish(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }
}
