package adris.altoclef.legacy.mixin;

import adris.altoclef.legacy.LegacyTickControl;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class TickFenceMixin {
    @Shadow public ServerPlayerEntity player;
    @Inject(method="onCustomPayload",at=@At(value="INVOKE",target="Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/util/ThreadExecutor;)V",shift=At.Shift.AFTER),cancellable=true)
    private void fence(CustomPayloadC2SPacket packet,CallbackInfo ci) {
        if(!LegacyTickControl.CHANNEL.equals(packet.getChannel())) return;
        if(packet.getPayload().readableBytes()==8) LegacyTickControl.acknowledge(player.server,packet.getPayload().readLong());
        ci.cancel();
    }
}
