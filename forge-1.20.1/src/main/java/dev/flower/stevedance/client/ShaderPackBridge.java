package dev.flower.stevedance.client;

/**
 * 着色器包激活时，Oculus拥有实体着色器。
 * <p>Oculus owns entity shaders while a shader pack is active.</p>
 */
public final class ShaderPackBridge {
    private static boolean checked;
    private static Object api;
    private static java.lang.reflect.Method inUse;

    private ShaderPackBridge() {}

    public static boolean active() {
        if (!checked) {
            checked = true;
            if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                try {
                    var type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    api = type.getMethod("getInstance").invoke(null);
                    inUse = type.getMethod("isShaderPackInUse");
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Oculus shader API unavailable", ex);
                }
            }
        }
        if (api == null) {
            return false;
        }
        try {
            return (boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Oculus shader state unavailable", ex);
        }
    }
}
