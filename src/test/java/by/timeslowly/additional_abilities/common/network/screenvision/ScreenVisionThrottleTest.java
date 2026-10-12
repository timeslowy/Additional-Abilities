package by.timeslowly.additional_abilities.common.network.screenvision;

import by.timeslowly.additional_abilities.common.network.screenvision.ScreenVisionSender.LastSend;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionTiming;
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

    /** 与 {@code ScreenVisionSender#createSlots} 一致的初始记录：什么都没发过 */
    private static LastSend fresh() {
        return new LastSend(0L, 0.0F, 0.0F, 0, 0L);
    }

    /** 已发过一包、客户端被覆盖到 {@code coveredUntil} */
    private static LastSend sent(final long tick, final long coveredUntil) {
        return new LastSend(tick, AMPLITUDE, SIZE, RGB, coveredUntil);
    }

    /** 同参数的常规下发，返回是否放行 */
    private static boolean passes(final LastSend last, final long now, final int durationTicks) {
        return ScreenVisionSender.evaluate(last, now, AMPLITUDE, SIZE, RGB, durationTicks) != null;
    }

    // ================================================================ F09：报告固定情景

    @Test
    @DisplayName("F09：先发 1 刻、再在同一窗口内用同参数发 100 刻，后一次必须放行（旧实现丢弃）")
    void longerDurationInsideThrottleWindowIsNotDropped() {
        // 第 0 刻下发 1 刻 → 客户端只被覆盖到第 1 刻
        LastSend afterFirst = ScreenVisionSender.evaluate(fresh(), 0L, AMPLITUDE, SIZE, RGB, 1);
        assertNotNull(afterFirst, "首包必须发出");
        assertEquals(1L, afterFirst.coveredUntil());

        // 第 2 刻、参数完全相同、仍在节流窗口内，但这次要发 100 刻。
        // 旧实现只看"参数没变" → 返回 null（丢弃），客户端手里就只剩 1 刻。
        LastSend afterSecond = ScreenVisionSender.evaluate(afterFirst, 2L, AMPLITUDE, SIZE, RGB, 100);

        assertNotNull(afterSecond, "延长租期的下发绝不能被节流丢弃");
        assertEquals(102L, afterSecond.coveredUntil(), "覆盖终点应推进到 now + 新时长");
    }

    @Test
    @DisplayName("F09：余量掉进客户端淡出区时必须补发，否则会刷出一次可见的明暗脉动")
    void refreshHappensBeforeClientEntersFadeOut() {
        // 安全线的含义：余量 >= COVERAGE_SAFETY_TICKS 才允许跳过。
        // 恰好等于安全线时，等满一个节流窗口后仍会剩下 FADE_OUT_TICKS 刻满强度余量 → 可以跳过。
        assertNull(ScreenVisionSender.evaluate(
                        sent(100L, 100L + ScreenVisionTiming.COVERAGE_SAFETY_TICKS), 100L, AMPLITUDE, SIZE, RGB, 100),
                "余量恰好等于安全线：撑得住，允许跳过");

        // 少一刻就不够了 → 必须补发
        assertNotNull(ScreenVisionSender.evaluate(
                        sent(100L, 100L + ScreenVisionTiming.COVERAGE_SAFETY_TICKS - 1), 100L, AMPLITUDE, SIZE, RGB, 100),
                "余量低于安全线一刻就必须补发");
    }

    @Test
    @DisplayName("F09：短时长被连续刷新时逐刻补发，客户端不会闪断")
    void shortDurationRepeatedRefreshStaysCovered() {
        // 时长 3 刻、每刻都触发：余量始终低于安全线，于是每刻都补发，客户端覆盖范围持续前移
        LastSend after = ScreenVisionSender.evaluate(fresh(), 0L, AMPLITUDE, SIZE, RGB, 3);
        assertNotNull(after);
        assertEquals(3L, after.coveredUntil());

        for (long now = 1L; now <= 3L; now++) {
            LastSend next = ScreenVisionSender.evaluate(after, now, AMPLITUDE, SIZE, RGB, 3);

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
        LastSend after = ScreenVisionSender.evaluate(fresh(), 0L, AMPLITUDE, SIZE, RGB, 100);
        assertNotNull(after);

        // 紧接着的第 1、2、3、4 刻：参数没变且余量充足 → 全部跳过
        for (long now = 1L; now < ScreenVisionTiming.THROTTLE_TICKS; now++) {
            assertNull(ScreenVisionSender.evaluate(after, now, AMPLITUDE, SIZE, RGB, 100),
                    "第 " + now + " 刻不该发包（窗口未满且余量充足）");
        }

        // 窗口一满就照常刷新（这是修复前就有的常规节拍）
        assertNotNull(ScreenVisionSender.evaluate(after, ScreenVisionTiming.THROTTLE_TICKS,
                AMPLITUDE, SIZE, RGB, 100), "窗口到期后应常规刷新");
    }

    @Test
    @DisplayName("参数任何一项变化都立即放行（强度 / 尺寸 / 颜色）")
    void anyParameterChangeBypassesThrottle() {
        LastSend last = sent(10L, 1000L);

        assertNotNull(ScreenVisionSender.evaluate(last, 11L, AMPLITUDE + 0.5F, SIZE, RGB, 100), "强度变化");
        assertNotNull(ScreenVisionSender.evaluate(last, 11L, AMPLITUDE, SIZE + 0.1F, RGB, 100), "尺寸变化");
        assertNotNull(ScreenVisionSender.evaluate(last, 11L, AMPLITUDE, SIZE, RGB ^ 0xFFFFFF, 100), "颜色变化");
    }

    @Test
    @DisplayName("容差内的浮点抖动仍算「没变」，不因逐帧误差产生发包风暴")
    void floatingPointNoiseStaysThrottled() {
        // 差值 1e-5 远小于 1e-4 容差 → 视为没变
        assertNull(ScreenVisionSender.evaluate(sent(10L, 1000L), 11L,
                AMPLITUDE + 1.0E-5F, SIZE - 1.0E-5F, RGB, 100));
    }

    // ================================================================ 租期只增不减

    @Test
    @DisplayName("较短的新时长不会把已承诺的长租期缩短")
    void shorterDurationNeverShrinksCoverage() {
        LastSend after = ScreenVisionSender.evaluate(sent(100L, 1000L), 500L, AMPLITUDE, SIZE, RGB, 10);

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
        assertNotNull(ScreenVisionSender.evaluate(fresh(), 0L, AMPLITUDE, SIZE, RGB, 1),
                "初始强度为 0，任何正的强度都算参数变化");
    }

    @Test
    @DisplayName("窗口外的常规刷新不受余量判断影响")
    void refreshOutsideWindowIsUnaffected() {
        assertTrue(passes(sent(0L, 100_000L), ScreenVisionTiming.THROTTLE_TICKS, 100),
                "余量极充足、但窗口已满 → 仍按常规节拍刷新");
    }
}
