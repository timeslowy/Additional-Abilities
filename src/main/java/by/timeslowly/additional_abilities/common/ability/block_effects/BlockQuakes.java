package by.timeslowly.additional_abilities.common.ability.block_effects;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.block_effects.BlockQuakeData.Entry;
import by.timeslowly.additional_abilities.common.network.BlockQuakePayload;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *
 * <h2>状态放在哪里（本次修复的核心）</h2>
 * 冷却表与待发缓冲都挂在 {@code ServerLevel} 的数据附件上（{@code AAAttachments.BLOCK_QUAKE}，
 * 见 {@link BlockQuakeData}），<b>不再</b>用静态 {@code Map<ResourceKey<Level>, …>}。
 * 原先那种写法以维度 ResourceKey 为键，表在世界切换后依然存活，而里面存的是上一个存档的
 * {@code getGameTime()} 数值：进入时间较小的存档后 {@code now - lastQuake} 为负，
 * {@code now - lastTick < COOLDOWN_TICKS} 恒成立，新存档里同坐标的震动会被长期拒绝。
 * 挂到 Level 附件后状态随实例生灭，结构上不可能串味。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public final class BlockQuakes {
    /** 同一位置两次震动的冷却（游戏刻） */
    public static final int COOLDOWN_TICKS = 10;

    /** 假身方块默认视距 64 格，广播半径在此之上留出余量 */
    private static final double VIEW_MARGIN = 64.0;

    /**
     * 音效音量与音高。固定为原版默认值，不随强度变化 ——
     * 音量同时决定可听半径（约 {@code volume × 16} 格），超出该半径的玩家看不到效果也就不必听。
     */
    private static final float SOUND_VOLUME = 1.0F;
    private static final float SOUND_PITCH = 1.0F;

    /** 同一批发包与发声的参数。音效计入分组键，使不同技能的声音不会被合并到一起 */
    private record QuakeShape(float height, int durationTicks, Optional<SoundEvent> sound) {}

    private BlockQuakes() {}

    /**
     * 尝试让某个方块进入震动队列。冷却期内会被静默丢弃。
     *
     * @param height        跃起高度（方块），已钳制
     * @param durationTicks 一次完整升落的时长
     * @param sound         跃起时播放的音效，空则无声
     */
    public static void trigger(final @NotNull ServerLevel level, final @NotNull BlockPos position,
                               final float height, final int durationTicks, final @NotNull Optional<SoundEvent> sound) {
        if (height <= 0.0F || durationTicks <= 0) {
            return;
        }

        level.getData(AAAttachments.BLOCK_QUAKE)
                .enqueue(position, level.getGameTime(), height, durationTicks, sound);
    }

    /**
     * 逐维度冲刷：把本刻缓冲的震动聚合成包发出去，并剪掉冷却窗口外的记录。
     * <p>
     * <b>为什么用 {@code LevelTickEvent.Post} 而不是 {@code ServerTickEvent.Post}</b>：状态挂在
     * {@code ServerLevel} 上，按维度触发天然与数据布局对齐，直接拿到本维度实例与它自己的刻数 ——
     * 旧实现要 {@code server.getLevel(dimension)} 反查，维度取不到时那条分支正是脏数据的来源。
     * <p>
     * 时机仍然满足"当刻触发、当刻发包"：{@code LevelTickEvent.Post} 在本维度
     * {@code ServerLevel#tick} 之后触发，而玩家 tick（{@code PlayerTickEvent.Post}，方块效果正是在那里执行）
     * 位于 {@code ServerLevel#tick} 内部，因此本刻登记的震动当刻就会被冲刷。
     */
    @SubscribeEvent
    public static void onLevelTickPost(final @NotNull LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // 只读探测而不是 getData：本监听器对每个维度每刻都跑，getData 会顺手把默认值
        // 建出来并永久挂在 Level 上 —— 从没触发过震动的维度不该因此长期带着两个空表
        BlockQuakeData data = level.getExistingDataOrNull(AAAttachments.BLOCK_QUAKE);

        if (data == null) {
            return;
        }

        if (data.hasPending()) {
            flush(level, data);
            data.clearPending();
        }

        // 剪掉冷却窗口外的记录：表大小因此被"冷却期内触发过的位置数"自然限住，不会随时间无限增长
        data.dropExpiredCooldowns(level.getGameTime());
    }

    private static void flush(final @NotNull ServerLevel level, final @NotNull BlockQuakeData data) {
        // 按「高度 + 时长 + 音效」分组：不同强度的效果恰好落在同一刻时，各自保持各自的参数
        Map<QuakeShape, List<Long>> grouped = new LinkedHashMap<>();

        for (Entry entry : data.pending().values()) {
            grouped.computeIfAbsent(new QuakeShape(entry.height(), entry.durationTicks(), entry.sound()), ignored -> new ArrayList<>())
                    .add(entry.position().asLong());
        }

        grouped.forEach((shape, positions) -> {
            send(level, shape, positions);
            playSound(level, shape, positions);
        });
    }

    private static void send(final @NotNull ServerLevel level, final @NotNull QuakeShape shape, final @NotNull List<Long> positions) {
        for (int start = 0; start < positions.size(); start += BlockQuakePayload.MAX_POSITIONS) {
            List<Long> slice = positions.subList(start, Math.min(start + BlockQuakePayload.MAX_POSITIONS, positions.size()));
            long[] packed = new long[slice.size()];

            for (int index = 0; index < slice.size(); index++) {
                packed[index] = slice.get(index);
            }

            double[] center = center(slice);
            double maxDistanceSqr = 0.0;

            for (long position : packed) {
                maxDistanceSqr = Math.max(maxDistanceSqr, BlockPos.of(position).distToCenterSqr(center[0], center[1], center[2]));
            }

            double radiusSqr = Math.pow(Math.sqrt(maxDistanceSqr) + VIEW_MARGIN, 2.0);
            BlockQuakePayload payload = new BlockQuakePayload(packed, shape.height(), shape.durationTicks());

            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(center[0], center[1], center[2]) <= radiusSqr) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
    }

    /**
     * 播放一次震动音效。
     * <p>
     * <b>每次技能动作只响一次</b>：声源取该批方块的几何中心，而不是每个方块各响一次 ——
     * 一次范围震动往往同时抬起几十个方块，逐方块播放等于几十个音源同时叠加，
     * 听感上是爆音而不是"一下砸地"。同一批内的方块本就来自同一次动作，中心足以代表声源。
     */
    private static void playSound(final @NotNull ServerLevel level, final @NotNull QuakeShape shape, final @NotNull List<Long> positions) {
        shape.sound().ifPresent(sound -> {
            double[] center = center(positions);

            // 首参为"要排除的玩家"，传 null 表示不排除任何人 —— 施法者本人也应听到声响
            level.playSound(null, center[0], center[1], center[2], sound, SoundSource.BLOCKS, SOUND_VOLUME, SOUND_PITCH);
        });
    }

    /** 一批方块的几何中心（各取方块中心，即整数坐标 +0.5） */
    @Contract("_ -> new")
    private static double @NotNull [] center(final @NotNull List<Long> positions) {
        double sumX = 0.0;
        double sumY = 0.0;
        double sumZ = 0.0;

        for (long position : positions) {
            BlockPos pos = BlockPos.of(position);
            sumX += pos.getX() + 0.5;
            sumY += pos.getY() + 0.5;
            sumZ += pos.getZ() + 0.5;
        }

        int count = positions.size();
        return new double[]{sumX / count, sumY / count, sumZ / count};
    }
}
