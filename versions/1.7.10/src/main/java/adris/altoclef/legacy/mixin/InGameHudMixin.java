package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.LegacyBootstrap;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method="render", at=@At("TAIL"))
    private void altoclef$renderStatus(float tickDelta, boolean hasScreen, int mouseX, int mouseY, CallbackInfo ci) { LegacyBootstrap.renderHud(); }
}
