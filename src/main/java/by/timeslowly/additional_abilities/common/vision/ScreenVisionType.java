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
 * 扩展方式：在此新增枚举值，再在客户端渲染处
 * （{@code client.ClientScreenVisionState}）补一条对应分支即可，
 * JSON 与网络层无需改动。
 */
public enum ScreenVisionType implements StringRepresentable {
    /** 画面抖动：按强度对镜头 roll 施加不规则偏移 */
    SCREEN_SHAKE("screen_shake");

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
