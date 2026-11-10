package dev.flower.stevedance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Camera movement never moves the real player. 镜头移动不改变真实玩家位置。 */
public final class StageCamera {
    public enum Mode {
        AUTHORED,
        FRONT,
        LEFT,
        RIGHT,
        BACK,
        TOP,
        ORBIT,
        ACTOR_EYES,
        FREE
    }

    public static final double FREE_RADIUS = 24;
    private static Mode mode = Mode.AUTHORED, returnMode = Mode.AUTHORED;
    private static Vec3 free = Vec3.ZERO;
    private static float yaw, pitch;
    private static int actorSlot;

    public static Mode mode() {
        return mode;
    }

    public static boolean eye() {
        return mode == Mode.ACTOR_EYES;
    }

    public static boolean hideHead(UUID id) {
        return PvPlaybackController.active()
                && eye()
                && PvPlaybackController.actor(id) == selectedActor();
    }

    public static Component label(Mode value) {
        return Component.translatable(
                "ui.stevedance.camera." + value.name().toLowerCase(Locale.ROOT));
    }

    public static List<Integer> visibleActors() {
        if (!PvPlaybackController.active()) {
            return List.of();
        }
        return PvPlaybackController.worldActors().keySet().stream()
                .filter(
                        i ->
                                PvPlaybackController.scene.performerVisible(
                                                PvPlaybackController.timeMs(), i)
                                        && onStage(i))
                .sorted()
                .toList();
    }

    private static boolean onStage(int slot) {
        var motion = PvPlaybackController.performerMotion(slot);
        if (motion == null || !motion.sourceWorldCoordinates) {
            return true;
        }
        var pose = motion.sample(PvPlaybackController.timeMs() / 1000);
        double now =
                (pose.get("LeftLeg").position().y() + pose.get("RightLeg").position().y()) * .5;
        double rest =
                (motion.bind.get("LeftLeg").position().y()
                                + motion.bind.get("RightLeg").position().y())
                        * .5;
        return now - rest > -100;
    }

    public static int selectedActor() {
        var visible = visibleActors();
        if (!visible.contains(actorSlot) && !visible.isEmpty()) {
            actorSlot = visible.get(0);
        }
        return actorSlot;
    }

    public static void selectActor(int slot) {
        if (!visibleActors().contains(slot)) {
            return;
        }
        actorSlot = slot;
        begin(Mode.ACTOR_EYES);
    }

    public static void cycleActor() {
        var visible = visibleActors();
        if (visible.isEmpty()) {
            return;
        }
        int index = visible.indexOf(selectedActor());
        actorSlot = visible.get((index + 1) % visible.size());
        begin(mode == Mode.AUTHORED || mode == Mode.FREE ? Mode.ACTOR_EYES : mode);
        announce();
    }

    private static void announce() {
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("ui.stevedance.camera.actor", selectedActor() + 1),
                    true);
        }
    }

    public static void reset() {
        actorSlot = 0;
        returnMode = Mode.AUTHORED;
        mode = Mode.AUTHORED;
        yaw = pitch = 0;
    }

    public static void begin(Mode value) {
        if (value == Mode.FREE && mode != Mode.FREE && PvPlaybackController.active()) {
            var f = PerformanceCameraController.sample();
            free = bound(f.position());
            yaw = f.yaw();
            pitch = f.pitch();
            returnMode = mode;
        } else if (value != Mode.FREE) {
            yaw = 0;
            pitch = 0;
        }
        mode = value;
    }

    public static void toggleFree() {
        begin(mode == Mode.FREE ? returnMode : Mode.FREE);
    }

    public static void cycle() {
        Mode base = mode == Mode.FREE ? returnMode : mode;
        begin(Mode.values()[(base.ordinal() + 1) % (Mode.values().length - 1)]);
    }

    public static void drag(double dx, double dy) {
        if (mode == Mode.FREE || mode == Mode.ORBIT || eye()) {
            yaw += (float) dx * .35f;
            pitch = Math.max(-89, Math.min(89, pitch + (float) dy * .3f));
        }
    }

    public static PerformanceCameraController.Frame frame() {
        if (!PvPlaybackController.active() || mode == Mode.AUTHORED) {
            return null;
        }
        var mc = Minecraft.getInstance();
        float facing = PvPlaybackController.performerYaw();
        if (mode == Mode.FREE) {
            return at(free, yaw, pitch);
        }
        if (eye()) {
            int slot = selectedActor();
            var actor = PvPlaybackController.worldActors().get(slot);
            if (actor == null) {
                return null;
            }
            var body = PvPlaybackController.performerMotion(slot);
            float headYaw = facing, headPitch = 0;
            if (body != null && body.sourceWorldCoordinates) {
                var pose = body.sample(PvPlaybackController.timeMs() / 1000);
                var direction =
                        pose.get("Head")
                                .rotation()
                                .mul(body.bind.get("Head").rotation().inv())
                                .turn(
                                        new dev.flower.stevedance.core.SkeletonRetargeter.Vec(
                                                0, 0, 1));
                var d =
                        new Vec3(-direction.x(), direction.y(), -direction.z())
                                .yRot((float) -Math.toRadians(facing - 180));
                headYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                headPitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            }
            return at(
                    actorEye(slot), headYaw + yaw, Math.max(-89, Math.min(89, headPitch + pitch)));
        }
        Vec3 target = actorEye(selectedActor()).add(0, -.4, 0);
        double angle =
                switch (mode) {
                    case LEFT -> 90;
                    case RIGHT -> -90;
                    case BACK -> 180;
                    case ORBIT -> yaw + PvPlaybackController.timeMs() * .006;
                    default -> 0;
                };
        double radians = Math.toRadians(facing + angle);
        Vec3 eye =
                mode == Mode.TOP
                        ? target.add(.01, 6, 0)
                        : target.add(
                                -Math.sin(radians) * 5,
                                mode == Mode.ORBIT ? 1 + Math.sin(Math.toRadians(pitch)) * 5 : 1,
                                Math.cos(radians) * 5);
        return look(eye, target);
    }

    public static Vec3 actorEye(int slot) {
        var actor = PvPlaybackController.worldActors().get(slot);
        if (actor == null) {
            return PvPlaybackController.origin().add(0, 1.6, 0);
        }
        var snapshot = ClientPlaybackRuntime.MOTION_SNAPSHOTS.get(actor.getUUID());
        var choice = PerformerModelSelection.renderingChoice(actor);
        if (snapshot != null && snapshot.nativeMotion() != null && choice != null) {
            if (choice.kind() != dev.flower.stevedance.core.PerformerModelChoice.Kind.MAID
                    && choice.kind() != dev.flower.stevedance.core.PerformerModelChoice.Kind.YSM) {
                return VanillaPlayerPoseAdapter.viewEye(actor, snapshot);
            }
            if (choice.kind() == dev.flower.stevedance.core.PerformerModelChoice.Kind.YSM) {
                var point = YsmAnimationAdapter.viewEye(actor, choice.modelId(), snapshot);
                if (point != null) {
                    return point;
                }
            }
            if (choice.kind() == dev.flower.stevedance.core.PerformerModelChoice.Kind.MAID) {
                var point =
                        dev.flower.stevedance.content.MaidModels.viewEye(
                                actor, choice.modelId(), snapshot);
                if (point != null) {
                    return point;
                }
            }
        }
        var ref = CameraCalibration.reference(actor.getUUID());
        double height = ref == null ? actor.getEyeHeight() : ref.head().y() + ref.focusOffset().y();
        var body = PvPlaybackController.performerMotion(slot);
        if (body == null || !body.sourceWorldCoordinates) {
            return actor.position().add(0, height, 0);
        }
        var h = body.sample(PvPlaybackController.timeMs() / 1000).get("Head").position();
        var neutral = body.bind.get("Head").position();
        return PvPlaybackController.origin()
                .add(
                        new Vec3(-h.x(), h.y() - neutral.y() + height, -h.z())
                                .yRot(
                                        (float)
                                                -Math.toRadians(
                                                        PvPlaybackController.performerYaw()
                                                                - 180)));
    }

    /** Pick authored actor positions, not the stationary controller entities. 按实际演出位置拾取角色。 */
    public static boolean pickActor() {
        if (mode != Mode.FREE) {
            return false;
        }
        var camera = PerformanceCameraController.sample();
        Vec3 start = camera.position(),
                end =
                        start.add(
                                Vec3.directionFromRotation(camera.pitch(), camera.yaw())
                                        .scale(FREE_RADIUS * 2));
        double nearest = Double.POSITIVE_INFINITY;
        int picked = -1;
        for (int slot : visibleActors()) {
            var eye = actorEye(slot);
            var actor = PvPlaybackController.worldActors().get(slot);
            var ref = CameraCalibration.reference(actor.getUUID());
            double height = Math.max(.4, ref == null ? actor.getEyeHeight() : ref.head().y());
            var box =
                    new AABB(
                            eye.x - .45,
                            eye.y - height,
                            eye.z - .45,
                            eye.x + .45,
                            eye.y + .35,
                            eye.z + .45);
            var hit = box.contains(start) ? Optional.of(start) : box.clip(start, end);
            if (hit.isPresent()) {
                double distance = hit.get().distanceToSqr(start);
                if (distance < nearest) {
                    nearest = distance;
                    picked = slot;
                }
            }
        }
        if (picked < 0) {
            return false;
        }
        selectActor(picked);
        announce();
        return true;
    }

    public static void move(double forward, double right, double up, double distance) {
        if (mode != Mode.FREE) {
            return;
        }
        Vec3 facing = Vec3.directionFromRotation(pitch, yaw),
                side = new Vec3(-Math.cos(Math.toRadians(yaw)), 0, -Math.sin(Math.toRadians(yaw)));
        Vec3 step = facing.scale(forward).add(side.scale(right)).add(0, up, 0);
        if (step.lengthSqr() > 1) {
            step = step.normalize();
        }
        free = bound(free.add(step.scale(distance)));
    }

    private static Vec3 bound(Vec3 point) {
        Vec3 center = PvPlaybackController.origin().add(0, 1.5, 0), delta = point.subtract(center);
        return delta.lengthSqr() > FREE_RADIUS * FREE_RADIUS
                ? center.add(delta.normalize().scale(FREE_RADIUS))
                : point;
    }

    public static PerformanceCameraController.Frame look(Vec3 eye, Vec3 target) {
        var d = target.subtract(eye);
        return at(
                eye,
                (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    }

    private static PerformanceCameraController.Frame at(Vec3 p, float y, float x) {
        return new PerformanceCameraController.Frame(p, y, x, 0, 55, true, 1);
    }
}
