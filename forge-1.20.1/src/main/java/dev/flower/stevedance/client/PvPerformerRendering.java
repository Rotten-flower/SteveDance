package dev.flower.stevedance.client;

import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;

/**
 * 原生角色颜色应用于YSM实际网格绘制参数。
 * <p>Native character color is applied to YSM's actual mesh draw arguments.</p>
 */
public final class PvPerformerRendering {
    private static Method entity;
    private static boolean failed;
    private static Method texture;

    public static int actor(Object model) {
        try {
            var id = ((net.minecraft.world.entity.Entity) entity.invoke(model)).getUUID();
            return SceneCrowdRenderer.contains(id) ? 0 : PvPlaybackController.actor(id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("YSM performer interface unavailable", ex);
        }
    }

    public static net.minecraft.resources.ResourceLocation texture(Object model) {
        try {
            if (texture == null) {
                texture = model.getClass().getMethod("b_");
            }
            return (net.minecraft.resources.ResourceLocation) texture.invoke(model);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("YSM mesh texture interface unavailable", ex);
        }
    }

    public static PvLighting lighting(Object model) {
        if (failed
                || !PvPlaybackController.active()
                || !PvPlaybackController.nativeCharacterLighting()
                || PvPlaybackController.scene.lighting == null) {
            return null;
        }
        try {
            if (entity == null) {
                entity =
                        Class.forName(
                                        "com.elfmcys.yesstevemodel.o0000OoOooO0oo0o0oooo0Oo",
                                        false,
                                        model.getClass().getClassLoader())
                                .getMethod("OO00OOOOo0Ooo0oo0o0Oo0OO");
            }
            Object e = entity.invoke(model);
            if (!(e instanceof LivingEntity living)
                    || !ClientPlaybackRuntime.MOTION_SNAPSHOTS.containsKey(living.getUUID())) {
                return null;
            }
            var light = PvPlaybackController.scene.lighting;
            light.sample(PvPlaybackController.timeMs());
            return light;
        } catch (ReflectiveOperationException ex) {
            failed = true;
            dev.flower.stevedance.SteveDanceMod.LOGGER.error(
                    "YSM native-light interface unavailable", ex);
            return null;
        }
    }

    public static float tint(Object model, float value, int channel) {
        var l = lighting(model);
        return l == null || l.hdr() && PvCharacterRenderer.nativeShader()
                ? value
                : Math.max(0, Math.min(1, value * l.character[channel] + l.characterAdd[channel]));
    }

    public static void inheritModel(
            net.minecraft.world.entity.Entity source, net.minecraft.world.entity.Entity target) {
        if (!net.minecraftforge.fml.loading.LoadingModList.get().getMods().stream()
                .anyMatch(
                        m ->
                                m.getModId().equals("yes_steve_model")
                                        && m.getVersion()
                                                .toString()
                                                .equals("2.6.5-forge+mc1.20.1"))) {
            return;
        }
        try {
            var renderProvider =
                    Class.forName("com.elfmcys.yesstevemodel.O0OooOo0oOOoOoOoOooO000o");
            var render =
                    (net.minecraftforge.common.capabilities.Capability<?>)
                            renderProvider.getField("Oo0Oo0o00O00Oo0OOoOOoooo").get(null);
            Object sourceModel = source.getCapability(render).resolve().orElseThrow(),
                    targetModel = target.getCapability(render).resolve().orElseThrow();
            String id =
                    (String)
                            sourceModel
                                    .getClass()
                                    .getMethod("ooooO0o00oO0Oo0OOo0O0O0o")
                                    .invoke(sourceModel);
            var loader =
                    Class.forName("com.elfmcys.yesstevemodel.oOo00OoOO00O000ooO0Oo00o")
                            .getDeclaredMethod("o0OOooo0o0OO00OoOOOo0o0O", String.class);
            loader.setAccessible(true);
            loader.invoke(targetModel, id);
            // 中文：远程玩家通常会在YSM的同步包中收到此就绪握手。
            // EN: Remote players normally receive this readiness handshake in YSM's sync packet.
            var ready =
                    Class.forName("com.elfmcys.yesstevemodel.o0000OoOooO0oo0o0oooo0Oo")
                            .getDeclaredMethod("OO0OO0000O0ooO0oO000Oo0O");
            ready.setAccessible(true);
            ready.invoke(targetModel);
            dev.flower.stevedance.SteveDanceMod.LOGGER.info(
                    "Second world performer inherits YSM model {}", id);
        } catch (ReflectiveOperationException | java.util.NoSuchElementException ex) {
            dev.flower.stevedance.SteveDanceMod.LOGGER.error(
                    "Cannot inherit the YSM performer model", ex);
        }
    }
}
