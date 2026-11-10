package dev.flower.stevedance.client;

import net.minecraft.client.gui.GuiGraphics;

public final class SongLoadingScreen extends RhythmScreen
        implements PvPlaybackController.WorldView {
    private final LoadingArtwork artwork = new LoadingArtwork();

    public SongLoadingScreen() {
        super("ui.stevedance.native_loading");
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        GamepadInput.menu(this);
        artwork.draw(
                g,
                width,
                height,
                RhythmClientController.song,
                dev.flower.stevedance.client.SongText.title(
                        dev.flower.stevedance.SongSelectionCatalog.displayTitleKey(
                                RhythmClientController.song)),
                title,
                true);
    }

    @Override
    protected void padPressed(int bit) {
        if (bit == 2 || bit == 128) {
            onClose();
        }
    }

    @Override
    public void onClose() {
        RhythmClientController.stop();
        RhythmClientController.select();
    }
}
