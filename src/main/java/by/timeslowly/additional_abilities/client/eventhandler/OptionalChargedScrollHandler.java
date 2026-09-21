package by.timeslowly.additional_abilities.client.eventhandler;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.OptionalChargedSelection;
import by.timeslowly.additional_abilities.common.network.OptionalChargedSelectionPayload;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * 可选性蓄力档位（{@code additional_abilities:optional_charged}）的<b>滚轮选档</b>（仅客户端加载）。
 *
 * <h2>交互</h2>
 * 按住施法键蓄力期间，鼠标滚轮在 {@code [0, 当前已达成档位]} 内调整"将要释放的档位"：
 * 上滚 +1、下滚 -1；{@code 0} 表示"蓄力后取消，不释放"。未滚动时自动跟随已达档位，
 * 行为与 {@code additional_abilities:charged} 一致（详见 {@link OptionalChargedSelection}）。
 *
 * <h2>为什么必须吞掉这次滚动</h2>
 * {@link InputEvent.MouseScrollingEvent} 在<b>原版处理之前</b>触发。若不取消，
 * 同一次滚动会顺带切换快捷栏物品 —— 表现为"档位变了，手里的东西也换了"。
 * 因此只要处于"可选性蓄力且已达成档位 ≥ 1"的状态，就无条件 {@code setCanceled(true)}。
 * <p>
 * 有意为之的副作用：本技能蓄力期间滚轮不再切换快捷栏物品。这是"滚轮被技能占用"的预期语义；
 * 未达最低蓄力时（此时没有可选项）不接管，滚轮恢复原版行为。
 * <p>
 * 其他技能（包括 {@code additional_abilities:charged}）完全不干预。
 *
 * <h2>为什么在这里直接改客户端状态</h2>
 * 选定档位是纯客户端交互状态，服务端不需要它也能正确结算 ——
 * 真正生效的档位随 {@code ChargedReleasePayload} 单独上报，并由服务端重新校验是否超过已达成档位。
 * 这里额外同步一次到服务端，只为让 {@code /dragon-ability query … current_selected_level} 可读，
 * 因此<b>只在档位真正变化时发送</b>（一次施法内至多几次）。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID, value = Dist.CLIENT)
public final class OptionalChargedScrollHandler {
    /** 选档反馈音的音量。 */
    private static final float SELECT_VOLUME = 0.6F;
    /** 选定为"取消"（档位 0）时的音高：明显低于正常选档，形成可辨识的区分。 */
    private static final float CANCEL_PITCH = 0.6F;
    /** 正常选档的音高基准与逐档增量，随档位升高而升高。 */
    private static final float SELECT_BASE_PITCH = 1.0F;
    private static final float SELECT_PITCH_PER_LEVEL = 0.08F;
    private static final float SELECT_MAX_PITCH = 1.8F;

    private OptionalChargedScrollHandler() {
        // 事件订阅类
    }

    @SubscribeEvent
    public static void onMouseScroll(final @NotNull InputEvent.MouseScrollingEvent event) {
        double delta = event.getScrollDeltaY();

        if (delta == 0.0) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // 与 DS 相同的入口条件：有界面打开、或不是龙形态时不参与
        if (minecraft.screen != null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        Player player = minecraft.player;

        if (player.isSpectator() || !DragonStateProvider.isDragon(player)) {
            return;
        }

        MagicData magic = MagicData.getData(player);

        if (!magic.isCasting()) {
            OptionalChargedSelection.reset();
            return;
        }

        DragonAbilityInstance casting = magic.getCurrentlyCasting();

        if (casting == null
                || !(casting.value().activation() instanceof ChargeableActivation chargeable)
                || !chargeable.selectsReleaseLevel()) {
            return;
        }

        OptionalChargedSelection.onCasting(casting.key());

        // 以"已蓄力时长"换算已达档位，与客户端松手拦截、HUD 用同一套规则
        int achieved = chargeable.getChargedLevel(casting.getCurrentTick(), casting.level());

        // 未达最低蓄力时没有可选项：不接管，滚轮照常切换快捷栏物品
        if (achieved < 1) {
            return;
        }

        boolean changed = OptionalChargedSelection.adjust(delta > 0.0 ? 1 : -1, achieved);

        // 无论是否真的变化都吞掉这次滚动：避免出现"有时切物品栏、有时不切"的不可预期手感
        event.setCanceled(true);

        if (!changed) {
            return;
        }

        int selected = OptionalChargedSelection.displayLevel(achieved);
        playSelectSound(selected);

        // 仅供查询指令展示；真正生效的档位走 ChargedReleasePayload
        PacketDistributor.sendToServer(new OptionalChargedSelectionPayload(
                casting.key(), OptionalChargedSelection.rawForSync()));
    }

    /** 档位 0（取消）用低音，其余档位音高随档位递增。仅本地播放（"给自己听的选档提示"）。 */
    private static void playSelectSound(final int selected) {
        float pitch = selected < 1
                ? CANCEL_PITCH
                : Math.min(SELECT_BASE_PITCH + SELECT_PITCH_PER_LEVEL * selected, SELECT_MAX_PITCH);

        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, SELECT_VOLUME));
    }
}
