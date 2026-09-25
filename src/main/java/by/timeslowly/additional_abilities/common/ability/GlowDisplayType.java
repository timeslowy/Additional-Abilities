package by.timeslowly.additional_abilities.common.ability;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

/**
 * 方块发光的显示方式，与效果 JSON 的 {@code display_type} 字段一一对应。
 * <p>
 * 两个取值直接对应 DragonSurvival 自己 {@code block_vision} 系统里的
 * {@code BlockVision.DisplayType}（那套系统的 {@code outline} / {@code simple_shader}
 * 是挂在玩家身上、只对该玩家本地渲染的；本模组把它改造成「服务端广播固定方块坐标」的形态）。
 * <p>
 * 两者的代价差异是本效果做性能分层筛选的<b>唯一依据</b>：
 * <ul>
 *     <li>{@link #OUTLINE} —— 画一个立方体线框，<b>恒 12 条棱 / 24 个顶点</b>，
 *         开销与方块形状、模型复杂度完全无关，且服务端无需预筛；</li>
 *     <li>{@link #SIMPLE_SHADER} —— 复用 DS 的核心着色器把<b>整块方块的模型逐面重新染色</b>，
 *         客户端每帧要为每个方块做 {@code getBlockModel} + {@code getQuads}（7 次：未剔除 + 6 方向）
 *         + 顶点写入，开销显著 → 服务端在<b>发包之前</b>就先把被完全遮挡、形状不完整的方块剔掉
 *         （见 {@link BlockGlows#register}）。</li>
 * </ul>
 * 扩展方式：在此新增枚举值，并在客户端 {@code client.eventhandler.BlockGlowRenderHandler}
 * 补上对应渲染分支；JSON 与网络层无需改动。
 */
public enum GlowDisplayType implements StringRepresentable {
    /** 线框描边：沿方块边界画 12 条棱，开启深度测试（被遮挡的棱不显示） */
    OUTLINE("outline"),
    /** 整体染色：复用 DS 的 {@code dragonsurvival:block_vision_simple} 着色器给方块模型染色 */
    SIMPLE_SHADER("simple_shader");

    public static final Codec<GlowDisplayType> CODEC = StringRepresentable.fromEnum(GlowDisplayType::values);

    /**
     * 网络传输按<b>序列名</b>而非序号编解码：将来增删枚举值或调整书写顺序时，
     * 不会让两端的序号错位（与 {@code common.vision.ScreenVisionType} 同一约定）。
     */
    public static final StreamCodec<ByteBuf, GlowDisplayType> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(GlowDisplayType::byName, GlowDisplayType::getSerializedName);

    private final String serializedName;

    GlowDisplayType(final String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public @NotNull String getSerializedName() {
        return serializedName;
    }

    public static @NotNull GlowDisplayType byName(final @NotNull String name) {
        for (GlowDisplayType value : values()) {
            if (value.serializedName.equals(name)) {
                return value;
            }
        }

        throw new IllegalArgumentException("Unknown glow display type: " + name);
    }

    /** 是否走 DS 的核心着色器路径 —— 服务端据此决定要不要做「源头剔除」（详见枚举注释） */
    public boolean isShader() {
        return this == SIMPLE_SHADER;
    }
}
