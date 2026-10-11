package by.timeslowly.additional_abilities.common.ability.block_effects;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一个维度内的<b>全部存活方块发光条目</b>，承载在 {@code Level} 的数据附件上
 * （{@code AAAttachments.BLOCK_GLOW}），由 {@code BlockGlows} 挂在
 * {@code LevelTickEvent.Post} 上逐 tick 推进。
 *
 * <h2>为什么挂在 Level 附件上，而不是静态 Map</h2>
 * 这正是本次修复的核心。原先用 {@code Map<ResourceKey<Level>, Storage>} 静态表按维度分区，
 * 带来一类跨存档串味：表的键是<b>维度 ResourceKey</b>（{@code minecraft:overworld} 这种），
 * 而不是 {@code ServerLevel} 实例，因此它在世界切换后<b>依然存在</b>，
 * 而里面存的却是上一个存档的 {@code expireTick} / {@code lastSentTick} ——
 * 那些值来自上一个世界的 {@code getGameTime()}。
 * <p>
 * 于是「进入游戏时间较大的存档 A，效果未结束时退出，再进入时间较小的存档 B，
 * 在同维度同坐标施法」时，B 的 {@code now} 减去 A 留下的 {@code lastSentTick} 可能为<b>负数</b>：
 * <ul>
 *     <li>{@code now - lastSentTick >= REFRESH_INTERVAL_TICKS} 判false → 新效果长时间不发包；</li>
 *     <li>旧的 {@code expireTick} 若远大于 B 的 {@code now}，还会把 B 里同坐标的新条目污染成超长时长。</li>
 * </ul>
 * 停止后的服务器也不会再靠 tick 清理（{@code onServerTickPost} 只在运行时触发）。
 * <p>
 * 改成 Level 附件后，状态随 {@code ServerLevel} 实例一起创建、一起回收：
 * 新存档 = 新实例 = 全新的空表，结构上不可能串味，也就不需要额外的 {@code ServerStoppedEvent} 清理钩子。
 * 这与 {@code DomainData} 的取舍一致（见其类注释的 "vs 静态 Map" 一节）。
 *
 * <h2>刻意不做序列化</h2>
 * {@code AttachmentType.builder(...)} 而非 {@code .serializable(...)}：发光是纯视觉的短生命周期状态
 * （最长 {@code GlowEffect.MAX_DURATION_TICKS}），服务端重启后重新触发即可，没有持久化的意义。
 * 反过来说，若序列化它，存档里就会留下一批「重启后仍在倒计时」的发光 —— 那不是期望的语义。
 *
 * <h2>表键不含施法者</h2>
 * 同色同显示类型的两份发光在视觉上无法区分，合并成一条不产生任何观感差异；
 * 而不同颜色 / 不同显示类型天然成为不同条目 → 满足「不同施法、不同玩家的发光可以重叠」。
 * 施法者只记在 {@link Entry#casters} 里供调试清空使用，不进网络包。
 */
public class BlockGlowData {
    /** 条目键：位置 + 颜色 + 显示类型（不含施法者，理由见类注释） */
    public record GlowKey(long packedPos, int colorARGB, GlowDisplayType displayType) {}

    /** 本维度全部存活条目 */
    private final Map<GlowKey, Entry> entries = new HashMap<>();

    /**
     * 一条发光记录（可变：过期时刻与上次发送时刻都随触发/广播推进）。
     * <p>
     * 位置、颜色、显示类型是条目键的组成部分，构造后不再变化。
     */
    static final class Entry {
        final BlockPos position;
        final int colorARGB;
        final GlowDisplayType displayType;

        /**
         * 贡献过这条发光的施法者。条目按「位置 + 颜色 + 显示类型」合并，所以同色同类型的两名玩家会挂在
         * 同一条上 —— 这里因此可能不止一个。它<b>只服务于调试清空</b>（见 {@link #dropCasters}），
         * 不参与渲染判定，也不进网络包（协议里没有施法者概念）。
         */
        final Set<UUID> casters = new HashSet<>(1);

        long expireTick;
        long lastSentTick = BlockGlows.NEVER_SENT;

        Entry(final @NotNull BlockPos position, final int colorARGB,
              final @NotNull GlowDisplayType displayType, final long expireTick,
              final @NotNull UUID caster) {
            this.position = position;
            this.colorARGB = colorARGB;
            this.displayType = displayType;
            this.expireTick = expireTick;
            this.casters.add(caster);
        }

        /** 续命：只推迟过期时刻，不新增条目、不重做遮挡剔除 */
        void extendTo(final long expireTick) {
            this.expireTick = Math.max(this.expireTick, expireTick);
        }

        /** 追加贡献者（先查再写：绝大多数条目只有一名施法者，避免 HashSet 无谓地扩容） */
        void addCaster(final @NotNull UUID caster) {
            casters.add(caster);
        }

        /**
         * 移除给定施法者的贡献。
         *
         * @return {@code true} 表示这条条目已经<b>没有任何</b>贡献者、调用方应整条丢弃；
         *         仍有别人撑着时返回 {@code false}，条目原样保留
         */
        boolean dropCasters(final @NotNull Set<UUID> candidates) {
            return casters.removeAll(candidates) && casters.isEmpty();
        }
    }

    /** 本维度是否一条条目都没有（tick 与清空路径的早退判据） */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 供 {@code BlockGlows} 遍历以推进过期与刷新 */
    @NotNull Map<GlowKey, Entry> entries() {
        return entries;
    }

    /** 丢弃已经过期的条目 */
    void dropExpired(final long now) {
        entries.values().removeIf(entry -> now > entry.expireTick);
    }

    /**
     * 移除给定施法者的贡献，返回被<b>整条</b>移除的条目（仍有人贡献的不算，因此不会误伤其他玩家）。
     * <p>
     * 返回值口径与 {@code domain clear} 一致 —— 是"被移除的条目数"，便于 {@code /execute store result} 观察。
     */
    @NotNull List<Entry> dropCasters(final @NotNull Set<UUID> casters) {
        List<Entry> dropped = null;
        Iterator<Entry> iterator = entries.values().iterator();

        while (iterator.hasNext()) {
            Entry entry = iterator.next();

            if (!entry.dropCasters(casters)) {
                continue;
            }

            iterator.remove();

            if (dropped == null) {
                dropped = new ArrayList<>();
            }

            dropped.add(entry);
        }

        return dropped == null ? List.of() : dropped;
    }
}
