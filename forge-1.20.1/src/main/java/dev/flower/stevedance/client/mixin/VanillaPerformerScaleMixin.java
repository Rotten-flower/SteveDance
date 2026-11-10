package dev.flower.stevedance.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.client.VanillaPlayerPoseAdapter;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 仅原版渲染器：YSM 使用其现有校准比例渲染自身网格。
 * <p>Vanilla renderer only: YSM renders its own mesh with its existing calibrated scale.</p>
 */
@Mixin(PlayerRenderer.class)
public abstract class VanillaPerformerScaleMixin {
    @Inject(
            method =
                    "scale(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;F)V",
            at = @At("TAIL"))
    private void stevedance$performanceScale(
            AbstractClientPlayer player, PoseStack pose, float partial, CallbackInfo ci) {
        float scale = VanillaPlayerPoseAdapter.performanceScale(player);
        if (scale != 1) {
            pose.scale(scale, scale, scale);
        }
    }
}
