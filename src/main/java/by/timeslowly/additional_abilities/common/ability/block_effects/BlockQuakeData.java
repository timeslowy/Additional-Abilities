package by.timeslowly.additional_abilities.common.ability.block_effects;

import by.timeslowly.additional_abilities.common.ability.actions.ActionScope.ActionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 一个维度内的<b>方块震动调度状态</b>，承载在 {@code Level} 的数据附件上
 * （{@code AAAttachments.BLOCK_QUAKE}），由 {@code BlockQuakes} 挂在
 * {@code LevelTickEvent.Post} 上逐 tick 推进。
 *
 * <h2>为什么挂在 Level 附件上，而不是静态 Map</h2>
 * 与 {@link BlockGlowData} 完全同因：原先 {@code Map<ResourceKey<Level>, …>} 静态表以
 * <b>维度 ResourceKey</b> 为键，世界切换后表仍然存在，而里面存的是上一个存档的
 * {@code getGameTime()} 数值。于是「进入时间较大的存档 A、退出、再进入时间较小的存档 B」
 * 之后，B 的 {@code now - lastQuake} 可能为负数，
 * {@code now - lastTick < COOLDOWN_TICKS} 恒成立 → B 里同坐标的新震动被永久拒绝。
 * 挂到 Level 附件上之后，状态随 {@code ServerLevel} 实例生灭，新存档即全新空表。
 *
 * <h2>刻意不做序列化</h2>
 * {@code AttachmentType.builder(...)} 而非 {@code .serializable(...)}：冷却窗口只有
 * {@code BlockQuakes.COOLDOWN_TICKS} 刻、待发缓冲活不过一个服务端刻，两者都没有跨重启的意义。
 *
 * <h2>两个表各自的清理口径</h2>
 * <ul>
 *     <li>{@link #lastQuake}：只保留冷却窗口内的记录，因此表大小被"冷却期内触发过的位置数"
 *         自然限住，不会随时间无限增长（由 {@code dropExpiredCooldowns} 每刻剪枝）；</li>
 *     <li>{@link #pending}：每刻冲刷后立即清空，正常情况下一个服务端刻结束即为空 ——
 *         因此它不会跨 tick 累积，也不需要独立的上限。</li>
 * </ul>
 *
 * <h2>待发项为什么还要记「动作身份」（F13 + 复审 P1）</h2>
 * 视觉包可以跨施法者合并 —— 同参数的两批方块在画面上本就无法区分。
 * <b>声音不行</b>：声源取的是那一批方块的几何中心，合并相距很远的两批会把声源
 * 放到两者中间，而音量固定为 1.0（可听半径约 16 格），结果是<b>两边都听不到</b>。
 * <p>
 * F13 先按「施法者 + 技能」拆开，但那仍不足以标识"一次<u>动作</u>"：
 * 同一玩家同一技能的两个<b>独立</b>动作（同技能的两个领域、{@code max_domains > 1} 的领域流）
 * 会被并成一声，若两片方块区相距较远，声源同样落在中间。
 * 因此 {@link Entry} 记的是 {@code ActionScope.ActionKey}
 * —— 施法者 + 技能 + <b>施法起点世界刻</b>，即项目里既有的"流身份"。
 */
public class BlockQuakeData {
    /** 位置（打包 long）→ 上次震动刻。条目只保留冷却窗口内的 */
    private final Map<Long, Long> lastQuake = new HashMap<>();

    /** 本刻待发送的方块：位置 → 该项的完整参数 */
    private final Map<Long, Entry> pending = new LinkedHashMap<>();

    /**
     * 本刻待发送的一项：位置 + 跃起参数 + 音效 + <b>动作身份</b>。
     * <p>
     * {@code action} 只服务于声音分组（见类注释），不进网络包，也不参与位置冷却判定。
     */
    record Entry(BlockPos position, float height, int durationTicks, Optional<SoundEvent> sound,
                 ActionKey action) {}

    /** 本刻是否有待发的震动（冲刷路径的早退判据） */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /** 供 {@code BlockQuakes.flush} 遍历 */
    @NotNull Map<Long, Entry> pending() {
        return pending;
    }

    /** 冲刷完成后清空待发表（防止残留到下一刻被重复发送） */
    void clearPending() {
        pending.clear();
    }

    /**
     * 登记一次震动。
     *
     * @param action 动作身份（施法者 + 技能 + 施法起点刻），仅用于声音分组
     * @return {@code false} 表示该位置仍在冷却窗口内、本次应被静默丢弃
     */
    boolean enqueue(final @NotNull BlockPos position, final long now, final float height,
                    final int durationTicks, final @NotNull Optional<SoundEvent> sound,
                    final @NotNull ActionKey action) {
        long key = position.asLong();
        Long lastTick = lastQuake.get(key);

        if (lastTick != null && now - lastTick < BlockQuakes.COOLDOWN_TICKS) {
            return false;
        }

        lastQuake.put(key, now);
        pending.put(key, new Entry(position.immutable(), height, durationTicks, sound, action));

        return true;
    }

    /**
     * 剪掉冷却窗口外的记录。
     * <p>
     * <b>这里必须用本维度自己的 {@code now}</b>：旧实现从 {@code MinecraftServer#getLevel}
     * 反查维度再取时间，而维度缺失时只做 {@code clear()} —— 那正是串味的温床。
     * 挂到附件上后，"本维度的当前时刻"由调用方直接传入，不存在反查失败的分支。
     */
    void dropExpiredCooldowns(final long now) {
        if (lastQuake.isEmpty()) {
            return;
        }

        lastQuake.values().removeIf(lastTick -> now - lastTick >= BlockQuakes.COOLDOWN_TICKS);
    }
}
