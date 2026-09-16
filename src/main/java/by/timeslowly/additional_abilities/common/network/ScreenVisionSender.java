package by.timeslowly.additional_abilities.common.network;

import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionType;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 屏幕视觉效果的发送端（服务端）。
 * <p>
 * 之所以单独抽一层而不是直接写在实体效果里，是为了做<b>发送节流</b>：
 * 被动技能的 {@code trigger_rate} 很小时 {@code apply} 会被高频调用，
 * 若每次都发包会造成无谓的带宽开销。这里对同一玩家维护"上次发包刻"，
 * 间隔不足且强度没有提升时直接跳过。
 * <p>
 * 强度提升时放行，是为了避免"先前触发的弱效果把后到的强效果挤掉"。
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID)
public final class ScreenVisionSender {
    /** 同一玩家两次发包的最小间隔（游戏刻） */
    private static final int THROTTLE_TICKS = 5;

    /** 浮点比较容差：小于此差值视为强度没变 */
    private static final float AMPLITUDE_EPSILON = 1.0E-4F;

    /** 玩家 UUID → 上次发包信息。登出时清理，避免长开服内存堆积 */
    private static final Map<UUID, LastSend> LAST_SEND = new ConcurrentHashMap<>();

    private record LastSend(long tick, float amplitude) {}

    private ScreenVisionSender() {}

    public static void send(final @NotNull ServerPlayer player, final @NotNull ScreenVisionType visionType,
                            final float amplitude, final int durationTicks) {
        if (durationTicks <= 0 || amplitude <= 0.0F) {
            return;
        }

        long now = player.level().getGameTime();
        LastSend last = LAST_SEND.get(player.getUUID());

        if (last != null
                && now - last.tick() < THROTTLE_TICKS
                && amplitude <= last.amplitude() + AMPLITUDE_EPSILON) {
            return;
        }

        LAST_SEND.put(player.getUUID(), new LastSend(now, amplitude));
        PacketDistributor.sendToPlayer(player, new ScreenVisionPayload(visionType, amplitude, durationTicks));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(final @NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SEND.remove(event.getEntity().getUUID());
    }
}
