package by.timeslowly.additional_abilities.common.ability.geometry;

import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import by.timeslowly.additional_abilities.registry.AAAttributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 「龙息范围收束」的光束几何：一个**贴着视线方向**的旋转长方体（OBB）。
 *
 * <h2>为什么不能继续用 AABB</h2>
 * DS 的 {@code DragonBreathTarget} 把判定区表达为一个**轴对齐**包围盒，
 * 而 {@code getEntities(AABB, …)} / {@code BlockPos.betweenClosedStream(AABB)} 这两个消费端
 * <b>没有几何阈值</b>（{@code targetingMode} 与 {@code target_conditions} 都不是几何判定），
 * 因此「包围盒」就等于「判定区」。
 * <p>
 * 收束本身要求「截面收窄 + 沿视线拉长」，但一个**必须包住射线远端**的轴对齐盒
 * 逐轴都要满足 {@code span ≥ 射程 · |视线在该轴的投影|} —— 视线一转斜，盒就在两个轴上同时伸展，
 * 体积不降反升（实测：R=4、收束 0.8 时，θ=45° 的盒体积是原版的 2.5 倍，
 * 而真实光束只有它的 1/32）。**这是容器的限制，改公式绕不过去。**
 * <p>
 * 所以这里改为：包围盒只当「粗筛区」（{@link #boundingBox()}），真正的命中判定换成
 * {@link #intersects(AABB)} —— 一个薄长的、任意朝向的盒体。粗筛 + 窄相两段式之后，
 * 作用范围才真正随视线旋转，而不是随瞄准角度膨胀。
 *
 * <h2>几何定义</h2>
 * <pre>
 * 原点 origin = 玩家眼睛位置
 * 轴向 direction = 视线单位向量
 * 长度 length = range_multiplier × dragonsurvival:dragon_breath_range × k     （k = 1 / w）
 * 横向半宽 halfWidth  = getScale()      × w        （沿 right，水平横向）
 * 纵向半高 halfHeight = getEyeHeight()/2 × w        （沿 up，垂直方向）
 * 其中 w = 1 - 收束属性值（属性上限 0.9 ⇒ w ∈ [0.1, 1]）
 * </pre>
 * 纵向以**眼睛为中心**是刻意选择：DS 原式把 {@code eyeHeight} 整体放在 +Y 侧，
 * 若改成对 y 跨度直接乘 w，箱体会连带整体下沉到下巴高度。
 * <p>
 * 体积比 ≈ {@code w² · k = w}，即随收束值线性收缩（收束 0.8 时剩 20%）。
 *
 * <h2>复用</h2>
 * 本类不依赖任何 DS 目标类型的具体实现，只要给出「方向 + 射程」即可构造，
 * 因此本模组自己的目标选择器（如 {@code additional_abilities:anti_dragon_breath}，
 * 传 {@code -视线} 即可）以后可以直接一行接入同一套判定。
 */
public record BreathBeam(Vec3 origin, Vec3 direction, Vec3 right, Vec3 up,
                         double length, double halfWidth, double halfHeight) {
    /** 浮点容差：多给一点，宁可多判一个也不要漏判 */
    private static final double EPSILON = 1.0E-4;

    /** 收束属性的上限（{@code AAAttributes.DRAGON_BREATH_RESTRICTION} 的 max） */
    private static final double MAX_RESTRICTION = 0.9;

    /** 截面系数的下限 = 1 - MAX_RESTRICTION */
    private static final double MIN_FACTOR = 1.0 - MAX_RESTRICTION;

    /**
     * 以玩家视线为轴构造光束；**收束属性为 0 时返回 {@code null}**，
     * 调用方应据此回落到 DS 原版路径（零回归）。
     *
     * @param rangeMultiplier 技能 JSON 里的 {@code range_multiplier}
     * @param level           技能当前等级
     */
    public static @Nullable BreathBeam restricted(final Player dragon, final @NotNull LevelBasedValue rangeMultiplier, final int level) {
        return restricted(dragon, dragon.getLookAngle(),
                rangeMultiplier.calculate(level) * dragon.getAttributeValue(DSAttributes.DRAGON_BREATH_RANGE));
    }

    /**
     * 通用重载：显式给出方向与射程（方向无需单位化）。
     *
     * @param direction 光束轴向（本模组目标选择器可传反向视线）
     * @param range     未收束的射程（格）
     * @return 收束后的光束；收束属性 ≤ 0 时为 {@code null}
     */
    public static @Nullable BreathBeam restricted(final @NotNull Player dragon, final Vec3 direction, final double range) {
        double restriction = dragon.getAttributeValue(AAAttributes.DRAGON_BREATH_RESTRICTION);

        if (restriction <= 0) {
            return null;
        }

        double factor = Math.max(MIN_FACTOR, 1.0 - Math.min(restriction, MAX_RESTRICTION));

        Vec3 axis = direction.lengthSqr() < EPSILON ? new Vec3(0, 0, 1) : direction.normalize();

        // 正交基：right = 轴 × 世界上方（视线正上/正下时退化，任取一个水平轴）
        Vec3 right = axis.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < EPSILON ? new Vec3(1, 0, 0) : right.normalize();

        Vec3 up = right.cross(axis).normalize();

        return new BreathBeam(dragon.getEyePosition(), axis, right, up,
                range / factor,
                dragon.getScale() * factor,
                dragon.getEyeHeight() / 2.0 * factor);
    }

    /**
     * 光束自身的轴对齐包围盒（**精确**：按 OBB 的三条局部轴求各世界轴的支撑区间）。
     * 仅用于粗筛（{@code getEntities} / 方块枚举）与调试显示；斜视时它会很大，这是几何必然。
     */
    public @NotNull AABB boundingBox() {
        Vec3 half = new Vec3(
                Math.abs(direction.x) * length / 2.0 + Math.abs(right.x) * halfWidth + Math.abs(up.x) * halfHeight,
                Math.abs(direction.y) * length / 2.0 + Math.abs(right.y) * halfWidth + Math.abs(up.y) * halfHeight,
                Math.abs(direction.z) * length / 2.0 + Math.abs(right.z) * halfWidth + Math.abs(up.z) * halfHeight);

        Vec3 center = origin.add(direction.scale(length / 2.0));
        return new AABB(center.subtract(half), center.add(half));
    }

    /** 窄相：任意轴对齐盒是否与光束相交（完整 OBB vs AABB 分离轴测试） */
    public boolean intersects(final @NotNull AABB box) {
        return intersects(box.getCenter().x, box.getCenter().y, box.getCenter().z,
                box.getXsize() / 2.0, box.getYsize() / 2.0, box.getZsize() / 2.0);
    }

    /** 窄相：整数方块格是否与光束相交 */
    public boolean intersectsBlock(final int x, final int y, final int z) {
        return intersects(x + 0.5, y + 0.5, z + 0.5, 0.5, 0.5, 0.5);
    }

    /**
     * <b>完整</b> OBB（光束）vs AABB（盒）分离轴测试：15 条候选轴 —— 光束 3 条局部轴、
     * 盒的 3 条世界轴、以及两两叉积的 9 条轴。任一轴上投影区间不重叠即判为分离。
     *
     * <h2>为什么 6 条分离轴不够</h2>
     * 只用「光束 3 轴 + 世界 3 轴」时，<b>棱-棱交叉</b>那一类分离方向没有任何候选轴能表达，
     * 于是会产生<b>假命中</b>：盒明明在光束外侧，六轴测试却全部重叠。固定反例（见
     * {@code BreathBeamIntersectionTest}）：
     * <pre>
     * origin = (0, 0, 0)；direction = normalize(0.7, 0.4, 0.59)；length = 20
     * halfWidth = 0.2；halfHeight = 0.8；盒 = 方块 (5, 4, 3) 的完整碰撞箱
     * 预期：不相交
     * 实测：六轴全部重叠（最大间隙 -0.0036）→ 误判为相交；
     *      叉积轴 {@code direction × 世界 X} 上未归一化间隙 +0.1839 → 正确判定为分离
     * </pre>
     * 即旧注释「精确无漏判」只对<b>漏判</b>成立，对<b>假命中</b>不成立。
     *
     * <h2>投影半径的统一算法</h2>
     * 对任意候选轴 {@code a}，两侧的投影半径都是「各自三条局部轴在 {@code a} 上的分量 × 对应半长」
     * 之和：光束贡献 {@code Σ r_i·|axis_i·a|}，盒贡献 {@code Σ h_i·|a_i|}。光束自己的三条局部轴
     * 也走同一条式子（正交基下 {@code direction·a} 恰为 1、另两项为 0，结果就等于该轴半长），
     * 因此 15 条轴共用<b>一个</b>判定函数，没有特例分支。
     *
     * <h2>叉积轴为什么不参与"未归一化比较"</h2>
     * 分离判据的每一项（中心距投影、两侧半径）对候选轴都是<b>线性</b>的，所以形式上可以直接比较
     * 未归一化的叉积。但 {@link #EPSILON} 是<b>绝对</b>容差：除以 {@code |a|} 后它变成
     * {@code EPSILON / |a|} —— 轴越短，等效容差越大。当 {@code |a|} 接近退化阈值时等效容差会被放大
     * 若干个数量级，反而重新引入<b>假命中</b>。因此这里把叉积轴显式
     * <b>归一化</b>后再交给同一个判定函数，让 15 条轴的容差口径完全一致。
     *
     * <h2>退化轴</h2>
     * 叉积模长趋近 0 表示两条轴平行，该轴不携带新的分离信息（它退化成的两条面法线已经测过），
     * 必须跳过 —— 否则 {@code |a|→0} 会把两侧半径同时压成 0，浮点噪声就可能被当成"分离"。
     */
    private boolean intersects(final double cx, final double cy, final double cz,
                               final double hx, final double hy, final double hz) {
        double halfLength = length / 2.0;

        // 光束中心
        double ccx = origin.x + direction.x * halfLength;
        double ccy = origin.y + direction.y * halfLength;
        double ccz = origin.z + direction.z * halfLength;

        // 两心之差
        double dx = cx - ccx;
        double dy = cy - ccy;
        double dz = cz - ccz;

        // 1~3. 光束的三条局部轴（轴向 / 横向 / 纵向）—— 已是单位向量
        if (separated(dx, dy, dz, hx, hy, hz, direction.x, direction.y, direction.z)
                || separated(dx, dy, dz, hx, hy, hz, right.x, right.y, right.z)
                || separated(dx, dy, dz, hx, hy, hz, up.x, up.y, up.z)) {
            return false;
        }

        // 4~6. 盒的三条世界轴
        if (separated(dx, dy, dz, hx, hy, hz, 1.0, 0.0, 0.0)
                || separated(dx, dy, dz, hx, hy, hz, 0.0, 1.0, 0.0)
                || separated(dx, dy, dz, hx, hy, hz, 0.0, 0.0, 1.0)) {
            return false;
        }

        // 7~15. 九条叉积轴：光束每条局部轴 × 盒的每条世界轴。
        // 世界轴是单位轴，因此 a × e_i 每个分量只剩两项（a × X 恒为 (0, a_z, -a_y)，依此类推）。
        return !crossAxisSeparates(dx, dy, dz, hx, hy, hz, 0.0, direction.z, -direction.y)
                && !crossAxisSeparates(dx, dy, dz, hx, hy, hz, -direction.z, 0.0, direction.x)
                && !crossAxisSeparates(dx, dy, dz, hx, hy, hz, direction.y, -direction.x, 0.0)
                && !crossAxisSeparates(dx, dy, dz, hx, hy, hz, 0.0, right.z, -right.y)
                && !crossAxisSeparates(dx, dy, dz, hx, hy, hz, -right.z, 0.0, right.x)
                && !crossAxisSeparates(dx, dy, dz, hx, hy, hz, right.y, -right.x, 0.0)
                && !crossAxisSeparates(dx, dy, dz, hx, hy, hz, 0.0, up.z, -up.y)
                && !crossAxisSeparates(dx, dy, dz, hx, hy, hz, -up.z, 0.0, up.x)
                && !crossAxisSeparates(dx, dy, dz, hx, hy, hz, up.y, -up.x, 0.0);
    }

    /**
     * 单条叉积轴：退化（与某条面法线平行）则跳过；否则归一化后按同一口径判定。
     *
     * @return {@code true} 表示确认分离
     */
    private boolean crossAxisSeparates(final double dx, final double dy, final double dz,
                                       final double hx, final double hy, final double hz,
                                       final double axisX, final double axisY, final double axisZ) {
        double axisLength = Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);

        if (axisLength < EPSILON) {
            return false;
        }

        return separated(dx, dy, dz, hx, hy, hz, axisX / axisLength, axisY / axisLength, axisZ / axisLength);
    }

    /**
     * 单条<b>单位</b>候选轴上的分离判定：{@code |D·a| > 光束半径 + 盒半径} 即为分离。
     * 两侧半径都按 {@link #intersects} 说明里的统一式子求。
     */
    private boolean separated(final double dx, final double dy, final double dz,
                              final double hx, final double hy, final double hz,
                              final double axisX, final double axisY, final double axisZ) {
        double beamRadius = length / 2.0 * Math.abs(direction.x * axisX + direction.y * axisY + direction.z * axisZ)
                + halfWidth * Math.abs(right.x * axisX + right.y * axisY + right.z * axisZ)
                + halfHeight * Math.abs(up.x * axisX + up.y * axisY + up.z * axisZ);

        double boxRadius = Math.abs(axisX) * hx + Math.abs(axisY) * hy + Math.abs(axisZ) * hz;
        double distance = Math.abs(dx * axisX + dy * axisY + dz * axisZ);

        return distance > beamRadius + boxRadius + EPSILON;
    }
}
