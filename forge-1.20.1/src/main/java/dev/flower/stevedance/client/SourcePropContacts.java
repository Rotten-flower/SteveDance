package dev.flower.stevedance.client;

import dev.flower.stevedance.core.SkeletonRetargeter.Transform;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Source-space contact inference, shared across songs and target models.
 * 五个源动画采样确认手在道具局部空间内保持稳定，不把一次靠近当作握持。 */
public final class SourcePropContacts {
    public record Contact(
            String graph,
            PvSceneData.Rig rig,
            int actor,
            String role,
            int node,
            boolean instrument,
            boolean pick) {}

    private static final Map<PvSceneData.Rig, Contact> contacts = new IdentityHashMap<>();
    private static final Set<String> GRIPS =
            Set.of(
                    "j_temoti_wj",
                    "j_phone_wj",
                    "j_pik_wj",
                    "j_kasa_wj",
                    "j_sensu_wj",
                    "j_katana_wj",
                    "j_stick_01_wj",
                    "j_mic_wj",
                    "j_mic01_wj",
                    "j_vio_wj",
                    "j_yumi_wj",
                    "j_bin_wj",
                    "j_hocho_wj",
                    "j_bento_wj",
                    "j_radio_wj");

    private static boolean instrumentJoint(String name) {
        return name.toLowerCase(Locale.ROOT).startsWith("j_guitar_");
    }

    /**
     * 创作的握持关节独立于当前接触或文件名标识手部道具。
     * <p>Authored grip joints identify hand props independently of current contact or filename.</p>
     */
    public static boolean handProp(PvSceneData.Rig rig) {
        return Arrays.stream(rig.nodes)
                .anyMatch(
                        n ->
                                instrumentJoint(n.name)
                                        || GRIPS.contains(n.name.toLowerCase(Locale.ROOT)));
    }

    private static PvSceneData installed;
    private static long bucket = Long.MIN_VALUE, epoch = -1;

    public static void clear() {
        contacts.clear();
        installed = null;
        bucket = Long.MIN_VALUE;
    }

    public static List<Contact> forActor(int actor) {
        return contacts.values().stream().filter(c -> c.actor() == actor).toList();
    }

    public static boolean replaces(PvSceneData.Rig rig) {
        var c = contacts.get(rig);
        return PvStageRenderer.propsEnabled
                && c != null
                && dev.flower.stevedance.client.PvVisibility.actor(c.actor())
                && PerformerPropBindings.hasPending(c.actor());
    }

    /**
     * 附加的源效果获得与其武器完全相同的修正。
     * <p>Attached source effects receive exactly the same correction as their weapon.</p>
     */
    public static boolean replaces(String graph, PvSceneData.Node node) {
        return node.propRig != null
                && contacts.values().stream()
                        .anyMatch(
                                c ->
                                        c.graph().equals(graph)
                                                && c.rig().name.equals(node.propRig)
                                                && replaces(c.rig()));
    }

    public static double frame(PvSceneData scene, String graph, double ms) {
        if (scene.description.itemTracks() != null) {
            for (var item : scene.description.itemTracks()) {
                if (item.graph().equals(graph)) {
                    return scene.itemFrame(item, ms);
                }
            }
        }
        return scene.graphFrame(graph, ms);
    }

    public static void prepare() {
        prepare(PvPlaybackController.timeMs());
    }

    public static void prepare(double ms) {
        if (!PvPlaybackController.active()) {
            clear();
            return;
        }
        var scene = PvPlaybackController.scene;
        long cell = (long) (ms / 100);
        if (installed == scene && epoch == PvPlaybackController.epoch() && bucket == cell) {
            return;
        }
        installed = scene;
        epoch = PvPlaybackController.epoch();
        bucket = cell;
        contacts.clear();
        var actors = new TreeMap<Integer, net.minecraft.world.entity.player.Player>();
        if (PvPlaybackController.performer() != null) {
            actors.put(0, PvPlaybackController.performer());
        }
        actors.putAll(PvPlaybackController.worldActors());
        double[] times = {
            Math.max(0, ms - 200),
            Math.max(0, ms - 100),
            ms,
            Math.min(scene.description.durationMs() - 1, ms + 100),
            Math.min(scene.description.durationMs() - 1, ms + 200)
        };
        Map<Integer, List<Map<String, Transform>>> hands = new HashMap<>();
        for (int actor : actors.keySet()) {
            var body = PvPlaybackController.performerMotion(actor);
            if (body == null || !scene.performerVisible(ms, actor)) {
                continue;
            }
            List<Map<String, Transform>> poses = new ArrayList<>();
            for (double t : times) {
                poses.add(body.sample(t / 1000));
            }
            hands.put(actor, poses);
        }
        Set<String> graphs = new LinkedHashSet<>(scene.active(ms));
        if (scene.description.itemTracks() != null) {
            for (var item : scene.description.itemTracks()) {
                if (scene.itemFrame(item, ms) >= 0) {
                    graphs.add(item.graph());
                }
            }
        }
        for (String name : graphs) {
            var graph = scene.description.graphs().get(name);
            if (graph == null || graph.rigs.length == 0) {
                continue;
            }
            // 中文：专用物品轨道已具有明确的源所有权和配对语义。
            // EN: Dedicated item tracks already have explicit source ownership and pair semantics.
            if (scene.itemBindings.stream().anyMatch(i -> i.graph().equals(name))) {
                continue;
            }
            var item =
                    scene.description.itemTracks() == null
                            ? null
                            : scene.description.itemTracks().stream()
                                    .filter(i -> i.graph().equals(name))
                                    .findFirst()
                                    .orElse(null);
            boolean continuous = true;
            for (double t : times) {
                if (item == null ? !scene.active(t).contains(name) : scene.itemFrame(item, t) < 0) {
                    continuous = false;
                    break;
                }
            }
            if (!continuous) {
                continue;
            }
            Map<PvSceneData.Rig, Matrix4f[][]> samples = new IdentityHashMap<>();
            for (var rig : graph.rigs) {
                if (rig.geometry != null && rig.geometry.bones.length > 0) {
                    samples.put(rig, new Matrix4f[5][rig.nodes.length]);
                }
            }
            try {
                for (int k = 0; k < 5; k++) {
                    scene.evaluate(
                            graph,
                            item == null
                                    ? scene.graphFrame(name, times[k])
                                    : scene.itemFrame(item, times[k]));
                    for (var e : samples.entrySet()) {
                        var r = e.getKey();
                        if (!r.visible) {
                            continue;
                        }
                        for (int n = 0; n < r.nodes.length; n++) {
                            if (r.nodes[n].visible) {
                                e.getValue()[k][n] = new Matrix4f(r.model).mul(r.nodes[n].matrix);
                            }
                        }
                    }
                }
                for (var e : samples.entrySet()) {
                    var rig = e.getKey();
                    double best = Double.POSITIVE_INFINITY;
                    Contact selected = null;
                    boolean instrument =
                            Arrays.stream(rig.nodes).anyMatch(n -> instrumentJoint(n.name));
                    boolean pick =
                            Arrays.stream(rig.geometry.bones)
                                    .anyMatch(b -> b.name().equalsIgnoreCase("j_pik_wj"));
                    // 中文：关节生物不是手部道具。已知源握持关节描述
                    // EN: Articulated creatures are not hand props. Known source grip joints
                    // describe
                    // 中文：跨歌曲的对象语义，绝不是歌曲/图谱标识符。
                    // EN: object semantics across songs, never a song/graph identifier.
                    boolean grip =
                            Arrays.stream(rig.nodes)
                                    .anyMatch(n -> GRIPS.contains(n.name.toLowerCase(Locale.ROOT)));
                    if (!instrument && !grip && rig.geometry.bones.length > 1) {
                        continue;
                    }
                    for (var actor : hands.entrySet()) {
                        for (String role : List.of("LeftHand", "RightHand")) {
                            for (int n = 0; n < rig.nodes.length; n++) {
                                if (item != null && item.actor() != actor.getKey()) {
                                    continue;
                                }
                                // 中文：乐器手指在琴弦上移动：其局部手腕姿态
                                // EN: Instrument fingers move over the strings: their local wrist
                                // pose
                                // is
                                // 中文：不是刚性附着测试。只有创作的吉他枢轴
                                // EN: not a rigid attachment test. Only the authored guitar pivot
                                // 中文：才算数。
                                // EN: counts.
                                // 演奏时手指和腕部本就相对琴身移动，不能据此反复撤销绑定。
                                if (instrument && !instrumentJoint(rig.nodes[n].name)) {
                                    continue;
                                }
                                Vector3f[] local = new Vector3f[5];
                                Quaternionf[] orientation = new Quaternionf[5];
                                double distance = 0;
                                boolean valid = true;
                                for (int k = 0; k < 5; k++) {
                                    var m = e.getValue()[k][n];
                                    var h = actor.getValue().get(k).get(role);
                                    if (m == null
                                            || h == null
                                            || Math.abs(m.determinant()) < 1e-8) {
                                        valid = false;
                                        break;
                                    }
                                    Vector3f p =
                                            new Vector3f(
                                                    (float) h.position().x(),
                                                    (float) h.position().y(),
                                                    (float) h.position().z());
                                    distance =
                                            Math.max(
                                                    distance,
                                                    p.distance(m.getTranslation(new Vector3f())));
                                    local[k] = new Matrix4f(m).invert().transformPosition(p);
                                    var q = h.rotation();
                                    orientation[k] =
                                            m.getUnnormalizedRotation(new Quaternionf())
                                                    .normalize()
                                                    .invert()
                                                    .mul(
                                                            new Quaternionf(
                                                                    (float) q.x(),
                                                                    (float) q.y(),
                                                                    (float) q.z(),
                                                                    (float) q.w()));
                                }
                                if (!valid || distance > (instrument ? .55 : .28)) {
                                    continue;
                                }
                                double spread = 0;
                                for (int k = 0; k < 5; k++) {
                                    spread = Math.max(spread, local[k].distance(local[2]));
                                }
                                var start = actor.getValue().get(0).get(role).position();
                                var end = actor.getValue().get(4).get(role).position();
                                // 中文：静置的手旁的静态椅子不提供附着
                                // EN: A static chair beside a resting hand supplies no attachment
                                // 中文：证据。
                                // EN: evidence.
                                double travel = 0, angle = 0;
                                for (int k = 0; k < 5; k++) {
                                    travel =
                                            Math.max(
                                                    travel,
                                                    e.getValue()[k][n]
                                                            .getTranslation(new Vector3f())
                                                            .distance(
                                                                    e.getValue()[2][n]
                                                                            .getTranslation(
                                                                                    new Vector3f())));
                                    angle =
                                            Math.max(
                                                    angle,
                                                    2
                                                            * Math.acos(
                                                                    Math.min(
                                                                            1,
                                                                            Math.abs(
                                                                                    orientation[k]
                                                                                            .dot(
                                                                                                    orientation[
                                                                                                            2])))));
                                }
                                // 中文：语义手部道具可在动作暂停期间保持持有状态。
                                // EN: Semantic hand props can remain held during a pause in the
                                // action.
                                // 中文：未知刚性场景仍需关联运动。
                                // EN: Unknown rigid scenery still needs correlated movement.
                                if (!instrument
                                        && ((!grip
                                                        && (end.sub(start).length() < .008
                                                                || travel < .004))
                                                || spread > .05
                                                || angle > .35)) {
                                    continue;
                                }
                                double score = spread * 10 + distance;
                                if (score < best) {
                                    best = score;
                                    selected =
                                            new Contact(
                                                    name,
                                                    rig,
                                                    actor.getKey(),
                                                    role,
                                                    n,
                                                    instrument,
                                                    pick);
                                }
                            }
                        }
                    }
                    if (selected != null) {
                        contacts.put(rig, selected);
                    }
                }
            } finally {
                scene.evaluate(
                        graph,
                        item == null ? scene.graphFrame(name, ms) : scene.itemFrame(item, ms));
            }
        }
    }
}
