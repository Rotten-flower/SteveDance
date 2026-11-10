package dev.flower.stevedance.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.flower.stevedance.client.YsmPropAdapter;

import net.minecraft.client.renderer.MultiBufferSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.OOoo0o0oO000ooO0Oo00OoOo", remap = false)
public abstract class YsmPropMixin {
    @Inject(
            method =
                    "Oo0Oo0o00O00Oo0OOoOOoooo(Lcom/elfmcys/yesstevemodel/o0O0oOooOo0OoOo0oOo00O00;Lcom/mojang/blaze3d/vertex/PoseStack;FLnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At("RETURN"),
            remap = false)
    private void attach(
            @Coerce Object model,
            PoseStack stack,
            float tick,
            MultiBufferSource buffers,
            VertexConsumer vertices,
            int light,
            int overlay,
            float r,
            float g,
            float b,
            float a,
            CallbackInfo ci) {
        YsmPropAdapter.render(model, stack);
    }
}
