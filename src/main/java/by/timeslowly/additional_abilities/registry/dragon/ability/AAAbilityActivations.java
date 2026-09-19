package by.timeslowly.additional_abilities.registry.dragon.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Activation;
import by.timeslowly.additional_abilities.Additional_abilities;
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
 */
public class AAAbilityActivations {
    public static void register(final @NotNull IEventBus modEventBus) {
        modEventBus.addListener(AAAbilityActivations::registerEntries);
    }

    private static void registerEntries(final @NotNull RegisterEvent event) {
        // 蓄力档位：additional_abilities:charged
        event.register(Activation.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "charged"),
                () -> ChargedActivation.CODEC);

        // 可选性蓄力档位：additional_abilities:optional_charged
        // 字段与 charged 完全一致，can_charge_exceed_cast_time 默认 true，
        // 并额外支持蓄力期间用鼠标滚轮指定释放档位（含取消）
        event.register(Activation.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "optional_charged"),
                () -> OptionalChargedActivation.CODEC);
    }
}
