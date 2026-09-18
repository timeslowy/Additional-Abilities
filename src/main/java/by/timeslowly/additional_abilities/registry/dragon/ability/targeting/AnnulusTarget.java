package by.timeslowly.additional_abilities.registry.dragon.ability.targeting;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.AbilityTargeting;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

/**
 * 龙之技能目标选择器：环形（{@code additional_abilities:annulus}）。
 * <p>
 * 结构、执行流程与侧边栏描述均以 DS 内置的 {@code dragonsurvival:disc}
 * （{@code DiscTarget}）为蓝本，唯一区别是<b>把内圈挖空</b>：
 * {@code disc} 选中一个以施法者为中心的<b>扁立方体</b>（实为方形盘），
 * 本类型只选中「内半径 ~ 外半径」之间的<b>环带</b>，即水平方向上的圆柱壳。
 *
 * <h2>作用范围</h2>
 * <pre>
 * origin = dragon.position()                                   // 脚部，与 disc 一致
 * r_in   = inner_radius.calculate(level)
 * r_out  = r_in + width.calculate(level)                        // 环宽沿径向叠加
 *
 * 包围盒（XZ）= origin ± r_out
 * Y 区间       = height_starts_below ? [y - 1, y + height - 1] : [y, y + height]
 *                // 与 DS DiscTarget#calculateAffectedArea 逐行一致
 *
 * 命中条件：水平距离 d = hypot(px - x, pz - z)，满足 r_in ≤ d ≤ r_out
 * </pre>
 *
 * <h2>边界判定：中心点法</h2>
 * 包围盒只是粗筛，真正的环形判定在包围盒内部再做一次水平距离过滤，取点方式为：
 * <ul>
 *     <li><b>方块</b>：格中心 {@code (x + 0.5, z + 0.5)}；</li>
 *     <li><b>实体</b>：{@code entity.position()}（脚部中心，与原点取点口径一致）。</li>
 * </ul>
 * 相比「体积重叠法」，中心点法的判定可预测、易文档化，边界格不会出现"看起来多吃一格"的观感。
 *
 * <h2>为什么不用 {@code BlockPos#betweenClosedStream} 之外的做法</h2>
 * 与 DS 的 {@code area} / {@code disc} 同构：对包围盒内<b>每个方块位置</b>跑一次条件与效果。
 * 遍历量仍为 {@code (2 · r_out + 1)² × 高度格}，环带本身是"空"的并不能减少遍历次数，
 * 因此 {@code inner_radius} 给大值时请同时抬高 {@code trigger_rate}（建议 ≥ 5）。
 *
 * <h2>JSON 用法</h2>
 * <pre>
 * "target_selection": {
 *   "target_type": "additional_abilities:annulus",
 *   "inner_radius": 2.0,                                                   // 必填，内圈半径（格）
 *   "width": 3.0,                                                          // 必填，环宽（格）
 *   "height": 1.0,                                                         // 可选，默认 1，厚度（格）
 *   "height_starts_below": false,                                          // 可选，默认 false
 *   "applied_effects": { "entity_effect": [ ... ], "targeting_mode": "enemies" }
 * }
 * </pre>
 * {@code inner_radius} / {@code width} / {@code height} 均支持原版 {@link LevelBasedValue} 全部写法
 * （{@code linear} / {@code lookup} / {@code constant} …），例如
 * {@code "width": { "type": "minecraft:linear", "base": 2.0, "per_level_above_first": 0.5 }}。
 *
 * <h2>注意</h2>
 * <ul>
 *     <li>方块分支传入的 {@code direction} 为 {@code null}——与 DS {@code area} / {@code disc} 一致
 *         （只有 {@code looking_at} 会传真实的命中面朝向）。依赖朝向的方块效果需自行兜底。</li>
 *     <li>本类只负责"选谁"。谁能被打由 {@code applied_effects.targeting_mode} 决定，
 *         与目标类型无关（与 DS 其它目标类型同理）。</li>
 *     <li>侧边栏中 <b>F3+B 调试箱</b>由 {@code AbilityHitboxEventHandler} 单独绘制
 *         （DS 的 {@code ClientDragonRenderer#renderAbilityHitbox} 是硬编码分支链，没有扩展点），
 *         画的是内、外两个包围盒线框。</li>
 * </ul>
 */
public record AnnulusTarget(Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> target,
                            LevelBasedValue innerRadius,
                            LevelBasedValue width,
                            LevelBasedValue height,
                            boolean heightStartsBelow) implements AbilityTargeting {
    /** 方块分支目标描述：Targets an annulus around you (inner radius: %s / width: %s / height: %s) */
    private static final String ANNULUS_TARGET_BLOCK = "additional_abilities.gui.ability_target.annulus.block";

    /** 实体分支目标描述：Targets %s in an annulus around you (inner radius: %s / width: %s / height: %s) */
    private static final String ANNULUS_TARGET_ENTITY = "additional_abilities.gui.ability_target.annulus.entity";

    public static final MapCodec<AnnulusTarget> CODEC = RecordCodecBuilder.mapCodec(instance ->
            // 必须经 codecStart 拼入公共字段 applied_effects，否则本类型无法承载任何效果
            AbilityTargeting.codecStart(instance)
                    .and(LevelBasedValue.CODEC.fieldOf("inner_radius").forGetter(AnnulusTarget::innerRadius))
                    .and(LevelBasedValue.CODEC.fieldOf("width").forGetter(AnnulusTarget::width))
                    .and(LevelBasedValue.CODEC.optionalFieldOf("height", LevelBasedValue.constant(1)).forGetter(AnnulusTarget::height))
                    .and(Codec.BOOL.optionalFieldOf("height_starts_below", false).forGetter(AnnulusTarget::heightStartsBelow))
                    .apply(instance, AnnulusTarget::new)
    );

    @Override
    public void apply(final @NotNull ServerPlayer dragon, final @NotNull DragonAbilityInstance ability) {
        // 与 DS DiscTarget 一致：以脚部位置为原点
        Vec3 origin = dragon.position();
        double inner = resolveInnerRadius(ability);
        double outer = resolveOuterRadius(ability);
        AABB area = calculateArea(origin, outer, resolveHeight(ability));

        target.ifLeft(blockTarget -> BlockPos.betweenClosedStream(area).forEach(position -> {
            // 环形粗筛后的精筛：取格中心，水平距离落在 [内半径, 外半径] 内才处理
            if (!isWithinAnnulus(origin, position.getX() + 0.5, position.getZ() + 0.5, inner, outer)) {
                return;
            }

            if (blockTarget.matches(dragon, position)) {
                // direction 传 null，与 DS area / disc 一致
                blockTarget.effects().forEach(effect -> effect.apply(dragon, ability, position, null));
            }
        })).ifRight(entityTarget -> dragon.serverLevel().getEntities(EntityTypeTest.forClass(Entity.class), area,
                entity -> isWithinAnnulus(origin, entity.getX(), entity.getZ(), inner, outer)
                        && entityTarget.targetingMode().isEntityRelevant(dragon, entity, entityTarget.isHarmful())
                        && entityTarget.matches(dragon, entity, entity.position())
        ).forEach(entity -> entityTarget.effects().forEach(effect -> effect.apply(dragon, ability, entity))));
    }

    /**
     * 生成包围盒：以 {@code origin} 为原点、半径 {@code radius}、厚度 {@code height}。
     * <p>
     * 公式与 DS {@code DiscTarget#calculateAffectedArea} 逐行一致——XZ 各向外扩 {@code radius}，
     * Y 区间看 {@link #heightStartsBelow}（{@code false} → {@code [y, y + height]}；
     * {@code true} → {@code [y - 1, y + height - 1]}）。
     * <p>
     * 传入 {@code r_out} 得外包围盒，传入 {@code r_in} 得内包围盒（F3+B 双框用）。
     */
    public @NotNull AABB calculateArea(final @NotNull Vec3 origin, final double radius, final double height) {
        return new AABB(
                origin.subtract(radius, heightStartsBelow ? 1 : 0, radius),
                origin.add(radius, heightStartsBelow ? height - 1 : height, radius)
        );
    }

    /**
     * 水平环带判定：{@code (x, z)} 到原点的水平距离是否落在 {@code [innerRadius, outerRadius]} 闭区间内。
     * <p>
     * 只比较水平（XZ）距离，Y 方向由包围盒本身约束——因此本类型是「环形 × 厚度」的圆柱壳，
     * 而不是三维球壳（这也是 {@code height} 参数能独立存在的语义基础）。
     */
    public static boolean isWithinAnnulus(final @NotNull Vec3 origin, final double x, final double z,
                                          final double innerRadius, final double outerRadius) {
        double dx = x - origin.x();
        double dz = z - origin.z();
        double distance = Math.sqrt(dx * dx + dz * dz);
        return distance >= innerRadius && distance <= outerRadius;
    }

    /** 内半径（已按技能等级求值） */
    public double resolveInnerRadius(final @NotNull DragonAbilityInstance ability) {
        return innerRadius.calculate(ability.level());
    }

    /** 外半径 = 内半径 + 环宽（各自按技能等级求值后相加） */
    public double resolveOuterRadius(final @NotNull DragonAbilityInstance ability) {
        return resolveInnerRadius(ability) + width.calculate(ability.level());
    }

    /** 厚度（已按技能等级求值） */
    public double resolveHeight(final @NotNull DragonAbilityInstance ability) {
        return height.calculate(ability.level());
    }

    @Override
    public @NotNull MutableComponent getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        // 与 DS DiscTarget 相同的双分支文本选择：方块分支没有阵营前缀参数
        Component targetingComponent = target.map(block -> null, entity -> entity.targetingMode().translation());
        // 与 disc 不同：内半径 / 环宽常为小数，这里保留 2 位小数而非 (int) 截断
        Component innerComponent = DSColors.dynamicValue(FORMAT.format(resolveInnerRadius(ability)));
        Component widthComponent = DSColors.dynamicValue(FORMAT.format(width.calculate(ability.level())));
        Component heightComponent = DSColors.dynamicValue(FORMAT.format(resolveHeight(ability)));

        if (targetingComponent == null) {
            return Component.translatable(ANNULUS_TARGET_BLOCK, innerComponent, widthComponent, heightComponent);
        } else {
            return Component.translatable(ANNULUS_TARGET_ENTITY, DSColors.dynamicValue(targetingComponent),
                    innerComponent, widthComponent, heightComponent);
        }
    }

    @Override
    public float getDistance(final Player dragon, final @NotNull DragonAbilityInstance instance) {
        // 侧边栏显示的范围取外半径（环带最远处）
        return (float) resolveOuterRadius(instance);
    }

    @Override
    public MapCodec<? extends AbilityTargeting> codec() {
        return CODEC;
    }
}
