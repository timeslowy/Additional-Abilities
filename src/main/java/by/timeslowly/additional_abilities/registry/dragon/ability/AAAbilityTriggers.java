package by.timeslowly.additional_abilities.registry.dragon.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.trigger.ActivationTrigger;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger.OnBlockPlaced;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger.OnItemConsumed;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 向 DragonSurvival 的 {@code dragonsurvival:activation_trigger} 静态注册表注册
 * 本模组的自定义被动触发类型（trigger type）。
 * <p>
 * 该注册表由 DS 自己用 {@code NewRegistryEvent} 创建
 * （{@code ActivationTrigger.REGISTRY_KEY} / {@code ActivationTrigger.REGISTRY}），
 * 本模组只往里追加条目，因此与 {@link AAAbilityEntityEffects} / {@link AAAbilityBlockEffects} /
 * {@link AAAbilityTargets} / {@link AAAbilityActivations} 完全同款：监听 {@link RegisterEvent} →
 * {@code event.register(注册表键, 资源位置, CODEC)}。
 * <p>
 * 无需额外守卫：NeoForge 的 {@code RegisterEvent#register} 内部仅在
 * {@code event.getRegistryKey().equals(registryKey)} 时才真正写入，
 * 同一监听器在其它注册表的事件里调用是空操作，不会重复注册。
 * <p>
 * 该注册表是**二级分派**结构的一环：外层 {@code dragonsurvival:activation} 先按
 * {@code activation_type} 分派到 {@code PassiveActivation}，其内部的 {@code trigger} 字段
 * 再按 {@code trigger_type} 分派到具体触发器。注册后即可在 dragon_ability JSON 中通过
 * {@code "trigger_type": "additional_abilities:<id>"} 使用（仅对 {@code activation_type: passive} 有效）。
 * <p>
 * <b>注意</b>：{@code RegisterEvent} 双端都会触发（客户端也要解析同步过来的技能 JSON），
 * 因此本注册类是双侧注册；而事件的订阅（接线）不在这里，见 {@link OnBlockPlaced#trigger} 的说明。
 */
public class AAAbilityTriggers {
    public static void register(final @NotNull IEventBus modEventBus) {
        modEventBus.addListener(AAAbilityTriggers::registerEntries);
    }

    private static void registerEntries(final @NotNull RegisterEvent event) {
        // 放置方块时：additional_abilities:on_block_placed
        // 字段与 dragonsurvival:on_block_break 相同（可选的 condition），仅事件源相反（放置而非破坏）
        event.register(ActivationTrigger.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "on_block_placed"),
                () -> OnBlockPlaced.CODEC);

        // 消耗物品时：additional_abilities:on_item_consumed
        // 字段对齐原版 minecraft:consume_item（可选的 item，ItemPredicate），事件接 LivingEntityUseItemEvent.Finish
        event.register(ActivationTrigger.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "on_item_consumed"),
                () -> OnItemConsumed.CODEC);
    }
}
