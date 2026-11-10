package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Skeleton;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wine Fox 的四片铰接裙板，在舞蹈中由腿部胶囊驱动。
 * <p>Wine Fox's four articulated skirt panels, driven by leg capsules during a dance.</p>
 */
public final class SkirtAvoidance {
    public record Output(Map<String, Quat> rotations, Vec translation, double maximumOpening) {}

    private record Panel(String bone, Vec outward, Vec axis, double flare) {}

    private static final List<Panel> PANELS =
            List.of(
                    new Panel(
                            "FrontClothe", new Vec(0, 0, -1), new Vec(1, 0, 0), Math.toRadians(14)),
                    new Panel(
                            "BackClothe", new Vec(0, 0, 1), new Vec(-1, 0, 0), Math.toRadians(14)),
                    new Panel(
                            "LeftClothe", new Vec(1, 0, 0), new Vec(0, 0, 1), Math.toRadians(7.5)),
                    new Panel(
                            "RightClothe",
                            new Vec(-1, 0, 0),
                            new Vec(0, 0, -1),
                            Math.toRadians(7.5)));
    private final Skeleton rig;
    private final double[] resting = new double[4];
    private final Vec waistOffset;

    private record SkinVertex(String bone, Vec local, Vec reference) {}

    private final List<SkinVertex> skin;

    public SkirtAvoidance(Skeleton rig) {
        this.rig = rig;
        waistOffset = rig.bind.get("clothe").position().sub(rig.bind.get("DownBody").position());
        for (int i = 0; i < PANELS.size(); i++) {
            resting[i] = required(PANELS.get(i), rig.bind, rig.bind.get("clothe"));
        }
        List<SkinVertex> vertices = new ArrayList<>();
        for (String side : List.of("Left", "Right")) {
            // 中文：从该 Wine Fox 套装测得未旋转腿部网格边界，而非圆形半径。
            // EN: Measured unrotated leg mesh bounds from this Wine Fox outfit, not a circular
            // radius.
            addBox(vertices, side + "Leg", side, .375, 10.7, -2, 3.775, 19.7, 2);
            addBox(vertices, side + "LowerLeg", side, .4, 3.55, -1.9, 3.75, 11.55, 2.1);
            addBox(vertices, side + "LowerLeg", side, .25, 2.875, -1.925, 3.875, 5.975, 2.15);
            addBox(vertices, side + "Foot", side, .1, .1, -4.3, 4.105, 4.075, 2.775);
        }
        skin = List.copyOf(vertices);
    }

    private void addBox(
            List<SkinVertex> vertices,
            String bone,
            String side,
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1) {
        var bind = rig.bind.get(bone);
        var waist = rig.bind.get("clothe");
        for (double x : new double[] {x0, x1}) {
            for (double y : new double[] {y0, y1}) {
                for (double z : new double[] {z0, z1}) {
                    Vec local =
                            new Vec(side.equals("Left") ? x : -x, y, z).sub(rig.bone(bone).pivot());
                    Vec reference =
                            waist.rotation()
                                    .inv()
                                    .turn(
                                            bind.position()
                                                    .add(bind.rotation().turn(local))
                                                    .sub(waist.position()));
                    vertices.add(new SkinVertex(bone, local, reference));
                }
            }
        }
    }

    public Output apply(SkeletonRetargeter.Output body) {
        var world = rig.fkTotal(body.localRotations(), body.rootTranslation());
        var pelvis = world.get("DownBody");
        var parent = world.get(rig.bone("clothe").parent());
        var waist =
                new Transform(
                        pelvis.position().add(pelvis.rotation().turn(waistOffset)),
                        pelvis.rotation());
        Vec translation =
                parent.rotation().inv().turn(waist.position().sub(world.get("clothe").position()));
        Map<String, Quat> rotations = new LinkedHashMap<>(body.localRotations());
        rotations.put("clothe", parent.rotation().inv().mul(waist.rotation()));
        double maximum = 0;
        for (int i = 0; i < PANELS.size(); i++) {
            Panel panel = PANELS.get(i);
            double demand =
                    i < 2
                            ? required(panel, world, waist) - resting[i]
                            : requiredSide(panel, world, waist);
            double angle = Math.min(Math.toRadians(75), Math.max(0, demand));
            maximum = Math.max(maximum, angle);
            rotations.put(
                    panel.bone(),
                    Quat.axis(panel.axis(), angle)
                            .mul(Quat.euler(rig.bone(panel.bone()).restEuler())));
        }
        return new Output(Collections.unmodifiableMap(rotations), translation, maximum);
    }

    private double requiredSide(Panel panel, Map<String, Transform> world, Transform waist) {
        Vec attachment = rig.bone(panel.bone()).pivot().sub(rig.bone("clothe").pivot());
        Vec baseNormal =
                panel.outward()
                        .mul(Math.cos(panel.flare()))
                        .add(new Vec(0, Math.sin(panel.flare()), 0));
        Quat inverse = waist.rotation().inv();
        double opening = 0;
        for (SkinVertex vertex : skin) {
            var bone = world.get(vertex.bone());
            Vec point =
                    inverse.turn(
                            bone.position()
                                    .add(bone.rotation().turn(vertex.local()))
                                    .sub(waist.position()));
            Vec relative = point.sub(attachment);
            // 中文：有限裙摆无法触及超出其下摆的脚或高于其腰部的点。
            // EN: A finite skirt cannot touch a foot beyond its hem or a point above its waist.
            // 中文：在这一物理范围外使用缓冲区，避免顶点突然切换。
            // EN: Use a buffer outside that physical area instead of switching vertices on/off.
            double radialWeight = 1 - smooth((relative.length() - 16) / 2);
            double waistWeight = 1 - smooth((point.y() - attachment.y()) / 3);
            double along = relative.dot(panel.outward()),
                    drop = -relative.y(),
                    length = Math.hypot(along, drop);
            // 中文：侧片具有有限的前/后宽度。远在前方抬起的膝盖属于
            // EN: Side pieces have finite front/back width. A lifted knee far in front belongs
            // 中文：前片；将其投影到无限侧平面上会导致跳变。
            // EN: to the front piece; projecting it onto an infinite side plane caused snapping.
            double halfDepth = 3 + .23 * Math.max(0, drop);
            double depthWeight = 1 - smooth((Math.abs(relative.z()) - halfDepth) / 1.5);
            double weight = radialWeight * waistWeight * depthWeight;
            if (weight <= 0) {
                continue;
            }
            if (length < .001) {
                continue;
            }
            // 中文：仅保留顶点原始的腰部重叠。减去一个全局中性
            // EN: Preserve only a vertex's authored waist overlap. Subtracting one global neutral
            // 中文：角度对膝盖和下摆校正不足，即使根部明显移动。
            // EN: angle under-corrected the knee and hem, even though the root visibly moved.
            double allowance =
                    Math.max(0, vertex.reference().sub(attachment).dot(baseNormal) + .25);
            double required =
                    Math.atan2(along, drop)
                            + Math.asin(Math.max(-1, Math.min(1, (.25 - allowance) / length)))
                            - panel.flare();
            opening = Math.max(opening, required * weight);
        }
        return Math.max(0, opening);
    }

    private static double smooth(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }

    private double required(Panel panel, Map<String, Transform> world, Transform waist) {
        Vec attachment = rig.bone(panel.bone()).pivot().sub(rig.bone("clothe").pivot());
        double opening = 0;
        Quat inverse = waist.rotation().inv();
        for (String side : List.of("Left", "Right")) {
            Vec hip = world.get(side + "Leg").position(),
                    knee = world.get(side + "LowerLeg").position(),
                    foot = world.get(side + "Foot").position();
            for (int segment = 0; segment < 2; segment++) {
                for (int sample = 0; sample <= 4; sample++) {
                    double t = sample / 4.0;
                    Vec start = segment == 0 ? hip : knee, end = segment == 0 ? knee : foot;
                    Vec point =
                            inverse.turn(start.mul(1 - t).add(end.mul(t)).sub(waist.position()));
                    double bottom = 8.0 - rig.bone("clothe").pivot().y();
                    if (point.y() < bottom - 1 || point.y() > attachment.y() + 2) {
                        continue;
                    }
                    double radius = (segment == 0 ? 2.25 : 2.1) + .4;
                    double excess = point.sub(attachment).dot(panel.outward()) + radius;
                    if (excess <= 0) {
                        continue;
                    }
                    double drop = Math.max(.5, attachment.y() - point.y());
                    opening = Math.max(opening, Math.atan2(excess, drop) - panel.flare());
                }
            }
        }
        return Math.max(0, opening);
    }
}
