package by.timeslowly.additional_abilities.client.eventhandler;

import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.client.ClientBlockQuakeState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 方块震动的客户端推进（<b>仅客户端加载</b>）。
 * <p>
 * 假身位置每刻更新一次，渲染时的位置插值由原版实体负责，因此不需要监听渲染事件。
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID, value = Dist.CLIENT)
public class BlockQuakeEventHandler {
    @SubscribeEvent
    public static void onClientTick(final @NotNull ClientTickEvent.Post event) {
        ClientBlockQuakeState.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(final @NotNull ClientPlayerNetworkEvent.LoggingOut event) {
        ClientBlockQuakeState.clear();
    }
}
