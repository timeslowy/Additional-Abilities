package by.timeslowly.additional_abilities.common.network;

import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.client.ClientBlockQuakeState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 方块震动效果的服务端 → 客户端同步包。
 * <p>
 * <b>只传位置，不传方块数据</b>：本效果不修改世界，客户端自己就能读到那些位置的方块状态。
 * 这样既省带宽，也免去方块状态注册表 id 的编解码与两端一致性风险。
 *
 * @param positions     打包后的 {@link net.minecraft.core.BlockPos}（{@code asLong()}）
 * @param height        跃起高度（方块）
 * @param durationTicks 一次完整升落的时长（游戏刻）
 */
public record BlockQuakePayload(long[] positions, float height, int durationTicks) implements CustomPacketPayload {
    /** 单包位置数上限，服务端按此切分；解码侧据此拒绝异常包 */
    public static final int MAX_POSITIONS = 256;

    public static final CustomPacketPayload.Type<BlockQuakePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "block_quake"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockQuakePayload> STREAM_CODEC =
            StreamCodec.of(BlockQuakePayload::encode, BlockQuakePayload::decode);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(final @NotNull RegistryFriendlyByteBuf buffer, final @NotNull BlockQuakePayload payload) {
        long[] positions = payload.positions();
        buffer.writeVarInt(positions.length);

        for (long position : positions) {
            buffer.writeLong(position);
        }

        buffer.writeFloat(payload.height());
        buffer.writeVarInt(payload.durationTicks());
    }

    private static @NotNull BlockQuakePayload decode(final @NotNull RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();

        // 超限说明两端协议不一致，直接抛错断连比截断更安全（截断会留下未读字节导致后续包错位）
        if (count < 0 || count > MAX_POSITIONS) {
            throw new IllegalArgumentException("Invalid block quake position count: " + count);
        }

        long[] positions = new long[count];

        for (int index = 0; index < count; index++) {
            positions[index] = buffer.readLong();
        }

        return new BlockQuakePayload(positions, buffer.readFloat(), buffer.readVarInt());
    }

    /**
     * 客户端接收入口。逻辑在客户端专属的 {@link ClientBlockQuakeState} 里，
     * 方法体中的引用运行时才解析，专用服务端不会加载客户端类。
     */
    public static void handleClient(final @NotNull BlockQuakePayload payload, final @NotNull IPayloadContext context) {
        context.enqueueWork(() -> ClientBlockQuakeState.onReceive(payload));
    }
}
