package by.timeslowly.additional_abilities.api;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.common.ability.activation.ChargedCasts;
import by.timeslowly.additional_abilities.common.network.charged.ChargedReleasePayload;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;

/**
 * 蓄力档位族系激活类型的<b>正式入口</b>：触发一次结算，或读取档位记录。
 *
 * <h2>它挡在前面做什么</h2>
 * 真正的实现在 {@code common.ability.activation.ChargedCasts}，但它贴着实现细节：
 * 形参里的 {@code payloadTicks} 是<b>网络请求</b>的字段（客户端上报的"上限声明"），
 * {@code requestedLevel} 还允许取值 {@code AUTO}（{@code -1}）与 {@code CANCEL}（{@code 0}）
 * 这两个<b>协议哨兵</b>。第三方照那个签名调用，就等于把自己的代码绑在一条私有协议上 ——
 * 协议一改，调用方跟着编译失败（这正是审查报告 F15 与 §4.2 要修的东西）。
 * <p>
 * 本类把这两个"协议味"的参数就地消化：
 * <ul>
 *     <li><b>时长</b>一律取服务端自己的 {@link DragonAbilityInstance#getCurrentTick()}。
 *         这是最严格的一档 —— 服务端复算时本就与它取最小（{@code effectiveTicks = min(请求值,
 *         服务端已蓄刻数, cast_time)}），直接传"服务端已蓄刻数"即等于声明"我不虚报"。
 *         因此本入口<b>天然免疫</b>报告 F02 那条"谎报满时长换高档"的路径；</li>
 *     <li><b>档位</b>由业务语义表达：{@link #release} 是"按已蓄到的档位"，
 *         {@link #releaseAt} 是"请求某一档"（仍会被服务端钳到已达成档位），
 *         {@link #cancel} 是"取消"。</li>
 * </ul>
 *
 * <h2>调用前提</h2>
 * {@code instance} 必须是<b>此刻正在施放</b>的那个实例（{@code MagicData#getCurrentlyCasting()}）。
 * 不满足时全部入口返回 {@code REJECTED} 而<b>不做任何事</b> —— 不会释放、不会取消、
 * 不会扣蓝，也不会替调用方收尾。想先判断可以看
 * {@link ChargedCastResult#handled()}。
 * <p>
 * 只对实现了 {@link ChargeableActivation} 的激活类型有意义；其余类型一律 {@code REJECTED}
 * （{@link ChargedCastResult.Reason#NOT_CHARGEABLE}）。
 *
 * <h2>成功与"已接管但取消"的区别</h2>
 * 两个都会<b>终结这次施法</b>，因此都算 {@link ChargedCastResult#handled()}。
 * 区别只在结果：取消不执行动作、不扣初始魔力、不进冷却。调用方若要区分，
 * 判断 {@link ChargedCastResult#isReleased()} 即可。
 *
 * <p>All entry points require the server main thread.
 */
public final class ChargedCasting {
    private ChargedCasting() {
        // 门面
    }

    /**
     * 按<b>已蓄到的档位</b>释放（与普通 {@code charged} 松手等价）。
     *
     * @param player   施法者
     * @param instance 此刻正在施放的技能实例
     * @return 成功时为 {@code RELEASED}（{@link ChargedCastResult#releasedLevel()} 即实际档位）
     */
    public static @NotNull ChargedCastResult release(final @NotNull ServerPlayer player,
                                                     final @NotNull DragonAbilityInstance instance) {
        return fireServerAuthoritative(player, instance, ChargedReleasePayload.AUTO);
    }

    /**
     * 请求按<b>指定档位</b>释放。
     * <p>
     * 请求值会被服务端钳制到"已达成档位"，因此<b>越档请求只会降档、不会被满足</b>，
     * 也不会被拒绝（拒绝会让服务端停留在施法中，比降档更糟）。
     * 请求值小于 1 时按取消处理。
     *
     * @param requestedLevel 期望档位；{@code <= 0} 等同 {@link #cancel}
     */
    public static @NotNull ChargedCastResult releaseAt(final @NotNull ServerPlayer player,
                                                       final @NotNull DragonAbilityInstance instance,
                                                       final int requestedLevel) {
        return fireServerAuthoritative(player, instance, requestedLevel);
    }

    /**
     * 取消这次施法：不执行动作、不扣初始魔力、<b>不进冷却</b>，并通知观察者停掉循环音效与蓄力动画。
     * <p>
     * 这条路径<b>不能</b>交给 DS 原生停止流程：{@code isApplyingEffects()} 在"按过 {@code cast_time}
     * 仍在蓄力"时会提前返回 {@code true}，原生路径会据此误判为"效果已生效"而凭空施加冷却。
     */
    public static @NotNull ChargedCastResult cancel(final @NotNull ServerPlayer player,
                                                    final @NotNull DragonAbilityInstance instance) {
        return fireServerAuthoritative(player, instance, ChargedReleasePayload.CANCEL);
    }

    /**
     * 超限窗口到点的<b>自动</b>释放：以玩家自身最高档结算。
     * <p>
     * 与 {@link #release} 共用同一份结算，因此动作时钟、扣蓝、冷却、连携触发与观察者收尾完全一致。
     * 与手动释放的区别只有一条业务语义：到点即以<b>玩家最高档</b>释放，
     * 忽略滚轮选定的低档或"选定为取消"。
     * <p>
     * 只有开启了 {@code can_charge_exceed_cast_time} 的技能才有这条路径；否则返回
     * {@link ChargedCastResult.Reason#OVERCHARGE_NOT_ENABLED}。技能自身的超限到点是由
     * DS 的 tick 流程驱动的（本模组注入在完成点接管），<b>通常不需要第三方主动调用本方法</b> ——
     * 它公开出来是为了让"自定义的到此为止判定"也能走同一份结算，而不是各写一遍。
     */
    public static @NotNull ChargedCastResult releaseOvercharged(final @NotNull ServerPlayer player,
                                                                final @NotNull DragonAbilityInstance instance) {
        return ChargedCasts.settleOvercharged(player, instance);
    }

    /**
     * 此刻<b>松手会放几档</b>（查询语义，不产生任何结算）。
     * <p>
     * 这是给 HUD、提示与调试用的只读换算，语义与
     * {@code /dragon-ability query … current_selected_level} 完全一致：
     * <ul>
     *     <li>手动选定过 → 返回选定值；其中 {@link ChargedCastResult#NO_LEVEL}（{@code 0}）
     *         <b>表示玩家已选定为取消</b>，是一个有效答案；</li>
     *     <li>自动跟随 → 返回当前已蓄到的档位；</li>
     *     <li>{@code empty} → <b>没有可参照的档位</b>：未在蓄力、蓄力尚未达最低档、
     *         或激活类型不属于本族系。</li>
     * </ul>
     * <b>为什么用 {@link OptionalInt} 而不是哨兵值</b>：实现里"未指定"与"选定为取消"
     * 分别是 {@code -1} 与 {@code 0}，后者是一个真实答案。若把前者也夹成 {@code 0}，
     * 调用方就再也分不出"玩家按了取消"和"还没得选" —— 那正是内部协议哨兵不该透出的原因。
     */
    public static @NotNull OptionalInt pendingLevel(final @NotNull ServerPlayer player,
                                                    final @NotNull DragonAbilityInstance instance) {
        int resolved = ChargedCasts.resolveSelectedLevel(player, instance);

        // AUTO（-1）是"未指定"的内部哨兵，映射为空；其余（含 0 = 选定取消）都是有效业务档位
        return resolved < ChargedCastResult.NO_LEVEL ? OptionalInt.empty() : OptionalInt.of(resolved);
    }

    /**
     * 档位查询：正在蓄力 → 当前蓄力对应的档位；否则 → <b>最近一次实际释放</b>所用的档位。
     * <p>
     * 取消不写入最近释放记录（取消不是释放）。没有可报告的档位时（未在蓄力且无记录、
     * 或激活类型不属于本族系）返回 {@link ChargedCastResult#NO_LEVEL}。
     * 与 {@link #pendingLevel} 不同，这里 {@code 0} 只有"没有档位"一种含义，
     * 因此可以直接用具名常量表达，不需要 {@code OptionalInt}。
     */
    public static int lastOrCurrentLevel(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance) {
        return Math.max(ChargedCasts.resolveQueryLevel(player, instance), ChargedCastResult.NO_LEVEL);
    }

    /**
     * 以服务端自己的刻计数作为"已蓄力时长"发起结算。
     * <p>
     * 这是本门面唯一的实际出口：{@code payloadTicks} 取
     * {@link DragonAbilityInstance#getCurrentTick()} 而非任何外部上报值。
     * 服务端复算时对该值取 {@code min(它, 它, cast_time)}，结果恒为它本身按 {@code cast_time} 封顶，
     * 因此"调用方说蓄了多久"这件事在正式入口上不存在。
     *
     * <p>The client-reported duration is never forwarded: this facade passes the server's own
     * tick counter, which is the strictest input the settlement service accepts.
     */
    private static @NotNull ChargedCastResult fireServerAuthoritative(final @NotNull ServerPlayer player,
                                                                     final @NotNull DragonAbilityInstance instance,
                                                                     final int requestedLevel) {
        return ChargedCasts.fire(player, instance, instance.getCurrentTick(), requestedLevel);
    }
}
