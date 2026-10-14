package by.timeslowly.additional_abilities.common.ability.actions;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 当前正在派发的<b>施法动作身份</b>（服务端作用域）。
 *
 * <h2>职责</h2>
 * 为"这一次施法"提供一个可比较的身份，供方块震动按动作分离声源。
 * 身份取<b>施法起点世界刻</b>（{@code castStartTick}），与 {@code DomainTarget} /
 * {@code DomainInstance} 判定"同一次施法"用的是同一个量：
 * <pre>
 * castStartTick = now - (currentTick - 1)
 * </pre>
 * 它在同一次施法内恒定、不同次施法必然不同，因此天然就是动作身份，
 * 不需要 DS 提供施法 ID（DS 也没有）。
 *
 * <h2>为什么用作用域而不是传参</h2>
 * 方块效果的形参由 DS 的 {@code AbilityBlockEffect#apply(dragon, ability, position, direction)}
 * 固定，无法加参数；实现该接口的效果（含 DS 内置的 33 种）都只能从形参取信息。
 * 在<b>派发点</b>设置作用域、在<b>效果内</b>读取，是唯一不改变接口的做法。
 *
 * <h2>线程模型</h2>
 * 依赖"服务端只有一个逻辑主线程"这一前提，与 {@code api} 包的线程契约一致。
 * 用 {@link ThreadLocal} 使作用域按线程隔离：万一有别的线程意外读到，
 * 拿到的是 {@code null} 而不是别人的身份，退化为保守分组，不会串味。
 *
 * <h2>必须成对使用</h2>
 * 一律用 {@link #run}，它在 {@code finally} 里恢复上一层，
 * 因此嵌套派发（领域回调里再触发别的技能）也能正确还原。
 * 手工 {@code set} / {@code clear} 会在效果抛异常时把身份泄漏到后续派发上。
 *
 * <h2>拿不到身份时</h2>
 * {@link #current()} 返回 {@code null}，调用方（{@code BlockQuakes.trigger}）退化为
 * "没有动作身份"：同一玩家同一技能的全部震动仍会并成一组发声（即保守行为）。
 * 未接线的调用点不会因此抛异常或产生更差的结果，只是拿不到分离效果。
 *
 * <pre>{@code
 * // null identity degrades to the conservative grouping - never an exception.
 * // Always pair set/restore through run(); a thrown effect must not leak identity.
 * }</pre>
 */
public final class ActionScope {
    /**
     * 动作身份。{@code null} = 当前不在任何已标记的派发作用域内。
     * <p>
     * 这里只存一个 {@code long}（施法起点刻）而不是完整的施法对象：
     * 身份的比较语义就是"起点刻相等即同一次施法"，存多了反而要维护一堆无用字段。
     */
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private ActionScope() {
        // 作用域持有类
    }

    /** 当前动作身份（施法起点世界刻）；{@code null} 表示不在已标记的派发作用域内。 */
    public static @Nullable Long current() {
        return CURRENT.get();
    }

    /**
     * 在给定身份下执行 {@code action}，结束后恢复上一层身份。
     *
     * @param castStartTick 施法起点世界刻，见类注释；{@code null} 表示不标记
     */
    public static void run(final @Nullable Long castStartTick, final @NotNull Runnable action) {
        Long previous = CURRENT.get();

        if (castStartTick != null) {
            CURRENT.set(castStartTick);
        } else {
            CURRENT.remove();
        }

        try {
            action.run();
        } finally {
            // 恢复上一层而不是无条件 remove：本方法可能被嵌套调用
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        }
    }

    /**
     * 按「某个方块效果是否在<b>释放技能那一刻</b>被一次性派发」计算身份。
     *
     * <p>
     * DS 的 {@code ActionContainer#tick} 用 {@code currentTick} 推进节流，
     * 因此 {@code now - (currentTick - 1)} 就是本次施法的起点刻
     * （{@code currentTick} 在 {@code apply} 时至少为 1）。
     * 这与 {@code DomainTarget} 建立领域时用的是同一条算式，两者必须一致 ——
     * 否则同一次施法在领域与一次性效果里会得到两个身份。
     *
     * @param level      当前服务端维度（取世界刻用）
     * @param currentTick 技能实例的 {@code getCurrentTick()}
     * @return 施法起点世界刻；{@code currentTick <= 0}（实例不在施法中）时返回 {@code null}
     */
    public static @Nullable Long castStartTickOf(final @NotNull ServerLevel level, final int currentTick) {
        if (currentTick <= 0) {
            return null;
        }

        return level.getGameTime() - (currentTick - 1L);
    }

    /**
     * 动作身份键：施法者 + 技能 + <b>施法起点世界刻</b>。
     * <p>
     * 只用 {@code record} 自带的相等语义：三段全等才算"同一次动作"。
     * 特别是 {@code castStartTick} 为 {@code null}（身份未知）时，
     * 只有同样是"未知"的项才会并到一起 —— 未接线调用方的震动<b>不会</b>
     * 被并入某一次真实动作，从而避免"声源被拖到两片区域中间、两边都听不到"。
     *
     * @param caster        施法者 UUID
     * @param ability       技能键
     * @param castStartTick 施法起点世界刻；{@code null} 表示"身份未知"，退化为保守分组
     */
    public record ActionKey(@NotNull UUID caster, @NotNull ResourceKey<?> ability,
                            @Nullable Long castStartTick) {
    }
}
