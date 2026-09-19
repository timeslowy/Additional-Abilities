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
     * 因此改动 payload 字段结构、增删 payload 种类、或改动随包传输的
     * 枚举序列名取值时，必须同步递增。
     * <p>
     * 6 → 7：{@link ChargedReleasePayload} 增加 {@code releaseLevel} 字段
     * （支撑 {@code additional_abilities:optional_charged} 的滚轮选档与取消）。
     */
    private static final String PROTOCOL_VERSION = "7";

    @SubscribeEvent
    public static void register(final @NotNull RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        // 屏幕视觉：仅服务端 → 客户端
        registrar.playToClient(ScreenVisionPayload.TYPE, ScreenVisionPayload.STREAM_CODEC, ScreenVisionPayload::handleClient);

        // 屏幕视觉的立即清空（无字段）：仅服务端 → 客户端
        registrar.playToClient(ScreenVisionClearPayload.TYPE, ScreenVisionClearPayload.STREAM_CODEC, ScreenVisionClearPayload::handleClient);

        // 方块震动：仅服务端 → 客户端
        registrar.playToClient(BlockQuakePayload.TYPE, BlockQuakePayload.STREAM_CODEC, BlockQuakePayload::handleClient);

        // 蓄力释放：仅客户端 → 服务端
        registrar.playToServer(ChargedReleasePayload.TYPE, ChargedReleasePayload.STREAM_CODEC, ChargedReleasePayload::handleServer);

        // 可选性蓄力的滚轮选档同步：仅客户端 → 服务端（只为查询指令可见性，不参与结算）
        registrar.playToServer(OptionalChargedSelectionPayload.TYPE, OptionalChargedSelectionPayload.STREAM_CODEC,
                OptionalChargedSelectionPayload::handleServer);
    }
}
