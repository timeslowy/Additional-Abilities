package by.timeslowly.additional_abilities.common.network;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.ClientScreenVisionState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 屏幕视觉的「立即清空」包（服务端 → 客户端，<b>无字段</b>）。
 * <p>
 * 视觉状态只存在于客户端（{@link ClientScreenVisionState}），服务端没有可清的东西，
 * 因此调试指令 {@code /additional-abilities simple-screen-vision clear <targets>} 只能通过本包让目标玩家自行清空。
 * <p>
 * 注意：被动技能会在下一拍重新下发，清空只对"当前这一刻"有效 —— 要让效果彻底不再出现得停用技能。
 */
public record ScreenVisionClearPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ScreenVisionClearPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "screen_vision_clear"));

    /** 无字段包：用 {@code StreamCodec.unit} 编解码 */
    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenVisionClearPayload> STREAM_CODEC =
            StreamCodec.unit(new ScreenVisionClearPayload());

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端接收入口。
     * <p>
     * 与 {@link ScreenVisionPayload#handleClient} 同一套路：这里只做转发，
     * 真正逻辑在客户端专属类里，因此专用服务端不会加载任何客户端类型。
     */
    public static void handleClient(final @NotNull ScreenVisionClearPayload payload, final @NotNull IPayloadContext context) {
        context.enqueueWork(ClientScreenVisionState::clear);
    }
}
