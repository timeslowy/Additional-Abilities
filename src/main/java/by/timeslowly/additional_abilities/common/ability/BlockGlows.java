package by.timeslowly.additional_abilities.common.ability;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.network.BlockGlowPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 方块发光的服务端调度（仅服务端逻辑，客户端状态见 {@code client.ClientBlockGlowState}）。
 * <p>
 * <b>为什么需要这一层</b>：Wiki 明确指出「area 目标下，范围内每个方块位置每刻都会判定一次所有
 * block_effect」。半径 5 的区域就有上百个地表方块，若每次判定都直接发包，带宽与客户端状态都会爆。
 * 这里做四件事：
 * <ol>
 *     <li><b>源头剔除</b>（仅 {@link GlowDisplayType#SIMPLE_SHADER}，见 {@link #register}）；</li>
 *     <li><b>同键续命</b>：同一位置 + 同色 + 同显示类型只保留一条，重复触发只刷新过期时刻，不新增条目；</li>
 *     <li><b>按刻聚合</b>：每服务端刻把「到期的」条目按「颜色 + 显示类型」分组，聚合成尽可能少的包；</li>
 *     <li><b>就近广播</b>：按本批方块包围盒半径只发给附近玩家（技能目标是方块，影响对象是"看得到的人"）。</li>
 * </ol>
 * <b>生命周期</b>：{@code AbilityBlockEffect} 接口没有 {@code remove}（方块效果一律瞬时），
 * 所以时长完全由本类自管 —— 条目带过期刻，服务端每 {@link #REFRESH_INTERVAL_TICKS} 刻重发一次续命；
 * 技能停止触发后条目自然过期，客户端按包内剩余时长本地倒计时，因此<b>不需要额外的移除包</b>。
 * 代价是技能被提前打断时发光会残留到时长结束（已在实现规划中确认接受）。
 * <p>
 * <b>表键为什么不含施法者</b>：同色同显示类型的两份发光在视觉上无法区分（都是同一段色值画在同一条棱上），
 * 合并成一条不产生任何观感差异；而<b>不同颜色 / 不同显示类型</b>天然成为不同条目 → 满足
 * 「不同施法、不同玩家的发光可以重叠」。这样协议里也就不需要传施法者 UUID。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public final class BlockGlows {
    /**
     * 重发间隔（游戏刻）。条目每满这么久重发一次，让客户端刷新本地倒计时：
     * 引导类技能持续触发时发光常亮，技能停止后客户端各自到期消失。
     */
    public static final int REFRESH_INTERVAL_TICKS = 20;

    /** 假身/方块渲染的实际视距约 64 格，广播半径在此之上留出余量（与 {@code BlockQuakes} 同口径） */
    private static final double VIEW_MARGIN = 64.0;

    /** {@code Direction.values()} 每次调用都会克隆数组，热路径上必须缓存（虽然这里只在建立条目时用） */
    private static final Direction[] DIRECTIONS = Direction.values();

    /** 尚未发送过的哨兵值：放在 {@link Entry#lastSentTick} 上使新条目下一服务端刻即发出 */
    private static final long NEVER_SENT = -1L;

    /** 维度 → 条目表 */
    private static final Map<ResourceKey<Level>, Storage> STORAGE = new HashMap<>();

    /** 条目键：位置 + 颜色 + 显示类型（不含施法者，理由见类注释） */
    public record GlowKey(long packedPos, int colorARGB, GlowDisplayType displayType) {}

    /** 同一批发包与分组用的参数：颜色 + 显示类型 */
    private record Batch(int colorARGB, GlowDisplayType displayType) {}

    /** 本刻待发送的一项：位置 + 剩余时长（剩余时长逐项不同，故随项携带而不是整批一个） */
    private record Pending(long packedPos, int remainingTicks) {}

    /** 一条发光记录（可变：过期时刻与上次发送时刻都随触发/广播推进） */
    static final class Entry {
        final BlockPos position;
        final int colorARGB;
        final GlowDisplayType displayType;

        /**
         * 贡献过这条发光的施法者。条目按「位置 + 颜色 + 显示类型」合并，所以同色同类型的两名玩家会挂在
         * 同一条上 —— 这里因此可能不止一个。它<b>只服务于调试清空</b>（见 {@link #clearByCasters}），
         * 不参与渲染判定，也不进网络包（协议里没有施法者概念）。
         */
        final Set<UUID> casters = new HashSet<>(1);

        long expireTick;
        long lastSentTick = NEVER_SENT;

        private Entry(final @NotNull BlockPos position, final int colorARGB,
                      final @NotNull GlowDisplayType displayType, final long expireTick,
                      final @NotNull UUID caster) {
            this.position = position;
            this.colorARGB = colorARGB;
            this.displayType = displayType;
            this.expireTick = expireTick;
            this.casters.add(caster);
        }

        /** 追加贡献者（先查再写：绝大多数条目只有一名施法者，避免 HashSet 无谓地扩容） */
        void addCaster(final @NotNull UUID caster) {
            if (!casters.contains(caster)) {
                casters.add(caster);
            }
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

    private static final class Storage {
        private final Map<GlowKey, Entry> entries = new HashMap<>();

        /**
         * 移除给定施法者的贡献，返回被<b>整条</b>移除的条目（仍有人贡献的不算，因此不会误伤其他玩家）。
         * <p>
         * 返回值口径与 {@code domain clear} 一致 —— 是"被移除的条目数"，便于 {@code /execute store result} 观察。
         */
        private @NotNull List<Entry> dropCasters(final @NotNull Set<UUID> casters) {
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

    private BlockGlows() {}

    /**
     * 登记一次方块发光（由 {@code block_effects.GlowEffect#apply} 调用）。
     * <p>
     * <b>源头剔除</b>：仅对 {@link GlowDisplayType#SIMPLE_SHADER} 生效，且只在新条目建立时判定一次 ——
     * 该路径每个接收端都要逐帧烘焙方块模型，代价远高于线框，因此被完全遮挡、形状不完整、
     * 或本来就是空气的方块<b>连网络包都不产生</b>（比在每个客户端各判一次划算得多）。
     * 已有条目只续命、不重判，于是这 6 次邻接查询在整条条目生命周期里最多发生一次。
     *
     * @param caster        施法者 uuid。只用于调试指令「按施法者清空」（见 {@link #clearByCasters}），
     *                      不参与渲染判定、也不进网络包 —— 协议里没有施法者概念
     * @param durationTicks 本次触发给出的时长；与现有条目取较晚的过期时刻
     * @param hideOccluded  是否剔除被完全遮挡的方块（对应 JSON 的 {@code hide_occluded}）
     */
    public static void register(final @NotNull ServerLevel level, final @NotNull UUID caster,
                                final @NotNull BlockPos position, final int colorARGB,
                                final @NotNull GlowDisplayType displayType,
                                final int durationTicks, final boolean hideOccluded) {
        if (durationTicks <= 0) {
            return;
        }

        long now = level.getGameTime();
        GlowKey key = new GlowKey(position.asLong(), colorARGB, displayType);
        Storage storage = STORAGE.computeIfAbsent(level.dimension(), ignored -> new Storage());
        Entry existing = storage.entries.get(key);

        if (existing != null) {
            // 重复触发（area 目标每刻判定）只续命，不新增条目、不重做剔除
            existing.expireTick = Math.max(existing.expireTick, now + durationTicks);
            existing.addCaster(caster);
            return;
        }

        if (displayType.isShader() && !isWorthShaderRendering(level, position, hideOccluded)) {
            return;
        }

        storage.entries.put(key,
                new Entry(position.immutable(), colorARGB, displayType, now + durationTicks, caster));
    }

    /**
     * 方块级遮挡判定：<b>6 个邻接方块全部「实心渲染」时，任何视线都不可能进到该方块</b>。
     * <p>
     * {@link BlockState#isSolidRender} 用的是原版<b>遮挡形状</b>（{@code getOcclusionShape}，
     * 与本方块自身邻面剔除、光照传播同一套判定），不是碰撞形状；且绝大多数方块命中
     * {@code BlockState} 自带的缓存，调用几乎免费。
     * <p>
     * 这个判定是<b>充分</b>的（6 面全被不透明满方块盖住 ⇒ 一定看不见），因此不会误剔可见方块。
     */
    public static boolean isFullyOccluded(final @NotNull ServerLevel level, final @NotNull BlockPos position) {
        for (Direction direction : DIRECTIONS) {
            BlockPos neighbor = position.offset(direction.getStepX(), direction.getStepY(), direction.getStepZ());

            if (!level.getBlockState(neighbor).isSolidRender(level, neighbor)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 该位置值不值得走核心着色器渲染：空气、非完整形状、被完全遮挡（且要求剔除）时都不值得。
     * <p>
     * 非完整形状（草、火把、台阶、藤蔓……）本身 quad 数不定，而 DS 的着色器路径对每个方块
     * 固定做 7 次 {@code getQuads}，染色后观感只是一团色块 —— 收益低、成本照收，因此排除。
     * 线框（{@link GlowDisplayType#OUTLINE}）不受此限：它的代价恒为 12 条棱，任何形状都画得起。
     */
    private static boolean isWorthShaderRendering(final @NotNull ServerLevel level, final @NotNull BlockPos position,
                                                  final boolean hideOccluded) {
        BlockState state = level.getBlockState(position);

        if (state.isAir() || !state.isCollisionShapeFullBlock(level, position)) {
            return false;
        }

        return !hideOccluded || !isFullyOccluded(level, position);
    }

    @SubscribeEvent
    public static void onServerTickPost(final @NotNull ServerTickEvent.Post event) {
        if (STORAGE.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<ResourceKey<Level>, Storage>> iterator = STORAGE.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<ResourceKey<Level>, Storage> slot = iterator.next();
            ServerLevel level = event.getServer().getLevel(slot.getKey());

            // 维度已卸载（存档关闭 / 服务器重启）：整张表丢弃
            if (level == null) {
                iterator.remove();
                continue;
            }

            Storage storage = slot.getValue();

            if (storage.entries.isEmpty()) {
                iterator.remove();
                continue;
            }

            long now = level.getGameTime();
            storage.entries.values().removeIf(entry -> now > entry.expireTick);
            flush(level, storage, now);
        }
    }

    /**
     * 移除给定施法者名下的全部方块发光贡献（调试指令 {@code /additional-abilities block-glow clear} 的入口）。
     * <p>
     * <b>跨维度扫描</b>：条目表按维度分区，而施法者一旦换了维度，旧维度里的条目不会自动消失
     * （只是不再被刷新，靠自然过期），因此必须遍历 {@code MinecraftServer#getAllLevels()} ——
     * 与 {@code DomainCommand} 同处理。
     * <p>
     * <b>只丢贡献、不丢整条</b>：某条发光还有别的玩家在贡献时原地保留 —— 这是「可以重叠」语义的必然结果，
     * 不会因为清了 A 就把 B 的效果一起抹掉。整条消失时给附近玩家补发 {@code remainingTicks = 0}
     * 作为移除信号，否则客户端最多要等到下一次刷新（20 刻）才不再画它。
     *
     * @return 被<b>整条</b>移除的条目数
     */
    public static int clearByCasters(final @NotNull MinecraftServer server, final @NotNull Set<UUID> casters) {
        if (casters.isEmpty() || STORAGE.isEmpty()) {
            return 0;
        }

        int removed = 0;
        Iterator<Map.Entry<ResourceKey<Level>, Storage>> iterator = STORAGE.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<ResourceKey<Level>, Storage> slot = iterator.next();
            ServerLevel level = server.getLevel(slot.getKey());

            // 维度已卸载：条目本就失效，直接丢弃
            if (level == null) {
                iterator.remove();
                continue;
            }

            List<Entry> dropped = slot.getValue().dropCasters(casters);

            if (dropped.isEmpty()) {
                continue;
            }

            removed += dropped.size();
            notifyRemoval(level, dropped);
        }

        return removed;
    }

    /** 把这批条目的「已失效」通知发给附近玩家：{@code remainingTicks = 0} 即移除信号 */
    private static void notifyRemoval(final @NotNull ServerLevel level, final @NotNull List<Entry> dropped) {
        Map<Batch, List<Pending>> grouped = new LinkedHashMap<>();

        for (Entry entry : dropped) {
            grouped.computeIfAbsent(new Batch(entry.colorARGB, entry.displayType), ignored -> new ArrayList<>())
                    .add(new Pending(entry.position.asLong(), 0));
        }

        grouped.forEach((batch, pending) -> send(level, batch, pending));
    }

    /** 把「到期的」条目按「颜色 + 显示类型」分组后发出去，并推进其 {@code lastSentTick} */
    private static void flush(final @NotNull ServerLevel level, final @NotNull Storage storage, final long now) {
        Map<Batch, List<Pending>> grouped = null;

        for (Entry entry : storage.entries.values()) {
            boolean due = entry.lastSentTick == NEVER_SENT
                    || now - entry.lastSentTick >= REFRESH_INTERVAL_TICKS;

            if (!due) {
                continue;
            }

            if (grouped == null) {
                grouped = new LinkedHashMap<>();
            }

            // 剩余时长下限为 1：避免整刻对齐误差把「刚好到期」的条目按 0 发出去
            int remaining = (int) Math.max(1L, entry.expireTick - now);
            grouped.computeIfAbsent(new Batch(entry.colorARGB, entry.displayType), ignored -> new ArrayList<>())
                    .add(new Pending(entry.position.asLong(), remaining));
            entry.lastSentTick = now;
        }

        if (grouped == null) {
            return;
        }

        grouped.forEach((batch, pending) -> send(level, batch, pending));
    }

    private static void send(final @NotNull ServerLevel level, final @NotNull Batch batch,
                             final @NotNull List<Pending> pending) {
        for (int start = 0; start < pending.size(); start += BlockGlowPayload.MAX_POSITIONS) {
            int end = Math.min(start + BlockGlowPayload.MAX_POSITIONS, pending.size());
            int count = end - start;

            long[] packed = new long[count];
            int[] remaining = new int[count];

            for (int index = 0; index < count; index++) {
                Pending item = pending.get(start + index);
                packed[index] = item.packedPos();
                remaining[index] = item.remainingTicks();
            }

            double[] center = center(packed);
            double maxDistanceSqr = 0.0;

            for (long position : packed) {
                maxDistanceSqr = Math.max(maxDistanceSqr, BlockPos.of(position).distToCenterSqr(center[0], center[1], center[2]));
            }

            double radiusSqr = Math.pow(Math.sqrt(maxDistanceSqr) + VIEW_MARGIN, 2.0);
            BlockGlowPayload payload = new BlockGlowPayload(packed, remaining, batch.colorARGB(), batch.displayType());

            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(center[0], center[1], center[2]) <= radiusSqr) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
    }

    /** 一批方块的几何中心（各取方块中心，即整数坐标 +0.5） */
    @Contract("_ -> new")
    private static double @NotNull [] center(final long @NotNull [] positions) {
        double sumX = 0.0;
        double sumY = 0.0;
        double sumZ = 0.0;

        for (long position : positions) {
            BlockPos pos = BlockPos.of(position);
            sumX += pos.getX() + 0.5;
            sumY += pos.getY() + 0.5;
            sumZ += pos.getZ() + 0.5;
        }

        int count = positions.length;
        return new double[]{sumX / count, sumY / count, sumZ / count};
    }
}
