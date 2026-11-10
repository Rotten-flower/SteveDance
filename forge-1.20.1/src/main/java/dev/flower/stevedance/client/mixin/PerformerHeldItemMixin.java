package dev.flower.stevedance.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.client.ClientPlaybackRuntime;

import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版与YSM共用手持物品绘制，背包内容不变。
 * <p>Shared held-item rendering used by vanilla and YSM; inventory contents are untouched.</p>
 */
@Mixin(ItemInHandRenderer.class)
public abstract class PerformerHeldItemMixin {
    @Inject(
            method =
                    "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private void stevedance$hideHeldItem(
            LivingEntity performer,
            ItemStack item,
            ItemDisplayContext context,
            boolean left,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            CallbackInfo ci) {
        if (ClientPlaybackRuntime.MOTION_SNAPSHOTS.containsKey(performer.getUUID())
                || ClientPlaybackRuntime.DANCES.containsKey(performer.getUUID())) {
            ci.cancel();
        }
    }
}
