package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.PvPlaybackController;

import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * F6控制世界各通道，演出模式不隐式隐藏世界。
 * <p>F6 controls world channels independently of the playback space.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class PvEnvironmentMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void stevedance$hdr(CallbackInfo ci) {
        dev.flower.stevedance.client.PvHdrRenderer.prepare();
    }

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void stevedance$back(CallbackInfo ci) {
        if (!dev.flower.stevedance.client.PvVisibility.world("sky")) {
            stevedance$drawBack();
            ci.cancel();
        }
    }

    @Inject(method = "renderSky", at = @At("TAIL"))
    private void stevedance$afterSky(CallbackInfo ci) {
        stevedance$drawBack();
    }

    private static void stevedance$drawBack() {
        if (PvPlaybackController.active()
                    && PvPlaybackController.sceneryActive()
                    && dev.flower.stevedance.client.PvAetTimeline.hasBack(
                            PvPlaybackController.timeMs())) {
            dev.flower.stevedance.client.PvAetTimeline.drawBack();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void stevedance$clouds(CallbackInfo ci) {
        if (!dev.flower.stevedance.client.PvVisibility.world("clouds")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void stevedance$terrain(CallbackInfo ci) {
        if (!dev.flower.stevedance.client.PvVisibility.world("terrain")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void stevedance$weather(CallbackInfo ci) {
        if (!dev.flower.stevedance.client.PvVisibility.world("weather")) {
            ci.cancel();
        }
    }
}
