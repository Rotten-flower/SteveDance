package dev.flower.stevedance.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.client.PerformanceCameraController;
import dev.flower.stevedance.client.PvPlaybackController;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VMD正交镜头投影整个世界。
 * <p>Orthographic VMD shots project the entire level.</p>
 */
@Mixin(GameRenderer.class)
public abstract class StageProjectionMixin {
    @Inject(
            method = {"bobView", "bobHurt"},
            at = @At("HEAD"),
            cancellable = true)
    private void stevedance$steadyCamera(PoseStack pose, float partial, CallbackInfo ci) {
        if (PerformanceCameraController.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true)
    private void stevedance$lens(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (!PerformanceCameraController.active()) {
            return;
        }
        var frame = PerformanceCameraController.sample();
        // 中文：原生外墙距离演员450单位，世界几何与
        // EN: Native outer walls are 450 units from the performer. World geometry and
        // 中文：玩家共享此投影，因此深度遮挡保持一致。
        // EN: players share this projection so depth occlusion remains consistent.
        if (PvPlaybackController.active() && frame.perspective()) {
            Matrix4f projection = new Matrix4f(cir.getReturnValue());
            float near = projection.perspectiveNear(), far = 4096;
            projection.m22(-(far + near) / (far - near)).m32(-2 * far * near / (far - near));
            cir.setReturnValue(projection);
            return;
        }
        if (frame.perspective()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float aspect = mc.getWindow().getWidth() / (float) mc.getWindow().getHeight(),
                half =
                        (float)
                                (Math.abs(frame.distance() * .1)
                                        * Math.tan(Math.toRadians(frame.fov()) / 2));
        cir.setReturnValue(
                new Matrix4f()
                        .ortho(
                                -half * aspect,
                                half * aspect,
                                -half,
                                half,
                                .05f,
                                mc.options.getEffectiveRenderDistance() * 64));
    }
}
