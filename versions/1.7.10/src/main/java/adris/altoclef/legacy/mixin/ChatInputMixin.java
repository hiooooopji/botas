package adris.altoclef.legacy.mixin;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.SendChatEvent;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class ChatInputMixin {
    @Inject(method = "method_6738", at = @At("HEAD"), cancellable = true)
    private void altoclef$beforeChat(String message, CallbackInfo ci) {
        SendChatEvent event = new SendChatEvent(message);
        EventBus.publish(event);
        if (event.isCancelled()) {
            ci.cancel();
        }
    }
}
