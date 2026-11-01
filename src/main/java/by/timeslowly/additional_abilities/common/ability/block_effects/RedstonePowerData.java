package by.timeslowly.additional_abilities.common.ability.block_effects;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一个维度内的<b>全部存活虚拟红石供能</b>，承载在 {@code Level} 的数据附件上
 * （{@code AAAttachments.REDSTONE_POWER}），由 {@code VirtualRedstonePower} 读写、
 * {@code RedstonePowerTickHandler} 每维度刻末尾清理。
 *
 * <h2>两类供能</h2>
 * <ul>
 *     <li><b>源供能</b>：{@link Entry}，键为 {@code (S, D)}，表示“S 处有一个朝 D 方向发出信号的源”。
 *         对应 {@code target_direction}：{@code S = P.relative(D)}；</li>
 *     <li><b>方块充能</b>：{@link BlockEntry}，键为 {@code P}，表示“P 自身作为被充能的方块向六个面放能”。
 *         目标方块是原版导电方块（{@code isRedstoneConductor}）时，无论有无 {@code target_direction} 都建立；
 *         有方向时还会额外建立上面的源供能。</li>
 * </ul>
 * 两者都按“施法者 + 技能”身份登记贡献，各自独立到期、取存活最高强度。
 *
 * <h2>为什么挂在 Level 附件上，而不是静态 Map</h2>
 * 与 {@link BlockGlowData} / {@link BlockQuakeData} 完全同因：{@code Map<ResourceKey<Level>, …>}
 * 以<b>维度 ResourceKey</b> 为键，世界切换后表仍然存活，里面却是上一个存档的 {@code getGameTime()}
 * 数值。红石供能依赖“到期刻”与“当前刻”的比较，串味后会出现新存档里信号永久卡住或立刻消失。
 * 挂到 Level 附件后状态随 {@code ServerLevel} 实例生灭，结构上不可能跨存档残留。
 *
 * <h2>刻意不做序列化</h2>
 * {@code AttachmentType.builder(...)} 而非 {@code .serializable(...)}：红石供能是技能的瞬时运行时状态，
 * 服务端重启后不应继续供能。若序列化它，存档里就会留下“重启后仍在为某处供能”的幽灵信号，
 * 而触发它的技能早已结束。
 *
 * <h2>热路径：无分配 + O(1) 聚合读取</h2>
 * 这张表会被 {@code SignalGetter} 的红石查询每秒调用成千上万次，因此：
 * <ul>
 *     <li>内部键是<b>原始 {@code long}（{@code BlockPos.asLong()}）</b>，源供能的值是按
 *         {@link Direction#ordinal()} 索引的长度 6 数组（fastutil 的 {@code Long2ObjectOpenHashMap}）：
 *         查询不会创建 {@link PowerKey}、不会装箱 {@code Long}，每个 {@code (S, D)} 一次原始键查找；</li>
 *     <li>每个 {@link PowerStore} 缓存“当前仍存活的最高强度 + 该值的有效期”，{@link PowerStore#power(long)}
 *         在有效期内直接返回缓存，不遍历贡献；只有最大值到期或贡献被增删时才 O(k) 重算
 *         （{@code k} = 同一位置不同“施法者 + 技能”的笔数，通常为 1）。</li>
 * </ul>
 * {@link PowerKey} 保留为只读键视图（测试 / 调试用），不参与热路径。
 *
 * <h2>到期语义（含端点）</h2>
 * {@link Contribution#lastTick()} 是<b>最后一个仍然生效的刻</b>（不是“第一个失效的刻”）。
 * 在刻 {@code T} 登记、时长 {@code D} 时写入 {@code T + D - 1}，于是它恰好覆盖
 * {@code T .. T + D - 1} 共 {@code D} 个刻：{@code D = 1} 就是“只在登记它的那一触发光”。
 * 清理在刻 {@code now} 的 {@code LevelTickEvent.Post} 上按 {@code lastTick <= now} 执行 ——
 * 那一刻它已经完整服务完本刻，于是“信号从非零降为零”的边沿正好落在本刻末尾，
 * 不会晚一个刻、也不会让“1 刻脉冲”实际持续 2 刻。
 */
public class RedstonePowerData {
    /** {@code Direction.values().length}；每个源坐标固定 6 个输入面 */
    private static final int DIRECTION_COUNT = Direction.values().length;

    /**
     * 源供能的只读键：源坐标（打包 long）+ 输入方向。
     * <p>
     * 位置一律以不可变 long 保存，避免 {@code MutableBlockPos} 被后续邻居更新改写后污染键。
     * 本记录只用于对外的只读键视图与测试，内部热路径直接用原始 long 查表。
     */
    public record PowerKey(long packedPos, Direction direction) {
        public PowerKey(final @NotNull BlockPos position, final @NotNull Direction direction) {
            this(position.asLong(), direction);
        }

        /** 还原为不可变坐标（仅在补发邻居更新时用） */
        @Contract(value = " -> new", pure = true)
        public @NotNull BlockPos position() {
            return BlockPos.of(packedPos);
        }
    }

    /**
     * 一笔贡献的身份：<b>施法者 + 技能</b>。
     * <p>
     * 同一技能在 {@code area} / {@code disc} 目标下每刻会对同一位置重复登记，
     * 用这个身份合并成同一笔，避免同一 tick 反复排队相同更新；不同玩家 / 不同技能则是独立的两笔，
     * 各自到期，不会互相续命或互相削减。
     */
    record ContributionKey(UUID caster, ResourceKey<?> ability) {}

    /**
     * 单笔贡献。
     *
     * @param power    信号强度（调用方已钳制到 {@code 1..15}；0 不会进入本表）
     * @param lastTick 最后一个仍生效的刻（含），见类注释「到期语义」
     */
    record Contribution(int power, long lastTick) {}

    /** 源供能的到期变化，调用方据此补发 S 处的邻居更新 */
    record PowerChange(BlockPos sourcePos, Direction direction, int before, int after) {}

    /** 方块充能的到期变化，调用方据此补发 P 六个邻居的更新 */
    record BlockPowerChange(BlockPos position, int before, int after) {}

    /**
     * 一个位置上的全部贡献 + 聚合缓存 + “世界最后看到的强度”。
     * <p>
     * 源供能与方块充能共用这套逻辑，只是外层键不同（{@code (S, D)} 或多方向都不区分的 {@code P}）。
     */
    static final class PowerStore {
        private final Map<ContributionKey, Contribution> contributions = new HashMap<>(1);

        /**
         * 上一次真正<b>广播给世界</b>的聚合强度。
         * <p>
         * 到期清理不能只看“清理这一刻按时间过滤出的值”：若本维度因故错过了一个刻的 Post
         * （维度暂停、服务端卡顿、区块刚恢复 tick），那个已到期贡献在清理时按 {@code now} 过滤就是 0，
         * 于是这次退场会被误判为“没有变化”而静默丢弃 —— 消费者会被永久留在 {@code POWERED=true}。
         * 记住最后广播过的值后，只要它曾经非零，退场时就一定补一次“非零 → 0”的下降沿。
         */
        private int notifiedPower;

        /** 缓存：在 {@link #cachedUntil} 之前（含）聚合强度恒为 {@link #cachedPower} */
        private int cachedPower;
        private long cachedUntil = Long.MIN_VALUE;

        /**
         * 当前刻仍然存活的最高信号强度。
         * <p>
         * 缓存命中时是 O(1)；未命中（最大值到期 / 贡献增删）才重算，重算逐笔判断
         * {@code now <= lastTick}：即使清理监听器还没轮到本维度，已到期却尚未剪掉的贡献
         * 也不会被读到 —— 这正是“到期刻”与“清理时机”解耦的关键。
         */
        int power(final long now) {
            if (now <= cachedUntil) {
                return cachedPower;
            }

            recompute(now);
            return cachedPower;
        }

        /**
         * 重算聚合强度与缓存有效期。
         * <p>
         * 有效期取“所有具有当前最大强度的贡献中最晚的 {@code lastTick}”：只要还有一笔同强度贡献活着，
         * 最大值就不会掉；等到超过这个刻，再重算自然能拿到次高强度。这样既不会返回过期的最大值，
         * 也不需要在每次查询里遍历贡献。
         */
        private void recompute(final long now) {
            int max = 0;
            long until = Long.MIN_VALUE;

            for (Contribution contribution : contributions.values()) {
                if (now > contribution.lastTick()) {
                    continue;
                }

                if (contribution.power() > max) {
                    max = contribution.power();
                    until = contribution.lastTick();
                } else if (contribution.power() == max) {
                    until = Math.max(until, contribution.lastTick());
                }
            }

            cachedPower = max;
            cachedUntil = until;
        }

        /**
         * 登记 / 刷新一笔贡献。
         * <p>
         * 同一身份重复登记时，强度与到期刻都<b>取较大值</b>：较短的脉冲不能削减已有的较长供能
         * （与 {@code BlockGlowData.Entry#extendTo} 的口径一致）。对于“同一技能每刻触发”的被动技能，
         * 每次登记都会把 {@code lastTick} 推到更晚，于是信号在触发期间持续存在。
         * <p>
         * 若同名旧贡献在 {@code now} 已过期（正常流程里 tick 清理会先移除它，但清理延迟时不能让它
         * 的高强度“借尸还魂”），直接以新贡献整体替换，不参与“取较大值”。
         */
        void contribute(final @NotNull ContributionKey key, final int power, final long lastTick,
                        final long now) {
            Contribution existing = contributions.get(key);

            if (existing == null || existing.lastTick() < now) {
                contributions.put(key, new Contribution(power, lastTick));
            } else {
                contributions.put(key, new Contribution(
                        Math.max(existing.power(), power),
                        Math.max(existing.lastTick(), lastTick)));
            }

            // 登记不是热路径（每次 apply 一条），重算一次让缓存立刻反映新贡献
            recompute(now);
        }

        /**
         * 剪掉本刻已经结束的贡献。
         * <p>
         * 判据是 {@code lastTick <= now}：刻 {@code now} 的 {@code LevelTickEvent.Post} 上，
         * 带 {@code lastTick == now} 的贡献已经完整覆盖了本刻（{@link #power(long)} 在刻内仍读得到它），
         * 此刻正是它退场的时机。若等到下一刻才剪，边沿通知会晚一个刻，消费者可能已经读到 0
         * 却没有收到“信号消失”的邻居更新。
         *
         * @return {@code true} 表示一笔都不剩，调用方应整 key 丢弃
         */
        boolean prune(final long now) {
            boolean removed = contributions.values().removeIf(contribution -> contribution.lastTick() <= now);

            if (removed) {
                // 被剪掉的可能是当前最大值，必须让缓存失效并重算
                recompute(now);
            }

            return contributions.isEmpty();
        }

        int notifiedPower() {
            return notifiedPower;
        }

        void notifiedPower(final int value) {
            this.notifiedPower = value;
        }
    }

    /** 一个 {@code (S, D)} 上的源供能 */
    static final class Entry {
        final BlockPos sourcePos;
        final Direction direction;
        final PowerStore store = new PowerStore();

        Entry(final @NotNull BlockPos sourcePos, final @NotNull Direction direction) {
            this.sourcePos = sourcePos;
            this.direction = direction;
        }
    }

    /** 一个被充能的方块 P（不分方向，向六个面放能） */
    static final class BlockEntry {
        final BlockPos position;
        final PowerStore store = new PowerStore();

        BlockEntry(final @NotNull BlockPos position) {
            this.position = position;
        }
    }

    /** 源供能：源坐标（原始 long）→ 六个方向各自的 {@link Entry}（未使用的位置为 null） */
    private final Long2ObjectOpenHashMap<Entry[]> entries = new Long2ObjectOpenHashMap<>();

    /** 方块充能：方块坐标（原始 long）→ {@link BlockEntry} */
    private final Long2ObjectOpenHashMap<BlockEntry> blockEntries = new Long2ObjectOpenHashMap<>();

    /** 本维度是否一条供能都没有（tick 监听器与 Mixin 路径的早退判据） */
    public boolean isEmpty() {
        return entries.isEmpty() && blockEntries.isEmpty();
    }

    /**
     * 读取某个 {@code (源坐标, 输入方向)} 的源供能强度。
     * <p>
     * <b>原始 long 键查找 + 缓存聚合值</b>，不创建对象、不遍历贡献、不做任何世界访问。
     */
    public int powerAt(final long now, final @NotNull BlockPos sourcePos, final @NotNull Direction direction) {
        Entry[] byDirection = entries.get(sourcePos.asLong());

        if (byDirection == null) {
            return 0;
        }

        Entry entry = byDirection[direction.ordinal()];
        return entry == null ? 0 : entry.store.power(now);
    }

    /**
     * 读取某个方块自身的充能强度（不分方向，向六个面放能）。
     * <p>
     * 与 {@link #powerAt} 一样是无分配热路径。
     */
    public int blockPowerAt(final long now, final @NotNull BlockPos position) {
        BlockEntry entry = blockEntries.get(position.asLong());
        return entry == null ? 0 : entry.store.power(now);
    }

    /**
     * 登记一笔源供能。
     *
     * @param power         已钳制的信号强度；{@code <= 0} 直接忽略
     * @param durationTicks 生效刻数（含登记刻），{@code >= 1}
     * @return {@code true} 表示该键的<b>聚合信号强度发生了变化</b>，调用方应立即补发一次邻居更新
     */
    boolean register(final @NotNull UUID caster, final @NotNull ResourceKey<?> ability,
                     final @NotNull BlockPos sourcePos, final @NotNull Direction direction,
                     final int power, final long now, final int durationTicks) {
        if (power <= 0 || durationTicks <= 0) {
            return false;
        }

        long packedPos = sourcePos.asLong();
        Entry[] byDirection = entries.get(packedPos);

        if (byDirection == null) {
            byDirection = new Entry[DIRECTION_COUNT];
            entries.put(packedPos, byDirection);
        }

        int index = direction.ordinal();
        Entry entry = byDirection[index];

        if (entry == null) {
            entry = new Entry(sourcePos.immutable(), direction);
            byDirection[index] = entry;
        }

        // before 必须在写入前取：它按“当前刻仍存活”过滤，已到期但尚未剪掉的旧贡献不会虚增 before
        int before = entry.store.power(now);
        entry.store.contribute(new ContributionKey(caster, ability), power, now + durationTicks - 1L, now);
        int after = entry.store.power(now);

        if (before != after) {
            entry.store.notifiedPower(after);
        }

        return before != after;
    }

    /**
     * 登记一笔方块充能。
     *
     * @param power         已钳制的信号强度；{@code <= 0} 直接忽略
     * @param durationTicks 生效刻数（含登记刻），{@code >= 1}
     * @return {@code true} 表示该方块可见的充能强度发生了变化，调用方应补发 P 的邻居更新
     */
    boolean chargeBlock(final @NotNull UUID caster, final @NotNull ResourceKey<?> ability,
                        final @NotNull BlockPos position, final int power, final long now,
                        final int durationTicks) {
        if (power <= 0 || durationTicks <= 0) {
            return false;
        }

        long packedPos = position.asLong();
        BlockEntry entry = blockEntries.get(packedPos);

        if (entry == null) {
            entry = new BlockEntry(position.immutable());
            blockEntries.put(packedPos, entry);
        }

        int before = entry.store.power(now);
        entry.store.contribute(new ContributionKey(caster, ability), power, now + durationTicks - 1L, now);
        int after = entry.store.power(now);

        if (before != after) {
            entry.store.notifiedPower(after);
        }

        return before != after;
    }

    /** 丢弃源供能中本刻已经结束的贡献，并返回世界可见强度发生变化的键 */
    @NotNull List<PowerChange> expire(final long now) {
        if (entries.isEmpty()) {
            return List.of();
        }

        List<PowerChange> changes = null;
        ObjectIterator<Long2ObjectMap.Entry<Entry[]>> iterator = entries.long2ObjectEntrySet().fastIterator();

        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<Entry[]> mapEntry = iterator.next();
            Entry[] byDirection = mapEntry.getValue();
            boolean anyRemaining = false;

            for (int index = 0; index < byDirection.length; index++) {
                Entry entry = byDirection[index];

                if (entry == null) {
                    continue;
                }

                if (entry.store.prune(now)) {
                    byDirection[index] = null;
                    int notified = entry.store.notifiedPower();

                    if (notified > 0) {
                        if (changes == null) {
                            changes = new ArrayList<>();
                        }

                        changes.add(new PowerChange(entry.sourcePos, entry.direction, notified, 0));
                    }

                    continue;
                }

                anyRemaining = true;
                int after = entry.store.power(now);

                if (entry.store.notifiedPower() != after) {
                    if (changes == null) {
                        changes = new ArrayList<>();
                    }

                    changes.add(new PowerChange(entry.sourcePos, entry.direction,
                            entry.store.notifiedPower(), after));
                    entry.store.notifiedPower(after);
                }
            }

            if (!anyRemaining) {
                iterator.remove();
            }
        }

        return changes == null ? List.of() : changes;
    }

    /** 丢弃方块充能中本刻已经结束的贡献，并返回世界可见强度发生变化的方块 */
    @NotNull List<BlockPowerChange> expireBlocks(final long now) {
        if (blockEntries.isEmpty()) {
            return List.of();
        }

        List<BlockPowerChange> changes = null;
        ObjectIterator<Long2ObjectMap.Entry<BlockEntry>> iterator =
                blockEntries.long2ObjectEntrySet().fastIterator();

        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<BlockEntry> mapEntry = iterator.next();
            BlockEntry entry = mapEntry.getValue();

            if (entry.store.prune(now)) {
                iterator.remove();
                int notified = entry.store.notifiedPower();

                if (notified > 0) {
                    if (changes == null) {
                        changes = new ArrayList<>();
                    }

                    changes.add(new BlockPowerChange(entry.position, notified, 0));
                }

                continue;
            }

            int after = entry.store.power(now);

            if (entry.store.notifiedPower() != after) {
                if (changes == null) {
                    changes = new ArrayList<>();
                }

                changes.add(new BlockPowerChange(entry.position, entry.store.notifiedPower(), after));
                entry.store.notifiedPower(after);
            }
        }

        return changes == null ? List.of() : changes;
    }

    /** 仅测试 / 调试用：当前维度持有的源供能只读键视图（生产热路径不使用） */
    @NotNull Set<PowerKey> keys() {
        Set<PowerKey> keys = new HashSet<>();

        for (Entry[] byDirection : entries.values()) {
            for (Entry entry : byDirection) {
                if (entry != null) {
                    keys.add(new PowerKey(entry.sourcePos, entry.direction));
                }
            }
        }

        return keys;
    }

    /** 仅测试用：当前维度持有的源供能条目数 */
    int entryCount() {
        int count = 0;

        for (Entry[] byDirection : entries.values()) {
            for (Entry entry : byDirection) {
                if (entry != null) {
                    count++;
                }
            }
        }

        return count;
    }

    /** 仅测试用：当前维度持有的方块充能条目数 */
    int blockEntryCount() {
        return blockEntries.size();
    }
}
