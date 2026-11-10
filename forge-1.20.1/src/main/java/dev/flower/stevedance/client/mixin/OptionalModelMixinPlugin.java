package dev.flower.stevedance.client.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * 可选联动仅限已核对的YSM2.6.5 Forge1.20.1二进制。
 * <p>Optional integration is restricted to the inspected YSM 2.6.5 Forge 1.20.1 binary.</p>
 */
public final class OptionalModelMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String p) {}

    public String getRefMapperConfig() {
        return null;
    }

    public boolean shouldApplyMixin(String target, String mixin) {
        if (!mixin.endsWith("YsmAnimationControllerMixin")
                && !mixin.endsWith("YsmPropMixin")
                && !mixin.endsWith("YsmLightMixin")
                && !mixin.endsWith("YsmSelectedVanillaMixin")) {
            return true;
        }
        return net.minecraftforge.fml.loading.LoadingModList.get().getMods().stream()
                .anyMatch(
                        m ->
                                m.getModId().equals("yes_steve_model")
                                        && m.getVersion()
                                                .toString()
                                                .equals("2.6.5-forge+mc1.20.1"));
    }

    public void acceptTargets(Set<String> mine, Set<String> others) {}

    public List<String> getMixins() {
        return null;
    }

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
