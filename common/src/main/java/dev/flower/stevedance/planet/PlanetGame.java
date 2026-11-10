package dev.flower.stevedance.planet;

import static dev.flower.stevedance.planet.Values.CURRENT;
import static dev.flower.stevedance.planet.Values.Context;
import static dev.flower.stevedance.planet.Values.Fn;
import static dev.flower.stevedance.planet.Values.Instance;
import static dev.flower.stevedance.planet.Values.Scope;
import static dev.flower.stevedance.planet.Values.attr;
import static dev.flower.stevedance.planet.Values.call;
import static dev.flower.stevedance.planet.Values.copy;
import static dev.flower.stevedance.planet.Values.dict;
import static dev.flower.stevedance.planet.Values.fromJson;
import static dev.flower.stevedance.planet.Values.integer;
import static dev.flower.stevedance.planet.Values.item;
import static dev.flower.stevedance.planet.Values.list;
import static dev.flower.stevedance.planet.Values.module;
import static dev.flower.stevedance.planet.Values.number;
import static dev.flower.stevedance.planet.Values.put;
import static dev.flower.stevedance.planet.Values.str;
import static dev.flower.stevedance.planet.Values.toJson;
import static dev.flower.stevedance.planet.Values.truth;
import static dev.flower.stevedance.planet.Values.unpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 独立 Java 游戏会话；也可在无头奇偶校验中运行。
 * <p>Standalone Java gameplay session; also runs in headless parity checks.</p>
 */
public final class PlanetGame {
    private final Context context;
    private final Path savePath;
    private boolean closed;
    private int highScore;
    private String saveResult = "";
    private long frames;

    @SuppressWarnings("unchecked")
    public PlanetGame(InputStream data, Path savePath) throws IOException {
        this.savePath = savePath;
        Path highPath = savePath.resolveSibling("high-score.json");
        if (Files.isRegularFile(highPath)) {
            try {
                highScore =
                        Math.max(
                                0,
                                Math.min(
                                        999999,
                                        JsonParser.parseString(Files.readString(highPath))
                                                .getAsJsonObject()
                                                .get("highScore")
                                                .getAsInt()));
            } catch (RuntimeException ex) {
                throw new IOException("Invalid Planet high score", ex);
            }
        }
        try (var reader =
                new InputStreamReader(new GZIPInputStream(data), StandardCharsets.UTF_8)) {
            context =
                    new Context(
                            (Map<String, Object>)
                                    (Map<?, ?>) fromJson(JsonParser.parseReader(reader)));
        }
        within(
                () -> {
                    for (String name : context.data.keySet()) {
                        if (context.data.get(name) instanceof Map<?, ?>
                                && !name.startsWith("asset:")
                                && !name.equals("translated_text")) {
                            Scope scope = new Scope(context.builtins, null);
                            context.modules.put(name, scope);
                            ((Map<?, ?>) context.data.get(name))
                                    .forEach((k, v) -> scope.set(str(k), v));
                        }
                    }
                    for (var entry : context.modules.entrySet()) {
                        GameModules.define(entry.getValue(), entry.getKey());
                    }
                    installNativeBindings();
                    for (var entry : context.modules.entrySet()) {
                        GameModules.link(entry.getValue(), entry.getKey());
                    }
                    module("preview_runtime").set("SAVE_PATH", savePath);
                    invoke("preview_runtime", "reset");
                    Object session = module("preview_runtime").get("session");
                    if (truth(call(attr(session, "load_progress"), list(savePath), dict()))) {
                        syncWorld();
                    }
                    retainHighScore();
                    return null;
                });
    }

    private <T> T within(java.util.function.Supplier<T> action) {
        Context previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private Object invoke(String module, String name, Object... args) {
        return call(module(module).get(name), list(args), dict());
    }

    private void syncWorld() {
        Scope r = module("preview_runtime");
        r.set("world", attr(r.get("session"), "world"));
        invoke("preview_runtime", "bind_textures");
    }

    private void installNativeBindings() {
        module("behavior").set("f32", (Fn) (a, k) -> (double) (float) number(a.get(0)));
        Fn words =
                (a, k) ->
                        unpack(
                                list(
                                        "<" + integer(a.get(1)) + "I",
                                        module("preview_runtime").get("MODULE"),
                                        integer(a.get(0)) + 192));
        module("preview_content").set("words", words);
        module("preview_runtime").set("words", words);
        module("preview_runtime")
                .set(
                        "number",
                        (Fn)
                                (a, k) ->
                                        item(
                                                unpack(
                                                        list(
                                                                "<f",
                                                                module("preview_runtime")
                                                                        .get("MODULE"),
                                                                integer(a.get(0)) + 192)),
                                                0L));
        Fn sourceText =
                (a, k) -> {
                    byte[] bytes = (byte[]) module("preview_runtime").get("MODULE");
                    int start = (int) integer(a.get(0)) + 192, end = start;
                    while (bytes[end] != 0) {
                        end++;
                    }
                    return new String(bytes, start, end - start, Charset.forName("windows-31j"));
                };
        module("preview_content").set("source_text", sourceText);
        module("preview_content")
                .set(
                        "text",
                        (Fn)
                                (a, k) -> {
                                    Object t =
                                            ((Map<?, ?>) context.data.get("translated_text"))
                                                    .get(str(a.get(0)));
                                    if (t != null) {
                                        return t;
                                    }
                                    Object original = sourceText.apply(a, k);
                                    if (str(original).isEmpty()) {
                                        return "";
                                    }
                                    throw new IllegalArgumentException(
                                            "Missing translated text " + a.get(0));
                                });
        module("preview_session")
                .set(
                        "load_planes",
                        (Fn) (a, k) -> copy(item(context.data.get("planes"), a.get(0))));
    }

    private void retainHighScore() {
        Object player = attr(module("preview_runtime").get("world"), "player");
        int current = (int) Math.max(integer(item(player, 0x9a8L)), integer(item(player, 0x9a4L)));
        int best = Math.max(highScore, current);
        put(player, 0x9a8L, (long) best);
        if (best > highScore) {
            JsonObject data = new JsonObject();
            data.addProperty("highScore", best);
            try {
                Files.createDirectories(savePath.toAbsolutePath().getParent());
                Path target = savePath.resolveSibling("high-score.json"),
                        temp = savePath.resolveSibling("high-score.json.tmp");
                Files.writeString(temp, data.toString(), StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                highScore = best;
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    public JsonObject snapshot() {
        return within(
                () -> {
                    JsonObject s = toJson(invoke("preview_runtime", "snapshot")).getAsJsonObject();
                    s.addProperty("host_save_result", saveResult);
                    s.add("audio_sounds", toJson(module("preview_content").get("SOUNDS")));
                    return s;
                });
    }

    public JsonObject command(String op, int held, int pressed, int item) {
        if (closed) {
            throw new IllegalStateException("Game closed");
        }
        return within(
                () -> {
                    Object session = module("preview_runtime").get("session");
                    switch (op) {
                        case "step" -> {
                            invoke("preview_runtime", "advance", (long) held, (long) pressed);
                            frames++;
                        }
                        case "reset" -> {
                            invoke("preview_runtime", "reset");
                            saveResult = "";
                        }
                        case "equip" -> call(attr(session, "equip"), list((long) item), dict());
                        case "save" ->
                                saveResult =
                                        truth(
                                                        call(
                                                                attr(session, "save_progress"),
                                                                list(savePath),
                                                                dict()))
                                                ? "saved"
                                                : "save_unavailable";
                        case "load" -> {
                            saveResult =
                                    truth(
                                                    call(
                                                            attr(session, "load_progress"),
                                                            list(savePath),
                                                            dict()))
                                            ? "loaded"
                                            : "load_unavailable";
                            syncWorld();
                        }
                        case "finish-ending" -> {
                            call(attr(session, "finish_ending"), list(), dict());
                            syncWorld();
                        }
                        default ->
                                throw new IllegalArgumentException("Unknown game operation: " + op);
                    }
                    retainHighScore();
                    return snapshot();
                });
    }

    public void close() {
        closed = true;
    }

    public long frames() {
        return frames;
    }

    /**
     * 用于开发时差分验证的完整稀疏字段。
     * <p>Full sparse fields for development-only differential verification.</p>
     */
    public JsonObject verificationState() {
        return within(
                () -> {
                    Object world = module("preview_runtime").get("world");
                    Map<Object, Object> result =
                            dict(
                                    "player",
                                    attr(world, "player"),
                                    "hazard",
                                    attr(world, "hazard"),
                                    "enemies",
                                    attr(attr(world, "enemies"), "pool"),
                                    "moving",
                                    item(attr(world, "map"), "moving"),
                                    "active",
                                    attr(world, "active"));
                    Object quiz = attr(world, "quiz");
                    if (quiz instanceof Instance instance) {
                        result.put("quiz", instance.fields);
                    }
                    return toJson(result).getAsJsonObject();
                });
    }
}
