package dev.flower.stevedance.core;

import java.util.Map;

/**
 * 女仆原生整脸闭眼面片，只表达双眼闭合。
 * <p>Native whole-face overlays cannot express unilateral winks.</p>
 */
public final class MaidBlinkState {
    private MaidBlinkState() {}

    public static boolean closed(Map<String, Float> authored, double ticks, long actorPhase) {
        if (authored != null) {
            float both =
                    authored.getOrDefault("blink", 0f) + authored.getOrDefault("smileEyes", 0f);
            return Math.min(
                            both + authored.getOrDefault("winkLeft", 0f),
                            both + authored.getOrDefault("winkRight", 0f))
                    >= .5f;
        }
        // 与 TLM 原生 60 tick 周期、最后 5 tick 闭眼一致，暂停时使用冻结的演出时间。
        // 中文：保持原生更新节奏，暂停时调用方使用冻结的播放时钟。
        // EN: Match the native cadence; callers use the frozen playback clock while paused.
        double cycle = (ticks + Math.floorMod(actorPhase, 10)) % 60;
        return cycle > 55 && cycle < 60;
    }
}
