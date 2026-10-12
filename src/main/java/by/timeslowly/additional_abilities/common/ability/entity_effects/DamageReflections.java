package by.timeslowly.additional_abilities.common.ability.entity_effects;

import by.timeslowly.additional_abilities.registry.AAAttachments;
import by.timeslowly.additional_abilities.registry.dragon.ability.entity_effects.DamageReflectionEffect;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 伤害反弹附件数据（仿 DS 的 OnAttackEffects 附件模式）。
 * <p>
 * 以技能实例 ID 为键保存各技能提供的反弹参数；由施法期间/被动技能周期性调用
 * {@link DamageReflectionEffect#apply} 刷新，因此无需序列化
 * （技能等级变化时也会随下一次刷新自动更新数值）。
 * <p>
 * 每条目记录写入时的游戏刻并设有有效期（{@link #REFLECTION_TTL_TICKS}）：
 * 主动技能施法结束、被动技能条件不满足后不再刷新，条目到期即失效，
 * 从而不依赖 DS 的 remove 回调（其仅对被动常驻 self 技能可靠）。
 */
public class DamageReflections {
    /**
     * 条目有效期（游戏刻，1 秒 = 20）。需大于技能配置的 trigger_rate 节拍间隔，
     * 否则刷新间隙条目会短暂失效导致反伤闪烁；技能结束后反伤最多残留该时长。
     */
    public static final long REFLECTION_TTL_TICKS = 100;

    private final Map<String, Entry> entries = new HashMap<>();

    /** 单个技能提供的反弹参数（均已按当前技能等级计算并钳制为非负值），appliedTick 为写入时的游戏刻 */
    public record Entry(float percentage, int range, boolean useSameDamageType, long appliedTick) {}

    public static @NotNull DamageReflections getData(final @NotNull Entity entity) {
        return entity.getData(AAAttachments.DAMAGE_REFLECTION);
    }

    public void set(final String abilityId, final Entry entry) {
        entries.put(abilityId, entry);
    }

    public void remove(final String abilityId) {
        entries.remove(abilityId);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 返回当前<b>未过期</b>条目的只读快照，并顺带清理已过期条目（技能结束后附件内存不会残留）。
     * <p>
     * <b>为什么返回快照而不是 {@code entries.values()}</b>（审查报告 F17）：后者是底层
     * {@link HashMap} 的<b>活视图</b>，调用方拿到的集合既可以被 {@code remove} 改写本附件的内部状态，
     * 也会在遍历途中因别处调用 {@link #set} / {@link #remove} 而抛
     * {@code ConcurrentModificationException}。虽然当前唯一调用方（伤害反弹事件）是单线程顺序访问，
     * 但那是<b>实现的巧合</b>而非契约 —— 对外查询必须给出一份"当刻定格、不可修改"的结果。
     * <p>
     * 代价是每次调用多一次 {@link List#copyOf}：本方法只在"实体确实受了伤害且附件非空"时才被调用，
     * 且条目数等于该实体身上的反伤技能数（通常 1~2），不在逐帧路径上。
     *
     * @param nowTick 当前游戏刻（{@code level().getGameTime()})
     */
    public @NotNull List<Entry> active(final long nowTick) {
        entries.entrySet().removeIf(entry -> nowTick - entry.getValue().appliedTick() > REFLECTION_TTL_TICKS);

        // 空表是最常见的情形（没有反伤技能时根本不会走到这里，但被动刷新间隙会），单独短路省一次分配
        return entries.isEmpty() ? List.of() : List.copyOf(entries.values());
    }
}
