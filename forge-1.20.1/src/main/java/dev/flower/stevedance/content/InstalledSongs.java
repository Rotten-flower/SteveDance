package dev.flower.stevedance.content;

import dev.flower.stevedance.SteveDanceMod;

import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.ModList;

import java.io.IOException;

/**
 * 游戏进程持有只读挂载，安装或更换歌曲包后重启。
 * <p>Mounts live until game exit; restart after replacing packs.</p>
 */
public final class InstalledSongs {
    private static SongPacks packs;

    private InstalledSongs() {}

    // 进程内共享同一只读挂载；界面切换或单曲停止不能关闭这些ZIP文件系统。
    // Share mounts for the process lifetime; screen/song teardown must not close these ZIP filesystems.
    public static synchronized SongPacks get() {
        if (packs == null) {
            try {
                // 在读取器挂载ZIP之前同步；附加JAR移除后的清理由本体负责。
                // Sync before mounting ZIPs; the core handles cleanup after addon removal.
                var desired = new java.util.LinkedHashMap<String, ManagedSongPacks.Source>();
                var mods = ModList.get();
                var core = mods.getModFileById(SteveDanceMod.MOD_ID).getFile()
                        .findResource("assets", "stevedance", "bundled-packs");
                ManagedSongPacks.readIndex(core, desired);
                if (mods.isLoaded("stevedance_f2")) {
                    ManagedSongPacks.readIndex(mods.getModFileById("stevedance_f2").getFile()
                            .findResource("assets", "stevedance_f2", "bundled-packs"), desired);
                }
                ManagedSongPacks.sync(FMLPaths.GAMEDIR.get().resolve("stevedance/packs"), desired,
                        message -> SteveDanceMod.LOGGER.warn("Song installation: {}", message));
                packs =
                        SongPacks.load(
                                FMLPaths.GAMEDIR.get().resolve("stevedance/packs"),
                                message ->
                                        SteveDanceMod.LOGGER.error(
                                                "Song pack rejected: {}", message));
            } catch (IOException error) {
                throw new IllegalStateException("Cannot read song packs", error);
            }
        }
        return packs;
    }

    public static SongPacks.Song song(String id) {
        return get().songs().get(id);
    }
}
