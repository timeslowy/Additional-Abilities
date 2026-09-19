package by.timeslowly.additional_abilities.mixins;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 让"可以蓄力按住超过上限而不释放"成为可能
 * （蓄力类激活类型的 {@code can_charge_exceed_cast_time} 字段，
 * 目前由 {@code additional_abilities:charged} 与 {@code additional_abilities:optional_charged} 使用）。
 *
 * <h2>为什么要动 Mixin</h2>
 * 蓄力档位类型本身完全建立在 DS 现成流程之上，唯独这一项做不到：
 * {@code DragonAbilityInstance#tickActions} 的完成判定写死在
 * {@code int castTime = value().activation().getCastTime(level);} 这个局部量上 ——
 * 一旦 {@code currentTick} 追平它，DS 就会播完成音效、扣初始魔力、执行默认动作并立刻停手。
 * 而 {@code Activation} 接口只有取值器、没有任何 tick / 松手回调，
 * 外部无法在"该释放"与"还想继续按住"之间插话。
 *
 * <h2>注入点为什么选这里</h2>
 * 只改 {@code tickActions} 内的这一个局部量，是侵入面最小的做法：
 * <ul>
 *     <li>DS 的其余逻辑（蓄力分支、charging 触发器、动画、音效）<b>原样运行</b>，
 *         我们只是让它永远停在"蓄力中"分支；</li>
 *     <li>该局部量只在这个方法里存在，因此
 *         {@code Activation#getCastTime} 本身不受影响 ——
 *         HUD 蓄力条的长度、侧边栏"施法时间"、以及本模组
 *         {@code ChargedCasts#fire} 里读取的档位阈值全部照旧；</li>
 *     <li>只对实现了 {@link ChargeableActivation} 且显式开启该字段的技能生效，
 *         其他技能与 DS 内置技能完全不受影响。</li>
 * </ul>
 * 蓄力按满后继续按住时，{@code currentTick} 会一直增长（越过真实 {@code cast_time}），
 * 于是 HUD 上的满档数字会一直保持、蓄力条保持满格，直到玩家松手才由
 * {@code ChargedCasts#fire} 按档位（{@code optional_charged} 下为玩家滚轮指定的档位）释放。
 *
 * <h2>副作用（已在收尾路径上处理）</h2>
 * {@code currentTick} 越过 {@code cast_time} 会让 {@code DragonAbilityInstance#isApplyingEffects()}
 * 提前返回 {@code true}，进而使 {@code MagicData#stopCasting(player, instance)} 误判为
 * "效果已生效"而施加冷却。因此"取消施法"不能交给 DS 原生路径，
 * 必须显式 {@code stopCasting(…, false)} —— 详见 {@code ChargedCasts}。
 *
 * <h2>风险与对策</h2>
 * {@code @ModifyVariable} 依赖局部量序号，DS 若重构 {@code tickActions} 可能错位。
 * 因此这里加了一道安全网：只有当被改写的值确实等于本技能的 {@code cast_time} 时才接管；
 * 同时 {@code mixins.json} 保留 {@code defaultRequire: 1}，
 * 一旦注入点失效会在加载期直接报错，而不是静默失效。
 */
@Mixin(DragonAbilityInstance.class)
public abstract class DragonAbilityInstanceMixin {
    /**
     * @param castTime {@code tickActions} 里的完成判定阈值（局部量序号 0）
     * @return 撑开后的阈值：{@code Integer.MAX_VALUE} 表示"永远不完成"
     */
    @ModifyVariable(method = "tickActions", at = @At("STORE"), name = "castTime")
    private int additional_abilities$holdChargePastCastTime(final int castTime) {
        DragonAbilityInstance self = (DragonAbilityInstance) (Object) this;

        if (!(self.value().activation() instanceof ChargeableActivation chargeable)
                || !chargeable.canChargeExceedCastTime()) {
            return castTime;
        }

        // 安全网：序号错位时这个值不会等于 cast_time，于是原样返回、不改动任何行为
        if (castTime != chargeable.getCastTime(self.level())) {
            return castTime;
        }

        // currentTick 永远小于它 -> 一直停留在"蓄力中"分支，不会被自动释放
        return Integer.MAX_VALUE;
    }
}
