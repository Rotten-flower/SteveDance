package dev.flower.stevedance.client;

import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.PropBinding;

import org.joml.Vector3f;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 身体绑定道具和每骨架世界回退；不受支持的模型保留源道具。
 * <p>Body-bound props and per-rig world fallbacks; unsupported models keep source props.</p>
 */
public final class YsmPropAdapter {
    private record ItemKey(int actor, String graph, String rig) {}

    private record Attachment(long epoch, double timeMs) {}

    private static final Map<ItemKey, Attachment> attachedItems = new HashMap<>();
    private static final Set<Object> rejected = Collections.newSetFromMap(new WeakHashMap<>());
    public static volatile boolean bound;
    public static volatile double secondaryError;
    public static volatile double pairedRotationDegrees = Double.NaN;
    public static volatile double bowGripError = Double.NaN;
    public static volatile String activeModel = "";
    public static volatile Map<String, Transform> lastSockets = Map.of();
    public static volatile PropBinding.Fit lastFit;
    public static volatile float[] lastRenderBasis;

    public static Map<String, Vec> nativeSocketPoints() {
        return YsmPropSockets.lastNativePoints;
    }

    public static boolean replaceItem(int actor, String graph, PvSceneData.Rig rig, double ms) {
        if (!PvStageRenderer.propsEnabled
                || !dev.flower.stevedance.client.PvVisibility.actor(actor)) {
            return false;
        }
        var attachment = attachedItems.get(new ItemKey(actor, graph, rig.name));
        return attachment != null
                && attachment.epoch() == PvPlaybackController.epoch()
                && java.lang.Math.abs(ms - attachment.timeMs()) <= 100
                && itemBinding(actor, graph, rig, ms) != null;
    }

    static String fitKey(PvSceneData.ItemBinding binding) {
        return binding.graph() + "/" + binding.rig();
    }

    static void itemRendered(PvSceneData.ItemBinding binding, double ms) {
        attachedItems.put(
                new ItemKey(binding.actor(), binding.graph(), binding.rig()),
                new Attachment(PvPlaybackController.epoch(), ms));
    }

    public static void clearAttachments() {
        SourcePropContacts.clear();
        pairedRotationDegrees = bowGripError = Double.NaN;
        attachedItems.clear();
        rejected.clear();
        YsmPropSockets.clear();
        PerformerPropBindings.clearPending();
        bound = false;
        lastSockets = Map.of();
    }

    static void removeActor(int actor) {
        attachedItems.keySet().removeIf(key -> key.actor() == actor);
        if (actor == 0) {
            bound = false;
        }
    }

    static PvSceneData.ItemBinding itemBinding(
            int actor, String graph, PvSceneData.Rig rig, double ms) {
        var scene = PvPlaybackController.scene;
        if (!PvPlaybackController.active()
                || scene.itemBindings == null
                || !rig.visible
                || rig.nodes.length == 0) {
            return null;
        }
        var body = PvPlaybackController.performerMotion(actor);
        if (body == null) {
            return null;
        }
        for (var binding : scene.itemBindings) {
            if (binding.actor() == actor
                    && binding.graph().equals(graph)
                    && binding.rig().equals(rig.name)) {
                var source = body.sample(ms / 1000);
                var anchor = source.get(binding.role());
                var p = rig.nodes[rig.nodes.length - 1].matrix.getTranslation(new Vector3f());
                if (anchor != null
                        && anchor.position().sub(new Vec(p.x, p.y, p.z)).length()
                                <= binding.maximumSourceDistance()) {
                    return binding;
                }
            }
        }
        return null;
    }

    public static void render(Object model, PoseStack stack) {
        if (!PvPlaybackController.active() || PvPlaybackController.motion == null) {
            return;
        }
        var body = YsmAnimationAdapter.propSkeleton(model);
        if (body == null) {
            return;
        }
        int actor = PvPlaybackController.actor(body.actor());
        if (actor < 0) {
            return;
        }
        if (rejected.contains(model)) {
            removeActor(actor);
            return;
        }
        try {
            var frame = YsmPropSockets.sample(model, actor);
            if (frame == null) {
                removeActor(actor);
                return;
            }
            if (actor == 0) {
                activeModel = frame.modelId();
                bound = true;
            }
            PerformerPropBindings.render(stack, actor, frame.sockets(), frame.scale());
        } catch (ReflectiveOperationException
                | java.io.IOException
                | IllegalArgumentException
                | IllegalStateException ex) {
            rejected.add(model);
            removeActor(actor);
            SteveDanceMod.LOGGER.warn(
                    "Prop sockets for {} unavailable; retaining world props", body.modelId(), ex);
        }
    }
}
