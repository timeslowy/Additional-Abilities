package by.timeslowly.additional_abilities.registry.dragon.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Activation;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargedActivation;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.OptionalChargedActivation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 向 DragonSurvival 的 activation 静态注册表注册本模组的自定义激活类型。
 * <p>
 * 激活类型注册表由 DS 自己用 {@code NewRegistryEvent} 创建
 * （{@code Activation.REGISTRY_KEY} / {@code Activation.REGISTRY}），本模组只往里追加条目，
 * 因此这里与 {@link AAAbilityEntityEffects} / {@link AAAbilityBlockEffects} 完全同款：
 * 监听 {@link RegisterEvent} → {@code event.register(注册表键, 资源位置, CODEC)}。
 * <p>
 * 注册后即可在 dragon_ability JSON 中通过 {@code "activation_type": …} 使用：
 * <ul>
 *     <li>{@code additional_abilities:charged} —— 蓄力档位，松手按已达成档位释放；</li>
 *     <li>{@code additional_abilities:optional_charged} —— 可选性蓄力档位，蓄力期间用鼠标滚轮指定释放档位
 *         （可为 0 = 取消）。两者共用 {@code activation.ChargeableActivation} 的档位换算。</li>
 * </ul>
 *
 * <h2>激活类型的界面显示</h2>
 * 龙生本体只对<b>被动</b>技能显示「触发条件」，从不显示 {@code activation.activation_type} 本身，
 * 于是 {@code charged} 与 {@code optional_charged} 在界面上都只是「主动能力」，玩家看不出区别。
 * 因此本模组用 {@link by.timeslowly.additional_abilities.mixins.DragonAbilityInfoMixin}
 * 往技能信息面板的<b>首行</b>补一行「激活类型」。
 * <p>
 * 该行对<b>全部</b>激活类型通用（龙生内置三种 + 上方两种 + 未来任何附属模组注册的类型），
 * 因为类型名是从 {@code Activation.REGISTRY} 按其自身 ID 反查的；
 * 译名键为 {@code activation_type.<命名空间>.<路径>}，缺译名时退回显示原始 ID。
 */
public class AAAbilityActivations {
    public static void register(final @NotNull IEventBus modEventBus) {
        modEventBus.addListener(AAAbilityActivations::registerEntries);
    }

    private static void registerEntries(final @NotNull RegisterEvent event) {
        // 蓄力档位：additional_abilities:charged
        event.register(Activation.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "charged"),
                () -> ChargedActivation.CODEC);

        // 可选性蓄力档位：additional_abilities:optional_charged
        // 字段与 charged 完全一致，can_charge_exceed_cast_time 默认 true，
        // 并额外支持蓄力期间用鼠标滚轮指定释放档位（含取消）
        event.register(Activation.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "optional_charged"),
                () -> OptionalChargedActivation.CODEC);
    }
}
