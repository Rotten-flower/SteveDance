package dev.flower.stevedance.content;

import dev.flower.stevedance.core.SkeletonRetargeter.Vec;

/** Fixed neutral-pose calibration; animated roots retain their authored path.
 * 静止骨架的固定校准，不随舞蹈动作改变比例。 */
public record CameraFit(double ratio, Vec offset) {
    public CameraFit {
        if (!Double.isFinite(ratio)
                || ratio <= 0
                || ratio > 10
                || !Double.isFinite(offset.x() + offset.y() + offset.z())) {
            throw new IllegalArgumentException("Invalid camera calibration");
        }
    }

    public static CameraFit between(Vec sourceHead, Vec targetHead) {
        if (sourceHead.y() <= .05 || targetHead.y() <= .05) {
            throw new IllegalArgumentException("Invalid neutral head height");
        }
        double ratio = targetHead.y() / sourceHead.y();
        return new CameraFit(ratio, targetHead.sub(sourceHead.mul(ratio)));
    }

    public Vec point(Vec camera, Vec root) {
        return root.add(camera.sub(root).mul(ratio)).add(offset);
    }
}
