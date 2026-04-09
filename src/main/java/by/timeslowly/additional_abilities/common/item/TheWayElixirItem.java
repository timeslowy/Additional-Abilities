package by.timeslowly.additional_abilities.common.item;

import by.dragonsurvivalteam.dragonsurvival.DragonSurvival;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.DragonSpecies;
import by.timeslowly.additional_abilities.registry.AAItems;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

public class TheWayElixirItem extends Item {
    public TheWayElixirItem(Properties properties) {
        super(properties);

    }
    @Override
    public void inventoryTick(@NotNull ItemStack stack, @NotNull Level level, @NotNull Entity entity, int slotId, boolean isSelected) {
        if (entity instanceof Player player && player.getInventory().getItem(slotId) == stack) {
            Holder<DragonSpecies> species = DragonStateProvider.getData(player).species();
            if (species != null) {
                if (species.is(DragonSurvival.res("sea_dragon"))) {
                    if (!stack.is(AAItems.theWayElixir_SEA)) {
                        player.getInventory().setItem(slotId, new ItemStack(AAItems.theWayElixir_SEA));
                    }
                } else if (species.is(DragonSurvival.res("cave_dragon"))) {
                    if (!stack.is(AAItems.theWayElixir_CAVE)) {
                        player.getInventory().setItem(slotId, new ItemStack(AAItems.theWayElixir_CAVE));
                    }
                } else if (species.is(DragonSurvival.res("wing_kirin"))) {
                    if (!stack.is(AAItems.theWayElixir_WING_KIRIN)) {
                        player.getInventory().setItem(slotId, new ItemStack(AAItems.theWayElixir_WING_KIRIN));
                    }
                } else if (!stack.is(AAItems.theWayElixir_EMPTY)) {
                    player.getInventory().setItem(slotId, new ItemStack(AAItems.theWayElixir_EMPTY));
                    }
            } else if (!stack.is(AAItems.theWayElixir_EMPTY)) {
                player.getInventory().setItem(slotId, new ItemStack(AAItems.theWayElixir_EMPTY));
            }
        }

        super.inventoryTick(stack, level, entity, slotId, isSelected);
    }


}
