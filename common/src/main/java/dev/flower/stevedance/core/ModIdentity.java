package dev.flower.stevedance.core;

/**
 * 四个平台共享此层，不引入Minecraft或加载器类型。
 * <p>Shared across all four platforms; keep Minecraft/loader types out of this layer.</p>
 */
public final class ModIdentity {
    public static final String ID = "stevedance";
    public static final String AUTHOR = "Rotten-flower";

    private ModIdentity() {}
}
