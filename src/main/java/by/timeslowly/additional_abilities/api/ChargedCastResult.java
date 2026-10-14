package by.timeslowly.additional_abilities.api;

import org.jetbrains.annotations.NotNull;

/**
 * 一次蓄力终结（手动松手 / 超限到点自动释放 / 取消）的<b>结算结果</b>。
 * <p>
 * <b>为什么不是 boolean</b>：布尔值会把四件完全不同的事混成同一个 {@code false} ——
 * 激活类型不支持、请求指向的不是此刻正在施法的实例、技能未解锁、以及"已接管但按取消收尾"。
 * 调用方无法据此做任何区分，因此必须给出可判定的结果类型。
 * <p>
 * 本类型把结局分成三类，并让"请求未被采纳"与"已按取消收尾"不再共享同一个返回值：
 * <ul>
 *     <li>{@link Outcome#RELEASED} —— 已按 {@link #releasedLevel()} 释放，动作 / 初始魔力 / 冷却 /
 *         观察者收尾都已完成；</li>
 *     <li>{@link Outcome#CANCELLED} —— <b>已接管</b>并按取消收尾：不执行动作、不扣初始魔力、不进冷却。
 *         典型来源是"服务端按真实时间复算后不足最低档"（客户端早已停手，服务端必须照常收尾）；</li>
 *     <li>{@link Outcome#REJECTED} —— 这次请求<b>不该由本模组结算</b>，服务端什么都没做，
 *         调用方也应什么都不做。</li>
 * </ul>
 * {@link #releasedLevel()} 只在 {@code RELEASED} 时非零，其余情况恒为 {@link #NO_LEVEL}（{@code 0}）。
 * <b>内部协议的哨兵值不会从这里透出</b>：{@code AUTO}（{@code -1}）与 {@code CANCEL}（{@code 0}）
 * 属于网络请求的编码约定，不是业务档位。
 *
 * <h2>不变量</h2>
 * {@link #outcome()} 与 {@link #reason()} 必须自洽（{@code RELEASED} 只能配 {@link Reason#RELEASED}，
 * 依此类推），且 {@code releasedLevel} 只允许在成功释放时为正。构造期即校验，故不存在"半个结果"。
 * <p>
 * This type is immutable and safe to store or hand to another thread.
 *
 * @param outcome       三类结局之一
 * @param releasedLevel 实际释放的档位；未释放时为 {@link #NO_LEVEL}
 * @param reason        具体成因，与 {@code outcome} 自洽
 */
public record ChargedCastResult(@NotNull Outcome outcome, int releasedLevel, @NotNull Reason reason) {
    /** 未释放时的档位取值。与"可选性蓄力选到取消"共用同一个业务含义：没有档位。 */
    public static final int NO_LEVEL = 0;

    /** 三类结局。判断"要不要做后续处理"用 {@link ChargedCastResult#handled()}，而不是逐个比对。 */
    public enum Outcome {
        /** 已按 {@link #releasedLevel()} 释放 */
        RELEASED,
        /** 已按取消收尾（不执行动作、不扣初始魔力、不进冷却） */
        CANCELLED,
        /** 未接管，服务端什么都没做 */
        REJECTED
    }

    /**
     * 结局的具体成因。<b>与 {@link Outcome} 一一对应</b>，映射关系在
     * {@link ChargedCastResult} 的构造期校验。
     */
    public enum Reason {
        /** 成功释放（{@code RELEASED} 的唯一原因） */
        RELEASED,
        /** 取消：服务端按真实时间复算后不足最低档 */
        INSUFFICIENT_CHARGE,
        /** 取消：请求本身就是取消（可选性蓄力选定档位 0） */
        CANCELLED_BY_REQUEST,
        /** 拒绝：技能的激活类型不属于蓄力档位族系 */
        NOT_CHARGEABLE,
        /** 拒绝：请求指向的不是此刻正在施法的那个实例 */
        NOT_CURRENTLY_CASTING,
        /** 拒绝：技能未解锁（实例等级低于 {@code MIN_LEVEL_FOR_CALCULATIONS}） */
        ABILITY_NOT_UNLOCKED,
        /** 拒绝：该技能未开启 {@code can_charge_exceed_cast_time}，不存在超限自动释放 */
        OVERCHARGE_NOT_ENABLED
    }

    public ChargedCastResult {
        if (outcome == null || reason == null) {
            throw new IllegalArgumentException("ChargedCastResult requires a non-null outcome and reason");
        }

        boolean consistent = switch (outcome) {
            case RELEASED -> reason == Reason.RELEASED;
            case CANCELLED -> reason == Reason.INSUFFICIENT_CHARGE || reason == Reason.CANCELLED_BY_REQUEST;
            case REJECTED -> reason != Reason.RELEASED
                    && reason != Reason.INSUFFICIENT_CHARGE
                    && reason != Reason.CANCELLED_BY_REQUEST;
        };

        if (!consistent) {
            throw new IllegalArgumentException("Outcome " + outcome + " is inconsistent with reason " + reason);
        }

        if (outcome != Outcome.RELEASED && releasedLevel != NO_LEVEL) {
            throw new IllegalArgumentException("Only a released cast carries a level, got " + releasedLevel);
        }

        if (outcome == Outcome.RELEASED && releasedLevel <= NO_LEVEL) {
            throw new IllegalArgumentException("A released cast must carry a level above " + NO_LEVEL);
        }
    }

    /** 成功释放，档位为 {@code releasedLevel}。 */
    public static @NotNull ChargedCastResult released(final int releasedLevel) {
        return new ChargedCastResult(Outcome.RELEASED, releasedLevel, Reason.RELEASED);
    }

    /** 已接管并按取消收尾（不执行动作、不扣初始魔力、不进冷却）。 */
    public static @NotNull ChargedCastResult cancelled(final @NotNull Reason reason) {
        return new ChargedCastResult(Outcome.CANCELLED, NO_LEVEL, reason);
    }

    /** 未接管：这次请求不该由本模组结算，调用方什么都不必做。 */
    public static @NotNull ChargedCastResult rejected(final @NotNull Reason reason) {
        return new ChargedCastResult(Outcome.REJECTED, NO_LEVEL, reason);
    }

    /** 是否成功释放。 */
    public boolean isReleased() {
        return outcome == Outcome.RELEASED;
    }

    /**
     * 本次终结是否<b>已由本模组接管</b>（成功释放或按取消收尾）。
     * <p>
     * 这是"要不要拦下 DS 原生流程"的判据：接管了就必须拦，被拒绝才应放行。
     * {@code outcome != REJECTED}。
     */
    public boolean handled() {
        return outcome != Outcome.REJECTED;
    }
}
