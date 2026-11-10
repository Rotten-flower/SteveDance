package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.PvCharacterRenderer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原版演员主网格也使用同一源灯光。
 * <p>Native light on the actual vanilla body mesh.</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class VanillaPerformerLightMixin {
    @org.spongepowered.asm.mixin.Unique private LivingEntity stevedance$lightingEntity;

    @Inject(method = "render", at = @At("HEAD"))
    private void stevedance$entity(
            LivingEntity entity,
            float yaw,
            float tick,
            com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.client.renderer.MultiBufferSource buffers,
            int light,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        stevedance$lightingEntity = entity;
    }

    // 按唯一参数类型定位，兼容其他模组 Redirect 插入的参数。 / Match the unique type even after a mod Redirect adds
    // 中文：参数。
    // EN: arguments.
    @org.spongepowered.asm.mixin.injection.ModifyArg(
            method = "render",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"))
    private com.mojang.blaze3d.vertex.VertexConsumer stevedance$packLight(
            com.mojang.blaze3d.vertex.VertexConsumer vertices) {
        return PvCharacterRenderer.compatibleVertices(stevedance$lightingEntity, vertices);
    }

    @SuppressWarnings("unchecked")
    @Inject(method = "getRenderType", at = @At("RETURN"), cancellable = true)
    private void stevedance$sourceLight(
            LivingEntity entity,
            boolean visible,
            boolean translucent,
            boolean glowing,
            CallbackInfoReturnable<RenderType> ci) {
        if (!(entity instanceof Player) || !visible || ci.getReturnValue() == null) {
            return;
        }
        var renderer = (EntityRenderer<LivingEntity>) (Object) this;
        ci.setReturnValue(
                PvCharacterRenderer.forActor(
                        entity, renderer.getTextureLocation(entity), ci.getReturnValue()));
    }
}
