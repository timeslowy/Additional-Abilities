package by.timeslowly.additional_abilities.mixins;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.DragonBreathTarget;
import by.timeslowly.additional_abilities.common.ability.geometry.BreathBeam;
import by.timeslowly.additional_abilities.common.ability.geometry.BreathSelection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「龙息范围收束」属性（{@code additional_abilities:dragon_breath_restriction}）的作用点。
 *
 * <h2>为什么要动 Mixin</h2>
 * DS 只留了一个出口 {@code DragonBreathTarget#calculateBreathArea(Player, DragonAbilityInstance)}，
 * 且 {@code dragonsurvival:dragon_breath} 这一注册表条目不允许被覆盖，
 * 因此没有任何纯事件方案可用。
 *
 * <h2>两个注入点</h2>
 * <ol>
 *     <li><b>{@code calculateBreathArea} 的 {@code RETURN}</b>：把返回值换成
 *         {@link BreathBeam#boundingBox()}（光束的**精确**轴对齐包围盒）。
 *         这样客户端 {@code ClientDragonRenderer#renderAbilityHitbox}（F3+B 的红框）与服务端
 *         实际粗筛区始终一致 —— 那个红框现在是「粗筛范围」而不再是「判定区」；</li>
 *     <li><b>{@code apply} 的 {@code HEAD}（可取消）</b>：收束生效时接管整个目标筛选，
 *         交给 {@link BreathSelection} 做「粗筛 + 窄相」；未收束时**不取消**，
 *         DS 原逻辑原样运行（零回归）。</li>
 * </ol>
 *
 * <h2>为什么不用 {@code @Redirect} 改两个枚举调用</h2>
 * 这两个调用并不在 {@code apply} 本体里，而在它的 lambda 中
 * （{@code lambda$apply$3} 里的 {@code BlockPos.betweenClosedStream}、
 * {@code lambda$apply$7} 里的 {@code ServerLevel#getEntities}）——
 * 用 {@code @Redirect} 就必须绑定 {@code lambda$apply$N} 的编号，DS 一旦增删本类的任何 lambda 就会错位。
 * 改为 HEAD 接管后：既不用绑定 lambda 编号，也能把「枚举上限」这类原版没有的逻辑写进去。
 * <p>
 * 代价是本类镜像了 DS 2.0.70 的 {@code apply} 结构（枚举 + 过滤链 + 效果派发）。
 * 过滤链本身**没有复制**：{@code targetingMode#isEntityRelevant}、{@code target_conditions}
 * （{@code matches}）与效果派发全部走 DS 的 public API，DS 调整过滤语义时我们自动跟随；
 * 只有「枚举什么」是我们自己的。若日后 DS 在 {@code apply} 里新增步骤，需要人工比对更新
 * （做法与本模组 {@code AntiDragonBreathTarget} 镜像 DS 箱体算式同款，Javadoc 里已记录来源版本）。
 *
 * <h2>风险与对策</h2>
 * {@code mixins.json} 保留 {@code defaultRequire: 1}：DS 若重构这两个方法（改名 / 改签名），
 * 会在加载期直接报错，而不是静默失效。
 * 已比对 DS 2.0.69（本地源码）与 2.0.70（构建依赖 jar）：两者签名一致。
 */
@Mixin(DragonBreathTarget.class)
public abstract class DragonBreathTargetMixin {
    /**
     * 选择箱改为「光束的包围盒」（仅粗筛与调试显示用）。
     * 属性为 0（绝大多数玩家、以及所有非玩家实体）时直接原样返回。
     */
    @Inject(method = "calculateBreathArea", at = @At("RETURN"), cancellable = true)
    private void additional_abilities$restrictBreathArea(final Player dragon, final @NotNull DragonAbilityInstance ability,
                                                         final CallbackInfoReturnable<AABB> cir) {
        BreathBeam beam = BreathBeam.restricted(dragon, ((DragonBreathTarget) (Object) this).rangeMultiplier(), ability.level());

        if (beam != null) {
            cir.setReturnValue(beam.boundingBox());
        }
    }

    /**
     * 收束生效时接管目标筛选：包围盒只作粗筛，命中判定换成真正的光束（OBB）。
     * 属性为 0 时不取消 → DS 原路径逐位不变。
     */
    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private void additional_abilities$applyRestricted(final ServerPlayer dragon, final @NotNull DragonAbilityInstance ability,
                                                      final CallbackInfo ci) {
        DragonBreathTarget self = (DragonBreathTarget) (Object) this;
        BreathBeam beam = BreathBeam.restricted(dragon, self.rangeMultiplier(), ability.level());

        if (beam == null) {
            return;
        }

        self.target()
                .ifLeft(blockTarget -> BreathSelection.applyToBlocks(dragon, ability, beam, blockTarget))
                .ifRight(entityTarget -> BreathSelection.applyToEntities(dragon, ability, beam, entityTarget));

        ci.cancel();
    }
}
