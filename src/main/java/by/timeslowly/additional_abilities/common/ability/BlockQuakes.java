package by.timeslowly.additional_abilities.common.ability;

import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.common.network.BlockQuakePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方块震动的服务端调度（仅服务端逻辑，客户端状态见 {@code client.ClientBlockQuakeState}）。
 * <p>
 * <b>为什么需要这一层</b>：方块效果的宿主目标是方块，Wiki 明确指出
 * 「area 目标下，范围内每个方块位置每刻都会判定一次所有 block_effect」。
 * 半径 5 的区域就有上百个地表方块，若每次判定都直接发包、每次都让假身重新起跳，
 * 效果会退化成高频闪烁且带宽爆炸。这里做三件事：
 * <ol>
 *     <li><b>位置冷却</b>：{@link #COOLDOWN_TICKS} 刻内同一位置只震一次；</li>
 *     <li><b>按刻聚合</b>：触发先收进缓冲，每服务端刻冲刷一次，聚合成尽可能少的包；</li>
 *     <li><b>就近广播</b>：按本批方块包围盒半径只发给附近玩家（技能目标是方块，影响对象是"看得到的人"）。</li>
 * </ol>
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID)
public final class BlockQuakes {
    /** 同一位置两次震动的冷却（游戏刻） */
    public static final int COOLDOWN_TICKS = 10;

    /** 假身方块默认视距 64 格，广播半径在此之上留出余量 */
    private static final double VIEW_MARGIN = 64.0;

    /** 维度 → 位置 → 上次震动刻。条目只保留冷却窗口内的，见 {@link #onServerTickPost} */
    private static final Map<ResourceKey<Level>, Map<Long, Long>> LAST_QUAKE = new HashMap<>();

    /** 维度 → 本刻待发送的方块 */
    private static final Map<ResourceKey<Level>, Pending> PENDING = new HashMap<>();

    private record Entry(BlockPos position, float height, int durationTicks) {}

    /** 同一批方块共享的高度与时长（同一次技能动作必然一致） */
    private record QuakeShape(float height, int durationTicks) {}

    private static final class Pending {
        private final ServerLevel level;
        private final Map<Long, Entry> entries = new LinkedHashMap<>();

        private Pending(final ServerLevel level) {
            this.level = level;
        }
    }

    private BlockQuakes() {}

    /**
     * 尝试让某个方块进入震动队列。冷却期内会被静默丢弃。
     *
     * @param height        跃起高度（方块），已钳制
     * @param durationTicks 一次完整升落的时长
     */
    public static void trigger(final @NotNull ServerLevel level, final @NotNull BlockPos position,
                               final float height, final int durationTicks) {
        if (height <= 0.0F || durationTicks <= 0) {
            return;
        }

        long now = level.getGameTime();
        long key = position.asLong();
        Map<Long, Long> lastQuake = LAST_QUAKE.computeIfAbsent(level.dimension(), ignored -> new HashMap<>());
        Long lastTick = lastQuake.get(key);

        if (lastTick != null && now - lastTick < COOLDOWN_TICKS) {
            return;
        }

        lastQuake.put(key, now);
        PENDING.computeIfAbsent(level.dimension(), ignored -> new Pending(level))
                .entries.put(key, new Entry(position.immutable(), height, durationTicks));
    }

    @SubscribeEvent
    public static void onServerTickPost(final @NotNull ServerTickEvent.Post event) {
        PENDING.values().forEach(BlockQuakes::flush);
        PENDING.clear();

        // 剪掉冷却窗口外的记录：表大小因此被"冷却期内触发过的位置数"自然限住，不会随时间无限增长
        LAST_QUAKE.forEach((dimension, cooldowns) -> {
            ServerLevel level = event.getServer().getLevel(dimension);

            if (level == null) {
                cooldowns.clear();
                return;
            }

            long now = level.getGameTime();
            cooldowns.values().removeIf(lastTick -> now - lastTick >= COOLDOWN_TICKS);
        });
    }

    private static void flush(final @NotNull Pending pending) {
        if (pending.entries.isEmpty()) {
            return;
        }

        // 按「高度 + 时长」分组：不同强度的效果恰好落在同一刻时，各自保持各自的参数
        Map<QuakeShape, List<Long>> grouped = new LinkedHashMap<>();

        for (Entry entry : pending.entries.values()) {
            grouped.computeIfAbsent(new QuakeShape(entry.height(), entry.durationTicks()), ignored -> new ArrayList<>())
                    .add(entry.position().asLong());
        }

        grouped.forEach((shape, positions) -> send(pending.level, shape, positions));
    }

    private static void send(final @NotNull ServerLevel level, final @NotNull QuakeShape shape, final @NotNull List<Long> positions) {
        for (int start = 0; start < positions.size(); start += BlockQuakePayload.MAX_POSITIONS) {
            List<Long> slice = positions.subList(start, Math.min(start + BlockQuakePayload.MAX_POSITIONS, positions.size()));
            long[] packed = new long[slice.size()];

            double sumX = 0.0;
            double sumY = 0.0;
            double sumZ = 0.0;

            for (int index = 0; index < slice.size(); index++) {
                packed[index] = slice.get(index);
                BlockPos position = BlockPos.of(packed[index]);
                sumX += position.getX() + 0.5;
                sumY += position.getY() + 0.5;
                sumZ += position.getZ() + 0.5;
            }

            double centerX = sumX / slice.size();
            double centerY = sumY / slice.size();
            double centerZ = sumZ / slice.size();

            double maxDistanceSqr = 0.0;

            for (long position : packed) {
                BlockPos pos = BlockPos.of(position);
                maxDistanceSqr = Math.max(maxDistanceSqr, pos.distToCenterSqr(centerX, centerY, centerZ));
            }

            double radiusSqr = Math.pow(Math.sqrt(maxDistanceSqr) + VIEW_MARGIN, 2.0);
            BlockQuakePayload payload = new BlockQuakePayload(packed, shape.height(), shape.durationTicks());

            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(centerX, centerY, centerZ) <= radiusSqr) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
    }
}
