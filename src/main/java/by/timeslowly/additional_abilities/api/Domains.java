package by.timeslowly.additional_abilities.api;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainData;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainInstance;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 领域（{@code additional_abilities:domain} 目标类型留下的持续区域）的<b>只读查询与清理</b>入口。
 *
 * <h2>领域是什么</h2>
 * 施法时在锚点建立的一块"持续存在的区域"，按 {@code apply_interval} 反复结算效果，
 * 到 {@code duration} 到期后消散。它在服务端只是<b>内存里的一个对象</b>，不写存档：
 * 服务端重启即消失（这是有意取舍，见 {@code AAAttachments#DOMAIN} 的说明）。
 *
 * <h2>为什么按"施法者"清理，而不是按"受影响的实体"</h2>
 * 领域对目标施加的效果来自任意 DS 效果类型（药水 / 属性修饰 / 函数…），
 * 没有一份"这个领域给谁加过什么"的通用账本可供撤销。因此"清除领域"的可靠含义只能是
 * <b>移除这些领域本身</b>：此后不再结算，也不会再刷新效果；已经落在目标身上的限时效果
 * 按各自 duration 自然过期。
 * <p>
 * 若技能配了 {@code remove_effects_on_end: true}，清理会走与"自然到期"完全相同的收尾路径，
 * 因此也会对<b>当时仍在范围内</b>的目标执行一次移除（不会因为"中途离开过"而漏掉或误伤）。
 *
 * <h2>跨维度</h2>
 * 领域挂在各维度自己的 {@code ServerLevel} 上。施法者换维度后，旧维度里的领域<b>不会</b>自动消失
 * （只是因"施法者不可用"而跳过结算，计时照走），因此 {@link #clearByCasters} 会遍历全部维度；
 * {@link #of(ServerLevel)} 则只回答"这一个维度里有什么" —— 需要全量请自行对
 * {@link MinecraftServer#getAllLevels()} 逐个调用。
 *
 * <p>All entry points require the server main thread.
 */
public final class Domains {
    private Domains() {
        // 门面
    }

    /**
     * 某个维度里<b>全部存活领域</b>的只读快照。
     * <p>
     * 返回的是当刻定格的 {@link DomainInfo} 列表：调用方拿到的对象此后不会再变，
     * 可以安全地遍历、比较、跨刻保存。代价是它<b>不反映</b>后续变化 —— 要拿最新的需重新调用。
     * <p>
     * 该维度从未建立过领域时返回空列表（<b>不会</b>因此给这个维度创建一份空状态）。
     */
    public static @NotNull List<DomainInfo> of(final @NotNull ServerLevel level) {
        DomainData data = level.getExistingDataOrNull(AAAttachments.DOMAIN);

        // 只读探测而不是 getData：查询一个从没用过领域的维度，不该顺手给它挂上一份空表
        if (data == null) {
            return List.of();
        }

        List<DomainInstance> domains = data.snapshot();
        List<DomainInfo> result = new ArrayList<>(domains.size());

        for (DomainInstance domain : domains) {
            result.add(info(domain));
        }

        return List.copyOf(result);
    }

    /** 某个施法者在<b>该维度</b>留下的存活领域快照。 */
    public static @NotNull List<DomainInfo> of(final @NotNull ServerLevel level, final @NotNull UUID caster) {
        List<DomainInfo> all = of(level);
        List<DomainInfo> result = new ArrayList<>(all.size());

        for (DomainInfo info : all) {
            if (info.casterUuid().equals(caster)) {
                result.add(info);
            }
        }

        return List.copyOf(result);
    }

    /**
     * 某个施法者在<b>该维度 + 该技能</b>下留下的存活领域快照。
     * <p>
     * 领域按「施法者 + 技能」去重成"流"，因此这个组合下通常只有一条
     * （{@code max_domains > 1} 时才可能有几条）。
     */
    public static @NotNull List<DomainInfo> of(final @NotNull ServerLevel level, final @NotNull UUID caster,
                                               final @NotNull ResourceKey<DragonAbility> ability) {
        String key = ability.location().toString();
        List<DomainInfo> result = new ArrayList<>();

        for (DomainInfo info : of(level, caster)) {
            if (info.abilityKey().equals(key)) {
                result.add(info);
            }
        }

        return List.copyOf(result);
    }

    /**
     * 移除给定施法者名下的全部领域，<b>跨维度</b>执行。
     * <p>
     * 走的是与自然到期相同的收尾路径，因此 {@code remove_effects_on_end: true} 的领域
     * 同样会对范围内目标执行一次收尾移除。
     *
     * @param casters 施法者 UUID 集合；<b>调用方保留所有权</b>，本方法只读不改
     * @return 实际被移除的领域数量（跨维度累计）
     */
    public static int clearByCasters(final @NotNull MinecraftServer server, final @NotNull Set<UUID> casters) {
        if (casters.isEmpty()) {
            return 0;
        }

        int removed = 0;

        for (ServerLevel level : server.getAllLevels()) {
            DomainData data = level.getExistingDataOrNull(AAAttachments.DOMAIN);

            if (data == null) {
                continue;
            }

            removed += data.clearByCasters(level, casters);
        }

        return removed;
    }

    /**
     * 移除单个施法者名下的全部领域（{@link #clearByCasters} 的单人重载）。
     *
     * @return 实际被移除的领域数量
     */
    public static int clearByCaster(final @NotNull MinecraftServer server, final @NotNull UUID caster) {
        return clearByCasters(server, Set.of(caster));
    }

    /** 便捷重载：按玩家对象取施法者，并以其所在的 {@link MinecraftServer} 跨维度清理。 */
    public static int clearByCasters(final @NotNull Collection<ServerPlayer> casters) {
        if (casters.isEmpty()) {
            return 0;
        }

        // 同一批玩家必然同属一个服务器；取第一个即可（空集合已在上方排除）
        MinecraftServer server = casters.iterator().next().getServer();

        if (server == null) {
            return 0;
        }

        Set<UUID> uuids = new HashSet<>(casters.size());

        for (ServerPlayer player : casters) {
            uuids.add(player.getUUID());
        }

        return clearByCasters(server, uuids);
    }

    /** 把内部领域对象转成对外值对象。 */
    private static @NotNull DomainInfo info(final @NotNull DomainInstance domain) {
        return new DomainInfo(
                domain.casterUUID(),
                domain.abilityKey().location().toString(),
                domain.followsCaster(),
                domain.anchorPosition(),
                domain.shape().getSerializedName(),
                domain.radius(),
                domain.height(),
                domain.applyInterval(),
                domain.duration(),
                domain.removesEffectsOnEnd(),
                domain.expireTick(),
                domain.nextApplyTick(),
                domain.castStartTick());
    }
}
