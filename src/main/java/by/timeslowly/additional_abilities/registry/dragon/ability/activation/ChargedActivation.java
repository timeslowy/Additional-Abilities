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
 * <h2>等级传递</h2>
 * <b>技能内所有输出（伤害、弹射物、方块效果、概率）的等级都只读
 * {@link DragonAbilityInstance#level()}</b>，因此"按档位释放"的落地手法是：在服务端执行动作前
 * 临时 {@code setLevel(档位)}、执行完毕后还原。详见
 * {@code by.timeslowly.additional_abilities.common.ability.ChargedCasts#fire}。
 *
 * <h2>JSON 字段（与 {@code activation_type} 平级）</h2>
 * <pre>
 * "charged_duration_per_level": { "type": "minecraft:linear", "base": 10.0, "per_level_above_first": 10.0 } // 必填，各档位所需蓄力时长（tick）
 * "cast_time":                  { "type": "minecraft:linear", "base": 60.0, "per_level_above_first": 0.0  } // 必填，总蓄力上限（tick），必须为正
 * "cooldown":                   200.0                                                                    // 可选，默认 0
 * "initial_mana_cost":          2.0                                                                      // 可选，默认 0
 * "notification":               { "not_enough_mana": …, "usage_blocked": … }                             // 可选
 * "can_move_while_casting":     false                                                                    // 可选，默认 true
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
 * 档位 {@code L} 所需时长 = {@code min(charged_duration_per_level(L), cast_time(L))}
 * —— 即"最高等级所需蓄力时长不得超过总施法时间"的落地位置：超限时封顶为 {@code cast_time}，
 * 于是蓄满（{@code cast_time}）恰好能拿到玩家自身等级对应的档位，与
 * {@code DragonSurvival} 原生"蓄满自动释放"所用的等级自然对齐。
 * <p>
 * 因为 {@link LevelBasedValue} 以 1 为最低计算等级（0 会被 {@code lookup} 类型读成越界），
 * 本类所有涉及等级的换算都从 1 起算，等级 0 一律视为"技能未解锁 / 无效"。
 */
// TODO：添加是否可以蓄力按住超过上限而不释放布尔值（can_charge_exceed_cast_time）（需要mixin）
public record ChargedActivation(
        LevelBasedValue chargedDurationPerLevel,
        LevelBasedValue castTime,
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
     * 若 {@code charged_duration_per_level} 在该档位超过总施法时间 {@link #getCastTime(int)}，
     * 则封顶为总施法时间 —— 这就是"最高等级所需蓄力时长不得超过 {@code cast_time}"的落地点。
     */
    public int getRequiredChargeTicks(final int level) {
        int required = Math.max(0, (int) chargedDurationPerLevel.calculate(level));
        int limit = getCastTime(level);
        return limit > 0 ? Math.min(required, limit) : required;
    }

    /** 最低蓄力时长：档位 1 所需的蓄力时长，未达此值松手视为取消施法。 */
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
     */
    public static int getChargedLevelOf(final DragonAbilityInstance instance, final int chargeTicks) {
        return instance.value().activation() instanceof ChargedActivation charged
                ? charged.getChargedLevel(chargeTicks, instance.level())
                : NO_CHARGED_LEVEL;
    }

    /** 是否为蓄力档位类型（客户端/服务端共用的类型判定）。 */
    public static boolean isCharged(final @NotNull Activation activation) {
        return activation instanceof ChargedActivation;
    }
}
