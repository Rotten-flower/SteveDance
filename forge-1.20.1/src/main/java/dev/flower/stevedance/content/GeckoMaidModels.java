package dev.flower.stevedance.content;

import static dev.flower.stevedance.core.SkeletonRetargeter.Bone;
import static dev.flower.stevedance.core.SkeletonRetargeter.HUMANOID;
import static dev.flower.stevedance.core.SkeletonRetargeter.Output;
import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Skeleton;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.client.ClientPlaybackRuntime;
import dev.flower.stevedance.client.PerformerPropBindings;
import dev.flower.stevedance.client.PvPlaybackController;
import dev.flower.stevedance.client.YsmPropAdapter;
import dev.flower.stevedance.core.PvBodyMotion;
import dev.flower.stevedance.core.PvSkeletonRetargeter;
import dev.flower.stevedance.core.SkeletonProfileReader;
import dev.flower.stevedance.core.SkeletonRetargeter;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.client.event.RenderPlayerEvent;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Optional native Gecko backend. 每个演员独享动画实例，不修改女仆共享模型。 */
public final class GeckoMaidModels {
    private static final String ROOT = "com.github.tartaricacid.touhoulittlemaid.";
    private static final Map<LivingEntity, State> ACTORS = new WeakHashMap<>();
    private static final ClassValue<Map<String, Method>> METHODS =
            new ClassValue<>() {
                protected Map<String, Method> computeValue(Class<?> type) {
                    return new HashMap<>();
                }
            };

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(ROOT + name);
    }

    private static Object call(Object object, String name, Object... args)
            throws ReflectiveOperationException {
        String key =
                name
                        + Arrays.stream(args)
                                .map(a -> a == null ? "null" : a.getClass().getName())
                                .toList();
        var cache = METHODS.get(object.getClass());
        Method method = cache.get(key);
        if (method == null) {
            for (Method m : object.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    Class<?>[] p = m.getParameterTypes();
                    boolean ok = true;
                    for (int i = 0; i < p.length; i++) {
                        if (args[i] != null
                                && !p[i].isInstance(args[i])
                                && !(p[i].isPrimitive()
                                        && (args[i] instanceof Number
                                                || args[i] instanceof Boolean))) {
                            ok = false;
                        }
                    }
                    if (ok) {
                        method = m;
                        break;
                    }
                }
            }
            if (method == null) {
                throw new NoSuchMethodException(object.getClass() + "." + name);
            }
            cache.put(key, method);
        }
        return method.invoke(object, args);
    }

    private static Object field(Object o, String n) throws ReflectiveOperationException {
        return o.getClass().getField(n).get(o);
    }

    private static Vec vector(Object v) {
        var p = (Vector3f) v;
        return new Vec(p.x, p.y, p.z);
    }

    private static Vec rotation(Object b) throws ReflectiveOperationException {
        return new Vec(
                ((Number) call(b, "getRotationX")).doubleValue(),
                ((Number) call(b, "getRotationY")).doubleValue(),
                ((Number) call(b, "getRotationZ")).doubleValue());
    }

    private static void write(Object b, String property, Vec v)
            throws ReflectiveOperationException {
        call(b, "set" + property + "X", (float) v.x());
        call(b, "set" + property + "Y", (float) v.y());
        call(b, "set" + property + "Z", (float) v.z());
    }

    private static Object cache() throws ReflectiveOperationException {
        return type("geckolib3.resource.GeckoLibCache").getMethod("getInstance").invoke(null);
    }

    public static boolean available(Object info) throws ReflectiveOperationException {
        return ((Map<?, ?>) call(cache(), "getGeoModels")).containsKey(call(info, "getModelId"));
    }

    private record Eye(String bone, String property, Vec open, Vec closed, int side) {}

    private static final class State {
        final String id;
        final LivingEntity maid;
        final Object nativeEntity, mesh, geo, renderer;
        final Map<String, Object> bones;
        final Map<String, String> roles = new LinkedHashMap<>();
        final Skeleton skeleton;
        final List<Eye> eyes = new ArrayList<>();
        final Set<String> body = new HashSet<>();
        final Map<String, Vec> pivotOffsets = new HashMap<>();
        MultiBufferSource buffers;
        ResourceLocation texture;
        Object cycle;
        PvBodyMotion source;
        PvSkeletonRetargeter solver;
        SkeletonRetargeter vmdSolver;
        double lastTime = -1;
        int eyeCount;

        @SuppressWarnings("unchecked")
        State(String id, Object info) throws ReflectiveOperationException {
            this.id = id;
            maid = MaidModels.createPreview(id);
            if (maid == null) {
                throw new IllegalStateException("Cannot create native maid");
            }
            maid.setUUID(new UUID(0, 0));
            nativeEntity =
                    type("client.entity.GeckoMaidEntity")
                            .getConstructors()[0]
                            .newInstance(maid, maid);
            call(nativeEntity, "setMaidInfo", info);
            if (!(boolean) call(nativeEntity, "updateModel")) {
                throw new IllegalArgumentException("Missing Gecko mesh: " + id);
            }
            mesh = call(nativeEntity, "getCurrentModel");
            geo = call(mesh, "geoModel");
            bones = (Map<String, Object>) call(mesh, "bones");
            for (String role : HUMANOID.keySet()) {
                String name =
                        bones.keySet().stream()
                                .filter(n -> n.equalsIgnoreCase(role))
                                .findFirst()
                                .orElse(null);
                if (name == null && role.endsWith("Hand")) {
                    name = bones.containsKey(role + "Locator") ? role + "Locator" : null;
                }
                if (name == null && role.equals("UpperBody")) {
                    name = bones.containsKey("UpBody") ? "UpBody" : null;
                }
                if (name == null) {
                    throw new IllegalArgumentException(
                            "Missing native body role " + role + " in " + id);
                }
                roles.put(role, name);
            }
            List<String> targets = new ArrayList<>(roles.values());
            for (String hand : List.of("LeftHandLocator", "RightHandLocator")) {
                if (bones.containsKey(hand)) {
                    targets.add(hand);
                }
            }
            for (String name : targets) {
                Object g = call(bones.get(name), "geoBone");
                while (g != null) {
                    body.add((String) call(g, "name"));
                    g = call(g, "parent");
                }
            }
            Map<String, String> aliases = new HashMap<>();
            roles.forEach((r, n) -> aliases.put(n, r));
            List<Bone> definitions = new ArrayList<>();
            for (var e : bones.entrySet()) {
                if (!body.contains(e.getKey())) {
                    continue;
                }
                Object g = call(e.getValue(), "geoBone"), p = call(g, "parent");
                String parent = p == null ? null : (String) call(p, "name");
                Vec pivot = vector(call(g, "pivot")), rest = vector(call(g, "rotation"));
                if (aliases.getOrDefault(e.getKey(), "").endsWith("Foot") && p != null) {
                    Vec corrected = footPivot(g, p, pivot);
                    if (corrected.sub(pivot).length() > 1e-5) {
                        pivotOffsets.put(e.getKey(), corrected.sub(pivot));
                    }
                    pivot = corrected;
                }
                definitions.add(
                        new Bone(
                                aliases.getOrDefault(e.getKey(), e.getKey()),
                                parent == null ? null : aliases.getOrDefault(parent, parent),
                                new Vec(-pivot.x(), pivot.y(), pivot.z()),
                                new Vec(rest.x(), -rest.y(), -rest.z())));
            }
            skeleton = new Skeleton(definitions);
            Class<?> rendererType = type("geckolib3.geo.IGeoRenderer");
            renderer =
                    Proxy.newProxyInstance(
                            rendererType.getClassLoader(),
                            new Class<?>[] {rendererType},
                            (proxy, m, a) -> {
                                return switch (m.getName()) {
                                    case "getCurrentRTB" -> buffers;
                                    case "setCurrentRTB" -> {
                                        buffers = (MultiBufferSource) a[0];
                                        yield null;
                                    }
                                    case "getTextureLocation" -> texture;
                                    case "getCurrentModelRenderCycle" -> cycle;
                                    case "setCurrentModelRenderCycle" -> {
                                        cycle = a[0];
                                        yield null;
                                    }
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == a[0];
                                    case "toString" -> "SteveDance native maid renderer";
                                    default -> InvocationHandler.invokeDefault(proxy, m, a);
                                };
                            });
            Object context = animate(0);
            bakeEyes(context);
        }

        /** A lateral ankle pivot outside its mesh is not an anatomical endpoint.
         * Match the parent's lateral axis only when that axis lies inside the actual foot mesh.
         * 仅纠正网格外且偏离小腿轴的足部横向枢轴，不改原生模型。 */
        private Vec footPivot(Object geo, Object parent, Vec pivot)
                throws ReflectiveOperationException {
            Object mesh = call(geo, "cubes");
            int count = ((Number) call(mesh, "getCubeCount")).intValue();
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < count; i++) {
                Vec p = vector(call(mesh, "position", i)),
                        dx = vector(call(mesh, "dx", i)),
                        dy = vector(call(mesh, "dy", i)),
                        dz = vector(call(mesh, "dz", i));
                for (int mask = 0; mask < 8; mask++) {
                    double x =
                            (p.x()
                                            + ((mask & 1) == 0 ? 0 : dx.x())
                                            + ((mask & 2) == 0 ? 0 : dy.x())
                                            + ((mask & 4) == 0 ? 0 : dz.x()))
                                    * 16;
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
            }
            double x = vector(call(parent, "pivot")).x();
            return count > 0 && (pivot.x() < min || pivot.x() > max) && x >= min && x <= max
                    ? new Vec(x, pivot.y(), pivot.z())
                    : pivot;
        }

        private Object event(double ticks) throws ReflectiveOperationException {
            return type("geckolib3.core.event.predicate.AnimationEvent")
                    .getConstructors()[0]
                    .newInstance(
                            nativeEntity,
                            0f,
                            0f,
                            (float) (ticks - Math.floor(ticks)),
                            false,
                            List.of(
                                    type("geckolib3.model.provider.data.EntityModelData")
                                            .getConstructor()
                                            .newInstance()));
        }

        private Object context(Object event) throws ReflectiveOperationException {
            Object data = ((List<?>) call(event, "getExtraData")).get(0);
            return type("geckolib3.core.molang.context.AnimationContext")
                    .getConstructors()[0]
                    .newInstance(maid, nativeEntity, event, data);
        }

        Object animate(double ticks) throws ReflectiveOperationException {
            maid.tickCount = (int) ticks;
            Object event = event(ticks), context = context(event);
            call(nativeEntity, "setCustomAnimations", context, event);
            return context;
        }

        private Vec sample(List<?> frames, Object evaluator, double tick)
                throws ReflectiveOperationException {
            if (frames.isEmpty()) {
                return null;
            }
            Object chosen = frames.get(0);
            for (Object frame : frames) {
                if (((Number) call(frame, "getStartTick")).doubleValue() <= tick) {
                    chosen = frame;
                } else {
                    break;
                }
            }
            double start = ((Number) call(chosen, "getStartTick")).doubleValue(),
                    length = ((Number) call(chosen, "getTotalTick")).doubleValue();
            return vector(
                    call(
                            chosen,
                            "getLerpPoint",
                            evaluator,
                            Math.max(0, Math.min(1, length == 0 ? 1 : (tick - start) / length))));
        }

        /**
         * 读取原生合并动画，包括自定义包，不依赖模型ID表。
         * <p>Read native merged animations, including custom packs; no model ID table.</p>
         */
        void bakeEyes(Object context) throws ReflectiveOperationException {
            Object evaluator =
                    type("molang.runtime.ExpressionEvaluator")
                            .getMethod("evaluator", Object.class)
                            .invoke(null, context);
            Object controller =
                    type("geckolib3.core.controller.AnimationControllerContext")
                            .getConstructor()
                            .newInstance();
            call(context, "setAnimationControllerContext", controller);
            Map<String, Eye> candidates = new LinkedHashMap<>();
            List<String> animations = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                animations.add("pre_parallel" + i);
                animations.add("parallel" + i);
            }
            animations.add("sleep");
            Object event = call(context, "animationEvent");
            for (String animation : animations) {
                Object anim = call(nativeEntity, "getAnimation", animation);
                if (anim == null) {
                    continue;
                }
                for (Object track : (List<?>) field(anim, "boneAnimations")) {
                    String name = (String) field(track, "boneName"),
                            lower = name.toLowerCase(Locale.ROOT);
                    boolean
                            lid =
                                    lower.contains("eye")
                                            && !lower.contains("base")
                                            && !lower.contains("brow"),
                            brow = lower.contains("eyebrow");
                    if ((!lid && !brow) || !bones.containsKey(name)) {
                        continue;
                    }
                    int side = lower.contains("left") ? 1 : lower.contains("right") ? 2 : 0;
                    for (String property : List.of("Position", "Scale", "Rotation")) {
                        List<?> frames =
                                (List<?>)
                                        field(
                                                track,
                                                property.toLowerCase(Locale.ROOT) + "KeyFrames");
                        if (frames.isEmpty()) {
                            continue;
                        }
                        event.getClass().getField("animationTick").setDouble(event, 0);
                        call(controller, "setAnimTime", 0d);
                        Vec open = sample(frames, evaluator, 0), closed = open;
                        double score = 0;
                        if (animation.equals("sleep")) {
                            closed = open;
                            open = property.equals("Scale") ? new Vec(1, 1, 1) : Vec.ZERO;
                            score = closure(lid, brow, property, open, closed);
                        } else {
                            for (int frame = 0; frame <= 96; frame++) {
                                double tick = frame * 20 / 12d;
                                event.getClass().getField("animationTick").setDouble(event, 87);
                                call(controller, "setAnimTime", tick / 20);
                                double duration =
                                        ((Number) field(anim, "animationLength")).doubleValue();
                                Vec v =
                                        sample(
                                                frames,
                                                evaluator,
                                                duration > 0 ? tick % duration : 0);
                                double s = closure(lid, brow, property, open, v);
                                if (s > score) {
                                    score = s;
                                    closed = v;
                                }
                            }
                        }
                        String key = name + property;
                        Eye previous = candidates.get(key);
                        if (score > 1e-4
                                && (previous == null
                                        || animation.equals("sleep")
                                        || score
                                                > closure(
                                                        lid,
                                                        brow,
                                                        property,
                                                        previous.open,
                                                        previous.closed))) {
                            candidates.put(key, new Eye(name, property, open, closed, side));
                        }
                    }
                }
            }
            for (Eye eye : candidates.values()) {
                List<Eye> split = new ArrayList<>();
                if (eye.side == 0) {
                    for (Object child : (List<?>) call(bones.get(eye.bone), "children")) {
                        String n = (String) call(child, "getName"),
                                lower = n.toLowerCase(Locale.ROOT);
                        int side = lower.contains("left") ? 1 : lower.contains("right") ? 2 : 0;
                        if (side != 0) {
                            split.add(new Eye(n, eye.property, eye.open, eye.closed, side));
                        }
                    }
                }
                if (split.stream().anyMatch(e -> e.side == 1)
                        && split.stream().anyMatch(e -> e.side == 2)) {
                    eyes.add(new Eye(eye.bone, eye.property, eye.open, eye.open, 0));
                    eyes.addAll(split);
                } else {
                    eyes.add(eye);
                }
            }
            eyeCount = eyes.size();
        }

        private double closure(boolean lid, boolean brow, String p, Vec a, Vec b) {
            if (p.equals("Scale") && lid) {
                return Math.max(0, a.y() - b.y());
            }
            if (p.equals("Position")) {
                return brow
                        ? Math.max(0, a.y() - b.y())
                        : lid ? Math.abs(b.y() - a.y()) + Math.abs(b.z() - a.z()) : 0;
            }
            return 0;
        }

        void face(Map<String, Float> channels, double ticks, long phase)
                throws ReflectiveOperationException {
            float blink =
                    channels == null
                            ? (dev.flower.stevedance.core.MaidBlinkState.closed(null, ticks, phase)
                                    ? 1
                                    : 0)
                            : channels.getOrDefault("blink", 0f);
            double smile =
                    channels == null
                            ? 0
                            : Math.max(0, Math.min(1, channels.getOrDefault("smileEyes", 0f)));
            double
                    left =
                            Math.max(
                                    0,
                                    Math.min(
                                            1,
                                            blink
                                                    + (channels == null
                                                            ? 0
                                                            : channels.getOrDefault(
                                                                    "winkLeft", 0f)))),
                    right =
                            Math.max(
                                    0,
                                    Math.min(
                                            1,
                                            blink
                                                    + (channels == null
                                                            ? 0
                                                            : channels.getOrDefault(
                                                                    "winkRight", 0f))));
            // 中文：原生睡眠轨道中的零标量表示显隐，不代表眼睛宽度或厚度。
            // EN: Scalar zero in native sleep tracks is visibility, not eye width/depth.
            // 半闭眼只压低眼睑，避免眼球向面部内缩而消失。
            for (Eye eye : eyes) {
                double weight =
                        eye.side == 1 ? left : eye.side == 2 ? right : Math.min(left, right);
                weight = weight * (1 - smile) + smile;
                Vec v = eye.open.mul(1 - weight).add(eye.closed.mul(weight));
                if (eye.property.equals("Scale")) {
                    v = new Vec(eye.open.x(), v.y(), eye.open.z());
                }
                if (eye.property.equals("Rotation")) {
                    v = v.add(vector(call(call(bones.get(eye.bone), "geoBone"), "rotation")));
                }
                write(bones.get(eye.bone), eye.property, v);
            }
        }

        void body(ClientPlaybackRuntime.MotionSnapshot snapshot, float scale)
                throws ReflectiveOperationException {
            if (snapshot == null) {
                return;
            }
            Output result;
            if (snapshot.nativeMotion() != null) {
                if (source != snapshot.nativeMotion()) {
                    source = snapshot.nativeMotion();
                    solver = PvSkeletonRetargeter.nativeSize(source, skeleton, scale);
                }
                result = solver.sample(source, snapshot.sampleSeconds());
            } else if (snapshot.motion() != null && !snapshot.motion().bones().isEmpty()) {
                if (vmdSolver == null) {
                    try (var in =
                            GeckoMaidModels.class.getResourceAsStream(
                                    "/assets/stevedance/retarget/miku-reference.json")) {
                        vmdSolver =
                                new SkeletonRetargeter(SkeletonProfileReader.read(in), skeleton);
                    } catch (java.io.IOException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
                result = vmdSolver.sample(snapshot.motion(), snapshot.sampleSeconds());
            } else {
                return;
            }
            Map<String, String> aliases = new HashMap<>();
            roles.forEach((r, n) -> aliases.put(n, r));
            for (String name : body) {
                Object b = bones.get(name);
                String role = aliases.getOrDefault(name, name);
                Vec r = result.localRotations().get(role).euler();
                Vec nativeRotation = new Vec(r.x(), -r.y(), -r.z());
                write(b, "Rotation", nativeRotation);
                Vec shift = pivotOffsets.getOrDefault(name, Vec.ZERO),
                        correction =
                                shift.sub(
                                        Quat.euler(nativeRotation)
                                                .mul(
                                                        Quat.euler(
                                                                        vector(
                                                                                call(
                                                                                        call(
                                                                                                b,
                                                                                                "geoBone"),
                                                                                        "rotation")))
                                                                .inv())
                                                .turn(shift));
                write(
                        b,
                        "Position",
                        new Vec(
                                -correction.x(),
                                correction.y(),
                                correction.z())); /* Keep native visibility scale. 保留原生显隐。 */
            }
            // 中文：RenderUtils自身会反射平移X，设置器接收公共坐标X。
            // EN: RenderUtils itself reflects translation X; the setter receives common X.
            Vec root = result.rootTranslation();
            write(bones.get(roles.get("AllBody")), "Position", root);
        }

        Object parent(Object bone) throws ReflectiveOperationException {
            Object g = call(call(bone, "geoBone"), "parent");
            return g == null ? null : bones.get(call(g, "name"));
        }

        void matrix(Object bone, PoseStack pose) throws ReflectiveOperationException {
            Object p = parent(bone);
            if (p != null) {
                matrix(p, pose);
            }
            type("geckolib3.util.RenderUtils")
                    .getMethod(
                            "prepMatrixForBone",
                            PoseStack.class,
                            type("geckolib3.core.processor.ILocationBone"))
                    .invoke(null, pose, bone);
        }

        Transform socket(String role, PvBodyMotion motion) throws ReflectiveOperationException {
            String name =
                    role.endsWith("Hand") && bones.containsKey(role + "Locator")
                            ? role + "Locator"
                            : roles.get(role);
            Object b = bones.get(name);
            PoseStack stack = new PoseStack();
            matrix(b, stack);
            Vec pivot = vector(call(call(b, "geoBone"), "pivot"));
            Vector3f p =
                    stack.last()
                            .pose()
                            .transformPosition(
                                    new Vector3f(
                                            (float) pivot.x() / 16,
                                            (float) pivot.y() / 16,
                                            (float) pivot.z() / 16));
            Quaternionf rotation =
                    stack.last().pose().getUnnormalizedRotation(new Quaternionf()).normalize();
            Quat q = new Quat(rotation.x, rotation.y, rotation.z, rotation.w());
            Quat rest = skeleton.bind.get(skeleton.has(name) ? name : role).rotation();
            rest = new Quat(rest.x(), -rest.y(), -rest.z(), rest.w());
            Quat source = motion.bind.get(role).rotation();
            source = new Quat(source.x(), -source.y(), -source.z(), source.w());
            return new Transform(new Vec(p.x, p.y, p.z), q.mul(rest.inv()).mul(source));
        }
    }

    public static net.minecraft.world.phys.Vec3 viewEye(
            net.minecraft.world.entity.player.Player actor,
            String id,
            Object info,
            ClientPlaybackRuntime.MotionSnapshot snapshot)
            throws ReflectiveOperationException {
        State state = ACTORS.get(actor);
        Object current = ((Map<?, ?>) call(cache(), "getGeoModels")).get(call(info, "getModelId"));
        if (state == null || !state.id.equals(id) || state.geo != current) {
            state = new State(id, info);
            ACTORS.put(actor, state);
        }
        float scale = ((Number) call(info, "getRenderEntityScale")).floatValue();
        state.animate(snapshot.sampleSeconds() * 20);
        state.body(snapshot, scale);
        var head = state.socket("Head", snapshot.nativeMotion());
        Vec offset = dev.flower.stevedance.client.CameraCalibration.focusOffset("maid:" + id);
        var point = head.position().add(head.rotation().turn(offset.mul(1. / 16)));
        return actor.position()
                .add(
                        new net.minecraft.world.phys.Vec3(
                                        point.x() * scale, point.y() * scale, point.z() * scale)
                                .yRot(
                                        (float)
                                                Math.toRadians(
                                                        180
                                                                - PvPlaybackController
                                                                        .performerYaw())));
    }

    public static boolean render(
            RenderPlayerEvent.Pre event,
            String id,
            Object info,
            Map<String, Float> channels,
            double ticks)
            throws ReflectiveOperationException {
        var actor = event.getEntity();
        State state = ACTORS.get(actor);
        Object current = ((Map<?, ?>) call(cache(), "getGeoModels")).get(call(info, "getModelId"));
        if (state == null || !state.id.equals(id) || state.geo != current) {
            state = new State(id, info);
            ACTORS.put(actor, state);
        }
        float scale = ((Number) call(info, "getRenderEntityScale")).floatValue();
        if (!Float.isFinite(scale) || scale <= 0 || scale > 16) {
            throw new IllegalArgumentException("Invalid maid scale");
        }
        var snapshot = ClientPlaybackRuntime.MOTION_SNAPSHOTS.get(actor.getUUID());
        state.animate(ticks);
        state.body(snapshot, scale);
        state.face(channels, ticks, actor.getUUID().getLeastSignificantBits());
        Vec head = state.skeleton.bind.get("Head").position();
        dev.flower.stevedance.client.CameraCalibration.measure(
                actor.getUUID(),
                "maid:" + id,
                new Vec(head.x(), head.y(), -head.z()).mul(scale / 16),
                dev.flower.stevedance.client.CameraCalibration.focusOffset("maid:" + id)
                        .mul(scale / 16));
        Object viewHead = null;
        boolean headHidden = false, headChildrenHidden = false;
        if (dev.flower.stevedance.client.StageCamera.hideHead(actor.getUUID())) {
            viewHead = state.bones.get(state.roles.get("Head"));
            headHidden = (boolean) call(viewHead, "isHidden");
            headChildrenHidden = (boolean) call(viewHead, "childBonesAreHiddenToo");
            call(viewHead, "setHidden", true, true);
        }
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        try {
            pose.mulPose(
                    com.mojang.math.Axis.YP.rotationDegrees(
                            180
                                    - Mth.rotLerp(
                                            event.getPartialTick(),
                                            actor.yBodyRotO,
                                            actor.yBodyRot)));
            pose.scale(scale, scale, scale);
            state.texture = (ResourceLocation) call(info, "getTexture");
            state.buffers = event.getMultiBufferSource();
            var render =
                    dev.flower.stevedance.client.PvCharacterRenderer.forActor(
                            event.getEntity(),
                            state.texture,
                            RenderType.entityCutoutNoCull(state.texture));
            call(
                    state.renderer,
                    "render",
                    state.mesh,
                    state.maid,
                    event.getPartialTick(),
                    render,
                    pose,
                    state.buffers,
                    state.buffers.getBuffer(render),
                    event.getPackedLight(),
                    OverlayTexture.NO_OVERLAY,
                    1f,
                    1f,
                    1f,
                    1f);
            int index = PvPlaybackController.actor(actor.getUUID());
            if (PvPlaybackController.active()
                    && index >= 0
                    && snapshot != null
                    && snapshot.nativeMotion() != null) {
                Map<String, Transform> sockets = new HashMap<>();
                for (String role :
                        List.of("LeftHand", "RightHand", "LeftArm", "RightArm", "UpperBody")) {
                    sockets.put(role, state.socket(role, snapshot.nativeMotion()));
                }
                double
                        sourceLength =
                                snapshot.nativeMotion()
                                        .bind
                                        .get("LeftHand")
                                        .position()
                                        .sub(snapshot.nativeMotion().bind.get("LeftArm").position())
                                        .length(),
                        targetLength =
                                state.skeleton
                                                .bind
                                                .get("LeftHand")
                                                .position()
                                                .sub(state.skeleton.bind.get("LeftArm").position())
                                                .length()
                                        / 16;
                if (index == 0) {
                    YsmPropAdapter.activeModel = id;
                    YsmPropAdapter.bound = true;
                }
                PerformerPropBindings.render(pose, index, sockets, targetLength / sourceLength);
            }
        } finally {
            pose.popPose();
            if (viewHead != null) {
                call(viewHead, "setHidden", headHidden, headChildrenHidden);
            }
        }
        return true;
    }

    public static void clear() {
        ACTORS.clear();
    }
}
