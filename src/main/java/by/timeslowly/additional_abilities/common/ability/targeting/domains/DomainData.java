package by.timeslowly.additional_abilities.common.ability.targeting.domains;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 一个维度内的<b>全部存活领域</b>，承载在 {@code Level} 的数据附件上
 * （{@code AAAttachments.DOMAIN}），由 {@code DomainTickHandler} 挂在
 * {@code LevelTickEvent.Post} 上逐 tick 推进。
 *
 * <h2>为什么挂在 Level 附件上，而不是静态 Map 或实体</h2>
 * <ul>
 *     <li><b>vs 静态 Map</b>：{@code Level extends AttachmentHolder}（NeoForge 对原版 Level 的补丁，
 *         已从 {@code neoforge-21.1.250-sources.jar} 核实），附件随 Level 实例一起回收，
 *         不需要额外挂 {@code ServerStoppedEvent} 清理，也不会有跨存档串味。</li>
 *     <li><b>vs 标记实体</b>：实体方案要把 {@code applied_effects} 编解码进 NBT，
 *         而它内部是 {@code ConditionalOps.decodeListWithElementConditions(...)} 包出来的列表，
 *         跨 DS 版本极易因字段变动而解析失败；而且会把 DS 的效果配置固化进存档。
 *         本组件因此<b>刻意选择纯内存、不持久化</b>——领域生命周期只有几十秒到几分钟，
 *         服务端重启即消失是可接受的代价。</li>
 * </ul>
 *
 * <h2>去重键与"顶替"</h2>
 * 同施法者 + 同技能视为同一「领域流」。建立新领域时按 {@code max_domains} 上限
 * 顶掉<b>最旧</b>的一个；判定"是新一次施法还是引导续帧"时取<b>最新</b>的一个
 * （同一技能不可能同时存在两条活跃施法流，所以最新的那个就是当前流的载体）。
 */
public class DomainData {
    private final List<DomainInstance> domains = new ArrayList<>();

    /**
     * 逐 tick 推进本维度的所有领域。
     * <p>
     * 遍历的是快照：{@link DomainInstance#applyNow} 会执行任意技能效果，理论上存在
     * （经 {@code run_function} 这类效果）间接触发新施法的可能；用快照避免
     * {@code ConcurrentModificationException}。
     */
    public void tick(final ServerLevel level) {
        if (domains.isEmpty()) {
            return;
        }

        long now = level.getGameTime();
        List<DomainInstance> finished = null;

        for (DomainInstance domain : new ArrayList<>(domains)) {
            if (domain.isExpired(now)) {
                if (finished == null) {
                    finished = new ArrayList<>();
                }

                finished.add(domain);
                continue;
            }

            if (domain.isDue(now)) {
                // 先推后结算时刻，再执行效果：万一同 tick 内被再次触发也不会重复结算
                domain.advance(now);
                domain.applyNow(level);
            }
        }

        if (finished != null) {
            for (DomainInstance domain : finished) {
                remove(level, domain);
            }
        }
    }

    /**
     * 最近创建的同键领域（同施法者 + 同技能）；无则返回 {@code null}。
     * <p>
     * 调用方用它判定"本条 {@code apply} 是引导续帧还是新一次施法"。
     */
    public @Nullable DomainInstance latest(final UUID caster, final ResourceKey<DragonAbility> key) {
        for (int index = domains.size() - 1; index >= 0; index--) {
            DomainInstance domain = domains.get(index);

            if (domain.matches(caster, key)) {
                return domain;
            }
        }

        return null;
    }

    public int count(final UUID caster, final ResourceKey<DragonAbility> key) {
        int amount = 0;

        for (DomainInstance domain : domains) {
            if (domain.matches(caster, key)) {
                amount++;
            }
        }

        return amount;
    }

    public void add(final DomainInstance domain) {
        domains.add(domain);
    }

    /** 移除并执行收尾（到期 / 顶替共用同一条收尾路径）。 */
    public void remove(final ServerLevel level, final DomainInstance domain) {
        if (!domains.remove(domain)) {
            return;
        }

        domain.end(level);
    }

    /**
     * 顶掉最旧的一个同键领域（列表保持创建顺序，故从头遍历即最旧优先）。
     *
     * @return 是否真的移除了一个
     */
    public boolean evictOldest(final ServerLevel level, final UUID caster, final ResourceKey<DragonAbility> key) {
        for (DomainInstance domain : domains) {
            if (domain.matches(caster, key)) {
                remove(level, domain);
                return true;
            }
        }

        return false;
    }

    /** 仅用于调试 / 查询指令：本维度当前存活领域数。 */
    public int size() {
        return domains.size();
    }

    /**
     * 移除本维度中所有由给定施法者建立的领域（走与自然到期相同的收尾路径）。
     * <p>
     * 供调试指令 {@code /additional-abilities domain clear <targets>} 使用。
     * 由于领域按维度存在，指令侧需要遍历所有维度逐个调用本方法
     * （施法者换维度后旧维度里的领域并不会自动消失）。
     *
     * @param casters 施法者 UUID 集合；为空直接返回
     * @return 实际移除的数量（用于指令反馈与 {@code /execute store result}）
     */
    public int clearByCasters(final ServerLevel level, final Set<UUID> casters) {
        if (domains.isEmpty() || casters.isEmpty()) {
            return 0;
        }

        int removed = 0;

        // 遍历快照：remove() 内部会调用 end()，而收尾会执行任意技能效果（可能反过来触发新施法）
        for (DomainInstance domain : new ArrayList<>(domains)) {
            if (casters.contains(domain.casterUUID())) {
                remove(level, domain);
                removed++;
            }
        }

        return removed;
    }
}
