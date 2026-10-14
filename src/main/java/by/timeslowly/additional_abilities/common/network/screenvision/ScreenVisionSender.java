package by.timeslowly.additional_abilities.common.network.screenvision;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionTiming;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
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
 * 若只比强度，节流窗口（{@link ScreenVisionTiming#THROTTLE_TICKS} 刻）内的换色 / 改尺寸会被<b>静默吞掉</b>，
 * 表现为"改了 JSON（或换了施法者）却看不出变化"。反过来说，参数由 {@code LevelBasedValue}
 * 依技能等级算出、本身稳定，不会出现逐帧抖动导致的发包风暴。
 * <p>
 * <b>节流按「玩家 + 视觉类型」分槽</b>：不同视觉必须各自计时、各自比参数。
 * 若只按玩家存一份，同一次施法里先下发的强效果会把随后下发的弱效果吞掉
 * （间隔不足且参数没提升 → 跳过），表现为"并存的两种视觉只有一种生效"。
 *
 * <h2>不变量一：覆盖到期时间</h2>
 * 只比"参数变没变"是不够的：参数完全相同、但<b>时长更长</b>的那次下发同样必须放行，
 * 否则"先发 1 刻、再发 100 刻"会让后者被丢弃，客户端只拿到 1 刻。
 * 因此每条槽记下 {@code coveredUntil} —— 服务端<b>已经承诺给客户端的时间</b>，
 * 判据从"参数没变就跳过"变成"参数没变<b>且客户端余量还够</b>才跳过"。
 * 该判据只在<b>节流窗口内</b>参与判断，超窗照旧放行，
 * 因此长效果仍按 {@link ScreenVisionTiming#THROTTLE_TICKS} 刻的节拍复发。
 *
 * <h2>不变量二：时间轴按维度隔离</h2>
 * {@code coveredUntil} 是<b>绝对游戏刻</b>，属于某个 {@code ServerLevel} 的时间轴。
 * 玩家换维度时槽位并不重来，而各维度的 {@code getGameTime()} 相互独立 ——
 * 切到时间较小的维度后 {@code coveredUntil - now} 会异常偏大、
 * {@code now - last.tick()} 甚至可能为负，两者都会让同参数的新视觉被长期节流掉，
 * 表现为"换维度后效果不显示"。因此每条槽记住自己的维度身份，
 * 与当前维度不一致时<b>整槽作废</b>（当作全新槽），换维度后必然放行首包。
 * <p>
 * 同理 {@code coveredUntil} 必须在玩家登出时清掉，
 * 否则同一 JVM 换存档后残留的旧时刻会让新存档里的余量判断失真。
 * <p>
 * 判据取 {@code ResourceKey<Level>} 而不是 {@code ServerLevel} 实例：
 * 同一维度退出再进入会得到新的 {@code ServerLevel}，而刻数本来也可能倒退，
 * 仍应作废 —— 「维度键不同必然作废」已覆盖这一情形，
 * 故无需持有实例引用（也不会阻止 GC）。
 *
 * <pre>{@code
 * // A skip requires all of: same dimension, unchanged params, client coverage still sufficient.
 * // coveredUntil is an absolute game tick on that dimension's own timeline.
 * }</pre>
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public final class ScreenVisionSender {
    /** 浮点比较容差：小于此差值视为参数没变（强度与尺寸共用） */
    private static final float PARAM_EPSILON = 1.0E-4F;

    /** 玩家 UUID → 各视觉类型各自的上次发包信息（下标 = {@link ScreenVisionType#ordinal()}）。登出时清理，避免长开服内存堆积 */
    private static final Map<UUID, LastSend[]> LAST_SEND = new ConcurrentHashMap<>();

    /**
     * 一条槽的发包记录。
     *
     * @param level        上次发包时玩家所在维度的键；与本次不一致时整槽作废（见类注释）
     * @param tick         上次发包时的游戏刻
     * @param amplitude    上次下发的强度
     * @param size         上次下发的尺寸
     * @param rgb          上次下发的颜色
     * @param coveredUntil 客户端<b>已知</b>会持续到哪一刻（= 上次发包刻 + 那次下发的时长）。
     *                     这是服务端对客户端的承诺范围，用来判断能否安全跳过本次发包
     */
    record LastSend(ResourceKey<Level> level, long tick, float amplitude, float size, int rgb, long coveredUntil) {}

    private ScreenVisionSender() {}

    public static void send(final @NotNull ServerPlayer player, final @NotNull ScreenVisionType visionType,
                            final float amplitude, final float size, final int rgb, final int durationTicks) {
        if (durationTicks <= 0 || amplitude <= 0.0F) {
            return;
        }

        LastSend[] slots = LAST_SEND.computeIfAbsent(player.getUUID(), uuid -> createSlots());
        int index = visionType.ordinal();
        ResourceKey<Level> dimension = player.level().dimension();
        LastSend next = evaluate(slots[index], dimension, player.level().getGameTime(),
                amplitude, size, rgb, durationTicks);

        if (next == null) {
            return;
        }

        slots[index] = next;
        PacketDistributor.sendToPlayer(player, new ScreenVisionPayload(visionType, amplitude, size, rgb, durationTicks));
    }

    /**
     * 节流判据本身（<b>纯函数</b>）：算出这次下发后该记什么，或返回 {@code null} 表示应当跳过。
     * <p>
     * 单独抽出来的理由是<b>可测性</b>：{@link #send} 需要一个真实的 {@code ServerPlayer}
     * 才能取到游戏刻并发包，而本机没有 Mockito、造不出这个重型对象。
     * 把"该不该发、发了以后记什么"这段决策与发包动作分开后，
     * 判据就能用普通单元测试逐条钉住（见 {@code ScreenVisionThrottleTest}）。
     *
     * @param dimension 玩家<b>本次</b>所在的维度键；与 {@code last} 记录的不一致时整槽作废
     * @return 该写入槽位的新记录；{@code null} 表示本次不下发
     */
    static @Nullable LastSend evaluate(final @NotNull LastSend last, final @NotNull ResourceKey<Level> dimension,
                                       final long now, final float amplitude, final float size,
                                       final int rgb, final int durationTicks) {
        // 维度变了：槽里的刻数属于另一个 ServerLevel 的时间轴，任何比较都无意义
        // （coveredUntil 可能远在将来、now - last.tick() 甚至为负），
        // 因此整槽作废 —— 参数不参与比较、覆盖终点归零，本次必然放行。
        // 初始槽的 level 为 null（"还没发过"），因此用 Objects.equals 而非 dimension.equals(last.level())
        boolean sameDimension = Objects.equals(dimension, last.level());

        // 依次回答两个问题：参数是否变化？客户端余量是否还够？
        boolean unchanged = sameDimension
                && Math.abs(amplitude - last.amplitude()) <= PARAM_EPSILON
                && Math.abs(size - last.size()) <= PARAM_EPSILON
                && rgb == last.rgb();

        // 余量按"距离覆盖终点还剩多少刻"算；已过期时为负值，必然放行。
        // 跨维度时直接用"没有承诺"表示：不能拿别的维度的时间轴来算余量
        long remaining = sameDimension ? last.coveredUntil() - now : Long.MIN_VALUE;
        boolean coveredLongEnough = remaining >= ScreenVisionTiming.COVERAGE_SAFETY_TICKS;

        // 窗口内 + 参数没变 + 余量够 → 这一次下发没有带来任何新信息，跳过。
        // 窗口外一律放行：那是常规刷新，与本次修复无关。
        // 跨维度时 now - last.tick() 无意义（可能为负），一律不进入跳过分支。
        if (sameDimension
                && now - last.tick() < ScreenVisionTiming.THROTTLE_TICKS
                && unchanged && coveredLongEnough) {
            return null;
        }

        // 覆盖终点取「新承诺」与「已承诺」的较大值：本次时长较短时不能把已有的长租期缩短。
        // 跨维度时已承诺值不再适用（它属于旧时间轴），故只取本次承诺
        long previousCoverage = sameDimension ? last.coveredUntil() : 0L;

        return new LastSend(dimension, now, amplitude, size, rgb, Math.max(now + durationTicks, previousCoverage));
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(final @NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SEND.remove(event.getEntity().getUUID());
    }

    /**
     * 各类型的初始记录：维度为 {@code null}（"还没发过，不属于任何维度"）、tick = 0、
     * 强度 = 0、尺寸 = 0、颜色 = 0、覆盖终点 = 0。
     * <p>
     * <b>"首次下发一定放行"靠的是覆盖终点，不是强度比较</b>：{@code coveredUntil = 0} 使
     * 余量判据 {@code remaining >= COVERAGE_SAFETY_TICKS} 对任何非负的游戏刻都不成立
     * （{@code 0 - now} 不可能达到安全余量），因此 {@code coveredLongEnough} 恒为 {@code false}
     * —— 这一条与参数变没变无关，是首包的充分保证。
     * <p>
     * <b>首包放行的唯一依据是覆盖终点，不是强度比较</b>：容差为
     * {@code PARAM_EPSILON = 1.0E-4}，而初始强度是 {@code 0.0F}，
     * 因此 {@code amplitude ≤ 1.0E-4} 的极弱效果其实满足"参数没变"，
     * 不能靠"强度 &gt; 0 所以参数必然变了"来推断首包会放行。
     */
    private static @NotNull LastSend @NotNull [] createSlots() {
        LastSend[] slots = new LastSend[ScreenVisionType.values().length];

        for (int i = 0; i < slots.length; i++) {
            slots[i] = new LastSend(null, 0L, 0.0F, 0.0F, 0, 0L);
        }

        return slots;
    }
}
