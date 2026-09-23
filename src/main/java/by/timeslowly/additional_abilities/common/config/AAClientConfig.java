package by.timeslowly.additional_abilities.common.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.NotNull;

/**
 * 本模组的客户端配置，落地为 {@code config/additional_abilities-client.toml}。
 *
 * <h2>为什么是 CLIENT 而不是 COMMON</h2>
 * 这里放的是 HUD 读数区位置与蓄力提示音一类项，纯客户端状态、不参与任何服务端判定，
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
 * 枚举选项还多一层：每个枚举值自己一个键（见 {@link LevelUpSound#getTranslatedName()}）。
 *
 * <h2>改完立即生效</h2>
 * 游戏内「模组 → Additional Abilities for DS → 配置」改完即写回文件并重载，
 * {@code ConfigValue} 的缓存随之失效，下一帧 HUD / 下一次播音就用新值，<b>无需重启</b>。
 *
 * <h2>为什么偏移量是整数</h2>
 * 与 GUI 缩放后的像素坐标同一量纲，取整后可以保证文字与进度条都落在整像素上，避免半像素模糊。
 *
 * <h2>提示音为什么是「总开关 + 总倍率」而不是绝对值</h2>
 * 两种提示音（档位提升音、滚轮选档音）各自的<b>基准音量</b>不同是刻意的 —— 后者更短、更轻，
 * 与前者区分开。因此配置项只提供一个叠在基准之上的总倍率 {@link #SOUND_VOLUME}，
 * 而不是把两者拉平成同一个绝对值；倍率默认 {@code 1.0}，
 * 也就是完全不配置时与升级前的响度<b>逐分贝一致</b>。
 * <p>
 * ⚠️ NeoForge 21.1 的 {@link ModConfigSpec} <b>没有</b>「由某个布尔项联动灰化另一个项」的能力
 * （{@code FeatureFlag} 要 1.21.4 才有），所以关掉 {@link #PLAY_SOUND} 时音量项在配置界面上
 * 仍然是可编辑的，只能在它的提示行里写明「仅在开关打开时生效」。
 *
 * <h2>档位提升音为什么只有固定预设</h2>
 * NeoForge 配置系统<b>没有注册表类型</b>（{@code define*} 只覆盖基本类型与枚举），
 * 所以 {@code SoundEvent} 这类注册表对象装不进配置项；而"存成字符串 id"在配置界面里只会渲染成
 * 一个手输框，既没有可选项也没有补全。另一方面原版 {@code SoundEvents} 就有 1486 个常量、
 * 加模组通常 2000+，下拉框根本放不下。
 * <p>
 * 因此 {@link #LEVEL_UP_SOUND} 做成 {@link LevelUpSound} 枚举 —— {@code defineEnum} 会让配置界面
 * 渲染成下拉框，零 UI 代码即可点选，代价是只能从这 8 个预设里挑。
 * 想要"任意已注册音效"必须自建带搜索的选择界面，属另一件事。
 * <p>
 * 音高曲线由 {@link #LEVEL_UP_PITCH} / {@link #LEVEL_UP_PITCH_PER_LEVEL} / {@link #LEVEL_UP_MAX_PITCH}
 * 三项给出（公式见 {@code client.eventhandler.ChargedLevelSoundHandler}）。
 * 三者的取值范围都落在 <b>[0.5, 2.0]</b> 内，因为游戏的声音引擎本身就按这个区间钳制音高。
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

    /** 提示音总倍率的上限，同时也是默认值：{@code 1.0} 表示各音使用自己的基准音量。 */
    private static final double SOUND_VOLUME_MAX = 1.0;

    /**
     * 音高可设范围，同时也是<b>游戏声音引擎的硬性钳制区间</b>：
     * {@code SoundEngine} 播放时执行 {@code Mth.clamp(pitch, 0.5F, 2.0F)}，
     * 超出这个区间的取值没有意义（设了也会被钳回去），所以配置范围直接取该区间。
     */
    private static final double PITCH_MIN = 0.5;
    private static final double PITCH_MAX = 2.0;

    /** 第 1 档的默认音高（旧版行为）。 */
    private static final double LEVEL_UP_PITCH_DEFAULT = 0.9;
    /** 每升一档的默认音高增量（旧版行为）。 */
    private static final double LEVEL_UP_PITCH_PER_LEVEL_DEFAULT = 0.12;

    /**
     * 超限窗口临界警示的默认阈值：<b>剩余比例</b> ≤ 0.3（即只剩下一半）时转红。
     * <p>
     * 用比例而不是绝对刻数，是为了自动适配各技能各自不同的窗口长度
     * （窗口 20 刻与 200 刻的技能可以用同一个配置）。
     */
    private static final double OVERCHARGE_WARNING_DEFAULT = 0.3;

    /** 临界警示阈值的取值区间；{@link #OVERCHARGE_WARNING_MIN} 同时就是"不转红"的取值。 */
    private static final double OVERCHARGE_WARNING_MIN = 0.0;
    private static final double OVERCHARGE_WARNING_MAX = 1.0;

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.EnumValue<IndicatorAnchor> INDICATOR_ANCHOR;
    private static final ModConfigSpec.IntValue INDICATOR_OFFSET_X;
    private static final ModConfigSpec.IntValue INDICATOR_OFFSET_Y;
    private static final ModConfigSpec.DoubleValue OVERCHARGE_WARNING_RATIO;
    private static final ModConfigSpec.BooleanValue PLAY_SOUND;
    private static final ModConfigSpec.DoubleValue SOUND_VOLUME;
    private static final ModConfigSpec.EnumValue<LevelUpSound> LEVEL_UP_SOUND;
    private static final ModConfigSpec.DoubleValue LEVEL_UP_PITCH;
    private static final ModConfigSpec.DoubleValue LEVEL_UP_PITCH_PER_LEVEL;
    private static final ModConfigSpec.DoubleValue LEVEL_UP_MAX_PITCH;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // push 之前写下的 comment / translation 会成为这一节的「节注释」与「节译名」，
        // 即 TOML 里 [charged_indicator] 上方的说明与配置界面里那一行的标题
        builder.comment(
                        "蓄力档位的交互反馈：读数区（HUD）位置，以及档位提示音。",
                        "Charged-tier feedback: readout (HUD) placement and the tier sounds.")
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

        OVERCHARGE_WARNING_RATIO = builder
                .comment(
                        "超限窗口的临界限（剩余比例）：剩余量占窗口总长的比例不超过它时，读数区的超限行转红。",
                        "范围 0.0 ~ 1.0；0.5 = 只剩下一半时转红，0.0 = 不转红（超限行始终用常态色）。",
                        "用比例而非绝对刻数，可自动适配各技能不同的窗口时长。该项只改颜色，不发声。",
                        "Warning threshold for the overcharge window (as a fraction of the window): the overcharge row",
                        "of the readout turns red once the remaining fraction is at or below it.",
                        "Range 0.0 - 1.0; 0.5 turns red at the halfway point, 0.0 disables the warning colour.",
                        "A fraction rather than a tick count, so one setting fits every window length.",
                        "Colour only — this option plays no sound.")
                .translation(LANG_PREFIX + "overcharge_warning_ratio")
                .defineInRange("overcharge_warning_ratio", OVERCHARGE_WARNING_DEFAULT,
                        OVERCHARGE_WARNING_MIN, OVERCHARGE_WARNING_MAX);

        PLAY_SOUND = builder
                .comment(
                        "是否播放蓄力档位的提示音：每升一档一声，滚轮选档时另有一声更短的点击音。",
                        "关掉后两者一起静音；龙之生存自己的蓄力 / 释放音效不受影响。",
                        "Whether to play the charged-tier sounds (one per tier gained, plus a shorter click per wheel pick).",
                        "Turning it off silences both; Dragon Survival's own charging / release sounds are unaffected.")
                .translation(LANG_PREFIX + "play_sound")
                .define("play_sound", true);

        SOUND_VOLUME = builder
                .comment(
                        "提示音总倍率（0.0 ~ 1.0），叠在每种提示音各自的基准音量之上：",
                        "档位提升音基准 0.7、滚轮选档音基准 0.6 —— 两者的差异是刻意的，不会被本项拉平。",
                        "默认 1.0 即与旧版响度一致；本项仅在 play_sound 打开时生效。",
                        "Master volume multiplier (0.0 - 1.0) applied on top of each sound's own base volume:",
                        "0.7 for the tier sound and 0.6 for the wheel click (the difference is intentional and kept).",
                        "Default 1.0 matches the previous loudness. Only takes effect while play_sound is on.")
                .translation(LANG_PREFIX + "sound_volume")
                .defineInRange("sound_volume", SOUND_VOLUME_MAX, 0.0, SOUND_VOLUME_MAX);

        LEVEL_UP_SOUND = builder
                .comment(
                        "档位提升音用哪个音效（下拉框，8 个预设）。默认 NOTE_PLING，即旧版行为。",
                        "配置系统装不下「任意已注册音效」（没有注册表类型，且原版就有 1486 个音效），",
                        "所以只能给固定几个预设。换音效后建议顺带调一下下面的音高曲线。",
                        "Which sound plays when the charge tier goes up (dropdown, 8 presets).",
                        "NOTE_PLING is the default = the old behaviour. The config system cannot offer every",
                        "registered sound (no registry type, and vanilla alone has 1486 of them), hence the fixed set.",
                        "After switching sounds you will likely want to retune the pitch curve below.")
                .translation(LANG_PREFIX + "level_up_sound")
                .defineEnum("level_up_sound", LevelUpSound.NOTE_PLING);

        LEVEL_UP_PITCH = builder
                .comment(
                        "第 1 档的起始音高，范围 0.5 ~ 2.0，默认 0.9。1.0 为音效的原始音高。",
                        "只影响档位提升音；滚轮选档音的音高不受本项影响。",
                        "Pitch at tier 1, range 0.5 - 2.0, default 0.9. 1.0 is the sound's original pitch.",
                        "Only affects the tier sound; the wheel click has its own fixed pitch curve.")
                .translation(LANG_PREFIX + "level_up_sound_pitch")
                .defineInRange("level_up_sound_pitch", LEVEL_UP_PITCH_DEFAULT, PITCH_MIN, PITCH_MAX);

        LEVEL_UP_PITCH_PER_LEVEL = builder
                .comment(
                        "每升一档增加多少音高，范围 0.0 ~ 1.0，默认 0.12。设为 0 则所有档位同一个音高。",
                        "Pitch added per tier gained, range 0.0 - 1.0, default 0.12. 0 keeps one pitch for every tier.")
                .translation(LANG_PREFIX + "level_up_sound_pitch_per_level")
                .defineInRange("level_up_sound_pitch_per_level", LEVEL_UP_PITCH_PER_LEVEL_DEFAULT, 0.0, 1.0);

        LEVEL_UP_MAX_PITCH = builder
                .comment(
                        "音高封顶值，范围 0.5 ~ 2.0，默认 2.0。",
                        "游戏的声音引擎把音高钳在 [0.5, 2.0]，所以 2.0 已是硬上限；调低它可以让音高更早停止上升。",
                        "Pitch ceiling, range 0.5 - 2.0, default 2.0. The sound engine clamps pitch to [0.5, 2.0],",
                        "so 2.0 is already the hard limit; lowering it makes the pitch stop rising earlier.")
                .translation(LANG_PREFIX + "level_up_sound_max_pitch")
                .defineInRange("level_up_sound_max_pitch", PITCH_MAX, PITCH_MIN, PITCH_MAX);

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

    /**
     * 超限窗口的临界警示阈值（<b>剩余比例</b>）：读数区的超限行在剩余量占比 ≤ 该值时转红。
     * <p>
     * 取值 {@code [0.0, 1.0]}，{@link #OVERCHARGE_WARNING_MIN}（= {@code 0}）表示不转红
     * （超限行始终用常态色）。配置尚未加载时返回 {@link #OVERCHARGE_WARNING_DEFAULT}
     * （剩下一半时转红）—— 与"配置没读出来也不该静默改变观感"的既有约定一致。
     * <p>
     * 为什么用比例而不是绝对刻数：超限窗口的长度由各技能自己的 {@code max_overcharged_duration} 决定，
     * 彼此可能相差一个数量级；比例让同一个配置对所有技能都成立，也不必再为"刻数上限该给多少"做取舍。
     * <p>
     * 本项<b>只改颜色、不发声</b>：听觉提示需要额外的节流与音效设计，且容易与 DS 自己的蓄力音、
     * 本模组的档位提示音互相打架，本次有意不做。
     */
    public static double overchargeWarningRatio() {
        return SPEC.isLoaded() ? OVERCHARGE_WARNING_RATIO.get() : OVERCHARGE_WARNING_DEFAULT;
    }

    /**
     * 是否播放蓄力档位提示音（档位提升音与滚轮选档音共用本开关）。
     * <p>
     * 配置尚未加载时返回 {@code true} —— 与升级前"总是播"一致，
     * 也让"配置没读出来"不至于静默改变观感。
     */
    public static boolean playSound() {
        return !SPEC.isLoaded() || PLAY_SOUND.get();
    }

    /**
     * 提示音总倍率，叠在各音自己的基准音量之上（见类注释）。
     * <p>
     * 配置尚未加载时返回 {@link #SOUND_VOLUME_MAX}，即各音使用基准音量。
     */
    public static double soundVolume() {
        return SPEC.isLoaded() ? SOUND_VOLUME.get() : SOUND_VOLUME_MAX;
    }

    /** 档位提升音用哪个预设；配置尚未加载时退回旧版音效 {@link LevelUpSound#NOTE_PLING}。 */
    public static LevelUpSound levelUpSound() {
        return SPEC.isLoaded() ? LEVEL_UP_SOUND.get() : LevelUpSound.NOTE_PLING;
    }

    /** 第 1 档的起始音高；配置尚未加载时返回旧版默认值。 */
    public static double levelUpPitch() {
        return SPEC.isLoaded() ? LEVEL_UP_PITCH.get() : LEVEL_UP_PITCH_DEFAULT;
    }

    /** 每升一档的音高增量；配置尚未加载时返回旧版默认值。 */
    public static double levelUpPitchPerLevel() {
        return SPEC.isLoaded() ? LEVEL_UP_PITCH_PER_LEVEL.get() : LEVEL_UP_PITCH_PER_LEVEL_DEFAULT;
    }

    /** 音高封顶值；配置尚未加载时返回 {@link #PITCH_MAX}（引擎硬上限，即旧版行为）。 */
    public static double levelUpMaxPitch() {
        return SPEC.isLoaded() ? LEVEL_UP_MAX_PITCH.get() : PITCH_MAX;
    }
}
