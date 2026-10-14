package by.timeslowly.additional_abilities.api;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 一个存活领域的<b>只读快照</b>（值对象，不含任何可变状态）。
 *
 * <h2>为什么不是直接把内部领域对象交出去</h2>
 * 内部的 {@code DomainInstance} 是<b>活对象</b>：它带着 {@code join} / {@code advance} /
 * {@code markRemoved} 这些改状态的方法，而且 {@code DomainData} 每刻都在推它的
 * {@code expireTick} / {@code nextApplyTick}。把它直接返回等于让调用方拿到一只"可以改、
 * 而且会自己变"的对象 —— 既可以越权续命 / 提前催结算，也无法安全地跨刻比较。
 * 因此对外只给这份<b>当刻定格</b>的值对象：正式查询一律返回只读快照。
 *
 * <h2>坐标与时间都是"维度自己的轴"</h2>
 * {@code anchor} 与两个 tick 字段都取自<b>该领域所在维度</b>：
 * {@code expireTick} / {@code nextApplyTick} 与该维度的 {@code level.getGameTime()} 同轴；
 * {@code anchor} 是该维度的绝对坐标。跨维度比较没有意义。
 *
 * @param casterUuid     施法者 UUID
 * @param abilityKey     施法所用技能的注册键字符串，例如 {@code "additional_abilities:test_domain"}
 * @param followsCaster  锚点是否跟随施法者移动；为 {@code true} 时 {@code anchor} 不具参考意义
 * @param anchor         建立时固化的锚点坐标（{@code followsCaster} 为 {@code false} 时有意义）
 * @param shape          形状的序列化名（{@code sphere} / {@code cube} / {@code cylinder}）
 * @param radius         半径（格）
 * @param height         柱形高度（格）；球形 / 方形下等于 {@code radius}
 * @param applyInterval  结算间隔（游戏刻）
 * @param duration       单次续命给出的存活时长（游戏刻）
 * @param removesOnEnd   消散时是否对当时范围内的目标执行收尾移除（{@code remove_effects_on_end}）
 * @param expireTick     到期时刻（所在维度的 {@code getGameTime()} 轴）
 * @param nextApplyTick  下一次结算时刻（同一轴）
 * @param castStartTick  本次施法的起点世界刻，即该领域的"流身份"
 *
 * <p>This snapshot is immutable: it never observes later changes to the underlying domain.
 * It carries no mutable state, so it is safe to keep across ticks or hand to another thread.
 */
public record DomainInfo(@NotNull UUID casterUuid,
                         @NotNull String abilityKey,
                         boolean followsCaster,
                         @NotNull Vec3 anchor,
                         @NotNull String shape,
                         double radius,
                         double height,
                         int applyInterval,
                         int duration,
                         boolean removesOnEnd,
                         long expireTick,
                         long nextApplyTick,
                         long castStartTick) {
}
