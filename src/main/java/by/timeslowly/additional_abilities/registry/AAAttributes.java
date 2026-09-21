package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.PercentageAttribute;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.NotNull;

/**
 * 本模组的自定义属性（{@code Registries.ATTRIBUTE}）。
 *
 * <h2>{@code dragon_breath_restriction}（龙息范围收束）</h2>
 * 百分比属性，范围 {@code [0, 0.8]}`，默认 0。语义为「截面收窄比例」：
 * 数值 {@code r} 越大，龙息目标选择箱的截面按 {@code w = 1 - r} 收窄，
 * 同时沿视线方向按 {@code k = 1 / w} 拉长 —— 形状由「宽而短的锥形」变为
 * 「细而长的光束」（霰弹枪收束器的效果，见
 * {@code by.timeslowly.additional_abilities.mixins.DragonBreathTargetMixin}）。
 * <p>
 * 实际作用点不在属性本身，而在 {@code DragonBreathTarget#calculateBreathArea}
 * 的返回值上：DS 的所有龙息类技能（各色龙息、吐息）共用这一个箱体算式，
 * 因此戴上本属性后**所有**使用 {@code dragonsurvival:dragon_breath}
 * 目标选择器的技能都会一并改变形状。
 *
 * <h2>为什么用 {@link PercentageAttribute} 而不是 {@code RangedAttribute}</h2>
 * {@code PercentageAttribute} 只是把显示值乘以 {@code scaleFactor}（默认 100），
 * 即 0.8 显示为 {@code 80%}，**不改变数值语义**（取值、钳制、修饰符运算全部照旧）。
 * 这与本属性的「比例」定位相符。
 *
 * <h2>为什么必须 {@code setSyncable(true)}</h2>
 * {@code Attribute#syncable} 的默认值是 {@code false}（构造函数不设置该字段），
 * 不同步到客户端的后果是：客户端 {@code ClientDragonRenderer#renderAbilityHitbox}
 * （显示选择箱的调试框）按属性默认值 0 计算，于是框体与服务端实际选中的目标不一致。
 * DS 自身的全部属性也都调用了 {@code setSyncable(true)}。
 *
 * <h2>仅挂在玩家身上</h2>
 * 通过 {@link EntityAttributeModificationEvent} 只对 {@code EntityType.PLAYER} 注册
 * （DS 的龙息技能本就以玩家为施法者）。其它生物读取该属性会得到默认值 0，
 * 即完全不改变行为。
 */
public class AAAttributes {
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(Registries.ATTRIBUTE, AdditionalAbilities.MOD_ID);

    /**
     * 龙息范围收束：{@code additional_abilities:dragon_breath_restriction}。
     * 默认 0、最小 0、最大 0.8（= 截面收窄至 20%、前伸拉长至 5 倍）。
     */
    public static final DeferredHolder<Attribute, Attribute> DRAGON_BREATH_RESTRICTION =
            ATTRIBUTES.register("dragon_breath_restriction", () -> new PercentageAttribute(
                    "attribute." + AdditionalAbilities.MOD_ID + ".dragon_breath_restriction",
                    0.0, 0.0, 0.8).setSyncable(true));

    public static void register(final @NotNull IEventBus modEventBus) {
        ATTRIBUTES.register(modEventBus);
        // 属性挂载事件：EntityAttributeModificationEvent 在 mod 事件总线上派发
        modEventBus.addListener(AAAttributes::attachAttributes);
    }

    private static void attachAttributes(final @NotNull EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, DRAGON_BREATH_RESTRICTION);
    }
}
