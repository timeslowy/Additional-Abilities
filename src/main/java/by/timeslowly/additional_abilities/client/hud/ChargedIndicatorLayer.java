package by.timeslowly.additional_abilities.client.hud;

import by.dragonsurvivalteam.dragonsurvival.client.gui.hud.MagicHUD;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.client.OptionalChargedSelection;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 蓄力档位数字提示（仅客户端加载），同时服务 {@code additional_abilities:charged} 与
 * {@code additional_abilities:optional_charged}。
 *
 * <h2>挂载方式</h2>
 * DS 的 {@code MagicHUD} 本身是一个 GUI 图层（id 为 {@code dragonsurvival:magic_hud}），
 * 因此本图层用 {@code RegisterGuiLayersEvent#registerAbove(MagicHUD.ID, …)} 叠在它<b>之上</b>，
 * 这样蓄力条画完才轮到我们画数字，不会互相遮挡。
 * <p>
 * {@code RegisterGuiLayersEvent} 属于 mod 事件总线的事件，而 NeoForge 21.1 已废弃
 * {@code @EventBusSubscriber#bus()}，因此改由主类经
 * {@link by.timeslowly.additional_abilities.common.AAClientSetup} 手动注册
 * （该类的方法体里才引用本类，专用服务端不会加载到这里的客户端类型）。
 *
 * <h2>坐标怎么来的</h2>
 * {@code MagicHUD} 画蓄力条时把 pose 缩放为 0.5 后 translate(startX, startY)，再以
 * (startX, startY) 为起点贴 196×47 的贴图 —— 经过 0.5 缩放后，蓄力条在屏幕上的实际矩形是
 * {@code [startX, startX + 98] × [startY, startY + 23.5]}，其中
 * {@code startX = guiWidth / 2 - 49 + castbarXOffset}、{@code startY = guiHeight - 96 + castbarYOffset}。
 * 因此"蓄力条右侧"= {@code startX + 98 + 间距}。
 * DS 把这两个偏移量暴露为 public static，直接读它们就能跟随玩家的自定义位置。
 *
 * <h2>读数区：两行</h2>
 * <ul>
 *     <li><b>第一行</b>：档位文本左对齐 + 百分比右对齐，两端顶在固定宽度的读数区上，
 *         因此档位位数变化时数字与进度条都<b>不会横向跳动</b>；</li>
 *     <li><b>第二行</b>：当前档位区间内的细进度条（{@link #WIDGET_WIDTH} × {@link #BAR_HEIGHT}）。</li>
 * </ul>
 * 两行合起来在蓄力条高度内垂直居中。
 *
 * <h2>第一行的两种形态</h2>
 * <ul>
 *     <li><b>自动跟随</b>（{@code charged}，以及 {@code optional_charged} 未滚动时）：
 *         只显示"将要释放的档位"一个数字 —— 与旧版表现一致；</li>
 *     <li><b>手动选档</b>（{@code optional_charged} 滚动后）：显示 {@code 选定/已达} 两个数字，
 *         选定用青蓝、已达用灰色，一眼能看出"要放几档、还能蓄到几档"。
 *         选定为 {@code 0}（取消）时整体转红。</li>
 * </ul>
 * 档位 {@code 0} 采用纯数字表达而不做本地化文案：位数固定、不会随语言改变读数区宽度，
 * 其"取消"语义由红色与选档时的低音提示共同承载。
 *
 * <h2>百分比与进度条表达的是"本档区间内的进度"</h2>
 * 不是"距离 {@code cast_time} 的总进度"（那是 DS 自己那条蓄力条在表达的），而是
 * <b>从"本档刚达成"到"下一档达成"之间的填充比例</b>，也就是"还差多久能升下一档 / 还能在低档停留多久"。
 * 区间长度逐档不同（取决于 {@code charged_duration_per_level}），所以按当次区间的实际长度归一化；
 * 未达标位 1 时区间取 {@code [0, 最低蓄力时长]}，正好回答"还差多久才可用"。
 * 已达自身最高档时没有"下一档"，条显示满格并整体转为金色，语义为"已无可蓄"。
 *
 * <h2>提示音</h2>
 * 档位每跨升一级播一次，音高随档位递增；只在客户端本地播放（属于"给自己听的档位提示"，
 * 不需要发给其他玩家）。滚轮选档时另有一声更短的点击音，见
 * {@code client.eventhandler.OptionalChargedScrollHandler}。
 */
public final class ChargedIndicatorLayer {
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "charged_indicator");

    /** 档位提升提示音，复用原版音符盒资源，不新增音频文件。 */
    private static final SoundEvent LEVEL_UP_SOUND = SoundEvents.NOTE_BLOCK_PLING.value();
    private static final float LEVEL_UP_VOLUME = 0.7F;
    private static final float LEVEL_UP_BASE_PITCH = 0.9F;
    private static final float LEVEL_UP_PITCH_PER_LEVEL = 0.12F;
    private static final float LEVEL_UP_MAX_PITCH = 2.0F;

    /** 蓄力条在屏幕上的宽度：196 的贴图经过 0.5 缩放后为 98。 */
    private static final int CAST_BAR_WIDTH = 98;
    /** 读数区与蓄力条右边缘的间距（GUI 缩放后的像素）。 */
    private static final int TEXT_GAP = 6;
    /** 蓄力条在屏幕上的实际高度（196×47 的贴图经 0.5 缩放后约 23.5，取整为 24），用于垂直居中。 */
    private static final int CAST_BAR_HEIGHT = 24;

    /**
     * 读数区的固定宽度。取固定值而不是随内容伸缩，是为了让档位位数变化时
     * 数字与进度条都不发生横向跳动。
     * <p>
     * 宽度需容得下最宽的一行：手动选档时的 {@code 12/15}（约 30px）+ 间距 + {@code 100%}（约 23px）。
     * 档位上限是玩家自身的升级等级，实际很少到 2 位数，这里按最坏情况留量。
     */
    private static final int WIDGET_WIDTH = 60;
    /** 进度提示条的厚度。 */
    private static final int BAR_HEIGHT = 3;
    /** 第一行文字与第二行进度条之间的间距。 */
    private static final int ROW_GAP = 2;

    /** 进度条底槽：半透明黑，保证在任意亮度的游戏画面上都能看见。 */
    private static final int COLOR_BAR_TRACK = 0x80000000;

    private static final int COLOR_BELOW_MINIMUM = 0xFF9E9E9E;
    private static final int COLOR_CHARGING = 0xFFFFFFFF;
    private static final int COLOR_MAXIMUM = 0xFFFFD700;
    /** 手动选定档位的颜色：青蓝，与"已达档位"的灰、自动跟随的白都不同。 */
    private static final int COLOR_SELECTED = 0xFF4FC3F7;
    /** 手动选档时"已达档位"的颜色：灰，作为参照而不是主体。 */
    private static final int COLOR_ACHIEVED_DIM = 0xFF9E9E9E;
    /** 选定为"取消"（档位 0）时的颜色。 */
    private static final int COLOR_CANCEL = 0xFFFF5252;

    /** 上一帧显示的"已达成档位"，用于判断"是否刚刚跨过一级"。仅渲染线程访问。 */
    private static int lastRenderedLevel = ChargeableActivation.NO_CHARGED_LEVEL;

    private ChargedIndicatorLayer() {
        // 图层与事件订阅类
    }

    @SubscribeEvent
    public static void registerLayers(final @NotNull RegisterGuiLayersEvent event) {
        event.registerAbove(MagicHUD.ID, ID, ChargedIndicatorLayer::render);
    }

    public static void render(final @NotNull GuiGraphics graphics, final @NotNull DeltaTracker tracker) {
        if (Minecraft.getInstance().options.hideGui) {
            reset();
            return;
        }

        Player player = Minecraft.getInstance().player;

        if (player == null || player.isSpectator() || !DragonStateProvider.isDragon(player)) {
            reset();
            return;
        }

        MagicData magic = MagicData.getData(player);

        if (!magic.isCasting()) {
            reset();
            return;
        }

        DragonAbilityInstance casting = magic.getCurrentlyCasting();

        if (casting == null || !(casting.value().activation() instanceof ChargeableActivation chargeable)) {
            reset();
            return;
        }

        int playerLevel = casting.level();

        // 与客户端松手拦截用同一套换算，因此这里显示的数字就是松手时实际会用的档位
        int level = chargeable.getChargedLevel(casting.getCurrentTick(), playerLevel);

        if (level > lastRenderedLevel) {
            playLevelUpSound(level);
        }

        lastRenderedLevel = level;

        // 可选性蓄力：读取滚轮选定的档位。同时完成"切换施法技能即清空选档"的对齐
        int selected = level;
        boolean manual = false;

        if (chargeable.selectsReleaseLevel()) {
            OptionalChargedSelection.onCasting(casting.key());
            selected = OptionalChargedSelection.displayLevel(level);
            manual = OptionalChargedSelection.isManual();
        }

        Minecraft instance = Minecraft.getInstance();
        int fontHeight = instance.font.lineHeight;
        int widgetLeft = graphics.guiWidth() / 2 - 49 + castbarOffsetX() + CAST_BAR_WIDTH + TEXT_GAP;
        int blockTop = graphics.guiHeight() - 96 + castbarOffsetY()
                + (CAST_BAR_HEIGHT - (fontHeight + ROW_GAP + BAR_HEIGHT)) / 2;
        int barTop = blockTop + fontHeight + ROW_GAP;
        int color = colorFor(selected, level, playerLevel, manual);

        // 用 partialTick 插值让条逐帧推进（与 DS 自己的蓄力条同一手法）；
        // 档位数字仍取整数，避免刚跨过阈值时数字来回跳
        float progress = progressOf(casting, chargeable, level, tracker.getGameTimeDeltaPartialTick(false));

        // 第二行：底槽 + 填充
        graphics.fill(widgetLeft, barTop, widgetLeft + WIDGET_WIDTH, barTop + BAR_HEIGHT, COLOR_BAR_TRACK);

        int filled = Math.round(WIDGET_WIDTH * progress);

        if (filled > 0) {
            graphics.fill(widgetLeft, barTop, widgetLeft + filled, barTop + BAR_HEIGHT, color);
        }

        // 第一行：档位左对齐、百分比右对齐，都顶在固定宽度的读数区两端
        int textRight = drawLevelText(graphics, instance, widgetLeft, blockTop, level, selected, manual, color);

        String percentage = Math.round(progress * 100.0F) + "%";
        int percentageLeft = Math.max(textRight, widgetLeft + WIDGET_WIDTH - instance.font.width(percentage));
        graphics.drawString(instance.font, percentage, percentageLeft, blockTop, color, true);
    }

    /**
     * 画第一行的档位文本。
     * <p>
     * 自动跟随时只画一个数字；手动选档时画 {@code 选定/已达}，两者分色，
     * 让"将要释放几档"与"已经蓄到几档"同时可读。
     *
     * @return 文本结束处的横坐标，供百分比避让
     */
    private static int drawLevelText(final @NotNull GuiGraphics graphics, final @NotNull Minecraft instance,
                                     final int left, final int top, final int achieved, final int selected,
                                     final boolean manual, final int color) {
        String selectedText = String.valueOf(manual ? selected : achieved);
        graphics.drawString(instance.font, selectedText, left, top, color, true);
        int cursor = left + instance.font.width(selectedText);

        if (!manual) {
            return cursor;
        }

        // 手动时把"已达档位"作为参照紧跟在选定值之后（灰），语义：选定值 / 已达值
        String achievedText = "/" + achieved;
        graphics.drawString(instance.font, achievedText, cursor, top, COLOR_ACHIEVED_DIM, true);

        return cursor + instance.font.width(achievedText);
    }

    /**
     * 当前档位区间内的进度：从"本档刚达成"到"下一档达成"之间的填充比例。
     * <p>
     * 区间长度逐档不同（取决于 {@code charged_duration_per_level}），因此按<b>当次区间的实际长度</b>归一化；
     * 未达标位 1 时区间取 {@code [0, 最低蓄力时长]}，正好回答"还差多久才可用"。
     *
     * @param casting    正在施法的技能实例，其 {@link DragonAbilityInstance#level()} 为玩家真实升级等级
     * @param chargeable 本次施法所用的蓄力档位激活类型
     * @param level      当前已达成的档位（{@link ChargeableActivation#NO_CHARGED_LEVEL} 表示未达最低蓄力）
     * @param partialTick 当前帧的部分刻，用于让进度条平滑推进
     * @return 0.0 ~ 1.0；已达自身最高档（或阈值被压缩成零长区间）时返回 1.0，语义为"已无可蓄"
     */
    private static float progressOf(final @NotNull DragonAbilityInstance casting, final @NotNull ChargeableActivation chargeable,
                                    final int level, final float partialTick) {
        int playerLevel = casting.level();

        // 已达自身最高档：没有"下一档"可指向，直接给满格（配合金色数字表示"已无可蓄"）
        if (level >= playerLevel) {
            return 1.0F;
        }

        // 等级 0 不能交给 LevelBasedValue 求值（lookup 类型会读到越界索引），直接从 0 起算
        int bandStart = level <= ChargeableActivation.NO_CHARGED_LEVEL ? 0 : chargeable.getRequiredChargeTicks(level);
        int bandLength = chargeable.getRequiredChargeTicks(level + 1) - bandStart;

        // 阈值被 clamp 到同一刻时区间长度为 0，避免除零
        if (bandLength <= 0) {
            return 1.0F;
        }

        return Mth.clamp((casting.getCurrentTick() + partialTick - bandStart) / bandLength, 0.0F, 1.0F);
    }

    /**
     * 档位文本与进度条的颜色。
     * <ul>
     *     <li>未达最低蓄力 → 灰；</li>
     *     <li>手动选档 → 选定为 0 时红（取消），否则青蓝；</li>
     *     <li>自动跟随 → 已达自身最高档时金，其余为白。</li>
     * </ul>
     *
     * @param displayed   当前显示的档位（手动时为选定值，自动时等于已达值）
     * @param achieved    当前已达档位
     * @param playerLevel 玩家自身的技能升级等级（即档位上限）
     * @param manual      是否处于手动选档状态
     */
    private static int colorFor(final int displayed, final int achieved, final int playerLevel, final boolean manual) {
        if (achieved <= ChargeableActivation.NO_CHARGED_LEVEL) {
            return COLOR_BELOW_MINIMUM;
        }

        if (manual) {
            return displayed <= ChargeableActivation.NO_CHARGED_LEVEL ? COLOR_CANCEL : COLOR_SELECTED;
        }

        return achieved >= playerLevel ? COLOR_MAXIMUM : COLOR_CHARGING;
    }

    private static void playLevelUpSound(final int level) {
        float pitch = Math.min(LEVEL_UP_BASE_PITCH + LEVEL_UP_PITCH_PER_LEVEL * level, LEVEL_UP_MAX_PITCH);
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(LEVEL_UP_SOUND, pitch, LEVEL_UP_VOLUME));
    }

    /** 离开蓄力状态时清空，下一次蓄力从 0 重新逐级提示；同时清掉可选性蓄力的选档状态。 */
    private static void reset() {
        lastRenderedLevel = ChargeableActivation.NO_CHARGED_LEVEL;
        OptionalChargedSelection.reset();
    }

    private static int castbarOffsetX() {
        return MagicHUD.castbarXOffset == null ? 0 : MagicHUD.castbarXOffset;
    }

    private static int castbarOffsetY() {
        return MagicHUD.castbarYOffset == null ? 0 : MagicHUD.castbarYOffset;
    }
}
