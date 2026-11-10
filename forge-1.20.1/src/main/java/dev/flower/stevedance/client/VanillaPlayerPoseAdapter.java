package dev.flower.stevedance.client;

import dev.flower.stevedance.core.SkeletonRetargeter;
import dev.flower.stevedance.core.VmdMotion;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;

public final class VanillaPlayerPoseAdapter {
    /**
     * 与 YSM 桥接使用相同的源头到脚踝校准；仅渲染，位于脚部周围。
     * <p>Same source head-to-ankle calibration used by the YSM bridge; render-only, around the feet.</p>
     */
    public static float performanceScale(LivingEntity player) {
        return 1; // 中文：保留玩家模型的常规世界尺寸。
        // EN: Preserve the player model's ordinary world size.
    }

    public static void reset(PlayerModel<?> m) {
        for (ModelPart p :
                new ModelPart[] {
                    m.head,
                    m.hat,
                    m.body,
                    m.leftArm,
                    m.rightArm,
                    m.leftLeg,
                    m.rightLeg,
                    m.jacket,
                    m.leftSleeve,
                    m.rightSleeve,
                    m.leftPants,
                    m.rightPants
                }) {
            p.resetPose();
        }
    }

    public static double time(LivingEntity player, ClientPlaybackRuntime.Dance dance) {
        var self = net.minecraft.client.Minecraft.getInstance().player;
        return Math.max(
                0,
                self != null
                                && self.getUUID().equals(player.getUUID())
                                && ClientPlaybackRuntime.session != null
                        ? ClientPlaybackRuntime.timeMs() / 1000.0
                        : (System.nanoTime() - dance.startNanos()) / 1e9);
    }

    public static void apply(PlayerModel<?> model, LivingEntity player) {
        var choice = PerformerModelSelection.renderingChoice(player);
        if (choice != null
                && (choice.kind() == dev.flower.stevedance.core.PerformerModelChoice.Kind.MAID
                        || choice.kind()
                                == dev.flower.stevedance.core.PerformerModelChoice.Kind.YSM)) {
            return;
        }

        var snapshot = ClientPlaybackRuntime.MOTION_SNAPSHOTS.get(player.getUUID());
        if (snapshot != null && snapshot.nativeMotion() != null) {
            dev.flower.stevedance.client.CameraCalibration.measure(
                    player.getUUID(),
                    "minecraft:player",
                    new SkeletonRetargeter.Vec(0, 1.5 * .9375 * performanceScale(player), 0));
            applyNative(
                    model,
                    snapshot,
                    performanceScale(player),
                    PvPlaybackController.actor(player.getUUID()));
            return;
        }
        var dance = ClientPlaybackRuntime.DANCES.get(player.getUUID());
        if (dance == null || dance.motion() == null) {
            return;
        }
        double seconds = time(player, dance);
        if (seconds > dance.pack().chart().durationMs() / 1000.0) {
            return;
        }
        VmdMotion m = dance.motion();
        rotate(model.body, m.sample("上半身", seconds));
        rotate(model.head, m.sample("頭", seconds));
        rotate(model.leftArm, m.sample("左腕", seconds));
        rotate(model.rightArm, m.sample("右腕", seconds));
        rotate(model.leftLeg, m.sample("左足", seconds));
        rotate(model.rightLeg, m.sample("右足", seconds));
        // 中文：Steve 缺少膝关节；使用 IK 目标来保持腿部运动方向。
        // EN: Steve lacks knee joints; use IK targets to preserve the direction of leg movement.
        var left = m.sample("左足ＩＫ", seconds);
        var right = m.sample("右足ＩＫ", seconds);
        model.leftLeg.xRot += Math.max(-.65f, Math.min(.65f, left.z() * .12f));
        model.rightLeg.xRot += Math.max(-.65f, Math.min(.65f, right.z() * .12f));
        var center = m.sample("センター", seconds);
        float x = center.x() * .65f, y = -center.y() * .65f, z = -center.z() * .65f;
        model.body.setPos(x, y, z);
        model.head.setPos(x, y, z);
        model.leftArm.setPos(5 + x, 2 + y, z);
        model.rightArm.setPos(-5 + x, 2 + y, z);
        model.leftLeg.setPos(1.9f + x, 12 + y, z);
        model.rightLeg.setPos(-1.9f + x, 12 + y, z);
        model.hat.copyFrom(model.head);
        model.jacket.copyFrom(model.body);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftPants.copyFrom(model.leftLeg);
        model.rightPants.copyFrom(model.rightLeg);
    }

    private static void rotate(ModelPart part, VmdMotion.Pose pose) {
        float[] r = pose.euler();
        part.xRot = -r[0];
        part.yRot = -r[1];
        part.zRot = r[2];
    }

    private static net.minecraft.client.model.PlayerModel<
                    net.minecraft.client.player.AbstractClientPlayer>
            viewModel;

    /**
     * 在相机设置前评估相同的姿势化身体，绝不用上一帧的渲染结果。
     * <p>Evaluate the same posed body before camera setup, never last frame's render.</p>
     */
    public static net.minecraft.world.phys.Vec3 viewEye(
            net.minecraft.world.entity.player.Player actor,
            ClientPlaybackRuntime.MotionSnapshot snapshot) {
        if (viewModel == null) {
            viewModel =
                    new net.minecraft.client.model.PlayerModel<>(
                            net.minecraft.client.Minecraft.getInstance()
                                    .getEntityModels()
                                    .bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER),
                            false);
        }
        applyNative(
                viewModel,
                snapshot,
                performanceScale(actor),
                PvPlaybackController.actor(actor.getUUID()));
        var point =
                dev.flower.stevedance.client.SteveSockets.sample(viewModel).get("eyes").position();
        double scale = .9375 * performanceScale(actor);
        return actor.position()
                .add(
                        new net.minecraft.world.phys.Vec3(
                                        -point.x() * scale,
                                        (1.501 - point.y()) * scale,
                                        point.z() * scale)
                                .yRot(
                                        (float)
                                                Math.toRadians(
                                                        180
                                                                - PvPlaybackController
                                                                        .performerYaw())));
    }

    static void applyNative(
            PlayerModel<?> m,
            ClientPlaybackRuntime.MotionSnapshot snapshot,
            float renderScale,
            int actor) {
        reset(m);
        var motion = snapshot.nativeMotion();
        var p = motion.sample(snapshot.sampleSeconds());
        var body =
                nativeFrame(
                        p.get("UpperBody")
                                .rotation()
                                .mul(motion.bind.get("UpperBody").rotation().inv()));
        var pelvis =
                nativeFrame(
                        p.get("DownBody")
                                .rotation()
                                .mul(motion.bind.get("DownBody").rotation().inv()));
        nativeRotation(m.body, body);
        nativeRotation(
                m.head,
                nativeFrame(
                        p.get("Head").rotation().mul(motion.bind.get("Head").rotation().inv())));
        var bindHip =
                motion.bind
                        .get("LeftLeg")
                        .position()
                        .add(motion.bind.get("RightLeg").position())
                        .mul(.5);
        double floor =
                (motion.bind.get("LeftFoot").position().y()
                                + motion.bind.get("RightFoot").position().y())
                        * .5;
        double scale = .75 / Math.max(.1, bindHip.y() - floor);
        var sourceHip = p.get("LeftLeg").position().add(p.get("RightLeg").position()).mul(.5);
        // 中文：仅补偿根运动：缩小身体不得缩小其编辑路径。
        // EN: Compensate only root movement: shrinking the body must not shrink its authored path.
        double referenceY = 24 - (bindHip.y() - floor) * 16 * scale;
        double worldUnits = 16 / (.9375 * renderScale);
        var hip =
                new SkeletonRetargeter.Vec(
                        sourceHip.x() * worldUnits,
                        referenceY - (sourceHip.y() - bindHip.y()) * worldUnits,
                        -sourceHip.z() * worldUnits);
        // 中文：原版肢体是独立的模型部件。从相同的
        // EN: Vanilla limbs are separate model parts. Derive their pivots from the same
        // 中文：躯干/骨盆框架推导其枢轴，使弓不会分离肩部、头部和臀部。
        // EN: torso/pelvis frames so a bow cannot separate shoulders, head and hips.
        var top = hip.sub(body.turn(new SkeletonRetargeter.Vec(0, 12, 0)));
        nativePosition(m.body, top);
        nativePosition(m.head, top);
        // 中文：地面间隙相对于编辑根，包括舞台外运动。
        // EN: Floor clearance is relative to the authored root, including off-stage motion.
        // 中文：使用实体的地面会把停在舞台下方的演员拉回视野。
        // EN: Using the entity's floor would pull actors parked below the stage back into view.
        double sourceFloor = 24 - (sourceHip.y() - bindHip.y()) * worldUnits;
        double bottom = sourceFloor;
        for (String side : java.util.List.of("Left", "Right")) {
            boolean left = side.equals("Left");
            var arm = left ? m.leftArm : m.rightArm;
            var leg = left ? m.leftLeg : m.rightLeg;
            var shoulder = top.add(body.turn(new SkeletonRetargeter.Vec(left ? 5 : -5, 2, 0)));
            var legPivot =
                    hip.add(pelvis.turn(new SkeletonRetargeter.Vec(left ? 1.9 : -1.9, 0, 0)));
            var armRotation =
                    SkeletonRetargeter.Quat.between(
                            new SkeletonRetargeter.Vec(0, 1, 0),
                            nativeVector(
                                    p.get(side + "Hand")
                                            .position()
                                            .sub(p.get(side + "Arm").position())));
            var legRotation =
                    SkeletonRetargeter.Quat.between(
                            new SkeletonRetargeter.Vec(0, 1, 0),
                            nativeVector(
                                    p.get(side + "Foot")
                                            .position()
                                            .sub(p.get(side + "Leg").position())));
            nativePosition(arm, shoulder);
            nativePosition(leg, legPivot);
            nativeRotation(arm, armRotation);
            nativeRotation(leg, legRotation);
            bottom =
                    Math.max(
                            bottom,
                            legPivot.add(legRotation.turn(new SkeletonRetargeter.Vec(0, 12, 0)))
                                    .y());
        }
        // 中文：刚性腿无法像源膝盖那样折叠。抬起连接的骨骼
        // EN: A rigid leg cannot fold like the source knee. Lift the connected skeleton
        // 中文：使其足够高，以保持两条定长腿位于玩家地平面之上。
        // EN: enough to keep both fixed-length legs above the player's floor plane.
        float lift = (float) (bottom - sourceFloor);
        for (ModelPart part :
                new ModelPart[] {m.head, m.body, m.leftArm, m.rightArm, m.leftLeg, m.rightLeg}) {
            part.y -= lift;
        }
        m.hat.copyFrom(m.head);
        m.jacket.copyFrom(m.body);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftPants.copyFrom(m.leftLeg);
        m.rightPants.copyFrom(m.rightLeg);
    }

    // 中文：PlayerRenderer 翻转模型 X/Y，然后使用表演者偏航 +180。在其局部
    // EN: PlayerRenderer flips model X/Y, then uses performer yaw +180. In its local
    // 中文：ModelParts 源中 +X 保持 +X，源 +Y 指向上方（-Y），+Z 为前方（-Z）。
    // EN: ModelParts source +X stays +X, source +Y points up (-Y), and +Z is front (-Z).
    // 中文：此处不能使用 YSM 的反射绑定框架：它会反转 Steve 的手臂深度。
    // EN: YSM's reflected rig frame cannot be used here: it reverses Steve's arm depth.
    private static SkeletonRetargeter.Vec nativeVector(SkeletonRetargeter.Vec source) {
        return new SkeletonRetargeter.Vec(source.x(), -source.y(), -source.z());
    }

    private static SkeletonRetargeter.Quat nativeFrame(SkeletonRetargeter.Quat source) {
        return new SkeletonRetargeter.Quat(source.x(), -source.y(), -source.z(), source.w());
    }

    private static void nativePosition(ModelPart part, SkeletonRetargeter.Vec p) {
        part.setPos((float) p.x(), (float) p.y(), (float) p.z());
    }

    private static void nativeRotation(ModelPart part, SkeletonRetargeter.Quat q) {
        var r = q.euler();
        part.xRot = (float) r.x();
        part.yRot = (float) r.y();
        part.zRot = (float) r.z();
    }
}
