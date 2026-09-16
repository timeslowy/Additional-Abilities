package by.timeslowly.additional_abilities.common.network;

import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.client.ClientScreenVisionState;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 屏幕视觉效果的服务端 → 客户端同步包。
 * <p>
 * 与 DS 自家的 {@code network.magic.SyncBlockVision} 同一套路：
 * 实体效果的 {@code apply} 只在服务端执行，屏幕类效果必须显式下发到目标玩家的客户端。
 *
 * @param visionType    视觉选项（对应 JSON 的 {@code type}）
 * @param amplifier     强度倍率，1.0 为基准
 * @param durationTicks 持续时长（游戏刻）
 */
public record ScreenVisionPayload(ScreenVisionType visionType, float amplifier, int durationTicks) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ScreenVisionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "screen_vision"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenVisionPayload> STREAM_CODEC = StreamCodec.composite(
            ScreenVisionType.STREAM_CODEC, ScreenVisionPayload::visionType,
            ByteBufCodecs.FLOAT, ScreenVisionPayload::amplifier,
            ByteBufCodecs.VAR_INT, ScreenVisionPayload::durationTicks,
            ScreenVisionPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端接收入口。
     * <p>
     * 这里只做转发，真正的逻辑在客户端专属的 {@link ClientScreenVisionState} 里
     * —— 方法体中的引用是运行时才解析的，所以专用服务端不会加载任何客户端类。
     * <p>
     * {@code enqueueWork} 保证状态写入发生在客户端主线程。
     */
    public static void handleClient(final @NotNull ScreenVisionPayload payload, final @NotNull IPayloadContext context) {
        context.enqueueWork(() -> ClientScreenVisionState.onReceive(payload.visionType(), payload.amplifier(), payload.durationTicks()));
    }
}
