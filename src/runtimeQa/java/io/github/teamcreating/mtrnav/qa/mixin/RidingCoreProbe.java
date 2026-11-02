package io.github.teamcreating.mtrnav.qa.mixin;
import io.github.teamcreating.mtrnav.qa.RuntimeRideQa;
import org.mtr.core.operation.UpdateVehicleRidingEntities;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=UpdateVehicleRidingEntities.class,remap=false)
abstract class RidingCoreProbe {
    @Inject(method="update",at=@At("RETURN"))
    private void observe(Simulator simulator,CallbackInfoReturnable<UpdateVehicleRidingEntities> ci){
        RuntimeRideQa.coreUpdates.incrementAndGet();
        RuntimeRideQa.lastCoreUpdate=Utilities.getJsonObjectFromData((UpdateVehicleRidingEntities)(Object)this).toString()+" success="+(ci.getReturnValue()!=null);
    }
}
