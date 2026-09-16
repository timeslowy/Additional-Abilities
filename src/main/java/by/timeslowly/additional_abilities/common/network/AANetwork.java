package by.timeslowly.additional_abilities.common.network;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.NotNull;

/**
 * 本模组的网络通道注册。
 * <p>
 * 通道由 {@link ScreenVisionPayload#TYPE} 的 ResourceLocation 决定
 * （{@code additional_abilities:screen_vision}），与 DS 的通道互不干扰。
 */
public class AANetwork {
    /**
     * 协议版本。两端版本不一致时 NeoForge 会拒绝连接，
     * 因此改动 payload 字段结构、或增删 payload 种类时必须同步递增。
     */
    private static final String PROTOCOL_VERSION = "2";

    @SubscribeEvent
    public static void register(final @NotNull RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        // 屏幕视觉：仅服务端 → 客户端
        registrar.playToClient(ScreenVisionPayload.TYPE, ScreenVisionPayload.STREAM_CODEC, ScreenVisionPayload::handleClient);

        // 方块震动：仅服务端 → 客户端
        registrar.playToClient(BlockQuakePayload.TYPE, BlockQuakePayload.STREAM_CODEC, BlockQuakePayload::handleClient);
    }
}
