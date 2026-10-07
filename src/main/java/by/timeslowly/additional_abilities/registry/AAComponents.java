package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus.EnchantmentBonus;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 本模组的物品数据组件（{@code DataComponentType}）注册。
 * <p>
 * 组件与附件（{@link AAAttachments}）的分工：附件挂在<b>实体 / 维度</b>上，
 * 组件挂在<b>物品栈</b>上、随物品一起同步与持久化。本类目前只有一个组件。
 */
public class AAComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(
            Registries.DATA_COMPONENT_TYPE, AdditionalAbilities.MOD_ID);

    /**
     * 临时附魔的<b>刷新脉冲</b>：由 {@code additional_abilities:enchantment_bonus} 实体效果逐刻写在
     * 「手持 / 身穿」的适用物品上，载荷是"该实体对这只物品的贡献指纹"（一个数值）。
     * <p>
     * 它<b>不携带任何可读的附魔信息</b>，也不参与结算或提示行（那两条走查询期反查，
     * 见 {@code EnchantmentBonusHandler}）；唯一用途是让原版 {@code collectEquipmentChanges}
     * 察觉"装备变了"、重收一次属性修饰符。因此即便残留，也不会误导任何人。
     * <p>
     * {@code persistent} 让它随物品进存档；{@code networkSynchronized} 让客户端也能看到变化
     * （客户端据此在自己的那份装备变更检测里重收修饰符）。
     * <p>
     * ⚠️ 线上格式一旦变化必须同步递增 {@code AANetwork} 的 {@code PROTOCOL_VERSION}；
     * 且<b>不要改组件 id</b> —— 原版遇到未注册的组件 id 会让物品栈解码失败
     * （见 {@link EnchantmentBonus#PULSE_CODEC}）。
     */
    public static final Supplier<DataComponentType<Long>> ENCHANTMENT_BONUS = COMPONENTS.register(
            "enchantment_bonus",
            () -> DataComponentType.<Long>builder()
                    .persistent(EnchantmentBonus.PULSE_CODEC)
                    .networkSynchronized(ByteBufCodecs.VAR_LONG)
                    .build());

    public static void register(final IEventBus eventBus) {
        COMPONENTS.register(eventBus);
    }
}
