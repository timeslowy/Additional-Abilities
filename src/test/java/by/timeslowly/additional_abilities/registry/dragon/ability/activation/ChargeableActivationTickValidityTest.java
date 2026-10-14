package by.timeslowly.additional_abilities.registry.dragon.ability.activation;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Activation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Animations;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Notification;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Sound;
import com.mojang.serialization.DataResult;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cast_time} / {@code max_overcharged_duration} 的 <b>tick 值有效性契约</b>
 * （审查报告 §4.2：Codec、Java 构造与运行时解析必须遵守同一契约）。
 *
 * <h2>要钉住的缝隙</h2>
 * 旧校验只要求"浮点值 &gt; 0"，而运行时唯一使用的粒度是<b>整数刻</b>。于是
 * {@code cast_time: 0.5} 能通过解析期校验，随后被截断成 {@code 0} ——
 * 那个技能的蓄力过程彻底退化（第 1 刻就读条完成），而配置里写着一个正数，
 * 玩家和作者都看不出哪里错了。报告里举的正是 {@code 0.5} 这个例子。
 *
 * <h2>为什么用真 Codec 而不是直接比对常量</h2>
 * 断言的对象是"{@code CAST_TIME_CODEC} 会拒绝什么"，因此必须真的把值喂进 Codec
 * ——直接读 {@code getCastTime()} 只能证明运行时兜底生效，证明不了"解析期就报错"，
 * 而"让写错的数据包当场报出来"才是这条契约的目的。
 * <p>
 * {@link LevelBasedValue#constant(float)} 与 {@link LevelBasedValue#linear} 都是纯值构造，
 * 不触注册表，因此本类可在普通单元测试里运行。
 */
class ChargeableActivationTickValidityTest {
    private static final int MIN_LEVEL = 1;

    // ================================================================ 解析期：cast_time

    @Test
    @DisplayName("cast_time：1 刻是合法下界")
    void castTimeAcceptsOneTick() {
        assertSuccess(ChargeableActivation.CAST_TIME_CODEC, LevelBasedValue.constant(1.0F));
    }

    @Test
    @DisplayName("cast_time：0.5 被拒绝 —— 它就是那条缝隙（正数，却折算成 0 刻）")
    void castTimeRejectsFractionalBelowOneTick() {
        assertFailure(ChargeableActivation.CAST_TIME_CODEC, LevelBasedValue.constant(0.5F));
    }

    @Test
    @DisplayName("cast_time：0 与负数继续被拒绝（原有行为不变）")
    void castTimeRejectsNonPositive() {
        assertFailure(ChargeableActivation.CAST_TIME_CODEC, LevelBasedValue.constant(0.0F));
        assertFailure(ChargeableActivation.CAST_TIME_CODEC, LevelBasedValue.constant(-5.0F));
    }

    @Test
    @DisplayName("cast_time：1.9 折算成 1 刻，合法（截断而非四舍五入）")
    void castTimeTruncatesTowardZero() {
        assertSuccess(ChargeableActivation.CAST_TIME_CODEC, LevelBasedValue.constant(1.9F));
    }

    @Test
    @DisplayName("cast_time：现有正常配置（整数正数）一个都不受影响")
    void castTimeKeepsExistingConfigsValid() {
        // 遍历本模组与内置数据包实际使用的取值（50 / 60 / 20 / 10 / 30 / 5 / 80 / 40）
        for (float ticks : new float[]{5.0F, 10.0F, 20.0F, 30.0F, 40.0F, 50.0F, 60.0F, 80.0F}) {
            assertSuccess(ChargeableActivation.CAST_TIME_CODEC, LevelBasedValue.constant(ticks));
        }
    }

    // ================================================================ 解析期：max_overcharged_duration

    @Test
    @DisplayName("max_overcharged_duration：判定与 cast_time 同口径")
    void overchargeDurationSharesContract() {
        assertSuccess(ChargeableActivation.MAX_OVERCHARGED_DURATION_CODEC, LevelBasedValue.constant(1.0F));
        assertSuccess(ChargeableActivation.MAX_OVERCHARGED_DURATION_CODEC, LevelBasedValue.constant(100.0F));
        assertFailure(ChargeableActivation.MAX_OVERCHARGED_DURATION_CODEC, LevelBasedValue.constant(0.5F));
        assertFailure(ChargeableActivation.MAX_OVERCHARGED_DURATION_CODEC, LevelBasedValue.constant(0.0F));
    }

    @Test
    @DisplayName("解析期只看最低计算等级：高等级算出别的值不由 Codec 裁决")
    void codecChecksLowestLevelOnly() {
        // linear 配负增量：1 级为 10（合法），2 级算出 -10。
        // 这必须<b>通过</b>解析 —— 逐级校验等于替数据包作者决定成长曲线，
        // 且会拒绝现有正常配置（报告明确要求"不得悄悄改变已支持的正常配置"）。
        LevelBasedValue decaying = LevelBasedValue.perLevel(10.0F, -20.0F);

        assertSuccess(ChargeableActivation.CAST_TIME_CODEC, decaying);
        assertEquals(10, (int) decaying.calculate(1));
        assertEquals(-10, (int) decaying.calculate(2));
    }

    // ================================================================ 运行时：同一契约的兜底

    @Test
    @DisplayName("运行时 getCastTime：高等级算出非正值时退化为 1 刻，而不是 0")
    void runtimeCastTimeFloorsAtOneTick() {
        // 关掉 can_charge_exceed_cast_time 以免 convalidate 拒绝 max_overcharged_duration 缺席之外的组合
        TestActivation activation = new TestActivation(LevelBasedValue.perLevel(10.0F, -20.0F), false);

        // 1 级：正常值
        assertEquals(10, activation.getCastTime(1));

        // 2 级：配置算出 -10 → 必须被兜底为 1，否则 currentTick 第 1 刻就追上 cast_time，
        // DS 会在第 1 刻直接自动释放
        assertEquals(-10, (int) activation.castTime().calculate(2));
        assertEquals(1, activation.getCastTime(2));
    }

    @Test
    @DisplayName("运行时 getCastTime：第三方直接构造绕过 Codec 时也被兜住")
    void runtimeCastTimeFloorsForDirectConstruction() {
        // 这条正是"Codec / Java 构造 / 运行时遵守同一契约"里 Java 构造那一环：
        // 不走 Codec 就不受解析期校验约束，只能靠运行时兜底
        assertEquals(1, new TestActivation(LevelBasedValue.constant(0.0F), false).getCastTime(1));
        assertEquals(1, new TestActivation(LevelBasedValue.constant(-3.0F), false).getCastTime(1));
        assertEquals(1, new TestActivation(LevelBasedValue.constant(0.5F), false).getCastTime(1));
    }

    @Test
    @DisplayName("运行时 getOverchargeTicks：配置过就至少 1 刻，未配置才是 0")
    void runtimeOverchargeTicksFloorsAtOneWhenPresent() {
        // 未配置 → 0（语义为"没有超限窗口概念"，与"配了但算成 0"必须区分开）
        assertEquals(0, new TestActivation(LevelBasedValue.constant(60.0F), true, Optional.empty()).getOverchargeTicks(1));

        // 配置了但算成 0.5 → 兜到 1。若就地截断成 0，窗口会静默消失
        //（而 hasOverchargeWindow 之类的判定会因此翻转），属于"配了却没效果"
        assertEquals(1, new TestActivation(LevelBasedValue.constant(60.0F), true, Optional.of(LevelBasedValue.constant(0.5F)))
                .getOverchargeTicks(1));

        // 正常值不受影响
        assertEquals(20, new TestActivation(LevelBasedValue.constant(60.0F), true, Optional.of(LevelBasedValue.constant(20.0F)))
                .getOverchargeTicks(1));
    }

    @Test
    @DisplayName("契约一致性：解析期拒绝的值，运行时也一定兜得住（两层不脱节）")
    void parseTimeAndRuntimeAgreeOnTheFloor() {
        // 解析期放行的最小合法值，运行时必须原样保留（1 刻）
        LevelBasedValue minimum = LevelBasedValue.constant(1.0F);
        assertSuccess(ChargeableActivation.CAST_TIME_CODEC, minimum);
        assertEquals(1, new TestActivation(minimum, false).getCastTime(MIN_LEVEL));

        // 而解析期拒绝的值（0.5），即便被绕过，运行时也只给到 1 —— 不会给出 0
        assertFailure(ChargeableActivation.CAST_TIME_CODEC, LevelBasedValue.constant(0.5F));
        assertTrue(new TestActivation(LevelBasedValue.constant(0.5F), false).getCastTime(MIN_LEVEL) >= 1);
    }

    // ================================================================ 夹具

    /**
     * 让 Codec 的校验器跑一遍。
     * <p>
     * {@code Codec#validate} 是<b>对称</b>的：DFU 的实现里 encode 与 decode 都会调用那个校验器，
     * 而它本身没有公开的取值器。因此用 {@code encodeStart} 触发 —— 这条路不碰世界、也不需要
     * 真实的数据包读取，且对 {@code Constant} 形态完全不需要注册表参与。
     */
    private static DataResult<?> runValidator(final com.mojang.serialization.Codec<LevelBasedValue> codec,
                                              final LevelBasedValue value) {
        return codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, value);
    }

    private static void assertSuccess(final com.mojang.serialization.Codec<LevelBasedValue> codec,
                                      final LevelBasedValue value) {
        DataResult<?> result = runValidator(codec, value);
        assertTrue(result.result().isPresent(),
                "应当通过校验，却被拒绝：" + value + " —— " + describe(result));
    }

    private static void assertFailure(final com.mojang.serialization.Codec<LevelBasedValue> codec,
                                      final LevelBasedValue value) {
        DataResult<?> result = runValidator(codec, value);

        assertFalse(result.result().isPresent(), "应当被拒绝，却通过了校验：" + value);
        // 报错信息里必须说明 tick 粒度，否则作者只知道"数据包加载失败"，不知道错在"不足 1 刻"
        assertTrue(result.error().isPresent() && result.error().get().message().contains("tick"),
                "报错信息应说明 tick 粒度，实际为：" + describe(result));
    }

    private static String describe(final DataResult<?> result) {
        return result.error().map(DataResult.Error::message).orElse("<无错误信息>");
    }

    /**
     * 只实现被测契约所需部分的激活类型。
     * <p>
     * 其余字段取最小合法值 —— 本类断言的对象是 {@link ChargeableActivation} 里那些
     * {@code default} 方法，它们只读 {@code castTime()} 与 {@code maxOverchargedDuration()}。
     */
    private record TestActivation(LevelBasedValue castTime,
                                  boolean canChargeExceed,
                                  Optional<LevelBasedValue> maxOverchargedDuration,
                                  LevelBasedValue chargedDurationPerLevel) implements ChargeableActivation {

        private TestActivation(final LevelBasedValue castTime, final boolean canChargeExceed) {
            this(castTime, canChargeExceed, Optional.empty(), LevelBasedValue.constant(10.0F));
        }

        private TestActivation(final LevelBasedValue castTime, final boolean canChargeExceed,
                               final Optional<LevelBasedValue> maxOverchargedDuration) {
            this(castTime, canChargeExceed, maxOverchargedDuration, LevelBasedValue.constant(10.0F));
        }

        @Override
        public boolean canChargeExceedCastTime() {
            return canChargeExceed;
        }

        @Override
        public Optional<LevelBasedValue> cooldown() {
            return Optional.empty();
        }

        @Override
        public Optional<LevelBasedValue> initialManaCost() {
            return Optional.empty();
        }

        @Override
        public Notification notification() {
            return Notification.DEFAULT;
        }

        @Override
        public Optional<Sound> sound() {
            return Optional.empty();
        }

        @Override
        public Optional<Animations> animations() {
            return Optional.empty();
        }

        @Override
        public Activation.@NotNull Type type() {
            return Activation.Type.SIMPLE;
        }

        @Override
        public com.mojang.serialization.MapCodec<? extends Activation> codec() {
            throw new UnsupportedOperationException("测试替身不参与编解码");
        }
    }
}
