package by.timeslowly.additional_abilities.common.vision;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

/**
 * 屏幕视觉效果的选项枚举，与效果 JSON 的 {@code "type"} 字段一一对应。
 * <p>
 * 扩展方式：在此新增枚举值，再在客户端补上对应的渲染分支
 * （状态与淡入淡出缓动在 {@code client.ClientScreenVisionState}，
 * 具体绘制在各类型的渲染入口，如模糊的 {@code client.ScreenBlurRenderer}）。
 * <p>
 * ⚠️ 若新类型所需的参数<b>超出</b> {@code ScreenVisionPayload} 现有的
 * {@code (type, amplifier, size, rgb, durationTicks)} 五个字段，
 * 则网络层与状态槽<b>不再"无需改动"</b> —— 必须同时扩字段并递增
 * {@code AANetwork#PROTOCOL_VERSION}（判定依据始终是线上字节格式是否变化）。
 */
public enum ScreenVisionType implements StringRepresentable {
    /** 画面抖动：按强度对镜头 roll 施加不规则偏移 */
    SHAKE("shake"),
    /** 画面模糊（近似近视）：按强度对世界画面做可分离盒式模糊，界面不受影响 */
    BLUR("blur"),
    /**
     * 边光：屏幕画面边缘覆盖一层由边向内渐隐的遮罩。
     * <p>
     * 三个参数各司其职：{@code amplifier} 是不透明度（强度）、{@code size} 是边缘厚度
     * （占屏幕短边的比例）、{@code color} 是遮罩颜色（默认白色 = 提亮成"光"，
     * 写 {@code black} 即变成压暗的"暗角"）。
     */
    EDGE_LIGHT("edge_light");

    public static final Codec<ScreenVisionType> CODEC = StringRepresentable.fromEnum(ScreenVisionType::values);

    /**
     * 网络传输按<b>序列名</b>而非序号编解码：
     * 将来增删枚举值时不会让两端序号错位。
     */
    public static final StreamCodec<ByteBuf, ScreenVisionType> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(ScreenVisionType::byName, ScreenVisionType::getSerializedName);

    private final String serializedName;

    ScreenVisionType(final String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public @NotNull String getSerializedName() {
        return serializedName;
    }

    public static @NotNull ScreenVisionType byName(final @NotNull String name) {
        for (ScreenVisionType value : values()) {
            if (value.serializedName.equals(name)) {
                return value;
            }
        }

        throw new IllegalArgumentException("Unknown screen vision type: " + name);
    }
}
