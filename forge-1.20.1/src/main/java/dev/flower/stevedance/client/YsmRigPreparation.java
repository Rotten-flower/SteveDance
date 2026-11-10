package dev.flower.stevedance.client;

import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.AutomaticYsmBodyProfiles;
import dev.flower.stevedance.core.FingerRetargeter;
import dev.flower.stevedance.core.PreparedYsmRig;
import dev.flower.stevedance.core.YsmModelProfiles;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 所选模型准备；原生工作保留在渲染线程上，磁盘工作在其外。
 * <p>Selected-model preparation; native work stays on the render thread, disk work off it.</p>
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
        modid = "stevedance",
        value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class YsmRigPreparation implements AutoCloseable {
    private static final String PREFIX = "com.elfmcys.yesstevemodel.";
    private static final Map<String, YsmModelProfiles.Profile> PREPARED = new ConcurrentHashMap<>();
    private static final Map<String, Object> SOURCES = new ConcurrentHashMap<>();
    private static Method library;
    private static final Set<String> REQUESTS = new LinkedHashSet<>();
    private static final Set<String> ATTEMPTED = new HashSet<>();
    private static YsmRigPreparation playbackPreparation;

    /**
     * 已保存的表演者选择也在重启游戏后的首次回放时准备。
     * <p>Saved performer choices also prepare on first playback after restarting the game.</p>
     */
    public static void request(String id) {
        synchronized (REQUESTS) {
            if (!PREPARED.containsKey(id) && !ATTEMPTED.contains(id) && REQUESTS.size() < 16) {
                REQUESTS.add(id);
            }
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void clientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            if (playbackPreparation != null) {
                playbackPreparation.close();
            }
            playbackPreparation = null;
            synchronized (REQUESTS) {
                REQUESTS.clear();
                ATTEMPTED.clear();
            }
            PREPARED.clear();
            SOURCES.clear();
            return;
        }
        String selected = null;
        if (playbackPreparation == null) {
            synchronized (REQUESTS) {
                if (!REQUESTS.isEmpty()) {
                    selected = REQUESTS.iterator().next();
                    REQUESTS.remove(selected);
                    ATTEMPTED.add(selected);
                }
            }
        }
        if (selected != null) {
            playbackPreparation = new YsmRigPreparation();
            playbackPreparation.tick(selected);
        } else if (playbackPreparation != null) {
            playbackPreparation.tick(playbackPreparation.id);
            if (Set.of("ready", "review", "error", "unavailable")
                    .contains(playbackPreparation.state)) {
                playbackPreparation.close();
                playbackPreparation = null;
            }
        }
    }

    private String id = "", state = "idle", signature = "";
    private Object source, dummy, data, poseBuffer, locationBuffer;
    private final List<Object> bones = new ArrayList<>();
    private final List<PreparedYsmRig.Joint> reference = new ArrayList<>();
    private final List<BitSet> influence = new ArrayList<>();
    private Method draw, release, tx, ty, tz, wx, wy, wz;
    private Object vertices, pose;
    private Matrix4f identity;
    private double[][] baseline;
    private int next, fingers;
    private long maxStepNanos;
    private boolean fromCache, started;
    private CompletableFuture<JsonObject> loading;
    private CompletableFuture<PreparedYsmRig.Result> compiling;
    private YsmModelProfiles.Profile seed;

    public static Path directory() {
        return Minecraft.getInstance()
                .gameDirectory
                .toPath()
                .resolve("config/stevedance/retarget/prepared");
    }

    public static YsmModelProfiles.Profile profile(String id) {
        return PREPARED.get(id);
    }

    public static boolean hasProfile(String id) {
        return PREPARED.containsKey(id);
    }

    /**
     * 重新加载的原生模型可复用其ID，但参考关节可能改变。
     * <p>A reloaded native model can reuse its ID but change reference joints.</p>
     */
    public static void discard(String id) {
        PREPARED.remove(id);
        SOURCES.remove(id);
        synchronized (REQUESTS) {
            ATTEMPTED.remove(id);
        }
    }

    private static Class<?> c(String n) throws ClassNotFoundException {
        return Class.forName(PREFIX + n);
    }

    private static Object invoke(Object o, String n, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        Method m = o.getClass().getMethod(n, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    private static Object invoke(Object o, String n) throws ReflectiveOperationException {
        return invoke(o, n, new Class<?>[0]);
    }

    private static Map<?, ?> library() throws ReflectiveOperationException {
        if (library == null) {
            library = c("o0OooO00ooo0OO000O0OoOoO").getMethod("o0OOooo0o0OO00OoOOOo0o0O");
        }
        return (Map<?, ?>) library.invoke(null);
    }

    /**
     * 显式刷新也允许读取用户修改后的角色映射，无需更换JAR。
     * <p>Explicit refresh also permits reading the user's edited roles without a new JAR.</p>
     */
    public void refresh() {
        String selected = id;
        close();
        id = "";
        PREPARED.remove(selected);
        SOURCES.remove(selected);
        synchronized (REQUESTS) {
            ATTEMPTED.remove(selected);
        }
        YsmAnimationAdapter.invalidatePrepared(selected);
    }

    public Component status() {
        return switch (state) {
            case "measure" ->
                    Component.translatable("ui.stevedance.rig.measuring", next, bones.size());
            case "ready" ->
                    Component.translatable(
                            fromCache ? "ui.stevedance.rig.cached" : "ui.stevedance.rig.ready",
                            fingers);
            default -> Component.translatable("ui.stevedance.rig." + state);
        };
    }

    public String state() {
        return state;
    }

    public String signature() {
        return signature;
    }

    public long maxStepNanos() {
        return maxStepNanos;
    }

    public int nativeBoneCount() {
        return reference.size();
    }

    /**
     * 仅对当前选中的模型库条目调用，不用于预览网格。
     * <p>Called only for the single selected library entry, never the preview grid.</p>
     */
    public void tick(String selected) {
        try {
            Object asset = library().get(selected);
            if (!selected.equals(id) || asset != source) {
                close();
                id = selected;
                source = asset;
                started = false;
                state = "idle";
                maxStepNanos = 0;
                if (asset == null) {
                    state = "unavailable";
                    return;
                }
                if (SOURCES.get(id) == asset && PREPARED.containsKey(id)) {
                    state = "ready";
                    fromCache = true;
                    fingers =
                            (int)
                                    PREPARED.get(id).definition().nativeNames().keySet().stream()
                                            .filter(FingerRetargeter::isFinger)
                                            .count();
                    started = true;
                    return;
                }
                PREPARED.remove(id);
                SOURCES.remove(id);
            }
            if (!started) {
                started = true;
                begin();
                return;
            }
            if (loading != null && loading.isDone()) {
                JsonObject cached = loading.join();
                loading = null;
                if (cached != null) {
                    try {
                        List<PreparedYsmRig.Joint> rig = PreparedYsmRig.joints(cached, signature);
                        if (rig.size() != reference.size()) {
                            throw new IllegalArgumentException("Native bone count changed");
                        }
                        for (int i = 0; i < rig.size(); i++) {
                            if (!rig.get(i).name().equals(reference.get(i).name())
                                    || !rig.get(i).pivot().equals(reference.get(i).pivot())
                                    || !rig.get(i).rotation().equals(reference.get(i).rotation())) {
                                throw new IllegalArgumentException(
                                        "Cached native reference changed");
                            }
                        }
                        compile(rig, true);
                        return;
                    } catch (RuntimeException e) {
                        SteveDanceMod.LOGGER.warn(
                                "YSM skeleton cache {} will be measured again: {}",
                                id,
                                e.getMessage());
                    }
                }
                state = "measure";
            }
            if (state.equals("measure")) {
                long until = System.nanoTime() + 4_000_000L;
                do {
                    long start = System.nanoTime();
                    measure();
                    maxStepNanos = Math.max(maxStepNanos, System.nanoTime() - start);
                } while (state.equals("measure") && System.nanoTime() < until);
            }
            if (compiling != null && compiling.isDone()) {
                var result = compiling.join();
                compiling = null;
                fromCache = result.cached();
                fingers = result.fingers();
                if (result.profile() != null) {
                    PREPARED.put(id, result.profile());
                    SOURCES.put(id, source);
                    YsmAnimationAdapter.invalidatePrepared(id);
                    state = "ready";
                } else {
                    state = "review";
                    SteveDanceMod.LOGGER.info(
                            "YSM skeleton {} needs roles {} in {}",
                            id,
                            result.missing(),
                            directory().resolve(signature + ".mapping.json"));
                }
                dispose();
            }
        } catch (ReflectiveOperationException | RuntimeException | java.io.IOException e) {
            state = "error";
            loading = null;
            compiling = null;
            dispose();
            SteveDanceMod.LOGGER.error(
                    "Cannot prepare YSM skeleton {}. Review local mapping files in {}",
                    id,
                    directory(),
                    e);
        }
    }

    private void begin() throws ReflectiveOperationException, java.io.IOException {
        YsmAnimationAdapter.prepare();
        seed = YsmAnimationAdapter.knownProfile(id);
        dummy = c("O00000oOo0o0o00OOoo0O0oo").getConstructor().newInstance();
        Object def = invoke(source, "Oo0Oo0o00O00Oo0OOoOOoooo");
        invoke(
                dummy,
                "Oo0Oo0o00O00Oo0OOoOOoooo",
                new Class[] {String.class, String.class},
                id,
                invoke(def, "Oo00o0OooOOo0ooOoo0oO0o0"));
        Method ready = c("o0000OoOooO0oo0o0oooo0Oo").getDeclaredMethod("OO0OO0000O0ooO0oO000Oo0O");
        ready.setAccessible(true);
        ready.invoke(dummy);
        release = dummy.getClass().getMethod("Ooo0O0000OO0OOO0o0Oo0oOO");
        Object geo = invoke(dummy, "OOOoOO000000o0o0oOooo0o0");
        var map = (Map<?, ?>) invoke(geo, "O00OOOooOoooOoo0o0o0oO0O");
        if (map.isEmpty() || map.size() > 4096) {
            throw new IllegalArgumentException("Native skeleton bone budget");
        }
        Map<String, Object> sorted = new TreeMap<>();
        for (Object b : map.values()) {
            sorted.put((String) invoke(b, "oOOo0Ooo0oOoo0O0OOOOo0oo"), b);
        }
        String[] writes = {
            "Oo0Oo0o00O00Oo0OOoOOoooo",
            "o0OOooo0o0OO00OoOOOo0o0O",
            "O00OOOooOoooOoo0o0o0oO0O",
            "oOOOo0OOO0ooooo0O00OO0o0",
            "OOOOo0O0oO0OOo0O0O0Oo0O0",
            "Ooooo0oooO0oooOOOoO0000O",
            "oo0OoO00oOoo000O0000o0oo",
            "oooooooOOoOOoO00OooOo00O",
            "Oo00o0OooOOo0ooOoo0oO0o0"
        };
        Class<?> bone = c("Oo0o00oOOo0OO000000O0oO0");
        tx = bone.getMethod(writes[3], float.class);
        ty = bone.getMethod(writes[4], float.class);
        tz = bone.getMethod(writes[5], float.class);
        wx = bone.getMethod("Ooo0O0000OO0OOO0o0Oo0oOO");
        wy = bone.getMethod("o0o0Oooo00OoOoOOOooo0000");
        wz = bone.getMethod("ooooO0o00oO0Oo0OOo0O0O0o");
        for (var e : sorted.entrySet()) {
            Object b = e.getValue();
            bones.add(b);
            Vector3f rot = (Vector3f) invoke(b, "OO0ooO00OoO00o0OO0OOooO0");
            reference.add(
                    new PreparedYsmRig.Joint(
                            e.getKey(),
                            null,
                            new Vec(
                                    -((Number) invoke(b, "o0OOO0o0o0OOo000oO00o00O")).doubleValue(),
                                    ((Number) invoke(b, "O0OooOo0oOOoOoOoOooO000o")).doubleValue(),
                                    ((Number) invoke(b, "ooOO000o0O0OOOoO0Oo0o0Oo")).doubleValue()),
                            new Vec(rot.x, -rot.y, -rot.z)));
            for (int a = 0; a < 9; a++) {
                bone.getMethod(writes[a], float.class).invoke(b, a < 6 ? 0f : 1f);
            }
            bone.getMethod("Oo0Oo0o00O00Oo0OOoOOoooo", boolean.class, boolean.class)
                    .invoke(b, false, false);
            // 中文：标志11启用原生位置输出，包括没有网格的控制骨骼。
            // EN: Flag 11 enables native location output, including meshless controller bones.
            bone.getMethod("o0OOooo0o0OO00OoOOOo0o0O", boolean.class).invoke(b, true);
        }
        data = invoke(geo, "oOOOo0OOO0ooooo0O00OO0o0");
        poseBuffer = invoke(geo, "Oo0Oo0o00O00Oo0OOoOOoooo");
        locationBuffer = invoke(geo, "o0OOooo0o0OO00OoOOOo0o0O");
        vertices =
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class[] {VertexConsumer.class},
                        (proxy, m, args) -> m.getReturnType() == void.class ? null : proxy);
        pose = new PoseStack().last();
        identity = new Matrix4f();
        draw =
                c("ooOOo000OOO0ooO0oo0ooooO")
                        .getDeclaredMethod(
                                "Oo0Oo0o00O00Oo0OOoOOoooo",
                                VertexConsumer.class,
                                PoseStack.Pose.class,
                                Matrix4f.class,
                                boolean.class,
                                c("OOoOoooOOooO0o0000o0O0o0"),
                                float[].class,
                                float[].class,
                                int.class,
                                int.class,
                                int.class,
                                int.class,
                                float.class,
                                float.class,
                                float.class,
                                float.class,
                                boolean.class);
        draw.setAccessible(true);
        draw();
        baseline = points();
        // 中文：两次原生绘制同时为父子图、名称和参考枢轴生成指纹。
        // EN: Two native draws fingerprint the graph as well as names/reference pivots.
        // 中文：因此只修改父节点也会使缓存失效，即使所有关节名称保持
        // EN: A parent-only edit therefore invalidates the cache even when every joint name stays
        // the
        // 中文：不变。
        // EN: same.
        Random codes = new Random(0x5354455645524947L);
        for (Object b : bones) {
            tx.invoke(b, .25f + codes.nextFloat());
            ty.invoke(b, .25f + codes.nextFloat());
            tz.invoke(b, .25f + codes.nextFloat());
        }
        draw();
        double[][] coded = points();
        StringBuilder key = new StringBuilder("ysm-2.6.5/rig-" + PreparedYsmRig.VERSION);
        for (int i = 0; i < bones.size(); i++) {
            var j = reference.get(i);
            key.append('|')
                    .append(j.name())
                    .append(':')
                    .append(j.pivot())
                    .append(':')
                    .append(j.rotation());
            for (int a = 0; a < 3; a++) {
                key.append(':').append(Math.round((coded[i][a] - baseline[i][a]) * 1000));
            }
        }
        signature = PreparedYsmRig.digest(key.toString());
        for (Object b : bones) {
            tx.invoke(b, 0f);
            ty.invoke(b, 0f);
            tz.invoke(b, 0f);
        }
        if (seed != null) {
            Map<String, YsmModelProfiles.NativeBone> actual = new HashMap<>();
            reference.forEach(
                    j ->
                            actual.put(
                                    j.name(),
                                    new YsmModelProfiles.NativeBone(
                                            j.pivot(), Quat.euler(j.rotation()))));
            if (!YsmModelProfiles.matches(seed, actual)) {
                seed = null;
            }
        }
        if (seed == null) {
            Map<String, AutomaticYsmBodyProfiles.Joint> joints = new HashMap<>();
            reference.forEach(
                    j ->
                            joints.put(
                                    j.name(),
                                    new AutomaticYsmBodyProfiles.Joint(j.pivot(), j.rotation())));
            seed = YsmAnimationAdapter.inferProfile(id, joints);
        }
        Path cache = directory().resolve(signature + ".skeleton.json");
        state = "loading";
        loading =
                CompletableFuture.supplyAsync(
                        () -> {
                            if (!Files.isRegularFile(cache)) {
                                return null;
                            }
                            try {
                                return PreparedYsmRig.read(cache);
                            } catch (Exception e) {
                                SteveDanceMod.LOGGER.warn(
                                        "Cannot read YSM skeleton cache {}: {}",
                                        cache,
                                        e.toString());
                                return null;
                            }
                        });
    }

    private void draw() throws ReflectiveOperationException {
        draw.invoke(
                null,
                vertices,
                pose,
                identity,
                false,
                data,
                poseBuffer,
                locationBuffer,
                0,
                0,
                15728880,
                655360,
                1f,
                1f,
                1f,
                1f,
                true);
    }

    private double[][] points() throws ReflectiveOperationException {
        double[][] p = new double[bones.size()][3];
        for (int i = 0; i < bones.size(); i++) {
            p[i][0] = ((Number) wx.invoke(bones.get(i))).doubleValue();
            p[i][1] = ((Number) wy.invoke(bones.get(i))).doubleValue();
            p[i][2] = ((Number) wz.invoke(bones.get(i))).doubleValue();
            for (double v : p[i]) {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("Invalid native location");
                }
            }
        }
        return p;
    }

    private void measure() throws ReflectiveOperationException {
        Object b = bones.get(next);
        tx.invoke(b, 4f);
        draw();
        double[][] p = points();
        tx.invoke(b, 0f);
        BitSet set = new BitSet(bones.size());
        for (int i = 0; i < bones.size(); i++) {
            double sum = 0;
            for (int a = 0; a < 3; a++) {
                double d = p[i][a] - baseline[i][a];
                sum += d * d;
            }
            if (sum > 1) {
                set.set(i);
            }
        }
        influence.add(set);
        next++;
        if (next == bones.size()) {
            int[] parent = PreparedYsmRig.parents(influence);
            List<PreparedYsmRig.Joint> rig = new ArrayList<>();
            for (int i = 0; i < reference.size(); i++) {
                var j = reference.get(i);
                rig.add(
                        new PreparedYsmRig.Joint(
                                j.name(),
                                parent[i] < 0 ? null : reference.get(parent[i]).name(),
                                j.pivot(),
                                j.rotation()));
            }
            compile(List.copyOf(rig), false);
        }
    }

    private void compile(List<PreparedYsmRig.Joint> rig, boolean cached) {
        state = "saving";
        String selected = id, key = signature;
        var metadata = seed;
        Path dir = directory();
        compiling =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                if (!cached) {
                                    PreparedYsmRig.write(
                                            dir.resolve(key + ".skeleton.json"),
                                            PreparedYsmRig.snapshot(key, rig));
                                }
                                Path mapping = dir.resolve(key + ".mapping.json");
                                JsonObject doc;
                                if (Files.exists(mapping)) {
                                    doc = PreparedYsmRig.read(mapping);
                                } else {
                                    doc = PreparedYsmRig.mapping(selected, key, rig, metadata);
                                    PreparedYsmRig.write(mapping, doc);
                                }
                                var result =
                                        PreparedYsmRig.compile(
                                                selected, key, rig, doc, metadata, cached);
                                JsonObject index = new JsonObject();
                                index.addProperty("schema", 1);
                                index.addProperty("model", selected);
                                index.addProperty("signature", key);
                                index.addProperty("mapping", mapping.getFileName().toString());
                                index.addProperty("bodyReady", result.profile() != null);
                                index.addProperty("fingerJoints", result.fingers());
                                JsonArray missing = new JsonArray();
                                result.missing().forEach(missing::add);
                                index.add("missingBodyRoles", missing);
                                JsonArray pending = new JsonArray();
                                Set<String> mapped = new HashSet<>();
                                doc.getAsJsonObject("roles")
                                        .entrySet()
                                        .forEach(e -> mapped.add(e.getValue().getAsString()));
                                Map<String, PreparedYsmRig.Joint> nodes = new HashMap<>();
                                rig.forEach(j -> nodes.put(j.name(), j));
                                Set<String> hands = new HashSet<>();
                                for (String role : List.of("LeftHand", "RightHand")) {
                                    if (doc.getAsJsonObject("roles").has(role)) {
                                        hands.add(
                                                doc.getAsJsonObject("roles")
                                                        .get(role)
                                                        .getAsString());
                                    }
                                }
                                for (var j : rig) {
                                    if (!mapped.contains(j.name())) {
                                        String n = j.parent();
                                        while (n != null && !hands.contains(n)) {
                                            n = nodes.get(n).parent();
                                        }
                                        if (n != null) {
                                            pending.add(j.name());
                                        }
                                    }
                                }
                                index.add("unmappedHandDescendants", pending);
                                PreparedYsmRig.write(
                                        dir.resolve(
                                                PreparedYsmRig.digest(selected) + ".status.json"),
                                        index);
                                return result;
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        });
    }

    private void dispose() {
        if (dummy != null && release != null) {
            try {
                release.invoke(dummy);
            } catch (ReflectiveOperationException e) {
                SteveDanceMod.LOGGER.warn("Cannot release YSM skeleton dummy", e);
            }
        }
        dummy = null;
        vertices = null;
        data = null;
        poseBuffer = null;
        locationBuffer = null;
        baseline = null;
        bones.clear();
        influence.clear();
    }

    @Override
    public void close() {
        dispose();
        loading = null;
        compiling = null;
        reference.clear();
        next = 0;
        started = false;
    }
}
