package by.timeslowly.additional_abilities.client.hud;

import by.dragonsurvivalteam.dragonsurvival.client.gui.hud.MagicHUD;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargedActivation;
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
 * 蓄力档位数字提示（仅客户端加载）。
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
 *     <li><b>第一行</b>：{@code 档位} 左对齐 + {@code 百分比} 右对齐，两端顶在固定宽度的读数区上，
 *         因此档位从 1 位数进位到 2 位数时数字与进度条都<b>不会横向跳动</b>；</li>
 *     <li><b>第二行</b>：当前档位区间内的细进度条（{@link #WIDGET_WIDTH} × {@link #BAR_HEIGHT}）。</li>
 * </ul>
 * 两行合起来在蓄力条高度内垂直居中。
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
 * 不需要发给其他玩家）。全部参数都是常量，方便后续微调。
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
     * 读数区的固定宽度。取固定值而不是随内容伸缩，是为了让档位从 1 位数变 2 位数时
     * 数字与进度条都不发生横向跳动。
     * <p>
     * 宽度需容得下最宽的一行：3 位档位（约 18px）+ 间距 + {@code 100%}（约 23px）。
     * 档位上限是玩家自身的升级等级，实际很少到 3 位，这里按最坏情况留量。
     */
    private static final int WIDGET_WIDTH = 44;
    /** 进度提示条的厚度。 */
    private static final int BAR_HEIGHT = 3;
    /** 第一行文字与第二行进度条之间的间距。 */
    private static final int ROW_GAP = 2;

    /** 进度条底槽：半透明黑，保证在任意亮度的游戏画面上都能看见。 */
    private static final int COLOR_BAR_TRACK = 0x80000000;

    private static final int COLOR_BELOW_MINIMUM = 0xFF9E9E9E;
    private static final int COLOR_CHARGING = 0xFFFFFFFF;
    private static final int COLOR_MAXIMUM = 0xFFFFD700;

    /** 上一帧显示的档位，用于判断"是否刚刚跨过一级"。仅渲染线程访问。 */
    private static int lastRenderedLevel = ChargedActivation.NO_CHARGED_LEVEL;

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

        if (casting == null || !(casting.value().activation() instanceof ChargedActivation charged)) {
            reset();
            return;
        }

        // 与客户端松手拦截用同一套换算，因此这里显示的数字就是松手时实际会用的档位
        int level = charged.getChargedLevel(casting.getCurrentTick(), casting.level());

        if (level > lastRenderedLevel) {
            playLevelUpSound(level);
        }

        lastRenderedLevel = level;

        Minecraft instance = Minecraft.getInstance();
        int fontHeight = instance.font.lineHeight;
        int widgetLeft = graphics.guiWidth() / 2 - 49 + castbarOffsetX() + CAST_BAR_WIDTH + TEXT_GAP;
        int blockTop = graphics.guiHeight() - 96 + castbarOffsetY()
                + (CAST_BAR_HEIGHT - (fontHeight + ROW_GAP + BAR_HEIGHT)) / 2;
        int barTop = blockTop + fontHeight + ROW_GAP;
        int color = colorFor(level, casting.level());

        // 用 partialTick 插值让条逐帧推进（与 DS 自己的蓄力条同一手法）；
        // 档位数字仍取整数，避免刚跨过阈值时数字来回跳
        float progress = progressOf(casting, charged, level, tracker.getGameTimeDeltaPartialTick(false));

        // 第二行：底槽 + 填充
        graphics.fill(widgetLeft, barTop, widgetLeft + WIDGET_WIDTH, barTop + BAR_HEIGHT, COLOR_BAR_TRACK);

        int filled = Math.round(WIDGET_WIDTH * progress);

        if (filled > 0) {
            graphics.fill(widgetLeft, barTop, widgetLeft + filled, barTop + BAR_HEIGHT, color);
        }

        // 第一行：档位左对齐、百分比右对齐，都顶在固定宽度的读数区两端
        graphics.drawString(instance.font, String.valueOf(level), widgetLeft, blockTop, color, true);

        String percentage = Math.round(progress * 100.0F) + "%";
        graphics.drawString(instance.font, percentage,
                widgetLeft + WIDGET_WIDTH - instance.font.width(percentage), blockTop, color, true);
    }

    /**
     * 当前档位区间内的进度：从"本档刚达成"到"下一档达成"之间的填充比例。
     * <p>
     * 区间长度逐档不同（取决于 {@code charged_duration_per_level}），因此按<b>当次区间的实际长度</b>归一化；
     * 未达标位 1 时区间取 {@code [0, 最低蓄力时长]}，正好回答"还差多久才可用"。
     *
     * @param casting    正在施法的技能实例，其 {@link DragonAbilityInstance#level()} 为玩家真实升级等级
     * @param charged    本次施法所用的蓄力档位激活类型
     * @param level      当前已达成的档位（{@link ChargedActivation#NO_CHARGED_LEVEL} 表示未达最低蓄力）
     * @param partialTick 当前帧的部分刻，用于让进度条平滑推进
     * @return 0.0 ~ 1.0；已达自身最高档（或阈值被压缩成零长区间）时返回 1.0，语义为"已无可蓄"
     */
    private static float progressOf(final @NotNull DragonAbilityInstance casting, final @NotNull ChargedActivation charged,
                                    final int level, final float partialTick) {
        int playerLevel = casting.level();

        // 已达自身最高档：没有"下一档"可指向，直接给满格（配合金色数字表示"已无可蓄"）
        if (level >= playerLevel) {
            return 1.0F;
        }

        // 等级 0 不能交给 LevelBasedValue 求值（lookup 类型会读到越界索引），直接从 0 起算
        int bandStart = level <= ChargedActivation.NO_CHARGED_LEVEL ? 0 : charged.getRequiredChargeTicks(level);
        int bandLength = charged.getRequiredChargeTicks(level + 1) - bandStart;

        // 阈值被 clamp 到同一刻时区间长度为 0，避免除零
        if (bandLength <= 0) {
            return 1.0F;
        }

        return Mth.clamp((casting.getCurrentTick() + partialTick - bandStart) / bandLength, 0.0F, 1.0F);
    }

    /** 未达最低蓄力用灰、已达上限用金、其余为白。 */
    private static int colorFor(final int level, final int playerLevel) {
        if (level <= ChargedActivation.NO_CHARGED_LEVEL) {
            return COLOR_BELOW_MINIMUM;
        }

        return level >= playerLevel ? COLOR_MAXIMUM : COLOR_CHARGING;
    }

    private static void playLevelUpSound(final int level) {
        float pitch = Math.min(LEVEL_UP_BASE_PITCH + LEVEL_UP_PITCH_PER_LEVEL * level, LEVEL_UP_MAX_PITCH);
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(LEVEL_UP_SOUND, pitch, LEVEL_UP_VOLUME));
    }

    /** 离开蓄力状态时清空，下一次蓄力从 0 重新逐级提示。 */
    private static void reset() {
        lastRenderedLevel = ChargedActivation.NO_CHARGED_LEVEL;
    }

    private static int castbarOffsetX() {
        return MagicHUD.castbarXOffset == null ? 0 : MagicHUD.castbarXOffset;
    }

    private static int castbarOffsetY() {
        return MagicHUD.castbarYOffset == null ? 0 : MagicHUD.castbarYOffset;
    }
}
