package dev.flower.stevedance.content;

import dev.flower.stevedance.core.Chart;

import java.util.ArrayList;
import java.util.List;

/**
 * osu!mania .osu v14 到 Chart 转换器
 * 支持 Mode:3 (mania)，CircleSize:4 (4K)
 * 仅支持 tap (bit0) 和 hold (bit128)
 */
public final class OsuManiaImport {
    // 最大输入大小：4 MiB
    private static final int MAX_INPUT_SIZE = 4 * 1024 * 1024;
    // 最大音符数量
    private static final int MAX_NOTES = 12000;
    // 四列固定位置（960x544 视口）
    private static final float[] LANE_X = {240f, 400f, 560f, 720f};
    private static final float LANE_Y = 272f;
    // 固定接近时间
    private static final int APPROACH_MS = 1200;
    // 固定难度名称
    private static final String DIFFICULTY = "custom";

    /**
     * 将 osu!mania .osu 文本转换为 Chart
     *
     * @param text osu!mania 文件内容
     * @param id 图表 ID
     * @param titleKey 标题键
     * @param audioDurationMs 音频时长（毫秒）
     * @return 转换后的 Chart
     * @throws IllegalArgumentException 当输入无效时
     */
    public static Chart read(String text, String id, String titleKey, int audioDurationMs) {
        // 输入验证
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Input text cannot be null or empty");
        }
        if (text.length() > MAX_INPUT_SIZE) {
            throw new IllegalArgumentException("Input exceeds 4MiB limit");
        }
        if (audioDurationMs < 1000 || audioDurationMs > 600000) {
            throw new IllegalArgumentException("Audio duration must be between 1000 and 600000 ms");
        }

        // 按行解析，支持 BOM
        String[] lines = text.split("\\r?\\n", -1);
        List<Chart.Note> notes = new ArrayList<>();
        String currentSection = null;
        int mode = -1;
        int circleSize = -1;
        boolean headerValidated = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 处理 BOM
            if (i == 0 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
                line = line.substring(1);
            }

            line = line.trim();

            // 跳过空行
            if (line.isEmpty()) {
                continue;
            }

            // 跳过注释
            if (line.startsWith("//")) {
                continue;
            }

            // 验证文件头（第一非空行）
            if (!headerValidated) {
                if (!line.equals("osu file format v14")) {
                    throw new IllegalArgumentException(
                            "Invalid file format: expected 'osu file format v14'");
                }
                headerValidated = true;
                continue;
            }

            // 检查节标题
            if (line.startsWith("[") && line.endsWith("]")) {
                String section = line.substring(1, line.length() - 1);
                if (section.equals("General")
                        || section.equals("Difficulty")
                        || section.equals("HitObjects")) {
                    currentSection = section;
                } else {
                    currentSection = null; // 忽略其他节
                }
                continue;
            }

            // 解析节内容
            if ("General".equals(currentSection)) {
                if (line.startsWith("Mode:")) {
                    try {
                        mode = Integer.parseInt(line.substring(5).trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid Mode value");
                    }
                }
            } else if ("Difficulty".equals(currentSection)) {
                if (line.startsWith("CircleSize:")) {
                    try {
                        circleSize = Integer.parseInt(line.substring(11).trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid CircleSize value");
                    }
                }
            } else if ("HitObjects".equals(currentSection)) {
                // 检查音符数量限制
                if (notes.size() >= MAX_NOTES) {
                    throw new IllegalArgumentException("Note count exceeds " + MAX_NOTES);
                }

                Chart.Note note = parseHitObject(line, audioDurationMs);
                if (note != null) {
                    notes.add(note);
                }
            }
        }

        // 验证文件头
        if (!headerValidated) {
            throw new IllegalArgumentException("Missing file format header");
        }

        // 验证模式
        if (mode != 3) {
            throw new IllegalArgumentException("Invalid mode: " + mode + " (expected 3 for mania)");
        }

        // 验证键数
        if (circleSize != 4) {
            throw new IllegalArgumentException(
                    "Invalid circle size: " + circleSize + " (expected 4)");
        }

        // 验证音符数量
        if (notes.isEmpty()) {
            throw new IllegalArgumentException("No valid notes found");
        }
        if (notes.size() > MAX_NOTES) {
            throw new IllegalArgumentException("Note count exceeds " + MAX_NOTES);
        }

        // 按时间排序（稳定排序）
        notes.sort((a, b) -> Integer.compare(a.timeMs(), b.timeMs()));

        // 创建并返回 Chart
        return new Chart(1, id, titleKey, audioDurationMs, APPROACH_MS, DIFFICULTY, notes);
    }

    /**
     * 解析单个 HitObject 行
     * 格式: x,y,time,type,hitSound,...
     * 对于 hold: x,y,time,type,hitSound,endTime:...
     */
    private static Chart.Note parseHitObject(String line, int audioDurationMs) {
        String[] parts = line.split(",");
        if (parts.length < 5) {
            throw new IllegalArgumentException("Malformed hit object: insufficient fields");
        }

        try {
            // 解析基本字段
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int time = Integer.parseInt(parts[2].trim());
            int type = Integer.parseInt(parts[3].trim());
            int hitSound = Integer.parseInt(parts[4].trim());

            // 验证 x 坐标（0-512）
            if (x < 0 || x > 512) {
                throw new IllegalArgumentException(
                        "Invalid x coordinate: " + x + " (must be 0-512)");
            }

            // 验证 y 坐标（整数）
            if (y < 0) {
                throw new IllegalArgumentException("Invalid y coordinate: " + y);
            }

            // 验证时间戳
            if (time < 0 || time > audioDurationMs) {
                throw new IllegalArgumentException("Invalid timestamp: " + time);
            }

            // 验证 hitSound
            if (hitSound < 0) {
                throw new IllegalArgumentException("Invalid hitSound: " + hitSound);
            }

            // 计算音轨（x 坐标映射到 0-3）
            int lane = x * 4 / 512;
            // 由于已验证 0 <= x <= 512，lane 必定在 0-3 范围内
            // 但为确保 x=512 时映射到 lane=3 而非 4
            if (lane > 3) {
                lane = 3;
            }

            // 验证类型标志
            // 允许的位: bit0 (tap), bit7 (hold, 128), bit2 (new combo, 4), bits4-6 (combo offset,
            // 16/32/64)
            int allowedFlags = 1 | 128 | 4 | 16 | 32 | 64;
            if ((type & ~allowedFlags) != 0) {
                throw new IllegalArgumentException("Unsupported hit object type: " + type);
            }

            // 检查是否为 tap 或 hold（必须恰好一个）
            boolean isTap = (type & 1) != 0;
            boolean isHold = (type & 128) != 0;

            if (isTap && isHold) {
                throw new IllegalArgumentException("Hit object cannot be both tap and hold");
            }
            if (!isTap && !isHold) {
                throw new IllegalArgumentException("Hit object must be either tap or hold");
            }

            // 处理音符
            if (isHold) {
                // 长按音符需要至少 6 个字段，且第 6 个字段包含结束时间（可能带冒号后缀）
                if (parts.length < 6) {
                    throw new IllegalArgumentException("Hold note requires end time");
                }

                String endTimeStr = parts[5].trim();
                // 去除可能的冒号后缀
                int colonIndex = endTimeStr.indexOf(':');
                if (colonIndex >= 0) {
                    endTimeStr = endTimeStr.substring(0, colonIndex);
                }

                int endTime;
                try {
                    endTime = Integer.parseInt(endTimeStr);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid hold end time: " + endTimeStr);
                }

                // 验证结束时间
                if (endTime <= time) {
                    throw new IllegalArgumentException("Hold end time must be after start time");
                }
                if (endTime > audioDurationMs) {
                    throw new IllegalArgumentException("Hold end time exceeds audio duration");
                }

                return new Chart.Note(
                        time,
                        endTime,
                        lane,
                        Chart.Kind.HOLD,
                        LANE_X[lane],
                        LANE_Y,
                        LANE_X[lane],
                        LANE_Y,
                        false);
            } else {
                // Tap 音符
                return new Chart.Note(
                        time,
                        time,
                        lane,
                        Chart.Kind.TAP,
                        LANE_X[lane],
                        LANE_Y,
                        LANE_X[lane],
                        LANE_Y,
                        false);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed hit object: invalid number format", e);
        }
    }
}
