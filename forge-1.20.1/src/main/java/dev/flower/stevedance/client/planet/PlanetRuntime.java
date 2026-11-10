package dev.flower.stevedance.client.planet;

import com.google.gson.JsonObject;

import dev.flower.stevedance.planet.PlanetGame;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 界面拥有的纯Java玩法会话，后台初始化与推进。 / Screen-owned Java game, initialized and stepped off-thread. */
public final class PlanetRuntime implements AutoCloseable {
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "Hello Planet Java");
                        t.setDaemon(true);
                        return t;
                    });
    private volatile PlanetGame game;
    private volatile boolean closed, busy = true;
    private volatile JsonObject state;
    private volatile String error = "";
    private volatile long requests, stepNanos, stepCount, maxStepNanos;

    public PlanetRuntime() {
        var mc = Minecraft.getInstance();
        var manager = mc.getResourceManager();
        Path save =
                mc.gameDirectory
                        .toPath()
                        .toAbsolutePath()
                        .normalize()
                        .resolve("config/stevedance/planet/progress.json");
        worker.execute(
                () -> {
                    try {
                        if (closed) {
                            return;
                        }
                        try (var data =
                                manager.open(
                                        new ResourceLocation(
                                                "stevedance", "planet/game-data.json.gz"))) {
                            game = new PlanetGame(data, save);
                        }
                        if (closed) {
                            game.close();
                            return;
                        }
                        state = game.snapshot();
                        requests++;
                    } catch (Exception e) {
                        fail(e);
                    } finally {
                        busy = false;
                    }
                });
    }

    private void fail(Exception e) {
        error = e.getMessage() == null ? e.toString() : e.getMessage();
        dev.flower.stevedance.SteveDanceMod.LOGGER.error("Hello Planet Java failed", e);
    }

    public boolean submit(String op, int held, int pressed) {
        return submit(op, held, pressed, -1);
    }

    public boolean submit(String op, int held, int pressed, int item) {
        if (closed || busy || !error.isEmpty() || state == null) {
            return false;
        }
        busy = true;
        worker.execute(
                () -> {
                    try {
                        if (!closed) {
                            long start = System.nanoTime();
                            state = game.command(op, held, pressed, item);
                            long elapsed = System.nanoTime() - start;
                            if (op.equals("step")) {
                                stepNanos += elapsed;
                                stepCount++;
                                maxStepNanos = Math.max(maxStepNanos, elapsed);
                            }
                            requests++;
                        }
                    } catch (Exception e) {
                        if (!closed) {
                            fail(e);
                        }
                    } finally {
                        busy = false;
                    }
                });
        return true;
    }

    public JsonObject state() {
        return state;
    }

    public String error() {
        return error;
    }

    public boolean busy() {
        return busy;
    }

    public boolean alive() {
        return !closed && game != null && error.isEmpty();
    }

    public long requests() {
        return requests;
    }

    public double averageStepMs() {
        return stepCount == 0 ? 0 : stepNanos / (double) stepCount / 1_000_000;
    }

    public double maxStepMs() {
        return maxStepNanos / 1_000_000.0;
    }

    @Override
    public void close() {
        closed = true;
        worker.execute(
                () -> {
                    if (game != null) {
                        game.close();
                    }
                });
        worker.shutdown();
    }
}
