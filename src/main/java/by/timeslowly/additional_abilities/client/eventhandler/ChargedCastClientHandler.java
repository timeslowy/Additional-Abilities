package by.timeslowly.additional_abilities.client.eventhandler;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.config.ClientConfig;
import by.dragonsurvivalteam.dragonsurvival.input.Keybind;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.common.network.ChargedReleasePayload;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargedActivation;
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
 * 蓄力档位激活类型的<b>松手拦截</b>（仅客户端加载）。
 *
 * <h2>为什么必须抢在 DS 之前</h2>
 * DS 的 {@code ClientCastingHandler} 同样挂在 {@link InputEvent.Key} /
 * {@link InputEvent.MouseButton.Pre} 上（默认优先级），松手时它只要看到
 * {@code MagicData#isCasting()} 为 true 就会"本地停手 + 发 SyncStopCast"，
 * 也就是把这次施法当成取消。而蓄力技能需要的是"按档位释放"。
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
 * <h2>不拦截的情形</h2>
 * <ul>
 *     <li>正在施法的技能不是蓄力档位类型 → 完全不干预，交给 DS 原生处理；</li>
 *     <li>蓄力未达最低蓄力时长 → 也完全不干预，让 DS 走原生"提前松手 = 取消施法（无冷却）"
 *         —— 注意这里<b>不能</b>替 DS 停手，否则 SyncStopCast 不会发出，
 *         服务端会继续读条直到蓄满并自动释放；</li>
 *     <li>已经蓄满 → 客户端自己的 {@code tickActions} 早已停手，{@code isCasting()} 为 false，
 *         本类在入口就返回了。</li>
 * </ul>
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID, value = Dist.CLIENT)
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

        if (casting == null || !(casting.value().activation() instanceof ChargedActivation charged)) {
            return;
        }

        // 复刻 DS 的按键判定：只有"当前选中槽位对应的技能键"才算施法键
        if (!abilityKey(magic.getSelectedAbilitySlot()).isReleased(input)) {
            return;
        }

        int chargeTicks = casting.getCurrentTick();
        int chargedLevel = charged.getChargedLevel(chargeTicks, casting.level());

        // 未达最低蓄力时长：不干预，让 DS 走原生取消路径（它会发 SyncStopCast）
        if (chargedLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            return;
        }

        // 先发释放请求：服务端据此按档位执行一次技能动作
        PacketDistributor.sendToServer(new ChargedReleasePayload(casting.key(), chargeTicks));

        // 再本地收尾。这里刻意复用 DS 的原生路径，而不是自己拼冷却与动画：
        // 把等级临时换成档位，release 内部的 getCooldown(level) 才会算出与服务端一致的冷却
        int realLevel = casting.level();
        casting.setLevel(chargedLevel);

        try {
            // withCooldown = true -> 结束音效 + 结束动画 + 按档位的冷却 + isCasting 置 false
            magic.stopCasting(player, casting, true);
        } finally {
            casting.setLevel(realLevel);
        }
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
