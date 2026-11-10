package dev.flower.stevedance.client;

import dev.flower.stevedance.StageNetwork;
import dev.flower.stevedance.core.VmdCamera;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 将歌曲应用于Minecraft的关卡摄像机，相对于世界舞台方块。
 * <p>Applies the song to Minecraft's level camera, relative to a world stage block.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class PerformanceCameraController {
    public record Frame(
            Vec3 position,
            float yaw,
            float pitch,
            float roll,
            float fov,
            boolean perspective,
            float distance) {}

    public static VmdCamera.Shot lastShot;
    public static Vec3 origin = Vec3.ZERO;
    private static float stageYaw, orbitYaw = 30, orbitPitch = 16, orbitDistance = 11;
    private static String dimension = "";
    private static CameraType previous;
    private static boolean running, overview;

    public static void begin(StageNetwork.State state) {
        stop();
        Minecraft mc = Minecraft.getInstance();
        origin =
                new Vec3(
                        state.stage().getX() + .5,
                        state.stage().getY() + .125,
                        state.stage().getZ() + .5);
        stageYaw = state.yaw();
        dimension = state.dimension();
        previous = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        overview = false;
        running = true;
    }

    public static void stop() {
        if (previous != null) {
            Minecraft.getInstance().options.setCameraType(previous);
        }
        previous = null;
        running = false;
        overview = false;
    }

    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return dev.flower.stevedance.client.PhotoMode.active()
                || WorldPreviewClient.local()
                || PvPlaybackController.cameraActive()
                || running
                        && mc.level != null
                        && mc.player != null
                        && ClientPlaybackRuntime.session != null
                        && mc.level.dimension().location().toString().equals(dimension)
                        && mc.screen instanceof MotionPackPlayScreen;
    }

    public static void toggle() {
        overview = !overview;
    }

    public static void overview(boolean enabled) {
        overview = enabled;
    }

    public static boolean overview() {
        return overview;
    }

    public static void drag(double dx, double dy) {
        orbitYaw += (float) dx * .5f;
        orbitPitch = Math.max(-20, Math.min(75, orbitPitch + (float) dy * .3f));
    }

    public static void zoom(double amount) {
        orbitDistance = Math.max(3, Math.min(24, orbitDistance - (float) amount));
    }

    private static boolean frameOpen, sampled;
    private static Frame frameCamera;

    /** One immutable camera for all passes of a rendered frame, including shader passes.
     * 每帧只采样一次，位置、视角、FOV 和投影共用结果。 */
    public static void beginFrame() {
        frameOpen = true;
        sampled = false;
        frameCamera = null;
    }

    public static void endFrame() {
        frameOpen = false;
        sampled = false;
        frameCamera = null;
    }

    public static Frame sample() {
        if (!frameOpen) {
            return computeFrame();
        }
        if (!sampled) {
            frameCamera = computeFrame();
            sampled = true;
        }
        return frameCamera;
    }

    private static Frame computeFrame() {
        if (dev.flower.stevedance.client.PhotoMode.active()) {
            return dev.flower.stevedance.client.PhotoMode.camera();
        }
        if (WorldPreviewClient.local()) {
            return WorldPreviewClient.camera();
        }
        if (PvPlaybackController.active()) {
            return PvPlaybackController.camera();
        }
        double seconds = Math.max(0, ClientPlaybackRuntime.timeMs() / 1000.0);
        VmdCamera.Shot shot =
                overview
                        ? new VmdCamera.Shot(
                                0,
                                11,
                                0,
                                (float) -Math.toRadians(orbitPitch),
                                (float) Math.toRadians(orbitYaw),
                                0,
                                -orbitDistance * 10,
                                55,
                                true)
                        : ClientPlaybackRuntime.camera == null
                                ? new VmdCamera.Shot(0, 11, 0, -.08f, 0, 0, -35, 40, true)
                                : ClientPlaybackRuntime.camera.sample(seconds);
        lastShot = shot;
        Quaternionf rotation =
                new Quaternionf()
                        .rotationY((float) -Math.toRadians(stageYaw))
                        .mul(new Quaternionf().rotationXYZ(shot.rx(), shot.ry(), -shot.rz()));
        Vector3f target =
                new Vector3f(shot.x(), shot.y(), -shot.z())
                        .mul(.1f)
                        .rotateY((float) -Math.toRadians(stageYaw));
        Vector3f eye = new Vector3f(0, 0, -shot.distance() * .1f).rotate(rotation).add(target);
        Vector3f direction = new Vector3f(target).sub(eye).normalize(),
                up = new Vector3f(0, 1, 0).rotate(rotation);
        Vector3f right = new Vector3f(direction).cross(new Vector3f(0, 1, 0)).normalize(),
                baseUp = new Vector3f(right).cross(direction).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z)),
                pitch =
                        (float)
                                -Math.toDegrees(
                                        Math.atan2(
                                                direction.y, Math.hypot(direction.x, direction.z))),
                roll = (float) Math.toDegrees(Math.atan2(up.dot(right), up.dot(baseUp)));
        return new Frame(
                origin.add(eye.x, eye.y, eye.z),
                yaw,
                pitch,
                roll,
                shot.fov(),
                shot.perspective(),
                shot.distance());
    }

    @SubscribeEvent
    public static void lens(ViewportEvent.ComputeFov event) {
        if (active()) {
            event.setFOV(sample().fov);
        }
    }

    @SubscribeEvent
    public static void angles(ViewportEvent.ComputeCameraAngles event) {
        if (active()) {
            var frame = sample();
            event.setYaw(frame.yaw);
            event.setPitch(frame.pitch);
            event.setRoll(frame.roll);
        }
    }

    @SubscribeEvent
    public static void hud(RenderGuiOverlayEvent.Pre event) {
        if (active()) {
            event.setCanceled(true);
        }
    }
}
