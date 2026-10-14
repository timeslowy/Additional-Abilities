package by.timeslowly.additional_abilities.api;

import by.timeslowly.additional_abilities.common.ability.block_effects.BlockGlows;
import by.timeslowly.additional_abilities.common.ability.block_effects.GlowDisplayType;
import by.timeslowly.additional_abilities.common.network.screenvision.ScreenVisionSender;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 视觉类效果的<b>正式入口</b>：方块发光与屏幕视觉的登记、刷新与清理。
 *
 * <h2>两个效果，两套生命周期</h2>
 * <ul>
 *     <li><b>方块发光</b>（{@link #glow} / {@link #clearGlowByCasters}）——
 *         服务端持有条目表，按「位置 + 颜色 + 显示类型」合并，逐刻给附近玩家重发续命。
 *         因为是<b>服务端权威</b>的固定世界坐标，视距内任何玩家看到的是同一处发光；</li>
 *     <li><b>屏幕视觉</b>（{@link #screenVision} / {@link #clearScreenVision}）——
 *         纯客户端状态，服务端不持副本，只能"下发 + 通知清空"。因此它<b>无法被服务端查询</b>，
 *         也不存在"把某个玩家的画面关掉"之外的服务端语义。</li>
 * </ul>
 *
 * <h2>关于"活视图"与快照的分层</h2>
 * 本包承诺：<b>正式查询一律返回当刻定格的不可变结果</b>。
 * 但客户端逐帧渲染路径刻意例外 ——
 * {@code client.state.ClientBlockGlowState#entries()} 每帧都会被渲染器遍历一次
 * （最多 4096 条），若改成逐帧拷贝，等于用真实的帧开销去换一个渲染器自己并不需要的契约纯度。
 * 因此那条路径保留活视图，并在方法注释里明确标注为<b>内部渲染入口、不属于正式 API</b>；
 * 而真正的对外查询（如反伤条目的 {@code DamageReflections#active}）返回不可变快照。
 * <p>
 * 判断标准是"调用频率与是否需要跨刻比较"：低频查询要快照，逐帧渲染要活视图。
 *
 * <h2>刷新与重复触发</h2>
 * 两个效果都可以被高频重复触发（{@code area} 目标下每个方块位置每刻都判定一次，
 * {@code trigger_rate} 默认 1）。因此：
 * <ul>
 *     <li>发光：<b>同键续命</b>，不新增条目；每 {@code BlockGlows.REFRESH_INTERVAL_TICKS}（20）刻
 *         才重发一次；</li>
 *     <li>屏幕视觉：<b>节流</b>到每玩家每类型 {@code ScreenVisionTiming.THROTTLE_TICKS}（5）刻
 *         最多一次，但"延长租期"的请求必然放行（否则会出现"先发 1 刻、再发 100 刻被丢弃"）。</li>
 * </ul>
 * 两者都<b>无需调用方自己去重</b>，直接按每次触发调用即可。
 *
 * <p>All entry points require the server main thread.
 */
public final class Visuals {
    private Visuals() {
        // 门面
    }

    /**
     * 登记一次方块发光（服务端权威、视距内所有玩家可见）。
     * <p>
     * 时长会被实现钳制到 {@code [1, 1200]} 刻（60 秒上限，防止 JSON 写出永不消失的发光）；
     * {@code durationTicks <= 0} 时静默不做任何事。
     * <p>
     * {@code caster} 只用于"按施法者清空"（{@link #clearGlowByCasters}）与
     * "每个施法者各自到期"的记账，<b>不参与渲染判定、也不进网络包</b> ——
     * 同色同显示类型的发光在画面上本就无法区分，合并成一条不产生观感差异。
     *
     * @param caster          施法者 UUID
     * @param position        方块位置
     * @param colorARGB       颜色，ARGB 形态（alpha 已补齐）
     * @param displayType     显示方式
     * @param durationTicks   本次触发给出的时长（游戏刻）
     * @param hideOccluded    是否剔除被完全遮挡的方块；<b>仅对
     *                        {@link GlowDisplayType#SIMPLE_SHADER} 生效</b>（线框恒 12 条棱，不值得预筛）
     */
    public static void glow(final @NotNull ServerLevel level, final @NotNull UUID caster,
                            final @NotNull BlockPos position, final int colorARGB,
                            final @NotNull GlowDisplayType displayType,
                            final int durationTicks, final boolean hideOccluded) {
        BlockGlows.register(level, caster, position, colorARGB, displayType, durationTicks, hideOccluded);
    }

    /**
     * 撤销给定施法者名下的<b>全部</b>方块发光贡献（跨维度）。
     * <p>
     * <b>只丢贡献、不丢整条</b>：某条发光还有别的玩家在贡献时原地保留 ——
     * 这是"可以重叠"语义的必然结果，不会因为清了 A 就把 B 的效果一起抹掉。
     * 整条消失时会立刻给附近玩家补发移除通知，不必等下一次刷新（20 刻）。
     * <p>
     * 之所以"按施法者"而不是"按被影响玩家的画面"：条目在服务端按位置共享，
     * 服务端并不知道某条发光属于谁的画面；而且只清客户端的话，最多 20 刻后就会被重发回来。
     *
     * @param casters 施法者 UUID 集合；<b>调用方保留所有权</b>，本方法只读不改
     * @return 被<b>整条</b>移除的发光条目数（仍有别人贡献的不计入）
     */
    public static int clearGlowByCasters(final @NotNull MinecraftServer server, final @NotNull Set<UUID> casters) {
        return BlockGlows.clearByCasters(server, casters);
    }

    /** {@link #clearGlowByCasters} 的单人重载。 */
    public static int clearGlowByCaster(final @NotNull MinecraftServer server, final @NotNull UUID caster) {
        return BlockGlows.clearByCasters(server, Set.of(caster));
    }

    /** 便捷重载：按玩家对象取施法者集合，返回被整条移除的条目数。 */
    public static int clearGlowByCasters(final @NotNull Collection<ServerPlayer> casters) {
        if (casters.isEmpty()) {
            return 0;
        }

        MinecraftServer server = casters.iterator().next().getServer();

        if (server == null) {
            return 0;
        }

        Set<UUID> uuids = new HashSet<>(casters.size());

        for (ServerPlayer player : casters) {
            uuids.add(player.getUUID());
        }

        return clearGlowByCasters(server, uuids);
    }

    /**
     * 给某个玩家下发一次屏幕视觉（抖动 / 模糊 / 边光）。
     * <p>
     * 参数会被实现就地消化：{@code durationTicks <= 0} 或 {@code amplitude <= 0} 时直接短路；
     * 同一玩家同一类型在节流窗口内、参数没变且客户端余量还够时也会跳过
     * （这不影响"延长租期"的请求，见类注释）。
     *
     * @param visionType    视觉类型
     * @param amplitude     强度；对 {@link ScreenVisionType#EDGE_LIGHT} 即不透明度（&gt;1 由客户端按 1 处理）
     * @param size          边缘厚度，占屏幕<b>短边</b>的比例（0~0.5）；仅 {@code EDGE_LIGHT} 读取
     * @param rgb           遮罩颜色 {@code 0xRRGGBB}；仅 {@code EDGE_LIGHT} 读取
     * @param durationTicks 持续时长（游戏刻）
     */
    public static void screenVision(final @NotNull ServerPlayer player, final @NotNull ScreenVisionType visionType,
                                    final float amplitude, final float size, final int rgb, final int durationTicks) {
        ScreenVisionSender.send(player, visionType, amplitude, size, rgb, durationTicks);
    }

    /**
     * 通知目标玩家清空其<b>全部</b>屏幕视觉状态。
     * <p>
     * 纯通知：服务端不持有副本，因此本方法除了"让客户端立刻清空"之外没有别的语义。
     * 若效果来自<b>被动技能</b>，它会在下一拍重新下发 —— 要让效果彻底消失得停用技能。
     */
    public static void clearScreenVision(final @NotNull ServerPlayer player) {
        by.timeslowly.additional_abilities.common.network.screenvision.ScreenVisionClearPayload.sendTo(player);
    }

    /** 批量版本的 {@link #clearScreenVision}；返回被通知的玩家数。 */
    public static int clearScreenVision(final @NotNull Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            clearScreenVision(player);
        }

        return players.size();
    }
}
