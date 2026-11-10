package dev.flower.stevedance.client;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.IdentityHashMap;
import java.util.Map;

/** Hide only the viewed head for this draw; restore shared models immediately.
 *  只隐藏当前第一人称头部，绘制后恢复共享模型。 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class FirstPersonBody {
    private static final Map<PlayerModel<?>, boolean[]> SAVED = new IdentityHashMap<>();

    public static void apply(PlayerModel<?> model, LivingEntity actor) {
        if (!StageCamera.hideHead(actor.getUUID())) {
            return;
        }
        SAVED.putIfAbsent(model, new boolean[] {model.head.visible, model.hat.visible});
        model.head.visible = false;
        model.hat.visible = false;
    }

    public static void restore(PlayerModel<?> model) {
        var saved = SAVED.remove(model);
        if (saved != null) {
            model.head.visible = saved[0];
            model.hat.visible = saved[1];
        }
    }

    @SubscribeEvent
    public static void after(RenderPlayerEvent.Post event) {
        restore(event.getRenderer().getModel());
    }
}
