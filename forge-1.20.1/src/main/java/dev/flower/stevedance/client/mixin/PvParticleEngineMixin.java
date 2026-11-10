package dev.flower.stevedance.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.client.MenuCharacterPresentation;
import dev.flower.stevedance.client.PvPlaybackController;

import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 独立演出绘制期间隐藏世界粒子。
 * <p>Hides world particles during isolated presentation rendering.</p>
 */
@Mixin(ParticleEngine.class)
public abstract class PvParticleEngineMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void stevedance$hideWorldParticles(
            PoseStack poseStack,
            MultiBufferSource.BufferSource buffers,
            LightTexture lightTexture,
            Camera camera,
            float partialTick,
            CallbackInfo ci) {
        if (MenuCharacterPresentation.active() || !dev.flower.stevedance.client.PvVisibility.world("particles")) {
            ci.cancel();
        }
    }
}
