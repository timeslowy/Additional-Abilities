package by.timeslowly.additional_abilities.registry.dragon.ability.activation;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Activation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Animations;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Notification;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Sound;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 龙之技能激活类型：<b>蓄力档位</b>（{@code additional_abilities:charged}）。
 * <p>
 * 行为上等价于 {@code dragonsurvival:simple} 的"按住读条"，但读条过程被理解为<b>蓄力</b>：
 * 按住期间按蓄力时长逐档提升，<b>松手时按当前所达到的档位释放技能</b>。
 * <ul>
 *     <li>蓄力未达 {@link #getMinimumChargeTicks()}（即档位 1 所需时长）就松手 → 视为取消施法；
 *         与服务端 {@code stopCasting} 的原生"提前松手"路径一致，<b>不产生冷却</b>。</li>
 *     <li>蓄力达到档位 1 起松手 → 以该档位执行一次技能动作，随后按该档位结算冷却 / 结束音效 / 结束动画。</li>
 *     <li>一直按到 {@code cast_time}（= 总蓄力上限）→ 由 {@code DragonSurvival} 自己的
 *         {@code DragonAbilityInstance#tickActions} 原生自动释放，档位即玩家自身升级等级（满档）。</li>
 * </ul>
 *
 * <h2>等级传递：哪些数值按"档位"结算</h2>
 * 本类型最容易看错的一点就是"档位到底影响了什么"。答案是：<b>所有以 {@link LevelBasedValue}
 * （JSON 里写成 {@code {"type": "minecraft:linear"} / {"type": "minecraft:lookup"} …} 的那类按等级求值的函数）
 * 取值的字段，都按档位而不是玩家自身的升级等级结算</b>。
 * <p>
 * 落地手法：DS 里所有效果取等级的唯一来源都是 {@link DragonAbilityInstance#level()}
 * （源码中一律写作 {@code ability.level()}），因此本模组在服务端执行动作前临时
 * {@code setLevel(档位)}、执行完立刻还原，并在同一窗口内完成扣蓝与冷却结算。
 * 详见 {@code by.timeslowly.additional_abilities.common.ability.ChargedCasts#fire}
 * 与 {@code by.timeslowly.additional_abilities.client.eventhandler.ChargedCastClientHandler}。
 * <p>
 * 按档位求值的字段：
 * <ul>
 *     <li><b>技能内全部动作参数</b> —— 伤害、弹射物数量 / 速度 / 散布、方块效果参数、概率判定、时长等；</li>
 *     <li><b>冷却时间 {@code cooldown}</b> —— 收尾走 DS 原生 {@code release}，其内部读
 *         {@code getCooldown(level)}，因此冷却长度随档位变化；</li>
 *     <li><b>初始魔力消耗 {@code initial_mana_cost}</b>；</li>
 *     <li>{@code cast_time} —— 一并作为动作 {@code trigger_rate} 的取模基准传入。</li>
 * </ul>
 * 不随档位变化的是"档位换算本身"：{@code charged_duration_per_level} 逐级求值得到各档阈值，
 * {@code cast_time} 给出蓄力上限（见下节"档位换算"）。
 * <p>
 * 直接后果：一个只升到 3 级的玩家，即使蓄满也只是按 3 级结算上面全部数值。
 * <p>
 * 两处需要留意的边界：
 * <ul>
 *     <li><b>起手校验用的是真实等级</b>：{@code MagicData#checkCast} 在按下技能键那一刻就用玩家真实等级
 *         对应的 {@code initial_mana_cost} 校验法力，因此"低档消耗"不会放宽起手门槛 ——
 *         想放低档也得先付得起满档的起手校验。</li>
 *     <li><b>延迟型效果读不到档位</b>：生成实体后由该实体在后续游戏刻才去读 {@code ability.level()}
 *         的效果读到的是玩家真实等级。在同一窗口内立即结算的效果不受影响。</li>
 * </ul>
 *
 * <h2>JSON 字段（与 {@code activation_type} 平级）</h2>
 * <pre>
 * "charged_duration_per_level": { "type": "minecraft:linear", "base": 10.0, "per_level_above_first": 10.0 } // 必填，各档位所需蓄力时长（tick）
 * "cast_time":                  { "type": "minecraft:linear", "base": 60.0, "per_level_above_first": 0.0  } // 必填，总蓄力上限（tick），必须为正
 * "cooldown":                   200.0                                                                    // 可选，默认 0；按档位求值
 * "initial_mana_cost":          2.0                                                                      // 可选，默认 0；按档位求值
 * "notification":               { "not_enough_mana": …, "usage_blocked": … }                             // 可选
 * "can_move_while_casting":     false                                                                    // 可选，默认 true
 * "can_charge_exceed_cast_time": true                                                                    // 可选，默认 false，见下文
 * "sound":                      { "start": …, "charging": …, "end": … }                                  // 可选，禁 looping
 * "animations":                 { "start_and_charging": …, "end": … }                                     // 可选，禁 looping
 * </pre>
 *
 * <h2>与 {@code dragonsurvival:simple} 的两处有意差异</h2>
 * <ol>
 *     <li>{@code cast_time} 由可选改为<b>必填且必须为正</b>：本类型的"总蓄力上限"就是它，
 *         缺失或为 0 会让蓄力过程退化（第 1 刻就完成），因此在数据包加载期直接报错，
 *         而不是留到运行时表现异常。</li>
 *     <li>禁 {@code looping} 音效 / 动画，与 {@code simple} 相同（本类型同样没有"引导期"）。</li>
 * </ol>
 *
 * <h2>档位换算</h2>
 * 档位 {@code L} 所需时长 = {@code min(charged_duration_per_level(L), 蓄力时长上限(L))}，
 * 也就是"最高等级所需蓄力时长不得超过总施法时间"的落地位置：超限时封顶。
 * <p>
 * 上限取多少取决于 {@link #canChargeExceedCastTime()}：
 * <ul>
 *     <li>{@code false}（默认）：DS 会在 {@code currentTick == cast_time} 的<b>当刻</b>完成释放并立刻停手，
 *         所以"仍在蓄力"的状态最多只能观察到 {@code cast_time - 1}。若把上限压在 {@code cast_time}，
 *         最高档的落点就落在客户端看不到的那一帧 —— 满档数字与提示音永远不会出现；
 *         而且"最后一刻松手"会比"按满自动释放"低一档，自相矛盾。
 *         故上限前移 1 刻（{@code cast_time - 1}），消除这一帧错位。</li>
 *     <li>{@code true}：蓄力按满后可以继续按住不释放 —— {@code currentTick} 会越过 {@code cast_time}
 *         （由 {@code mixins.DragonAbilityInstanceMixin} 撑开 DS 的完成判定），
 *         上限即 {@code cast_time} 本身，满档可以一直保持并显示在 HUD 上。</li>
 * </ul>
 * <p>
 * 因为 {@link LevelBasedValue} 以 1 为最低计算等级（0 会被 {@code lookup} 类型读成越界），
 * 本类所有涉及等级的换算都从 1 起算，等级 0 一律视为"技能未解锁 / 无效"。
 *
 * @param chargedDurationPerLevel 各档位所需的蓄力时长（tick），对应 JSON {@code charged_duration_per_level}
 * @param castTime                总蓄力上限（tick），对应 {@code cast_time}；必填且必须为正
 * @param canChargeExceedCastTime 是否允许蓄力按住超过上限而不释放，对应
 *                                {@code can_charge_exceed_cast_time}（默认 {@code false}）；
 *                                开启后由 {@code mixins.DragonAbilityInstanceMixin} 撑开 DS 的完成判定
 * @param cooldown                冷却时间（tick），按档位求值
 * @param initialManaCost         初始魔力消耗，按档位求值
 * @param notification            魔力不足 / 被禁用时的提示文案
 * @param canMoveWhileCasting     蓄力期间能否移动
 * @param sound                   音效组（本类型禁 {@code looping}）
 * @param animations              动画组（本类型禁 {@code looping}）
 */
// TODO:或许可以设置超过施法时长的蓄力时长上限
public record ChargedActivation(
        LevelBasedValue chargedDurationPerLevel,
        LevelBasedValue castTime,
        boolean canChargeExceedCastTime,
        Optional<LevelBasedValue> cooldown,
        Optional<LevelBasedValue> initialManaCost,
        Notification notification,
        boolean canMoveWhileCasting,
        Optional<Sound> sound,
        Optional<Animations> animations
) implements Activation {
    /** 档位 0 表示"蓄力不足，未达成任何档位"。 */
    public static final int NO_CHARGED_LEVEL = 0;

    /**
     * 总蓄力上限。必须为正 —— 否则蓄力过程无意义（第 1 刻就读条完成），
     * 因此在解析期直接拦下，而不是留到运行时。
     */
    private static final Codec<LevelBasedValue> CAST_TIME_CODEC = LevelBasedValue.CODEC.validate(value ->
            value.calculate(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) > 0
                    ? DataResult.success(value)
                    : DataResult.error(() -> "Charged activation requires a positive [cast_time] (it is the maximum charge duration)"));

    public static final MapCodec<ChargedActivation> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LevelBasedValue.CODEC.fieldOf("charged_duration_per_level").forGetter(ChargedActivation::chargedDurationPerLevel),
            CAST_TIME_CODEC.fieldOf("cast_time").forGetter(ChargedActivation::castTime),
            Codec.BOOL.optionalFieldOf("can_charge_exceed_cast_time", false).forGetter(ChargedActivation::canChargeExceedCastTime),
            LevelBasedValue.CODEC.optionalFieldOf("cooldown").forGetter(ChargedActivation::cooldown),
            LevelBasedValue.CODEC.optionalFieldOf("initial_mana_cost").forGetter(ChargedActivation::initialManaCost),
            Notification.CODEC.optionalFieldOf("notification", Notification.DEFAULT).forGetter(ChargedActivation::notification),
            Codec.BOOL.optionalFieldOf("can_move_while_casting", true).forGetter(ChargedActivation::canMoveWhileCasting),
            Sound.CODEC
                    .validate(sound -> sound.looping().isPresent() ? DataResult.error(() -> "Charged activation does not support [looping] sounds") : DataResult.success(sound))
                    .optionalFieldOf("sound").forGetter(ChargedActivation::sound),
            Animations.CODEC
                    .validate(animations -> animations.looping().isPresent() ? DataResult.error(() -> "Charged activation does not support [looping] animations") : DataResult.success(animations))
                    .optionalFieldOf("animations").forGetter(ChargedActivation::animations)
    ).apply(instance, ChargedActivation::new));

    @Override
    public int getCastTime(final int level) {
        return (int) castTime.calculate(level);
    }

    @Override
    public int getCooldown(final int level) {
        return cooldown.map(value -> (int) value.calculate(level))
                .orElseGet(() -> Activation.super.getCooldown(level));
    }

    @Override
    public float getInitialManaCost(final int level) {
        return initialManaCost.map(cost -> cost.calculate(level))
                .orElseGet(() -> Activation.super.getInitialManaCost(level));
    }

    @Override
    public Type type() {
        return Type.SIMPLE;
    }

    @Override
    public MapCodec<? extends Activation> codec() {
        return CODEC;
    }

    /**
     * 档位 {@code level} 所需的蓄力时长（tick）。
     * <p>
     * 若 {@code charged_duration_per_level} 在该档位超过蓄力时长上限 {@link #chargeCapTicks(int)}，
     * 则封顶为该上限 —— 这就是"最高等级所需蓄力时长不得超过 {@code cast_time}"的落地点
     * （上限为何是 {@code cast_time} 或 {@code cast_time - 1} 见 {@link #canChargeExceedCastTime()} 的说明）。
     *
     * @param level 档位，从 1（{@link DragonAbilityInstance#MIN_LEVEL_FOR_CALCULATIONS}）起算
     * @return 该档位所需的蓄力游戏刻数
     */
    public int getRequiredChargeTicks(final int level) {
        int required = Math.max(0, (int) chargedDurationPerLevel.calculate(level));
        return Math.min(required, chargeCapTicks(level));
    }

    /**
     * 蓄力时长的有效上限：超过它之后档位不再提升。
     * <p>
     * 不能超上限蓄力时前移 1 刻 —— DS 在 {@code currentTick == cast_time} 当刻就完成释放，
     * "仍在蓄力"的状态观察不到那一帧（详见类注释）。
     */
    private int chargeCapTicks(final int level) {
        int castTimeTicks = Math.max(1, getCastTime(level));
        return canChargeExceedCastTime ? castTimeTicks : Math.max(1, castTimeTicks - 1);
    }

    /**
     * 最低蓄力时长：档位 1 所需的蓄力时长，未达此值松手视为取消施法。
     *
     * @return 最少需要按住的游戏刻数
     */
    public int getMinimumChargeTicks() {
        return getRequiredChargeTicks(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS);
    }

    /**
     * 由已蓄力时长反查档位。
     *
     * @param chargeTicks 已蓄力的游戏刻数
     * @param playerLevel 玩家自身的技能升级等级，档位不会超过它
     * @return 达成的最高档位；未达最低蓄力时长或玩家等级为 0 时返回 {@link #NO_CHARGED_LEVEL}
     */
    public int getChargedLevel(final int chargeTicks, final int playerLevel) {
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
     * 便捷入口：技能实例的激活类型是蓄力档位时才换算档位，否则返回 {@link #NO_CHARGED_LEVEL}。
     * <p>
     * 客户端与服务端都要用同一套换算，故放在这里统一提供。
     *
     * @param instance    技能实例，等级取 {@link DragonAbilityInstance#level()}（即玩家真实升级等级）
     * @param chargeTicks 已蓄力的游戏刻数
     * @return 达成的最高档位；类型不匹配、未达最低蓄力时长或玩家等级为 0 时为 {@link #NO_CHARGED_LEVEL}
     */
    public static int getChargedLevelOf(final @NotNull DragonAbilityInstance instance, final int chargeTicks) {
        return instance.value().activation() instanceof ChargedActivation charged
                ? charged.getChargedLevel(chargeTicks, instance.level())
                : NO_CHARGED_LEVEL;
    }

    /**
     * 是否为蓄力档位类型（客户端 / 服务端共用的类型判定）。
     *
     * @param activation 待判定的激活类型
     * @return 该激活类型是否为本类
     */
    public static boolean isCharged(final @NotNull Activation activation) {
        return activation instanceof ChargedActivation;
    }
}
