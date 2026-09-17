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
 * <p>
 * <b>节流按「玩家 + 视觉类型」分槽</b>：不同视觉必须各自计时、各自比强度。
 * 若只按玩家存一份，同一次施法里先下发的强效果会把随后下发的弱效果吞掉
 * （间隔不足 5 刻且强度没提升 → 跳过），表现为"并存的两种视觉只有一种生效"。
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID)
public final class ScreenVisionSender {
    /** 同一玩家同一视觉类型两次发包的最小间隔（游戏刻） */
    private static final int THROTTLE_TICKS = 5;

    /** 浮点比较容差：小于此差值视为强度没变 */
    private static final float AMPLITUDE_EPSILON = 1.0E-4F;

    /** 玩家 UUID → 各视觉类型各自的上次发包信息（下标 = {@link ScreenVisionType#ordinal()}）。登出时清理，避免长开服内存堆积 */
    private static final Map<UUID, LastSend[]> LAST_SEND = new ConcurrentHashMap<>();

    private record LastSend(long tick, float amplitude) {}

    private ScreenVisionSender() {}

    public static void send(final @NotNull ServerPlayer player, final @NotNull ScreenVisionType visionType,
                            final float amplitude, final int durationTicks) {
        if (durationTicks <= 0 || amplitude <= 0.0F) {
            return;
        }

        LastSend[] slots = LAST_SEND.computeIfAbsent(player.getUUID(), uuid -> createSlots());
        int index = visionType.ordinal();
        LastSend last = slots[index];
        long now = player.level().getGameTime();

        if (now - last.tick() < THROTTLE_TICKS && amplitude <= last.amplitude() + AMPLITUDE_EPSILON) {
            return;
        }

        slots[index] = new LastSend(now, amplitude);
        PacketDistributor.sendToPlayer(player, new ScreenVisionPayload(visionType, amplitude, durationTicks));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(final @NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SEND.remove(event.getEntity().getUUID());
    }

    /**
     * 各类型的初始记录：tick = 0、强度 = 0。
     * 因为调用方已保证 {@code amplitude > 0}，所以"强度没提升"必然不成立 → 首次下发一定放行。
     */
    private static @NotNull LastSend @NotNull [] createSlots() {
        LastSend[] slots = new LastSend[ScreenVisionType.values().length];

        for (int i = 0; i < slots.length; i++) {
            slots[i] = new LastSend(0L, 0.0F);
        }

        return slots;
    }
}
