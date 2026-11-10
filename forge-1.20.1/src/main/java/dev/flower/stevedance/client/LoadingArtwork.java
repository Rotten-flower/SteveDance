package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 曲包缩略图每三秒随机轮播。 / Shuffle installed pack thumbnails every three seconds. */
public final class LoadingArtwork {
    private final java.util.List<String> covers = new java.util.ArrayList<>();
    private boolean galleryReady;
    private long slide = -1;
    private int coverIndex = -1;

    public LoadingArtwork() {
        prepare();
    }

    private void prepareGallery() {
        if (galleryReady || !SongThumbnailCache.ready()) {
            return;
        }
        covers.addAll(SongThumbnailCache.availableIds());
        java.util.Collections.shuffle(covers);
        galleryReady = true;
    }

    public String coverAt(double seconds) {
        prepareGallery();
        if (covers.isEmpty()) {
            return "";
        }
        long next = (long) (Math.max(0, seconds) / 3);
        if (next != slide) {
            slide = next;
            coverIndex = (coverIndex + 1) % covers.size();
            if (coverIndex == 0 && next > 0 && covers.size() > 1) {
                String previous = covers.get(covers.size() - 1);
                java.util.Collections.shuffle(covers);
                if (covers.get(0).equals(previous)) {
                    java.util.Collections.swap(covers, 0, 1);
                }
            }
        }
        return covers.get(coverIndex);
    }

    private final long began = System.nanoTime();

    public static void prepare() {
        SongThumbnailCache.prepare();
        OriginalUiAssets.prepare();
    }

    public void draw(
            GuiGraphics g,
            int width,
            int height,
            Component song,
            Component status,
            boolean controls) {
        draw(g, width, height, RhythmClientController.song, song, status, controls);
    }

    public void draw(
            GuiGraphics g,
            int width,
            int height,
            String songId,
            Component song,
            Component status,
            boolean controls) {
        double seconds = (System.nanoTime() - began) / 1e9;
        g.fill(0, 0, width, height, 0xff10272d);
        String cover = coverAt(seconds);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1, 1, 1, 1);
        if (!cover.isEmpty()) {
            SongThumbnailCache.drawCover(g, cover, width, height);
        }
        g.fillGradient(0, height * 2 / 5, width, height, 0x0010272d, 0x9910272d);
        OriginalUiAssets.begin(g, width, height);
        var font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().translate(48, 520, 0);
        g.pose().scale(2.3f, 2.3f, 1);
        g.drawString(font, song, 0, 0, 0xffffffff, false);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(48, 570, 0);
        g.pose().scale(1.7f, 1.7f, 1);
        g.drawString(font, status, 0, 0, 0xffb9f1f4, false);
        g.pose().popPose();
        // 中文：使用原生128像素帧和最近邻采样，固定图集格保持定位稳定。
        // EN: Native 128px frames, nearest sampling; fixed atlas cell keeps registration stable.
        int[] ends = {300, 460, 640, 1040, 1440, 1620, 1800, 2100};
        int clock = (int) (seconds * 1000) % 2100;
        int frame = 0;
        while (frame < 7 && clock >= ends[frame]) {
            frame++;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        var tea = new ResourceLocation("stevedance", "textures/gui/miku_tea.png");
        g.blit(tea, 1090, 514, 128, 128, frame * 128, 0, 128, 128, 1024, 128);
        var caption = Component.translatable("ui.stevedance.loading.tea");
        g.pose().pushPose();
        g.pose().translate(1154 - font.width(caption) * .75f, 632, 0);
        g.pose().scale(1.5f, 1.5f, 1);
        g.drawString(font, caption, 0, 0, 0xffe1f4f6, false);
        g.pose().popPose();
        // 原作加载字母放在提示下方，保留原曲线和透明背景。 / Native loading letters below the caption.
        g.pose().pushPose();
        g.pose().translate(1154, 670, 0);
        g.pose().scale(.4f, .4f, 1);
        g.pose().translate(-1024, -672, 0);
        OriginalUiAssets.loop(g, "loading_screen", "load_txt", seconds);
        g.pose().popPose();
        OriginalUiAssets.end(g);
    }
}
