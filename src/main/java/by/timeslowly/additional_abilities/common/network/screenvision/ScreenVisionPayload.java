package by.timeslowly.additional_abilities.common.network.screenvision;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.state.ClientScreenVisionState;
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
 * <p>
 * {@code size} 与 {@code rgb} 是 {@code edge_light} 用的参数，<b>跟随所有类型一起下发</b>
 * （抖动/模糊的条目里恒为默认值、客户端不读）。加这两个字段只多 8 字节，而发送本身已被
 * 节流到「每玩家每类型 5 刻最多一次」，因此不值得为此另开一个 payload。
 *
 * @param visionType    视觉选项（对应 JSON 的 {@code type}）
 * @param amplifier     强度倍率，1.0 为基准；对 {@code edge_light} 即遮罩不透明度（&gt;1 由消费端按 1 处理）
 * @param size          遮罩边缘厚度，占屏幕<b>短边</b>的比例（0~0.5）；仅 {@code edge_light} 读取
 * @param rgb           遮罩颜色（{@code 0xRRGGBB}，无 alpha；alpha 由客户端的淡入淡出与强度算出）；仅 {@code edge_light} 读取
 * @param durationTicks 持续时长（游戏刻）
 */
public record ScreenVisionPayload(ScreenVisionType visionType, float amplifier, float size, int rgb, int durationTicks) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ScreenVisionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "screen_vision"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenVisionPayload> STREAM_CODEC = StreamCodec.composite(
            ScreenVisionType.STREAM_CODEC, ScreenVisionPayload::visionType,
            ByteBufCodecs.FLOAT, ScreenVisionPayload::amplifier,
            ByteBufCodecs.FLOAT, ScreenVisionPayload::size,
            ByteBufCodecs.VAR_INT, ScreenVisionPayload::rgb,
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
        context.enqueueWork(() -> ClientScreenVisionState.onReceive(payload.visionType(), payload.amplifier(),
                payload.size(), payload.rgb(), payload.durationTicks()));
    }
}
