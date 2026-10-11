package by.timeslowly.additional_abilities.common.ability.activation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一个维度内<b>待确认的方块放置</b>，承载在 {@code Level} 的数据附件上
 * （{@code AAAttachments.PENDING_PLACE}），由 {@link PendingBlockPlaces} 挂在
 * {@code LevelTickEvent.Post} 上确认后分发。
 *
 * <h2>为什么需要"待确认"这一步（F21）</h2>
 * {@code BlockEvent.EntityPlaceEvent} 是在 {@code EventBus#post(...)} <b>内部</b>逐监听器派发的。
 * 因此在本模组的监听器被调用时，<b>"最终会不会被取消"还不知道</b> —— 排在本模组之后的监听器
 * （同优先级或更低优先级）随时可能取消这次放置。若此刻就分发技能，就会出现
 * 「技能已经触发过，方块却被别的模组拒绝、根本没放下」。
 * <p>
 * 所以这里把放置<b>登记</b>下来，等事件派发结束后再确认并分发：
 * <pre>
 * EntityPlaceEvent 派发中  → 登记（不执行技能）
 * ……其余监听器运行，可能取消……
 * 下一个 LevelTickEvent.Post → 读最终取消状态 → 未取消才分发
 * </pre>
 * 代价是技能比放置晚 <b>最多 1 个服务端刻</b>（约 50ms）执行。对被动技能而言不可感知，
 * 且这正是「只响应最终未取消的放置」（C09 已确认）所必须付出的代价。
 * <p>
 * <b>为什么不直接把监听器优先级改成 LOWEST</b>：那只保证晚于优先级更高的监听器，
 * <b>无法</b>保证晚于同优先级的其他模组 —— 同优先级之间没有顺序承诺，缺陷只是变窄、没有消失。
 *
 * <h2>为什么队列挂在 Level 附件上</h2>
 * 与 {@code BlockGlowData} / {@code BlockQuakeData} / {@code DomainData} 同因：这是"按维度"的
 * 服务端状态，用静态 {@code Map<ResourceKey<Level>, …>} 会在换存档后串味（键是维度键而非 Level 实例）。
 * 挂到附件上后队列随 Level 生灭，跨存档结构上不可能残留。
 * <p>
 * <b>刻意不做序列化</b>：队列活不过一个服务端刻，没有跨重启的意义。
 *
 * <h2>为什么要持有事件对象</h2>
 * 确认"最终取消状态"的唯一可靠来源就是事件本身（{@code ICancellableEvent#isCanceled()} 读的是
 * 事件对象上的一个字段，派发结束后即为终值）。因此 {@link Pending} 保留事件引用，但
 * <b>只用于读这一个布尔值</b>：坐标、方块状态在登记时已复制，玩家在确认时按 UUID 重新解析 ——
 * 这样"玩家已离线 / 已换维度"两个跳过条件才成立（见 {@link PendingBlockPlaces}）。
 * 事件对象只被持有到下一个维度刻，且其内部对 Level 是弱引用，不构成泄漏。
 */
public class PendingBlockPlaceData {
    /** 本维度待确认的放置，按登记顺序排列。正常情况下一个服务端刻内即被清空 */
    private final List<Pending> pending = new ArrayList<>();

    /**
     * 一次待确认的放置。
     * <p>
     * {@code position} 与 {@code state} 都是登记当时复制下来的不可变值（{@code BlockState} 本身不可变，
     * {@code BlockPos} 已 {@code immutable()}），因此确认时拿到的就是"放置那一刻"的坐标与方块状态，
     * 不受之后世界变化影响 —— 这也让战利品上下文里的 {@code block_state} 与玩家当时的操作一致。
     *
     * @param event 只用于在确认时读最终取消状态，见类注释
     */
    record Pending(@NotNull UUID caster, @NotNull BlockPos position, @NotNull BlockState state,
                   @NotNull BlockEvent.EntityPlaceEvent event) {}

    /** 供 {@link PendingBlockPlaces} 登记 */
    void add(final @NotNull Pending entry) {
        pending.add(entry);
    }

    /** 队列是否为空（逐刻早退判据） */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /**
     * 取出本刻要确认的全部登记，并清空队列。
     * <p>
     * 先复制再清空（而不是边遍历边删）：确认过程会执行技能效果，那些效果经 {@code run_function}
     * 完全可能反过来触发新的放置、往本队列里追加 —— 直接遍历活列表会抛
     * {@code ConcurrentModificationException}，而"本刻新登记的"应当留到下一刻确认。
     */
    @NotNull List<Pending> drain() {
        List<Pending> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }
}
