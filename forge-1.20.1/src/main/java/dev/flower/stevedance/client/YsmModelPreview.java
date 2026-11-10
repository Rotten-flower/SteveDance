package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.flower.stevedance.SteveDanceMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import org.joml.Vector3f;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 使用YSM 2.6.5的选择器虚拟体、空闲控制器和GUI渲染器，无需玩家能力。
 * <p>Uses YSM 2.6.5's selector dummy, idle controller and GUI renderer without player capabilities.</p>
 */
public final class YsmModelPreview implements AutoCloseable {
    private static final String PREFIX = "com.elfmcys.yesstevemodel.";
    private static Constructor<?> constructor;
    private static Method library,
            initialize,
            definition,
            defaultTexture,
            controller,
            animationProfile,
            idleProfile,
            idleAnimation,
            setIdle,
            renderer,
            draw,
            release;
    private static boolean failed;
    private static final Set<Object> OWNED =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final Map<Object, List<Object>> DECORATIONS = new WeakHashMap<>();
    private static Field controllerModel;
    private static Method geometry, bones, boneName, scaleX, scaleY, scaleZ;

    private record Preview(Object model, Object source) {}

    private final Map<String, Preview> previews = new LinkedHashMap<>(16, .75f, true);

    private static void initialize() throws ReflectiveOperationException {
        if (constructor != null) {
            return;
        }
        var preview = Class.forName(PREFIX + "O00000oOo0o0o00OOoo0O0oo");
        var asset = Class.forName(PREFIX + "oOo0oO0OOo0ooOoo0oOo0oOo");
        var base = Class.forName(PREFIX + "o0O0oOooOo0OoOo0oOo00O00");
        var render = Class.forName(PREFIX + "OOoo0o0oO000ooO0Oo00OoOo");
        library =
                Class.forName(PREFIX + "o0OooO00ooo0OO000O0OoOoO")
                        .getMethod("o0OOooo0o0OO00OoOOOo0o0O");
        initialize = preview.getMethod("Oo0Oo0o00O00Oo0OOoOOoooo", String.class, String.class);
        definition = asset.getMethod("Oo0Oo0o00O00Oo0OOoOOoooo");
        defaultTexture =
                Class.forName(PREFIX + "Oo0o0o0o0OOo0000ooo00o00")
                        .getMethod("Oo00o0OooOOo0ooOoo0oO0o0");
        controller = preview.getMethod("Oo0Oo0o00O00Oo0OOoOOoooo");
        animationProfile = asset.getMethod("Ooooo0oooO0oooOOOoO0000O");
        idleProfile =
                Class.forName(PREFIX + "o0OoOo0OOOo0oOo0oOO0O0oO")
                        .getMethod("o0OOooo0o0OO00OoOOOo0o0O");
        idleAnimation =
                Class.forName(PREFIX + "oO00O0OOOoO0000oOO0OOO00")
                        .getMethod("oOOOo0OOO0ooooo0O00OO0o0");
        setIdle =
                Class.forName(PREFIX + "oO0Ooo00oOOO00O0OO00o0o0")
                        .getMethod("Oo0Oo0o00O00Oo0OOoOOoooo", String.class);
        renderer =
                Class.forName(PREFIX + "OOoO00ooO00OOO00O0o0000O")
                        .getMethod("Oo0Oo0o00O00Oo0OOoOOoooo");
        draw =
                Class.forName(PREFIX + "OoO00Oo00Ooo0OoOoo00o000")
                        .getMethod(
                                "Oo0Oo0o00O00Oo0OOoOOoooo",
                                float.class,
                                float.class,
                                float.class,
                                float.class,
                                base,
                                render,
                                boolean.class,
                                boolean.class);
        release = preview.getMethod("Ooo0O0000OO0OOO0o0Oo0oOO");
        constructor = preview.getConstructor();
    }

    boolean render(GuiGraphics g, String id, int x, int bottom, int size) {
        if (failed || !YsmModelLibraryBridge.supported()) {
            return false;
        }
        try {
            initialize();
            Object asset = ((Map<?, ?>) library.invoke(null)).get(id);
            if (asset == null) {
                return false;
            }
            var preview = previews.get(id);
            if (preview == null || preview.source() != asset) {
                if (preview != null) {
                    OWNED.remove(preview.model());
                    release.invoke(preview.model());
                }
                Object model = constructor.newInstance();
                initialize.invoke(model, id, defaultTexture.invoke(definition.invoke(asset)));
                setIdle.invoke(
                        controller.invoke(model),
                        idleAnimation.invoke(idleProfile.invoke(animationProfile.invoke(asset))));
                OWNED.add(model);
                preview = new Preview(model, asset);
                previews.put(id, preview);
                while (previews.size() > 8) {
                    var entries = previews.entrySet().iterator();
                    var first = entries.next();
                    OWNED.remove(first.getValue().model());
                    release.invoke(first.getValue().model());
                    entries.remove();
                }
            }
            // 中文：YSM原生GUI辅助器使用GUI坐标，而非GuiGraphics当前姿态。
            // EN: YSM's native GUI helper consumes GUI coordinates, not GuiGraphics' current pose.
            // 中文：原生选择器模型以中心为基准，而Minecraft物品栏模型使用脚部
            // EN: Native selector models are centred, while Minecraft inventory models use a foot
            // 中文：锚点。
            // EN: anchor.
            var matrix = g.pose().last().pose();
            var point = matrix.transformPosition(new Vector3f(x, bottom - size * .9f, 0));
            float scaledSize = size * .5f * (float) Math.hypot(matrix.m00(), matrix.m01());
            g.flush();
            draw.invoke(
                    null,
                    point.x,
                    point.y,
                    scaledSize,
                    Minecraft.getInstance().getFrameTime(),
                    preview.model(),
                    renderer.invoke(null),
                    false,
                    true);
            return true;
        } catch (ReflectiveOperationException | ClassCastException ex) {
            failed = true;
            SteveDanceMod.LOGGER.error("YSM selector preview interface unavailable", ex);
            return false;
        } finally {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }

    /**
     * 仅在此屏幕的私有原生预览实例上隐藏创作的选择器场景。
     * <p>Hide authored selector scenery only on this screen's private native preview instances.</p>
     */
    public static synchronized void applyController(Object animationController) {
        if (OWNED.isEmpty() || failed) {
            return;
        }
        try {
            if (controllerModel == null) {
                controllerModel =
                        animationController.getClass().getDeclaredField("o0OOooo0o0OO00OoOOOo0o0O");
                controllerModel.setAccessible(true);
            }
            Object model = controllerModel.get(animationController);
            if (!OWNED.contains(model)) {
                return;
            }
            if (geometry == null) {
                geometry =
                        Class.forName(PREFIX + "o0000OoOooO0oo0o0oooo0Oo")
                                .getMethod("OOOoOO000000o0o0oOooo0o0");
                bones =
                        Class.forName(PREFIX + "OOOO0O0O000O000000oOOO0o")
                                .getMethod("O00OOOooOoooOoo0o0o0oO0O");
                var bone = Class.forName(PREFIX + "Oo0o00oOOo0OO000000O0oO0");
                boneName = bone.getMethod("oOOo0Ooo0oOoo0O0OOOOo0oo");
                scaleX = bone.getMethod("oo0OoO00oOoo000O0000o0oo", float.class);
                scaleY = bone.getMethod("oooooooOOoOOoO00OooOo00O", float.class);
                scaleZ = bone.getMethod("Oo00o0OooOOo0ooOoo0oO0o0", float.class);
            }
            Object mesh = geometry.invoke(model);
            if (mesh == null) {
                return;
            }
            var decoration = DECORATIONS.get(mesh);
            if (decoration == null) {
                decoration = new ArrayList<>();
                for (Object bone : ((Map<?, ?>) bones.invoke(mesh)).values()) {
                    String name = ((String) boneName.invoke(bone)).toLowerCase(Locale.ROOT);
                    // 中文：GuiRoot包含实际身体，必须保持可见。
                    // EN: GuiRoot contains the actual body and must remain visible.
                    if (name.equals("gui")
                            || name.equals("backgrounds")
                            || name.matches("background\\d*")) {
                        decoration.add(bone);
                    }
                }
                DECORATIONS.put(mesh, decoration);
            }
            for (Object bone : decoration) {
                scaleX.invoke(bone, 0f);
                scaleY.invoke(bone, 0f);
                scaleZ.invoke(bone, 0f);
            }
        } catch (ReflectiveOperationException | ClassCastException ex) {
            failed = true;
            SteveDanceMod.LOGGER.error("YSM selector decoration interface unavailable", ex);
        }
    }

    @Override
    public void close() {
        if (release != null) {
            for (var preview : previews.values()) {
                try {
                    OWNED.remove(preview.model());
                    release.invoke(preview.model());
                } catch (ReflectiveOperationException ex) {
                    SteveDanceMod.LOGGER.warn("Cannot release YSM selector preview", ex);
                }
            }
        }
        previews.clear();
    }
}
