package by.timeslowly.additional_abilities.common.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.NotNull;

/**
 * 本模组的客户端配置，落地为 {@code config/additional_abilities-client.toml}。
 *
 * <h2>为什么是 CLIENT 而不是 COMMON</h2>
 * 这里目前只放 HUD 位置一类项，纯客户端状态、不参与任何服务端判定，
 * 因此用 {@link ModConfig.Type#CLIENT}：文件只写在玩家本地，也不会随联机同步给其他人。
 *
 * <h2>跨端安全性（为什么主类可以无条件登记）</h2>
 * 本类<b>不引用任何客户端专属类型</b>（只用到 {@link ModConfigSpec}、本模组枚举与字符串），
 * 所以 {@link #register(ModContainer)} 可以在主类构造函数里直接调用，不需要物理端判定。
 * NeoForge 只在物理客户端加载 CLIENT 配置（{@code CommonModLoader} 里的
 * {@code if (FMLEnvironment.dist == Dist.CLIENT)}），专用服务端上 {@code SPEC} 只会被登记、永不加载 ——
 * 因此下面所有读取入口都用 {@link ModConfigSpec#isLoaded()} 兜底，未加载时返回默认值。
 *
 * <h2>译文键分三层，漏一层就会在界面上显示原始键名</h2>
 * NeoForge 配置界面按下列规则取译文，缺了不会有兜底文案，只会把键名原样画出来：
 * <ul>
 *     <li><b>分区名</b>：{@code push()} 之前用 {@code builder.translation(LANG_SECTION)} 指定，
 *         否则回退到 {@code <modid>.configuration.<分区路径>} 这种机器生成的键名；</li>
 *     <li><b>选项名</b>：{@code builder.translation(LANG_PREFIX + "<选项>")}；</li>
 *     <li><b>选项提示行</b>：上面那个键再加 {@code .tooltip}。</li>
 * </ul>
 *
 * <h2>改完立即生效</h2>
 * 游戏内「模组 → Additional Abilities for DS → 配置」改完即写回文件并重载，
 * {@code ConfigValue} 的缓存随之失效，下一帧 HUD 就用新值，<b>无需重启</b>。
 *
 * <h2>为什么偏移量是整数</h2>
 * 与 GUI 缩放后的像素坐标同一量纲，取整后可以保证文字与进度条都落在整像素上，避免半像素模糊。
 */
public final class AAClientConfig {
    /**
     * 分区 {@code [charged_indicator]} 自身的译文键。
     * <p>
     * 它是所有选项键去掉尾点后的公共前缀 —— 两者刻意保持这个关系，
     * 是为了让人一眼看出"选项键都在这个分区之下"。
     */
    public static final String LANG_SECTION = "additional_abilities.configuration.charged_indicator";

    /** 本分区内所有选项译文键的公共前缀（即 {@link #LANG_SECTION} 加一个点）。 */
    public static final String LANG_PREFIX = LANG_SECTION + ".";

    /** 偏移量上下限（GUI 缩放后的像素）。四个方位已经解决"贴哪边"，偏移只是微调，不需要更大。 */
    private static final int OFFSET_LIMIT = 4000;

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.EnumValue<IndicatorAnchor> INDICATOR_ANCHOR;
    private static final ModConfigSpec.IntValue INDICATOR_OFFSET_X;
    private static final ModConfigSpec.IntValue INDICATOR_OFFSET_Y;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // push 之前写下的 comment / translation 会成为这一节的「节注释」与「节译名」，
        // 即 TOML 里 [charged_indicator] 上方的说明与配置界面里那一行的标题
        builder.comment(
                        "蓄力档位读数区（HUD）的位置。",
                        "Position of the charged-tier indicator (HUD).")
                .translation(LANG_SECTION)
                .push("charged_indicator");

        INDICATOR_ANCHOR = builder
                .comment(
                        "读数区相对蓄力条摆在哪个方位。",
                        "CAST_BAR_RIGHT：贴蓄力条右侧（默认，即旧版行为）；CAST_BAR_LEFT：贴左侧；",
                        "CAST_BAR_ABOVE / CAST_BAR_BELOW：放在蓄力条上方 / 下方，横向与蓄力条对齐。",
                        "Which side of the cast bar the indicator sits on.",
                        "CAST_BAR_RIGHT is the right side (default = the old behaviour); CAST_BAR_LEFT the left side;",
                        "CAST_BAR_ABOVE / CAST_BAR_BELOW place it above / below, aligned with the bar horizontally.")
                .translation(LANG_PREFIX + "anchor")
                .defineEnum("anchor", IndicatorAnchor.CAST_BAR_RIGHT);

        INDICATOR_OFFSET_X = builder
                .comment(
                        "在上述方位之上再横向平移多少像素（GUI 缩放后的像素，向右为正）。",
                        "Horizontal shift in GUI-scaled pixels applied on top of the placement (positive = right).")
                .translation(LANG_PREFIX + "offset_x")
                .defineInRange("offset_x", 0, -OFFSET_LIMIT, OFFSET_LIMIT);

        INDICATOR_OFFSET_Y = builder
                .comment(
                        "在上述方位之上再纵向平移多少像素（GUI 缩放后的像素，向下为正）。",
                        "Vertical shift in GUI-scaled pixels applied on top of the placement (positive = down).")
                .translation(LANG_PREFIX + "offset_y")
                .defineInRange("offset_y", 0, -OFFSET_LIMIT, OFFSET_LIMIT);

        builder.pop();

        SPEC = builder.build();
    }

    private AAClientConfig() {
        // 配置持有类，只暴露静态成员
    }

    /**
     * 把本配置登记到模组容器。
     * <p>
     * 必须在模组构造函数里调用（NeoForge 只在那时接受登记）。这一步只是"登记 + 校验结构"，
     * 真正读文件由 NeoForge 按物理端在后续阶段完成（客户端才会加载 CLIENT 类型）。
     * <p>
     * 注意：光登记配置<b>拿不到</b>模组列表里那个可点的「配置」按钮 ——
     * 还要额外注册 {@code IConfigScreenFactory} 扩展点，见
     * {@code common.AAClientSetup#register}。
     */
    public static void register(final @NotNull ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    /** 读数区方位；配置尚未加载时（专用服务端、或加载失败）退回旧版行为。 */
    public static IndicatorAnchor indicatorAnchor() {
        return SPEC.isLoaded() ? INDICATOR_ANCHOR.get() : IndicatorAnchor.CAST_BAR_RIGHT;
    }

    /** 读数区横向偏移（GUI 缩放后的像素，向右为正）。 */
    public static int indicatorOffsetX() {
        return SPEC.isLoaded() ? INDICATOR_OFFSET_X.get() : 0;
    }

    /** 读数区纵向偏移（GUI 缩放后的像素，向下为正）。 */
    public static int indicatorOffsetY() {
        return SPEC.isLoaded() ? INDICATOR_OFFSET_Y.get() : 0;
    }
}
