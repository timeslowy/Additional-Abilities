package by.timeslowly.additional_abilities.common.ability.activation;

import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 复审 P2 的回归测试：{@code current_charged_level} 在<b>蓄力早期</b>必须答“当前档位（含 0）”，
 * 而不是回退成<b>上一次</b>释放的档位。
 *
 * <h2>报告中的固定情景</h2>
 * 玩家上一轮释放过 3 档，下一轮<b>刚按下、还没蓄到最低档</b>时查询：
 * <ul>
 *     <li>正确语义（指令文档承诺的）：“正在蓄力 → 当前蓄力所对应的档位” ⇒ 答案是 {@code 0}；</li>
 *     <li>旧实现：当前换算值为 0，于是掉进“读最近一次记录”的分支 ⇒ 答 {@code 3}。</li>
 * </ul>
 * 两者矛盾，且旧行为对玩家是“撒谎”：此刻松手一档都放不出来。
 *
 * <h2>这条测试守的是“回退时机”，不是某个具体数字</h2>
 * 断言的边界是「<b>能否求值</b>」，与“当前值大不大”无关。
 * 因此下面既钉住“能求值就一律不回退”（含 0），也钉住“取不到值时才回退”。
 *
 * <h2>为什么不测 {@code resolveQueryLevel} 本身</h2>
 * 它需要 {@code Player} 与 {@code DragonAbilityInstance}，本机无外网、无 Mockito，
 * 造不出这两个重型对象。因此被测的是它委托的纯决策
 * {@code ChargedCasts.resolveQueryLevelPure}（行为等价的抽取，见该方法的 javadoc）。
 */
class ChargedQueryLevelTest {
    /** 上一轮实际释放记录到的档位（报告固定情景里的 3） */
    private static final int LAST = 3;

    /** 无记录：与 {@code ChargeableActivation.NO_CHARGED_LEVEL} 同值 */
    private static final int NO_RECORD = ChargeableActivation.NO_CHARGED_LEVEL;

    // ================================================================ 报告固定情景

    @Test
    @DisplayName("P2：刚按下、尚未达最低档时查询当前档位必须答 0，不能答上一轮的 3")
    void earlyChargeReportsZeroNotThePreviousRelease() {
        int result = ChargedCasts.resolveQueryLevelPure(true, 0, LAST);

        assertEquals(0, result,
                "正在蓄力时只报当前值：此刻一档都放不出来，答 3 与指令文档矛盾");
    }

    @Test
    @DisplayName("P2：蓄力中且已蓄到档位 → 答当前值（不受上一轮记录影响）")
    void midChargeReportsTheLiveLevel() {
        assertEquals(2, ChargedCasts.resolveQueryLevelPure(true, 2, LAST),
                "当前值就是答案，与上一轮记录无关");
    }

    @Test
    @DisplayName("P2：当前值恰好等于上一轮记录 → 仍答当前值（不得借机走回退）")
    void liveLevelEqualToHistoryStillReportsLive() {
        assertEquals(LAST, ChargedCasts.resolveQueryLevelPure(true, LAST, LAST),
                "两个来源数值相同时结果相同，但走的分支必须是‘当前值’");
    }

    // ================================================================ 回退只在“取不到当前值”时发生

    @Test
    @DisplayName("P2：未在施法 → 答最近一次实际释放的档位")
    void notCastingFallsBackToHistory() {
        assertEquals(LAST, ChargedCasts.resolveQueryLevelPure(false, 0, LAST),
                "没有当前施法时，最近一次记录才是正确答案");
    }

    @Test
    @DisplayName("P2：未在施法且无记录 → 无档位")
    void notCastingWithoutHistoryReportsNoLevel() {
        assertEquals(NO_RECORD, ChargedCasts.resolveQueryLevelPure(false, 0, NO_RECORD));
    }

    /**
     * 等级过低（{@code < MIN_LEVEL_FOR_CALCULATIONS}）时无法求当前值，
     * 因此即使实例 {@code currentTick > 0} 也必须回退。
     * <p>
     * 这是<b>唯一</b>一条“施法中仍回退”的例外，且理由是硬的：
     * {@code LevelBasedValue} 不接受 0 级（{@code Lookup#calculate(0)} 会越界），
     * 根本取不到“当前档位”这个值。
     */
    @Test
    @DisplayName("P2：施法中但等级过低无法求值 → 仍回退到最近记录（唯一的例外）")
    void castingButLevelTooLowStillFallsBack() {
        assertEquals(LAST, ChargedCasts.resolveQueryLevelPure(false, 0, LAST),
                "取不到当前值就只能用最近记录，这是无值可用而非语义选择");
    }

    // ================================================================ 纯函数的判据是自洽的

    @Test
    @DisplayName("P2：能求值时结果恒等于 liveLevel（含 0），lastLevel 完全不参与")
    void evaluatingAlwaysReturnsLiveLevelRegardlessOfHistory() {
        for (int live = 0; live <= 8; live++) {
            for (int last = 0; last <= 8; last++) {
                assertEquals(live, ChargedCasts.resolveQueryLevelPure(true, live, last),
                        "能求值时只报当前值；live=" + live + " last=" + last);
            }
        }
    }

    @Test
    @DisplayName("P2：不能求值时结果恒等于 lastLevel，liveLevel 完全不参与")
    void notEvaluatingAlwaysReturnsHistory() {
        for (int live = 0; live <= 8; live++) {
            for (int last = 0; last <= 8; last++) {
                assertEquals(last, ChargedCasts.resolveQueryLevelPure(false, live, last),
                        "取不到当前值时只报记录；live=" + live + " last=" + last);
            }
        }
    }
}
