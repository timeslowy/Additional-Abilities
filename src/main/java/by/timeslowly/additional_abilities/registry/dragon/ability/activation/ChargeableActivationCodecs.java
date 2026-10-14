package by.timeslowly.additional_abilities.registry.dragon.ability.activation;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Animations;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Notification;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Sound;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.item.enchantment.LevelBasedValue;

import java.util.Optional;

/**
 * {@link ChargedActivation} 与 {@link OptionalChargedActivation} 共用的 <b>JSON 字段片段</b>。
 *
 * <h2>为什么值得抽出来</h2>
 * 两个类型的类注释都承诺「字段结构完全一致（同名、同类型、同必填性）」，但字段名、默认值与
 * 逐字段校验此前各写了一份。于是任何一方单独改字段名或默认值，都会让两端 schema <b>静默漂移</b>
 * —— 数据包作者按手册写 {@code cooldown}，换一个 {@code activation_type} 就解析失败或用了别的默认值，
 * 而两边编译都不报错。集中到一处后，"结构一致"由代码保证，而不是靠注释提醒。
 *
 * <h2>刻意不做什么</h2>
 * <b>不建立十参数的泛型构造器框架。</b>本类只共享「逐字段的片段」，
 * 两个 record 的 {@code instance.group(...)} 与构造签名仍然完整写在各自文件里 ——
 * 这样从任一文件都能一眼读出它接受的完整字段表，而共享的只是那些重复的常量。
 * （刻意保留两个 record 与既有注册 ID，不建立复杂十参数泛型框架。）
 *
 * <h2>唯一的真实差异</h2>
 * {@code can_charge_exceed_cast_time} 的默认值：{@code charged} 为 {@code false}、
 * {@code optional_charged} 为 {@code true}。这是两个类型<b>唯一</b>的 schema 差异，
 * 因此不给它做片段，两边各自写一行 —— 把差异留在明面上，比藏进一个布尔参数更清楚。
 *
 * <h2>包级可见</h2>
 * 本类与这些常量都是<b>包级私有</b>（不加 {@code public}）：它们是实现细节，不属于对外契约。
 * 放进接口会因接口字段隐式 {@code public} 而把内部片段暴露成 API。
 */
final class ChargeableActivationCodecs {
    /** {@code charged_duration_per_level}：必填，各档位所需的蓄力时长（tick）。 */
    static final MapCodec<LevelBasedValue> CHARGED_DURATION_PER_LEVEL =
            LevelBasedValue.CODEC.fieldOf("charged_duration_per_level");

    /** {@code cast_time}：必填，总蓄力上限（tick）。经 {@link ChargeableActivation#CAST_TIME_CODEC} 校验"折算成整数刻后 ≥ 1"。 */
    static final MapCodec<LevelBasedValue> CAST_TIME =
            ChargeableActivation.CAST_TIME_CODEC.fieldOf("cast_time");

    /**
     * {@code max_overcharged_duration}：可选，超限窗口长度（tick）。
     * <p>
     * 缺省（{@code Optional.empty()}）= 无限按住，即保持该字段引入之前的原有行为。
     * 经 {@link ChargeableActivation#MAX_OVERCHARGED_DURATION_CODEC} 校验。
     */
    static final MapCodec<Optional<LevelBasedValue>> MAX_OVERCHARGED_DURATION =
            ChargeableActivation.MAX_OVERCHARGED_DURATION_CODEC.optionalFieldOf("max_overcharged_duration");

    /** {@code cooldown}：可选，按档位求值。 */
    static final MapCodec<Optional<LevelBasedValue>> COOLDOWN = LevelBasedValue.CODEC.optionalFieldOf("cooldown");

    /** {@code initial_mana_cost}：可选，按档位求值。 */
    static final MapCodec<Optional<LevelBasedValue>> INITIAL_MANA_COST =
            LevelBasedValue.CODEC.optionalFieldOf("initial_mana_cost");

    /** {@code notification}：可选，缺省为 {@link Notification#DEFAULT}。 */
    static final MapCodec<Notification> NOTIFICATION =
            Notification.CODEC.optionalFieldOf("notification", Notification.DEFAULT);

    /** {@code can_move_while_casting}：可选，缺省 {@code true}。 */
    static final MapCodec<Boolean> CAN_MOVE_WHILE_CASTING = Codec.BOOL.optionalFieldOf("can_move_while_casting", true);

    /** {@code sound}：可选，已经过 {@link ChargeableActivation#SOUND_CODEC} 的禁 {@code looping} 校验。 */
    static final MapCodec<Optional<Sound>> SOUND = ChargeableActivation.SOUND_CODEC.optionalFieldOf("sound");

    /** {@code animations}：可选，已经过 {@link ChargeableActivation#ANIMATIONS_CODEC} 的禁 {@code looping} 校验。 */
    static final MapCodec<Optional<Animations>> ANIMATIONS =
            ChargeableActivation.ANIMATIONS_CODEC.optionalFieldOf("animations");

    private ChargeableActivationCodecs() {
        // 常量持有类
    }
}
