package dev.flower.stevedance.content;

import java.util.Map;
import java.util.Set;

/**
 * 歌曲文本的三语选择。
 * <p>Three-language song text selection.</p>
 */
public final class SongLanguages {
    private SongLanguages() {}

    public static String locale(String language) {
        if (language != null && language.startsWith("zh")) {
            return "zh_cn";
        }
        if (language != null && language.startsWith("ja")) {
            return "ja_jp";
        }
        return "en_us";
    }

    public static String select(Map<String, String> texts, String language, String original) {
        if (texts == null) {
            return original;
        }
        return texts.getOrDefault(locale(language), original);
    }

    public static void validate(Map<String, String> texts, int limit, boolean multiline) {
        if (texts == null) {
            return;
        }
        if (texts.size() > 3) {
            throw new IllegalArgumentException("Too many song languages");
        }
        for (var entry : texts.entrySet()) {
            String value = entry.getValue();
            if (!Set.of("zh_cn", "ja_jp", "en_us").contains(entry.getKey())
                    || value == null
                    || value.isBlank()
                    || value.length() > limit
                    || value.indexOf('§') >= 0
                    || !multiline && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
                throw new IllegalArgumentException("Invalid localized song text");
            }
        }
    }
}
