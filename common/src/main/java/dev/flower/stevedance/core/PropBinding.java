package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

/**
 * 固定尺寸道具跟随主握点，副握点决定朝向。
 * <p>A fixed-size prop follows its primary grip; the second grip determines aim.</p>
 */
public final class PropBinding {
    public record Fit(Vec translation, Quat rotation, double scale, double secondaryError) {
        public Vec point(Vec source) {
            return translation.add(rotation.turn(source.mul(scale)));
        }
    }

    /**
     * 无手腕骨时保留源手腕相对整臂的动作，不移动实际握点。
     * <p>Preserve wrist motion on a rigid arm without moving its mesh-derived grip.</p>
     */
    public static Transform rigidHand(
            Transform grip,
            Transform targetArm,
            Transform sourceArm,
            Transform sourceHand,
            Transform bindArm,
            Transform bindHand) {
        Vec bindAxis = bindArm.rotation().inv().turn(bindHand.position().sub(bindArm.position()));
        Quat sourceRigid =
                Quat.between(
                                sourceArm.rotation().turn(bindAxis),
                                sourceHand.position().sub(sourceArm.position()))
                        .mul(sourceArm.rotation());
        return new Transform(
                grip.position(),
                targetArm.rotation().mul(sourceRigid.inv()).mul(sourceHand.rotation()));
    }

    /**
     * 身高主导，臂长最多修正15%，不随动态握距改变尺寸。
     * <p>Height leads; static arm proportions refine size within fifteen percent.</p>
     */
    public static double proportionalScale(double heightScale, double armScale) {
        if (!Double.isFinite(heightScale + armScale) || heightScale <= 0 || armScale <= 0) {
            throw new IllegalArgumentException("Invalid model proportions");
        }
        return heightScale * Math.max(.85, Math.min(1.15, Math.pow(armScale / heightScale, .25)));
    }

    public static Fit single(Transform source, Transform target, double fixedScale) {
        if (!Double.isFinite(fixedScale) || fixedScale <= 0) {
            throw new IllegalArgumentException("Invalid prop scale");
        }
        Quat q = target.rotation().mul(source.rotation().inv());
        return new Fit(
                target.position().sub(q.turn(source.position().mul(fixedScale))), q, fixedScale, 0);
    }

    /** Map the source hand/shoulder triangle without altering the target pose.
     * 用双方掌心与肩中点建立局部坐标，避免混用模型绑定轴，不反向改动作。 */
    public static Fit authored(
            Vec sourceGrip,
            Vec sourceOther,
            Vec sourceChest,
            Vec targetGrip,
            Vec targetOther,
            Vec targetChest,
            double scale) {
        if (!Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("Invalid prop scale");
        }
        Vec a = sourceOther.sub(sourceGrip), b = targetOther.sub(targetGrip);
        Quat q = Quat.between(a, b);
        Vec axis = b.unit();
        Vec x = q.turn(sourceChest.sub(sourceGrip)), y = targetChest.sub(targetGrip);
        x = x.sub(axis.mul(x.dot(axis)));
        y = y.sub(axis.mul(y.dot(axis)));
        if (x.length() > 1e-5 && y.length() > 1e-5) {
            double angle = Math.atan2(axis.dot(x.unit().cross(y.unit())), x.unit().dot(y.unit()));
            q = Quat.axis(axis, angle).mul(q);
        }
        var fit = new Fit(targetGrip.sub(q.turn(sourceGrip.mul(scale))), q, scale, 0);
        return new Fit(
                fit.translation(), q, scale, fit.point(sourceOther).sub(targetOther).length());
    }
}
