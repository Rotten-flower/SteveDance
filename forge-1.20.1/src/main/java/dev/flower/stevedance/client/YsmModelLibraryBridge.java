package dev.flower.stevedance.client;

import dev.flower.stevedance.SteveDanceMod;

import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.loading.LoadingModList;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 所有受检的YSM 2.6.5库和渲染能力访问都位于此处。
 * <p>All inspected YSM 2.6.5 library and render-capability access lives here.</p>
 */
public final class YsmModelLibraryBridge {
    private static final String PREFIX = "com.elfmcys.yesstevemodel.";
    private static Capability<?> capability;
    private static Method models, selected, load, ready;
    private static boolean failed;

    public static boolean supported() {
        return LoadingModList.get().getMods().stream()
                .anyMatch(
                        m ->
                                m.getModId().equals("yes_steve_model")
                                        && m.getVersion()
                                                .toString()
                                                .equals("2.6.5-forge+mc1.20.1"));
    }

    private static void initialize() throws ReflectiveOperationException {
        if (models != null) {
            return;
        }
        var provider = Class.forName(PREFIX + "O0OooOo0oOOoOoOoOooO000o");
        capability = (Capability<?>) provider.getField("Oo0Oo0o00O00Oo0OOoOOoooo").get(null);
        models =
                Class.forName(PREFIX + "o0OooO00ooo0OO000O0OoOoO")
                        .getMethod("o0OOooo0o0OO00OoOOOo0o0O");
        var living = Class.forName(PREFIX + "oOo00OoOO00O000ooO0Oo00o");
        selected = living.getMethod("ooooO0o00oO0Oo0OOo0O0O0o");
        load = living.getDeclaredMethod("o0OOooo0o0OO00OoOOOo0o0O", String.class);
        load.setAccessible(true);
        ready =
                Class.forName(PREFIX + "o0000OoOooO0oo0o0oooo0Oo")
                        .getDeclaredMethod("OO0OO0000O0ooO0oO000Oo0O");
        ready.setAccessible(true);
    }

    /**
     * 实时库包含文件夹、压缩包和加密的.ysm文件。不做文件系统猜测。
     * <p>The live library includes folders, archives and encrypted .ysm files. No filesystem guessing.</p>
     */
    public static List<String> modelIds() {
        if (!supported() || failed) {
            return List.of();
        }
        try {
            initialize();
            var library = (Map<?, ?>) models.invoke(null);
            return library.keySet().stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(id -> !id.isBlank())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .limit(2048)
                    .toList();
        } catch (ReflectiveOperationException | ClassCastException ex) {
            failure(ex);
            return List.of();
        }
    }

    public static String displayName(String id) {
        try {
            initialize();
            var value = ((Map<?, ?>) models.invoke(null)).get(id);
            if (value != null) {
                return (String)
                        value.getClass()
                                .getMethod("Oo0Oo0o00O00Oo0OOoOOoooo", String.class)
                                .invoke(
                                        value,
                                        id.substring(id.lastIndexOf('/') + 1)
                                                .replaceFirst("\\.ysm$", ""));
            }
        } catch (ReflectiveOperationException ex) {
            failure(ex);
        }
        return id.substring(id.lastIndexOf('/') + 1).replaceFirst("\\.ysm$", "");
    }

    public static String current(Entity actor) {
        if (!supported() || failed) {
            return "";
        }
        try {
            initialize();
            var model = actor.getCapability(capability).resolve().orElse(null);
            return model == null ? "" : (String) selected.invoke(model);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            failure(ex);
            return "";
        }
    }

    /**
     * 临时客户端渲染器选择。不发送命令，也不更改玩家的服务器模型。
     * <p>Temporary client renderer choice. Does not send a command or change the player's server model.</p>
     */
    public static boolean apply(Entity actor, String id) {
        if (!supported() || failed) {
            return false;
        }
        try {
            initialize();
            var model = actor.getCapability(capability).resolve().orElse(null);
            if (model == null) {
                return false;
            }
            synchronized (YsmAnimationAdapter.class) {
                if (!Objects.equals(selected.invoke(model), id)) {
                    YsmAnimationAdapter.releaseActor(actor.getUUID());
                    load.invoke(model, id);
                    ready.invoke(model);
                }
            }
            return true;
        } catch (ReflectiveOperationException | ClassCastException ex) {
            failure(ex);
            return false;
        }
    }

    private static void failure(Exception ex) {
        failed = true;
        SteveDanceMod.LOGGER.error("YSM model library interface unavailable", ex);
    }
}
