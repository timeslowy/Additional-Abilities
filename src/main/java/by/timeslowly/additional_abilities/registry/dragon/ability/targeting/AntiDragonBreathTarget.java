package by.timeslowly.additional_abilities.registry.dragon.ability.targeting;

import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.AbilityTargeting;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * 龙之技能目标选择器：反向龙息锥形（{@code additional_abilities:anti_dragon_breath}）。
 * <p>
 * 字段结构、执行流程、阵营过滤链、侧边栏数值与 DS 内置的
 * {@code dragonsurvival:dragon_breath}（{@code DragonBreathTarget}）完全一致，
 * 唯一区别是<b>选择箱体的延伸方向与视线相反</b>——即朝施法者身后。
 *
 * <h2>为什么不能"先算原箱体再翻转 AABB"</h2>
 * DS 原公式在 Y 轴上<b>不以起点对称</b>：
 * <pre>
 * min.y = |L.y &lt; 0 ? |V.y| : 0|
 * max.y =    L.y &gt; 0 ? |V.y| + eyeHeight : eyeHeight      // ★ 恒以 eyeHeight（身体厚度）为基底
 * </pre>
 * 也就是说"身体厚度"永远留在 +Y 侧，只有视线的上下分量会往视线那一侧伸出。
 * 若先算原 AABB 再围绕起点做分量交换/取反，会把整个 Y 区间多平移一个 {@code eyeHeight}，
 * 得到的是"整体下移"而非"镜像"。
 * <p>
 * 因此本类的做法是：令 {@code L' = -L}（视线取负），把 {@code L'} 代入与 DS 逐行相同的公式。
 * 这样身体厚度仍留在 +Y 侧，而视线的前后 / 左右 / 上下分量全部反向伸出，是真正的镜像。
 *
 * <p>实测例（眼高 1.62、体型 1、射程 4、水平朝 +X）：
 * <table border="1">
 *     <tr><th>类型</th><th>X 区间</th><th>Y 区间</th><th>Z 区间</th></tr>
 *     <tr><td>{@code dragonsurvival:dragon_breath}</td><td>-1 ~ 4</td><td>0.81 ~ 2.43</td><td>-1 ~ 1</td></tr>
 *     <tr><td>{@code additional_abilities:anti_dragon_breath}</td><td>-4 ~ 1</td><td>0.81 ~ 2.43</td><td>-1 ~ 1</td></tr>
 * </table>
 *
 * <h2>JSON 用法</h2>
 * <pre>
 * "target_selection": {
 *   "target_type": "additional_abilities:anti_dragon_breath",
 *   "range_multiplier": 1.0,                                  // 与 dragonsurvival:dragon_breath_range 属性相乘
 *   "applied_effects": { "entity_effect": [ ... ], "targeting_mode": "non_allies" }
 * }
 * </pre>
 * 字段与 {@code dragonsurvival:dragon_breath} 完全相同（{@code applied_effects} + {@code range_multiplier}）。
 *
 * <h2>注意</h2>
 * <ul>
 *     <li>方块分支传入的 {@code direction} 与 DS 龙息完全一致
 *         （{@code Direction.getNearest(dragon.getEyePosition())}，按眼睛世界坐标取最近轴向，
 *         与视线朝向无关），未做反向处理。</li>
 *     <li>本类只负责"选谁"。谁能被打由 {@code applied_effects.targeting_mode} 决定，
 *         与目标类型无关（与 DS 其它目标类型同理）。</li>
 * </ul>
 */
// TODO:反向吐息粒子（不一定）
public record AntiDragonBreathTarget(Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> target,
                                     LevelBasedValue rangeMultiplier) implements AbilityTargeting {
    /** 方块分支目标描述：Targets a %s block cone behind you */
    private static final String REVERSE_CONE_TARGET_BLOCK = "additional_abilities.gui.ability_target.reverse_cone_target.block";

    /** 实体分支目标描述：Targets %s in a %s block cone behind you */
    private static final String REVERSE_CONE_TARGET_ENTITY = "additional_abilities.gui.ability_target.reverse_cone_target.entity";

    public static final MapCodec<AntiDragonBreathTarget> CODEC = RecordCodecBuilder.mapCodec(instance ->
            // 必须经 codecStart 拼入公共字段 applied_effects，否则本类型无法承载任何效果
            AbilityTargeting.codecStart(instance)
                    .and(LevelBasedValue.CODEC.fieldOf("range_multiplier").forGetter(AntiDragonBreathTarget::rangeMultiplier))
                    .apply(instance, AntiDragonBreathTarget::new)
    );

    @Override
    public void apply(final @NotNull ServerPlayer dragon, final @NotNull DragonAbilityInstance ability) {
        target.ifLeft(blockTarget -> {
            // 与 DS DragonBreathTarget 一致：忽略视线朝向，仅按眼睛世界坐标取最近轴向
            Direction direction = Direction.getNearest(dragon.getEyePosition());

            BlockPos.betweenClosedStream(calculateReverseBreathArea(dragon, ability)).forEach(position -> {
                if (blockTarget.matches(dragon, position)) {
                    blockTarget.effects().forEach(effect -> effect.apply(dragon, ability, position, direction));
                }
            });
        }).ifRight(entityTarget -> dragon.serverLevel().getEntities(EntityTypeTest.forClass(Entity.class), calculateReverseBreathArea(dragon, ability),
                entity -> entityTarget.targetingMode().isEntityRelevant(dragon, entity, entityTarget.isHarmful())
                        && entityTarget.matches(dragon, entity, entity.position())
        ).forEach(entity -> entityTarget.effects().forEach(effect -> effect.apply(dragon, ability, entity))));
    }

    /**
     * 反向龙息选择箱体：算式与 DS {@code DragonBreathTarget#calculateBreathArea} 逐行一致，
     * 仅把视线向量 {@code L} 替换为 {@code L' = -L}。
     */
    public AABB calculateReverseBreathArea(final Player dragon, final DragonAbilityInstance ability) {
        // ★ 唯一差异：视线取负 → 箱体朝施法者身后延伸
        Vec3 reversedLook = dragon.getLookAngle().scale(-1.0);
        Vec3 viewVector = reversedLook.scale(rangeMultiplier.calculate(ability.level()) * dragon.getAttributeValue(DSAttributes.DRAGON_BREATH_RANGE));
        double defaultRadius = dragon.getScale();

        // Set the radius (value will be at least the default radius)
        double xOffset = getOffset(viewVector.x(), defaultRadius);
        double yOffset = Math.abs(viewVector.y());
        double zOffset = getOffset(viewVector.z(), defaultRadius);

        // Check for look angle to avoid extending the range in the direction the player is not facing / looking
        double xMin = (reversedLook.x() < 0 ? xOffset : defaultRadius);
        double yMin = (reversedLook.y() < 0 ? yOffset : 0);
        double zMin = (reversedLook.z() < 0 ? zOffset : defaultRadius);
        Vec3 min = new Vec3(Math.abs(xMin), Math.abs(yMin), Math.abs(zMin));

        double xMax = (reversedLook.x() > 0 ? xOffset : defaultRadius);
        double yMax = (reversedLook.y() > 0 ? yOffset + dragon.getEyeHeight() : dragon.getEyeHeight());
        double zMax = (reversedLook.z() > 0 ? zOffset : defaultRadius);
        Vec3 max = new Vec3(Math.abs(xMax), Math.abs(yMax), Math.abs(zMax));

        Vec3 startPosition = dragon.getEyePosition().subtract(0, (dragon.getEyeHeight() / 2), 0);
        return new AABB(startPosition.subtract(min), startPosition.add(max));
    }

    @Override
    public MutableComponent getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        Component targetingComponent = target.map(block -> null, entity -> entity.targetingMode().translation());
        MutableComponent range = DSColors.dynamicValue(FORMAT.format(getDistance(dragon, ability)));

        if (targetingComponent == null) {
            return Component.translatable(REVERSE_CONE_TARGET_BLOCK, range);
        } else {
            return Component.translatable(REVERSE_CONE_TARGET_ENTITY, DSColors.dynamicValue(targetingComponent), range);
        }
    }

    @Override
    public float getDistance(final Player dragon, final @NotNull DragonAbilityInstance instance) {
        return (float) (rangeMultiplier.calculate(instance.level()) * dragon.getAttributeValue(DSAttributes.DRAGON_BREATH_RANGE));
    }

    private static double getOffset(final double value, final double defaultValue) {
        if (value < 0) {
            return Math.min(value, -defaultValue);
        }

        return Math.max(value, defaultValue);
    }

    @Override
    public MapCodec<? extends AbilityTargeting> codec() {
        return CODEC;
    }
}
