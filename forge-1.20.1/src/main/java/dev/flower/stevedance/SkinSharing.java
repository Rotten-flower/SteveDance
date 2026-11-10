package dev.flower.stevedance;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 共享歌曲身份。PV包和谱面按ID关联，不按菜单位置关联。可选config/stevedance/songs.json在启动时扩展内置目录，新增PV需要数据与验证，无需新增渲染器或界面。
 * <p>Session-only, bounded cache; PNG data never contains local paths.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance")
public final class SkinSharing {
    private static final Map<String, byte[]> images = new LinkedHashMap<>(16, .75f, true);
    private static final Map<UUID, long[]> limits = new HashMap<>();

    private static boolean permit(ServerPlayer player, int weight) {
        long now = player.serverLevel().getGameTime();
        var count = limits.computeIfAbsent(player.getUUID(), id -> new long[] {now, 0});
        if (now - count[0] >= 1200) {
            count[0] = now;
            count[1] = 0;
        }
        if (count[1] + weight > 512) {
            return false;
        }
        count[1] += weight;
        return true;
    }

    public static void upload(ServerPlayer player, String hash, byte[] bytes) {
        if (!permit(player, 8)
                || !hash.matches("[0-9a-f]{64}")
                || !SkinImage.hash(bytes).equals(hash)
                || images.containsKey(hash)) {
            return;
        }
        try {
            var normalized = SkinImage.normalize(bytes);
            if (!SkinImage.hash(normalized).equals(hash)) {
                return;
            }
            while (images.size() >= 256) {
                images.remove(images.keySet().iterator().next());
            }
            images.put(hash, normalized);
        } catch (Exception ignored) {
        }
    }

    public static void request(ServerPlayer player, String hash) {
        if (!permit(player, 1)) {
            return;
        }
        var data = images.get(hash);
        if (data != null) {
            StageNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new StageNetwork.SkinReply(hash, data));
        }
    }

    @SubscribeEvent
    public static void stop(net.minecraftforge.event.server.ServerStoppedEvent event) {
        images.clear();
        limits.clear();
    }

    @SubscribeEvent
    public static void logout(
            net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        limits.remove(event.getEntity().getUUID());
    }
}
