package by.timeslowly.additional_abilities.registry.dragon.ability.activation;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Activation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Animations;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Notification;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Sound;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.enchantment.LevelBasedValue;

import java.util.Optional;

/**
 * 龙之技能激活类型：<b>可选性蓄力档位</b>（{@code additional_abilities:optional_charged}）。
 * <p>
 * 是 {@link ChargedActivation}（{@code additional_abilities:charged}）的改版，两者关系：
 * <ul>
 *     <li><b>字段结构完全一致</b>（同名、同类型、同必填性、同样禁 {@code looping} 音效与动画）；</li>
 *     <li><b>唯一默认值差异</b>：{@code can_charge_exceed_cast_time} 默认为 {@code true}
 *         —— 因为"松手前自由挑档"必须能按住不被自动释放，否则满档那一帧会由 DS 原生路径
 *         以最高档直接打出去，玩家没有介入机会；</li>
 *     <li><b>新增交互</b>：蓄力期间可用鼠标滚轮在 {@code [0, 当前已达成档位]} 内自由指定释放档位，
 *         上滚 +1、下滚 -1；{@code 0} 表示"蓄力后取消，不释放"。</li>
 * </ul>
 * 档位换算（阈值、上限、反查）与 {@code charged} 共用 {@link ChargeableActivation} 的默认实现，
 * 本类只声明差异所在。
 *
 * <h2>选档规则</h2>
 * <ul>
 *     <li><b>上限恒为"当前已达成档位"</b>：档位必须既能蓄到（{@code charged_duration_per_level}
 *         在该档的阈值不超过 {@link #chargeCapTicks(int)}），又已经实际蓄到该档所需时长，
 *         因此选择范围天然收敛为 {@code [0, 已达档位]}。未达最低蓄力时全域只有 {@code 0}，无可选。</li>
 *     <li><b>默认自动跟随</b>：玩家未滚动时，释放档位等于"已达档位"，表现与 {@code charged} 完全一致；
 *         一旦滚动即转为手动指定，直到玩家把档位滚回"已达档位"为止（此时恢复自动跟随）。</li>
 *     <li><b>手动指定不会锁死蓄力</b>：已达档位仍随蓄力继续上升，玩家可随时再把档位滚回去
 *         （见本类 §"与其他类型的一致性"）。</li>
 *     <li><b>{@code 0} = 取消</b>：不执行任何动作、不扣初始魔力、<b>不产生冷却</b>，
 *         并清理循环音效与其他玩家看到的技能动画。</li>
 * </ul>
 *
 * <h2>为什么 {@code can_charge_exceed_cast_time} 允许被显式关闭</h2>
 * 保持与 {@code charged} 的字段结构一致是本类型的设计约束，因此该字段保留可选性。
 * 但关闭它之后，DS 会在 {@code currentTick == cast_time} 当刻以玩家自身最高档自动释放，
 * <b>选档将失去介入机会</b>（只有 {@code [0, cast_time - 1]} 这段窗口可用）。
 * 需要选档能力时请保持其为默认值 {@code true}。
 * <p>
 * 同理，{@code max_overcharged_duration} 会把"选档窗口"截断在 {@code cast_time + 该值} 那一刻：
 * 窗口到点由 DS 原生流程自动释放，且用的是<b>玩家自身的最高档</b> ——
 * 此时滚轮选定的低档（乃至"选定 0 = 取消"）都不再生效。
 * 若希望玩家始终有机会按自己的选定释放，就不要配置该字段（不配置 = 无限按住，只有松手才释放）。
 *
 * <h2>与其他类型的一致性</h2>
 * 本类型的"已达档位"始终独立于"选定档位"存在：例如已蓄到 5 档、滚轮下调到 3 档后继续按住，
 * 已达档位仍会停在自身最高档（5），玩家可再上滚回到 4、5。这是"此档位必须当前已蓄到"
 * 这条约束的自然结果 —— 约束检查的是"已达 ≥ 选定"，而不是"锁定蓄力进度"。
 *
 * <h2>JSON 字段</h2>
 * 与 {@link ChargedActivation} 完全相同（见其类注释的字段表），仅 {@code can_charge_exceed_cast_time}
 * 的默认值不同：
 * <pre>
 * "activation": {
 *   "activation_type": "additional_abilities:optional_charged",
 *   "charged_duration_per_level": { "type": "minecraft:linear", "base": 10.0, "per_level_above_first": 10.0 },
 *   "cast_time": 60,
 *   "can_charge_exceed_cast_time": true,   // 可省略，默认即 true
 *   "max_overcharged_duration": 20,        // 可省略：超限窗口长度（tick）；不填则无限按住
 *   "cooldown": 60,
 *   "initial_mana_cost": 1,
 *   "can_move_while_casting": false,
 *   "sound": { "charging": …, "end": … },
 *   "animations": { "start_and_charging": …, "end": … }
 * }
 * </pre>
 *
 * <h2>数值平衡提示</h2>
 * 冷却与初始魔力消耗同样按"最终释放的档位"结算，因此低档释放天然更便宜 —— 这是设计意图。
 * 但若把 {@code cooldown} / {@code initial_mana_cost} 配成随档位<b>递减</b>，
 * 会出现"刷低档反而更划算"的收益倒挂，建议这两项保持随档位非递减。
 *
 * @param chargedDurationPerLevel 各档位所需的蓄力时长（tick），对应 JSON {@code charged_duration_per_level}
 * @param castTime                总蓄力上限（tick），对应 {@code cast_time}；必填且必须为正
 * @param canChargeExceedCastTime 是否允许蓄力按住超过上限而不释放，对应
 *                                {@code can_charge_exceed_cast_time}（本类型默认 {@code true}）；
 *                                开启后由 {@code mixins.DragonAbilityInstanceMixin} 撑开 DS 的完成判定
 * @param maxOverchargedDuration  超限窗口长度（tick），对应 {@code max_overcharged_duration}；
 *                                仅当 {@code canChargeExceedCastTime} 为 {@code true} 时可配置，
 *                                未配置表示无限按住，配置后窗口到点会以玩家最高档自动释放
 * @param cooldown                冷却时间（tick），按最终释放档位求值
 * @param initialManaCost         初始魔力消耗，按最终释放档位求值
 * @param notification            魔力不足 / 被禁用时的提示文案
 * @param canMoveWhileCasting     蓄力期间能否移动
 * @param sound                   音效组（本类型禁 {@code looping}）
 * @param animations              动画组（本类型禁 {@code looping}）
 */
public record OptionalChargedActivation(
        LevelBasedValue chargedDurationPerLevel,
        LevelBasedValue castTime,
        boolean canChargeExceedCastTime,
        Optional<LevelBasedValue> maxOverchargedDuration,
        Optional<LevelBasedValue> cooldown,
        Optional<LevelBasedValue> initialManaCost,
        Notification notification,
        boolean canMoveWhileCasting,
        Optional<Sound> sound,
        Optional<Animations> animations
) implements ChargeableActivation {
    public static final MapCodec<OptionalChargedActivation> CODEC = createCodec();

    /**
     * 基础 codec + 跨字段校验（{@code max_overcharged_duration} 以 {@code can_charge_exceed_cast_time} 为前提）。
     * <p>
     * 刻意先落一个局部变量再调 {@code validate}，而不是直接在链式调用末尾追加：
     * 接收者本身是个泛型方法调用（{@code RecordCodecBuilder.mapCodec(...)}）时，
     * javac 无法把外层的目标类型传进去，会把接收者推断成 {@code MapCodec<Object>} 而编译失败。
     */
    private static MapCodec<OptionalChargedActivation> createCodec() {
        MapCodec<OptionalChargedActivation> base = RecordCodecBuilder.mapCodec(instance -> instance.group(
                LevelBasedValue.CODEC.fieldOf("charged_duration_per_level").forGetter(OptionalChargedActivation::chargedDurationPerLevel),
                ChargeableActivation.CAST_TIME_CODEC.fieldOf("cast_time").forGetter(OptionalChargedActivation::castTime),
                // 与 charged 的唯一差异：默认 true —— 否则满档那一帧会被 DS 原生路径自动释放，玩家来不及选档
                Codec.BOOL.optionalFieldOf("can_charge_exceed_cast_time", true).forGetter(OptionalChargedActivation::canChargeExceedCastTime),
                // 缺省 = 无限按住（选档窗口不受截断）
                ChargeableActivation.MAX_OVERCHARGED_DURATION_CODEC.optionalFieldOf("max_overcharged_duration").forGetter(OptionalChargedActivation::maxOverchargedDuration),
                LevelBasedValue.CODEC.optionalFieldOf("cooldown").forGetter(OptionalChargedActivation::cooldown),
                LevelBasedValue.CODEC.optionalFieldOf("initial_mana_cost").forGetter(OptionalChargedActivation::initialManaCost),
                Notification.CODEC.optionalFieldOf("notification", Notification.DEFAULT).forGetter(OptionalChargedActivation::notification),
                Codec.BOOL.optionalFieldOf("can_move_while_casting", true).forGetter(OptionalChargedActivation::canMoveWhileCasting),
                ChargeableActivation.SOUND_CODEC.optionalFieldOf("sound").forGetter(OptionalChargedActivation::sound),
                ChargeableActivation.ANIMATIONS_CODEC.optionalFieldOf("animations").forGetter(OptionalChargedActivation::animations)
        ).apply(instance, OptionalChargedActivation::new));

        return base.validate(ChargeableActivation::validateOverchargeSupport);
    }

    /**
     * 本类型在松手时使用玩家指定的档位，而不是"已达档位"。
     * <p>
     * 该判定同时驱动客户端收尾（读取滚轮选档状态）与服务端结算（校验并采用上报的档位）。
     */
    @Override
    public boolean selectsReleaseLevel() {
        return true;
    }

    @Override
    public Type type() {
        return Type.SIMPLE;
    }

    @Override
    public MapCodec<? extends Activation> codec() {
        return CODEC;
    }
}
