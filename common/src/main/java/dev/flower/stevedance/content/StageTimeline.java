package dev.flower.stevedance.content;

/**
 * 服务端演出时间轴，单位为毫秒及游戏 tick。
 * <p>Server timeline in milliseconds and game ticks.</p>
 */
public final class StageTimeline {
    private int position;
    private long tick;
    private boolean paused;

    public StageTimeline(int position, long tick) {
        this.position = position;
        this.tick = tick;
    }

    public int position(long now) {
        return (int)
                Math.min(Integer.MAX_VALUE, position + (paused ? 0 : Math.max(0, now - tick) * 50));
    }

    public boolean paused() {
        return paused;
    }

    public void pause(long now) {
        position = position(now);
        tick = now;
        paused = true;
    }

    public void resume(long now) {
        if (paused) {
            tick = now;
            paused = false;
        }
    }

    public void seek(int target, long now) {
        if (target < 0) {
            throw new IllegalArgumentException("Negative time");
        }
        position = target;
        tick = now;
    }
}
