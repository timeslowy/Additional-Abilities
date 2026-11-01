package by.timeslowly.additional_abilities.mixins;

import by.timeslowly.additional_abilities.common.ability.block_effects.VirtualRedstonePower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.SignalGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让原版红石查询看到本模组的<b>虚拟供能</b>（{@code additional_abilities:redstone_powering}）。
 *
 * <h2>为什么注入 {@code SignalGetter} 的 default 方法，而不是每个红石方块</h2>
 * 1.21.1 里所有红石消费者最终都经过这三个默认方法：
 * <ul>
 *     <li>{@code getSignal(BlockPos, Direction)}：红石线、活塞、门、比较器等读“普通信号”；</li>
 *     <li>{@code getDirectSignal(BlockPos, Direction)}：比较器 / 中继器 / 部分方块读“直接信号”；</li>
 *     <li>{@code getControlInputSignal(BlockPos, Direction, boolean)}：{@code DiodeBlock#getAlternateSignal}
 *         读“控制输入”—— 比较器的<b>侧面输入</b>走这条；中继器的锁定也走这条，但只接受真正的二极管
 *         （{@code sideInputDiodesOnly = true}），见下方说明。</li>
 * </ul>
 * 在返回点取 {@code max(原版值, 源供能, S 自身的方块充能)}，既保留真实红石（不覆盖、不削弱），
 * 又让每一级下游（红石线优先级、继电器延迟、比较器方块实体、实验性 evaluator）继续走它们自己的实现。
 * <b>方块充能</b>是本次扩展：目标 P 是导电方块时，{@code getSignal(P, 任意面)} 在脉冲期间都应返回
 * {@code power}，贴着 P 的中继器 / 火把才能像面对一个真正被充能的方块那样读到信号。
 * 不 Mixin 到 {@code RedStoneWireBlock} / {@code DiodeBlock}：那会同时承担优先级、锁定、
 * 比较器方块实体和版本映射的维护成本，且极易与红石实验特性打架。
 *
 * <h2>为什么是接口 Mixin</h2>
 * {@code SignalGetter} 是接口，三个目标都是 default 方法；Mixin 0.8.x 支持在接口 Mixin 里使用
 * {@code @Inject}（{@code INJECTORS_IN_INTERFACE_MIXINS}，JAVA_21 下启用）。
 * 注入后 {@code Level} / {@code LevelReader} 都不覆写这三个方法，因此服务端查询自然命中。
 *
 * <h2>热路径约束</h2>
 * 只有 {@code this} 是 {@code ServerLevel} 时才查虚拟层，客户端 / 普通 {@code BlockGetter} 立即返回；
 * 虚拟层本身用原始 {@code long} 做两次查表（源键 {@code (S, D)} 与 S 自身的方块充能）并读取缓存的聚合值：
 * 不创建 {@code PowerKey}、不遍历贡献、不加载区块、不写日志。
 *
 * <h2>失效方式</h2>
 * {@code mixins.json} 保持 {@code defaultRequire: 1}：若 MC 版本改变这三个方法的签名，
 * 加载期会直接报错，而不是静默关闭供能。
 */
@Mixin(SignalGetter.class)
public interface SignalGetterMixin {
    /**
     * 红石“普通信号”：{@code S} 处的方块向方向 {@code D} 发出的信号。
     * <p>
     * 原版 {@code hasNeighborSignal(P)} / {@code getBestNeighborSignal(P)} 会以
     * {@code getSignal(P.relative(D), D)} 询问，因此源供能键必须是 {@code (S = P.relative(D), D)}。
     * 这里同时包含 S 自身的方块充能（不分方向），所以被充能的方块对六个面都放能。
     */
    @Inject(method = "getSignal", at = @At("RETURN"), cancellable = true)
    private void additional_abilities$augmentVirtualSignal(final BlockPos position, final Direction direction,
                                                          final CallbackInfoReturnable<Integer> callback) {
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }

        int virtual = VirtualRedstonePower.powerAt(level, position, direction);

        if (virtual > callback.getReturnValue()) {
            callback.setReturnValue(virtual);
        }
    }

    /**
     * 红石“直接信号”：覆盖只读 direct signal 的元件（最典型是比较器）。
     * <p>
     * 与 {@link #additional_abilities$augmentVirtualSignal} 用完全相同的 {@code (S, D)} 规则，
     * 但因为 {@code Block#getDirectSignal} 的默认实现会转调 {@code getSignal}，
     * 两个方法都必须各自取最大值，不能只改其一。
     */
    @Inject(method = "getDirectSignal", at = @At("RETURN"), cancellable = true)
    private void additional_abilities$augmentVirtualDirectSignal(final BlockPos position, final Direction direction,
                                                                final CallbackInfoReturnable<Integer> callback) {
        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }

        int virtual = VirtualRedstonePower.powerAt(level, position, direction);

        if (virtual > callback.getReturnValue()) {
            callback.setReturnValue(virtual);
        }
    }

    /**
     * 红石“控制输入”：{@code DiodeBlock#getAlternateSignal} 用它读比较器的<b>侧面输入</b>与中继器锁定。
     * <p>
     * 该默认方法并不转调 {@code getSignal}：当 {@code S} 处是空气 / 普通非信号方块时，原版直接返回 0。
     * 若不补这一处，比较器的侧面输入与“红石块放在旁边”的直觉不一致 —— 明明有虚拟源却读不到。
     * <p>
     * {@code diodesOnly = true} 时<b>不注入</b>：这条分支只接受真正的二极管（中继器 / 比较器）的
     * direct signal；一个“红石块式”的普通源 / 被充能的普通方块本来就不能锁定中继器。若 {@code S} 处确实压着
     * 一个真二极管，它的 direct signal 已经由上面的 {@code getDirectSignal} 注入取了
     * {@code max(真实值, 虚拟值)}，不需要在这里重复处理。
     */
    @Inject(method = "getControlInputSignal", at = @At("RETURN"), cancellable = true)
    private void additional_abilities$augmentVirtualControlInput(final BlockPos position, final Direction direction,
                                                                 final boolean diodesOnly,
                                                                 final CallbackInfoReturnable<Integer> callback) {
        if (diodesOnly) {
            return;
        }

        if (!((Object) this instanceof ServerLevel level)) {
            return;
        }

        int virtual = VirtualRedstonePower.powerAt(level, position, direction);

        if (virtual > callback.getReturnValue()) {
            callback.setReturnValue(virtual);
        }
    }
}
