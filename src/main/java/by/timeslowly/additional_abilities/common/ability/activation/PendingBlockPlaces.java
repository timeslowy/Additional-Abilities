package by.timeslowly.additional_abilities.common.ability.activation;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.timeslowly.additional_abilities.common.ability.activation.PendingBlockPlaceData.Pending;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger.OnBlockPlaced;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 「放置方块」触发器的两阶段适配器：<b>登记</b>候选放置，等事件派发结束后<b>确认</b>
 * 最终未被取消才分发技能。
 *
 * <h2>两阶段为什么必须拆开</h2>
 * 放置事件在 {@code EventBus#post(...)} 内部逐监听器派发，本模组的监听器被调用时
 * <b>无法知道</b>排在后面的监听器会不会取消它（同优先级之间没有顺序承诺）。
 * 因此第一阶段只把放置登记进 {@link PendingBlockPlaceData}，第二阶段在下一个
 * {@code LevelTickEvent.Post} 读事件的<b>最终</b>取消状态，未取消才执行技能。
 * 完整的推理与代价（最多延迟 1 个服务端刻）见 {@link PendingBlockPlaceData} 的类注释。
 *
 * <h2>为什么确认点选 {@code LevelTickEvent.Post}</h2>
 * <ul>
 *     <li>玩家放置方块走的是 {@code ServerGamePacketListenerImpl#handleUseItemOn}，
 *         而它在 {@code MinecraftServer#tickChildren} 里由 {@code ServerConnectionListener#tick}
 *         驱动 —— 位置在<b>所有维度的 {@code ServerLevel#tick} 之后</b>。因此本刻登记的事件，
 *         必然要等到<b>下一个</b>维度刻才被确认，"事件已派发完毕"这一点是结构上成立的；</li>
 *     <li>按维度触发天然与"队列挂在 Level 附件上"的数据布局对齐，不需要再枚举所有维度；</li>
 *     <li>它在客户端也会触发（{@code ClientLevel}），故必须 {@code instanceof ServerLevel} 过滤。</li>
 * </ul>
 *
 * <h2>确认时的三个跳过条件</h2>
 * <ol>
 *     <li><b>最终被取消</b> —— 这次放置根本没发生（别的模组拒绝了），技能不得触发；</li>
 *     <li><b>玩家已离线</b> —— 拿不到 {@code ServerPlayer}，效果回调无法执行；</li>
 *     <li><b>玩家已换维度</b> —— 本维度队列里的放置属于原维度，玩家换了维度后其技能不应在本维度结算
 *         （与 {@code DomainInstance#resolveCaster} 同一口径）。</li>
 * </ol>
 * 三种情况都<b>只跳过、不做补偿</b>：这次放置就是没触发，不重试、不延后。
 */
public final class PendingBlockPlaces {
    private PendingBlockPlaces() { /* 工具类 */ }

    /**
     * 第一阶段：登记一次玩家放置。<b>不执行任何技能</b>。
     * <p>
     * 守卫顺序与 {@code on_block_break} 一致：非玩家 / 非龙直接丢弃。把守卫放在登记侧而不是确认侧，
     * 可以让绝大多数与龙无关的放置根本不进队列。
     */
    public static void enqueue(final BlockEvent.@NotNull EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        DragonStateHandler handler = DragonStateProvider.getData(player);

        if (!handler.isDragon()) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // 坐标与方块状态在此刻复制：确认时读到的就是"放置那一刻"的值，
        // 不会因为之后世界变化（别的模组改了这块地）而漂移
        level.getData(AAAttachments.PENDING_PLACE)
                .add(new Pending(player.getUUID(), event.getPos().immutable(), event.getState(), event));
    }

    /**
     * 第二阶段：确认本维度上一刻登记的放置，对最终未被取消的那些分发技能。
     * <p>
     * 只读探测附件（{@code getExistingDataOrNull}）：本监听器对每个维度每刻都跑，
     * {@code getData} 会顺手把默认值建出来并永久挂在 Level 上 —— 从没放过方块的维度
     * 不该因此长期带着一个空队列。
     */
    public static void confirm(final @NotNull LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        PendingBlockPlaceData data = level.getExistingDataOrNull(AAAttachments.PENDING_PLACE);

        if (data == null || data.isEmpty()) {
            return;
        }

        // 先取出并清空，再逐个确认：确认会执行技能效果，可能反过来触发新放置并入队，
        // 那些属于"下一刻"的登记，不应在本次遍历中被处理（见 PendingBlockPlaceData#drain）
        List<Pending> drained = data.drain();

        for (Pending entry : drained) {
            if (entry.event().isCanceled()) {
                continue;
            }

            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.caster());

            if (player == null || player.level() != level) {
                continue;
            }

            OnBlockPlaced.dispatch(player, entry.position(), entry.state());
        }
    }
}
