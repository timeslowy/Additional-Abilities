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
 * 其中 w = 1 - 收束属性值（属性上限 0.8 ⇒ w ∈ [0.2, 1]）
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
    private static final double MAX_RESTRICTION = 0.8;

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

    /** 窄相：任意轴对齐盒是否与光束相交（6 轴分离轴测试，精确无漏判） */
    public boolean intersects(final @NotNull AABB box) {
        return intersects(box.getCenter().x, box.getCenter().y, box.getCenter().z,
                box.getXsize() / 2.0, box.getYsize() / 2.0, box.getZsize() / 2.0);
    }

    /** 窄相：整数方块格是否与光束相交 */
    public boolean intersectsBlock(final int x, final int y, final int z) {
        return intersects(x + 0.5, y + 0.5, z + 0.5, 0.5, 0.5, 0.5);
    }

    /**
     * 6 条分离轴 = 光束的 3 条局部轴 + 3 条世界轴。任一轴上投影区间不重叠即判为分离。
     * <p>
     * 只用光束自身 3 条轴是不够的（会在光束两端出现假命中），因此世界轴也必须测。
     */
    private boolean intersects(final double cx, final double cy, final double cz,
                               final double hx, final double hy, final double hz) {
        double ccx = origin.x + direction.x * length / 2.0;
        double ccy = origin.y + direction.y * length / 2.0;
        double ccz = origin.z + direction.z * length / 2.0;

        double dx = cx - ccx;
        double dy = cy - ccy;
        double dz = cz - ccz;

        double halfLength = length / 2.0;

        // 1. 光束轴向
        if (Math.abs(dx * direction.x + dy * direction.y + dz * direction.z)
                > halfLength + hx * Math.abs(direction.x) + hy * Math.abs(direction.y) + hz * Math.abs(direction.z) + EPSILON) {
            return false;
        }

        // 2. 光束横向（right）
        if (Math.abs(dx * right.x + dy * right.y + dz * right.z)
                > halfWidth + hx * Math.abs(right.x) + hy * Math.abs(right.y) + hz * Math.abs(right.z) + EPSILON) {
            return false;
        }

        // 3. 光束纵向（up）
        if (Math.abs(dx * up.x + dy * up.y + dz * up.z)
                > halfHeight + hx * Math.abs(up.x) + hy * Math.abs(up.y) + hz * Math.abs(up.z) + EPSILON) {
            return false;
        }

        // 4~6. 世界三轴：光束在这些轴上的投影半径要按三条局部轴分别累加
        double beamOnX = Math.abs(direction.x) * halfLength + Math.abs(right.x) * halfWidth + Math.abs(up.x) * halfHeight;
        double beamOnY = Math.abs(direction.y) * halfLength + Math.abs(right.y) * halfWidth + Math.abs(up.y) * halfHeight;
        double beamOnZ = Math.abs(direction.z) * halfLength + Math.abs(right.z) * halfWidth + Math.abs(up.z) * halfHeight;

        return Math.abs(dx) <= beamOnX + hx + EPSILON
                && Math.abs(dy) <= beamOnY + hy + EPSILON
                && Math.abs(dz) <= beamOnZ + hz + EPSILON;
    }
}
