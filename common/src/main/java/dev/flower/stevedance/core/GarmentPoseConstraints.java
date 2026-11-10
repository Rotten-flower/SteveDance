package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.HUMANOID;
import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Skeleton;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依据当前身体与裙摆碰撞体限制附件旋转。
 * <p>Bounded attachment rotations against posed body and skirt collision volumes.</p>
 */
public final class GarmentPoseConstraints {
    public record Output(
            Map<String, Quat> rotations,
            Map<String, Vec> translations,
            Map<String, Vec> scales,
            double maximumOpening,
            double remainingPenetration) {
        public Output(
                Map<String, Quat> rotations,
                Map<String, Vec> translations,
                double maximumOpening,
                double remainingPenetration) {
            this(rotations, translations, Map.of(), maximumOpening, remainingPenetration);
        }
    }

    public static final class State {
        private final Map<String, Quat> previous = new HashMap<>();
        private double seconds = Double.NaN;

        public void reset() {
            previous.clear();
            seconds = Double.NaN;
        }
    }

    private record Box(String bone, Vec center, Vec half, Quat rotation, Vec surface) {}

    private record Anchor(String bone, String follows, Vec offset, Quat rotation) {}

    private record Envelope(Vec outward, Vec axis, double flare, double hem, double width) {}

    private record Ease(
            String bone,
            double width,
            double flare,
            double hem,
            double limit,
            Set<String> descendants) {}

    private record Space(Transform origin, double xScale, Set<String> descendants) {}

    private record Part(
            String bone,
            String support,
            double limit,
            List<Vec> samples,
            double radius,
            double[][] allowance,
            Envelope envelope) {}

    private record PosedBox(Vec center, Vec half, Quat inverse, double radius, Vec surface) {}

    private record Contact(double distance, Vec normal) {}

    private record Evaluation(double cost, double maximum, Vec torque, double leverage) {}

    private final Skeleton rig;
    private final SkirtAvoidance skirt;
    private final List<Box> boxes;
    private final List<Part> parts;
    private final double clearance;
    private final List<Anchor> anchors;
    private final Ease ease;
    private final Map<String, List<String>> children = new HashMap<>();

    public GarmentPoseConstraints(Skeleton rig, InputStream stream) throws IOException {
        this.rig = rig;
        if (stream == null) {
            throw new IOException("Garment collision profile missing");
        }
        try {
            byte[] bytes = stream.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) {
                throw new IOException("Garment collision profile too large");
            }
            var json =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (json.get("schema").getAsInt() != 1) {
                throw new IOException("Unsupported garment collision profile schema");
            }
            String mode = json.has("skirtMode") ? json.get("skirtMode").getAsString() : "winefox";
            if (!mode.equals("winefox") && !mode.equals("sampled")) {
                throw new IOException("Unknown skirt constraint mode");
            }
            skirt = mode.equals("winefox") ? new SkirtAvoidance(rig) : null;
            clearance = json.get("clearance").getAsDouble();
            if (!Double.isFinite(clearance) || clearance < 0 || clearance > 1) {
                throw new IOException("Invalid garment clearance");
            }
            List<Anchor> attachmentsToBody = new ArrayList<>();
            for (var value : json.getAsJsonArray("anchors")) {
                var anchor = value.getAsJsonObject();
                String bone = anchor.get("bone").getAsString(),
                        follows = anchor.get("follows").getAsString();
                rig.bone(bone);
                rig.bone(follows);
                var bind = rig.bind.get(bone);
                var parent = rig.bind.get(follows);
                attachmentsToBody.add(
                        new Anchor(
                                bone,
                                follows,
                                parent.rotation()
                                        .inv()
                                        .turn(bind.position().sub(parent.position())),
                                parent.rotation().inv().mul(bind.rotation())));
                if (attachmentsToBody.size() > 8) {
                    throw new IOException("Garment anchor limit");
                }
            }
            anchors = List.copyOf(attachmentsToBody);
            Ease lateral = null;
            if (json.has("lateralEase")) {
                var value = json.getAsJsonObject("lateralEase");
                String bone = value.get("bone").getAsString();
                rig.bone(bone);
                double width = value.get("waistHalfWidth").getAsDouble(),
                        flare = value.get("widthPerDrop").getAsDouble(),
                        hem = value.get("hemLength").getAsDouble(),
                        limit = value.get("maxScale").getAsDouble();
                if (!Double.isFinite(width + flare + hem + limit)
                        || width <= 0
                        || width > 16
                        || flare < 0
                        || flare > 1
                        || hem <= 0
                        || hem > 32
                        || limit < 1
                        || limit > 1.5
                        || anchors.stream().noneMatch(a -> a.bone().equals(bone))) {
                    throw new IOException("Invalid lateral skirt ease");
                }
                Set<String> descendants = new HashSet<>(Set.of(bone));
                for (var b : rig.bones) {
                    if (descendants.contains(b.parent())) {
                        descendants.add(b.name());
                    }
                }
                for (String role : HUMANOID.keySet()) {
                    if (descendants.contains(role)) {
                        throw new IOException("Skirt ease overlaps body joints");
                    }
                }
                lateral = new Ease(bone, width, flare, hem, limit, Set.copyOf(descendants));
            }
            ease = lateral;
            List<Box> shapes = new ArrayList<>();
            for (var value : json.getAsJsonArray("colliders")) {
                var box = value.getAsJsonObject();
                String bone = box.get("bone").getAsString();
                rig.bone(bone);
                Vec half = vector(box.getAsJsonArray("halfSize"));
                if (Math.min(half.x(), Math.min(half.y(), half.z())) <= 0 || half.length() > 32) {
                    throw new IOException("Invalid garment collision bounds");
                }
                Vec surface =
                        box.get("surface").isJsonNull()
                                ? null
                                : vector(box.getAsJsonArray("surface"));
                if (surface != null && Math.abs(surface.length() - 1) > 1e-8) {
                    throw new IOException("Invalid garment surface normal");
                }
                shapes.add(
                        new Box(
                                bone,
                                vector(box.getAsJsonArray("center")),
                                half,
                                Quat.euler(vector(box.getAsJsonArray("rotation"))),
                                surface));
                if (shapes.size() > 96) {
                    throw new IOException("Garment collider limit");
                }
            }
            boxes = List.copyOf(shapes);
            List<Part> attachments = new ArrayList<>();
            var reference = posedBoxes(rig.bind);
            for (var value : json.getAsJsonArray("parts")) {
                var part = value.getAsJsonObject();
                String bone = part.get("bone").getAsString(),
                        support = part.get("support").getAsString();
                rig.bone(bone);
                rig.bone(support);
                double limit = Math.toRadians(part.get("maxAngleDegrees").getAsDouble());
                if (!Double.isFinite(limit) || limit <= 0 || limit > Math.PI / 2) {
                    throw new IOException("Invalid attachment angle limit");
                }
                List<Vec> samples = new ArrayList<>();
                double radius = 0;
                for (var sample : part.getAsJsonArray("samples")) {
                    Vec point = vector(sample.getAsJsonArray());
                    radius = Math.max(radius, point.length());
                    samples.add(point);
                    if (samples.size() > 64 || radius > 32) {
                        throw new IOException("Attachment sample limit");
                    }
                }
                if (samples.isEmpty()) {
                    throw new IOException("Attachment has no collision samples");
                }
                double[][] allowance = new double[samples.size()][boxes.size()];
                var bind = rig.bind.get(bone);
                for (int s = 0; s < samples.size(); s++) {
                    for (int b = 0; b < boxes.size(); b++) {
                        allowance[s][b] =
                                Math.max(
                                        0,
                                        clearance
                                                - contact(
                                                                bind.position()
                                                                        .add(
                                                                                bind.rotation()
                                                                                        .turn(
                                                                                                samples
                                                                                                        .get(
                                                                                                                s))),
                                                                reference.get(b),
                                                                clearance)
                                                        .distance());
                    }
                }
                Envelope envelope = null;
                if (part.has("opening")) {
                    var open = part.getAsJsonObject("opening");
                    Vec outward = vector(open.getAsJsonArray("outward")),
                            axis = vector(open.getAsJsonArray("axis"));
                    double flare = Math.toRadians(open.get("flareDegrees").getAsDouble()),
                            hem = open.get("hemLength").getAsDouble(),
                            width = open.get("halfWidth").getAsDouble();
                    if (Math.abs(outward.length() - 1) > 1e-8
                            || Math.abs(axis.length() - 1) > 1e-8
                            || Math.abs(outward.y()) > 1e-8
                            || axis.sub(new Vec(0, -1, 0).cross(outward)).length() > 1e-8
                            || !Double.isFinite(flare + hem + width)
                            || flare < 0
                            || flare > Math.PI / 4
                            || hem <= 0
                            || hem > 32
                            || width <= 0
                            || width > 32) {
                        throw new IOException("Invalid skirt opening envelope");
                    }
                    envelope = new Envelope(outward, axis, flare, hem, width);
                }
                attachments.add(
                        new Part(
                                bone,
                                support,
                                limit,
                                List.copyOf(samples),
                                radius,
                                allowance,
                                envelope));
                if (attachments.size() > 16) {
                    throw new IOException("Attachment limit");
                }
            }
            parts = List.copyOf(attachments);
            for (var bone : rig.bones) {
                if (bone.parent() != null) {
                    children.computeIfAbsent(bone.parent(), n -> new ArrayList<>())
                            .add(bone.name());
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException ex) {
            throw new IOException("Invalid garment collision profile", ex);
        }
    }

    private static Vec vector(JsonArray values) {
        if (values.size() != 3) {
            throw new IllegalArgumentException("Expected 3 components");
        }
        return new Vec(
                values.get(0).getAsDouble(),
                values.get(1).getAsDouble(),
                values.get(2).getAsDouble());
    }

    public Output apply(SkeletonRetargeter.Output body) {
        return apply(body, null, 0);
    }

    double uncorrectedPenetration(SkeletonRetargeter.Output body) {
        var garment = base(body);
        var world = rig.fkTotal(garment.rotations(), garment.translations());
        var obstacles = posedBoxes(world);
        double maximum = 0;
        for (var part : parts) {
            maximum =
                    Math.max(
                            maximum,
                            evaluate(part, world.get(part.bone()), obstacles, null, null)
                                    .maximum());
        }
        return maximum;
    }

    public Output apply(SkeletonRetargeter.Output body, State state, double seconds) {
        var garment = base(body);
        Map<String, Quat> rotations = new LinkedHashMap<>(garment.rotations());
        Map<String, Vec> translations = new LinkedHashMap<>(garment.translations());
        Map<String, Transform> attached = rig.fkTotal(rotations, translations);
        for (Anchor anchor : anchors) {
            var follow = attached.get(anchor.follows());
            var definition = rig.bone(anchor.bone());
            var parent = attached.get(definition.parent());
            Vec desired = follow.position().add(follow.rotation().turn(anchor.offset()));
            translations.put(
                    anchor.bone(),
                    parent.rotation()
                            .inv()
                            .turn(desired.sub(attached.get(anchor.bone()).position())));
            rotations.put(
                    anchor.bone(),
                    parent.rotation().inv().mul(follow.rotation()).mul(anchor.rotation()));
            attached = rig.fkTotal(rotations, translations);
        }
        double xScale = ease == null ? 1 : requiredEase(attached);
        Space space =
                ease == null
                        ? null
                        : new Space(attached.get(ease.bone()), xScale, ease.descendants());
        double maximumOpening = garment.maximumOpening();
        for (Part part : parts) {
            if (part.envelope() != null) {
                double angle = Math.min(part.limit(), requiredOpening(part, attached));
                rotations.put(
                        part.bone(),
                        Quat.euler(rig.bone(part.bone()).restEuler())
                                .mul(Quat.axis(part.envelope().axis(), angle)));
                maximumOpening = Math.max(maximumOpening, angle);
            }
        }
        double dt = state == null ? 0 : seconds - state.seconds;
        if (state != null && dt >= 0 && dt <= .25) {
            for (Part part : parts) {
                Quat previous = state.previous.get(part.bone());
                if (previous != null && part.envelope() == null) {
                    rotations.put(
                            part.bone(),
                            blend(previous, rotations.get(part.bone()), 1 - Math.exp(-14 * dt)));
                }
            }
        }
        Map<String, Transform> world = rig.fkTotal(rotations, translations);
        var obstacles = posedBoxes(world);
        // 中文：使用固定计算预算，锚点和节段长度保持原骨架值。
        // EN: Fixed work budget; anchors and segment lengths remain those of the original rig.
        double penetration = 0;
        for (Part part : parts) {
            List<Integer> nearby = new ArrayList<>();
            Vec pivot = scaled(world.get(part.bone()).position(), part.bone(), space);
            for (int i = 0; i < obstacles.size(); i++) {
                if (boxes.get(i).bone().equals(part.support())) {
                    continue;
                }
                var box = obstacles.get(i);
                if (box.center().sub(pivot).length()
                        < box.radius() + part.radius() * xScale + clearance) {
                    nearby.add(i);
                }
            }
            var current = evaluate(part, world.get(part.bone()), obstacles, nearby, space);
            double minimumOpening = part.envelope() == null ? 0 : requiredOpening(part, attached);
            for (int iteration = 0; iteration < 8; iteration++) {
                Transform pose = world.get(part.bone());
                if (current.maximum() < .015 || current.leverage() < 1e-9) {
                    break;
                }
                Vec impulse = current.torque().mul(.85 / current.leverage());
                double angle = Math.min(Math.toRadians(10), impulse.length());
                if (angle < 1e-6) {
                    break;
                }
                var definition = rig.bone(part.bone());
                Quat parent = world.get(definition.parent()).rotation(),
                        local = rotations.get(part.bone());
                Vec axis = parent.inv().turn(impulse.unit());
                boolean accepted = false;
                for (int retry = 0; retry < 4; retry++) {
                    Quat candidate =
                            bounded(
                                    Quat.axis(axis, angle).mul(local),
                                    Quat.euler(definition.restEuler()),
                                    part.limit());
                    if (part.envelope() != null) {
                        Quat rest = Quat.euler(definition.restEuler()),
                                delta = rest.inv().mul(candidate);
                        Vec openingAxis = part.envelope().axis();
                        double twist =
                                2
                                        * Math.atan2(
                                                new Vec(delta.x(), delta.y(), delta.z())
                                                        .dot(openingAxis),
                                                delta.w());
                        if (twist < Math.min(part.limit(), minimumOpening)) {
                            candidate =
                                    rest.mul(
                                            Quat.axis(
                                                            openingAxis,
                                                            Math.min(part.limit(), minimumOpening)
                                                                    - twist)
                                                    .mul(delta));
                        }
                        double total =
                                2 * Math.acos(Math.min(1, Math.abs(rest.inv().mul(candidate).w())));
                        if (total > part.limit() + 1e-8) {
                            angle *= .5;
                            continue;
                        }
                    }
                    var changed = new Transform(pose.position(), parent.mul(candidate));
                    var result = evaluate(part, changed, obstacles, nearby, space);
                    if (result.cost() < current.cost() - 1e-10) {
                        rotations.put(part.bone(), candidate);
                        updateBranch(part.bone(), rotations, world);
                        current = result;
                        accepted = true;
                        break;
                    }
                    angle *= .5;
                }
                if (!accepted) {
                    break;
                }
            }
            penetration = Math.max(penetration, current.maximum());
        }
        if (state != null) {
            state.previous.clear();
            for (Part part : parts) {
                state.previous.put(part.bone(), rotations.get(part.bone()));
            }
            state.seconds = seconds;
        }
        return new Output(
                Collections.unmodifiableMap(rotations),
                Collections.unmodifiableMap(translations),
                ease == null ? Map.of() : Map.of(ease.bone(), new Vec(xScale, 1, 1)),
                maximumOpening,
                penetration);
    }

    private double requiredEase(Map<String, Transform> world) {
        Transform waist = world.get(ease.bone()), reference = rig.bind.get(ease.bone());
        double scale = 1;
        for (Box box : boxes) {
            if (!box.bone().matches("(Left|Right)(Leg|LowerLeg)")) {
                continue;
            }
            Transform posed = world.get(box.bone()), bind = rig.bind.get(box.bone());
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Vec local =
                                box.center()
                                        .add(
                                                box.rotation()
                                                        .turn(
                                                                new Vec(
                                                                        box.half().x() * x,
                                                                        box.half().y() * y,
                                                                        box.half().z() * z)));
                        Vec point =
                                waist.rotation()
                                        .inv()
                                        .turn(
                                                posed.position()
                                                        .add(posed.rotation().turn(local))
                                                        .sub(waist.position()));
                        double weight =
                                coverage(point.y() + 1)
                                        * coverage(-point.y() - ease.hem())
                                        * coverage(Math.abs(point.z()) - 4.5);
                        if (weight <= 0) {
                            continue;
                        }
                        Vec resting =
                                reference
                                        .rotation()
                                        .inv()
                                        .turn(
                                                bind.position()
                                                        .add(bind.rotation().turn(local))
                                                        .sub(reference.position()));
                        double width = ease.width() + ease.flare() * Math.max(0, -point.y());
                        double allowance =
                                Math.max(
                                        0,
                                        Math.abs(resting.x())
                                                + clearance
                                                - (ease.width()
                                                        + ease.flare()
                                                                * Math.max(0, -resting.y())));
                        scale =
                                Math.max(
                                        scale,
                                        1
                                                + Math.max(
                                                                0,
                                                                Math.abs(point.x())
                                                                        + clearance
                                                                        - width
                                                                        - allowance)
                                                        / width
                                                        * weight);
                    }
                }
            }
        }
        return Math.min(ease.limit(), scale);
    }

    private static Vec scaled(Vec point, String bone, Space space) {
        if (space == null || !space.descendants().contains(bone)) {
            return point;
        }
        Vec local = space.origin().rotation().inv().turn(point.sub(space.origin().position()));
        return space.origin()
                .position()
                .add(
                        space.origin()
                                .rotation()
                                .turn(new Vec(local.x() * space.xScale(), local.y(), local.z())));
    }

    private double requiredOpening(Part part, Map<String, Transform> world) {
        Envelope e = part.envelope();
        Transform attachment = world.get(part.bone()), reference = rig.bind.get(part.bone());
        double maximum = 0;
        for (Box box : boxes) {
            if (!box.bone().matches("(Left|Right)(Leg|LowerLeg|Foot)")) {
                continue;
            }
            Transform posed = world.get(box.bone()), bind = rig.bind.get(box.bone());
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        Vec local =
                                box.center()
                                        .add(
                                                box.rotation()
                                                        .turn(
                                                                new Vec(
                                                                        box.half().x() * x,
                                                                        box.half().y() * y,
                                                                        box.half().z() * z)));
                        Vec point =
                                attachment
                                        .rotation()
                                        .inv()
                                        .turn(
                                                posed.position()
                                                        .add(posed.rotation().turn(local))
                                                        .sub(attachment.position()));
                        double weight =
                                coverage(point.y() + 1.5)
                                        * coverage(-point.y() - e.hem())
                                        * coverage(Math.abs(point.x()) - e.width());
                        if (weight <= 0) {
                            continue;
                        }
                        Vec resting =
                                reference
                                        .rotation()
                                        .inv()
                                        .turn(
                                                bind.position()
                                                        .add(bind.rotation().turn(local))
                                                        .sub(reference.position()));
                        double baseline =
                                Math.max(
                                        0,
                                        Math.atan2(
                                                        Math.max(
                                                                0,
                                                                resting.dot(e.outward())
                                                                        + clearance),
                                                        Math.max(.5, -resting.y()))
                                                - e.flare());
                        double demand =
                                Math.atan2(
                                                Math.max(0, point.dot(e.outward()) + clearance),
                                                Math.max(.5, -point.y()))
                                        - e.flare()
                                        - baseline;
                        maximum = Math.max(maximum, demand * weight);
                    }
                }
            }
        }
        return Math.max(0, maximum);
    }

    private Output base(SkeletonRetargeter.Output body) {
        Map<String, Quat> rotations = new LinkedHashMap<>();
        for (var bone : rig.bones) {
            rotations.put(bone.name(), Quat.euler(bone.restEuler()));
        }
        rotations.putAll(body.localRotations());
        var full =
                new SkeletonRetargeter.Output(rotations, body.rootTranslation(), body.footError());
        if (skirt == null) {
            return new Output(rotations, Map.of("AllBody", body.rootTranslation()), 0, 0);
        }
        var output = skirt.apply(full);
        return new Output(
                output.rotations(),
                Map.of("AllBody", body.rootTranslation(), "clothe", output.translation()),
                output.maximumOpening(),
                0);
    }

    private Evaluation evaluate(
            Part part, Transform pose, List<PosedBox> boxes, List<Integer> nearby, Space space) {
        double cost = 0, maximum = 0, leverage = 0;
        Vec torque = Vec.ZERO;
        for (int s = 0; s < part.samples().size(); s++) {
            Vec
                    point =
                            scaled(
                                    pose.position()
                                            .add(pose.rotation().turn(part.samples().get(s))),
                                    part.bone(),
                                    space),
                    lever = point.sub(scaled(pose.position(), part.bone(), space));
            int count = nearby == null ? boxes.size() : nearby.size();
            for (int j = 0; j < count; j++) {
                int i = nearby == null ? j : nearby.get(j);
                if (this.boxes.get(i).bone().equals(part.support())) {
                    continue;
                }
                var box = boxes.get(i);
                if (box.surface() == null
                        && point.sub(box.center()).length() > box.radius() + clearance) {
                    continue;
                }
                Contact contact = contact(point, box, clearance);
                double depth = clearance - contact.distance() - part.allowance()[s][i];
                if (depth <= 0) {
                    continue;
                }
                Vec angular = lever.cross(contact.normal());
                torque = torque.add(angular.mul(depth));
                leverage += angular.dot(angular);
                cost += depth * depth;
                maximum = Math.max(maximum, depth);
            }
        }
        return new Evaluation(cost, maximum, torque, leverage);
    }

    private List<PosedBox> posedBoxes(Map<String, Transform> world) {
        List<PosedBox> result = new ArrayList<>(boxes.size());
        for (var box : boxes) {
            var bone = world.get(box.bone());
            result.add(
                    new PosedBox(
                            bone.position().add(bone.rotation().turn(box.center())),
                            box.half(),
                            bone.rotation().mul(box.rotation()).inv(),
                            box.half().length(),
                            box.surface()));
        }
        return result;
    }

    private static Contact contact(Vec point, PosedBox box, double clearance) {
        Vec local = box.inverse().turn(point.sub(box.center())), h = box.half();
        if (box.surface() != null) {
            Vec normal = box.surface();
            double weight = 1;
            if (normal.x() == 0) {
                weight *= coverage(Math.abs(local.x()) - h.x());
            }
            if (normal.y() == 0) {
                weight *= coverage(Math.abs(local.y()) - h.y());
            }
            if (normal.z() == 0) {
                weight *= coverage(Math.abs(local.z()) - h.z());
            }
            double
                    thickness =
                            Math.abs(normal.x()) * h.x()
                                    + Math.abs(normal.y()) * h.y()
                                    + Math.abs(normal.z()) * h.z(),
                    distance = local.dot(normal) - thickness;
            // 中文：服装另一侧的远端点不应接触这块薄面板。
            // EN: A distant point on the other side of the outfit cannot touch this thin panel.
            weight *= coverage(-distance - 2 * thickness);
            return new Contact(
                    clearance - Math.max(0, clearance - distance) * weight,
                    box.inverse().inv().turn(normal));
        }
        Vec
                nearest =
                        new Vec(
                                Math.max(-h.x(), Math.min(h.x(), local.x())),
                                Math.max(-h.y(), Math.min(h.y(), local.y())),
                                Math.max(-h.z(), Math.min(h.z(), local.z()))),
                delta = local.sub(nearest);
        double distance = delta.length();
        Vec normal;
        if (distance > 1e-9) {
            normal = delta.mul(1 / distance);
        } else {
            double x = h.x() - Math.abs(local.x()),
                    y = h.y() - Math.abs(local.y()),
                    z = h.z() - Math.abs(local.z());
            if (x < y && x < z) {
                distance = -x;
                normal = new Vec(local.x() < 0 ? -1 : 1, 0, 0);
            } else if (y < z) {
                distance = -y;
                normal = new Vec(0, local.y() < 0 ? -1 : 1, 0);
            } else {
                distance = -z;
                normal = new Vec(0, 0, local.z() < 0 ? -1 : 1);
            }
        }
        return new Contact(distance, box.inverse().inv().turn(normal));
    }

    private static double coverage(double beyond) {
        double t = Math.max(0, Math.min(1, beyond / .5));
        return 1 - t * t * (3 - 2 * t);
    }

    private void updateBranch(
            String name, Map<String, Quat> rotations, Map<String, Transform> world) {
        var bone = rig.bone(name);
        var parent = world.get(bone.parent());
        world.put(
                name,
                new Transform(
                        parent.position()
                                .add(
                                        parent.rotation()
                                                .turn(
                                                        bone.pivot()
                                                                .sub(
                                                                        rig.bone(bone.parent())
                                                                                .pivot()))),
                        parent.rotation().mul(rotations.get(name))));
        for (String child : children.getOrDefault(name, List.of())) {
            updateBranch(child, rotations, world);
        }
    }

    private static Quat bounded(Quat pose, Quat rest, double limit) {
        Quat delta = pose.mul(rest.inv());
        if (delta.w() < 0) {
            delta = new Quat(-delta.x(), -delta.y(), -delta.z(), -delta.w());
        }
        double angle = 2 * Math.acos(Math.max(-1, Math.min(1, delta.w())));
        return angle <= limit
                ? pose
                : Quat.axis(new Vec(delta.x(), delta.y(), delta.z()), limit).mul(rest);
    }

    private static Quat blend(Quat from, Quat to, double t) {
        double dot = from.x() * to.x() + from.y() * to.y() + from.z() * to.z() + from.w() * to.w(),
                sign = dot < 0 ? -1 : 1;
        return new Quat(
                        from.x() * (1 - t) + to.x() * t * sign,
                        from.y() * (1 - t) + to.y() * t * sign,
                        from.z() * (1 - t) + to.z() * t * sign,
                        from.w() * (1 - t) + to.w() * t * sign)
                .unit();
    }
}
