package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.common.item.TheWayElixirItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class AAItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(
            Additional_abilities.MOD_ID
    );

    //
    public static final DeferredItem<Item> theWayElixir = ITEMS.register("the_way_elixir", TheWayElixirItem::new);

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
