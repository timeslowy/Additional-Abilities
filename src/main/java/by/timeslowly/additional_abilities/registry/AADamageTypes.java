package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.Additional_abilities;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class AADamageTypes {
    public static final DeferredRegister<DamageType> DAMAGE_TYPES =
            DeferredRegister.create(Registries.DAMAGE_TYPE, Additional_abilities.MOD_ID);

    // 伤害类型注册名,已在数据包内注册，未来可调用（哎！没那么简单）
    public static final DeferredHolder<DamageType, DamageType> COUNTER_SHOCK =
            DAMAGE_TYPES.register("counter_shock", () -> new DamageType("additional_abilities.counter_shock", 0.1F));

    public static void register(IEventBus eventBus) {
        DAMAGE_TYPES.register(eventBus);
    }
}
