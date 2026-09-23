package by.timeslowly.additional_abilities.common.config;

import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.common.TranslatableEnum;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * 「档位提升音」可选的预设音效，供客户端配置下拉框选择。
 *
 * <h2>为什么只有固定几个预设，而不是"任意已注册音效"</h2>
 * NeoForge 的配置系统（{@link net.neoforged.neoforge.common.ModConfigSpec}）<b>没有注册表类型</b> ——
 * {@code define*} 只覆盖 boolean / int / long / double / String / enum / {@code List<String>}，
 * 所以 {@code SoundEvent} 这类注册表对象根本装不进配置项。
 * <p>
 * 退一步的"存成字符串 id"虽然能表达任意音效，但在配置界面里只会渲染成一个<b>手输框</b>
 * （{@code ConfigurationSectionScreen#createStringValue}），既没有可选项也没有补全；
 * 而"把全部音效列成可选项"也不现实 —— 原版 {@code SoundEvents} 就有 <b>1486</b> 个常量，
 * 加上模组通常 2000+，下拉框装不下（要做只能是自建带搜索的选择界面，属另一件事）。
 * <p>
 * 因此这里取"枚举 = 下拉框"这条路：{@code defineEnum} 会让配置界面渲染成 {@code CycleButton}，
 * 零 UI 代码即可得到可点选的下拉框，代价是预设固定。
 *
 * <h2>为什么是「无字段枚举 + 穷尽 switch」而不是字段持有 {@link SoundEvent}</h2>
 * <ol>
 *     <li><b>JLS 8.3.3</b>：枚举常量不能前向引用同类中声明在它之后的静态字段，而枚举体必须以常量列表开头 ——
 *         所以"给每个常量塞一个音效"的字段写法在这里天然别扭（与 {@link IndicatorAnchor} 同一个理由）；</li>
 *     <li><b>跨端安全</b>：本枚举被配置类 {@link AAClientConfig} 的静态块引用，
 *         因此<b>专用服务端也会加载它</b>。字段持有 {@code SoundEvent} 会在类初始化时就解析 Holder；
 *         而 {@link #resolve()} 是惰性的，只在客户端真正要播音时才求值，
 *         服务端上这个类只承载一个枚举名。</li>
 * </ol>
 *
 * <h2>音高不在本枚举里</h2>
 * 音高曲线由 {@link AAClientConfig#levelUpPitch()} / {@link AAClientConfig#levelUpPitchPerLevel()} /
 * {@link AAClientConfig#levelUpMaxPitch()} 三项配置决定（公式见
 * {@code client.eventhandler.ChargedLevelSoundHandler}）。
 * <p>
 * ⚠️ 游戏的声音引擎会把音高钳在 <b>[0.5, 2.0]</b>（{@code SoundEngine} 里
 * {@code Mth.clamp(sound.getPitch(), 0.5F, 2.0F)}），所以"随档位升高"实际最高只到 2.0 倍。
 * 音色对这种升调很敏感：短促的打击乐类悦耳，长音 / 环境音会明显失真。
 *
 * <h2>译名键</h2>
 * 形如 {@code additional_abilities.configuration.charged_indicator.level_up_sound.<小写名>}，
 * 逐值对应；真缺了译文也只是显示键名本身，不会崩。
 */
public enum LevelUpSound implements TranslatableEnum {
    /** 紫水晶「叮」—— 清脆、衰减快。 */
    AMETHYST_BREAK,
    /** 末影之眼抛出 —— 略沉闷的一声。 */
    ENDER_EYE_LAUNCH,
    /** 经验球拾取 —— 短促上扬。 */
    EXPERIENCE_ORB,
    /** 音符盒（铃）—— 比 pling 更浑厚。 */
    NOTE_BELL,
    /** 音符盒（钟琴）—— 比 pling 更亮。 */
    NOTE_CHIME,
    /** 音符盒（叮）—— 旧版行为，默认值。 */
    NOTE_PLING,
    /** 重生锚充能 —— 低频上扬，适合"充能"语义。 */
    RESPAWN_ANCHOR_CHARGE,
    /** 界面按钮点击 —— 最短最轻，几乎不打扰。 */
    UI_BUTTON_CLICK;

    /**
     * 解析出实际播放用的 {@link SoundEvent}。<b>只在客户端调用</b>（服务端不会播音）。
     * <p>
     * 用一个穷尽的 {@code switch} 把枚举名映射到原版常量：语义就近可读，
     * 且将来新增预设时**不处理就编译不过**。
     * <p>
     * ⚠️ 原版 {@code SoundEvents} 里两类声明混用 ——
     * {@code Holder.Reference<SoundEvent>}（要 {@code .value()}）与直接的 {@code SoundEvent}（不要），
     * 这里各自按其真实类型书写，不要照抄隔壁那一行。
     *
     * @return 该预设对应的原版音效
     */
    @Contract(pure = true)
    public @NotNull SoundEvent resolve() {
        return switch (this) {
            case AMETHYST_BREAK -> SoundEvents.AMETHYST_BLOCK_BREAK;
            case ENDER_EYE_LAUNCH -> SoundEvents.ENDER_EYE_LAUNCH;
            case EXPERIENCE_ORB -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case NOTE_BELL -> SoundEvents.NOTE_BLOCK_BELL.value();
            case NOTE_CHIME -> SoundEvents.NOTE_BLOCK_CHIME.value();
            case NOTE_PLING -> SoundEvents.NOTE_BLOCK_PLING.value();
            case RESPAWN_ANCHOR_CHARGE -> SoundEvents.RESPAWN_ANCHOR_CHARGE;
            case UI_BUTTON_CLICK -> SoundEvents.UI_BUTTON_CLICK.value();
        };
    }

    /**
     * 配置界面下拉框里显示的名字。
     * <p>
     * 键形如 {@code additional_abilities.configuration.charged_indicator.level_up_sound.note_pling}。
     */
    @Contract(" -> new")
    @Override
    public @NotNull Component getTranslatedName() {
        return Component.translatable(
                AAClientConfig.LANG_PREFIX + "level_up_sound." + name().toLowerCase(Locale.ROOT));
    }
}
