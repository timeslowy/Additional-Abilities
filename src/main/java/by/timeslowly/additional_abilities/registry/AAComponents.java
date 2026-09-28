package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.entity_effects.StampedEnchantments;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
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
     * 临时附魔来源标记：由 {@code additional_abilities:enchantment_bonus} 实体效果逐刻写在
     * 「手持 / 身穿」的适用物品上，移除时清掉。
     * <p>
     * {@code persistent} 让它随物品进存档（这是刻意的：服务端重启后时长实例也能从
     * {@link AAAttachments#ENCHANTMENT_BONUSES} 恢复，标记因此仍然自洽）；
     * {@code networkSynchronized} 让客户端也能拿到标记 —— 客户端的用途是
     * <b>物品提示行</b>与客户端侧的预测性判定，真正的结算仍在服务端。
     * <p>
     * ⚠️ 一旦客户端缺这个组件类型，带标记的物品栈会解码失败。因此新增本组件时必须
     * 同步递增 {@code AANetwork} 的 {@code PROTOCOL_VERSION}，让版本不匹配时给出明确拒绝。
     */
    public static final Supplier<DataComponentType<StampedEnchantments>> ENCHANTMENT_BONUS = COMPONENTS.register(
            "enchantment_bonus",
            () -> DataComponentType.<StampedEnchantments>builder()
                    .persistent(StampedEnchantments.CODEC)
                    .networkSynchronized(StampedEnchantments.STREAM_CODEC)
                    .build());

    public static void register(final IEventBus eventBus) {
        COMPONENTS.register(eventBus);
    }
}
