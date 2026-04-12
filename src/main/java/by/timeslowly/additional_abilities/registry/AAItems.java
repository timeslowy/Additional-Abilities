package by.timeslowly.additional_abilities.registry;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.DragonAbilityHolder;
import by.dragonsurvivalteam.dragonsurvival.registry.data_components.DSDataComponents;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.common.item.TheWayElixirItem;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Optional;

public class AAItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(
            Additional_abilities.MOD_ID
    );


    public static final Holder<Item> theWayElixir_SEA = ITEMS.register("the_way_elixir_sea", () -> new TheWayElixirItem(
            new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
                    .component(
                            DSDataComponents.DRAGON_ABILITIES,
                            new DragonAbilityHolder(
                                    List.of(
                                            new DragonAbilityHolder.AbilityPair(
                                                    List.of(
                                                            "additional_abilities:outfire_breath"
                                                    ), List.of(), false
                                            )
                                    ),
                                    Optional.empty(),
                                    List.of("dragonsurvival:sea_dragon")
                            )
                    )
    ));

    public static final Holder<Item> theWayElixir_CAVE = ITEMS.register("the_way_elixir_cave", () -> new TheWayElixirItem(
            new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
                    .component(
                            DSDataComponents.DRAGON_ABILITIES,
                            new DragonAbilityHolder(
                                    List.of(
                                            new DragonAbilityHolder.AbilityPair(
                                                    List.of(
                                                            "additional_abilities:smoke_breath",
                                                            "additional_abilities:piercing_eye"
                                                    ), List.of(), false
                                            )
                                    ),
                                    Optional.empty(),
                                    List.of("dragonsurvival:cave_dragon")
                            )
                    )
    ));

    public static final Holder<Item> theWayElixir_WING_KIRIN = ITEMS.register("the_way_elixir_wing_kirin", () -> new TheWayElixirItem(
            new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
                    .component(
                            DSDataComponents.DRAGON_ABILITIES,
                            new DragonAbilityHolder(
                                    List.of(
                                            new DragonAbilityHolder.AbilityPair(
                                                    List.of(
                                                            "additional_abilities:explosion_arrow",
                                                            "additional_abilities:entity_marker"
                                                    ), List.of(), false
                                            )
                                    ),
                                    Optional.empty(),
                                    List.of("dragonsurvival:wing_kirin")
                            )
                    )
    ));

    public static final Holder<Item> theWayElixir_EMPTY = ITEMS.register("the_way_elixir_empty", () -> new TheWayElixirItem(
            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)
    ));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
