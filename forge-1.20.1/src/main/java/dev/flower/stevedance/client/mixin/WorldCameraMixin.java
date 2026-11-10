package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.PerformanceCameraController;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class WorldCameraMixin {
    @Shadow private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "setup", at = @At("TAIL"))
    private void stevedance$camera(
            BlockGetter level,
            Entity entity,
            boolean detached,
            boolean mirrored,
            float partialTick,
            CallbackInfo ci) {
        if (PerformanceCameraController.active()) {
            var frame = PerformanceCameraController.sample();
            setPosition(frame.position());
            setRotation(frame.yaw(), frame.pitch());
            this.detached = true;
        }
    }
}
