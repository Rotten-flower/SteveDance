package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.PerformerModelSelection;
import dev.flower.stevedance.core.PerformerModelChoice;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 皮肤覆盖仅作用于预览实体与当前演出的演员。
 * <p>Skin overrides are scoped to preview entities and the currently presented actors.</p>
 */
@Mixin(AbstractClientPlayer.class)
public abstract class PerformerSkinMixin {
    @Inject(
            method = "getSkinTextureLocation()Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"),
            cancellable = true)
    private void skin(CallbackInfoReturnable<ResourceLocation> ci) {
        var actor = (AbstractClientPlayer) (Object) this;
        var c = PerformerModelSelection.renderingChoice(actor);
        if (c != null && c.kind() != PerformerModelChoice.Kind.YSM) {
            ci.setReturnValue(PerformerModelSelection.skin(c, actor));
        }
    }

    @Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
    private void arms(CallbackInfoReturnable<String> ci) {
        var actor = (AbstractClientPlayer) (Object) this;
        var c = PerformerModelSelection.renderingChoice(actor);
        if (c != null && c.kind() != PerformerModelChoice.Kind.YSM) {
            ci.setReturnValue(PerformerModelSelection.skinType(c, actor));
        }
    }
}
