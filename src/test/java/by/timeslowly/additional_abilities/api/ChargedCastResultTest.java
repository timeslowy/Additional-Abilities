package by.timeslowly.additional_abilities.api;

import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChargedCastResult} 的自洽性契约（审查报告 F15）。
 *
 * <h2>为什么这批用例值得存在</h2>
 * 这个类型的全部价值就在于"<b>结果不可能自相矛盾</b>"——调用方靠
 * {@link ChargedCastResult#handled()} 决定要不要拦下 DS 原生流程、靠
 * {@link ChargedCastResult#releasedLevel()} 决定后续数值处理。若允许构造出
 * {@code RELEASED} 配 {@code NOT_CHARGEABLE} 这种组合，调用方就又回到了
 * "布尔值看不出原因"的老问题，只是换了个更长的名字。
 * <p>
 * 因此构造期校验就是本类型的核心行为，也是最该被钉住的部分。
 * 本类<b>不碰世界对象</b>（纯值类型），所以能用普通单元测试完整覆盖。
 */
class ChargedCastResultTest {
    // ================================================================ 三个工厂的正常形态

    @Test
    @DisplayName("released：携带档位、handled 为真、reason 为 RELEASED")
    void releasedCarriesLevel() {
        ChargedCastResult result = ChargedCastResult.released(3);

        assertEquals(ChargedCastResult.Outcome.RELEASED, result.outcome());
        assertEquals(ChargedCastResult.Reason.RELEASED, result.reason());
        assertEquals(3, result.releasedLevel());
        assertTrue(result.isReleased());
        assertTrue(result.handled());
    }

    @Test
    @DisplayName("cancelled：档位恒为 NO_LEVEL，但 handled 仍为真（已接管）")
    void cancelledIsHandledWithoutLevel() {
        ChargedCastResult result = ChargedCastResult.cancelled(ChargedCastResult.Reason.INSUFFICIENT_CHARGE);

        assertEquals(ChargedCastResult.Outcome.CANCELLED, result.outcome());
        assertEquals(ChargedCastResult.NO_LEVEL, result.releasedLevel());
        assertFalse(result.isReleased());
        // 关键：取消是"已接管"，必须拦下 DS 原生流程，否则服务端会继续读条
        assertTrue(result.handled());
    }

    @Test
    @DisplayName("rejected：handled 为假，调用方应什么都不做")
    void rejectedIsNotHandled() {
        ChargedCastResult result = ChargedCastResult.rejected(ChargedCastResult.Reason.NOT_CHARGEABLE);

        assertEquals(ChargedCastResult.Outcome.REJECTED, result.outcome());
        assertEquals(ChargedCastResult.NO_LEVEL, result.releasedLevel());
        assertFalse(result.isReleased());
        assertFalse(result.handled());
    }

    // ================================================================ 构造期自洽校验

    @Test
    @DisplayName("RELEASED 不能配非 RELEASED 的原因")
    void releasedMustUseReleasedReason() {
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.RELEASED, 2, ChargedCastResult.Reason.NOT_CHARGEABLE));
    }

    @Test
    @DisplayName("CANCELLED 只能配两种取消原因")
    void cancelledReasonIsRestricted() {
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.CANCELLED, 0, ChargedCastResult.Reason.RELEASED));

        // 两个合法原因都要能构造出来
        assertEquals(ChargedCastResult.Outcome.CANCELLED, new ChargedCastResult(
                ChargedCastResult.Outcome.CANCELLED, 0, ChargedCastResult.Reason.CANCELLED_BY_REQUEST).outcome());
        assertEquals(ChargedCastResult.Outcome.CANCELLED, new ChargedCastResult(
                ChargedCastResult.Outcome.CANCELLED, 0, ChargedCastResult.Reason.INSUFFICIENT_CHARGE).outcome());
    }

    @Test
    @DisplayName("REJECTED 不能配任何带业务含义的原因")
    void rejectedReasonIsRestricted() {
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.REJECTED, 0, ChargedCastResult.Reason.RELEASED));
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.REJECTED, 0, ChargedCastResult.Reason.INSUFFICIENT_CHARGE));
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.REJECTED, 0, ChargedCastResult.Reason.CANCELLED_BY_REQUEST));
    }

    @Test
    @DisplayName("未释放的结果不允许携带档位（避免出现'取消了却报了个档位'）")
    void onlyReleasedCarriesLevel() {
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.CANCELLED, 2, ChargedCastResult.Reason.INSUFFICIENT_CHARGE));
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.REJECTED, 2, ChargedCastResult.Reason.NOT_CHARGEABLE));
    }

    @Test
    @DisplayName("释放结果必须带正档位：0 与负数都不是一次释放")
    void releasedNeedsPositiveLevel() {
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.RELEASED, 0, ChargedCastResult.Reason.RELEASED));
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.RELEASED, -1, ChargedCastResult.Reason.RELEASED));
    }

    @Test
    @DisplayName("空 outcome / reason 被拒绝")
    void nullsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                null, 1, ChargedCastResult.Reason.RELEASED));
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.RELEASED, 1, null));
    }

    // ================================================================ 与内部哨兵解耦

    @Test
    @DisplayName("业务档位里不存在内部协议哨兵：NO_LEVEL 是 0，而 AUTO/CANCEL 属于请求编码")
    void businessLevelHasNoProtocolSentinel() {
        // NO_LEVEL 与 ChargeableActivation.NO_CHARGED_LEVEL 是同一个业务含义（没有档位），
        // 两者必须一致，否则指令的显示口径会与结算口径分叉
        assertEquals(ChargeableActivation.NO_CHARGED_LEVEL, ChargedCastResult.NO_LEVEL);

        // AUTO(-1) 不得作为任何结果的档位出现在业务面上
        assertThrows(IllegalArgumentException.class, () -> new ChargedCastResult(
                ChargedCastResult.Outcome.RELEASED, -1, ChargedCastResult.Reason.RELEASED));
    }

    @Test
    @DisplayName("handled() 与 three-way outcome 的映射：只有 REJECTED 为假")
    void handledMatchesOutcome() {
        assertTrue(ChargedCastResult.released(1).handled());
        assertTrue(ChargedCastResult.cancelled(ChargedCastResult.Reason.INSUFFICIENT_CHARGE).handled());

        // 每个原因都归属且只归属于一类结局：拒绝原因只能构造 REJECTED，
        // 另外三类只能构造 RELEASED / CANCELLED。这条划分本身就是"结果可解释"的保证
        for (ChargedCastResult.Reason reason : ChargedCastResult.Reason.values()) {
            switch (reason) {
                case RELEASED -> {
                    assertTrue(ChargedCastResult.released(2).handled());
                    assertThrows(IllegalArgumentException.class, () -> ChargedCastResult.rejected(reason));
                    assertThrows(IllegalArgumentException.class, () -> ChargedCastResult.cancelled(reason));
                }
                case INSUFFICIENT_CHARGE, CANCELLED_BY_REQUEST -> {
                    assertTrue(ChargedCastResult.cancelled(reason).handled());
                    assertThrows(IllegalArgumentException.class, () -> ChargedCastResult.rejected(reason));
                }
                case NOT_CHARGEABLE, NOT_CURRENTLY_CASTING, ABILITY_NOT_UNLOCKED, OVERCHARGE_NOT_ENABLED -> {
                    assertFalse(ChargedCastResult.rejected(reason).handled(),
                            "REJECTED 的 handled() 必须为 false：" + reason);
                    assertThrows(IllegalArgumentException.class, () -> ChargedCastResult.cancelled(reason));
                }
            }
        }
    }
}
