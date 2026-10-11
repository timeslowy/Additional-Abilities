package by.timeslowly.additional_abilities.common.ability.geometry;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T05 / F19 的回归测试：光束窄相必须是<b>完整</b>的 OBB vs AABB 分离轴测试（15 条轴），
 * 而不是旧实现的 6 条。
 *
 * <h2>为什么这些用例能抓到回归</h2>
 * 核心是 {@link #fixedCounterexampleFromReportIsRejected()}：它用的是审查报告里给出的固定数值反例，
 * 期望值由<b>手算 / 独立脚本</b>得出（不依赖被测代码）。在旧的 6 轴实现下：
 * <ul>
 *     <li>6 条面法线轴<b>全部重叠</b>（最大间隙 −0.0036）→ 旧实现返回 {@code true}（假命中）；</li>
 *     <li>叉积轴 {@code direction × 世界X} 上未归一化间隙 +0.183917、归一化后 +0.2578 → 完整 SAT 返回 {@code false}。</li>
 * </ul>
 * 因此把 9 条叉积轴删掉，这条用例立刻变红 —— 它验证的正是 F19 那个缺失的方向。
 *
 * <h2>两组"证书"式断言（不是自证）</h2>
 * {@link #randomizedAgreementWithSeparatingCertificates()} 用<b>可独立验证的证书</b>来交叉检验，
 * 而不是拿实现和自己比。配套的 {@link #hasSeparatingDirection} 的 javadoc 说明了
 * 「15 条轴里找得到分离」与「真的不相交」为何是<b>充要</b>关系，因此两个方向的断言都成立：
 * <ul>
 *     <li>实现说"不相交"时，必须真能在 15 条轴上找出分离证书（否则是<b>漏判</b>）；
 *     <li>实现说"相交"时，必须<b>找不出</b>任何分离证书（否则是<b>假命中</b> —— 正是 F19 的形态）。
 * </ul>
 *
 * <h2>已知且刻意的行为：相切算相交</h2>
 * {@link #touchingIsTreatedAsIntersecting()} 固定下容差语义：{@code EPSILON} 让"恰好贴面"判为相交
 * （宁可多判一个也不要漏判），这是设计取舍而非缺陷，用例把它钉住以免被无意改掉。
 */
class BreathBeamIntersectionTest {
    /** 与 {@code BreathBeam} 内部一致的容差语义；用例里复用它来构造"恰好相切"的场景 */
    private static final double TOLERANCE = 1.0E-4;

    /**
     * 按与 {@code BreathBeam#restricted} <b>逐行相同</b>的规则构造光束。
     * <p>
     * 生产路径需要真实 {@code Player} 才能调用 {@code restricted}（要读属性与眼高），
     * 单元测试造不出来，因此这里复刻它的正交基构造 —— 包括视线正上/正下时的退化回退，
     * 那是 {@link #degenerateDirectionDoesNotCrash()} 要覆盖的分支。
     */
    private static BreathBeam beam(final Vec3 origin, final Vec3 rawDirection, final double length,
                                   final double halfWidth, final double halfHeight) {
        Vec3 axis = rawDirection.lengthSqr() < TOLERANCE ? new Vec3(0, 0, 1) : rawDirection.normalize();

        Vec3 right = axis.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < TOLERANCE ? new Vec3(1, 0, 0) : right.normalize();

        Vec3 up = right.cross(axis).normalize();

        return new BreathBeam(origin, axis, right, up, length, halfWidth, halfHeight);
    }

    // ================================================================ F19：固定反例

    @Test
    @DisplayName("F19：报告中的固定反例必须判为不相交（六轴实现会假命中）")
    void fixedCounterexampleFromReportIsRejected() {
        BreathBeam beam = beam(Vec3.ZERO, new Vec3(0.7, 0.4, 0.59), 20.0, 0.2, 0.8);

        // 方块 (5, 4, 3) 的完整格：中心 (5.5, 4.5, 3.5)，半边长 (0.5, 0.5, 0.5)
        assertFalse(beam.intersectsBlock(5, 4, 3),
                "预期不相交：六条面法线轴全部重叠，只有叉积轴 direction×世界X 能分离（间隙 +0.2578）");

        assertFalse(beam.intersects(new AABB(5, 4, 3, 6, 5, 4)),
                "同一个盒以 AABB 形式传入时结论必须一致（方块分支与实体分支共用同一条窄相）");
    }

    @Test
    @DisplayName("F19：反例的对照 —— 同一光束确实命中它正前方与近旁的格子")
    void counterexampleBeamStillHitsWhatItShould() {
        BreathBeam beam = beam(Vec3.ZERO, new Vec3(0.7, 0.4, 0.59), 20.0, 0.2, 0.8);

        // 光束沿 (0.7, 0.4, 0.59) 方向，t = 8 处的中心约 (5.6, 3.2, 4.7)；
        // 取它所在的格与紧邻轴向格，必须仍然命中 —— 防止"把叉积轴加过头"变成漏判
        assertTrue(beam.intersectsBlock(5, 3, 4), "光束轴线上 t=8 附近的格子必须命中");
        assertTrue(beam.intersectsBlock(6, 3, 5), "轴线附近的格子必须命中");
    }

    // ================================================================ 基本几何

    @Test
    @DisplayName("几何：光束正前方的格命中，偏出截面的格不命中")
    void axisAlignedBeamHitsAlongItsAxisOnly() {
        BreathBeam beam = beam(Vec3.ZERO, new Vec3(1, 0, 0), 10.0, 0.5, 0.5);

        assertTrue(beam.intersectsBlock(5, 0, 0), "轴线上的格（距轴 0.87 格内）应命中");
        assertFalse(beam.intersectsBlock(5, 5, 0), "竖直方向偏出截面 4 格以上，不应命中");
        assertFalse(beam.intersectsBlock(-5, 0, 0), "光束只朝 +X 延伸，身后不应命中");
        assertFalse(beam.intersectsBlock(20, 0, 0), "超出长度（半长 5）之外不应命中");
    }

    @Test
    @DisplayName("几何：光束正上方时仍能命中脚下的格（退化基已回退）")
    void verticalBeamHitsBlockAbove() {
        BreathBeam beam = beam(Vec3.ZERO, new Vec3(0, 1, 0), 10.0, 0.5, 0.5);

        assertTrue(beam.intersectsBlock(0, 5, 0), "竖直光束轴线上的格应命中");
        assertFalse(beam.intersectsBlock(4, 5, 0), "水平方向偏出截面 3 格以上，不应命中");
    }

    @Test
    @DisplayName("几何：容差语义被钉住 —— 相切算相交，超出容差的缝隙才算分离")
    void touchingIsTreatedAsIntersecting() {
        // 光束沿 +X、长 10（半长 5）、横向（Z）与纵向（Y）半径各 0.5，光束中心在 (5, 0, 0)
        BreathBeam beam = beam(Vec3.ZERO, new Vec3(1, 0, 0), 10.0, 0.5, 0.5);

        // Y 方向：光束区间 [−0.5, 0.5]
        // 该盒 Y 中心 0.55、半高 0.05 → 上边界 0.6、下边界 0.5，与光束恰好相切（间隙 0.0）
        assertTrue(beam.intersects(new AABB(5.0, 0.5, -0.1, 5.1, 0.6, 0.1)),
                "恰好相切应判为相交（判据是严格大于，再加 EPSILON 容差 → 宁可多判一个，避免细光束漏判）");

        // 同一个盒沿 Y 再抬高一点点：缝隙 = 0.01，远大于 EPSILON(1e-4) → 必须判为分离
        assertFalse(beam.intersects(new AABB(5.0, 0.51, -0.1, 5.1, 0.61, 0.1)),
                "缝隙 0.01 已远超容差，必须判为分离");
    }

    @Test
    @DisplayName("几何：视线指向六个轴向（含退化回退分支）时原点格必命中")
    void degenerateDirectionDoesNotCrash() {
        // 视线沿世界轴时，光束的某条局部轴与它叉积为零（退化），right 还要走 axis×世界上方 为零的回退。
        // 这些分支必须既不抛异常、也不把结果算成"处处不相交" —— 用原点格钉住。
        Vec3[] directions = {
                new Vec3(0, 1, 0), new Vec3(0, -1, 0),
                new Vec3(1, 0, 0), new Vec3(-1, 0, 0),
                new Vec3(0, 0, 1), new Vec3(0, 0, -1),
        };

        for (Vec3 direction : directions) {
            BreathBeam beam = beam(Vec3.ZERO, direction, 8.0, 0.4, 0.4);

            assertTrue(beam.intersectsBlock(0, 0, 0),
                    "沿 " + direction + " 的光束必然覆盖起点处的格（原点在光束端点上）");
        }
    }

    // ================================================================ 随机差分（证书式）

    @Test
    @DisplayName("随机差分：不相交时必须有分离证书，有分离证书时必须判不相交")
    void randomizedAgreementWithSeparatingCertificates() {
        Random random = new Random(20261010L);

        int disjoint = 0;
        int intersecting = 0;

        for (int index = 0; index < 4000; index++) {
            BreathBeam beam = randomBeam(random);

            // 一半的盒贴着光束轴线附近生成、一半完全随机：
            // 只做后者时几乎全是"远在天边"的分离样本，相交分支覆盖不到（首版正是因此空转）
            AABB box = random.nextBoolean() ? boxNearBeam(beam, random) : boxAnywhere(random);

            boolean reported = beam.intersects(box);
            boolean separable = hasSeparatingDirection(beam, box);

            if (reported) {
                intersecting++;
                assertFalse(separable,
                        "假命中（F19 的形态）：实现说相交，但存在分离方向 " + box + " / " + beam);
            } else {
                disjoint++;
                assertTrue(separable,
                        "漏判：实现说不相交，却在 15 条规范轴上找不到分离证书 " + box + " / " + beam);
            }
        }

        // 两类样本都要有足够数量，否则这条用例可能因为"全落在一侧"而空转
        assertTrue(disjoint > 200, "样本里应有足量不相交情形，实际 " + disjoint);
        assertTrue(intersecting > 200, "样本里应有足量相交情形，实际 " + intersecting);
    }

    @Test
    @DisplayName("随机差分：方块分支与实体分支对同一个盒必须给出一致结论")
    void blockAndBoxEntryPointsAgree() {
        Random random = new Random(951L);

        for (int index = 0; index < 2000; index++) {
            BreathBeam beam = randomBeam(random);
            int x = random.nextInt(21) - 10;
            int y = random.nextInt(21) - 10;
            int z = random.nextInt(21) - 10;

            boolean asBlock = beam.intersectsBlock(x, y, z);
            boolean asBox = beam.intersects(new AABB(x, y, z, x + 1, y + 1, z + 1));

            assertTrue(asBlock == asBox,
                    "同一个格两种入口结论必须一致：block=" + asBlock + " box=" + asBox + " at " + x + "," + y + "," + z);
        }
    }

    // ================================================================ 测试辅助

    private static BreathBeam randomBeam(final Random random) {
        Vec3 origin = new Vec3(random.nextDouble() * 10 - 5, random.nextDouble() * 10 - 5, random.nextDouble() * 10 - 5);
        Vec3 direction = new Vec3(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1);

        // 偶尔制造"正好沿某条世界轴"的退化输入，覆盖叉积趋零的分支
        if (random.nextInt(6) == 0) {
            direction = new Vec3(random.nextBoolean() ? 1 : 0, 0, 0);
        } else if (random.nextInt(6) == 0) {
            direction = new Vec3(0, random.nextBoolean() ? 1 : 0, 0);
        }

        double length = 1.0 + random.nextDouble() * 29.0;
        double halfWidth = 0.05 + random.nextDouble() * 1.45;
        double halfHeight = 0.05 + random.nextDouble() * 1.95;

        return beam(origin, direction, length, halfWidth, halfHeight);
    }

    /**
     * 贴着一个随机点生成盒：以光束轴线上随机一点为基准，再按截面尺度做小幅偏移。
     * <p>
     * 这样得到的盒有一半左右会与光束真交（偏移量落在截面半径内），另一半刚好在外侧 ——
     * 正是最容易同时暴露"假命中"与"漏判"的<b>临界区域</b>，而不是大片无关的远距离样本。
     */
    private static AABB boxNearBeam(final BreathBeam beam, final Random random) {
        double t = random.nextDouble() * beam.length();
        Vec3 onAxis = beam.origin().add(beam.direction().scale(t));

        // 偏移量按截面尺度取：横向 ±halfWidth、纵向 ±halfHeight，再各自放大一点点
        double offsetRight = (random.nextDouble() * 2 - 1) * beam.halfWidth() * 1.6;
        double offsetUp = (random.nextDouble() * 2 - 1) * beam.halfHeight() * 1.6;

        Vec3 center = onAxis.add(beam.right().scale(offsetRight)).add(beam.up().scale(offsetUp));

        double hx = 0.1 + random.nextDouble() * 0.9;
        double hy = 0.1 + random.nextDouble() * 0.9;
        double hz = 0.1 + random.nextDouble() * 0.9;

        return new AABB(center.x - hx, center.y - hy, center.z - hz, center.x + hx, center.y + hy, center.z + hz);
    }

    private static AABB boxAnywhere(final Random random) {
        double cx = random.nextDouble() * 24 - 12;
        double cy = random.nextDouble() * 24 - 12;
        double cz = random.nextDouble() * 24 - 12;

        double hx = 0.5 + random.nextDouble();
        double hy = 0.5 + random.nextDouble();
        double hz = 0.5 + random.nextDouble();

        return new AABB(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
    }

    /**
     * 在两凸集的标准 15 条轴上搜寻<b>分离证书</b>。
     * <p>
     * 为什么这 15 条就是"完整"的证书集合：对 OBB vs AABB 这类凸多面体，分离超平面定理保证
     * 「两集不相交」⟺「存在一个分离方向，且该方向可取为两侧的某个面法线或某对棱的叉积」。
     * 两侧的面法线分别是光束的 3 条局部轴与盒的 3 条世界轴，棱方向就是它们，因此候选叉积恰好 9 条
     * —— 合计 15 条。于是「15 条轴里找得到分离」与「真的不相交」<b>互为充要</b>，
     * 这个判据不会把相交误报成可分离，两个方向的断言都成立。
     * <p>
     * 反过来说：它也就<b>不</b>是"和实现比大小" —— 期望值由这条几何定理独立给出，
     * 实现若少测某条轴（如旧版的 6 轴），这里就会立刻发现"实现说相交、证书说分离"。
     */
    private static boolean hasSeparatingDirection(final BreathBeam beam, final AABB box) {
        Vec3 center = box.getCenter();
        double hx = box.getXsize() / 2.0;
        double hy = box.getYsize() / 2.0;
        double hz = box.getZsize() / 2.0;

        // 光束中心
        Vec3 beamCenter = beam.origin().add(beam.direction().scale(beam.length() / 2.0));
        Vec3 d = center.subtract(beamCenter);

        // 15 条规范轴：光束 3 条局部轴、盒 3 条世界轴、两两叉积 9 条
        if (separates(beam, d, hx, hy, hz, beam.direction())
                || separates(beam, d, hx, hy, hz, beam.right())
                || separates(beam, d, hx, hy, hz, beam.up())) {
            return true;
        }

        Vec3[] worldAxes = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
        Vec3[] beamAxes = {beam.direction(), beam.right(), beam.up()};

        for (Vec3 world : worldAxes) {
            if (separates(beam, d, hx, hy, hz, world)) {
                return true;
            }
        }

        for (Vec3 axis : beamAxes) {
            for (Vec3 world : worldAxes) {
                Vec3 cross = axis.cross(world);

                if (cross.length() > TOLERANCE && separates(beam, d, hx, hy, hz, cross.normalize())) {
                    return true;
                }
            }
        }

        return false;
    }

    /** 单条<b>单位</b>轴上的分离判据（与实现同口径，但由测试独立计算，用于做证书校验） */
    private static boolean separates(final BreathBeam beam, final Vec3 d,
                                     final double hx, final double hy, final double hz, final Vec3 axis) {
        double beamRadius = beam.length() / 2.0 * Math.abs(beam.direction().dot(axis))
                + beam.halfWidth() * Math.abs(beam.right().dot(axis))
                + beam.halfHeight() * Math.abs(beam.up().dot(axis));

        double boxRadius = Math.abs(axis.x) * hx + Math.abs(axis.y) * hy + Math.abs(axis.z) * hz;

        return Math.abs(d.dot(axis)) > beamRadius + boxRadius + TOLERANCE;
    }
}
