package dev.flower.stevedance.client;

import dev.flower.stevedance.SteveDanceMod;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;

/**
 * 专用Java Sound播放，具有独立的音乐/音效增益和可逆的世界隔离。
 * <p>Dedicated Java Sound playback, with separate music/effects gains and reversible world isolation.</p>
 */
@Mod.EventBusSubscriber(modid = SteveDanceMod.MOD_ID, value = Dist.CLIENT)
public final class PerformanceAudioBus {
    private static volatile boolean exclusive;

    private PerformanceAudioBus() {}

    public static Clip openClip() throws LineUnavailableException {
        return AudioSystem.getClip();
    }

    public static boolean exclusive() {
        return exclusive;
    }

    public static void beginRhythm() {
        if (exclusive) {
            return;
        }
        exclusive = true;
        Minecraft.getInstance().getSoundManager().stop();
    }

    public static void endRhythm() {
        exclusive = false;
    }

    public static void musicGain(Clip clip, float volume) {
        setGain(clip, volume);
    }

    public static void effectsGain(Clip clip, float bankGain) {
        setGain(clip, RhythmSettingsScreen.soundVolume * bankGain);
    }

    private static void setGain(Clip clip, float volume) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        var control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        control.setValue(
                volume <= 0
                        ? control.getMinimum()
                        : Math.max(
                                control.getMinimum(),
                                Math.min(control.getMaximum(), (float) (20 * Math.log10(volume)))));
    }

    @SubscribeEvent
    public static void worldSound(PlaySoundEvent event) {
        if (exclusive) {
            event.setSound(null);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END
                && exclusive
                && Minecraft.getInstance().level == null) {
            endRhythm();
        }
    }
}
