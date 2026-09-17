package by.timeslowly.additional_abilities.registry.dragon.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.AbilityTargeting;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.registry.dragon.ability.targeting.AntiDragonBreathTarget;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 向 DragonSurvival 的 {@code dragonsurvival:ability_targeting} 静态注册表注册
 * 本模组的自定义目标选择器类型（target type）。
 * <p>
 * 该注册表由 DS 自己用 {@code NewRegistryEvent} 创建
 * （{@code AbilityTargeting.REGISTRY_KEY} / {@code AbilityTargeting.REGISTRY}），
 * 本模组只往里追加条目，因此与 {@link AAAbilityEntityEffects} / {@link AAAbilityBlockEffects} /
 * {@link AAAbilityActivations} 完全同款：监听 {@link RegisterEvent} →
 * {@code event.register(注册表键, 资源位置, CODEC)}。
 * <p>
 * 无需额外守卫：NeoForge 的 {@code RegisterEvent#register} 内部仅在
 * {@code event.getRegistryKey().equals(registryKey)} 时才真正写入，
 * 同一监听器在其它注册表的事件里调用是空操作，不会重复注册。
 * <p>
 * 注册后即可在 dragon_ability JSON 的 {@code target_selection} 中通过
 * {@code "target_type": "additional_abilities:<id>"} 使用（与 effect_type 平级，另需
 * {@code applied_effects} 与各自独有字段）。
 */
public class AAAbilityTargets {
    public static void register(final @NotNull IEventBus modEventBus) {
        modEventBus.addListener(AAAbilityTargets::registerEntries);
    }

    private static void registerEntries(final @NotNull RegisterEvent event) {
        // 反向龙息锥形：additional_abilities:anti_dragon_breath
        // 字段与 dragonsurvival:dragon_breath 相同（applied_effects + range_multiplier），仅箱体方向相反
        event.register(AbilityTargeting.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "anti_dragon_breath"),
                () -> AntiDragonBreathTarget.CODEC);
    }
}
