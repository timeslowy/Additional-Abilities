package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.Additional_abilities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public class AAItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(
            Additional_abilities.MOD_ID
    );

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
