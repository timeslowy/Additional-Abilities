package by.timeslowly.additional_abilities.registry;

import by.dragonsurvivalteam.dragonsurvival.registry.DSCreativeTabs;
import by.timeslowly.additional_abilities.Additional_abilities;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.NotNull;

@EventBusSubscriber(modid = Additional_abilities.MOD_ID)
public class AACreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TAB =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Additional_abilities.MOD_ID);

    @SubscribeEvent
    public static void addItems(@NotNull BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == DSCreativeTabs.DS_TAB.getKey()) {
            event.accept(AAItems.theWayElixir_EMPTY::value);
        }
    }
    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TAB.register(eventBus);
    }
}
