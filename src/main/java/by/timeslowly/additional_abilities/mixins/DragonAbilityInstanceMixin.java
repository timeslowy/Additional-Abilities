package by.timeslowly.additional_abilities.mixins;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.common.ability.activation.ChargedCasts;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让"可以蓄力按住超过上限而不释放"成为可能
 * （蓄力类激活类型的 {@code can_charge_exceed_cast_time} 字段，
 * 目前由 {@code additional_abilities:charged} 与 {@code additional_abilities:optional_charged} 使用），
 * 并把"超限窗口到点"的自动释放交给本模组的统一结算服务。
 *
 * <h2>为什么要动 Mixin</h2>
 * 蓄力档位类型本身完全建立在 DS 现成流程之上，唯独这一项做不到：
 * {@code DragonAbilityInstance#tickActions} 的完成判定写死在
 * {@code int castTime = value().activation().getCastTime(level);} 这个局部量上 ——
 * 一旦 {@code currentTick} 追平它，DS 就会播完成音效、扣初始魔力、执行默认动作并立刻停手。
 * 而 {@code Activation} 接口只有取值器、没有任何 tick / 松手回调，
 * 外部无法在"该释放"与"还想继续按住"之间插话。
 *
 * <h2>两个注入点各自负责什么</h2>
 * <ol>
 *     <li>{@link #additional_abilities$holdChargePastCastTime}（{@code @ModifyVariable}）——
 *         <b>撑开</b>完成判定阈值，让 {@code currentTick} 可以越过真实 {@code cast_time}
 *         而不被 DS 自动释放；</li>
 *     <li>{@link #additional_abilities$settleOverchargedAtHoldLimit}（{@code @Inject} 到方法头）——
 *         在撑开后的阈值到点时<b>接管</b>这次自动释放，交给
 *         {@link ChargedCasts#settleOvercharged} 做与手动松手完全一致的结算，然后取消原方法。</li>
 * </ol>
 * 只改 {@code tickActions} 内部的这一个局部量、并在方法头做一次可取消的接管，是侵入面最小的做法：
 * <ul>
 *     <li>DS 的其余逻辑（蓄力分支、charging 触发器、动画、音效）<b>原样运行</b>，
 *         我们只是让它停在"蓄力中"分支，或改由统一服务完成最后一步；</li>
 *     <li>该局部量只在这个方法里存在，因此
 *         {@code Activation#getCastTime} 本身不受影响 ——
 *         HUD 蓄力条的长度、侧边栏"施法时间"、以及本模组
 *         {@code ChargedCasts} 里读取的档位阈值全部照旧；</li>
 *     <li>只对实现了 {@link ChargeableActivation} 且显式开启该字段的技能生效，
 *         其他技能与 DS 内置技能完全不受影响。</li>
 * </ul>
 * 蓄力按满后继续按住时，{@code currentTick} 会一直增长（越过真实 {@code cast_time}），
 * 于是 HUD 上的满档数字会一直保持、蓄力条保持满格，直到玩家松手才由
 * {@code ChargedCasts#fire} 按档位（{@code optional_charged} 下为玩家滚轮指定的档位）释放。
 * <p>
 * 撑开的幅度由技能的 {@code max_overcharged_duration} 决定（见
 * {@link ChargeableActivation#getHoldLimitTicks(int)}）：配置了该字段就只撑到
 * {@code cast_time + 超限窗口长度}；未配置则撑到
 * {@code Integer.MAX_VALUE}，即无限按住、只有松手才释放。
 *
 * <h2>为什么到点这一帧必须由统一服务结算</h2>
 * 单纯"让 DS 自己走到完成点"是可用的，但 DS 会把<b>越限后的</b> {@code currentTick} 原样传给
 * {@code tickDefaultActions}，而 {@code ActionContainer} 内部按
 * {@code actualTick = currentTick - getCastTime(level)} 对 {@code trigger_rate} 取模。
 * 于是 {@code trigger_rate > 1} 的动作在自动释放时会被静默吞掉，却照样扣蓝、进冷却并触发连携 ——
 * 与手动松手的结果不一致。因此在完成点前一步接管、走同一份结算代码，这条差异就不存在了。
 *
 * <h2>副作用（已在收尾路径上处理）</h2>
 * {@code currentTick} 越过 {@code castTime} 会让 {@code DragonAbilityInstance#isApplyingEffects()}
 * 提前返回 {@code true}，进而使 {@code MagicData#stopCasting(player, instance)} 误判为
 * "效果已生效"而施加冷却。因此"取消施法"不能交给 DS 原生路径，
 * 必须显式 {@code stopCasting(…, false)} —— 详见 {@code ChargedCasts}。
 *
 * <h2>风险与对策</h2>
 * 两个注入点都依赖 DS 的方法形状：{@code @ModifyVariable} 依赖局部变量名 {@code castTime}
 * （源码中该局部量的名字，不是序号），{@code @Inject} 依赖 {@code tickActions} 的参数表。
 * DS 若重构 {@code tickActions} 可能失效。因此这里加了两道安全网：
 * 改写局部量时只有当被改写的值确实等于本技能的 {@code cast_time} 才接管；
 * 接管时 {@link ChargedCasts#settleOvercharged} 还会复核"这确实是此刻正在施法的实例"，
 * 任一条不成立就原样返回、不改动任何行为。
 * 同时 {@code mixins.json} 保留 {@code defaultRequire: 1}，
 * 一旦注入点失效会在加载期直接报错，而不是静默失效。
 */
@Mixin(DragonAbilityInstance.class)
public abstract class DragonAbilityInstanceMixin {
    /**
     * 撑开 {@code tickActions} 里的完成判定阈值。
     *
     * @param castTime {@code tickActions} 内名为 {@code castTime} 的局部量（原本是真实 {@code cast_time}）
     * @return 撑开后的阈值：{@code cast_time + max_overcharged_duration}；
     *         未配置超限窗口时为 {@code Integer.MAX_VALUE}（"永远不完成"）
     */
    @ModifyVariable(method = "tickActions", at = @At("STORE"), name = "castTime")
    private int additional_abilities$holdChargePastCastTime(final int castTime) {
        DragonAbilityInstance self = (DragonAbilityInstance) (Object) this;

        if (!(self.value().activation() instanceof ChargeableActivation chargeable)
                || !chargeable.canChargeExceedCastTime()) {
            return castTime;
        }

        // 安全网：局部量名字对不上时这个值不会等于 cast_time，于是原样返回、不改动任何行为
        if (castTime != chargeable.getCastTime(self.level())) {
            return castTime;
        }

        // 未配置超限窗口 -> Integer.MAX_VALUE：currentTick 永远小于它，一直停留在"蓄力中"分支；
        // 配置了超限窗口 -> cast_time + 窗口长度：到达该刻会回到 DS 原生完成流程自动释放
        return chargeable.getHoldLimitTicks(self.level());
    }

    /**
     * 超限窗口到点：由本模组的统一结算服务完成这次自动释放，随后取消 {@code tickActions}，
     * 避免方法体继续执行 DS 原生的扣蓝 / 动作 / 收尾分支（那会重复结算一次）。
     * <p>
     * 只有服务端才结算：{@code tickActions} 双端都会跑，而客户端在同一刻会自行
     * {@code stopCasting} 收尾，不需要也不应该由这里插手。
     * <p>
     * 到点判定用 {@code getCurrentTick() + 1}：本方法注入在方法头，此时本刻的自增尚未发生，
     * 而 DS 判断"到点"用的是自增之后的值。用 {@code long} 比较以免无限按住时的
     * {@code Integer.MAX_VALUE} 自增溢出。
     */
    @Inject(method = "tickActions(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"), cancellable = true)
    private void additional_abilities$settleOverchargedAtHoldLimit(final Player dragon, final CallbackInfo callbackInfo) {
        if (!(dragon instanceof ServerPlayer serverPlayer)) {
            return;
        }

        DragonAbilityInstance self = (DragonAbilityInstance) (Object) this;

        if (!(self.value().activation() instanceof ChargeableActivation chargeable)
                || !chargeable.canChargeExceedCastTime()) {
            return;
        }

        int playerLevel = self.level();

        if (playerLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            return;
        }

        if ((long) self.getCurrentTick() + 1 < chargeable.getHoldLimitTicks(playerLevel)) {
            return;
        }

        // 结算成功才取消；返回 false 表示"这不是此刻正在施法的实例"，应放行 DS 原生流程
        if (ChargedCasts.settleOvercharged(serverPlayer, self)) {
            callbackInfo.cancel();
        }
    }
}
