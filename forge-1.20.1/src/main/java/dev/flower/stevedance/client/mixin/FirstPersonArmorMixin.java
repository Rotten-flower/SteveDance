package dev.flower.stevedance.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.client.StageCamera;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称头盔跟随头部显隐，身体护甲保持可见。
 * <p>First-person head armor follows head visibility; body armor remains visible.</p>
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class FirstPersonArmorMixin {
    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void stevedance$head(
            PoseStack pose,
            MultiBufferSource buffers,
            LivingEntity actor,
            EquipmentSlot slot,
            int light,
            HumanoidModel<?> model,
            CallbackInfo ci) {
        if (slot == EquipmentSlot.HEAD && StageCamera.hideHead(actor.getUUID())) {
            ci.cancel();
        }
    }
}
