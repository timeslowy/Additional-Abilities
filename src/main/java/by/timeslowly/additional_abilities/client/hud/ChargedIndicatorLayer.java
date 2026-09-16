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
 * 因此"蓄力条右侧"= {@code startX + 98 + 间距}，垂直居中 = {@code startY + 12} 再减去半行高。
 * DS 把这两个偏移量暴露为 public static，直接读它们就能跟随玩家的自定义位置。
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
    /** 数字与蓄力条右边缘的间距（GUI 缩放后的像素）。 */
    private static final int TEXT_GAP = 6;
    /** 蓄力条高度 23.5 的一半，用于垂直居中。 */
    private static final int CAST_BAR_HALF_HEIGHT = 12;

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
        int x = graphics.guiWidth() / 2 - 49 + castbarOffsetX() + CAST_BAR_WIDTH + TEXT_GAP;
        int y = graphics.guiHeight() - 96 + castbarOffsetY() + CAST_BAR_HALF_HEIGHT - fontHeight / 2;

        graphics.drawString(instance.font, String.valueOf(level), x, y, colorFor(level, casting.level()), true);
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
