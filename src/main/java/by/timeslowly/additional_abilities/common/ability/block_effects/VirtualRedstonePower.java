package by.timeslowly.additional_abilities.common.ability.block_effects;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 虚拟红石供能的统一入口：<b>登记</b>、<b>查询</b>、<b>到期清理</b>与<b>邻居通知</b>。
 *
 * <h2>它解决什么</h2>
 * {@code apply} 收到的是“目标方块 P”和“输入方向 D”。原版红石消费者（红石线、继电器、灯、活塞……）
 * 都通过 {@code Level.getSignal(pos, dir)} / {@code getDirectSignal(pos, dir)} /
 * {@code getControlInputSignal(pos, dir, ...)} 判断输入，而世界里并不存在真正的红石源。
 * 本类向 {@link RedstonePowerData}（挂在 {@code ServerLevel} 附件上）登记两类状态：
 * <ul>
 *     <li><b>源供能</b>：{@code (S, D) -> power}，对应“S 处有一个朝 D 发出信号的源”（{@code S = P.relative(D)}）；</li>
 *     <li><b>方块充能</b>：{@code P -> power}，对应“P 自身是被充能的方块，向六个面放能”
 *         （仅当 P 是原版导电方块；无论有无方向都建立）。</li>
 * </ul>
 * 再由 {@code SignalGetterMixin} 让查询看到 <b>{@code max(真实信号, 虚拟信号)}</b>，
 * 从而不覆盖真实红石、也不写入任何假方块。
 *
 * <h2>为什么只做“登记 + 通知”，不自己模拟红石网络</h2>
 * 信号读法交给 Mixin、传播交给原版 {@code CollectingNeighborUpdater}：
 * 红石线优先级、继电器延迟、比较器方块实体、实验性 redstone evaluator 全部原样运行。
 * 本类只在<b>聚合值发生变化的边沿</b>调用公开的原版邻居更新入口，不递归、不手改红石线状态。
 *
 * <h2>通知的形态</h2>
 * <ol>
 *     <li><b>无方向直接激活</b>（{@code target_direction} 省略）：对 P 调用一次
 *         {@link net.minecraft.server.level.ServerLevel#neighborChanged(BlockPos, Block, BlockPos)}，
 *         不建立 {@code (S, D)}、不向周围扩散源供能；P 若是导电方块，另见方块充能；</li>
 *     <li><b>有方向建立 / 撤销</b>：若 S 与它的六个邻居都在已加载区块内，对 {@code S} 调用
 *         {@code updateNeighborsAt(S, REDSTONE_BLOCK)}，让它们按原版队列重读信号；
 *         否则（区块边界 / 源区块已卸载）只对目标 P 补一次 {@code neighborChanged}，
 *         既不为通知强行加载邻块，也不让 P 卡在 {@code POWERED=true}；</li>
 *     <li><b>方块充能</b>：P 的充能强度发生边沿变化时，对 <b>P 的六个邻居</b>补发更新
 *         （区块边界时只通知已加载的邻居），让贴着 P 的中继器 / 火把 / 灯重读
 *         {@code getSignal(P, side)} —— 只通知 S 的邻居是不够的，这是“目标方块被充能”的关键；</li>
 *     <li><b>Observer</b>：Observer 的触发路径是形状更新而非普通 {@code neighborChanged}，
 *         因此在 P 确实是“面朝 S 的观察者”时额外补一次 {@code neighborShapeChanged}。
 *         方块充能本身不改变 P 的真实方块状态，因此不触发 Observer。</li>
 * </ol>
 *
 * <h2>{@code Blocks.REDSTONE_BLOCK} 只是身份标识</h2>
 * 它只作为邻居更新回调里的 {@code changedBlock} 传入，<b>永远不会</b>被放置、替换或短暂写入世界；
 * 因此不会留下方块快照、掉落物、方块实体或区块保存的污染。
 */
public final class VirtualRedstonePower {
    /** {@code Direction.values()} 每次调用都会克隆数组；边沿通知偶尔用到，这里缓存一份 */
    private static final Direction[] DIRECTIONS = Direction.values();

    private VirtualRedstonePower() {
        // 工具类
    }

    /**
     * Mixin 热路径：读取某个位置在给定方向上的虚拟信号。
     * <p>
     * 结果是两者取较大值：
     * <ul>
     *     <li>{@code (pos, direction)} 上的<b>源供能</b>；</li>
     *     <li>{@code pos} 自身的<b>方块充能</b>（不分方向，向六个面放能）。</li>
     * </ul>
     * 没有附件（从未使用过红石供能的维度）时直接返回 0，<b>不会</b>顺便把默认附件建出来；
     * 有附件时也只是两次原始 long 键查找，不创建对象。这里不判断区块是否加载：查询发生处
     * 本来就在已加载方块上，而数据只在登记时写入已加载位置。
     */
    public static int powerAt(final @NotNull ServerLevel level, final @NotNull BlockPos sourcePos,
                              final @NotNull Direction direction) {
        RedstonePowerData data = level.getExistingDataOrNull(AAAttachments.REDSTONE_POWER);

        if (data == null || data.isEmpty()) {
            return 0;
        }

        long now = level.getGameTime();
        int source = data.powerAt(now, sourcePos, direction);
        int block = data.blockPowerAt(now, sourcePos);
        return Math.max(source, block);
    }

    /**
     * 登记一笔有方向的虚拟供能（由 {@code RedstonePoweringEffect#apply} 调用）。
     * <p>
     * 顺序严格是<b>先写状态、再通知</b>：邻居更新回调里消费者会立刻重读信号，
     * 若先通知后写状态，它们会读到 0 而做一次多余的关断。
     *
     * @param power         已钳制到 {@code 1..15} 的信号强度；{@code <= 0} 时不做任何事
     * @param durationTicks 生效刻数（含登记刻），调用方已钳制到允许区间
     */
    public static void register(final @NotNull ServerLevel level, final @NotNull UUID caster,
                                final @NotNull ResourceKey<?> ability, final @NotNull BlockPos targetPos,
                                final @NotNull Direction inputDirection, final int power,
                                final int durationTicks) {
        if (power <= 0 || durationTicks <= 0) {
            return;
        }

        // 只为确实需要供能的两端建立状态：目标 P 与虚拟源 S 都必须已加载。
        // 不主动加载远处区块 —— 区块卸载时宁可让这一次效果静默失效（会在到期清理时再检查一次）
        if (!level.isLoaded(targetPos)) {
            return;
        }

        BlockPos sourcePos = targetPos.relative(inputDirection);

        if (!level.isLoaded(sourcePos)) {
            return;
        }

        RedstonePowerData data = level.getData(AAAttachments.REDSTONE_POWER);

        long now = level.getGameTime();

        if (data.register(caster, ability, sourcePos, inputDirection, power, now, durationTicks)) {
            AdditionalAbilities.LOGGER.debug("[redstone_powering] register S={} D={} power={} duration={} t={}",
                    sourcePos, inputDirection, power, durationTicks, now);
            notifySource(level, sourcePos, inputDirection);
        }
    }

    /**
     * 登记一笔“目标方块自身被充能”（由 {@code RedstonePoweringEffect#apply} 对导电方块调用）。
     * <p>
     * 与源供能分开：方块充能不分方向，{@code getSignal(P, 任意面)} 都会读到它；
     * 强度/到期边沿变化时通知 P 的六个邻居，让贴着 P 的中继器 / 火把 / 灯重新求值。
     *
     * @param power         已钳制到 {@code 1..15} 的信号强度；{@code <= 0} 时不做任何事
     * @param durationTicks 生效刻数（含登记刻），调用方已钳制到允许区间
     */
    public static void chargeBlock(final @NotNull ServerLevel level, final @NotNull UUID caster,
                                   final @NotNull ResourceKey<?> ability, final @NotNull BlockPos position,
                                   final int power, final int durationTicks) {
        if (power <= 0 || durationTicks <= 0) {
            return;
        }

        if (!level.isLoaded(position)) {
            return;
        }

        RedstonePowerData data = level.getData(AAAttachments.REDSTONE_POWER);
        long now = level.getGameTime();

        if (data.chargeBlock(caster, ability, position, power, now, durationTicks)) {
            AdditionalAbilities.LOGGER.debug("[redstone_powering] charge block P={} power={} duration={} t={}",
                    position, power, durationTicks, now);
            notifyBlock(level, position);
        }
    }

    /**
     * 每维度刻末尾：丢弃到期贡献，并只对<b>聚合值发生变化</b>的 {@code (S, D)} / 充能方块补发邻居更新。
     * <p>
     * 用 {@code getExistingDataOrNull}：从没被红石效果碰过的维度不该因为一个每刻运行的监听器
     * 长期挂着一个空表。
     */
    public static void expire(final @NotNull ServerLevel level) {
        RedstonePowerData data = level.getExistingDataOrNull(AAAttachments.REDSTONE_POWER);

        if (data == null || data.isEmpty()) {
            return;
        }

        long now = level.getGameTime();

        for (RedstonePowerData.PowerChange change : data.expire(now)) {
            AdditionalAbilities.LOGGER.debug("[redstone_powering] expire S={} D={} {} -> {} t={}",
                    change.sourcePos(), change.direction(), change.before(), change.after(), now);
            notifySource(level, change.sourcePos(), change.direction());
        }

        for (RedstonePowerData.BlockPowerChange change : data.expireBlocks(now)) {
            AdditionalAbilities.LOGGER.debug("[redstone_powering] expire block P={} {} -> {} t={}",
                    change.position(), change.before(), change.after(), now);
            notifyBlock(level, change.position());
        }
    }

    /**
     * 对一个虚拟源 S 补发原版邻居更新，并在必要时补 Observer 的形状更新。
     *
     * <h2>为什么不能无条件调用 {@code updateNeighborsAt}</h2>
     * {@code ServerLevel#updateNeighborsAt} 会让 {@code CollectingNeighborUpdater} 逐一
     * {@code getBlockState} 它的六个邻居；{@code Level#getBlockState} 在区块未加载时会走
     * {@code getChunk(..., FULL)}，也就是<b>顺手加载邻块</b>。虚拟源恰好位于区块边界时，
     * 这会违反规划里“不为效果主动加载区块”的约束。
     * <p>
     * 因此通知分两档：
     * <ol>
     *     <li>S 与六个邻居全部加载 → 走原版 {@code updateNeighborsAt}，保持原版更新顺序 / 队列 / 上限；</li>
     *     <li>否则 → 只对目标 P 调用一次 {@code neighborChanged(P, REDSTONE_BLOCK, S)}。
     *         这只读取 P 自己的方块状态（P 已加载），不会碰未加载的邻居；
     *         同时保证“源区块在到期前被卸载”时 P 仍能收到关断边沿，而不是永久卡在
     *         {@code POWERED=true}。</li>
     * </ol>
     * 两种情况都不会主动加载远处区块；代价是区块边界上的广义邻居更新会被降级为“仅目标”，
     * 未加载邻块里的元件本来也不在 tick，等它们加载后由原版自己的加载更新处理。
     */
    private static void notifySource(final @NotNull ServerLevel level, final @NotNull BlockPos sourcePos,
                                     final @NotNull Direction direction) {
        BlockPos targetPos = sourcePos.relative(direction.getOpposite());
        boolean sourceLoaded = level.isLoaded(sourcePos);
        boolean targetLoaded = level.isLoaded(targetPos);

        if (!sourceLoaded && !targetLoaded) {
            // 两端都不在加载区块内：没有可通知的方块；等区块加载后由原版加载逻辑处理其状态
            return;
        }

        if (sourceLoaded && areNeighborsLoaded(level, sourcePos)) {
            level.updateNeighborsAt(sourcePos, Blocks.REDSTONE_BLOCK);
        } else if (targetLoaded) {
            // 区块边界 / 源区块已卸载：只戳目标，不读未加载邻居
            level.neighborChanged(targetPos.immutable(), Blocks.REDSTONE_BLOCK, sourcePos.immutable());
        }

        if (sourceLoaded && targetLoaded) {
            notifyObserver(level, sourcePos, direction);
        }
    }

    /**
     * 对一个被充能的方块 P 通知它的六个邻居。
     * <p>
     * 与 {@code notifySource} 同理，{@code updateNeighborsAt(P)} 会读取 P 的六个邻居并可能加载区块；
     * 因此 P 与六个邻居全部加载时走原版更新，否则只对已加载的邻居逐个 {@code neighborChanged}。
     * 这次通知是“目标方块被充能”能否被旁边中继器 / 火把感知到的关键：源供能的
     * {@code updateNeighborsAt(S)} 只覆盖 S 的邻居，贴着 P 的元件并不在其中。
     * <p>
     * 方块充能不改变 P 的真实方块状态，因此这里不补 Observer 形状更新。
     */
    private static void notifyBlock(final @NotNull ServerLevel level, final @NotNull BlockPos position) {
        if (!level.isLoaded(position)) {
            return;
        }

        if (areNeighborsLoaded(level, position)) {
            level.updateNeighborsAt(position, Blocks.REDSTONE_BLOCK);
            return;
        }

        for (Direction direction : DIRECTIONS) {
            BlockPos neighbor = position.relative(direction);

            if (level.isLoaded(neighbor)) {
                level.neighborChanged(neighbor.immutable(), Blocks.REDSTONE_BLOCK, position.immutable());
            }
        }
    }

    /** S 的六个邻居是否都在已加载区块内（{@code isLoaded} 不会触发加载） */
    private static boolean areNeighborsLoaded(final @NotNull ServerLevel level, final @NotNull BlockPos position) {
        for (Direction direction : DIRECTIONS) {
            if (!level.isLoaded(position.relative(direction))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Observer 的脉冲由 {@code updateShape} 驱动，不走普通 {@code neighborChanged}。
     * <p>
     * 只在“P 确实是观察者、且它正好观察着 S”时补发：观察者的 {@code FACING} 是它<b>观察的方向</b>，
     * 即从 P 指向 S 的方向 {@code D}。其它方块 / 其它朝向完全不碰，避免对每个普通方块无条件多排一次形状更新。
     * <p>
     * 参数顺序按 1.21.1 的
     * {@code LevelAccessor#neighborShapeChanged(Direction, BlockState neighborState, BlockPos pos, BlockPos neighborPos, int flags, int recursionLeft)}：
     * 这里是 {@code (D, S 的真实方块状态, P, S, UPDATE_ALL, UPDATE_LIMIT)}。
     * <b>第二个参数必须是世界中 S 的真实状态</b> —— 绝不能用假红石块参与形状计算。
     * <p>
     * Observer 自己在 2 刻后才真正翻转；本方法只负责“它观察的邻居形状变了”这一事实，
     * 与红石实验特性、优先级等无关。
     */
    private static void notifyObserver(final @NotNull ServerLevel level, final @NotNull BlockPos sourcePos,
                                       final @NotNull Direction direction) {
        BlockPos targetPos = sourcePos.relative(direction.getOpposite());

        if (!level.isLoaded(targetPos)) {
            return;
        }

        BlockState targetState = level.getBlockState(targetPos);

        if (!(targetState.getBlock() instanceof ObserverBlock)
                || targetState.getValue(DirectionalBlock.FACING) != direction) {
            return;
        }

        level.neighborShapeChanged(direction, level.getBlockState(sourcePos), targetPos, sourcePos,
                Block.UPDATE_ALL, Block.UPDATE_LIMIT);
    }
}
