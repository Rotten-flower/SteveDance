package dev.flower.stevedance.client;

import com.google.gson.JsonParser;

import dev.flower.stevedance.content.CameraFit;
import dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Per-model neutral measurements, shared by songs; never uses posed mesh bounds.
 * 按模型校准原作镜头，世界视角和手动环绕不受影响。 */
public final class CameraCalibration {
    public record Measurement(String model, Vec head, Vec focusOffset) {}

    public record Applied(
            String model,
            double ratio,
            Vec offset,
            Vec root,
            Vec sourceHead,
            Vec targetHead,
            double sourceHeight) {}

    private record Reference(UUID actor, String model) {}

    // 中文：来自先前所选模型的过期渲染器不得影响本帧。
    // EN: Hidden YSM controllers may still run while the same actor renders as a maid.
    // 后台模型只能写自己的中立参照，不能覆盖当前角色的校准。
    private static final Map<Reference, Measurement> measurements =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, CameraFit> overrides = new HashMap<>();
    private static final Map<String, Vec> focusOffsets = new HashMap<>();
    private static final Map<String, Vec> framing = new HashMap<>();

    private static Vec framing(String model) {
        return framing.getOrDefault(
                model,
                switch (model) {
                    case "minecraft:player" -> new Vec(.65, .12, 0);
                    case "ysm:default" -> new Vec(.60, .10, 0);
                    case "maid:touhou_little_maid:hakurei_reimu" -> new Vec(1.25, .32, 0);
                    default -> Vec.ZERO;
                });
    }

    public static List<Applied> last = List.of();
    public static boolean enabled = true;
    private static long epoch = -1;
    private static net.minecraft.client.multiplayer.ClientLevel level;

    private static void context() {
        var current = Minecraft.getInstance().level;
        if (level != current) {
            measurements.clear();
            level = current;
        }
        if (epoch != PvPlaybackController.epoch()) {
            last = List.of();
            epoch = PvPlaybackController.epoch();
            reload();
        }
    }

    public static void reload() {
        overrides.clear();
        focusOffsets.clear();
        framing.clear();
        Path path =
                Minecraft.getInstance()
                        .gameDirectory
                        .toPath()
                        .resolve("config/stevedance/camera-calibration.json");
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            if (Files.size(path) > 65536) {
                throw new IllegalArgumentException("Camera calibration file too large");
            }
            var data = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (data.get("schema").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported camera calibration schema");
            }
            Map<String, CameraFit> values = new HashMap<>();
            for (var e : data.getAsJsonObject("models").entrySet()) {
                var p = e.getValue().getAsJsonObject();
                if (p.has("distance") || p.has("height")) {
                    double distance =
                            p.has("distance")
                                    ? p.get("distance").getAsDouble()
                                    : framing(e.getKey()).x();
                    double height =
                            p.has("height")
                                    ? p.get("height").getAsDouble()
                                    : framing(e.getKey()).y();
                    if (!Double.isFinite(distance + height)
                            || distance < 0
                            || distance > 4
                            || Math.abs(height) > 2) {
                        throw new IllegalArgumentException("Invalid camera framing");
                    }
                    framing.put(e.getKey(), new Vec(distance, height, 0));
                }
                if (p.has("headOffset")) {
                    var h = p.getAsJsonArray("headOffset");
                    if (h.size() != 3) {
                        throw new IllegalArgumentException("Head offset requires XYZ");
                    }
                    focusOffsets.put(
                            e.getKey(),
                            new Vec(
                                    h.get(0).getAsDouble(),
                                    h.get(1).getAsDouble(),
                                    h.get(2).getAsDouble()));
                }
                if (!p.has("ratio")) {
                    continue;
                }
                var a = p.getAsJsonArray("offset");
                if (a.size() != 3) {
                    throw new IllegalArgumentException("Camera offset requires XYZ");
                }
                values.put(
                        e.getKey(),
                        new CameraFit(
                                p.get("ratio").getAsDouble(),
                                new Vec(
                                        a.get(0).getAsDouble(),
                                        a.get(1).getAsDouble(),
                                        a.get(2).getAsDouble())));
            }
            overrides.putAll(values);
        } catch (Exception ex) {
            dev.flower.stevedance.SteveDanceMod.LOGGER.warn(
                    "Cannot load camera calibration {}", path, ex);
        }
    }

    /**
     * 根节点跟随原始平移，包括入场和出场；高度保持固定。
     * <p>Model-local pixel coordinates; verified Reimu face point, no guessed eyes for other rigs.</p>
     */
    public static Vec focusOffset(String model) {
        context();
        return focusOffsets.getOrDefault(
                model,
                model.equals("maid:touhou_little_maid:hakurei_reimu")
                        ? new Vec(0, -1.5, -4)
                        : Vec.ZERO);
    }

    public static void measure(UUID actor, String model, Vec head) {
        measure(actor, model, head, Vec.ZERO);
    }

    /** Cache neutral proportions once per actor/model; posed roots still update each frame.
     * 只固定中立校准，不冻结动作、源根轨迹或原作切镜。 */
    public static void measure(UUID actor, String model, Vec head, Vec focusOffset) {
        context();
        measurements.computeIfAbsent(
                new Reference(actor, model), id -> new Measurement(model, head, focusOffset));
    }

    public static void clearMeasurements() {
        measurements.clear();
        last = List.of();
    }

    public static Measurement reference(UUID actor) {
        var entity =
                PvPlaybackController.worldActors().values().stream()
                        .filter(p -> p.getUUID().equals(actor))
                        .findFirst()
                        .orElse(null);
        if (entity == null) {
            return null;
        }
        return measurements.get(
                new Reference(actor, modelKey(PerformerModelSelection.renderingChoice(entity))));
    }

    private static String modelKey(dev.flower.stevedance.core.PerformerModelChoice choice) {
        return choice == null
                ? ""
                : switch (choice.kind()) {
                    case MAID -> "maid:" + choice.modelId();
                    case YSM -> "ysm:" + choice.modelId();
                    default -> "minecraft:player";
                };
    }

    public static PerformanceCameraController.Frame apply(
            PerformanceCameraController.Frame frame, double ms, Vec3 origin, float stageYaw) {
        context();
        if (!enabled) {
            last = List.of();
            return frame;
        }
        var actors = new TreeMap<Integer, net.minecraft.world.entity.player.Player>();
        if (PvPlaybackController.performer() != null) {
            actors.put(0, PvPlaybackController.performer());
        }
        actors.putAll(PvPlaybackController.worldActors());
        List<Applied> used = new ArrayList<>();
        for (var entry : actors.entrySet()) {
            var body = PvPlaybackController.performerMotion(entry.getKey());
            if (body == null
                    || !body.sourceWorldCoordinates
                    || !PvPlaybackController.scene.performerVisible(ms, entry.getKey())) {
                continue;
            }
            // 中文：先前所选模型留下的过期渲染器不得影响本帧。
            // EN: A stale renderer from a previously selected model must not affect this frame.
            var choice = PerformerModelSelection.renderingChoice(entry.getValue());
            String key = modelKey(choice);
            var target = measurements.get(new Reference(entry.getValue().getUUID(), key));
            if (target == null) {
                continue;
            }
            var bind = body.bind;
            var foot =
                    bind.get("LeftFoot").position().add(bind.get("RightFoot").position()).mul(.5);
            Vec sourceHead = bind.get("Head").position().sub(foot);
            CameraFit neutral = CameraFit.between(sourceHead, target.head());
            CameraFit automatic =
                    new CameraFit(neutral.ratio(), neutral.offset().add(target.focusOffset()));
            CameraFit fit = overrides.getOrDefault(key, automatic);
            var pose = body.sample(ms / 1000);
            var hip = pose.get("LeftLeg").position().add(pose.get("RightLeg").position()).mul(.5);
            var neutralHip =
                    bind.get("LeftLeg").position().add(bind.get("RightLeg").position()).mul(.5);
            // 中文：根节点跟随原曲平移，包括入场与退场，身高保持固定。
            // EN: Root follows authored translation, including entrances and exits; height stays
            // fixed.
            Vec root = hip.sub(neutralHip).add(foot);
            // 中文：原曲动作会将部分名义可见的演员停放在负1000米处。
            // EN: Source motion parks some nominally visible actors at -1000 m.
            if (root.y() < -100) {
                continue;
            }
            Vec sourceNow = root;
            Vec targetNow = root.add(fit.offset());
            // 中文：以原曲根节点为中心作相似变换，仅使用固定模型身高与眼位偏移。
            // EN: Similarity about the authored root: fixed model height/eye offsets only.
            // 不跟随头部摆动；镜头不消费上一帧渲染测量。
            used.add(
                    new Applied(
                            key,
                            fit.ratio(),
                            fit.offset(),
                            root,
                            sourceNow,
                            targetNow,
                            sourceHead.y()));
        }
        last = List.copyOf(used);
        if (used.isEmpty()) {
            return frame;
        }
        // 中文：选择距离原作镜头最近的演员，避免远处第二演员拉偏近景。
        // Continuous screen-edge and actor contributions avoid subject-switch jumps.
        // 连续混合人物贡献，不在画面边缘或人数变化时切换镜头公式。
        double yaw=Math.toRadians(frame.yaw()),pitch=Math.toRadians(frame.pitch());
        Vec3 forward=new Vec3(-Math.sin(yaw)*Math.cos(pitch),-Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch));
        Vec3 right=new Vec3(Math.cos(yaw),0,Math.sin(yaw)),up=forward.cross(right),backward=forward.scale(-1);
        double halfHeight=Math.tan(Math.toRadians(frame.fov())*.5);
        double aspect=Minecraft.getInstance().getWindow().getWidth()/(double)Minecraft.getInstance().getWindow().getHeight();
        double roll=Math.toRadians(frame.roll()),sumVisibility=0,sumScore=0;
        double[] visibility=new double[used.size()],distance=new double[used.size()];
        for(int i=0;i<used.size();i++){
            var p=used.get(i);Vec3 head=world(p.root().add(new Vec(0,p.sourceHeight(),0)),stageYaw).add(origin);
            Vec3 relative=head.subtract(frame.position());double depth=relative.dot(forward),radius=p.sourceHeight()*.15;
            double x=relative.dot(right),y=relative.dot(up),safeDepth=Math.max(.01,depth);
            double nx=Math.abs(x*Math.cos(roll)+y*Math.sin(roll))/(safeDepth*halfHeight*aspect+radius);
            double ny=Math.abs(-x*Math.sin(roll)+y*Math.cos(roll))/(safeDepth*halfHeight+radius);
            visibility[i]=(1-smooth(.8,1.15,nx))*(1-smooth(.8,1.15,ny))*smooth(0,.25,depth);
            distance[i]=frame.position().distanceTo(head);sumVisibility+=visibility[i];
        }
        double group=smooth(1,2,sumVisibility);Vec3 correction=Vec3.ZERO;
        for(int i=0;i<used.size();i++){
            var p=used.get(i);double score=visibility[i]/Math.max(.25,distance[i]*distance[i]);
            if(score==0)continue;sumScore+=score;
            double close=closeWeight(distance[i]*halfHeight/Math.tan(Math.toRadians(55)*.5));
            double weight=close+(closeWeight(distance[i])-close)*group;
            Vec3 source=world(p.sourceHead(),stageYaw).add(origin),target=world(p.targetHead(),stageYaw).add(origin);
            Vec pad=framing(p.model());
            Vec3 adapted=target.add(frame.position().subtract(source).scale(p.ratio())).add(backward.scale(pad.x())).add(0,pad.y(),0);
            Vec3 eye=frame.position().lerp(adapted,weight);
            double height=p.sourceHeight()*p.ratio(),radius=Math.max(.28,Math.min(.55,height*.22));
            Vec3 excluded=outside(outside(eye,target.add(0,height,0),radius,backward),target.add(0,height*.65,0),radius*1.1,backward);
            eye=eye.lerp(excluded,weight);
            correction=correction.add(eye.subtract(frame.position()).scale(score));
        }
        Vec3 eye=sumScore==0?frame.position():frame.position().add(correction.scale(Math.min(1,sumVisibility)/sumScore));
        return new PerformanceCameraController.Frame(eye,frame.yaw(),frame.pitch(),frame.roll(),frame.fov(),frame.perspective(),frame.distance());
    }
    private static double smooth(double start,double end,double value){double t=Math.max(0,Math.min(1,(value-start)/(end-start)));return t*t*(3-2*t);}
    /**
     * 连续近景混合不积累状态，因此跳转与镜头切断不会产生滞后。
     * <p>Continuous close-up blend; no accumulated state, so seeking/cuts cannot lag.</p>
     */
    public static double closeWeight(double distance) {
        double t = Math.max(0, Math.min(1, (3 - distance) / 1.5));
        return t * t * (3 - 2 * t);
    }

    private static Vec3 outside(Vec3 eye, Vec3 center, double radius, Vec3 backward) {
        double distance = eye.distanceTo(center);
        if (distance >= radius) {
            return eye;
        }
        // 中文：沿镜头自身后轴退出，同时保持原作旋转。
        // EN: Exit along the camera's own backward axis, retaining authored rotation.
        Vec3 delta = eye.subtract(center);
        double projection = delta.dot(backward);
        double amount =
                -projection
                        + Math.sqrt(
                                Math.max(
                                        0,
                                        projection * projection
                                                + radius * radius
                                                - delta.lengthSqr()));
        return eye.add(backward.scale(amount));
    }

    private static Vec3 world(Vec p, float yaw) {
        return new Vec3(-p.x(), p.y(), -p.z()).yRot((float) -Math.toRadians(yaw));
    }
}
