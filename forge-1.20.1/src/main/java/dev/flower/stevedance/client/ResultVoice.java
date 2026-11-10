package dev.flower.stevedance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

import java.util.Locale;
import java.util.Set;

/** Original F 2nd result banks, keyed by authored vocalist rather than the replacement model.
 * 按曲目原歌手选择结算语音，不从玩家皮肤或替换模型猜角色。 */
public final class ResultVoice {
    private static final Set<String> BANKS =
            Set.of("mik", "rin", "len", "luk", "mei", "kai", "ner", "hak", "sak");

    private ResultVoice() {}

    public static String character(String song) {
        RhythmSettingsScreen.load();
        if (BANKS.contains(RhythmSettingsScreen.voicePack)) {
            return RhythmSettingsScreen.voicePack;
        }
        var slots = PerformerModelSelection.slots(song);
        for (int slot : slots.actors()) {
            var credit = PerformerModelSelection.credit(song, slot);
            if (!"GUEST".equals(credit.role())) {
                String character = credit.character().toLowerCase(Locale.ROOT);
                if (BANKS.contains(character)) {
                    return character;
                }
            }
        }
        return "mik";
    }

    public static ResourceLocation event(String song, String rank) {
        if (!Set.of("perfect", "excellent", "great", "standard", "cheap", "misstake")
                .contains(rank)) {
            throw new IllegalArgumentException("Unknown result rank");
        }
        return new ResourceLocation("stevedance", "result." + character(song) + "." + rank);
    }

    public static SimpleSoundInstance play(String song, String rank) {
        RhythmSettingsScreen.load();
        if (RhythmSettingsScreen.soundVolume <= 0 || "off".equals(RhythmSettingsScreen.voicePack)) {
            return null;
        }
        var sound =
                SimpleSoundInstance.forUI(
                        SoundEvent.createVariableRangeEvent(event(song, rank)),
                        1,
                        RhythmSettingsScreen.soundVolume);
        Minecraft.getInstance().getSoundManager().play(sound);
        return sound;
    }

    public static void stop(SimpleSoundInstance sound) {
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().stop(sound);
        }
    }
}
