package dev.flower.stevedance.client;

import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.AutomaticFaceProfiles;
import dev.flower.stevedance.core.AutomaticYsmBodyProfiles;
import dev.flower.stevedance.core.FacialExpressionRig;
import dev.flower.stevedance.core.FingerRetargeter;
import dev.flower.stevedance.core.GarmentPoseConstraints;
import dev.flower.stevedance.core.PvBodyMotion;
import dev.flower.stevedance.core.PvSkeletonRetargeter;
import dev.flower.stevedance.core.SkeletonRetargeter;
import dev.flower.stevedance.core.YsmModelProfiles;

import net.minecraft.world.entity.LivingEntity;

import org.joml.Vector3f;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 检查了 YSM 2.6.5 适配器。在其动画控制器之后写入实际模型。
 * <p>Inspected YSM 2.6.5 adapter. Writes the actual model after its animation controller.</p>
 */
public final class YsmAnimationAdapter {
    private record Binding(Object bone, SkeletonRetargeter.Bone definition) {}

    private record FaceBinding(Object bone, FacialExpressionRig.Node node) {}

    private static final class Saved {
        float[][] values;
        long epoch = -1;
    }

    private static final class Weapons {
        final List<Object> bones;
        boolean[] original;

        Weapons(List<Object> bones) {
            this.bones = List.copyOf(bones);
        }
    }

    private record Cached(
            UUID actor,
            Object geometry,
            String modelId,
            List<Binding> bindings,
            YsmModelProfiles.Profile profile,
            GarmentPoseConstraints.State garmentState,
            Saved body,
            FacialExpressionRig face,
            List<FaceBinding> faceBindings,
            Saved facial,
            Weapons weapons) {}

    private static AutomaticFaceProfiles autoFaces;
    private static AutomaticYsmBodyProfiles autoBodies;
    private static Method[] readers, writers;
    private static int activeBodies, activeFaces;
    private static final Map<UUID, FacialExpressionRig.Frame> FACE_FRAMES = new HashMap<>();

    public static synchronized Map<UUID, FacialExpressionRig.Frame> faceFrames() {
        return Map.copyOf(FACE_FRAMES);
    }

    private static final Map<Object, Cached> CACHE = new WeakHashMap<>();

    record PropJoint(String nativeName, Object bone, SkeletonRetargeter.Bone definition) {}

    record PropSkeleton(
            UUID actor,
            Object geometry,
            String modelId,
            YsmModelProfiles.Profile profile,
            List<PropJoint> joints) {}

    /**
     * 仅暴露实际绑定到此渲染模型的已验证身体映射。
     * <p>Only expose the validated body mapping actually bound to this rendered model.</p>
     */
    static synchronized PropSkeleton propSkeleton(Object model) {
        var cached = CACHE.get(model);
        if (cached == null || cached.profile() == null || cached.body().values == null) {
            return null;
        }
        return new PropSkeleton(
                cached.actor(),
                cached.geometry(),
                cached.modelId(),
                cached.profile(),
                cached.bindings().stream()
                        .map(
                                b ->
                                        new PropJoint(
                                                cached.profile()
                                                        .definition()
                                                        .nativeNames()
                                                        .get(b.definition().name()),
                                                b.bone(),
                                                b.definition()))
                        .toList());
    }

    private static Method entity,
            geometry,
            modelId,
            boneMap,
            name,
            origin,
            pivotX,
            pivotY,
            pivotZ,
            px,
            py,
            pz,
            rx,
            ry,
            rz,
            sx,
            sy,
            sz,
            renderScale,
            hiddenReader,
            hiddenWriter;
    private static boolean initialized, failed;
    private static Field controllerModel;
    private static YsmModelProfiles profiles;
    // 中文：每个选定的服装都有其自己的骨骼；模型切换后绝不要重用 maid 求解器。
    // EN: Each selected outfit has its own skeleton; never reuse a maid solver after a model
    // switch.
    private static final Map<PvBodyMotion, Map<YsmModelProfiles.Profile, PvSkeletonRetargeter>>
            NATIVE_SOLVERS = new WeakHashMap<>();
    public static volatile String lastProfile = "";
    public static volatile FacialExpressionRig.Frame lastFace;
    public static volatile int faceAppliedFrames;
    public static volatile int appliedFrames;
    public static volatile double lastFootError;
    public static volatile double lastGarmentPenetration;

    public static synchronized void prepare() throws IOException {
        if (profiles != null) {
            return;
        }
        var root =
                net.minecraft.client.Minecraft.getInstance()
                        .gameDirectory
                        .toPath()
                        .resolve("config/stevedance/retarget");
        profiles =
                YsmModelProfiles.bundled()
                        .withLocal(root, message -> SteveDanceMod.LOGGER.warn("{}", message));
        autoFaces =
                AutomaticFaceProfiles.bundled()
                        .withLocal(
                                root.resolve("auto-faces.json"),
                                message -> SteveDanceMod.LOGGER.warn("{}", message));
        autoBodies = new AutomaticYsmBodyProfiles(profiles.all());
        SteveDanceMod.LOGGER.info(
                "Loaded {} YSM model profiles and {} facial templates",
                profiles.all().size(),
                autoFaces.size());
    }

    public static synchronized void prepareNative(PvBodyMotion motion) throws IOException {
        prepare();
        if (NATIVE_SOLVERS.containsKey(motion)) {
            return;
        }
        Map<YsmModelProfiles.Profile, PvSkeletonRetargeter> prepared = new WeakHashMap<>();
        NATIVE_SOLVERS.put(motion, prepared);
    }

    public static synchronized YsmModelProfiles.Profile knownProfile(String id) {
        return profiles == null ? null : profiles.get(id);
    }

    public static synchronized YsmModelProfiles.Profile inferProfile(
            String id, Map<String, AutomaticYsmBodyProfiles.Joint> joints) {
        return autoBodies == null
                ? null
                : autoBodies.recognize(
                        id,
                        joints,
                        message ->
                                SteveDanceMod.LOGGER.info("YSM preparation {}: {}", id, message));
    }

    public static synchronized void invalidatePrepared(String id) {
        var entries = CACHE.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getValue().modelId().equals(id)) {
                try {
                    restore(entry.getValue());
                } catch (ReflectiveOperationException e) {
                    SteveDanceMod.LOGGER.error("Cannot restore YSM before mapping reload", e);
                }
                entries.remove();
            }
        }
    }

    // 中文：控制器在 YSM 的工作线程上运行。仅读取不可变的已发布时钟和运动数据。
    // EN: Controller runs on YSM's worker. Only immutable published clocks and motion data are
    // read.
    public static synchronized void applyController(Object controller) {
        if (failed
                || (ClientPlaybackRuntime.MOTION_SNAPSHOTS.isEmpty()
                        && activeBodies == 0
                        && activeFaces == 0)
                || profiles == null) {
            return;
        }
        try {
            if (controllerModel == null) {
                controllerModel =
                        controller.getClass().getDeclaredField("o0OOooo0o0OO00OoOOOo0o0O");
                controllerModel.setAccessible(true);
            }
            apply(controllerModel.get(controller));
        } catch (ReflectiveOperationException | ClassCastException ex) {
            failed = true;
            CACHE.clear();
            SteveDanceMod.LOGGER.error("YSM 2.6.5 retarget interface changed", ex);
        }
    }

    private static void apply(Object model) throws ReflectiveOperationException {
        if (!initialized) {
            initialize(model.getClass().getClassLoader());
        }
        Object object = entity.invoke(model);
        if (!(object instanceof LivingEntity player)) {
            return;
        }
        var snapshot = ClientPlaybackRuntime.MOTION_SNAPSHOTS.get(player.getUUID());
        if (snapshot == null) {
            restore(CACHE.get(model));
            return;
        }
        Object current = geometry.invoke(model);
        if (current == null) {
            return;
        }
        String id = (String) modelId.invoke(model);
        Cached cached = CACHE.get(model);
        dev.flower.stevedance.client.YsmRigPreparation.request(id);
        if (cached == null
                || !cached.actor().equals(player.getUUID())
                || cached.geometry() != current
                || !Objects.equals(cached.modelId(), id)) {
            restore(cached);
            Map<String, Object> bones = new HashMap<>();
            for (Object bone : ((Map<?, ?>) boneMap.invoke(current)).values()) {
                bones.put((String) name.invoke(bone), bone);
            }
            List<Binding> bindings = new ArrayList<>();
            YsmModelProfiles.Profile profile =
                    dev.flower.stevedance.client.YsmRigPreparation.profile(id);
            boolean preparedProfile = profile != null;
            if (profile == null) {
                profile = profiles.get(id);
            }
            if (profile == null) {
                Map<String, AutomaticYsmBodyProfiles.Joint> joints = new HashMap<>();
                for (var e : bones.entrySet()) {
                    Object bone = e.getValue();
                    var rest = (Vector3f) origin.invoke(bone);
                    joints.put(
                            e.getKey(),
                            new AutomaticYsmBodyProfiles.Joint(
                                    new SkeletonRetargeter.Vec(
                                            -((Number) pivotX.invoke(bone)).doubleValue(),
                                            ((Number) pivotY.invoke(bone)).doubleValue(),
                                            ((Number) pivotZ.invoke(bone)).doubleValue()),
                                    new SkeletonRetargeter.Vec(rest.x, -rest.y, -rest.z)));
                }
                profile =
                        autoBodies.recognize(
                                id,
                                joints,
                                message ->
                                        SteveDanceMod.LOGGER.info(
                                                "YSM automatic body {}: {}", id, message));
            }
            Map<String, YsmModelProfiles.NativeBone> nativeBones = new HashMap<>();
            if (profile != null) {
                for (var definition : profile.definition().skeleton().bones) {
                    String nativeName = profile.definition().nativeNames().get(definition.name());
                    Object bone = bones.get(nativeName);
                    if (bone == null) {
                        break;
                    }
                    double x = ((Number) pivotX.invoke(bone)).doubleValue(),
                            y = ((Number) pivotY.invoke(bone)).doubleValue(),
                            z = ((Number) pivotZ.invoke(bone)).doubleValue();
                    Vector3f rest = (Vector3f) origin.invoke(bone);
                    nativeBones.put(
                            nativeName,
                            new YsmModelProfiles.NativeBone(
                                    new SkeletonRetargeter.Vec(-x, y, z),
                                    SkeletonRetargeter.Quat.euler(
                                            new SkeletonRetargeter.Vec(rest.x, -rest.y, -rest.z))));
                    bindings.add(new Binding(bone, definition));
                }
            }
            if (profile != null && !YsmModelProfiles.matches(profile, nativeBones)) {
                if (preparedProfile) {
                    dev.flower.stevedance.client.YsmRigPreparation.discard(id);
                }
                for (var definition : profile.definition().skeleton().bones) {
                    String nativeName = profile.definition().nativeNames().get(definition.name());
                    var actual = nativeBones.get(nativeName);
                    var expected = SkeletonRetargeter.Quat.euler(definition.restEuler());
                    if (actual == null
                            || actual.pivot().sub(definition.pivot()).length() > .03
                            || Math.abs(
                                            actual.rest().x() * expected.x()
                                                    + actual.rest().y() * expected.y()
                                                    + actual.rest().z() * expected.z()
                                                    + actual.rest().w() * expected.w())
                                    < Math.cos(.002)) {
                        SteveDanceMod.LOGGER.warn(
                                "YSM reference mismatch {} node {}: expected pivot={} rotation={}, actual={}",
                                id,
                                nativeName,
                                definition.pivot(),
                                expected,
                                actual);
                    }
                }
                profile = null;
            }
            Map<String, FacialExpressionRig.Node> faceNodes = new HashMap<>();
            for (var e : bones.entrySet()) {
                Object bone = e.getValue();
                var rest = (Vector3f) origin.invoke(bone);
                faceNodes.put(
                        e.getKey(),
                        new FacialExpressionRig.Node(
                                e.getKey(),
                                new SkeletonRetargeter.Vec(
                                        -((Number) pivotX.invoke(bone)).doubleValue(),
                                        ((Number) pivotY.invoke(bone)).doubleValue(),
                                        ((Number) pivotZ.invoke(bone)).doubleValue()),
                                new SkeletonRetargeter.Vec(rest.x, -rest.y, -rest.z)));
            }
            FacialExpressionRig face = profile == null ? null : profile.face();
            List<FaceBinding> faceBindings = new ArrayList<>();
            if (face != null) {
                for (var node : face.nodes()) {
                    Object bone = bones.get(node.name());
                    if (bone == null) {
                        SteveDanceMod.LOGGER.warn("YSM face {} missing node {}", id, node.name());
                        face = null;
                        break;
                    }
                    double x = ((Number) pivotX.invoke(bone)).doubleValue(),
                            y = ((Number) pivotY.invoke(bone)).doubleValue(),
                            z = ((Number) pivotZ.invoke(bone)).doubleValue();
                    var rest = (Vector3f) origin.invoke(bone);
                    var q =
                            SkeletonRetargeter.Quat.euler(
                                    new SkeletonRetargeter.Vec(rest.x, -rest.y, -rest.z));
                    var expected = SkeletonRetargeter.Quat.euler(node.restEuler());
                    double dot =
                            Math.abs(
                                    q.x() * expected.x()
                                            + q.y() * expected.y()
                                            + q.z() * expected.z()
                                            + q.w() * expected.w());
                    if (new SkeletonRetargeter.Vec(-x, y, z).sub(node.pivot()).length() > .03
                            || dot < Math.cos(.002)) {
                        SteveDanceMod.LOGGER.warn(
                                "YSM face {} reference mismatch at {}: pivot={}, expected={}, rotation={}, expected={}",
                                id,
                                node.name(),
                                new SkeletonRetargeter.Vec(-x, y, z),
                                node.pivot(),
                                rest,
                                node.restEuler());
                        face = null;
                        break;
                    }
                    faceBindings.add(new FaceBinding(bone, node));
                }
            }
            if (face == null) {
                faceBindings.clear();
                face = autoFaces.recognize(faceNodes);
                if (face != null) {
                    for (var node : face.nodes()) {
                        faceBindings.add(new FaceBinding(bones.get(node.name()), node));
                    }
                }
            }
            if (face != null && profile != null) {
                var body = profile.definition().nativeNames().values();
                if (face.nodes().stream().anyMatch(n -> body.contains(n.name()))) {
                    face = null;
                    faceBindings.clear();
                    SteveDanceMod.LOGGER.warn("YSM face {} overlaps body nodes", id);
                }
            }
            SteveDanceMod.LOGGER.info(
                    "YSM face profile {}: supported={}, template={}, capabilities={}, {} nodes",
                    id,
                    face != null,
                    face == null ? "none" : face.id,
                    face == null ? Set.of() : face.capabilities(),
                    faceBindings.size());
            List<Object> weaponBones = new ArrayList<>();
            if (profile != null) {
                for (String weapon : profile.hiddenDuringPlayback()) {
                    Object bone = bones.get(weapon);
                    if (bone != null) {
                        weaponBones.add(bone);
                    } else {
                        SteveDanceMod.LOGGER.warn(
                                "YSM playback weapon {} missing node {}", id, weapon);
                    }
                }
            }
            cached =
                    new Cached(
                            player.getUUID(),
                            current,
                            id,
                            List.copyOf(bindings),
                            profile,
                            new GarmentPoseConstraints.State(),
                            new Saved(),
                            face,
                            List.copyOf(faceBindings),
                            new Saved(),
                            new Weapons(weaponBones));
            CACHE.put(model, cached);
            SteveDanceMod.LOGGER.info(
                    "YSM retarget {}: supported={}, family={}, mode={}, {} bones",
                    id,
                    profile != null,
                    profile == null ? "none" : profile.family(),
                    profile == null ? "native" : profile.mode(),
                    bindings.size());
        }
        double seconds = snapshot.sampleSeconds();
        if (seconds < 0
                || seconds > snapshot.durationSeconds()
                || System.nanoTime() - snapshot.capturedNanos() > 2_000_000_000L) {
            restore(cached);
            return;
        }
        if (cached.body().epoch != snapshot.clock().epoch()) {
            cached.garmentState().reset();
            cached.body().epoch = snapshot.clock().epoch();
        }
        var profile = cached.profile();
        if (profile != null) {
            boolean nativeMotion = snapshot.nativeMotion() != null;
            var byProfile = nativeMotion ? NATIVE_SOLVERS.get(snapshot.nativeMotion()) : null;
            if (nativeMotion
                    && byProfile != null
                    && (!byProfile.containsKey(profile)
                            || (snapshot.nativeMotion().sourceWorldCoordinates
                                    && byProfile.get(profile) != null
                                    && Math.abs(
                                                    byProfile.get(profile).modelScale
                                                            - ((Number) renderScale.invoke(model))
                                                                    .doubleValue())
                                            > 1e-6))) {
                try {
                    var target = profile.definition().skeleton();
                    double rendererScale = ((Number) renderScale.invoke(model)).doubleValue();
                    byProfile.put(
                            profile,
                            snapshot.nativeMotion().sourceWorldCoordinates
                                    ? PvSkeletonRetargeter.nativeSize(
                                            snapshot.nativeMotion(), target, rendererScale)
                                    : new PvSkeletonRetargeter(snapshot.nativeMotion(), target));
                } catch (IllegalArgumentException ex) {
                    byProfile.put(profile, null);
                    SteveDanceMod.LOGGER.warn(
                            "PV {} cannot bind automatic YSM {}: {}",
                            snapshot.nativeMotion().source,
                            id,
                            ex.getMessage());
                }
            }
            var nativeSolver = byProfile == null ? null : byProfile.get(profile);
            if (nativeMotion && nativeSolver == null) {
                restore(cached);
                return;
            }
            boolean sourceWorld = nativeMotion && snapshot.nativeMotion().sourceWorldCoordinates;
            float fixedScale = sourceWorld ? ((Number) renderScale.invoke(model)).floatValue() : 1;
            if (!Float.isFinite(fixedScale) || fixedScale <= .01f || fixedScale > 10) {
                throw new IllegalStateException("Invalid YSM renderer scale");
            }
            if (sourceWorld) {
                var rest = profile.definition().skeleton().bind;
                var h = rest.get("Head").position();
                var foot =
                        rest.get("LeftFoot")
                                .position()
                                .add(rest.get("RightFoot").position())
                                .mul(.5);
                var neutral = h.sub(foot).mul(fixedScale / 16);
                dev.flower.stevedance.client.CameraCalibration.measure(
                        player.getUUID(),
                        "ysm:" + id,
                        new SkeletonRetargeter.Vec(neutral.x(), neutral.y(), -neutral.z()));
            }
            var pose =
                    nativeMotion
                            ? nativeSolver.sample(snapshot.nativeMotion(), seconds)
                            : profile.solver().sample(snapshot.motion(), seconds);
            lastFootError = pose.footError();
            lastProfile = profile.id();
            var garment =
                    profile.garments() == null
                            ? new GarmentPoseConstraints.Output(
                                    pose.localRotations(),
                                    Map.of("AllBody", pose.rootTranslation()),
                                    0,
                                    0)
                            : profile.garments().apply(pose, cached.garmentState(), seconds);
            lastGarmentPenetration = garment.remainingPenetration();
            if (cached.body().values == null) {
                cached.body().values = save(cached.bindings().stream().map(Binding::bone).toList());
                activeBodies++;
            }
            for (Binding binding : cached.bindings()) {
                Object bone = binding.bone();
                var definition = binding.definition();
                if (!FingerRetargeter.available(snapshot.nativeMotion(), definition.name())) {
                    continue;
                }
                var e = garment.rotations().get(definition.name()).euler();
                // 中文：将 X 从通用模型空间镜像到原生骨骼：轴向符号
                // EN: Mirror X from common model space into the native skeleton: axial signs
                // (+X,-Y,-Z).
                rx.invoke(bone, (float) e.x());
                ry.invoke(bone, (float) -e.y());
                rz.invoke(bone, (float) -e.z());
                // 手指只接原曲转角，保留原生局部位移、缩放和显隐。Own only finger rotation.
                if (FingerRetargeter.isFinger(definition.name())) {
                    continue;
                }
                var delta =
                        garment.translations()
                                .getOrDefault(definition.name(), SkeletonRetargeter.Vec.ZERO);
                // 中文：YSM 的 JNI 将自身的 X 反射应用于根平移。
                // EN: YSM's JNI applies its own X reflection to the root translation.
                // 中文：保持局部骨骼镜像不变，但将此PV放置于源世界坐标。
                // EN: Keep local bone mirroring intact, but place this PV at source world
                // coordinates.
                px.invoke(
                        bone,
                        (float)
                                (sourceWorld && definition.name().equals("AllBody")
                                        ? delta.x()
                                        : -delta.x()));
                py.invoke(bone, (float) delta.y());
                pz.invoke(bone, (float) delta.z());
                // 中文：保留模型普通渲染器尺寸；仅平移创作根节点。
                // EN: Retain the model's ordinary renderer size; only translate the authored root.
                // 根骨不再把目标模型归一化为原作身高。
                float scale =
                        nativeMotion && definition.parent() == null
                                ? (float) nativeSolver.modelScale / fixedScale
                                : 1f;
                var outfitScale =
                        garment.scales()
                                .getOrDefault(
                                        definition.name(), new SkeletonRetargeter.Vec(1, 1, 1));
                sx.invoke(bone, scale * (float) outfitScale.x());
                sy.invoke(bone, scale * (float) outfitScale.y());
                sz.invoke(bone, scale * (float) outfitScale.z());
            }
            hideWeapons(cached);
        } else {
            restoreBody(cached);
        }
        var face = cached.face();
        boolean authored =
                face != null
                        && (snapshot.faces() != null
                                || snapshot.motion() != null && face.drives(snapshot.motion()));
        if (authored) {
            if (cached.facial().values == null) {
                cached.facial().values =
                        save(cached.faceBindings().stream().map(FaceBinding::bone).toList());
                activeFaces++;
            }
            var frame =
                    snapshot.faces() != null
                            ? face.sample(snapshot.faces().sample(seconds))
                            : face.sample(snapshot.motion(), seconds);
            for (int b = 0; b < cached.faceBindings().size(); b++) {
                var binding = cached.faceBindings().get(b);
                Object bone = binding.bone();
                var value = frame.bones().get(binding.node().name());
                if (value == null) {
                    for (int axis = 0; axis < 9; axis++) {
                        writers[axis].invoke(bone, cached.facial().values[b][axis]);
                    }
                    continue;
                }
                var e = value.rotation();
                var t = value.translation();
                var s = value.scale();
                rx.invoke(bone, (float) e.x());
                ry.invoke(bone, (float) -e.y());
                rz.invoke(bone, (float) -e.z());
                px.invoke(bone, (float) -t.x());
                py.invoke(bone, (float) t.y());
                pz.invoke(bone, (float) t.z());
                sx.invoke(bone, (float) s.x());
                sy.invoke(bone, (float) s.y());
                sz.invoke(bone, (float) s.z());
            }
            FACE_FRAMES.put(cached.actor(), frame);
            lastFace = frame;
            faceAppliedFrames++;
        } else {
            restoreFace(cached);
        }
        appliedFrames++;
    }

    private static float[][] save(List<Object> bones) throws ReflectiveOperationException {
        var saved = new float[bones.size()][9];
        for (int b = 0; b < saved.length; b++) {
            for (int axis = 0; axis < 9; axis++) {
                saved[b][axis] = ((Number) readers[axis].invoke(bones.get(b))).floatValue();
            }
        }
        return saved;
    }

    private static void restoreFace(Cached c) throws ReflectiveOperationException {
        if (c == null || c.facial().values == null) {
            return;
        }
        var saved = c.facial().values;
        for (int b = 0; b < saved.length; b++) {
            for (int axis = 0; axis < 9; axis++) {
                writers[axis].invoke(c.faceBindings().get(b).bone(), saved[b][axis]);
            }
        }
        c.facial().values = null;
        activeFaces--;
        FACE_FRAMES.remove(c.actor());
        lastFace = null;
    }

    private static void restoreBody(Cached c) throws ReflectiveOperationException {
        if (c == null || c.body().values == null) {
            return;
        }
        var saved = c.body().values;
        for (int b = 0; b < saved.length; b++) {
            for (int axis = 0; axis < 9; axis++) {
                writers[axis].invoke(c.bindings().get(b).bone(), saved[b][axis]);
            }
        }
        c.body().values = null;
        c.garmentState().reset();
        activeBodies--;
    }

    private static void hideWeapons(Cached c) throws ReflectiveOperationException {
        var state = c.weapons();
        if (state.bones.isEmpty()) {
            return;
        }
        if (state.original == null) {
            state.original = new boolean[state.bones.size()];
            for (int i = 0; i < state.original.length; i++) {
                state.original[i] = (boolean) hiddenReader.invoke(state.bones.get(i));
            }
        }
        // 使用原生显隐，明确处理完整武器子树，不修改手臂和原曲道具。
        // 中文：原生可见性仅拥有已测量的武器子树，从不涉及手臂或PV道具。
        // EN: Native visibility owns only the measured weapon subtree, never arms or PV props.
        for (Object bone : state.bones) {
            hiddenWriter.invoke(bone, true);
        }
    }

    private static void restoreWeapons(Cached c) throws ReflectiveOperationException {
        if (c == null || c.weapons().original == null) {
            return;
        }
        var state = c.weapons();
        for (int i = 0; i < state.original.length; i++) {
            hiddenWriter.invoke(state.bones.get(i), state.original[i]);
        }
        state.original = null;
    }

    private static void restore(Cached c) throws ReflectiveOperationException {
        restoreWeapons(c);
        restoreFace(c);
        restoreBody(c);
    }

    public static synchronized void releaseActor(UUID actor) {
        release(actor);
    }

    public static synchronized void releaseAll() {
        release(null);
        CACHE.clear();
        FACE_FRAMES.clear();
        activeFaces = activeBodies = 0;
    }

    private static void release(UUID actor) {
        try {
            for (var c : CACHE.values()) {
                if (actor == null || actor.equals(c.actor())) {
                    restore(c);
                }
            }
        } catch (ReflectiveOperationException ex) {
            failed = true;
            SteveDanceMod.LOGGER.error("Cannot restore YSM native pose", ex);
        }
    }

    /**
     * 摄像机使用当前不可变运动与已验证的目标骨架，而非工作线程骨骼位置。
     * <p>Camera consumes current immutable motion and the validated target rig, not worker bone positions.</p>
     */
    public static synchronized net.minecraft.world.phys.Vec3 viewEye(
            net.minecraft.world.entity.player.Player actor,
            String modelId,
            ClientPlaybackRuntime.MotionSnapshot snapshot) {
        for (var entry : CACHE.entrySet()) {
            var cached = entry.getValue();
            if (!cached.actor().equals(actor.getUUID())
                    || !cached.modelId().equals(modelId)
                    || cached.profile() == null) {
                continue;
            }
            try {
                double scale = ((Number) renderScale.invoke(entry.getKey())).doubleValue();
                var profile = cached.profile();
                var byProfile = NATIVE_SOLVERS.get(snapshot.nativeMotion());
                var solver = byProfile == null ? null : byProfile.get(profile);
                if (solver == null) {
                    continue;
                }
                var pose = solver.sample(snapshot.nativeMotion(), snapshot.sampleSeconds());
                var skeleton = profile.definition().skeleton();
                var head =
                        skeleton.fkTotal(pose.localRotations(), pose.rootTranslation()).get("Head");
                var reference = skeleton.bind.get("Head");
                var offset =
                        cached.face() == null
                                ? SkeletonRetargeter.Vec.ZERO
                                : reference
                                        .rotation()
                                        .inv()
                                        .turn(
                                                cached.face()
                                                        .eyeReference()
                                                        .sub(reference.position()));
                offset =
                        offset.add(
                                dev.flower.stevedance.client.CameraCalibration.focusOffset(
                                        "ysm:" + cached.modelId()));
                var point = head.position().add(head.rotation().turn(offset)).mul(scale / 16);
                return actor.position()
                        .add(
                                new net.minecraft.world.phys.Vec3(-point.x(), point.y(), point.z())
                                        .yRot(
                                                (float)
                                                        Math.toRadians(
                                                                180
                                                                        - PvPlaybackController
                                                                                .performerYaw())));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("YSM camera scale unavailable", ex);
            }
        }
        return null;
    }

    public record ViewHead(Object bone, boolean hidden) {}

    public static synchronized ViewHead hideViewHead(Object model) {
        try {
            if (!initialized) {
                initialize(model.getClass().getClassLoader());
            }
            Object e = entity.invoke(model);
            if (!(e instanceof LivingEntity actor)
                    || !dev.flower.stevedance.client.StageCamera.hideHead(actor.getUUID())) {
                return null;
            }
            Object mesh = geometry.invoke(model);
            if (mesh == null) {
                return null;
            }
            Object head = mesh.getClass().getMethod("oOOo0Ooo0oOoo0O0OOOOo0oo").invoke(mesh);
            if (head == null) {
                return null;
            }
            boolean hidden =
                    (boolean) head.getClass().getMethod("OOOo0OOOoOO0O00Oo00ooOOO").invoke(head);
            head.getClass().getMethod("Oo0Oo0o00O00Oo0OOoOOoooo", boolean.class).invoke(head, true);
            return new ViewHead(head, hidden);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("YSM first-person head interface unavailable", e);
        }
    }

    public static synchronized void restoreViewHead(ViewHead saved) {
        if (saved == null) {
            return;
        }
        try {
            saved.bone()
                    .getClass()
                    .getMethod("Oo0Oo0o00O00Oo0OOoOOoooo", boolean.class)
                    .invoke(saved.bone(), saved.hidden());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void initialize(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> model =
                Class.forName("com.elfmcys.yesstevemodel.o0000OoOooO0oo0o0oooo0Oo", false, loader);
        Class<?> mesh =
                Class.forName("com.elfmcys.yesstevemodel.OOOO0O0O000O000000oOOO0o", false, loader);
        Class<?> living =
                Class.forName("com.elfmcys.yesstevemodel.oOo00OoOO00O000ooO0Oo00o", false, loader);
        modelId = living.getMethod("ooooO0o00oO0Oo0OOo0O0O0o");
        Class<?> bone =
                Class.forName("com.elfmcys.yesstevemodel.Oo0o00oOOo0OO000000O0oO0", false, loader);
        entity = model.getMethod("OO00OOOOo0Ooo0oo0o0Oo0OO");
        geometry = model.getMethod("OOOoOO000000o0o0oOooo0o0");
        renderScale = model.getMethod("e_");
        boneMap = mesh.getMethod("O00OOOooOoooOoo0o0o0oO0O");
        name = bone.getMethod("oOOo0Ooo0oOoo0O0OOOOo0oo");
        origin = bone.getMethod("OO0ooO00OoO00o0OO0OOooO0");
        hiddenReader = bone.getMethod("OOOo0OOOoOO0O00Oo00ooOOO");
        hiddenWriter = bone.getMethod("Oo0Oo0o00O00Oo0OOoOOoooo", boolean.class);
        pivotX = bone.getMethod("o0OOO0o0o0OOo000oO00o00O");
        pivotY = bone.getMethod("O0OooOo0oOOoOoOoOooO000o");
        pivotZ = bone.getMethod("ooOO000o0O0OOOoO0Oo0o0Oo");
        // 中文：原生姿态缓冲：[0..2]总旋转弧度；[3..5]平移。
        // EN: Native pose buffer: [0..2] total rotation in radians; [3..5] translation.
        // 中文：OO0ooO00OoO00o0OO0OOooO0返回参考旋转，不是位置。
        // EN: OO0ooO00OoO00o0OO0OOooO0 returns the reference ROTATION, not a position.
        rx = bone.getMethod("Oo0Oo0o00O00Oo0OOoOOoooo", float.class);
        ry = bone.getMethod("o0OOooo0o0OO00OoOOOo0o0O", float.class);
        rz = bone.getMethod("O00OOOooOoooOoo0o0o0oO0O", float.class);
        px = bone.getMethod("oOOOo0OOO0ooooo0O00OO0o0", float.class);
        py = bone.getMethod("OOOOo0O0oO0OOo0O0O0Oo0O0", float.class);
        pz = bone.getMethod("Ooooo0oooO0oooOOOoO0000O", float.class);
        sx = bone.getMethod("oo0OoO00oOoo000O0000o0oo", float.class);
        sy = bone.getMethod("oooooooOOoOOoO00OooOo00O", float.class);
        sz = bone.getMethod("Oo00o0OooOOo0ooOoo0oO0o0", float.class);
        writers = new Method[] {rx, ry, rz, px, py, pz, sx, sy, sz};
        readers = new Method[9];
        for (int i = 0; i < 9; i++) {
            readers[i] = bone.getMethod(writers[i].getName());
        }
        initialized = true;
    }
}
