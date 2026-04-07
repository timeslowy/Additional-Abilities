package by.timeslowly.additional_abilities;

import by.timeslowly.additional_abilities.registry.AACreativeTabs;
import by.timeslowly.additional_abilities.registry.AAItems;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Additional_abilities.MOD_ID)
public class Additional_abilities {
    // Define mod id in a common place for everything to reference
    public static final String MOD_ID = "additional_abilities";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();


    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Additional_abilities(IEventBus modEventBus, @NotNull ModContainer modContainer) {
        // Register the commonSetup method for modloading
        AAItems.register(modEventBus);
        AACreativeTabs.register(modEventBus);


    }

}
