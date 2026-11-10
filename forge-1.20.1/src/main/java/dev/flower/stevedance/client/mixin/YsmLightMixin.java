package dev.flower.stevedance.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.flower.stevedance.client.PvPerformerRendering;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.OOoo0o0oO000ooO0Oo00OoOo", remap = false)
public abstract class YsmLightMixin {
    private static final String DRAW =
            "Oo0Oo0o00O00Oo0OOoOOoooo(Lcom/elfmcys/yesstevemodel/o0O0oOooOo0OoOo0oOo00O00;Lcom/mojang/blaze3d/vertex/PoseStack;FLnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";
    private static final String BODY =
            "Oo0Oo0o00O00Oo0OOoOOoooo(Lcom/elfmcys/yesstevemodel/o0O0oOooOo0OoOo0oOo00O00;Lnet/minecraft/resources/ResourceLocation;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";
    @Unique private dev.flower.stevedance.client.YsmAnimationAdapter.ViewHead stevedance$viewHead;
    @Unique private Object stevedance$lightModel;
    @Unique private ResourceLocation stevedance$lightTexture;

    @Inject(method = BODY, at = @At("HEAD"), remap = false)
    private void stevedance$lightContext(
            @Coerce Object model,
            ResourceLocation texture,
            float yaw,
            float tick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            CallbackInfo ci) {
        stevedance$lightModel = model;
        stevedance$lightTexture = texture;
        stevedance$viewHead = dev.flower.stevedance.client.YsmAnimationAdapter.hideViewHead(model);
    }

    @Inject(method = BODY, at = @At("RETURN"), remap = false)
    private void stevedance$lightClear(
            @Coerce Object model,
            ResourceLocation texture,
            float yaw,
            float tick,
            PoseStack pose,
            MultiBufferSource buffers,
            int packedLight,
            CallbackInfo ci) {
        dev.flower.stevedance.client.YsmAnimationAdapter.restoreViewHead(stevedance$viewHead);
        stevedance$viewHead = null;
        stevedance$lightModel = null;
        stevedance$lightTexture = null;
    }

    @ModifyArg(
            method = BODY,
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lcom/elfmcys/yesstevemodel/OOoo0o0oO000ooO0Oo00OoOo;Oo0Oo0o00O00Oo0OOoOOoooo(Lcom/elfmcys/yesstevemodel/OOOO0O0O000O000000oOOO0o;Lcom/elfmcys/yesstevemodel/o0000OoOooO0oo0o0oooo0Oo;FLnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
                            remap = false),
            index = 3,
            remap = false)
    private RenderType stevedance$bodyLight(RenderType original) {
        Object model = stevedance$lightModel;
        var light = PvPerformerRendering.lighting(model);
        if (light == null
                || !light.hdr()
                || !dev.flower.stevedance.client.PvCharacterRenderer.nativeShader()) {
            return original;
        }
        return dev.flower.stevedance.client.PvCharacterRenderer.type(
                stevedance$lightTexture == null
                        ? PvPerformerRendering.texture(model)
                        : stevedance$lightTexture,
                PvPerformerRendering.actor(model));
    }

    @ModifyVariable(method = DRAW, at = @At("HEAD"), argsOnly = true, ordinal = 1, remap = false)
    private float red(
            float value,
            @Coerce Object model,
            PoseStack pose,
            float tick,
            MultiBufferSource buffers,
            VertexConsumer vertices,
            int light,
            int overlay,
            float r,
            float g,
            float b,
            float a) {
        return PvPerformerRendering.tint(model, value, 0);
    }

    @ModifyVariable(method = DRAW, at = @At("HEAD"), argsOnly = true, ordinal = 2, remap = false)
    private float green(
            float value,
            @Coerce Object model,
            PoseStack pose,
            float tick,
            MultiBufferSource buffers,
            VertexConsumer vertices,
            int light,
            int overlay,
            float r,
            float g,
            float b,
            float a) {
        return PvPerformerRendering.tint(model, value, 1);
    }

    @ModifyVariable(method = DRAW, at = @At("HEAD"), argsOnly = true, ordinal = 3, remap = false)
    private float blue(
            float value,
            @Coerce Object model,
            PoseStack pose,
            float tick,
            MultiBufferSource buffers,
            VertexConsumer vertices,
            int light,
            int overlay,
            float r,
            float g,
            float b,
            float a) {
        return PvPerformerRendering.tint(model, value, 2);
    }

    @ModifyVariable(method = DRAW, at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private int light(
            int value,
            @Coerce Object model,
            PoseStack pose,
            float tick,
            MultiBufferSource buffers,
            VertexConsumer vertices,
            int light,
            int overlay,
            float r,
            float g,
            float b,
            float a) {
        return PvPerformerRendering.lighting(model) == null ? value : 15728880;
    }
}
