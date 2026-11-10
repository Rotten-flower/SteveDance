package dev.flower.stevedance.content;

import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.client.ClientPlaybackRuntime;
import dev.flower.stevedance.client.PerformerModelSelection;
import dev.flower.stevedance.client.PerformerPropBindings;
import dev.flower.stevedance.client.PvPlaybackController;
import dev.flower.stevedance.client.YsmPropAdapter;
import dev.flower.stevedance.core.PerformerModelChoice;
import dev.flower.stevedance.core.PropBinding;
import dev.flower.stevedance.core.PvBodyMotion;
import dev.flower.stevedance.core.SkeletonRetargeter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 复用已安装女仆模型，每次绘制后恢复共享骨骼。
 * <p>Reuses installed models and restores shared bones after each draw.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class MaidModels {
    public static int drawnFrames;
    public static String lastModel = "";
    private static final Map<String, String> DEFAULT =
            Map.of(
                    "UpperBody",
                    "body",
                    "Head",
                    "head",
                    "LeftArm",
                    "armLeft",
                    "RightArm",
                    "armRight",
                    "LeftLeg",
                    "legLeft",
                    "RightLeg",
                    "legRight");
    private static final String[] FIELDS = {
        "x", "y", "z", "xRot", "yRot", "zRot", "offsetX", "offsetY", "offsetZ", "xScale", "yScale",
        "zScale"
    };
    private static Object library;
    private static Method getModel, getInfo;
    private static boolean attempted, mappingsLoaded;
    private static java.io.IOException mappingError;
    private static final Set<String> WARNED = new HashSet<>();
    private static final Map<String, Map<String, String>> MAPPINGS = new HashMap<>();
    private static final Map<Object, Vec> HAND_GRIPS = new WeakHashMap<>();

    private record Saved(
            Object part, float[] values, org.joml.Quaternionf quaternion, boolean visible) {}

    private static void initialize() throws ReflectiveOperationException {
        if (attempted) {
            return;
        }
        attempted = true;
        if (!net.minecraftforge.fml.ModList.get().isLoaded("touhou_little_maid")) {
            return;
        }
        library =
                Class.forName(
                                "com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader")
                        .getField("MAID_MODELS")
                        .get(null);
        getModel = library.getClass().getMethod("getModel", String.class);
        getInfo = library.getClass().getMethod("getInfo", String.class);
    }

    private static Object model(String id) throws ReflectiveOperationException {
        return ((Optional<?>) getModel.invoke(library, id)).orElse(null);
    }

    private static Object info(String id) throws ReflectiveOperationException {
        return ((Optional<?>) getInfo.invoke(library, id)).orElse(null);
    }

    private static void warn(String id, Exception ex) {
        if (WARNED.add(id)) {
            SteveDanceMod.LOGGER.warn("Maid model {} unavailable", id, ex);
        }
    }

    public static List<String> ids() {
        try {
            initialize();
            if (library == null) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (Object value :
                    (Set<?>) library.getClass().getMethod("getModelIdSet").invoke(library)) {
                String id = (String) value;
                Object data = info(id);
                if (data != null
                        && (boolean) data.getClass().getMethod("isGeckoModel").invoke(data)) {
                    if (GeckoMaidModels.available(data)) {
                        result.add(id);
                    }
                } else if (data != null && model(id) != null) {
                    Map<?, ?> parts =
                            (Map<?, ?>)
                                    model(id).getClass().getMethod("getModelMap").invoke(model(id));
                    if (nativeRoles(id, parts).keySet().containsAll(List.of("UpperBody", "Head"))) {
                        result.add(id);
                    }
                }
            }
            List<String> ordered = new ArrayList<>();
            Set<String> valid = new HashSet<>(result);
            for (Object pack :
                    (List<?>) library.getClass().getMethod("getPackList").invoke(library)) {
                for (Object data :
                        (List<?>) pack.getClass().getMethod("getModelList").invoke(pack)) {
                    String id = data.getClass().getMethod("getModelId").invoke(data).toString();
                    if (valid.remove(id)) {
                        ordered.add(id);
                    }
                }
            }
            ordered.addAll(valid.stream().sorted().toList());
            return List.copyOf(ordered);
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException ex) {
            warn("library", ex);
            return List.of();
        }
    }

    /** 原生模型名称，支持模型包语言键。 / Native pack name and translations. */
    public static net.minecraft.network.chat.Component displayName(String id) {
        try {
            initialize();
            Object data = library == null ? null : info(id);
            if (data != null) {
                String name = (String) data.getClass().getMethod("getName").invoke(data);
                if (name != null && !name.isBlank()) {
                    return nativeText(name);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            warn(id, ex);
        }
        return net.minecraft.network.chat.Component.literal(id);
    }

    private static net.minecraft.network.chat.Component nativeText(String text)
            throws ReflectiveOperationException {
        return (net.minecraft.network.chat.Component)
                Class.forName("com.github.tartaricacid.touhoulittlemaid.util.ParseI18n")
                        .getMethod("parse", String.class)
                        .invoke(null, text);
    }

    public static net.minecraft.network.chat.Component packName(String id) {
        try {
            initialize();
            if (library != null) {
                for (Object pack :
                        (List<?>) library.getClass().getMethod("getPackList").invoke(library)) {
                    for (Object data :
                            (List<?>) pack.getClass().getMethod("getModelList").invoke(pack)) {
                        if (id.equals(
                                data.getClass().getMethod("getModelId").invoke(data).toString())) {
                            return nativeText(
                                    (String) pack.getClass().getMethod("getPackName").invoke(pack));
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            warn(id, ex);
        }
        return net.minecraft.network.chat.Component.translatable("ui.stevedance.model.category.2");
    }

    public static net.minecraft.world.entity.LivingEntity createPreview(String id) {
        try {
            initialize();
            if (library == null || Minecraft.getInstance().level == null) {
                return null;
            }
            Class<?> type =
                    Class.forName(
                            "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid");
            var entity =
                    (net.minecraft.world.entity.LivingEntity)
                            type.getConstructor(net.minecraft.world.level.Level.class)
                                    .newInstance(Minecraft.getInstance().level);
            Class.forName("com.github.tartaricacid.touhoulittlemaid.util.EntityCacheUtil")
                    .getMethod("clearMaidDataResidue", type, boolean.class)
                    .invoke(null, entity, false);
            type.getMethod("setModelId", String.class).invoke(entity, id);
            type.getMethod("setIsYsmModel", boolean.class).invoke(entity, false);
            entity.setNoGravity(true);
            return entity;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            warn(id, ex);
            return null;
        }
    }

    public static float previewScale(String id) {
        try {
            Object data = info(id);
            return data == null
                    ? 1
                    : ((Number) data.getClass().getMethod("getRenderItemScale").invoke(data))
                            .floatValue();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            warn(id, ex);
            return 1;
        }
    }

    private static Map<String, String> mapping(String id) throws java.io.IOException {
        if (mappingError != null) {
            throw mappingError;
        }
        try {
            return loadMapping(id);
        } catch (java.io.IOException | RuntimeException ex) {
            mappingError = new java.io.IOException("Invalid maid-bones.json", ex);
            throw mappingError;
        }
    }

    private static Map<String, String> nativeRoles(String id, Map<?, ?> parts)
            throws java.io.IOException {
        Map<String, String> configured = mapping(id);
        if (MAPPINGS.containsKey(id)) {
            return configured;
        }
        Map<String, String> result = new LinkedHashMap<>();
        configured.forEach(
                (role, name) -> {
                    if (parts.containsKey(name)) {
                        result.put(role, name);
                    }
                });
        if (!result.containsKey("LeftLeg")
                && !result.containsKey("RightLeg")
                && parts.containsKey("leg")) {
            result.put("DownBody", "leg");
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> loadMapping(String id) throws java.io.IOException {
        if (mappingError != null) {
            throw mappingError;
        }
        if (!mappingsLoaded) {
            mappingsLoaded = true;
            Path file =
                    Minecraft.getInstance()
                            .gameDirectory
                            .toPath()
                            .resolve("config/stevedance/maid-bones.json");
            if (Files.isRegularFile(file)) {
                if (Files.size(file) > 262144) {
                    throw new java.io.IOException("Maid mappings exceed budget");
                }
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (json.get("schema").getAsInt() != 1) {
                    throw new java.io.IOException("Unsupported maid mapping schema");
                }
                Map<String, Map<String, String>> pending = new HashMap<>();
                for (var entry : json.getAsJsonObject("models").entrySet()) {
                    Map<String, String> bones = new LinkedHashMap<>();
                    for (var b : entry.getValue().getAsJsonObject().entrySet()) {
                        String name = b.getValue().getAsString();
                        if (!SkeletonRetargeter.HUMANOID.containsKey(b.getKey())
                                || name.isBlank()
                                || name.length() > 128
                                || bones.containsValue(name)) {
                            throw new java.io.IOException("Invalid/duplicate maid bone mapping");
                        }
                        bones.put(b.getKey(), name);
                    }
                    if (pending.size() >= 128) {
                        throw new java.io.IOException("Maid mapping limit");
                    }
                    pending.put(entry.getKey(), Map.copyOf(bones));
                }
                MAPPINGS.putAll(pending);
            }
        }
        return MAPPINGS.getOrDefault(id, DEFAULT);
    }

    private static float value(Object part, String name) throws ReflectiveOperationException {
        return part.getClass().getField(name).getFloat(part);
    }

    private static void value(Object part, String name, float v)
            throws ReflectiveOperationException {
        part.getClass().getField(name).setFloat(part, v);
    }

    private static Object parent(Object part) throws ReflectiveOperationException {
        return part.getClass().getField("parent").get(part);
    }

    private static Quat rest(Object part) throws ReflectiveOperationException {
        return Quat.euler(
                new Vec(value(part, "initRotX"), value(part, "initRotY"), value(part, "initRotZ")));
    }

    private static Quat bind(Object part, Map<Object, Quat> cache)
            throws ReflectiveOperationException {
        Quat q = cache.get(part);
        if (q != null) {
            return q;
        }
        Object p = parent(part);
        q = (p == null ? Quat.ID : bind(p, cache)).mul(rest(part));
        cache.put(part, q);
        return q;
    }

    private static Quat posed(
            Object part,
            Map<Object, String> names,
            Map<Object, Quat> desired,
            Map<Object, Quat> bind)
            throws ReflectiveOperationException {
        if (names.containsKey(part)) {
            return desired.get(part);
        }
        Object p = parent(part);
        return p == null ? bind.get(part) : posed(p, names, desired, bind).mul(rest(part));
    }

    private static Vec position(Object part, Map<Object, Quat> rotations)
            throws ReflectiveOperationException {
        Vec local = new Vec(value(part, "x"), value(part, "y"), value(part, "z"));
        Object p = parent(part);
        return p == null ? local : position(p, rotations).add(rotations.get(p).turn(local));
    }

    private static Quat frame(Quat q) {
        return new Quat(q.x(), -q.y(), -q.z(), q.w());
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void render(RenderPlayerEvent.Pre event) {
        var choice = PerformerModelSelection.renderingChoice(event.getEntity());
        if (choice == null || choice.kind() != PerformerModelChoice.Kind.MAID) {
            return;
        }
        List<Saved> saved = new ArrayList<>();
        var pose = event.getPoseStack();
        boolean pushed = false;
        try {
            initialize();
            if (library == null) {
                return;
            }
            Object data = info(choice.modelId());
            if (data == null) {
                return;
            }
            if ((boolean) data.getClass().getMethod("isGeckoModel").invoke(data)) {
                var snapshot =
                        ClientPlaybackRuntime.MOTION_SNAPSHOTS.get(event.getEntity().getUUID());
                double ticks =
                        snapshot == null
                                ? event.getEntity().tickCount + event.getPartialTick()
                                : snapshot.sampleSeconds() * 20;
                if (GeckoMaidModels.render(
                        event, choice.modelId(), data, eyeChannels(snapshot), ticks)) {
                    drawnFrames++;
                    lastModel = choice.modelId();
                    event.setCanceled(true);
                }
                return;
            }
            Object mesh = model(choice.modelId());
            if (!(mesh instanceof EntityModel<?> entityModel)) {
                return;
            }
            Map<?, ?> parts = (Map<?, ?>) mesh.getClass().getMethod("getModelMap").invoke(mesh);
            Map<String, String> roles;
            try {
                roles = nativeRoles(choice.modelId(), parts);
            } catch (java.io.IOException | RuntimeException error) {
                mappingError = new java.io.IOException("Invalid maid-bones.json", error);
                throw mappingError;
            }
            for (String name : roles.values()) {
                if (!parts.containsKey(name)) {
                    throw new IllegalArgumentException("Missing mapped bone: " + name);
                }
            }
            Map<Object, String> names = new IdentityHashMap<>();
            for (var e : roles.entrySet()) {
                names.put(parts.get(e.getValue()), e.getKey());
            }
            Map<Object, Quat> bind = new IdentityHashMap<>(), desired = new IdentityHashMap<>();
            for (Object part : parts.values()) {
                float[] values = new float[FIELDS.length];
                for (int i = 0; i < FIELDS.length; i++) {
                    values[i] = value(part, FIELDS[i]);
                }
                var q =
                        (org.joml.Quaternionf)
                                part.getClass().getField("additionalQuaternion").get(part);
                saved.add(
                        new Saved(
                                part,
                                values,
                                new org.joml.Quaternionf(q),
                                part.getClass().getField("visible").getBoolean(part)));
                q.identity();
                value(part, "xRot", value(part, "initRotX"));
                value(part, "yRot", value(part, "initRotY"));
                value(part, "zRot", value(part, "initRotZ"));
                for (String n : List.of("offsetX", "offsetY", "offsetZ")) {
                    value(part, n, 0);
                }
                for (String n : List.of("xScale", "yScale", "zScale")) {
                    value(part, n, 1);
                }
                bind(part, bind);
            }
            float scale =
                    ((Number) data.getClass().getMethod("getRenderEntityScale").invoke(data))
                            .floatValue();
            var snapshot = ClientPlaybackRuntime.MOTION_SNAPSHOTS.get(event.getEntity().getUUID());
            if (snapshot != null && snapshot.nativeMotion() != null && roles.containsKey("Head")) {
                Vec head = position(parts.get(roles.get("Head")), bind);
                Vec focus =
                        dev.flower.stevedance.client.CameraCalibration.focusOffset(
                                "maid:" + choice.modelId());
                dev.flower.stevedance.client.CameraCalibration.measure(
                        event.getEntity().getUUID(),
                        "maid:" + choice.modelId(),
                        new Vec(
                                head.x() / 16 * scale,
                                (24 - head.y()) / 16 * scale,
                                -head.z() / 16 * scale),
                        new Vec(focus.x(), -focus.y(), -focus.z()).mul(scale / 16));
            }
            if (!Float.isFinite(scale) || scale <= 0 || scale > 16) {
                throw new IllegalArgumentException("Invalid maid scale");
            }
            poseParts(parts, roles, names, bind, desired, snapshot, scale);
            var texture = (ResourceLocation) data.getClass().getMethod("getTexture").invoke(data);
            pose.pushPose();
            pushed = true;
            pose.mulPose(
                    com.mojang.math.Axis.YP.rotationDegrees(
                            180
                                    - Mth.rotLerp(
                                            event.getPartialTick(),
                                            event.getEntity().yBodyRotO,
                                            event.getEntity().yBodyRot)));
            pose.scale(-scale, -scale, scale);
            pose.translate(0, -1.501, 0);
            // 使用女仆原生可见性，不能用缩放替代，也不能只处理一个模型 ID。
            // 中文：为每个支持的模型驱动原生显隐，并在本次绘制后恢复。
            // EN: Drive native visibility for every supported model, restoring it after this draw.
            Map<String, Float> eyes = null;
            double eyeTicks = event.getEntity().tickCount + event.getPartialTick();
            if (snapshot != null) {
                double seconds = snapshot.sampleSeconds();
                eyeTicks = seconds * 20;
                if (snapshot.faces() != null) {
                    eyes = snapshot.faces().sample(seconds).channels();
                } else if (snapshot.motion() != null) {
                    var source = snapshot.motion();
                    Map<String, Float> channels = new HashMap<>();
                    var morphs =
                            Map.of(
                                    "blink",
                                    List.of("まばたき"),
                                    "smileEyes",
                                    List.of("笑い", "なごみ"),
                                    "winkLeft",
                                    List.of("ウィンク", "ウィンク２"),
                                    "winkRight",
                                    List.of("ウィンク右", "ｳｨﾝｸ２右"));
                    boolean authored = false;
                    for (var channel : morphs.entrySet()) {
                        float weight = 0;
                        for (String name : channel.getValue()) {
                            if (source.morphs().contains(name)) {
                                authored = true;
                                weight += source.morph(name, seconds);
                            }
                        }
                        channels.put(channel.getKey(), weight);
                    }
                    if (authored) {
                        eyes = channels;
                    }
                }
            }
            boolean closed =
                    dev.flower.stevedance.core.MaidBlinkState.closed(
                            eyes, eyeTicks, event.getEntity().getUUID().getLeastSignificantBits());
            for (String name : List.of("blink", "blink2", "hurtBlink")) {
                Object part = parts.get(name);
                if (part != null) {
                    part.getClass()
                            .getField("visible")
                            .setBoolean(part, !name.equals("hurtBlink") && closed);
                }
            }
            if (dev.flower.stevedance.client.StageCamera.hideHead(event.getEntity().getUUID())) {
                Object headPart = parts.get(roles.get("Head"));
                for (var entry : parts.entrySet()) {
                    Object part = entry.getValue();
                    boolean head = part == headPart;
                    Object ancestor = parent(part);
                    while (!head && ancestor != null) {
                        head = ancestor == headPart;
                        ancestor = parent(ancestor);
                    }
                    String name = String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
                    if (head
                            || name.equals("blink")
                            || name.equals("blink2")
                            || name.equals("hurtblink")
                            || name.equals("hat")) {
                        part.getClass().getField("visible").setBoolean(part, false);
                    }
                }
            }
            entityModel.renderToBuffer(
                    pose,
                    dev.flower.stevedance.client.PvCharacterRenderer.compatibleVertices(
                            event.getEntity(),
                            event.getMultiBufferSource()
                                    .getBuffer(
                                            dev.flower.stevedance.client.PvCharacterRenderer
                                                    .forActor(
                                                            event.getEntity(),
                                                            texture,
                                                            RenderType.entityCutoutNoCull(
                                                                    texture)))),
                    event.getPackedLight(),
                    OverlayTexture.NO_OVERLAY,
                    1,
                    1,
                    1,
                    1);
            drawnFrames++;
            lastModel = choice.modelId();
            event.setCanceled(true);
            int actor = PvPlaybackController.actor(event.getEntity().getUUID());
            if (PvPlaybackController.active()
                    && actor >= 0
                    && snapshot != null
                    && snapshot.nativeMotion() != null) {
                var motion = snapshot.nativeMotion();
                var sockets = propSockets(parts, roles, bind, motion, snapshot.sampleSeconds());
                if (sockets.keySet()
                        .containsAll(List.of("LeftHand", "RightHand", "LeftArm", "UpperBody"))) {
                    double sourceLength =
                            motion.bind
                                            .get("LeftArm")
                                            .position()
                                            .sub(motion.bind.get("LeftForeArm").position())
                                            .length()
                                    + motion.bind
                                            .get("LeftForeArm")
                                            .position()
                                            .sub(motion.bind.get("LeftHand").position())
                                            .length();
                    double targetLength =
                            sockets.get("LeftHand")
                                    .position()
                                    .sub(sockets.get("LeftArm").position())
                                    .length();
                    if (actor == 0) {
                        YsmPropAdapter.activeModel = choice.modelId();
                        YsmPropAdapter.bound = true;
                    }
                    PerformerPropBindings.render(pose, actor, sockets, targetLength / sourceLength);
                }
            }
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException ex) {
            warn(choice.modelId(), ex);
        } finally {
            if (pushed) {
                pose.popPose();
            }
            for (Saved s : saved) {
                try {
                    s.part().getClass().getField("visible").setBoolean(s.part(), s.visible());
                    for (int i = 0; i < FIELDS.length; i++) {
                        value(s.part(), FIELDS[i], s.values()[i]);
                    }
                    ((org.joml.Quaternionf)
                                    s.part()
                                            .getClass()
                                            .getField("additionalQuaternion")
                                            .get(s.part()))
                            .set(s.quaternion());
                } catch (ReflectiveOperationException ex) {
                    warn("restore", ex);
                }
            }
        }
    }

    private static void poseParts(
            Map<?, ?> parts,
            Map<String, String> roles,
            Map<Object, String> names,
            Map<Object, Quat> bind,
            Map<Object, Quat> desired,
            ClientPlaybackRuntime.MotionSnapshot snapshot,
            float scale)
            throws ReflectiveOperationException {
        if (snapshot != null && snapshot.nativeMotion() != null) {
            var motion = snapshot.nativeMotion();
            var sampled = motion.sample(snapshot.sampleSeconds());
            for (Object part : parts.values()) {
                String role = names.get(part);
                Quat q = bind.get(part);
                if (role != null && sampled.containsKey(role)) {
                    q =
                            frame(
                                            sampled.get(role)
                                                    .rotation()
                                                    .mul(motion.bind.get(role).rotation().inv()))
                                    .mul(q);
                }
                desired.put(part, q);
            }
            for (String side : List.of("Left", "Right")) {
                for (boolean arm : List.of(true, false)) {
                    String role = side + (arm ? "Arm" : "Leg"),
                            middle = side + (arm ? "ForeArm" : "LowerLeg"),
                            end = side + (arm ? "Hand" : "Foot");
                    Object part = parts.get(roles.get(role));
                    if (part != null && !roles.containsKey(middle) && !roles.containsKey(end)) {
                        Vec direction =
                                sampled.get(end).position().sub(sampled.get(role).position());
                        direction = new Vec(direction.x(), -direction.y(), -direction.z());
                        Quat before = desired.get(part);
                        desired.put(
                                part,
                                Quat.between(before.turn(new Vec(0, 1, 0)), direction).mul(before));
                    }
                }
            }
            for (Object part : parts.values()) {
                if (names.containsKey(part)) {
                    Object p = parent(part);
                    var e =
                            (p == null ? Quat.ID : posed(p, names, desired, bind))
                                    .inv()
                                    .mul(desired.get(part))
                                    .euler();
                    value(part, "xRot", (float) e.x());
                    value(part, "yRot", (float) e.y());
                    value(part, "zRot", (float) e.z());
                }
            }
            var hip =
                    sampled.get("LeftLeg")
                            .position()
                            .add(sampled.get("RightLeg").position())
                            .mul(.5);
            var base =
                    motion.bind
                            .get("LeftLeg")
                            .position()
                            .add(motion.bind.get("RightLeg").position())
                            .mul(.5);
            double
                    floor =
                            (motion.bind.get("LeftFoot").position().y()
                                            + motion.bind.get("RightFoot").position().y())
                                    * .5,
                    gain =
                            motion.sourceWorldCoordinates
                                    ? 1 / scale
                                    : .75 / Math.max(.1, base.y() - floor);
            // 六段女仆常用独立根部件，肩颈跟随躯干，避免弯腰时分离。Keep detached six-part roots connected.
            Object torso = parts.get(roles.get("UpperBody"));
            if (torso != null && parent(torso) == null) {
                Vec origin = position(torso, bind);
                Quat delta = desired.get(torso).mul(bind.get(torso).inv());
                for (String role : List.of("Head", "LeftArm", "RightArm")) {
                    Object part = parts.get(roles.get(role));
                    if (part != null && parent(part) == null) {
                        Vec moved = origin.add(delta.turn(position(part, bind).sub(origin)));
                        value(part, "x", (float) moved.x());
                        value(part, "y", (float) moved.y());
                        value(part, "z", (float) moved.z());
                    }
                }
            }
            // 原生六段模型的独立附件根需要同时跟随枢轴和旋转。Only detached, unmapped accessory roots are driven here.
            if (torso != null) {
                Vec origin = position(torso, bind);
                Quat delta = desired.get(torso).mul(bind.get(torso).inv());
                for (String accessory : List.of("wingLeft", "wingRight", "sittingRotationSkirt")) {
                    followAccessory(parts.get(accessory), origin, delta, names, bind);
                }
            }
            Object left = parts.get(roles.get("LeftLeg")), right = parts.get(roles.get("RightLeg"));
            if (left != null && right != null && parent(left) == null && parent(right) == null) {
                Vec a = position(left, bind), b = position(right, bind), center = a.add(b).mul(.5);
                Quat delta =
                        frame(
                                sampled.get("DownBody")
                                        .rotation()
                                        .mul(motion.bind.get("DownBody").rotation().inv()));
                for (Object part : List.of(left, right)) {
                    Vec moved = center.add(delta.turn((part == left ? a : b).sub(center)));
                    value(part, "x", (float) moved.x());
                    value(part, "y", (float) moved.y());
                    value(part, "z", (float) moved.z());
                }
            }
            for (Object part : parts.values()) {
                if (parent(part) == null) {
                    value(part, "offsetX", (float) (hip.x() * gain));
                    value(part, "offsetY", (float) (-(hip.y() - base.y()) * gain));
                    value(part, "offsetZ", (float) (-hip.z() * gain));
                }
            }
        }
    }

    private static void followAccessory(
            Object part, Vec origin, Quat delta, Map<Object, String> names, Map<Object, Quat> bind)
            throws ReflectiveOperationException {
        if (part == null || parent(part) != null || names.containsKey(part)) {
            return;
        }
        Vec moved = origin.add(delta.turn(position(part, bind).sub(origin)));
        var rotation = delta.mul(bind.get(part)).euler();
        value(part, "x", (float) moved.x());
        value(part, "y", (float) moved.y());
        value(part, "z", (float) moved.z());
        value(part, "xRot", (float) rotation.x());
        value(part, "yRot", (float) rotation.y());
        value(part, "zRot", (float) rotation.z());
    }

    /**
     * 在镜头设置前计算原生头部链，随后恢复共享部件。
     * <p>Evaluate the native head chain before camera setup and restore shared parts.</p>
     */
    public static net.minecraft.world.phys.Vec3 viewEye(
            net.minecraft.world.entity.player.Player actor,
            String id,
            ClientPlaybackRuntime.MotionSnapshot snapshot) {
        List<Saved> saved = new ArrayList<>();
        try {
            initialize();
            if (library == null) {
                return null;
            }
            Object data = info(id);
            if (data == null) {
                return null;
            }
            if ((boolean) data.getClass().getMethod("isGeckoModel").invoke(data)) {
                return GeckoMaidModels.viewEye(actor, id, data, snapshot);
            }
            Object mesh = model(id);
            if (mesh == null) {
                return null;
            }
            Map<?, ?> parts = (Map<?, ?>) mesh.getClass().getMethod("getModelMap").invoke(mesh);
            var roles = nativeRoles(id, parts);
            Object head = parts.get(roles.get("Head"));
            if (head == null) {
                return null;
            }
            Map<Object, String> names = new IdentityHashMap<>();
            roles.forEach((role, name) -> names.put(parts.get(name), role));
            Map<Object, Quat> bind = new IdentityHashMap<>(), desired = new IdentityHashMap<>();
            for (Object part : parts.values()) {
                float[] values = new float[FIELDS.length];
                for (int i = 0; i < FIELDS.length; i++) {
                    values[i] = value(part, FIELDS[i]);
                }
                var q =
                        (org.joml.Quaternionf)
                                part.getClass().getField("additionalQuaternion").get(part);
                saved.add(
                        new Saved(
                                part,
                                values,
                                new org.joml.Quaternionf(q),
                                part.getClass().getField("visible").getBoolean(part)));
                q.identity();
                value(part, "xRot", value(part, "initRotX"));
                value(part, "yRot", value(part, "initRotY"));
                value(part, "zRot", value(part, "initRotZ"));
                for (String n : List.of("offsetX", "offsetY", "offsetZ")) {
                    value(part, n, 0);
                }
                for (String n : List.of("xScale", "yScale", "zScale")) {
                    value(part, n, 1);
                }
                bind(part, bind);
            }
            float scale =
                    ((Number) data.getClass().getMethod("getRenderEntityScale").invoke(data))
                            .floatValue();
            poseParts(parts, roles, names, bind, desired, snapshot, scale);
            Vec focus = dev.flower.stevedance.client.CameraCalibration.focusOffset("maid:" + id);
            var point =
                    partMatrix(head)
                            .transformPosition(
                                    new org.joml.Vector3f(
                                            (float) focus.x() / 16,
                                            (float) focus.y() / 16,
                                            (float) focus.z() / 16));
            return actor.position()
                    .add(
                            new net.minecraft.world.phys.Vec3(
                                            -point.x * scale,
                                            (1.501 - point.y) * scale,
                                            point.z * scale)
                                    .yRot(
                                            (float)
                                                    Math.toRadians(
                                                            180
                                                                    - PvPlaybackController
                                                                            .performerYaw())));
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException ex) {
            warn(id, ex);
            return null;
        } finally {
            for (Saved item : saved) {
                try {
                    for (int i = 0; i < FIELDS.length; i++) {
                        value(item.part(), FIELDS[i], item.values()[i]);
                    }
                    ((org.joml.Quaternionf)
                                    item.part()
                                            .getClass()
                                            .getField("additionalQuaternion")
                                            .get(item.part()))
                            .set(item.quaternion());
                } catch (ReflectiveOperationException ex) {
                    warn("view restore", ex);
                }
            }
        }
    }

    private static Map<String, Transform> propSockets(
            Map<?, ?> parts,
            Map<String, String> roles,
            Map<Object, Quat> bind,
            PvBodyMotion motion,
            double seconds)
            throws ReflectiveOperationException {
        Map<String, Transform> sockets = new HashMap<>();
        for (String role : List.of("LeftHand", "RightHand", "LeftArm", "RightArm", "UpperBody")) {
            String mapped = roles.get(role);
            boolean hand = role.endsWith("Hand");
            Object part =
                    parts.get(mapped != null ? mapped : roles.get(role.replace("Hand", "Arm")));
            if (part == null) {
                continue;
            }
            Vec offset =
                    hand && mapped == null
                            ? handGrip(part)
                            : role.equals("UpperBody") ? new Vec(0, 4, 0) : Vec.ZERO;
            sockets.put(role, socket(part, offset, motion.bind.get(role), bind.get(part)));
        }
        var sourcePose = motion.sample(seconds);
        for (String side : List.of("Left", "Right")) {
            String arm = side + "Arm", hand = side + "Hand";
            if (!roles.containsKey(hand)
                    && !roles.containsKey(side + "ForeArm")
                    && sockets.containsKey(arm)
                    && sockets.containsKey(hand)) {
                sockets.put(
                        hand,
                        PropBinding.rigidHand(
                                sockets.get(hand),
                                sockets.get(arm),
                                sourcePose.get(arm),
                                sourcePose.get(hand),
                                motion.bind.get(arm),
                                motion.bind.get(hand)));
            }
        }
        return sockets;
    }

    // 六段模型的臂长并不等于史蒂夫；握点取实际手臂网格末端的中心。
    // 中文：刚性女仆手臂使用自身网格边界，不套用Steve的十像素握点。
    // EN: Rigid maid arms use their own mesh bounds, rather than Steve's ten-pixel grip.
    private static Vec handGrip(Object part) throws ReflectiveOperationException {
        Vec cached = HAND_GRIPS.get(part);
        if (cached != null) {
            return cached;
        }
        double minX = Double.POSITIVE_INFINITY,
                minZ = minX,
                maxX = Double.NEGATIVE_INFINITY,
                maxY = maxX,
                maxZ = maxX;
        for (Object cube : (List<?>) part.getClass().getField("cubes").get(part)) {
            double x = cubeValue(cube, "x"), y = cubeValue(cube, "y"), z = cubeValue(cube, "z");
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + cubeValue(cube, "width"));
            maxY = Math.max(maxY, y + cubeValue(cube, "height"));
            maxZ = Math.max(maxZ, z + cubeValue(cube, "depth"));
        }
        if (!Double.isFinite(maxY)) {
            throw new IllegalArgumentException("Map a Hand bone for an arm without direct cubes");
        }
        Vec grip =
                new Vec((minX + maxX) * 8, maxY * 16 - Math.min(1, (maxX - minX) * 8), minZ * 16);
        HAND_GRIPS.put(part, grip);
        return grip;
    }

    private static float cubeValue(Object cube, String name) throws ReflectiveOperationException {
        Class<?> type = cube.getClass();
        while (type != null) {
            try {
                var f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.getFloat(cube);
            } catch (NoSuchFieldException ex) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // 与实际网格共用部件变换，包含父级、根位移和缩放。Use the mesh's exact posed transform chain.
    private static void partTransform(Object part, com.mojang.blaze3d.vertex.PoseStack stack)
            throws ReflectiveOperationException {
        Object p = parent(part);
        if (p != null) {
            partTransform(p, stack);
        }
        part.getClass()
                .getMethod("translateAndRotateAndScale", com.mojang.blaze3d.vertex.PoseStack.class)
                .invoke(part, stack);
    }

    private static org.joml.Matrix4f partMatrix(Object part) throws ReflectiveOperationException {
        var stack = new com.mojang.blaze3d.vertex.PoseStack();
        partTransform(part, stack);
        return new org.joml.Matrix4f(stack.last().pose());
    }

    private static Transform socket(Object part, Vec offset, Transform source, Quat rest)
            throws ReflectiveOperationException {
        var stack = new com.mojang.blaze3d.vertex.PoseStack();
        partTransform(part, stack);
        var matrix = stack.last().pose();
        var point =
                matrix.transformPosition(
                        new org.joml.Vector3f(
                                (float) offset.x() / 16,
                                (float) offset.y() / 16,
                                (float) offset.z() / 16));
        var rotation = matrix.getUnnormalizedRotation(new org.joml.Quaternionf()).normalize();
        Quat q =
                new Quat(rotation.x, rotation.y, rotation.z, rotation.w)
                        .mul(rest.inv())
                        .mul(Quat.axis(new Vec(1, 0, 0), Math.PI))
                        .mul(source.rotation());
        return new Transform(new Vec(point.x, point.y, point.z), q);
    }

    public static Path exportBones(String id)
            throws ReflectiveOperationException, java.io.IOException {
        initialize();
        if (library == null) {
            throw new java.io.IOException("Touhou Little Maid is not installed");
        }
        Object mesh = model(id);
        if (mesh == null) {
            throw new java.io.IOException("Maid model unavailable");
        }
        Map<?, ?> parts = (Map<?, ?>) mesh.getClass().getMethod("getModelMap").invoke(mesh);
        Map<Object, String> names = new IdentityHashMap<>();
        for (var entry : parts.entrySet()) {
            names.put(entry.getValue(), (String) entry.getKey());
        }
        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        json.addProperty("modelId", id);
        json.addProperty("coordinateConvention", "minecraft-local");
        JsonArray bones = new JsonArray();
        for (var entry : parts.entrySet()) {
            Object part = entry.getValue();
            JsonObject bone = new JsonObject();
            bone.addProperty("name", (String) entry.getKey());
            Object parent = parent(part);
            if (parent == null) {
                bone.add("parent", JsonNull.INSTANCE);
            } else {
                bone.addProperty("parent", names.get(parent));
            }
            JsonArray position = new JsonArray(), rotation = new JsonArray();
            for (String axis : List.of("x", "y", "z")) {
                position.add(value(part, axis));
                rotation.add(
                        Math.toDegrees(
                                value(part, "initRot" + axis.toUpperCase(java.util.Locale.ROOT))));
            }
            bone.add("position", position);
            bone.add("rotation", rotation);
            bones.add(bone);
        }
        json.add("bones", bones);
        json.add("defaultRoles", new Gson().toJsonTree(DEFAULT));
        Path directory =
                Minecraft.getInstance()
                        .gameDirectory
                        .toPath()
                        .resolve("config/stevedance/bone-exports");
        Files.createDirectories(directory);
        Path file = directory.resolve("maid-" + id.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".json");
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        return file;
    }

    public static void reloadMappings() {
        mappingsLoaded = false;
        mappingError = null;
        MAPPINGS.clear();
        WARNED.clear();
        GeckoMaidModels.clear();
        dev.flower.stevedance.client.CameraCalibration.clearMeasurements();
    }

    private static Map<String, Float> eyeChannels(ClientPlaybackRuntime.MotionSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        double seconds = snapshot.sampleSeconds();
        if (snapshot.faces() != null) {
            return snapshot.faces().sample(seconds).channels();
        }
        if (snapshot.motion() == null) {
            return null;
        }
        var source = snapshot.motion();
        Map<String, Float> channels = new HashMap<>();
        boolean authored = false;
        var morphs =
                Map.of(
                        "blink",
                        List.of("まばたき"),
                        "smileEyes",
                        List.of("笑い", "なごみ"),
                        "winkLeft",
                        List.of("ウィンク", "ウィンク２"),
                        "winkRight",
                        List.of("ウィンク右", "ｳｨﾝｸ２右"));
        for (var channel : morphs.entrySet()) {
            float weight = 0;
            for (String name : channel.getValue()) {
                if (source.morphs().contains(name)) {
                    authored = true;
                    weight += source.morph(name, seconds);
                }
            }
            channels.put(channel.getKey(), weight);
        }
        return authored ? channels : null;
    }
}
