package by.timeslowly.additional_abilities.common.ability.targeting.domains;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
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
     * （经 {@code run_function} 这类效果）间接触发新施法、甚至把后续领域整个清掉的
     * 可能；用快照避免 {@code ConcurrentModificationException}。
     * <p>
     * <b>但快照只挡并发修改异常，不等于"安全的结算顺序"</b>：快照里排在后面的领域若已被
     * 前面的回调移除，它依然会被本次循环访问到。因此这里在结算前复查
     * {@link DomainInstance#isRemoved()} —— 已被移除的领域<b>不再产生任何结算</b>。
     */
    public void tick(final ServerLevel level) {
        tick(level.getGameTime(), level);
    }

    /**
     * {@link #tick(ServerLevel)} 的实现体，但把"当前时刻"作为显式参数。
     * <p>
     * 抽出来的唯一目的是<b>可测试性</b>：结算顺序与重入保护（{@code isRemoved} 复查、到期收集）
     * 全都只依赖 {@code now} 与列表本身，与 {@code ServerLevel} 无关。把时间显式传进来之后，
     * 这些逻辑就能在不起服务端的前提下直接断言。生产路径行为完全一致
     * （{@link #tick(ServerLevel)} 只是把 {@code level.getGameTime()} 传进来）。
     *
     * @param now   当前游戏刻
     * @param level 领域所在维度；调用方需保证只在会真正执行效果的领域上依赖它非空
     */
    void tick(final long now, final ServerLevel level) {
        if (domains.isEmpty()) {
            return;
        }

        List<DomainInstance> finished = null;

        for (DomainInstance domain : new ArrayList<>(domains)) {
            // 已被移除（可能是本次循环中靠前的回调干的）：跳过，不结算、不重复收尾
            if (domain.isRemoved()) {
                continue;
            }

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

    /**
     * 本维度当前存活领域的<b>不可变快照</b>。
     * <p>
     * 与 {@link #size()} / {@link #count(UUID, ResourceKey)} 只回答"有几个"不同，本方法交出领域对象本身，
     * 供只读查询使用（例如第三方想列出某个施法者的领域）。用 {@link List#copyOf} 而不是
     * {@code Collections.unmodifiableList(domains)}：后者仍是<b>活视图</b>，
     * 调用方遍历途中只要有领域到期（{@code DomainData#tick} 每刻都在跑）就会抛
     * {@code ConcurrentModificationException}；快照则把"拿到手的那一刻"固定下来。
     * <p>
     * 读取路径返回快照属于对外契约的一部分（见 {@code api} 包的说明），
     * 而内部的 {@code tick} / {@code clearByCasters} 仍各自就地建快照，不走这里
     * —— 它们需要的是"当下最省的一次拷贝"，与对外语义无关。
     */
    public @NotNull List<DomainInstance> snapshot() {
        return List.copyOf(domains);
    }

    /**
     * 移除并执行收尾（到期 / 顶替 / 调试清空共用同一条收尾路径）。
     * <p>
     * <b>先 {@code markRemoved} 再 {@code end}</b>：收尾会执行任意技能效果，那些效果可能
     * 反过来触发新的施法或清空，从而重入到本类。先把"已撤"标志立起来，重入路径就能立刻看到
     * 本领域已失效，而不是让它继续结算一次。配合 {@link DomainInstance#end} 内部的幂等闸门，
     * 同一次收尾也不会被执行两遍。
     */
    public void remove(final ServerLevel level, final DomainInstance domain) {
        if (!domains.remove(domain)) {
            return;
        }

        domain.markRemoved();
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
