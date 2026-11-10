package dev.flower.stevedance.core;

/**
 * 依据各游戏演出参数离线编译原作图形时钟。
 * <p>Authored graph clocks compiled offline from each game's play parameters.</p>
 */
public record PvSourceGraphClock(
        double timeMs,
        double endMs,
        double frameStart,
        double framesPerSecond,
        double loopBegin,
        double loopEnd) {
    public void validate() {
        if (!Double.isFinite(timeMs + endMs + frameStart + framesPerSecond + loopBegin + loopEnd)
                || timeMs < 0
                || endMs <= timeMs
                || framesPerSecond < 0
                || framesPerSecond > 1000
                || frameStart < 0
                || loopBegin < 0
                || loopEnd < loopBegin) {
            throw new IllegalArgumentException("Invalid authored graph clock");
        }
    }

    public double sample(double musicTimeMs, double lastFrame) {
        double frame = frameStart + Math.max(0, musicTimeMs - timeMs) * framesPerSecond / 1000;
        if (loopEnd > loopBegin && frame >= loopEnd) {
            frame = loopBegin + (frame - loopBegin) % (loopEnd - loopBegin);
        }
        return Math.max(0, Math.min(lastFrame, frame));
    }
}
