package by.timeslowly.additional_abilities.common.ability.block_effects;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@link BlockGlows} 与 {@link BlockQuakes} 共用的两个纯几何 / 切包辅助。
 *
 * <h2>共享范围为什么只有这两件事</h2>
 * 两个调度器的<b>生命周期不同</b>（发光是"条目 + 每 20 刻续命 + 到期自然消失"，
 * 震动是"位置冷却 + 每刻冲刷一次性待发队列"），因此状态、分组键、清理口径都各自保留，
 * <b>刻意不合并成一个通用调度器</b> —— 只消除确切重复，不强行统一。
 * 真正逐字重复、且与两者生命周期无关的只有"一片方块要广播到多远"这一件事：
 * <ol>
 *     <li>算一片方块的几何中心；</li>
 *     <li>以中心为圆心、覆盖最远那一块再外扩固定余量，得到广播半径。</li>
 * </ol>
 * <b>切包循环刻意不抽</b>：发光侧的待发项是 {@code (位置, 剩余时长)} 二元组、震动侧是裸位置，
 * 两者的切片元素类型不同，抽成泛型只会换来一个每帧都要装箱的中间层，
 * 而循环本身只有三行（真正共享的是<b>距离辅助</b>，不是两个调度器）。
 *
 * <h2>中心的定义</h2>
 * 各项取<b>方块中心</b>（整数坐标 {@code +0.5}）后求算术平均，与两处原先的实现逐字一致。
 * 用方块中心而不是方块角点，是为了让"中心的距离"与玩家位置的距离可比。
 */
final class BlockBatchGeometry {
    private BlockBatchGeometry() {
        // 纯函数
    }

    /**
     * 一片方块的几何中心（各取方块中心）。
     *
     * @param positions 已打包的方块坐标（{@link BlockPos#asLong()}），必须非空
     * @return {@code [x, y, z]}
     */
    @Contract("_ -> new")
    static double @NotNull [] center(final long @NotNull [] positions) {
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

    /** 同 {@link #center(long[])}，入参为列表（震动侧待发队列按位置列表聚合）。 */
    @Contract("_ -> new")
    static double @NotNull [] center(final @NotNull List<Long> positions) {
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

    /**
     * 本片方块的广播半径平方：以中心为圆心，<b>覆盖最远那一块</b>再外扩 {@code margin} 格。
     * <p>
     * 先开方再加余量（而不是直接对平方和加余量），因为 {@code margin} 是<b>格</b>为单位的：
     * {@code sqrt(maxDistSqr) + margin} 才是"最远方块的距离再加 margin 格"。
     *
     * @param positions 已打包的方块坐标
     * @param center    该片的几何中心（{@link #center(long[])}）
     * @param margin    额外余量（格）
     */
    static double radiusSqr(final long @NotNull [] positions, final double @NotNull [] center, final double margin) {
        double maxDistanceSqr = 0.0;

        for (long position : positions) {
            maxDistanceSqr = Math.max(maxDistanceSqr,
                    BlockPos.of(position).distToCenterSqr(center[0], center[1], center[2]));
        }

        return Math.pow(Math.sqrt(maxDistanceSqr) + margin, 2.0);
    }
}
