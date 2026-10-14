package by.timeslowly.additional_abilities.common.ability.block_effects;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
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
 * <h2>表键不含施法者，但每笔贡献各有到期刻</h2>
 * 同色同显示类型的两份发光在视觉上无法区分，合并成一条不产生任何观感差异；
 * 而不同颜色 / 不同显示类型天然成为不同条目 → 满足「不同施法、不同玩家的发光可以重叠」。
 * 施法者只记在 {@link Entry#contributions} 里供调试清空使用，不进网络包。
 * <p>
 * <b>合并的是条目，不是有效期</b>：每个施法者各持一笔到期刻，条目的共享到期刻取它们的最大值
 * （{@link Entry#expireTick()}）。若改成「一个共享到期刻 + 一个施法者集合」，就会出现
 * 「A 贡献 1200 刻、B 贡献 20 刻，100 刻后清除 A」的情形下 B 那笔早已过期的贡献仍留在集合里撑着条目：
 * 整条无法被正确撤销，指令报出的移除数也会偏小。
 */
public class BlockGlowData {
    /** 条目键：位置 + 颜色 + 显示类型（不含施法者，理由见类注释） */
    public record GlowKey(long packedPos, int colorARGB, GlowDisplayType displayType) {}

    /** 本维度全部存活条目 */
    private final Map<GlowKey, Entry> entries = new HashMap<>();

    /**
     * 一条发光记录（可变：各施法者的到期刻与最近发送时刻都随触发/广播推进）。
     * <p>
     * 位置、颜色、显示类型是条目键的组成部分，构造后不再变化。
     */
    static final class Entry {
        final BlockPos position;
        final int colorARGB;
        final GlowDisplayType displayType;

        /**
         * 施法者 → <b>该施法者自己那笔贡献</b>的到期刻。条目按「位置 + 颜色 + 显示类型」合并，
         * 所以同色同类型的两名玩家会落在同一条上 —— 这里因此可能不止一项。
         * <p>
         * <b>为什么是 Map 而不是 Set</b>：见类注释「合并的是条目，不是有效期」。
         * 只用 Set 记「谁贡献过」的话，短时贡献会在自己的时长结束后继续留在集合里，
         * 使整条无法按时撤销（{@code dropCasters} 也报不出应该消失的条目）。
         */
        final Map<UUID, Long> contributions = new HashMap<>(1);

        long lastSentTick = BlockGlows.NEVER_SENT;

        Entry(final @NotNull BlockPos position, final int colorARGB,
              final @NotNull GlowDisplayType displayType, final long expireTick,
              final @NotNull UUID caster) {
            this.position = position;
            this.colorARGB = colorARGB;
            this.displayType = displayType;
            this.contributions.put(caster, expireTick);
        }

        /**
         * 本条目的共享到期刻 = 各笔贡献中最晚的那个。
         * <p>
         * 每刻都可能被 {@code dropExpired} / {@code flush} 调用；常见情形只有一笔贡献，
         * 因此这里刻意用简单循环而非 stream（避免为每条目每刻分配迭代器与 lambda）。
         */
        long expireTick() {
            long latest = Long.MIN_VALUE;

            for (long tick : contributions.values()) {
                if (tick > latest) {
                    latest = tick;
                }
            }

            return latest == Long.MIN_VALUE ? 0L : latest;
        }

        /** 是否已无任何贡献（调用方应整条丢弃）；正常情况下条目不会停留在这种状态 */
        boolean hasNoContributors() {
            return contributions.isEmpty();
        }

        /**
         * 续命：只推迟<b>该施法者自己</b>那笔贡献的到期时刻，不新增条目、不重做遮挡剔除。
         * <p>
         * 取较大值而非覆盖：同一施法者在同一条目上被反复触发时，较短的时长不该削减已有的较长时长
         * （与客户端「取较晚到期时刻」的口径一致）。
         */
        void extendTo(final @NotNull UUID caster, final long expireTick) {
            contributions.merge(caster, expireTick, Math::max);
        }

        /**
         * 移除给定施法者的贡献。
         *
         * @return {@code true} 表示这条条目已经<b>没有任何</b>贡献者、调用方应整条丢弃；
         *         仍有别人撑着时返回 {@code false}，条目原样保留
         */
        boolean dropCasters(final @NotNull Set<UUID> candidates) {
            if (!contributions.keySet().removeAll(candidates)) {
                return false;
            }

            return contributions.isEmpty();
        }

        /**
         * 丢弃<b>已过期</b>的各笔贡献。
         *
         * @return {@code true} 表示所有贡献都已过期、调用方应整条丢弃
         */
        boolean dropExpiredContributions(final long now) {
            contributions.values().removeIf(expireTick -> now > expireTick);
            return contributions.isEmpty();
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

    /**
     * 丢弃已经过期的条目。
     * <p>
     * <b>按「笔」判定而不是按「条」</b>：先清掉各施法者已过期的贡献，只有当一笔都不剩时才丢整条。
     * 这不只是记账问题 —— 条目是否存活、{@code flush} 算出的剩余时长都取决于此，
     * 用过期的短时贡献撑着条目会让它比实际应该存在的时间活得更久。
     */
    void dropExpired(final long now) {
        entries.values().removeIf(entry -> entry.dropExpiredContributions(now));
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
