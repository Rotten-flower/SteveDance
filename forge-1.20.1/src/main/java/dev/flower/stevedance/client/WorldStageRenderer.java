package dev.flower.stevedance.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.WorldStage;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public final class WorldStageRenderer implements BlockEntityRenderer<WorldStage.Anchor> {
    public WorldStageRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(
            WorldStage.Anchor stage,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay) {
        dev.flower.stevedance.client.ArcadeMesh.render(
                pose,
                buffers,
                light,
                overlay,
                stage.yaw(),
                (stage.getLevel().getGameTime() + partialTick) / 20.0,
                true);
    }

    @Override
    public boolean shouldRenderOffScreen(WorldStage.Anchor stage) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return 64;
    }

    @Mod.EventBusSubscriber(
            modid = "stevedance",
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void register(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(WorldStage.TYPE.get(), WorldStageRenderer::new);
        }
    }
}
