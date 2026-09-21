package by.timeslowly.additional_abilities.client.eventhandler;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.handlers.magic.ManaHandler;
import by.dragonsurvivalteam.dragonsurvival.config.ClientConfig;
import by.dragonsurvivalteam.dragonsurvival.input.Keybind;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.OptionalChargedSelection;
import by.timeslowly.additional_abilities.common.network.ChargedReleasePayload;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * 蓄力档位族系激活类型的<b>松手拦截</b>（仅客户端加载），同时服务
 * {@code additional_abilities:charged}（松手按已达成档位释放）与
 * {@code additional_abilities:optional_charged}（松手按滚轮选定档位释放，可为取消）。
 *
 * <h2>为什么必须抢在 DS 之前</h2>
 * DS 的 {@code ClientCastingHandler} 同样挂在 {@link InputEvent.Key} /
 * {@link InputEvent.MouseButton.Pre} 上（默认优先级），松手时它只要看到
 * {@code MagicData#isCasting()} 为 true 就会"本地停手 + 发 SyncStopCast"，
 * 也就是把这次施法当成取消。而蓄力技能需要的是"按档位释放"（或按选定档位取消）。
 * <p>
 * 因此本类以 {@link EventPriority#HIGHEST} 抢跑，并在处理松手前先调用
 * {@code MagicData#stopCasting} 把 {@code isCasting} 置为 false ——
 * 随后 DS 的处理器会因为 {@code isCasting() == false} 直接跳过，
 * <b>不再发出 SyncStopCast</b>。于是服务端的"停 / 放"完全由本模组的
 * {@link ChargedReleasePayload} 决定。
 * <p>
 * 这个顺序还顺带解决了一个连带问题：服务端 {@code SyncStopCast.handleServer} 在
 * {@code isApplyingEffects()} 为 false（蓄力中就是 false）时会下发
 * {@code StopAbilityAnimation}，把刚播上的结束动画立刻掐掉。不发这个包就没有这个问题。
 * <p>
 * 两类包走的是同一条连接，按发送先后到达，因此顺序确定（先发先到）。
 *
 * <h2>三条分支</h2>
 * <table border="1">
 *     <tr><th>分支</th><th>条件</th><th>行为</th></tr>
 *     <tr><td>直通</td><td>已达成档位 &lt; 1</td>
 *         <td>不干预，交回 DS 原生"提前松手 = 取消施法（无冷却）"路径
 *             —— 注意这里<b>不能</b>替 DS 停手，否则 SyncStopCast 不会发出，
 *             服务端会继续读条直到蓄满并自动释放</td></tr>
 *     <tr><td>释放</td><td>解析出的释放档位 &ge; 1</td>
 *         <td>发释放包 + 本地补齐"读条完成瞬间"的两端动作（起始音效 / 扣初始魔力），
 *             再走 DS 原生收尾</td></tr>
 *     <tr><td>取消</td><td>释放档位 == 0（仅 {@code optional_charged} 可到达）</td>
 *         <td>发取消包 + 本地 {@code stopCasting(…, false)}：不进冷却、不扣魔力、不播结束音效</td></tr>
 * </table>
 * 取消分支为什么不能交给 DS，见 {@code common.ability.ChargedCasts} 的类注释
 * （{@code isApplyingEffects()} 在按过 {@code cast_time} 后提前为 true，DS 原生路径会据此误判）。
 *
 * <h2>为什么还要在这里扣一次法力</h2>
 * DS 会在"读条完成瞬间"（{@code tickActions} 里 {@code currentTick == castTime} 那段）执行
 * 「起始音效 + 扣初始魔力」，而那段代码<b>两端都会跑</b>（不像动作那样只跑服务端）。
 * 我们的提前释放绕过了它，于是必须自己补 —— 且必须<b>两端都补</b>：
 * {@code MagicData} 是数据附件，客户端与服务端各持一份副本，而 DS <b>不逐刻同步法力</b>
 * （源码里就留着 {@code FIXME :: Mana may still be out of sync by about ~0.03} 的注释）。
 * 只扣服务端那份的话，客户端始终是"满蓝" —— HUD 上法力永远不下降，而且因为一直满蓝连回蓝都不触发，
 * 永远追不回来（只有指令/升级等偶发全量同步时才会突然掉一截）。
 * 服务端那份由 {@code ChargedCasts#fire} 扣，这里负责客户端这份。
 *
 * <h2>不拦截的情形</h2>
 * <ul>
 *     <li>正在施法的技能不属于蓄力档位族系 → 完全不干预，交给 DS 原生处理；</li>
 *     <li>已达成档位 &lt; 1 → 同上（走原生取消路径）；</li>
 *     <li>已经蓄满 → 客户端自己的 {@code tickActions} 早已停手，{@code isCasting()} 为 false，
 *         本类在入口就返回了。</li>
 * </ul>
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID, value = Dist.CLIENT)
public final class ChargedCastClientHandler {
    /** 与 DS {@code ClientCastingHandler} 中的槽位键位表保持一致。 */
    private static final Keybind[] SLOT_KEYBINDS = {
            Keybind.ABILITY1,
            Keybind.ABILITY2,
            Keybind.ABILITY3,
            Keybind.ABILITY4
    };

    private ChargedCastClientHandler() {
        // 事件订阅类
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseInput(final @NotNull InputEvent.MouseButton.Pre event) {
        handleRelease(InputConstants.Type.MOUSE.getOrCreate(event.getButton()), event.getAction());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyInput(final @NotNull InputEvent.Key event) {
        handleRelease(InputConstants.getKey(event.getKey(), event.getScanCode()), event.getAction());
    }

    private static void handleRelease(final InputConstants.Key input, final int action) {
        if (action != InputConstants.RELEASE) {
            return;
        }

        Minecraft instance = Minecraft.getInstance();

        // 与 DS 相同的入口条件：有界面打开、或不是龙形态时不参与
        if (instance.screen != null || instance.player == null || instance.level == null) {
            return;
        }

        Player player = instance.player;

        if (player.isSpectator() || !DragonStateProvider.isDragon(player)) {
            return;
        }

        MagicData magic = MagicData.getData(player);

        if (!magic.isCasting()) {
            return;
        }

        DragonAbilityInstance casting = magic.getCurrentlyCasting();

        if (casting == null || !(casting.value().activation() instanceof ChargeableActivation chargeable)) {
            return;
        }

        // 复刻 DS 的按键判定：只有"当前选中槽位对应的技能键"才算施法键
        if (!abilityKey(magic.getSelectedAbilitySlot()).isReleased(input)) {
            return;
        }

        int chargeTicks = casting.getCurrentTick();
        int achievedLevel = chargeable.getChargedLevel(chargeTicks, casting.level());

        // 未达最低蓄力时长：不干预，让 DS 走原生取消路径（它会发 SyncStopCast）
        if (achievedLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            OptionalChargedSelection.reset();
            return;
        }

        // 释放档位：可选性蓄力用滚轮选定的档位（0 = 取消），否则沿用"已达成档位"
        int releaseLevel = ChargedReleasePayload.AUTO;

        if (chargeable.selectsReleaseLevel()) {
            OptionalChargedSelection.onCasting(casting.key());
            releaseLevel = OptionalChargedSelection.resolveReleaseLevel(achievedLevel);
        }

        // 先发请求：服务端据此按档位执行一次技能动作，或按请求取消
        PacketDistributor.sendToServer(new ChargedReleasePayload(casting.key(), chargeTicks, releaseLevel));

        if (releaseLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            cancelLocally(player, magic, casting);
            return;
        }

        releaseLocally(player, magic, casting, chargeable, releaseLevel);
    }

    /**
     * 取消施法（仅 {@code optional_charged} 的"选定档位 = 0"会走到这里）。
     * <p>
     * 必须由本模组显式 {@code stopCasting(…, false)}：DS 的原生停手路径会依据
     * {@code isApplyingEffects()} 决定是否进冷却，而按过 {@code cast_time} 之后该判定提前为 true，
     * 照搬会让"取消"凭空产生冷却与结束音效。服务端侧由 {@code ChargedCasts#fire} 对称处理。
     */
    private static void cancelLocally(final @NotNull Player player, final @NotNull MagicData magic,
                                      final @NotNull DragonAbilityInstance casting) {
        // withCooldown = false：不进冷却、不扣初始魔力、不播结束音效与结束动画
        magic.stopCasting(player, casting, false);
        OptionalChargedSelection.reset();
    }

    /**
     * 按档位释放：本地补齐 DS「读条完成瞬间」两端都会执行的那两件事，再走 DS 原生收尾。
     * <p>
     * 这里刻意复用 DS 的原生路径，而不是自己拼冷却与动画：
     * 把等级临时换成档位，{@code release} 内部的 {@code getCooldown(level)} 与服务端的初始魔力消耗
     * 才会算出与服务端一致的数值。
     */
    private static void releaseLocally(final @NotNull Player player, final @NotNull MagicData magic,
                                       final @NotNull DragonAbilityInstance casting,
                                       final @NotNull ChargeableActivation chargeable, final int releaseLevel) {
        int realLevel = casting.level();
        casting.setLevel(releaseLevel);

        try {
            // ① 起始音效 —— 服务端那一路 playSound 会排除施法者本人，所以本地的这一次不会与它重复
            chargeable.playStartAndLoopingSound(player, casting);

            // ② 扣初始魔力 —— consumeMana 只改本端 MagicData 的 currentMana，而 DS 不逐刻同步法力，
            //    若只在服务端扣，玩家 HUD 上的法力将永远不下降（且因始终"满蓝"连回蓝都不触发，
            //    永远追不回来），表现就是"施法不消耗法力"
            ManaHandler.consumeMana(player, chargeable.getInitialManaCost(releaseLevel));

            // withCooldown = true -> 结束音效 + 结束动画 + 按档位的冷却 + isCasting 置 false
            magic.stopCasting(player, casting, true);
        } finally {
            casting.setLevel(realLevel);
        }

        OptionalChargedSelection.reset();
    }

    /**
     * 当前选中槽位对应的施法键，与 DS {@code ClientCastingHandler#getKey} 保持一致：
     * 交替施法模式下为各自的技能键，否则统一为"使用技能"键。
     */
    private static @NotNull Keybind abilityKey(final int slot) {
        if (Boolean.TRUE.equals(ClientConfig.alternateCastMode)
                && slot >= 0 && slot < SLOT_KEYBINDS.length) {
            return SLOT_KEYBINDS[slot];
        }

        return Keybind.USE_ABILITY;
    }
}
