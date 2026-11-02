package io.github.teamcreating.mtrnav.qa.mixin;
import io.github.teamcreating.mtrnav.qa.RuntimeRideQa;
import org.mtr.packet.PacketUpdateVehicleRidingEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=PacketUpdateVehicleRidingEntities.class,remap=false)
abstract class RidingPacketProbe {
    @Inject(method="runServerOutbound",at=@At("HEAD"))
    private void observe(ServerLevel world,ServerPlayer player,CallbackInfo ci){RuntimeRideQa.ridingPackets.incrementAndGet();}
}
