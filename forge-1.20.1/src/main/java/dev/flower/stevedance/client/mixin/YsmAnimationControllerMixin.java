package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.YsmAnimationAdapter;
import dev.flower.stevedance.client.YsmModelPreview;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.OOOooo0o0OOOO0O0OOoOo0OO", remap = false)
public abstract class YsmAnimationControllerMixin {
    @Inject(
            method =
                    "Oo0Oo0o00O00Oo0OOoOOoooo(Lcom/elfmcys/yesstevemodel/OO00O0o0OooOOOo00OO00o00;Lcom/elfmcys/yesstevemodel/O0O00Oo0o0oO0oo0oO00OOoO;ZZ)V",
            at = @At("RETURN"),
            remap = false)
    private void stevedance$dance(
            @Coerce Object state,
            @Coerce Object context,
            boolean changed,
            boolean reset,
            CallbackInfo ci) {
        YsmModelPreview.applyController(this);
        YsmAnimationAdapter.applyController(this);
    }
}
