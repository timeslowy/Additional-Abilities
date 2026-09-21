package by.timeslowly.additional_abilities.mixins;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger.OnAbilityCast;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为 {@code additional_abilities:on_ability_cast}（技能使用后）被动触发器提供唯一的接入口。
 *
 * <h2>为什么要动 Mixin</h2>
 * 龙之技能的施法流程<b>没有任何公共事件</b>：起手是网络包 {@code SyncBeginCast} → {@code MagicData#attemptCast}，
 * 收尾是 {@code DragonAbilityInstance#tickActions} / {@code SyncStopCast} → {@code MagicData#stopCasting}。
 * 既不经过 NeoForge 事件总线，也不是本模组可以注册载荷处理的包，因此「技能被使用」这件事
 * 只能从 DS 自己的方法上取得，纯监听器方案不存在。
 *
 * <h2>注入点为什么选这里</h2>
 * {@code MagicData#stopCasting(Player, DragonAbilityInstance, boolean)} 的第三个参数
 * {@code withCooldown} 在<b>上层调用点</b>被赋予 {@code instance.isApplyingEffects()}，含义正是
 * 「本次施法的效果已经真正生效」—— 换句话说，这个参数本身就是 DS 对「这次算不算用出去了」的官方判定：
 * <ul>
 *     <li>simple 读条完成 / channeled 松手或达 {@code max_duration} /
 *         本模组蓄力族系按档位释放 → {@code true}，触发 ✅；</li>
 *     <li>提前松手（效果未生效）、本模组蓄力族系「改为取消」
 *         （{@code ChargedCasts#cancel} 显式传 {@code false}）→ <b>不触发</b> ✅；</li>
 *     <li>被另一个技能打断时走的是 {@code DragonAbilityInstance#release}（{@code attemptCast} 内直接调用），
 *         根本不经过本方法 → 不会误触发 ✅。</li>
 * </ul>
 * 比起手（{@code attemptCast}）更贴近「使用了技能<b>后</b>」，也不会把取消算作一次使用。
 * <p>
 * 该重载与另外两个 {@code stopCasting(Player)} / {@code stopCasting(Player, DragonAbilityInstance)}
 * <b>同名重载</b>，因此这里用<b>完整描述符</b>定位而不是靠序号或在 {@code TAIL} 上撞运气。
 *
 * <h2>风险与对策</h2>
 * DS 若调整该重载的签名或移除它，注入点会失效。{@code mixins.json} 保留
 * {@code defaultRequire: 1}，因此届时会在<b>加载期</b>直接报错，而不是静默失效
 * （与同目录的 {@code DragonAbilityInstanceMixin} 采取同一策略）。
 *
 * <h2>副作用</h2>
 * {@code stopCasting} 双端都会被调用（客户端在 {@code DragonAbilityInstance#stopCasting} 的
 * {@code isClientSide} 分支里也会走本地 {@code MagicData}），因此这里必须先筛出 {@link ServerPlayer}；
 * 其余判定（被动 / 龙形态 / 集合归属）全部留在 {@link OnAbilityCast#trigger} 内，本类只做转接。
 */
@Mixin(MagicData.class)
public abstract class MagicDataMixin {
    /**
     * 施法结算后分发 {@code on_ability_cast} 触发器。
     *
     * @param player      施法者（双端都会到达这里，非服务端玩家在此被拦下）
     * @param instance    刚完成结算的技能实例；{@code null} 表示「停止当前施法但未命中具体实例」
     * @param withCooldown 效果是否已生效（{@code true} 才是一次真正的「使用」）
     */
    @Inject(
            method = "stopCasting(Lnet/minecraft/world/entity/player/Player;Lby/dragonsurvivalteam/dragonsurvival/registry/dragon/ability/DragonAbilityInstance;Z)V",
            at = @At("TAIL")
    )
    private void additional_abilities$onAbilityCast(final Player player, final DragonAbilityInstance instance,
                                                    final boolean withCooldown, final CallbackInfo callbackInfo) {
        if (withCooldown && instance != null && player instanceof ServerPlayer serverPlayer) {
            OnAbilityCast.trigger(serverPlayer, instance);
        }
    }
}
