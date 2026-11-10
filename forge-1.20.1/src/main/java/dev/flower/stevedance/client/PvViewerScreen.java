package dev.flower.stevedance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * 播放控件叠加在实时Minecraft渲染的PV上。
 * <p>Playback controls overlay the live Minecraft-rendered PV.</p>
 */
public final class PvViewerScreen extends Screen {
    private final LoadingArtwork artwork = new LoadingArtwork();
    private final dev.flower.stevedance.client.PlaybackOverlay overlay =
            new dev.flower.stevedance.client.PlaybackOverlay();

    private boolean cameraDragging;

    @Override
    protected void init() {
        wakeControls();
    }

    @Override
    public void mouseMoved(double x, double y) {
        wakeControls();
        super.mouseMoved(x, y);
    }

    public PvViewerScreen() {
        super(
                dev.flower.stevedance.client.SongText.title(
                        dev.flower.stevedance.SongSelectionCatalog.displayTitleKey(
                                PvPlaybackController.song())));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mx, int my, float partial) {
        int events = GamepadInput.navigate(this);
        if ((events & 2) != 0) {
            onClose();
            return;
        }
        if (events != 0) {
            wakeControls();
        }
        if (!PvPlaybackController.status.isEmpty()) {
            artwork.draw(
                    graphics,
                    width,
                    height,
                    PvPlaybackController.song(),
                    title,
                    Component.translatable(PvPlaybackController.status),
                    false);
            return;
        }
        double time = PvPlaybackController.timeMs();
        if (PvAetTimeline.ready()) {
            PvAetTimeline.draw(graphics, width, height, time);
        }

        PvLyrics.draw(graphics, width, height, time);
        String hint = Component.translatable(PvPlaybackController.playerViewing()
                ? "ui.stevedance.pv_viewer.player_controls" : "ui.stevedance.pv_viewer.hint").getString();
        if (!PvPlaybackController.playerViewing() && StageCamera.mode() == StageCamera.Mode.FREE) {
            hint += "\n" + Component.translatable("ui.stevedance.pv_viewer.free_hint").getString();
        }
        overlay.render(
                graphics,
                width,
                height,
                (int) time,
                PvPlaybackController.durationMs(),
                PvPlaybackController.paused(),
                Component.literal(hint));
        drawTopActions(graphics);
        PerformanceControls.draw(graphics, width, height, mx, my);
    }

    // 仅真实操作唤出控件，空格不更新计时。Only deliberate input wakes controls; Space never resets idle time.
    private void wakeControls() {
        overlay.wake();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        wakeControls();
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && cameraCanDrag()) {
            cameraDragging = true;
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && overlay.visibility() > .1f) {
            for (var action : topActions()) {
                if (x >= action.left() - 3 && x <= action.right() + 3 && y >= 8 && y <= 23) {
                    runTopAction(action.id());
                    return true;
                }
            }
        }
        if (super.mouseClicked(x, y, button)) {
            return true;
        }
        if (PerformanceControls.click(x, y, button, width, height)) {
            return true;
        }
        if (overlay.click(
                x,
                y,
                button,
                !PvPlaybackController.gameMode(),
                PvPlaybackController::togglePause)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && cameraDragging && cameraCanDrag()) {
            StageCamera.drag(dx, dy);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && overlay.drag(x)) {
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            cameraDragging = false;
        }
        if (overlay.release(x, button, PvPlaybackController::seekAbsolute)) {
            return true;
        }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (dev.flower.stevedance.client.PerformanceControls.scroll(x, y, amount, width, height)) {
            return true;
        }
        wakeControls();
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_F9 && PvPlaybackController.canWatchAsPlayer()) {
            // F9 matches the top action in both modes; F10 returns to the PV rig.
            // F9与顶部入口一致；F10返回预览机位。
            PvPlaybackController.watchAsPlayer();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F10 && PvPlaybackController.playerViewing()) {
            PvPlaybackController.returnToPreview();
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE && StageCamera.mode() != StageCamera.Mode.FREE) {
            PvPlaybackController.togglePause();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            PvPlaybackController.seekRelative(-5000);
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            PvPlaybackController.seekRelative(5000);
            return true;
        }
        if (key == GLFW.GLFW_KEY_F5 && PvPlaybackController.playerViewing()) {
            minecraft.options.setCameraType(minecraft.options.getCameraType().cycle());
            return true;
        }
        if (dev.flower.stevedance.client.PerformanceControls.key(key)) {
            return true;
        }
        if (key == GLFW.GLFW_KEY_P) {
            PvPlaybackController.togglePause();
            return true;
        }

        // 功能键只执行功能，不唤出或延长控件。Functional keys never wake or extend the overlay.
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) {
            return super.keyPressed(key, scan, modifiers);
        }
        wakeControls();
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void onClose() {
        PvPlaybackController.stop(true);
    }

    private record TopAction(int id, Component label, float left, float right, float scale) {}

    /** One layout is shared by rendering and hit testing. 顶部入口显示与点击共用坐标。 */
    private java.util.List<TopAction> topActions() {
        var labels = new java.util.LinkedHashMap<Integer, Component>();
        if (!PvPlaybackController.playerViewing()) {
            labels.put(5, Component.translatable("ui.stevedance.pv_viewer.camera_cycle", StageCamera.label(StageCamera.mode())));
            labels.put(4, Component.translatable("ui.stevedance.pv_viewer.camera_free"));
            labels.put(8, Component.translatable("ui.stevedance.pv_viewer.camera_actor"));
        }
        if (PvPlaybackController.canWatchAsPlayer()) {
            labels.put(9, Component.translatable(PvPlaybackController.playerViewing()
                    ? "ui.stevedance.pv_viewer.resume_player" : "ui.stevedance.pv_viewer.player_watch"));
            if (PvPlaybackController.playerViewing()) {
                labels.put(10, Component.translatable("ui.stevedance.pv_viewer.return_preview"));
            }
        }
        int textWidth = labels.values().stream().mapToInt(minecraft.font::width).sum();
        float scale = Math.min(.65f, (width - 24 - 14 * (labels.size() - 1)) / (float) Math.max(1, textWidth));
        float total = 0;
        for (var label : labels.values()) total += minecraft.font.width(label) * scale + 14;
        float x = (width - total + 14) / 2f;
        var actions = new java.util.ArrayList<TopAction>();
        for (var entry : labels.entrySet()) {
            float right = x + minecraft.font.width(entry.getValue()) * scale;
            actions.add(new TopAction(entry.getKey(), entry.getValue(), x, right, scale));
            x = right + 14;
        }
        return actions;
    }

    private void drawTopActions(GuiGraphics g) {
        if (overlay.visibility() <= .1f) return;
        int color = Math.max(1, Math.round(overlay.visibility() * 255)) << 24 | 0xffffff;
        for (var action : topActions()) {
            g.pose().pushPose();
            g.pose().translate(action.left(), 12, 0);
            g.pose().scale(action.scale(), action.scale(), 1);
            g.drawString(minecraft.font, action.label(), 0, 0, color, true);
            g.pose().popPose();
        }
    }

    private void runTopAction(int action) {
        switch (action) {
            case 4 -> PerformanceControls.key(GLFW.GLFW_KEY_F4);
            case 5 -> PerformanceControls.key(GLFW.GLFW_KEY_F5);
            case 8 -> PerformanceControls.key(GLFW.GLFW_KEY_F8);
            case 9 -> PvPlaybackController.watchAsPlayer();
            case 10 -> PvPlaybackController.returnToPreview();
            default -> { }
        }
    }

    private boolean cameraCanDrag() {
        return !PvPlaybackController.playerViewing() && (StageCamera.mode() == StageCamera.Mode.FREE
                || StageCamera.mode() == StageCamera.Mode.ORBIT || StageCamera.eye());
    }

    @Override
    public void tick() {
        if (StageCamera.mode() != StageCamera.Mode.FREE || PvPlaybackController.playerViewing()
                || !minecraft.isWindowActive()) return;
        long window = minecraft.getWindow().getWindow();
        double forward = held(window, GLFW.GLFW_KEY_W) - held(window, GLFW.GLFW_KEY_S);
        double right = held(window, GLFW.GLFW_KEY_D) - held(window, GLFW.GLFW_KEY_A);
        double up = held(window, GLFW.GLFW_KEY_SPACE) - held(window, GLFW.GLFW_KEY_LEFT_SHIFT);
        StageCamera.move(forward, right, up, held(window, GLFW.GLFW_KEY_LEFT_CONTROL) != 0 ? .6 : .18);
    }

    private static int held(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS ? 1 : 0;
    }
}
