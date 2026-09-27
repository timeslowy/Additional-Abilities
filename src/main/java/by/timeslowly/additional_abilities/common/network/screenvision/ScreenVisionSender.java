package by.timeslowly.additional_abilities.common.network.screenvision;

import by.timeslowly.additional_abilities.AdditionalAbilities;
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
 * 间隔不足<b>且参数完全没变</b>时直接跳过。
 * <p>
 * <b>"参数完全没变"而非"强度没提升"</b>：{@code edge_light} 的颜色与尺寸也是参数的一部分，
 * 若只比强度，节流窗口（5 刻）内的换色 / 改尺寸会被<b>静默吞掉</b>，
 * 表现为"改了 JSON（或换了施法者）却看不出变化"。反过来说，参数由 {@code LevelBasedValue}
 * 依技能等级算出、本身稳定，不会出现逐帧抖动导致的发包风暴。
 * <p>
 * <b>节流按「玩家 + 视觉类型」分槽</b>：不同视觉必须各自计时、各自比参数。
 * 若只按玩家存一份，同一次施法里先下发的强效果会把随后下发的弱效果吞掉
 * （间隔不足 5 刻且参数没提升 → 跳过），表现为"并存的两种视觉只有一种生效"。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public final class ScreenVisionSender {
    /** 同一玩家同一视觉类型两次发包的最小间隔（游戏刻） */
    private static final int THROTTLE_TICKS = 5;

    /** 浮点比较容差：小于此差值视为参数没变（强度与尺寸共用） */
    private static final float PARAM_EPSILON = 1.0E-4F;

    /** 玩家 UUID → 各视觉类型各自的上次发包信息（下标 = {@link ScreenVisionType#ordinal()}）。登出时清理，避免长开服内存堆积 */
    private static final Map<UUID, LastSend[]> LAST_SEND = new ConcurrentHashMap<>();

    private record LastSend(long tick, float amplitude, float size, int rgb) {}

    private ScreenVisionSender() {}

    public static void send(final @NotNull ServerPlayer player, final @NotNull ScreenVisionType visionType,
                            final float amplitude, final float size, final int rgb, final int durationTicks) {
        if (durationTicks <= 0 || amplitude <= 0.0F) {
            return;
        }

        LastSend[] slots = LAST_SEND.computeIfAbsent(player.getUUID(), uuid -> createSlots());
        int index = visionType.ordinal();
        LastSend last = slots[index];
        long now = player.level().getGameTime();

        boolean unchanged = Math.abs(amplitude - last.amplitude()) <= PARAM_EPSILON
                && Math.abs(size - last.size()) <= PARAM_EPSILON
                && rgb == last.rgb();

        if (now - last.tick() < THROTTLE_TICKS && unchanged) {
            return;
        }

        slots[index] = new LastSend(now, amplitude, size, rgb);
        PacketDistributor.sendToPlayer(player, new ScreenVisionPayload(visionType, amplitude, size, rgb, durationTicks));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(final @NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SEND.remove(event.getEntity().getUUID());
    }

    /**
     * 各类型的初始记录：tick = 0、强度 = 0、尺寸 = 0、颜色 = 0。
     * 因为调用方已保证 {@code amplitude > 0}，所以"参数没变"必然不成立 → 首次下发一定放行。
     */
    private static @NotNull LastSend @NotNull [] createSlots() {
        LastSend[] slots = new LastSend[ScreenVisionType.values().length];

        for (int i = 0; i < slots.length; i++) {
            slots[i] = new LastSend(0L, 0.0F, 0.0F, 0);
        }

        return slots;
    }
}
