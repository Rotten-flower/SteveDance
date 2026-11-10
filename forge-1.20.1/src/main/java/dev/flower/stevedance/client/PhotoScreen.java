package dev.flower.stevedance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/** 摄影沿用PV预览控件，工具单独打开。 / Photography reuses the PV overlay. */
public final class PhotoScreen extends Screen {
    private final PlaybackOverlay overlay = new PlaybackOverlay();
    private boolean hidden, looking;
    private long lastMove;

    public PhotoScreen() {
        super(Component.translatable("ui.stevedance.photo.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        moveCamera();
        if (PhotoMode.captureHidden()) {
            return;
        }
        int count = PhotoMode.countdown();
        if (count > 0) {
            g.pose().pushPose();
            g.pose().translate(width / 2f - 4, height / 2f - 8, 0);
            g.pose().scale(2, 2, 1);
            g.drawString(font, Integer.toString(count), 0, 0, 0xffffffff, true);
            g.pose().popPose();
            return;
        }
        if (hidden) {
            return;
        }
        overlay.render(
                g,
                width,
                height,
                (int) PhotoMode.time(),
                PhotoMode.duration(),
                !PhotoMode.playing(),
                Component.translatable("ui.stevedance.photo.hints"));
        if (!PhotoMode.error().isEmpty()) {
            g.drawCenteredString(
                    font, Component.translatable(PhotoMode.error()), width / 2, 28, 0xffff9db7);
        }
    }

    private boolean down(int key) {
        return GLFW.glfwGetKey(minecraft.getWindow().getWindow(), key) == GLFW.GLFW_PRESS;
    }

    private void moveCamera() {
        long now = System.nanoTime();
        double dt = lastMove == 0 ? 0 : Math.min(.05, (now - lastMove) / 1e9);
        lastMove = now;
        if (!minecraft.isWindowActive() || PhotoMode.countdown() > 0) {
            return;
        }
        double speed =
                down(GLFW.GLFW_KEY_LEFT_CONTROL) ? 10 : down(GLFW.GLFW_KEY_LEFT_SHIFT) ? 1 : 4;
        PhotoMode.move(
                (down(GLFW.GLFW_KEY_W) ? 1 : 0) - (down(GLFW.GLFW_KEY_S) ? 1 : 0),
                (down(GLFW.GLFW_KEY_D) ? 1 : 0) - (down(GLFW.GLFW_KEY_A) ? 1 : 0),
                (down(GLFW.GLFW_KEY_E) ? 1 : 0) - (down(GLFW.GLFW_KEY_Q) ? 1 : 0),
                dt * speed);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 1) {
            looking = true;
            overlay.wake();
            return true;
        }
        return !hidden && overlay.click(x, y, button, true, PhotoMode::togglePlay);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (looking && button == 1) {
            PhotoMode.look(dx, dy);
            return true;
        }
        return !hidden && overlay.drag(x);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == 1) {
            looking = false;
        }
        return !hidden && overlay.release(x, button, ms -> PhotoMode.seek(ms));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        PhotoMode.lens((float) -amount * 2);
        return true;
    }

    private void step(int direction) {
        if (PhotoMode.playing()) {
            PhotoMode.togglePlay();
        }
        PhotoMode.seek(PhotoMode.time() + direction * 1000.0 / 60);
        overlay.wake();
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F1) {
            hidden = !hidden;
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            minecraft.setScreen(new PhotoToolsScreen(this));
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            PhotoMode.togglePlay();
            return true;
        }
        if (key == GLFW.GLFW_KEY_P) {
            PhotoMode.photograph();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F5) {
            PhotoMode.toggleCamera();
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            step(key == GLFW.GLFW_KEY_LEFT ? -1 : 1);
            return true;
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) {
            return true;
        }
        overlay.wake();
        return true;
    }

    @Override
    public void onClose() {
        PhotoMode.close();
        minecraft.setScreen(null);
    }

    @Override
    public void removed() {
        looking = false;
        lastMove = 0;
    }
}
