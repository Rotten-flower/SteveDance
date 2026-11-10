package dev.flower.stevedance.core;

import java.util.Map;

/**
 * 使用已确认的F2/X表情预设语义，眼睛开合仍以现有采样为准。
 * <p>Confirmed F2/X preset semantics; existing sampled eye aperture stays authoritative.</p>
 */
public final class PvExpressionPresets {
    private PvExpressionPresets() {}

    public static void eyebrows(Map<String, Float> channels, int expression, float weight) {
        // 同版本原作预设表：0 sad，1 laugh，6 smile，40 supserious。
        // 中文：这些ID描述来源情绪，不代表目标模型按数字命名的嘴部网格。
        // EN: These IDs describe source emotion, never the target model's numbered mouth meshes.
        String channel =
                switch (expression) {
                    case 0 -> "browTroubled";
                    case 1, 6 -> "browSmile";
                    case 40 -> "browSerious";
                    default -> null;
                };
        if (channel != null && weight > 0) {
            channels.merge(channel, weight, Math::max);
        }
    }
}
