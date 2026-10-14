package by.timeslowly.additional_abilities.common.network.screenvision;

import by.timeslowly.additional_abilities.common.network.screenvision.ScreenVisionSender.LastSend;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionTiming;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T06 / F09 的回归测试：下发节流必须<b>记录客户端已被覆盖到什么时候</b>，
 * 不能只看"参数变没变"。
 *
 * <h2>为什么不测 {@code send}</h2>
 * {@code send} 需要真实 {@code ServerPlayer} 才能取游戏刻并发包，
 * 而本机无外网、缓存里也没有 Mockito，造不出这个重型对象。因此把判据抽成纯函数
 * {@code evaluate}（该不该发 + 发完记什么），这里逐条钉住它。
 * 发包动作本身只是把结果写回槽位并调 {@code PacketDistributor}，不承载任何决策。
 *
 * <h2>两条互相拉扯的要求</h2>
 * F09 要的是"延长租期必须放行"，而节流原本的<b>带宽目的</b>又不能丢。两者靠
 * {@link ScreenVisionTiming#COVERAGE_SAFETY_TICKS} 划界：余量够就照旧跳过（省带宽），
 * 余量不够就必须补发（不闪断、不脉动）。下面的用例分别钉住两侧。
 */
class ScreenVisionThrottleTest {
    private static final float AMPLITUDE = 1.0F;
    private static final float SIZE = 0.2F;
    private static final int RGB = 0xFF00FFFF;

    /** 用例默认所在维度；跨维度用例会另取一个键 */
    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
    private static final ResourceKey<Level> NETHER = Level.NETHER;

    /** 与 {@code ScreenVisionSender#createSlots} 一致的初始记录：什么都没发过（维度为 null） */
    private static LastSend fresh() {
        return new LastSend(null, 0L, 0.0F, 0.0F, 0, 0L);
    }

    /** 已在本维度发过一包、客户端被覆盖到 {@code coveredUntil} */
    private static LastSend sent(final long tick, final long coveredUntil) {
        return new LastSend(OVERWORLD, tick, AMPLITUDE, SIZE, RGB, coveredUntil);
    }

    /** 同参数、同维度的常规下发，返回是否放行 */
    private static boolean passes(final LastSend last, final long now, final int durationTicks) {
        return ScreenVisionSender.evaluate(last, OVERWORLD, now, AMPLITUDE, SIZE, RGB, durationTicks) != null;
    }

    /** 指定维度的常规下发，返回是否放行 */
    private static boolean passes(final LastSend last, final ResourceKey<Level> dimension,
                                  final long now, final int durationTicks) {
        return ScreenVisionSender.evaluate(last, dimension, now, AMPLITUDE, SIZE, RGB, durationTicks) != null;
    }

    // ================================================================ F09：报告固定情景

    @Test
    @DisplayName("F09：先发 1 刻、再在同一窗口内用同参数发 100 刻，后一次必须放行（旧实现丢弃）")
    void longerDurationInsideThrottleWindowIsNotDropped() {
        // 第 0 刻下发 1 刻 → 客户端只被覆盖到第 1 刻
        LastSend afterFirst = ScreenVisionSender.evaluate(fresh(), OVERWORLD, 0L, AMPLITUDE, SIZE, RGB, 1);
        assertNotNull(afterFirst, "首包必须发出");
        assertEquals(1L, afterFirst.coveredUntil());

        // 第 2 刻、参数完全相同、仍在节流窗口内，但这次要发 100 刻。
        // 旧实现只看"参数没变" → 返回 null（丢弃），客户端手里就只剩 1 刻。
        LastSend afterSecond = ScreenVisionSender.evaluate(afterFirst, OVERWORLD, 2L, AMPLITUDE, SIZE, RGB, 100);

        assertNotNull(afterSecond, "延长租期的下发绝不能被节流丢弃");
        assertEquals(102L, afterSecond.coveredUntil(), "覆盖终点应推进到 now + 新时长");
    }

    @Test
    @DisplayName("F09：余量掉进客户端淡出区时必须补发，否则会刷出一次可见的明暗脉动")
    void refreshHappensBeforeClientEntersFadeOut() {
        // 安全线的含义：余量 >= COVERAGE_SAFETY_TICKS 才允许跳过。
        // 恰好等于安全线时，等满一个节流窗口后仍会剩下 FADE_OUT_TICKS 刻满强度余量 → 可以跳过。
        assertNull(ScreenVisionSender.evaluate(
                        sent(100L, 100L + ScreenVisionTiming.COVERAGE_SAFETY_TICKS), OVERWORLD, 100L, AMPLITUDE, SIZE, RGB, 100),
                "余量恰好等于安全线：撑得住，允许跳过");

        // 少一刻就不够了 → 必须补发
        assertNotNull(ScreenVisionSender.evaluate(
                        sent(100L, 100L + ScreenVisionTiming.COVERAGE_SAFETY_TICKS - 1), OVERWORLD, 100L, AMPLITUDE, SIZE, RGB, 100),
                "余量低于安全线一刻就必须补发");
    }

    @Test
    @DisplayName("F09：短时长被连续刷新时逐刻补发，客户端不会闪断")
    void shortDurationRepeatedRefreshStaysCovered() {
        // 时长 3 刻、每刻都触发：余量始终低于安全线，于是每刻都补发，客户端覆盖范围持续前移
        LastSend after = ScreenVisionSender.evaluate(fresh(), OVERWORLD, 0L, AMPLITUDE, SIZE, RGB, 3);
        assertNotNull(after);
        assertEquals(3L, after.coveredUntil());

        for (long now = 1L; now <= 3L; now++) {
            LastSend next = ScreenVisionSender.evaluate(after, OVERWORLD, now, AMPLITUDE, SIZE, RGB, 3);

            assertNotNull(next, "第 " + now + " 刻必须补发，否则客户端会闪断");
            assertEquals(now + 3L, next.coveredUntil(), "覆盖终点应持续前移");
            after = next;
        }
    }

    // ================================================================ 带宽目的仍要保住

    @Test
    @DisplayName("长效果被持续刷新时仍按节流窗口折叠，不会退化成每刻发包")
    void longEffectIsStillThrottled() {
        // 第 0 刻发了 100 刻 → 覆盖到 100
        LastSend after = ScreenVisionSender.evaluate(fresh(), OVERWORLD, 0L, AMPLITUDE, SIZE, RGB, 100);
        assertNotNull(after);

        // 紧接着的第 1、2、3、4 刻：参数没变且余量充足 → 全部跳过
        for (long now = 1L; now < ScreenVisionTiming.THROTTLE_TICKS; now++) {
            assertNull(ScreenVisionSender.evaluate(after, OVERWORLD, now, AMPLITUDE, SIZE, RGB, 100),
                    "第 " + now + " 刻不该发包（窗口未满且余量充足）");
        }

        // 窗口一满就照常刷新（这是修复前就有的常规节拍）
        assertNotNull(ScreenVisionSender.evaluate(after, OVERWORLD, ScreenVisionTiming.THROTTLE_TICKS,
                AMPLITUDE, SIZE, RGB, 100), "窗口到期后应常规刷新");
    }

    @Test
    @DisplayName("参数任何一项变化都立即放行（强度 / 尺寸 / 颜色）")
    void anyParameterChangeBypassesThrottle() {
        LastSend last = sent(10L, 1000L);

        assertNotNull(ScreenVisionSender.evaluate(last, OVERWORLD, 11L, AMPLITUDE + 0.5F, SIZE, RGB, 100), "强度变化");
        assertNotNull(ScreenVisionSender.evaluate(last, OVERWORLD, 11L, AMPLITUDE, SIZE + 0.1F, RGB, 100), "尺寸变化");
        assertNotNull(ScreenVisionSender.evaluate(last, OVERWORLD, 11L, AMPLITUDE, SIZE, RGB ^ 0xFFFFFF, 100), "颜色变化");
    }

    @Test
    @DisplayName("容差内的浮点抖动仍算「没变」，不因逐帧误差产生发包风暴")
    void floatingPointNoiseStaysThrottled() {
        // 差值 1e-5 远小于 1e-4 容差 → 视为没变
        assertNull(ScreenVisionSender.evaluate(sent(10L, 1000L), OVERWORLD, 11L,
                AMPLITUDE + 1.0E-5F, SIZE - 1.0E-5F, RGB, 100));
    }

    // ================================================================ 租期只增不减

    @Test
    @DisplayName("较短的新时长不会把已承诺的长租期缩短")
    void shorterDurationNeverShrinksCoverage() {
        LastSend after = ScreenVisionSender.evaluate(sent(100L, 1000L), OVERWORLD, 500L, AMPLITUDE, SIZE, RGB, 10);

        assertNotNull(after);
        assertEquals(1000L, after.coveredUntil(), "覆盖终点必须取「新承诺」与「已承诺」的较大值");
    }

    @Test
    @DisplayName("已过期的记录必然放行（余量为负）")
    void expiredCoverageAlwaysSends() {
        assertTrue(passes(sent(0L, 10L), 500L, 100));
    }

    @Test
    @DisplayName("首包一定发出：初始记录的参数与余量两条理由都指向放行")
    void firstSendAlwaysPasses() {
        assertNotNull(ScreenVisionSender.evaluate(fresh(), OVERWORLD, 0L, AMPLITUDE, SIZE, RGB, 1),
                "初始强度为 0，任何正的强度都算参数变化");
    }

    @Test
    @DisplayName("窗口外的常规刷新不受余量判断影响")
    void refreshOutsideWindowIsUnaffected() {
        assertTrue(passes(sent(0L, 100_000L), ScreenVisionTiming.THROTTLE_TICKS, 100),
                "余量极充足、但窗口已满 → 仍按常规节拍刷新");
    }

    // ================================================================ 复审 P1：节流状态必须按维度隔离

    /**
     * 复审给出的固定情景：先在时间较大的维度发一包（把客户端覆盖到很远的将来），
     * 然后切到时间较小的维度、用<b>完全相同</b>的参数再发一次。
     * <p>
     * 旧实现只按玩家 UUID 分槽，于是：
     * <ul>
     *     <li>{@code coveredUntil - now} 异常偏大 → 余量判据认为"够用"；</li>
     *     <li>{@code now - last.tick()} 为负 → 小于节流窗口，于是被当成"窗口内重复下发"；</li>
     * </ul>
     * 两条同时成立，新维度的视觉会被<b>长期静默丢弃</b>。
     */
    @Test
    @DisplayName("P1：换到时间较小的维度后，同参数的新视觉必须放行（旧实现被长期节流）")
    void switchingToOlderDimensionIsNotThrottled() {
        // 在时间较大的维度：第 100_000 刻发一包 200 刻 → 覆盖到 100_200
        LastSend inOverworld = ScreenVisionSender.evaluate(fresh(), OVERWORLD, 100_000L,
                AMPLITUDE, SIZE, RGB, 200);
        assertNotNull(inOverworld);
        assertEquals(100_200L, inOverworld.coveredUntil());

        // 切到下界：本地刻数只有 50（远小于 100_200），参数完全相同、两刻后触发。
        // 旧实现下 remaining = 100_150 且 now - last.tick() = -99_950 → 被丢弃。
        LastSend inNether = ScreenVisionSender.evaluate(inOverworld, NETHER, 50L,
                AMPLITUDE, SIZE, RGB, 200);

        assertNotNull(inNether, "换维度后第一条必须发出，否则新维度里效果根本不显示");
        assertEquals(NETHER, inNether.level(), "槽位应改记为当前维度");
        assertEquals(250L, inNether.coveredUntil(),
                "覆盖终点必须按新维度的时间轴重算，不能沿用旧维度的 100_200");
    }

    @Test
    @DisplayName("P1：跨维度时槽位的参数/余量全部作废，哪怕新维度刻数看起来「还早」")
    void dimensionSwitchDiscardsAllSlotState() {
        // 新维度刻数极小，旧记录的 coveredUntil 与 tick 都远大于它
        LastSend old = new LastSend(OVERWORLD, 9_000_000L, AMPLITUDE, SIZE, RGB, 9_000_000L);

        LastSend next = ScreenVisionSender.evaluate(old, NETHER, 1L, AMPLITUDE, SIZE, RGB, 10);

        assertNotNull(next, "维度不同即整槽作废，不进跳过分支");
        assertEquals(NETHER, next.level());
        assertEquals(11L, next.coveredUntil(), "旧维度的承诺不得被带入新维度");
    }

    /**
     * 槽位只有<b>一个</b>维度，因此"去别的维度再回来"也会被判定为维度变化、重新发包。
     * <p>
     * 这是<b>刻意</b>的取舍：要保住"回到原维度仍沿用旧记录"就得按维度分区存（
     * {@code Map<维度, Map<UUID, 槽>>}），而那样做唯一的收益是"来回穿梭时少发一包"，
     * 代价是状态量随访问过的维度数增长、且旧记录里的 {@code coveredUntil}
     * 在离开期间早已过期（回到原维度时同样要重发）。既然重发不可避免，
     * 就不值得为它维护多份状态。
     * <p>
     * 本用例把这条取舍<b>钉住</b>：它既说明"回来后必然放行"是有意为之，
     * 也说明此刻槽位记的是当前维度而不是被旧维度粘住。
     */
    @Test
    @DisplayName("P1：槽位单维度 —— 从别的维度回来时记作维度变化，重新发包并改记当前维度")
    void slotIsSingleDimensionSoReturningResends() {
        // 主世界发过一包 → 覆盖到 1000
        LastSend overworld = ScreenVisionSender.evaluate(fresh(), OVERWORLD, 0L, AMPLITUDE, SIZE, RGB, 1000);
        assertNotNull(overworld);

        // 去下界发一包（覆盖到 2_000）
        LastSend nether = ScreenVisionSender.evaluate(overworld, NETHER, 500L, AMPLITUDE, SIZE, RGB, 1500);
        assertNotNull(nether);

        // 回主世界、第 1 刻：槽位此刻记着下界 → 判为维度变化 → 重发，并改记为主世界
        LastSend back = ScreenVisionSender.evaluate(nether, OVERWORLD, 1L, AMPLITUDE, SIZE, RGB, 1000);

        assertNotNull(back, "回来后必然重发：槽位属另一个维度，旧刻数不再可用");
        assertEquals(OVERWORLD, back.level(), "槽位应改记为当前维度，不能被旧维度粘住");
        assertEquals(1001L, back.coveredUntil(), "覆盖终点按主世界的当前刻重算");

        // 此后同维度内的节流恢复正常
        assertNull(ScreenVisionSender.evaluate(back, OVERWORLD, 2L, AMPLITUDE, SIZE, RGB, 1000),
                "重新落在当前维度后，节流照常生效");
    }

    @Test
    @DisplayName("P1：同维度内的既有节流行为完全不受本修复影响")
    void sameDimensionBehaviourIsUnchanged() {
        LastSend after = ScreenVisionSender.evaluate(fresh(), OVERWORLD, 100L, AMPLITUDE, SIZE, RGB, 1000);
        assertNotNull(after);

        assertNull(ScreenVisionSender.evaluate(after, OVERWORLD, 101L, AMPLITUDE, SIZE, RGB, 1000),
                "同维度、窗口内、参数没变、余量够 → 照旧跳过（带宽目的不变）");
        assertNotNull(ScreenVisionSender.evaluate(after, OVERWORLD, 101L, AMPLITUDE + 0.5F, SIZE, RGB, 1000),
                "同维度下参数变化照旧立即放行");
    }
}
