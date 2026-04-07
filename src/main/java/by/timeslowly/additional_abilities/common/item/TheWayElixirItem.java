package by.timeslowly.additional_abilities.common.item;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.DragonAbilityHolder;
import by.dragonsurvivalteam.dragonsurvival.registry.data_components.DSDataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import java.util.List;
import java.util.Optional;

public class TheWayElixirItem extends Item {
    public TheWayElixirItem() {
        super(new Item.Properties()
                .rarity(Rarity.EPIC)
                .stacksTo(1)
                .component(
                        DSDataComponents.DRAGON_ABILITIES,
                        new DragonAbilityHolder(
                                List.of(
                                        new DragonAbilityHolder.AbilityPair(
                                                List.of(
                                                        "additional_abilities:outfire_breath"
                                                ),List.of(),false
                                        )
                                ),
                                Optional.empty(),
                                List.of("dragonsurvival:sea_dragon")
                        )
                )
        );
    }
}
