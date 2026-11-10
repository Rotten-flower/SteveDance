package dev.flower.stevedance.client;

import dev.flower.stevedance.MotionPackLibrary;
import dev.flower.stevedance.core.PvAnimationCurve;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在 F2nd 字段定义中，源光照图与对象图是分开的。
 * <p>Source light graphs are separate from object graphs in F2nd field definitions.</p>
 */
public final class PvLighting {
    public record Light(
            int id,
            int flags,
            int type,
            float[] ambient,
            float[] diffuse,
            float[] specular,
            float[] position,
            float[] tone) {}

    public record Bloom(int flags, float[] color, float[] brightpass, float range) {}

    public record Correction(
            int flags,
            float hue,
            float saturation,
            float lightness,
            float exposure,
            float[] gamma,
            float contrast) {}

    public record Toon(
            int flags,
            float shine,
            float focus,
            float[] colorA,
            float[] colorB,
            float characterWidth,
            float stageWidth,
            float characterFace,
            float stageFace) {}

    public record Cut(double timeMs, String name, double startMs) {}

    public record Dof(
            int flags,
            float focus,
            float focusRange,
            float fuzzingRange,
            float ratio,
            float quality) {}

    public record Fog(
            int flags,
            int type,
            float density,
            float start,
            float end,
            float heightDensity,
            float heightStart,
            float heightEnd,
            float[] color,
            float[] heightColor) {}

    public record Data(
            int schema,
            String pipeline,
            Map<String, PvSceneData.Camera> animations,
            List<Cut> timeline,
            List<List<Light>> chara,
            List<List<Light>> stage,
            List<Bloom> bloom,
            List<Correction> correction,
            List<Toon> toon,
            List<Dof> dof,
            List<Fog> fog,
            List<PvSceneData.Event> events,
            String bloomMode,
            String characterAlbedo,
            String characterShading,
            Boolean characterAmbientEnabled) {}

    public boolean linearCharacterAlbedo() {
        return "srgb-albedo-v1".equals(data.characterAlbedo);
    }

    public int characterAlbedoMode() {
        return "source-square-v1".equals(data.characterAlbedo)
                ? 2
                : linearCharacterAlbedo() ? 1 : 0;
    }

    public boolean halfLambertCharacter() {
        return "source-half-lambert-v1".equals(data.characterShading);
    }

    public boolean intensityBloom() {
        return "reference-intensity-v1".equals(data.bloomMode);
    }

    public boolean hdr() {
        return data.schema == 2 && "hdr-source-v1".equals(data.pipeline);
    }

    public boolean dofEnabled;
    public int dofIndex = -1;
    public float dofFocus, dofRange, dofFuzz, dofRatio;
    public int fogType;
    public float fogDensity, fogStart, fogEnd;
    public final float[] fogColor = {1, 1, 1};
    public final float[] specular = {0, 0, 0},
            characterAmbient = {1, 1, 1},
            characterDiffuse = {0, 0, 0},
            characterPosition = {0, 100, 100};
    public final float[] characterColor = {1, 1, 1};
    private final float[] characterColorDiffuse = {0, 0, 0, 0},
            characterColorSpecular = {1, 1, 1, 0};
    public boolean characterColorActive;
    public final Data data;
    public final float[] ambient = {1, 1, 1},
            diffuse = {0, 0, 0},
            position = {0, 100, 100},
            character = {1, 1, 1},
            characterAdd = {0, 0, 0},
            bloomColor = {0, 0, 0},
            brightpass = {.9f, .9f, .9f},
            radius = {2, 2, 2},
            fade = {0, 0, 0, 0};
    public float saturation = 1,
            exposure = 1,
            lightness = 1,
            hue = 0,
            contrast = 0,
            toneExposure = 2,
            toneGamma = 1;
    public final float[] gamma = {1, 1, 1};
    public float edgeWidth;
    public final float[] edgeColor = {0, 0, 0};
    private double sampled = Double.NaN;

    private PvLighting(Data d) {
        data = d;
    }

    public static PvLighting load(Path root) throws IOException {
        Path p = root.resolve("render.json");
        if (!Files.isRegularFile(p)) {
            return null;
        }
        p = PvSceneData.safe(root, "render.json");
        if (Files.size(p) > 16 * 1024 * 1024) {
            throw new IOException("Render metadata exceeds budget");
        }
        Data d;
        try (var r = Files.newBufferedReader(p)) {
            d = MotionPackLibrary.GSON.fromJson(r, Data.class);
        }
        if ((d.schema != 1 && d.schema != 2)
                || d.animations.size() > 128
                || d.timeline.size() > 1024
                || d.chara.size() > 8
                || d.events.size() > 4096
                || d.toon.size() > 2048) {
            throw new IOException("Invalid render metadata");
        }
        if (d.schema == 2) {
            if (!"hdr-source-v1".equals(d.pipeline)
                    || d.dof == null
                    || d.fog == null
                    || d.dof.size() > 2048
                    || d.fog.size() > 256) {
                throw new IOException("Invalid HDR source profile");
            }
            for (var entry : d.dof) {
                if ((entry.flags & ~31) != 0
                        || ((entry.flags & 2) != 0 && entry.focusRange < 0)
                        || ((entry.flags & 4) != 0 && entry.fuzzingRange < 0)
                        || ((entry.flags & 8) != 0 && entry.ratio < 0)) {
                    throw new IOException("Unsupported DOFT entry");
                }
                for (float f :
                        new float[] {
                            entry.focus,
                            entry.focusRange,
                            entry.fuzzingRange,
                            entry.ratio,
                            entry.quality
                        }) {
                    if (!Float.isFinite(f)) {
                        throw new IOException("Nonfinite DOFT entry");
                    }
                }
            }
            for (var f : d.fog) {
                valid(f.color, 4);
                valid(f.heightColor, 4);
            }
        }
        for (var a : d.animations.values()) {
            for (var c : a.curves().values()) {
                c.validate();
            }
            for (var t : a.transforms().values()) {
                for (var c : t.values()) {
                    c.validate();
                }
            }
        }
        for (var section : d.chara) {
            if (section.size() > 1024) {
                throw new IOException("Light budget exceeded");
            }
            for (var l : section) {
                valid(l.ambient, 4);
                valid(l.diffuse, 4);
                valid(l.specular, 4);
                valid(l.position, 3);
            }
        }
        if (d.stage != null) {
            if (d.stage.size() > 8) {
                throw new IOException("Stage light section budget exceeded");
            }
            for (var section : d.stage) {
                if (section.size() > 1024) {
                    throw new IOException("Stage light budget exceeded");
                }
                for (var l : section) {
                    valid(l.ambient, 4);
                    valid(l.diffuse, 4);
                    valid(l.specular, 4);
                    valid(l.position, 3);
                }
            }
        }
        if (d.bloomMode != null && !"reference-intensity-v1".equals(d.bloomMode)) {
            throw new IOException("Unsupported bloom reconstruction");
        }
        if (d.characterAlbedo != null
                && !Set.of("srgb-albedo-v1", "source-square-v1").contains(d.characterAlbedo)) {
            throw new IOException("Unsupported character albedo transfer");
        }
        if (d.characterShading != null && !"source-half-lambert-v1".equals(d.characterShading)) {
            throw new IOException("Unsupported character light response");
        }
        for (var b : d.bloom) {
            valid(b.color, 3);
            valid(b.brightpass, 3);
            if (!Float.isFinite(b.range)) {
                throw new IOException("Invalid bloom range");
            }
        }
        for (var c : d.correction) {
            valid(c.gamma, 3);
            for (float f : new float[] {c.hue, c.saturation, c.lightness, c.exposure, c.contrast}) {
                if (!Float.isFinite(f)) {
                    throw new IOException("Invalid correction");
                }
            }
        }
        for (var t : d.toon) {
            valid(t.colorA, 3);
            valid(t.colorB, 3);
            if (!Float.isFinite(t.characterWidth) || !Float.isFinite(t.stageWidth)) {
                throw new IOException("Invalid edge width");
            }
        }
        return new PvLighting(d);
    }

    private static void valid(float[] a, int n) throws IOException {
        if (a == null || a.length != n) {
            throw new IOException("Render vector size mismatch");
        }
        for (float f : a) {
            if (!Float.isFinite(f)) {
                throw new IOException("Nonfinite render vector");
            }
        }
    }

    private static void copy(float[] from, float[] to) {
        System.arraycopy(from, 0, to, 0, to.length);
    }

    // 中文：当前的 type-0 A3DA 曲线是 STATIC_0，而不是缺失的属性。
    // EN: A present type-0 A3DA curve is STATIC_0, not an absent property.
    // 中文：只有缺失的通道才会保留表/默认值（Fog::load/get/ctrl）。
    // EN: Only a missing channel preserves the table/default value (Fog::load/get/ctrl).
    private static float curve(
            Map<String, PvAnimationCurve> c, String key, double frame, float fallback) {
        var v = c.get(key);
        return v == null ? fallback : v.sample(frame);
    }

    private static void color(
            Map<String, PvAnimationCurve> c, String key, double frame, float[] target) {
        for (int i = 0; i < 3; i++) {
            target[i] = curve(c, key + "." + "rgb".charAt(i), frame, target[i]);
        }
        if (target.length == 4) {
            target[3] = curve(c, key + ".a", frame, target[3]);
        }
    }

    /**
     * 确定性源状态：寻址和连续播放解析相同的参数。
     * <p>Deterministic source state: seeking and continuous playback resolve the same parameters.</p>
     */
    private void sampleHdr(double ms) {
        if (sampled == ms) {
            return;
        }
        sampled = ms;
        Arrays.fill(ambient, 1);
        Arrays.fill(diffuse, 0);
        Arrays.fill(specular, 0);
        Arrays.fill(characterAmbient, 1);
        Arrays.fill(characterDiffuse, 0);
        Arrays.fill(character, 1);
        Arrays.fill(characterAdd, 0);
        Arrays.fill(fade, 0);
        Arrays.fill(bloomColor, 0);
        Arrays.fill(gamma, 2);
        Arrays.fill(brightpass, 1);
        Arrays.fill(radius, 2);
        position[0] = 0;
        position[1] = 100;
        position[2] = 100;
        System.arraycopy(position, 0, characterPosition, 0, 3);
        saturation = exposure = lightness = 1;
        toneExposure = 2;
        toneGamma = 1;
        hue = contrast = edgeWidth = 0;
        dofIndex = -1;
        dofEnabled = false;
        dofFocus = dofRange = dofRatio = 0;
        dofFuzz = 1;
        fogType = 0;
        fogDensity = 0;
        fogStart = 20;
        fogEnd = 1000;
        Arrays.fill(fogColor, 1);
        // 中文：表提供持久默认值，而显式动画通道仅覆盖其
        // EN: Tables supply persistent defaults, while explicit animation channels override only
        // their
        // 中文：自身的属性。
        // EN: own properties.
        if (!data.bloom.isEmpty()) {
            apply(data.bloom.get(0));
        }
        if (!data.correction.isEmpty()) {
            apply(data.correction.get(0));
        }
        int lightIndex = 0, toonIndex = -1, stageIndex = -1;
        applyCharacterLight(0);
        for (var e : data.events) {
            if (e.timeMs() > ms) {
                break;
            }
            var args = e.args();
            switch (e.command()) {
                case "PV_CHARA_LIGHT" -> {
                    lightIndex = args[0];
                    applyCharacterLight(lightIndex);
                }
                case "TOON_EDGE" -> toonIndex = args[1];
                case "PV_STAGE_LIGHT" -> stageIndex = args[0];
                case "BLOOM" -> {
                    requireIndex(args[0], data.bloom.size(), "BLMT");
                    apply(data.bloom.get(args[0]));
                }
                case "COLOR_CORRECTION" -> {
                    if (args[0] != 0) {
                        requireIndex(args[1], data.correction.size(), "CCRT");
                        apply(data.correction.get(args[1]));
                    }
                }
                case "DOF" -> {
                    dofEnabled = args[0] != 0;
                    if (dofEnabled) {
                        requireIndex(args[1], data.dof.size(), "DOFT");
                        var oldFocus = dofFocus;
                        var oldRange = dofRange;
                        var oldFuzz = dofFuzz;
                        var oldRatio = dofRatio;
                        var d = data.dof.get(args[1]);
                        dofIndex = args[1];
                        float t =
                                args[2] <= 0
                                        ? 1
                                        : (float) Math.min(1, (ms - e.timeMs()) * .06 / args[2]);
                        if ((d.flags & 1) != 0) {
                            dofFocus = lerp(oldFocus, d.focus, t);
                        }
                        if ((d.flags & 2) != 0) {
                            dofRange = lerp(oldRange, d.focusRange, t);
                        }
                        if ((d.flags & 4) != 0) {
                            dofFuzz = lerp(oldFuzz, d.fuzzingRange, t);
                        }
                        if ((d.flags & 8) != 0) {
                            dofRatio = lerp(oldRatio, d.ratio, t);
                        }
                    }
                }
                // 中文：F2 FOG 是 [表索引, 过渡帧, 雾组]，而非 DOF 的 enable/index。
                // EN: F2 FOG is [table index, transition frames, fog group], not DOF's
                // enable/index.
                // 中文：原生 F2 校验（0x81234aba-0x81234ad0）拒绝缺失的
                // EN: Native F2 validation (0x81234aba-0x81234ad0) rejects an absent
                // 中文：FOGC 索引而不改变雾状态。PV711 实际使用此行为。
                // EN: FOGC index without changing fog state. PV711 actually uses this.
                case "FOG" -> {}
            }
        }
        sampleFog(ms);
        Arrays.fill(characterColorDiffuse, 0);
        Arrays.fill(characterColorSpecular, 1);
        characterColorSpecular[3] = 0;
        characterColorActive = false;
        float[] ca = characterAmbient, cd = characterDiffuse;
        if (stageIndex >= 0) {
            if (data.stage == null || data.stage.isEmpty()) {
                throw new IllegalArgumentException("Referenced stage light table unavailable");
            }
            requireIndex(stageIndex, data.stage.get(0).size(), "Stage LITC");
            var l = data.stage.get(0).get(stageIndex);
            if ((l.flags & 2) != 0) {
                copy(l.ambient, ambient);
            }
            if ((l.flags & 4) != 0) {
                copy(l.diffuse, diffuse);
            }
            if ((l.flags & 8) != 0) {
                copy(l.specular, specular);
            }
            if ((l.flags & 16) != 0) {
                copy(l.position, position);
            }
        }
        Cut cut = null;
        for (var c : data.timeline) {
            if (c.timeMs > ms) {
                break;
            }
            cut = c;
        }
        var a = cut == null ? null : data.animations.get(cut.name);
        if (a != null) {
            double frame = Math.max(0, (ms - cut.startMs) * .06);
            double size = Double.parseDouble(a.properties().getOrDefault("play_control.size", "0"));
            if (size > 0) {
                frame = Math.min(frame, size - 1);
            }
            var c = a.curves();
            toneExposure =
                    curve(c, "camera_auxiliary.exposure", frame, exposure * 2)
                            * curve(c, "camera_auxiliary.exposure_rate", frame, 1);
            toneGamma =
                    curve(c, "camera_auxiliary.gamma", frame, 1)
                            * curve(c, "camera_auxiliary.gamma_rate", frame, 1);
            saturation = curve(c, "camera_auxiliary.saturate", frame, saturation);
            for (int i = 0;
                    i < Integer.parseInt(a.properties().getOrDefault("light.length", "0"));
                    i++) {
                String k = "light." + i;
                int id = Integer.parseInt(a.properties().get(k + ".id"));
                if (id == 1) {
                    color(c, k + ".Ambient", frame, ambient);
                    color(c, k + ".Diffuse", frame, diffuse);
                    color(c, k + ".Specular", frame, specular);
                    samplePosition(a, k, frame, position);
                }
                if (id == 0 && lightIndex == 0) {
                    color(c, k + ".Ambient", frame, ca);
                    color(c, k + ".Diffuse", frame, cd);
                    samplePosition(a, k, frame, characterPosition);
                }
                if (id == 5) {
                    // 中文：CharColor Ambient 是边缘光参数，而非无条件 RGB 自发光。
                    // EN: CharColor Ambient is a rim parameter, not unconditional RGB emission.
                    // 中文：ReDIVA draw_object 在 Specular.a > 0 时启用此效果。源
                    // EN: ReDIVA draw_object gates this effect on Specular.a > 0. The source
                    // 中文：默认值为零；缺失的 alpha 保留该值，STATIC_0 显式清除它。
                    // EN: default is zero; missing alpha preserves it, STATIC_0 explicitly clears
                    // it.
                    color(c, k + ".Diffuse", frame, characterColorDiffuse);
                    color(c, k + ".Specular", frame, characterColorSpecular);
                    characterColorActive = characterColorSpecular[3] > 0;
                }
            }
            color(c, "post_process.Diffuse", frame, bloomColor);
            color(c, "post_process.Ambient", frame, radius);
            color(c, "post_process.Specular", frame, fade);
            for (int i = 0;
                    i < Integer.parseInt(a.properties().getOrDefault("fog.length", "0"));
                    i++) {
                String k = "fog." + i;
                if (Integer.parseInt(a.properties().get(k + ".id")) == 0) {
                    fogDensity = curve(c, k + ".density", frame, fogDensity);
                    fogStart = curve(c, k + ".start", frame, fogStart);
                    fogEnd = curve(c, k + ".end", frame, fogEnd);
                    color(c, k + ".Diffuse", frame, fogColor);
                }
            }
        } else {
            toneExposure = exposure * 2;
        }
        if (characterColorActive) {
            // 中文：活动的 CharColor 仍是重建的漫反射/镜面调整；
            // EN: Active CharColor remains a reconstructed diffuse/specular adjustment;
            // 中文：原始的边缘光和材质相关模式未在此处重现。
            // EN: the original rim and material-dependent modes are not reproduced here.
            float amount = Math.min(1, characterColorSpecular[3]);
            for (int i = 0; i < 3; i++) {
                character[i] = lerp(1, characterColorSpecular[i], amount);
                characterAdd[i] = characterColorDiffuse[i] * characterColorDiffuse[3] * amount;
            }
        }
        // 中文：Chara LITC 即使在舞台的角色环境光路由被
        // EN: Chara LITC stores ambient even when the stage's character ambient route is
        // 中文：禁用时也存储环境光。配置文件将此开关与未更改的源
        // EN: disabled. Profiles carry this gate separately from the unchanged source
        // 中文：颜色分开携带；旧导入在标志缺失时保留其响应。
        // EN: colors; older imports retain their response when the flag is absent.
        if (Boolean.FALSE.equals(data.characterAmbientEnabled)) {
            Arrays.fill(ca, 0);
        }
        copy(character, characterColor);
        for (int i = 0; i < 3; i++) {
            character[i] *= Math.max(0, ca[i] + cd[i] * .5f);
        }
        if (toonIndex >= 0) {
            requireIndex(toonIndex, data.toon.size(), "TETT");
            var t = data.toon.get(toonIndex);
            edgeWidth = t.characterWidth;
            copy(t.colorA, edgeColor);
        }
    }

    /**
     * LITC 标志零记录保留先前状态；重播片段也支持寻址。
     * <p>LITC flag-zero records retain the prior state; replaying cuts also supports seeking.</p>
     */
    private void applyCharacterLight(int index) {
        if (data.chara.isEmpty() || index < 0 || index >= data.chara.get(0).size()) {
            return;
        }
        var light = data.chara.get(0).get(index);
        if ((light.flags & 2) != 0) {
            copy(light.ambient, characterAmbient);
        }
        if ((light.flags & 4) != 0) {
            copy(light.diffuse, characterDiffuse);
        }
        if ((light.flags & 16) != 0) {
            copy(light.position, characterPosition);
        }
    }

    private void sampleFog(double ms) {
        Fog target = null;
        double started = 0, duration = 0;
        float density = 0, start = 20, end = 1000;
        float[] color = {1, 1, 1};
        for (var e : data.events) {
            if (e.timeMs() > ms) {
                break;
            }
            if (!e.command().equals("FOG") || e.args()[0] < 0 || e.args()[0] >= data.fog.size()) {
                continue;
            }
            if (e.args().length < 3 || e.args()[2] != -1) {
                throw new IllegalArgumentException("Unsupported F2 fog group");
            }
            // 中文：在此事件时间解析上一次过渡，包括被中断的淡入淡出。
            // EN: Resolve the previous transition at this event's time, including interrupted
            // fades.
            if (target != null) {
                float t =
                        duration <= 0 ? 1 : (float) Math.min(1, (e.timeMs() - started) / duration);
                density = lerp(density, target.density, t);
                start = lerp(start, target.start, t);
                end = lerp(end, target.end, t);
                for (int i = 0; i < 3; i++) {
                    color[i] = lerp(color[i], target.color[i], t);
                }
            }
            target = data.fog.get(e.args()[0]);
            started = e.timeMs();
            duration = Math.max(0, e.args()[1]) * 1000.0 / 60;
        }
        if (target != null) {
            float t = duration <= 0 ? 1 : (float) Math.min(1, (ms - started) / duration);
            fogType = target.type;
            fogDensity = lerp(density, target.density, t);
            fogStart = lerp(start, target.start, t);
            fogEnd = lerp(end, target.end, t);
            for (int i = 0; i < 3; i++) {
                fogColor[i] = lerp(color[i], target.color[i], t);
            }
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static void requireIndex(int i, int size, String table) {
        if (i < 0 || i >= size) {
            throw new IllegalArgumentException("Source " + table + " index out of range: " + i);
        }
    }

    private static void samplePosition(
            PvSceneData.Camera a, String key, double frame, float[] target) {
        var t = a.transforms().get(key + ".position.model_transform");
        if (t != null) {
            for (int axis = 0; axis < 3; axis++) {
                target[axis] = curve(t, "translation." + "xyz".charAt(axis), frame, target[axis]);
            }
        }
    }

    private void apply(Bloom b) {
        if ((b.flags & 1) != 0) {
            copy(b.color, bloomColor);
        }
        if ((b.flags & 2) != 0) {
            copy(b.brightpass, brightpass);
        }
        if ((b.flags & 4) != 0) {
            Arrays.fill(radius, b.range);
        }
    }

    private void apply(Correction c) {
        if ((c.flags & 1) != 0) {
            hue = c.hue;
        }
        if ((c.flags & 2) != 0) {
            saturation = c.saturation;
        }
        if ((c.flags & 4) != 0) {
            lightness = c.lightness;
        }
        if ((c.flags & 8) != 0) {
            exposure = c.exposure;
        }
        if ((c.flags & 16) != 0) {
            copy(c.gamma, gamma);
        }
        if ((c.flags & 32) != 0) {
            contrast = c.contrast;
        }
    }

    public void sample(double ms) {
        if (hdr()) {
            sampleHdr(ms);
            return;
        }
        if (sampled == ms) {
            return;
        }
        sampled = ms;
        Arrays.fill(ambient, 1);
        Arrays.fill(diffuse, 0);
        Arrays.fill(character, 1);
        Arrays.fill(characterAdd, 0);
        Arrays.fill(fade, 0);
        Arrays.fill(bloomColor, 0);
        Arrays.fill(gamma, 1);
        saturation = exposure = lightness = 1;
        toneExposure = 2;
        toneGamma = 1;
        hue = contrast = edgeWidth = 0;
        int lightIndex = 0, toonIndex = -1, bloomIndex = 0, correctionIndex = 0;
        for (var e : data.events) {
            if (e.timeMs() > ms) {
                break;
            }
            switch (e.command()) {
                case "PV_CHARA_LIGHT" -> lightIndex = e.args()[0];
                case "TOON_EDGE" -> toonIndex = e.args()[1];
                case "BLOOM" -> bloomIndex = e.args()[0];
                case "COLOR_CORRECTION" -> correctionIndex = e.args()[0] == 1 ? e.args()[1] : 0;
            }
        }
        if (!data.bloom.isEmpty()) {
            var b = data.bloom.get(Math.max(0, Math.min(bloomIndex, data.bloom.size() - 1)));
            copy(b.color, bloomColor);
            copy(b.brightpass, brightpass);
            Arrays.fill(radius, Math.max(.5f, Math.min(12, b.range)));
        }
        if (!data.correction.isEmpty()) {
            var c =
                    data.correction.get(
                            Math.max(0, Math.min(correctionIndex, data.correction.size() - 1)));
            saturation = c.saturation;
            exposure = c.exposure;
            lightness = c.lightness;
            hue = c.hue;
            contrast = c.contrast;
            copy(c.gamma, gamma);
        }
        float[] ca = {1, 1, 1}, cd = {0, 0, 0};
        if (!data.chara.isEmpty() && lightIndex >= 0 && lightIndex < data.chara.get(0).size()) {
            var l = data.chara.get(0).get(lightIndex);
            if ((l.flags & 2) != 0) {
                copy(Arrays.copyOf(l.ambient, 3), ca);
            }
            if ((l.flags & 4) != 0) {
                copy(Arrays.copyOf(l.diffuse, 3), cd);
            }
        }
        Cut cut = null;
        for (var c : data.timeline) {
            if (c.timeMs > ms) {
                break;
            }
            cut = c;
        }
        var a = cut == null ? null : data.animations.get(cut.name);
        if (a != null) {
            double frame = Math.max(0, (ms - cut.startMs) * .06);
            var c = a.curves();
            saturation = curve(c, "camera_auxiliary.saturate", frame, saturation);
            toneExposure =
                    curve(c, "camera_auxiliary.exposure", frame, toneExposure)
                            * curve(c, "camera_auxiliary.exposure_rate", frame, 1);
            toneGamma =
                    curve(c, "camera_auxiliary.gamma", frame, toneGamma)
                            * curve(c, "camera_auxiliary.gamma_rate", frame, 1);
            for (int i = 0;
                    i < Integer.parseInt(a.properties().getOrDefault("light.length", "0"));
                    i++) {
                String k = "light." + i;
                int id = Integer.parseInt(a.properties().get(k + ".id"));
                if (id == 1) {
                    color(c, k + ".Ambient", frame, ambient);
                    color(c, k + ".Diffuse", frame, diffuse);
                    var t = a.transforms().get(k + ".position");
                    if (t != null) {
                        for (int axis = 0; axis < 3; axis++) {
                            position[axis] =
                                    curve(
                                            t,
                                            "translation." + "xyz".charAt(axis),
                                            frame,
                                            position[axis]);
                        }
                    }
                }
                if (id == 0 && lightIndex == 0) {
                    color(c, k + ".Ambient", frame, ca);
                    color(c, k + ".Diffuse", frame, cd);
                }
                if (id == 5) {
                    color(c, k + ".Ambient", frame, characterAdd);
                    color(c, k + ".Specular", frame, character);
                }
            }
            color(c, "post_process.Diffuse", frame, bloomColor);
            color(c, "post_process.Ambient", frame, radius);
            color(c, "post_process.Specular", frame, fade);
        }
        for (int i = 0; i < 3; i++) {
            character[i] *= Math.max(0, ca[i] + cd[i] * .5f);
        }
        if (toonIndex >= 0 && toonIndex < data.toon.size()) {
            var t = data.toon.get(toonIndex);
            edgeWidth = Math.max(0, Math.min(4, t.characterWidth));
            copy(t.colorA, edgeColor);
        }
    }
}
