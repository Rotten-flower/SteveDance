package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.PvPlaybackController;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 仅在独立PV播放期间隐藏世界方块实体。
 * <p>Hide world block entities only during isolated PV playback.</p>
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class PresentationBlockEntityMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void stevedance$menuScenery(CallbackInfo ci) {
        if (!dev.flower.stevedance.client.PvVisibility.world("block_entities")) {
            ci.cancel();
        }
    }
}
