package by.timeslowly.additional_abilities.registry.dragon.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.AbilityBlockEffect;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.registry.dragon.ability.block_effects.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 向 DragonSurvival 的 {@code dragonsurvival:ability_block_effect} 静态注册表注册
 * 本模组的自定义方块效果类型。
 * <p>
 * 注册后即可在 dragon_ability JSON 的 {@code block_effect} 中通过
 * {@code "effect_type": "additional_abilities:<效果id>"} 使用。
 */
public class AAAbilityBlockEffects {
    public static void register(final @NotNull IEventBus modEventBus) {
        modEventBus.addListener(AAAbilityBlockEffects::registerEntries);
    }

    private static void registerEntries(final @NotNull RegisterEvent event) {
        // 方块震动：additional_abilities:block_quake
        event.register(AbilityBlockEffect.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "block_quake"),
                () -> BlockQuakeEffect.CODEC);

        // 方块熄灭：additional_abilities:extinguish
        event.register(AbilityBlockEffect.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "extinguish"),
                () -> ExtinguishEffect.CODEC);

        // 方块发光：additional_abilities:glow
        event.register(AbilityBlockEffect.REGISTRY_KEY,
                ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "glow"),
                () -> GlowEffect.CODEC);
    }
}
