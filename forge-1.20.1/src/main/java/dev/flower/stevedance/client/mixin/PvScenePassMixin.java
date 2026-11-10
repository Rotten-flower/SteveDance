package dev.flower.stevedance.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.client.PerformerPropBindings;
import dev.flower.stevedance.client.PvMovieBackground;
import dev.flower.stevedance.client.PvPostRenderer;
import dev.flower.stevedance.client.PvStageScreen;
import dev.flower.stevedance.client.SessionStageRenderer;
import dev.flower.stevedance.client.SourcePropContacts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forward geometry follows the completed world pipeline, retaining its real depth.
 * 世界管线完成后绘制原作网格，避免向光影的 G-buffer 写入不完整材质。 */
@Mixin(GameRenderer.class)
public abstract class PvScenePassMixin {
    @Inject(
            method = "renderLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
                            shift = At.Shift.AFTER))
    private void stevedance$scene(
            float partialTick, long finishTime, PoseStack pose, CallbackInfo ci) {
        // 世界深度完成后依次绘制背景、舞台/屏幕、道具，再统一后期与屏幕采样。
        // Preserve this order: world depth -> background/stage/props -> post -> screen capture.
        SourcePropContacts.prepare();
        PvMovieBackground.render();
        SessionStageRenderer.render(pose, Minecraft.getInstance().gameRenderer.getMainCamera());
        PerformerPropBindings.renderPending();
        PvPostRenderer.render();
        PvStageScreen.capture();
    }
}
