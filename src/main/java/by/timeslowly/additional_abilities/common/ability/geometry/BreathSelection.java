package by.timeslowly.additional_abilities.common.ability.geometry;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.AbilityTargeting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;

/**
 * 收束光束的目标筛选 —— 「粗筛 + 窄相」两段式里的第二段。
 *
 * <h2>为什么要有这一层</h2>
 * DS 的 {@code DragonBreathTarget#apply} 是「在包围盒里枚举一切」：
 * 方块走 {@code BlockPos.betweenClosedStream(盒)}，实体走 {@code getEntities(盒, 谓词)}，
 * 而两个消费端都没有几何阈值 —— 于是包围盒就是判定区。
 * 收束后包围盒不再等于光束（见 {@link BreathBeam} 的说明），所以必须在枚举之后补一层窄相，
 * 把「盒里的东西」收敛成「光束里的东西」。
 *
 * <h2>方块分支为什么仍然是「整盒枚举 + 窄相过滤」而不是沿轴步进</h2>
 * 曾考虑用 DDA 沿光束轴线步进（复杂度 O(射程)），最终没有采用，理由是：
 * <ol>
 *     <li><b>完备性</b>：整盒枚举 + 逐格窄相测试<b>在数学上不会漏格</b>。
 *         步进法必须选步长，薄光束下（截面半宽可低至 0.2 格）一旦步长/半径取小偏差就会漏格，
 *         而「龙息烧出一条线」漏格是肉眼可见的缺陷；</li>
 *     <li><b>真实开销已经很小</b>：DS 原本对盒内**每个**位置都要跑一次
 *         {@code blockTarget.matches()}（战利品条件求值），这才是热点。
 *         我们把窄相（约 30 次浮点运算）放在它前面，99% 的位置在极廉价的测试上就被刷掉，
 *         实际进入 {@code matches()} 的只剩光束内那几十格 —— 比原版更快；</li>
 *     <li><b>枚举本身有硬上限</b>：见 {@link #MAX_SCANNED_BLOCKS}。</li>
 * </ol>
 * 斜视时盒体积确实比原版大（θ=45° 约 900 格量级），但那只是「位置枚举 + 廉价浮点测试」，
 * 与它替掉的条件求值不是一个量级。
 *
 * <h2>枚举上限</h2>
 * 两个上限都是**安全阀**而非正常路径：常规光束（射程 20~40 格、截面 &lt; 1 格）内只有几十格，
 * 只有数据包把 {@code range_multiplier} 调到极端值才会真的触发截断。
 */
public final class BreathSelection {
    /** 单次触发允许枚举的方块位置上限（防止极端射程下的枚举爆炸） */
    public static final int MAX_SCANNED_BLOCKS = 4096;

    /** 单次触发允许实际生效的方块数量上限（限制效果本身的执行量） */
    public static final int MAX_AFFECTED_BLOCKS = 256;

    private BreathSelection() { /* 工具类 */ }

    /**
     * 光束版的方块分支。与 DS 原实现相比只改了两处：
     * 枚举前先做窄相、坐标改用不可变副本（{@code betweenClosed} 复用同一个 MutableBlockPos）。
     * {@code direction} 与 DS 完全一致（{@code Direction.getNearest(眼睛世界坐标)}），未做任何修正。
     */
    public static void applyToBlocks(final @NotNull ServerPlayer dragon, final DragonAbilityInstance ability,
                                     final @NotNull BreathBeam beam, final AbilityTargeting.BlockTargeting blockTarget) {
        AABB area = beam.boundingBox();
        Direction direction = Direction.getNearest(dragon.getEyePosition());

        BlockPos min = new BlockPos(Mth.floor(area.minX), Mth.floor(area.minY), Mth.floor(area.minZ));
        BlockPos max = new BlockPos(Mth.floor(area.maxX), Mth.floor(area.maxY), Mth.floor(area.maxZ));

        int scanned = 0;
        int affected = 0;

        for (BlockPos cursor : BlockPos.betweenClosed(min, max)) {
            if (scanned++ >= MAX_SCANNED_BLOCKS) {
                return;
            }

            if (!beam.intersectsBlock(cursor.getX(), cursor.getY(), cursor.getZ())) {
                continue;
            }

            if (affected >= MAX_AFFECTED_BLOCKS) {
                return;
            }

            BlockPos position = cursor.immutable();

            if (blockTarget.matches(dragon, position)) {
                affected++;
                blockTarget.effects().forEach(effect -> effect.apply(dragon, ability, position, direction));
            }
        }
    }

    /**
     * 光束版的实体分支。谓词顺序与 DS 一致（阵营 → 战利品条件 → 我们的几何判定），
     * 只在末尾追加一条 {@link BreathBeam#intersects(AABB)}，
     * 用实体的**完整碰撞箱**参与 6 轴分离轴测试（大体积实体贴在光束边缘时不会被漏判）。
     */
    public static void applyToEntities(final @NotNull ServerPlayer dragon, final DragonAbilityInstance ability,
                                       final @NotNull BreathBeam beam, final AbilityTargeting.EntityTargeting entityTarget) {
        dragon.serverLevel().getEntities(EntityTypeTest.forClass(Entity.class), beam.boundingBox(),
                entity -> entityTarget.targetingMode().isEntityRelevant(dragon, entity, entityTarget.isHarmful())
                        && entityTarget.matches(dragon, entity, entity.position())
                        && beam.intersects(entity.getBoundingBox())
        ).forEach(entity -> entityTarget.effects().forEach(effect -> effect.apply(dragon, ability, entity)));
    }
}
