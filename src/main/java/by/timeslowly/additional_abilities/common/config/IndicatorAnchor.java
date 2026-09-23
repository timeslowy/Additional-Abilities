package by.timeslowly.additional_abilities.common.config;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.TranslatableEnum;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * 蓄力档位读数区（HUD）相对 DS 蓄力条的摆放方位，供客户端配置选择。
 *
 * <h2>为什么只围绕蓄力条给四个方位</h2>
 * 施法时玩家的视线本来就落在蓄力条上，读数区贴着蓄力条摆才不用移开视线；
 * 「屏幕四角 / 屏幕居中」那类看着自由，实战里反而没人用。
 * 所以这里只保留右 / 左 / 上 / 下四个方位，需要再微调就用
 * {@code offset_x} / {@code offset_y}（见 {@link AAClientConfig}）。
 *
 * <h2>方位怎么算</h2>
 * 四个方位都相对<b>蓄力条在屏幕上的实际矩形</b> —— DS 会把玩家在它自己配置里设的自定义偏移
 * 叠加进这条矩形（见 {@code MagicHUD#castbarXOffset} / {@code castbarYOffset}），
 * 因此读数区会跟着 DS 的蓄力条一起被挪动：
 * <ul>
 *     <li>{@link #CAST_BAR_RIGHT}：整块读数区贴在蓄力条<b>右侧</b>，与蓄力条垂直居中；</li>
 *     <li>{@link #CAST_BAR_LEFT}：贴在<b>左侧</b>，垂直居中；</li>
 *     <li>{@link #CAST_BAR_ABOVE}：放在蓄力条<b>上方</b>，横向与蓄力条对齐（按读数区宽度居中）；</li>
 *     <li>{@link #CAST_BAR_BELOW}：放在蓄力条<b>下方</b>，横向对齐方式同上。</li>
 * </ul>
 * 四个方位与蓄力条之间都留 {@link #GAP} 像素间距。
 * <p>
 * 这里用的是 <b>GUI 缩放后的像素</b>（与 {@code GuiGraphics#guiWidth()} 同一套坐标）：
 * 换 GUI 缩放档位时像素数字会变，但方位语义不变。
 *
 * <h2>默认值与旧版一致</h2>
 * 默认值 {@link #CAST_BAR_RIGHT} 恰好复现旧版硬编码的坐标，因此不配置任何东西时观感与升级前完全相同。
 *
 * <h2>为什么是无字段枚举 + 穷尽 switch</h2>
 * 枚举常量<b>不能前向引用同类中声明在它之后的静态字段</b>（JLS 8.3.3），而枚举体又必须以常量列表开头，
 * 所以"给每个常量塞方位码"的写法在 Java 里编译不过。这里改用穷尽的
 * {@code switch (this)} 表达方位：语义就近可读，且将来新增方位时**不处理就编译不过**。
 *
 * <h2>跨端</h2>
 * 本枚举只做整数运算，<b>不引用任何客户端专属类型</b> —— 连 DS 的 {@code MagicHUD} 都不碰，
 * 蓄力条矩形由调用方算好后作为参数传进来。因此它可以安全地被通用代码里的配置类持有
 * （配置类在物理服务端同样会被加载，详见 {@link AAClientConfig}）。
 */
public enum IndicatorAnchor implements TranslatableEnum {
    /** 贴在蓄力条右侧、与蓄力条垂直居中。旧版行为，也是默认值。 */
    CAST_BAR_RIGHT,
    CAST_BAR_LEFT,
    CAST_BAR_ABOVE,
    CAST_BAR_BELOW;

    /** 读数区与蓄力条之间的间距（GUI 缩放后的像素）。四个方位通用。 */
    private static final int GAP = 6;

    /**
     * 读数区基准位的横坐标。
     *
     * @param castBarLeft  蓄力条在屏幕上的左边缘（GUI 缩放后的像素）
     * @param castBarWidth 蓄力条的屏幕宽度
     * @param widgetWidth  读数区自身的宽度，用于「贴左」与「上下方位时水平对齐」扣掉自身
     */
    public int left(final int castBarLeft, final int castBarWidth, final int widgetWidth) {
        return switch (this) {
            case CAST_BAR_RIGHT -> castBarLeft + castBarWidth + GAP;
            case CAST_BAR_LEFT -> castBarLeft - GAP - widgetWidth;
            case CAST_BAR_ABOVE, CAST_BAR_BELOW -> castBarLeft + (castBarWidth - widgetWidth) / 2;
        };
    }

    /**
     * 读数区基准位的纵坐标。
     *
     * @param castBarTop    蓄力条在屏幕上的上边缘（GUI 缩放后的像素）
     * @param castBarHeight 蓄力条的屏幕高度
     * @param widgetHeight  读数区自身的高度，用于「垂直居中」与「贴上方」扣掉自身
     */
    public int top(final int castBarTop, final int castBarHeight, final int widgetHeight) {
        return switch (this) {
            case CAST_BAR_RIGHT, CAST_BAR_LEFT -> castBarTop + (castBarHeight - widgetHeight) / 2;
            case CAST_BAR_ABOVE -> castBarTop - GAP - widgetHeight;
            case CAST_BAR_BELOW -> castBarTop + castBarHeight + GAP;
        };
    }

    /**
     * 配置界面下拉框里显示的名字。
     * <p>
     * 键形如 {@code additional_abilities.configuration.charged_indicator.anchor.cast_bar_right}，
     * 逐值对应；真缺了译文也只是显示键名本身，不会崩。
     */
    @Contract(" -> new")
    @Override
    public @NotNull Component getTranslatedName() {
        return Component.translatable(
                AAClientConfig.LANG_PREFIX + "anchor." + name().toLowerCase(Locale.ROOT));
    }
}
