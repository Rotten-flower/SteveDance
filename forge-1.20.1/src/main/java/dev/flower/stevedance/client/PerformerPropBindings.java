package dev.flower.stevedance.client;

import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.core.PropBinding;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 源接触门和每个道具的握持模式由YSM和Steve共享。
 * <p>The source contact gate and each prop's grip mode are shared by YSM and Steve.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class PerformerPropBindings {
    private record Pending(
            Matrix4f modelView,
            int actor,
            PropBinding.Fit guitar,
            PropBinding.Fit pick,
            PropBinding.Fit torso,
            Map<String, PropBinding.Fit> fits,
            long epoch) {}

    private static final Map<Integer, Pending> PENDING = new LinkedHashMap<>();

    /** One-way adaptation: source wrist motion drives the prop; props never re-pose arms.
     * 保留源手腕局部运动，只校准固定掌心，不反向扭动手臂追求零误差。 */
    public static PropBinding.Fit instrumentFit(
            Map<String, Transform> source, Map<String, Transform> sockets, double scale) {
        Vec sourceChest =
                source.get("LeftArm").position().add(source.get("RightArm").position()).mul(.5);
        Vec targetChest =
                sockets.get("LeftArm").position().add(sockets.get("RightArm").position()).mul(.5);
        return PropBinding.authored(
                source.get("LeftHand").position(),
                source.get("RightHand").position(),
                sourceChest,
                sockets.get("LeftHand").position(),
                sockets.get("RightHand").position(),
                targetChest,
                scale);
    }

    public static void render(
            PoseStack stack, int actor, Map<String, Transform> sockets, double scale) {
        var body = PvPlaybackController.performerMotion(actor);
        if (body == null) {
            return;
        }
        var source = body.sample(PvPlaybackController.timeMs() / 1000);
        if (!sockets.keySet()
                .containsAll(
                        List.of("LeftHand", "RightHand", "UpperBody", "LeftArm", "RightArm"))) {
            return;
        }
        // 身高为主，臂长仅作有限修正；移除渲染矩阵已经包含的缩放，避免重复缩小。
        // 中文：中性测量使道具尺寸在整个动画过程中保持恒定。
        // EN: Neutral measurements keep prop size constant throughout the animation.
        var player =
                actor == 0
                        ? PvPlaybackController.performer()
                        : PvPlaybackController.worldActors().get(actor);
        var neutral =
                player == null
                        ? null
                        : dev.flower.stevedance.client.CameraCalibration.reference(
                                player.getUUID());
        double basis = stack.last().pose().getScale(new org.joml.Vector3f()).x();
        if (neutral != null && basis > 1e-6) {
            Vec foot =
                    body.bind
                            .get("LeftFoot")
                            .position()
                            .add(body.bind.get("RightFoot").position())
                            .mul(.5);
            double sourceHeight = body.bind.get("Head").position().sub(foot).y();
            if (sourceHeight > 1e-6) {
                scale =
                        PropBinding.proportionalScale(
                                neutral.head().y() / sourceHeight / basis, scale);
            }
        }
        Map<String, PropBinding.Fit> fits = new HashMap<>();
        for (String role : List.of("LeftHand", "RightHand", "UpperBody")) {
            fits.put(role, PropBinding.single(source.get(role), sockets.get(role), scale));
        }
        // 躯干原点可能位于腰部；两肩中点才是跨模型一致的上身参照。
        // 中文：躯干枢轴因骨架而异。肩部中心提供相同的解剖学参考。
        // EN: Torso pivots vary between rigs. Shoulder centers give the same anatomical guide.
        Vec sourceChest =
                source.get("LeftArm").position().add(source.get("RightArm").position()).mul(.5);
        Vec targetChest =
                sockets.get("LeftArm").position().add(sockets.get("RightArm").position()).mul(.5);
        var guitar = instrumentFit(source, sockets, scale);
        fits.put(
                "UpperBody",
                PropBinding.single(
                        new Transform(sourceChest, source.get("UpperBody").rotation()),
                        new Transform(targetChest, sockets.get("UpperBody").rotation()),
                        scale));
        var bindings = PvPlaybackController.scene.itemBindings;
        if (bindings != null) {
            for (var binding : bindings) {
                if (binding.actor() == actor) {
                    if ("two-hand-instrument".equals(binding.fitMode())) {
                        fits.put(YsmPropAdapter.fitKey(binding), guitar);
                    }
                }
            }
        }
        if (actor == 0) {
            YsmPropAdapter.secondaryError = guitar.secondaryError();
            YsmPropAdapter.lastFit = guitar;
            YsmPropAdapter.lastSockets = Map.copyOf(sockets);
            YsmPropAdapter.lastRenderBasis = stack.last().pose().get(new float[16]);
        }
        // 中文：将直接GPU绘制延迟到Minecraft和Iris刷新实体缓冲区之后。
        // EN: Defer direct GPU draws until Minecraft and Iris have flushed entity buffers.
        // 延迟到 Minecraft/Iris 提交完实体缓冲后再直接绘制，避免道具被人物缓冲覆盖。
        PENDING.put(
                actor,
                new Pending(
                        new Matrix4f(stack.last().pose()),
                        actor,
                        guitar,
                        fits.get("RightHand"),
                        fits.get("UpperBody"),
                        new HashMap<>(fits),
                        PvPlaybackController.epoch()));
    }

    public static void clearPending() {
        PENDING.clear();
    }

    public static boolean hasPending(int actor) {
        var p = PENDING.get(actor);
        return p != null && p.epoch() == PvPlaybackController.epoch();
    }

    public static void renderPending() {
        if (!PvPlaybackController.active()) {
            PENDING.clear();
            return;
        }
        List<Pending> pendingDraws = List.copyOf(PENDING.values());
        PENDING.clear();
        for (Pending pending : pendingDraws) {
            if (pending.epoch() != PvPlaybackController.epoch()) {
                continue;
            }
            PoseStack pose = new PoseStack();
            pose.mulPoseMatrix(new Matrix4f(pending.modelView()));
            PvStageRenderer.renderProps(
                    pose,
                    pending.actor(),
                    pending.fits(),
                    pending.guitar(),
                    pending.pick(),
                    pending.torso());
            PvStageRenderer.renderItemProps(pose, pending.actor(), pending.fits());
        }
    }
}
