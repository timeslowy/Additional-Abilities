package by.timeslowly.additional_abilities.common.network;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.state.ClientBlockGlowState;
import by.timeslowly.additional_abilities.common.ability.block_effects.GlowDisplayType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 方块发光的服务端 → 客户端同步包。
 * <p>
 * <b>只传位置与颜色，不传方块数据</b>（与 {@link BlockQuakePayload} 同一策略）：本效果不修改世界，
 * 客户端自己就能读到那些位置的方块状态。这样既省带宽，也免去方块状态注册表 id 的编解码与两端一致性风险
 * —— 唯一例外是 {@code simple_shader} 需要在客户端取 {@code BakedModel}，但那也是客户端从自己的世界读的。
 * <p>
 * <b>为什么每项各带剩余时长</b>：同一批里的条目建立时刻可能不同（尤其引导类技能持续刷新时），
 * 由一个整批共用的数值表达不了各自的到期时刻。剩余时长用 VarInt 与位置交错写出，开销可忽略。
 * 客户端据此本地倒计时，因此服务端<b>不需要额外的移除包</b>。
 *
 * @param positions      打包后的 {@link net.minecraft.core.BlockPos}（{@code asLong()}）
 * @param remainingTicks 与 {@code positions} 一一对应的剩余时长（游戏刻）
 * @param colorARGB      颜色，{@code FastColor.ARGB32} 形态（alpha 已补齐，见 GlowEffect）
 * @param displayType    显示方式（{@code outline} / {@code simple_shader}）
 */
public record BlockGlowPayload(long[] positions, int[] remainingTicks, int colorARGB, GlowDisplayType displayType)
        implements CustomPacketPayload {
    /** 单包位置数上限，服务端按此切分；解码侧据此拒绝异常包 */
    public static final int MAX_POSITIONS = 256;

    public static final CustomPacketPayload.Type<BlockGlowPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "block_glow"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockGlowPayload> STREAM_CODEC =
            StreamCodec.of(BlockGlowPayload::encode, BlockGlowPayload::decode);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(final @NotNull RegistryFriendlyByteBuf buffer, final @NotNull BlockGlowPayload payload) {
        long[] positions = payload.positions();
        int[] remaining = payload.remainingTicks();
        int count = positions.length;

        buffer.writeVarInt(count);

        for (int index = 0; index < count; index++) {
            buffer.writeLong(positions[index]);
            buffer.writeVarInt(remaining[index]);
        }

        buffer.writeInt(payload.colorARGB());
        GlowDisplayType.STREAM_CODEC.encode(buffer, payload.displayType());
    }

    private static @NotNull BlockGlowPayload decode(final @NotNull RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();

        // 超限说明两端协议不一致，直接抛错断连比截断更安全（截断会留下未读字节导致后续包错位）
        if (count < 0 || count > MAX_POSITIONS) {
            throw new IllegalArgumentException("Invalid block glow position count: " + count);
        }

        long[] positions = new long[count];
        int[] remaining = new int[count];

        for (int index = 0; index < count; index++) {
            positions[index] = buffer.readLong();
            remaining[index] = buffer.readVarInt();
        }

        return new BlockGlowPayload(positions, remaining, buffer.readInt(), GlowDisplayType.STREAM_CODEC.decode(buffer));
    }

    /**
     * 客户端接收入口。逻辑在客户端专属的 {@link ClientBlockGlowState} 里，
     * 方法体中的引用运行时才解析，专用服务端不会加载客户端类。
     */
    public static void handleClient(final @NotNull BlockGlowPayload payload, final @NotNull IPayloadContext context) {
        context.enqueueWork(() -> ClientBlockGlowState.onReceive(payload));
    }
}
