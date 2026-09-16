package by.timeslowly.additional_abilities.registry.dragon.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.registry.dragon.ability.entity_effects.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 向 DragonSurvival 的 ability_entity_effect 静态注册表注册本模组的自定义实体效果类型。
 * <p>
 * 注册后即可在 dragon_ability JSON 中通过
 * {@code "effect_type": "additional_abilities:<效果id>"} 使用。
 */
public class AAAbilityEntityEffects {
    public static void register(final @NotNull IEventBus modEventBus) {
        modEventBus.addListener(AAAbilityEntityEffects::registerEntries);
    }

    private static void registerEntries(final @NotNull RegisterEvent event) {
        // 伤害反震：additional_abilities:damage_reflection
        event.register(AbilityEntityEffect.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "damage_reflection"),
                () -> DamageReflectionEffect.CODEC);

        // 百分比伤害：additional_abilities:percentaged_damage
        event.register(AbilityEntityEffect.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "percentaged_damage"),
                () -> PercentagedDamageEffect.CODEC);
    }
}
