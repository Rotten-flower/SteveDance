package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.ClientPlaybackRuntime;
import dev.flower.stevedance.client.VanillaPlayerPoseAdapter;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public abstract class PlayerDanceMixin {
    @Unique private boolean stevedance$wasDancing;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void stevedance$restore(
            LivingEntity player,
            float swing,
            float amount,
            float age,
            float yaw,
            float pitch,
            CallbackInfo ci) {
        dev.flower.stevedance.client.FirstPersonBody.restore((PlayerModel<?>) (Object) this);
        if (stevedance$wasDancing) {
            VanillaPlayerPoseAdapter.reset((PlayerModel<?>) (Object) this);
        }
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void stevedance$dance(
            LivingEntity player,
            float swing,
            float amount,
            float age,
            float yaw,
            float pitch,
            CallbackInfo ci) {
        stevedance$wasDancing =
                ClientPlaybackRuntime.MOTION_SNAPSHOTS.containsKey(player.getUUID())
                        || ClientPlaybackRuntime.DANCES.containsKey(player.getUUID());
        if (stevedance$wasDancing) {
            VanillaPlayerPoseAdapter.apply((PlayerModel<?>) (Object) this, player);
        }
        dev.flower.stevedance.client.FirstPersonBody.apply((PlayerModel<?>) (Object) this, player);
    }
}
