package by.timeslowly.additional_abilities.client;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 可选性蓄力档位（{@code additional_abilities:optional_charged}）的<b>客户端选档状态</b>（仅客户端加载）。
 *
 * <h2>两个档位概念</h2>
 * <ul>
 *     <li><b>已达成档位</b>（achieved）—— 由"已蓄力时长"反查得到，随蓄力单调上升，上限为玩家自身的技能等级。
 *         它是只读的换算结果，本类不持有，每次由调用方传入。</li>
 *     <li><b>选定档位</b>（selected）—— 玩家用鼠标滚轮指定的、松手时实际要用的档位，
 *         取值域为 {@code [0, 已达成档位]}；{@code 0} 表示"取消，不释放"。本类持有它。</li>
 * </ul>
 *
 * <h2>自动跟随 / 手动指定</h2>
 * 未滚动时处于<b>自动跟随</b>状态，选定档位恒等于已达成档位 —— 此时表现与
 * {@code additional_abilities:charged} 完全一致（蓄得越久放得越高）。
 * 一旦滚动即转为<b>手动指定</b>，此后不再随蓄力自动上升；把档位滚回"已达成档位"即恢复自动跟随。
 * <p>
 * 手动指定<b>不会锁死蓄力</b>：已达成档位仍然继续上升，玩家可随时再往上滚。
 * 这源于约束"选定档位必须当前已蓄到"只检查 {@code 已达成 ≥ 选定}，
 * 而不改变蓄力过程本身。
 *
 * <h2>状态归属</h2>
 * 状态按<b>技能</b>区分：切换施法技能（或结束施法）时立即清空，
 * 避免把上一个技能的选档带到下一个技能上。调用方在每帧 / 每次交互前调用
 * {@link #onCasting} 对齐当前施法技能即可。
 */
public final class OptionalChargedSelection {
    /** 选定档位的"自动跟随"哨兵值，与 {@code ChargedReleasePayload#AUTO} 保持一致。 */
    public static final int AUTO = -1;

    /** 当前选档状态归属的技能；{@code null} 表示尚未绑定任何施法。 */
    private static @Nullable ResourceKey<DragonAbility> currentAbility = null;

    /** 玩家手动指定的档位；仅在 {@link #manual} 为 {@code true} 时有意义。 */
    private static int selectedLevel = AUTO;

    /** 是否处于手动指定状态（玩家至少滚动过一次且未滚回"已达成档位"）。 */
    private static boolean manual = false;

    private OptionalChargedSelection() {
        // 仅客户端状态
    }

    /**
     * 对齐当前正在施法的技能：技能变了就清空选档状态。
     * <p>
     * 幂等，可在每帧渲染、每次滚动、每次松手前无脑调用。
     *
     * @param castingAbility 当前正在施法的技能注册键
     */
    public static void onCasting(final @NotNull ResourceKey<DragonAbility> castingAbility) {
        if (!castingAbility.equals(currentAbility)) {
            currentAbility = castingAbility;
            clear();
        }
    }

    /** 结束施法（或未在施法）时清空状态。 */
    public static void reset() {
        currentAbility = null;
        clear();
    }

    /**
     * 以指定方向调整选定档位。
     * <p>
     * 调整基准取"当前实际会释放的档位"：自动跟随时即已达成档位，手动时即手动值，
     * 因此连续上滚会从自动档位出发逐级上升，不会出现"先跳回低档"的怪异手感。
     *
     * @param delta    调整量，{@code +1} 为上滚、{@code -1} 为下滚
     * @param achieved 当前已达成档位
     * @return 是否发生了实际变化；{@code false} 表示已顶到边界（或尚未达最低蓄力，无可选项）
     */
    public static boolean adjust(final int delta, final int achieved) {
        // 未达最低蓄力时全域只有 0，没有可选项：不接管，让滚轮照常切换快捷栏物品
        if (achieved < 1) {
            return false;
        }

        int before = manual ? Math.min(selectedLevel, achieved) : achieved;
        int next = Mth.clamp(before + delta, 0, achieved);

        // 顶到边界（例如已达上限还继续上滚、已是 0 还继续下滚）：不视为变化，
        // 因此不会播放反馈音、也不会多发一次同步包
        if (next == before) {
            return false;
        }

        selectedLevel = next;
        // 滚回"已达成档位"即恢复自动跟随，此后继续随蓄力上升
        manual = next < achieved;
        return true;
    }

    /**
     * 松手时实际应当使用的档位。
     *
     * @param achieved 当前已达成档位
     * @return {@code 0} 表示取消；{@code >= 1} 表示按该档位释放
     */
    public static int resolveReleaseLevel(final int achieved) {
        if (achieved < 1) {
            return 0;
        }

        return manual ? Math.min(selectedLevel, achieved) : achieved;
    }

    /**
     * 供 HUD 使用的"当前将要释放的档位"。
     *
     * @param achieved 当前已达成档位
     * @return 自动跟随时等于 {@code achieved}；{@code 0} 表示已选定为取消
     */
    public static int displayLevel(final int achieved) {
        if (achieved < 1) {
            return 0;
        }

        return manual ? Math.min(selectedLevel, achieved) : achieved;
    }

    /**
     * 是否需要同步给服务端（仅供查询指令展示用）。
     *
     * @return 手动指定时为具体档位，自动跟随时为 {@link #AUTO}
     */
    public static int rawForSync() {
        return manual ? selectedLevel : AUTO;
    }

    /** 是否处于手动指定状态（HUD 据此区分展示样式）。 */
    public static boolean isManual() {
        return manual;
    }

    private static void clear() {
        selectedLevel = AUTO;
        manual = false;
    }
}
