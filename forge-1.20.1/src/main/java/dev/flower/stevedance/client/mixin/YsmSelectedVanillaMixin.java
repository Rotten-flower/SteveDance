package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.PerformerModelSelection;

import net.minecraftforge.client.event.RenderPlayerEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 仅对显式使用 Minecraft 皮肤的角色跳过 YSM 的替代绘制。
 * <p>Skip only YSM's replacement draw for actors explicitly using Minecraft skins.</p>
 */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.O0oOOo00o0oooOo0OoO0OOo0", remap = false)
public abstract class YsmSelectedVanillaMixin {
    @Inject(
            method =
                    "Oo0Oo0o00O00Oo0OOoOOoooo(Lnet/minecraftforge/client/event/RenderPlayerEvent$Pre;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void useVanilla(RenderPlayerEvent.Pre event, CallbackInfo ci) {
        if (event.isCanceled() || PerformerModelSelection.vanilla(event.getEntity())) {
            ci.cancel();
        }
    }
}
