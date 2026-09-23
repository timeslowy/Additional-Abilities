package by.timeslowly.additional_abilities.registry.dragon.ability.activation;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Activation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Animations;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Sound;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 「蓄力档位」类激活类型的<b>共享契约</b>。
 * <p>
 * 本接口把 {@code additional_abilities:charged} 与 {@code additional_abilities:optional_charged} 共用的
 * 全部档位换算逻辑集中在一处，两个实现各自只保留「字段 + CODEC + 类型标识」，
 * 避免同一套踩过坑的数学被复制两份后各自漂移。
 *
 * <h2>档位是什么</h2>
 * 蓄力类激活类型的读条过程被理解为<b>蓄力</b>：按住期间按蓄力时长逐档提升，
 * <b>松手时按当前所达到（或玩家指定）的档位释放技能</b>。
 * <ul>
 *     <li>蓄力未达 {@link #getMinimumChargeTicks()}（档位 1 所需时长）就松手 → 视为取消施法；
 *         与服务端 {@code stopCasting} 的原生"提前松手"路径一致，<b>不产生冷却</b>。</li>
 *     <li>若 {@code charged_duration_per_level} 在某一档超过蓄力上限，则封顶为该上限
 *         —— 这就是"最高等级所需蓄力时长不得超过 {@code cast_time}"的落地点。</li>
 *     <li>一直按到 {@code cast_time}：{@link #canChargeExceedCastTime()} 为 {@code false} 时由
 *         DS 原生流程自动释放；为 {@code true} 时由
 *         {@code mixins.DragonAbilityInstanceMixin} 撑开完成判定，可以继续按住 ——
 *         这段"超限窗口"的长度由 {@link #maxOverchargedDuration()} 给出上限，
 *         到点仍会回到 DS 原生流程自动释放（见 {@link #getHoldLimitTicks(int)}）；
 *         不配置该字段则无限按住。</li>
 * </ul>
 *
 * <h2>等级传递</h2>
 * DS 里所有效果取等级的唯一来源都是 {@link DragonAbilityInstance#level()}，
 * 因此"按档位释放"的落地手法是：在服务端执行动作前临时 {@code setLevel(档位)}、执行完立刻还原，
 * 并在同一窗口内完成扣蓝与冷却结算（见 {@code common.ability.ChargedCasts}）。
 *
 * <h2>两个实现的分工</h2>
 * <table border="1">
 *     <tr><th>实现</th><th>{@code can_charge_exceed_cast_time} 默认</th><th>{@link #selectsReleaseLevel()}</th></tr>
 *     <tr><td>{@code ChargedActivation}</td><td>{@code false}</td><td>{@code false}（松手即用已达成档位）</td></tr>
 *     <tr><td>{@code OptionalChargedActivation}</td><td>{@code true}</td>
 *         <td>{@code true}（松手时使用玩家用滚轮指定的档位，可为 0 = 取消）</td></tr>
 * </table>
 *
 * <h2>实现约束</h2>
 * 因为 {@link LevelBasedValue} 以 1 为最低计算等级（0 会被 {@code lookup} 类型读成越界），
 * 本接口所有涉及等级的换算都从 1（{@link DragonAbilityInstance#MIN_LEVEL_FOR_CALCULATIONS}）起算，
 * 等级 0 一律视为"技能未解锁 / 无效"。
 */
public interface ChargeableActivation extends Activation {
    /** 档位 0 表示"蓄力不足，未达成任何档位"。同时也是"可选性蓄力"里的取消档位。 */
    int NO_CHARGED_LEVEL = 0;

    /**
     * 总蓄力上限。必须为正 —— 否则蓄力过程无意义（第 1 刻就读条完成），
     * 因此在解析期直接拦下，而不是留到运行时。
     * <p>
     * 两个实现共用一份校验器，故报错文案不含具体类型名。
     */
    Codec<LevelBasedValue> CAST_TIME_CODEC = LevelBasedValue.CODEC.validate(value ->
            value.calculate(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) > 0
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Chargeable activation requires a positive [cast_time] (it is the maximum charge duration)"));

    /**
     * 超限窗口长度（tick），对应 JSON {@code max_overcharged_duration}；可选，
     * 只在 {@link #canChargeExceedCastTime()} 为 {@code true} 时才有意义。
     * <p>
     * 必须为正 —— 它回答的是"按满 {@code cast_time} 之后还能再按住多久"，
     * 取 0 意味着窗口根本不存在（此时该字段毫无意义，而且会把最高档的落点压在 DS
     * 完成判定的那一帧上，满档数字与提示音永远不会出现），因此在解析期直接拦下；
     * 想要"没有超限窗口"应当不开启 {@code can_charge_exceed_cast_time}。
     */
    Codec<LevelBasedValue> MAX_OVERCHARGED_DURATION_CODEC = LevelBasedValue.CODEC.validate(value ->
            value.calculate(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) > 0
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Chargeable activation requires a positive [max_overcharged_duration] (it is how long you may keep charging past [cast_time])"));

    /**
     * 跨字段校验：超限窗口建立在"允许按过 {@code cast_time} 仍不释放"这个前提之上，
     * 因此"写了 {@code max_overcharged_duration} 却没开 {@code can_charge_exceed_cast_time}"
     * 属于配置自相矛盾 —— 在数据包加载期直接报错，而不是留到运行时静默失效。
     * <p>
     * 由两个实现的 {@code CODEC} 经 {@code MapCodec#validate} 调用。
     *
     * @param activation 待校验的蓄力档位激活类型
     * @param <T>        具体实现类型，便于直接以方法引用形式充当校验器
     * @return 校验通过时原样返回，否则给出报错信息
     */
    static <T extends ChargeableActivation> DataResult<T> validateOverchargeSupport(final @NotNull T activation) {
        if (!activation.canChargeExceedCastTime() && activation.maxOverchargedDuration().isPresent()) {
            return DataResult.error(() -> "Chargeable activation cannot use [max_overcharged_duration] while [can_charge_exceed_cast_time] is [false] (there would be no overcharge window)");
        }

        return DataResult.success(activation);
    }

    /** 本类激活类型没有"引导期"，故禁 {@code looping} 音效（与 {@code dragonsurvival:simple} 一致）。 */
    Codec<Sound> SOUND_CODEC = Sound.CODEC.validate(sound ->
            sound.looping().isPresent()
                    ? DataResult.error(() -> "Chargeable activation does not support [looping] sounds")
                    : DataResult.success(sound));

    /** 同理禁 {@code looping} 动画。 */
    Codec<Animations> ANIMATIONS_CODEC = Animations.CODEC.validate(animations ->
            animations.looping().isPresent()
                    ? DataResult.error(() -> "Chargeable activation does not support [looping] animations")
                    : DataResult.success(animations));

    /**
     * 各档位所需的蓄力时长（tick），对应 JSON {@code charged_duration_per_level}。
     * <p>
     * 逐级求值得到各档阈值；若某档超过 {@link #chargeCapTicks(int)} 则封顶（见 {@link #getRequiredChargeTicks(int)}）。
     */
    LevelBasedValue chargedDurationPerLevel();

    /** 总蓄力上限（tick），对应 {@code cast_time}；必填且必须为正。 */
    LevelBasedValue castTime();

    /**
     * 是否允许蓄力按住超过上限而不释放，对应 {@code can_charge_exceed_cast_time}。
     * <p>
     * 开启后由 {@code mixins.DragonAbilityInstanceMixin} 撑开 DS 的完成判定，
     * 撑开的幅度由 {@link #maxOverchargedDuration()} 决定（见 {@link #getHoldLimitTicks(int)}）。
     */
    boolean canChargeExceedCastTime();

    /**
     * 超限窗口长度（tick），对应 JSON {@code max_overcharged_duration}；缺省表示"无限按住"，
     * 即保持该字段引入之前的原有行为。
     * <p>
     * 只在 {@link #canChargeExceedCastTime()} 为 {@code true} 时才有意义，此时
     * {@code cast_time + 该值} 就是按住释放的上限（见 {@link #getHoldLimitTicks(int)}）。
     * 它<b>不</b>抬高档位阈值上限 {@link #chargeCapTicks(int)} —— 超限窗口里档位不再提升，
     * 只是"保持满档、可松手、可滚轮挑档"的那段窗口。
     */
    Optional<LevelBasedValue> maxOverchargedDuration();

    /** 冷却时间（tick），按档位求值。 */
    Optional<LevelBasedValue> cooldown();

    /** 初始魔力消耗，按档位求值。 */
    Optional<LevelBasedValue> initialManaCost();

    /**
     * 本类型在松手时是否需要"由玩家指定释放档位"的语义。
     *
     * @return {@code false}（默认）表示松手时直接用"当前已达成档位"，与原生蓄力一致；
     *         {@code true} 表示松手时使用客户端滚轮指定的档位（可为 {@link #NO_CHARGED_LEVEL} = 取消）
     */
    default boolean selectsReleaseLevel() {
        return false;
    }

    /**
     * 档位 {@code level} 所需的蓄力时长（tick）。
     * <p>
     * 若 {@code charged_duration_per_level} 在该档位超过蓄力时长上限 {@link #chargeCapTicks(int)}，
     * 则封顶为该上限 —— 这就是"最高等级所需蓄力时长不得超过 {@code cast_time}"的落地点。
     *
     * @param level 档位，从 1（{@link DragonAbilityInstance#MIN_LEVEL_FOR_CALCULATIONS}）起算
     * @return 该档位所需的蓄力游戏刻数
     */
    default int getRequiredChargeTicks(final int level) {
        int required = Math.max(0, (int) chargedDurationPerLevel().calculate(level));
        return Math.min(required, chargeCapTicks(level));
    }

    @Override
    default int getCastTime(final int level) {
        return (int) castTime().calculate(level);
    }

    /**
     * 蓄力时长的有效上限：超过它之后档位不再提升。
     * <p>
     * 不能超上限蓄力时前移 1 刻 —— DS 在 {@code currentTick == cast_time} 当刻就完成释放，
     * "仍在蓄力"的状态观察不到那一帧；若把上限压在 {@code cast_time}，
     * 最高档的落点就在客户端看不到的那一帧（满档数字与提示音永远不会出现），
     * 而且"最后一刻松手"会比"按满自动释放"低一档，自相矛盾。
     * <p>
     * 注意本值只管<b>档位阈值</b>，与超限窗口无关：窗口内档位不再提升，
     * "还能按住多久"是另一个量，见 {@link #getHoldLimitTicks(int)}。
     */
    default int chargeCapTicks(final int level) {
        int castTimeTicks = Math.max(1, getCastTime(level));
        return canChargeExceedCastTime() ? castTimeTicks : Math.max(1, castTimeTicks - 1);
    }

    /**
     * 超限窗口长度（tick）：按满 {@code cast_time} 之后还允许继续按住的时长。
     *
     * @param level 求值等级
     * @return 未配置 {@link #maxOverchargedDuration()} 时为 {@code 0}
     */
    default int getOverchargeTicks(final int level) {
        return maxOverchargedDuration().map(value -> Math.max(0, (int) value.calculate(level))).orElse(0);
    }

    /**
     * 按住释放的上限：到达这一刻，DS 就会走原生"读条完成"流程自动释放
     * （以玩家自身等级执行一次动作、扣初始魔力、进冷却）。
     * <ul>
     *     <li>{@link #canChargeExceedCastTime()} 为 {@code false} → 不适用，
     *         返回 {@code cast_time} 本身（DS 自己在那一刻完成释放）；</li>
     *     <li>为 {@code true} 且未配置 {@link #maxOverchargedDuration()} → {@link Integer#MAX_VALUE}，
     *         即按满后可以无限按住，只有松手才释放；</li>
     *     <li>为 {@code true} 且配置了该字段 → {@code cast_time + max_overcharged_duration}，到点自动释放
     *         （并保证严格晚于 {@link #chargeCapTicks(int)}，见下）。</li>
     * </ul>
     * 该值被 {@code mixins.DragonAbilityInstanceMixin} 用作 {@code tickActions} 的完成判定阈值，
     * 因此"到点自动释放"完全走 DS 原生路径，本模组不需要额外接管。
     * <p>
     * <b>不变量</b>：该值严格大于 {@link #chargeCapTicks(int)}。DS 在到达该值的那一帧才释放，
     * 所以只有"阈值上限 + 1 ≤ 释放点"才能保证最高档在释放之前至少有一帧是可以被看到、可以被松手放出的。
     * <p>
     * 与 {@link #getRequiredChargeTicks(int)} 的区别：后者是<b>档位阈值</b>（超限窗口里不再增长），
     * 前者是<b>按住时长上限</b>。
     *
     * @param level 求值等级（DS 完成判定用的是玩家自身的技能升级等级）
     * @return DS 完成判定的阈值（游戏刻）
     */
    default int getHoldLimitTicks(final int level) {
        if (!canChargeExceedCastTime()) {
            // 不适用：DS 自己在 cast_time 当刻完成释放（本方法只被"允许超限蓄力"的技能用到）
            return Math.max(1, getCastTime(level));
        }

        if (maxOverchargedDuration().isEmpty()) {
            return Integer.MAX_VALUE;
        }

        // 释放点必须严格晚于档位阈值上限，否则最高档的落点就落在释放那一帧上（数字与提示音永远看不到）；
        // 超限窗口把它推得更远。两者取大，顺带兜住"逐档求值把窗口算成 0"的极端配置。
        // 用 long 计算再钳回 int：cast_time 与超限窗口都允许配得很大，避免溢出成负数
        long limit = Math.max((long) chargeCapTicks(level) + 1, (long) getCastTime(level) + getOverchargeTicks(level));
        return (int) Math.min(limit, Integer.MAX_VALUE);
    }

    /**
     * 最低蓄力时长：档位 1 所需的蓄力时长，未达此值松手视为取消施法。
     *
     * @return 最少需要按住的游戏刻数
     */
    default int getMinimumChargeTicks() {
        return getRequiredChargeTicks(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS);
    }

    @Override
    default int getCooldown(final int level) {
        return cooldown().map(value -> (int) value.calculate(level))
                .orElseGet(() -> Activation.super.getCooldown(level));
    }

    @Override
    default float getInitialManaCost(final int level) {
        return initialManaCost().map(cost -> cost.calculate(level))
                .orElseGet(() -> Activation.super.getInitialManaCost(level));
    }

    /**
     * 由已蓄力时长反查"已达成档位"。
     *
     * @param chargeTicks 已蓄力的游戏刻数
     * @param playerLevel 玩家自身的技能升级等级，档位不会超过它
     * @return 达成的最高档位；未达最低蓄力时长或玩家等级为 0 时返回 {@link #NO_CHARGED_LEVEL}
     */
    default int getChargedLevel(final int chargeTicks, final int playerLevel) {
        if (playerLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS
                || chargeTicks < getMinimumChargeTicks()) {
            return NO_CHARGED_LEVEL;
        }

        int result = NO_CHARGED_LEVEL;

        // 取"满足时长的最高档位"而不是遇到第一个不满足就中断：
        // 配置非单调（例如 lookup 手写成高低起伏）时，语义仍然是"蓄力足够久就该拿到对应档位"
        for (int level = DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS; level <= playerLevel; level++) {
            if (getRequiredChargeTicks(level) <= chargeTicks) {
                result = level;
            }
        }

        return result;
    }

    /**
     * 类型判定：激活类型属于"蓄力档位"族系时返回其契约视图。
     * <p>
     * 客户端与服务端共用同一判据，避免两端出现"一边认、一边不认"的分歧。
     *
     * @param activation 待判定的激活类型
     * @return 命中时为该实现的契约视图，否则为空
     */
    static Optional<ChargeableActivation> of(final Activation activation) {
        return activation instanceof ChargeableActivation chargeable ? Optional.of(chargeable) : Optional.empty();
    }

    /**
     * 是否为蓄力档位族系（客户端 / 服务端共用的类型判定）。
     *
     * @param activation 待判定的激活类型
     * @return 该激活类型是否属于本族系
     */
    static boolean isChargeable(final @NotNull Activation activation) {
        return activation instanceof ChargeableActivation;
    }

    /**
     * 便捷入口：技能实例的激活类型属于本族系时才换算档位，否则返回 {@link #NO_CHARGED_LEVEL}。
     * <p>
     * 客户端与服务端都要用同一套换算（HUD 显示、松手结算、查询指令），故放在这里统一提供。
     *
     * @param instance    技能实例，等级取 {@link DragonAbilityInstance#level()}（即玩家真实升级等级）
     * @param chargeTicks 已蓄力的游戏刻数
     * @return 达成的最高档位；类型不匹配、未达最低蓄力时长或玩家等级为 0 时为 {@link #NO_CHARGED_LEVEL}
     */
    static int getChargedLevelOf(final @NotNull DragonAbilityInstance instance, final int chargeTicks) {
        return of(instance.value().activation())
                .map(chargeable -> chargeable.getChargedLevel(chargeTicks, instance.level()))
                .orElse(NO_CHARGED_LEVEL);
    }
}
