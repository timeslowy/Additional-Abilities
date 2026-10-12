package by.timeslowly.additional_abilities.common.ability.block_effects;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T10：发光与震动共用的<b>批几何辅助</b>必须与抽取前的行为逐字一致。
 *
 * <h2>为什么这条测试要紧</h2>
 * {@link BlockBatchGeometry} 是从 {@link BlockGlows} 与 {@link BlockQuakes} 里抽出来的
 * "确切重复"（审查报告 §5.1）。这类抽取的风险不是"新逻辑写错"，而是<b>抽取时改变了数值口径</b>
 * —— 而这里的数值直接决定"哪些玩家能收到包"：
 * <ul>
 *     <li>{@code center} 若从"方块中心"退化成"方块角点"，整批方块的广播圆心会偏移半格；</li>
 *     <li>{@code radiusSqr} 若写成先加余量再开方（{@code sqrt(maxSqr + margin)}），
 *         余量的单位就从"格"变成了"格的平方"，广播范围会缩水若干格 ——
 *         表现为远处的发光/震动<b>静默不发包</b>，与 F06 那类失效同型。</li>
 * </ul>
 * 两种错误都不会有任何日志，因此必须有断言钉住。
 *
 * <h2>为什么用列表与数组两种入口</h2>
 * 发光侧聚合的是 {@code long[]}（包内的打包坐标），震动侧聚合的是 {@code List<Long>}
 * （待发队列）。两条路径都必须给出同一个中心，否则同一个动作在两套调度器下会广播到不同的范围。
 * 本类同时覆盖两者，并断言两条重载<b>互相一致</b>。
 */
class BlockBatchGeometryTest {
    /** 与两处调度器一致的余量（格） */
    private static final double MARGIN = 64.0;

    private static long packed(final int x, final int y, final int z) {
        return BlockPos.asLong(x, y, z);
    }

    // ================================================================ center：必须是方块中心

    @Test
    @DisplayName("单个方块的中心 = 整数坐标 + 0.5（不是角点）")
    void singleBlockCenterIsBlockCenter() {
        double[] center = BlockBatchGeometry.center(new long[]{packed(3, 64, -7)});

        assertEquals(3.5, center[0], 1.0E-9);
        assertEquals(64.5, center[1], 1.0E-9);
        assertEquals(-6.5, center[2], 1.0E-9);
    }

    @Test
    @DisplayName("两个方块的中心落在正中（中点也是半整数）")
    void twoBlocksCenterIsTheirMidpoint() {
        double[] center = BlockBatchGeometry.center(new long[]{packed(0, 64, 0), packed(2, 64, 0)});

        assertEquals(1.5, center[0], 1.0E-9, "0.5 与 2.5 的中点是 1.5");
        assertEquals(64.5, center[1], 1.0E-9);
        assertEquals(0.5, center[2], 1.0E-9);
    }

    @Test
    @DisplayName("数组入口与列表入口给出同一个中心（两条调度器口径一致）")
    void arrayAndListEntryPointsAgree() {
        long[] positions = {packed(0, 64, 0), packed(5, 70, -3), packed(-4, 62, 9)};
        List<Long> asList = List.of(positions[0], positions[1], positions[2]);

        double[] fromArray = BlockBatchGeometry.center(positions);
        double[] fromList = BlockBatchGeometry.center(asList);

        assertEquals(fromArray[0], fromList[0], 1.0E-9);
        assertEquals(fromArray[1], fromList[1], 1.0E-9);
        assertEquals(fromArray[2], fromList[2], 1.0E-9);
    }

    // ================================================================ radiusSqr：余量的单位是"格"

    @Test
    @DisplayName("单个方块的广播半径 = (0 + margin)² —— 余量按格计，不是平方")
    void singleBlockRadiusIsMarginSquared() {
        long[] positions = {packed(0, 64, 0)};
        double[] center = BlockBatchGeometry.center(positions);

        assertEquals(MARGIN * MARGIN, BlockBatchGeometry.radiusSqr(positions, center, MARGIN), 1.0E-6,
                "最远方块到中心距离为 0，半径即 margin 本身");
    }

    @Test
    @DisplayName("半径公式是 (最远距离 + margin)²，而不是 maxSqr + margin")
    void radiusUsesDistancePlusMarginNotSquaredMargin() {
        // 一个方块，最远距离 0；另一组把 margin 当成"平方上的增量"就会得到不同的值
        long[] positions = {packed(0, 64, 0), packed(6, 64, 8)};
        double[] center = BlockBatchGeometry.center(positions);

        // 中心 (3.5, 64.5, 4.5)，最远方块中心 (6.5, 64.5, 8.5) → 距离 sqrt(9 + 16) = 5
        double expected = Math.pow(5.0 + MARGIN, 2.0);

        assertEquals(expected, BlockBatchGeometry.radiusSqr(positions, center, MARGIN), 1.0E-6);

        // 反例：若误写成 sqrt(maxSqr + margin)，结果会明显偏小 —— 断言两者不等，说明本用例有区分力
        double wrong = Math.sqrt(25.0 + MARGIN);
        assertTrue(Math.abs(expected - wrong) > 1.0,
                "正确的半径必须与'先加余量再开方'的错误写法明显不同，否则本用例抓不到那个错误");
    }

    @Test
    @DisplayName("余量为 0 时半径恰为最远方块的距离平方（覆盖最远那一块）")
    void zeroMarginCoversTheFarthestBlock() {
        long[] positions = {packed(0, 64, 0), packed(6, 64, 8)};
        double[] center = BlockBatchGeometry.center(positions);

        assertEquals(25.0, BlockBatchGeometry.radiusSqr(positions, center, 0.0), 1.0E-9);
    }

    @Test
    @DisplayName("覆盖的是最远那一块，而不是第一块（逐项取最大）")
    void radiusTakesTheMaximumOverAllPositions() {
        // 故意把最远的放在中间，使"只看首项"的实现失败
        long[] positions = {packed(0, 64, 0), packed(100, 64, 0), packed(1, 64, 0)};
        double[] center = BlockBatchGeometry.center(positions);

        // 中心 x = (0.5 + 100.5 + 1.5)/3 = 34.1666…，最远是 x=100.5 → 距离 66.3333…
        double expected = Math.pow(66.33333333333333 + MARGIN, 2.0);

        assertEquals(expected, BlockBatchGeometry.radiusSqr(positions, center, MARGIN), 1.0E-3);
    }

    @Test
    @DisplayName("离散很远的方块仍取两者中更远的那个（跨施法者合并的那批）")
    void radiusCoversBothEndsOfAWideBatch() {
        long[] positions = {packed(0, 64, 0), packed(100, 64, 0)};
        double[] center = BlockBatchGeometry.center(positions);

        // 中心 50.5，两端各距 50 → 半径 = (50 + margin)²
        assertEquals(50.5, center[0], 1.0E-9, "0.5 与 100.5 的中点是 50.5");
        assertEquals(Math.pow(50.0 + MARGIN, 2.0), BlockBatchGeometry.radiusSqr(positions, center, MARGIN), 1.0E-6);
    }
}
