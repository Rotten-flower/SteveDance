package dev.flower.stevedance.core;

/**
 * 单个原作演员槽位的外观选择，独立于玩法状态。
 * <p>A cosmetic choice for one authored actor slot, independent of gameplay state.</p>
 */
public record PerformerModelChoice(Kind kind, String modelId) {
    public enum Kind {
        STEVE,
        PLAYER,
        ALEX,
        YSM,
        MAID,
        SKIN,
        ONLINE_PLAYER
    }

    public PerformerModelChoice {
        if (kind == null) {
            throw new IllegalArgumentException("Missing model kind");
        }
        modelId = modelId == null ? "" : modelId;
        if (kind == Kind.ONLINE_PLAYER) {
            modelId = java.util.UUID.fromString(modelId).toString();
        } else if (kind == Kind.SKIN) {
            if (!modelId.matches("[0-9a-f]{64}:(wide|slim)")) {
                throw new IllegalArgumentException("Invalid skin ID");
            }
        } else if (kind != Kind.YSM && kind != Kind.MAID) {
            modelId = "";
        } else if (modelId.isBlank()
                || modelId.length() > 256
                || modelId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid model ID");
        }
    }

    public static PerformerModelChoice player() {
        return new PerformerModelChoice(Kind.PLAYER, "");
    }
}
