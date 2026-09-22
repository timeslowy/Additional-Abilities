package by.timeslowly.additional_abilities.common.ability.domains;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * 领域形状（{@code additional_abilities:domain} 目标类型的 {@code shape} 字段）。
 * <p>
 * 三个取值共用同一套「包围盒粗筛 + 形状精筛」的两段式判定，与 DS 内置目标类型的做法一致：
 * 先用一个轴对齐盒把候选集缩到最小，再在盒内做一次真实形状判定。
 *
 * <table border="1">
 *     <caption>各形状的包围盒与精筛</caption>
 *     <tr><th>形状</th><th>包围盒</th><th>精筛</th></tr>
 *     <tr><td>{@link #SPHERE}</td><td>锚点 ± {@code radius}（三轴等长）</td>
 *         <td>三维距离 ≤ {@code radius}（剔除包围盒的 8 个角）</td></tr>
 *     <tr><td>{@link #CUBE}</td><td>锚点 ± {@code radius}（三轴等长）</td><td>无（包围盒即判定区）</td></tr>
 *     <tr><td>{@link #CYLINDER}</td><td>水平 ± {@code radius}，Y 见下</td>
 *         <td>水平距离 ≤ {@code radius}（剔除水平四角，Y 由包围盒约束）</td></tr>
 * </table>
 *
 * <h2>为什么 SPHERE / CUBE 的包围盒公式与 DS {@code area} 完全相同</h2>
 * DS {@code AreaTarget#calculateAffectedArea} 是 {@code AABB.ofSize(dragon.position(), r*2, r*2, r*2)}，
 * 即「以锚点为中心、每轴半边长 {@code r}」。球形沿用同一个盒子（球内切于该盒），
 * 这样 {@code radius} 在两种形状下的含义完全一致（都是"半边长"），玩家切换形状时不会出现范围错觉。
 *
 * <h2>CYLINDER 的 Y 区间——与 DS {@code disc} / 本模组 {@code annulus} 逐行一致</h2>
 * <pre>
 * height_starts_below = false → Y ∈ [y,         y + height]        （共 height + 1 格）
 * height_starts_below = true  → Y ∈ [y - 1,     y + height - 1]    （向下多覆盖一格）
 * </pre>
 * 因此 {@code height} 的语义是「从锚点往上额外延伸多少格」，不是"半高"。
 * 三者都锚在<b>脚部位置</b>（{@code dragon.position()}），与 DS 全部内置目标类型口径一致。
 */
public enum DomainShape implements StringRepresentable {
    SPHERE("sphere"),
    CUBE("cube"),
    CYLINDER("cylinder");

    public static final Codec<DomainShape> CODEC = StringRepresentable.fromEnum(DomainShape::values);

    /** 语言键前缀：{@code additional_abilities.gui.ability_target.domain.shape.<序列化名>} */
    private static final String LANG_KEY_PREFIX = "additional_abilities.gui.ability_target.domain.shape.";

    private final String name;

    DomainShape(final String name) {
        this.name = name;
    }

    @Override
    public @NotNull String getSerializedName() {
        return name;
    }

    /** 侧边栏显示用的语言键（形状名，如「球形」/「Sphere」）。 */
    @Contract(pure = true)
    public @NotNull String translationKey() {
        return LANG_KEY_PREFIX + name;
    }

    /**
     * 生成包围盒（粗筛区）。
     *
     * @param shape            形状
     * @param origin           锚点（脚部位置）
     * @param radius           半径；SPHERE / CUBE 为每轴半边长，CYLINDER 为水平半径
     * @param height           仅 CYLINDER：从锚点向上额外延伸的格数
     * @param heightStartsBelow 仅 CYLINDER：是否从锚点下方一格开始计算厚度
     */
    public static @NotNull AABB createArea(final @NotNull DomainShape shape, final Vec3 origin, final double radius,
                                           final double height, final boolean heightStartsBelow) {
        return switch (shape) {
            case SPHERE, CUBE -> AABB.ofSize(origin, radius * 2, radius * 2, radius * 2);
            case CYLINDER -> new AABB(
                    origin.subtract(radius, heightStartsBelow ? 1 : 0, radius),
                    origin.add(radius, heightStartsBelow ? height - 1 : height, radius));
        };
    }

    /**
     * 形状精筛：点 {@code (x, y, z)} 是否真的落在形状内。
     * <p>
     * 取点口径与 DS / 本模组既有目标类型保持一致：<b>方块取格中心</b>（{@code x+0.5, y+0.5, z+0.5}），
     * <b>实体取 {@code entity.position()}（脚部中心）</b>。这样边界格的判定可预测、易文档化，
     * 不会出现"看起来多吃一格"的观感。
     * <p>
     * {@link #CUBE} 恒返回 {@code true}（包围盒本身即判定区），仅用于统一调用点。
     */
    public static boolean contains(final @NotNull DomainShape shape, final @NotNull Vec3 origin, final double radius,
                                   final double x, final double y, final double z) {
        double dx = x - origin.x();
        double dz = z - origin.z();

        return switch (shape) {
            case CUBE -> true;
            case SPHERE -> {
                double dy = y - origin.y();
                yield dx * dx + dy * dy + dz * dz <= radius * radius;
            }
            case CYLINDER -> dx * dx + dz * dz <= radius * radius;
        };
    }
}
