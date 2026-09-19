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
 *         {@code mixins.DragonAbilityInstanceMixin} 撑开完成判定，可一直按住不释放。</li>
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
     * 开启后由 {@code mixins.DragonAbilityInstanceMixin} 撑开 DS 的完成判定。
     */
    boolean canChargeExceedCastTime();

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
     */
    default int chargeCapTicks(final int level) {
        int castTimeTicks = Math.max(1, getCastTime(level));
        return canChargeExceedCastTime() ? castTimeTicks : Math.max(1, castTimeTicks - 1);
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
