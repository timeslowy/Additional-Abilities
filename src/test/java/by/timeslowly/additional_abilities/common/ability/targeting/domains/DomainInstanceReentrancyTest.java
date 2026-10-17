package by.timeslowly.additional_abilities.common.ability.targeting.domains;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.AbilityTargeting;
import by.timeslowly.additional_abilities.testutil.DomainTestFixtures;
import com.mojang.datafixers.util.Either;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T04 的回归测试：领域新组首轮执行（F14）+ 回调重入保护。
 * <p>
 * 本类位于 {@code domains} 包内，因为 {@link DomainData#tick(long, ServerLevel)} 与
 * {@link DomainInstance#forEachEffectSet} 是包级可见的可测试接缝（见各自注释）。
 *
 * <h2>覆盖边界（重要，勿误读为"T04 已完全验证"）</h2>
 * 领域有两层：
 * <ol>
 *     <li><b>状态机</b> —— 哪些组被登记、被移除后是否还结算、收尾是否幂等、到期是否只移除一次；</li>
 *     <li><b>效果落地</b> —— 真正遍历方块 / 实体并调用 {@code AbilityBlockEffect#apply}。</li>
 * </ol>
 * 第 2 层需要一个真实的 {@code ServerPlayer} + {@code ServerLevel}（效果形参就是
 * {@code ServerPlayer}，战利品条件上下文还要读 {@code dragon.level()}）。
 * 本机<b>无外网、缓存中也没有 Mockito</b>（只有 JUnit 5.9.2），造不出这两个对象。
 * <p>
 * 因此本类<b>只</b>断言第 1 层，统一以 {@code null} 关卡调用
 * （{@code resolveCaster} 在"无可用世界"时短路）。
 * <p>
 * <b>由此产生的一条写作纪律</b>：凡是以"效果有没有被调用"为判据的断言，在 {@code null} 关卡下
 * <b>恒成立</b>，属于空测试 —— 本文档早先版本就写过两条这样的用例，
 * 在把 {@code DomainData} 的 {@code isRemoved} 复查整段删掉后它们<b>依然全绿</b>，
 * 直到改用 {@code advance()} 的副作用（{@code nextApplyTick} 是否被推进）才真正抓到回归。
 * 新增用例请一律寻找不依赖 {@code ServerLevel} 的可观测差异（状态字段、计数器、返回值）。
 * <p>
 * 结论：<b>效果是否真的落到方块 / 实体上，仍需游戏内验收</b>（报告 §9.1）。
 */
class DomainInstanceReentrancyTest {

    /** 夹具固定的起始时刻。 */
    private static final long T0 = 1_000L;

    /** 夹具固定的结算间隔 / 时长，从夹具常量取，避免两处各写一份而漂移。 */
    private static final int INTERVAL = DomainTestFixtures.APPLY_INTERVAL;
    private static final int DURATION = DomainTestFixtures.DURATION;

    // ================================================================ F14：新组识别

    @Test
    @DisplayName("F14：join 对同一组只报告一次'新增'，续帧一律报告 false")
    void joinReportsNewOnlyOnce() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("f14_signal");
        Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> set =
                DomainTestFixtures.blockSet("x");

        DomainInstance domain = DomainTestFixtures.domain(key, List.of(set), T0);

        // 同一份效果集（内容相等）——正是 channeled 每 trigger_rate tick 重复并入的那一份
        assertFalse(domain.join(T0 + 1, set), "已存在的组：必须是 false（否则续帧会变成高频重放）");
        assertFalse(domain.join(T0 + 2, set), "第 2 次续帧：仍是 false");
        assertFalse(domain.join(T0 + 3, set), "第 3 次续帧：仍是 false");

        // 内容不同的组才算新增
        assertTrue(domain.join(T0 + 4, DomainTestFixtures.blockSet("y")), "内容不同的组：必须是 true");
    }

    @Test
    @DisplayName("F14：复刻 DomainTarget 的调用形状 —— 反复并入同一组不触发任何首轮执行")
    void joinReturnValueDrivesFirstRun() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("f14_contract");
        Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> set =
                DomainTestFixtures.blockSet("x");

        DomainInstance domain = DomainTestFixtures.domain(key, List.of(set), T0);

        // 即 DomainTarget#apply 里的：
        //   if (latest.join(now, target)) { latest.applyGroupNow(level, target); }
        int firstRuns = 0;

        for (int tick = 1; tick <= 4; tick++) {
            if (domain.join(T0 + tick, set)) {
                firstRuns++;
            }
        }

        assertEquals(0, firstRuns, "反复并入同一组不得触发任何首轮执行");
    }

    @Test
    @DisplayName("F14：join 续命 —— 到期时刻被推后（'引导期间维持领域'的实现方式）")
    void joinExtendsExpiry() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("f14_extend");
        Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> set =
                DomainTestFixtures.blockSet("x");

        DomainInstance domain = DomainTestFixtures.domain(key, List.of(set), T0);

        // expireTick = T0 + DURATION
        assertFalse(domain.isExpired(T0 + DURATION - 1), "到期前一刻不应算过期");
        assertTrue(domain.isExpired(T0 + DURATION), "恰好到期即算过期（now >= expireTick）");

        // 在 T0+1 续命 → expireTick 推到 T0+1+DURATION
        domain.join(T0 + 1, set);
        assertFalse(domain.isExpired(T0 + DURATION), "续命后到期时刻应被推后 1 tick");
        assertTrue(domain.isExpired(T0 + DURATION + 1), "推后恰好 1 tick");
    }

    // ================================================================ 重入：遍历快照

    @Test
    @DisplayName("重入：遍历效果集用的是快照 —— 回调中并入新组不会抛 ConcurrentModificationException")
    void forEachEffectSetIteratesSnapshotWithoutCme() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("reentry_snapshot");
        DomainInstance domain = DomainTestFixtures.domain(key, List.of(DomainTestFixtures.blockSet("first")), T0);

        List<Integer> visited = new ArrayList<>();

        // 每次访问都往 effectSets 追加新组：若遍历的是活列表，这里会抛 ConcurrentModificationException
        domain.forEachEffectSet(set -> {
            visited.add(visited.size());
            domain.join(T0 + visited.size(), DomainTestFixtures.blockSet("added-" + visited.size()));
        });

        assertEquals(1, visited.size(),
                "快照只应访问建立时已有的 1 组；新增组留给 applyGroupNow 单独跑首轮（保证'恰好一次'）");
    }

    @Test
    @DisplayName("重入：回调中并入多个新组也不会破坏本次遍历（多组快照）")
    void snapshotSurvivesMultipleJoins() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("reentry_multi");
        DomainInstance domain = DomainTestFixtures.domain(key,
                List.of(DomainTestFixtures.blockSet("a"), DomainTestFixtures.blockSet("b")), T0);

        int[] visited = {0};

        domain.forEachEffectSet(set -> {
            visited[0]++;
            domain.join(T0 + visited[0], DomainTestFixtures.blockSet("new-" + visited[0]));
            domain.join(T0 + visited[0] + 1000, DomainTestFixtures.blockSet("newer-" + visited[0]));
        });

        assertEquals(2, visited[0], "两次访问对应建立时已有的 2 组，与回调中的追加无关");
    }

    // ================================================================ 重入：移除状态

    @Test
    @DisplayName("重入：markRemoved 置位后 isRemoved 保持为真（tick 与收尾都依赖这一标志）")
    void markRemovedIsSticky() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("reentry_flag");

        DomainInstance domain = DomainTestFixtures.domain(key, List.of(), T0);
        assertFalse(domain.isRemoved(), "新建领域不应处于已移除状态");

        domain.markRemoved();
        assertTrue(domain.isRemoved(), "markRemoved 后必须为真");

        domain.markRemoved();   // remove 路径可能被重入
        assertTrue(domain.isRemoved(), "重复置位不改变结果");
    }

    @Test
    @DisplayName("重入：已移除的领域接受后续调用而不抛异常，且状态不被破坏")
    void removedDomainAcceptsCallsSafely() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("reentry_safe");

        DomainInstance domain = DomainTestFixtures.domain(key, List.of(DomainTestFixtures.blockSet("never")), T0);
        domain.markRemoved();

        // 三个入口都必须在"无可用世界"时安全返回。
        // 注意：这里<b>不能</b>断言"效果没被调用" —— null 关卡下 resolveCaster 本就短路，
        // 那种断言无论守卫在不在都成立（空测试）。
        domain.applyNow(null);
        domain.applyGroupNow(null, DomainTestFixtures.blockSet("also-never"));
        domain.end(null);

        assertTrue(domain.isRemoved(), "上述调用不得清除 removed 标志");
    }

    @Test
    @DisplayName("重入：end 幂等 —— 重复收尾不改变领域的有效性状态；end 与 removed 语义独立")
    void endIsIdempotentAndDistinctFromRemoved() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("reentry_end");

        // removeEffectsOnEnd = true 才真正进入收尾分支
        DomainInstance withRemoval = new DomainInstance(DomainTestFixtures.CASTER,
                DomainTestFixtures.abilityInstance(key, 1), 1,
                DomainTestFixtures.blockSet("e"),
                DomainShape.SPHERE, DomainAnchor.CASTING_POSITION,
                4.0, 4.0, false, INTERVAL, DURATION, true, Vec3.ZERO, T0, T0);

        withRemoval.end(null);
        withRemoval.end(null);   // 重入：幂等闸门应让它直接返回

        assertFalse(withRemoval.isRemoved(),
                "end 不得置位 removed —— 两者语义不同：removed = 已撤出（不得再结算），ended = 收尾已跑过");
    }

    // ================================================================ DomainData：结算顺序与移除

    @Test
    @DisplayName("重入：tick 整条跳过已移除的领域（用 advance 副作用观测，不依赖施法者）")
    void tickSkipsRemovedDomains() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("data_skip_removed");
        DomainData data = new DomainData();

        long dueAt = T0 + INTERVAL;   // 恰好到结算点

        DomainInstance live = DomainTestFixtures.domain(key, List.of(), T0);
        DomainInstance removed = DomainTestFixtures.domain(key, List.of(), T0);

        data.add(live);
        data.add(removed);

        assertTrue(removed.isDue(dueAt), "前置条件：该领域此刻确实到结算点了");
        removed.markRemoved();

        data.tick(dueAt, null);

        // 关键断言：isRemoved 复查生效时，被移除的领域连 advance() 都不会执行。
        // 若删掉那道 continue，isDue 会先命中并把 nextApplyTick 推后，于是下面的断言失败。
        // 这是本用例唯一不依赖 ServerLevel 的可观测差异 —— 不能靠"效果有没有被调用"来判断。
        assertTrue(removed.isDue(dueAt),
                "已移除的领域必须被整条跳过：不得推进 nextApplyTick");

        // 对照组：未移除的那个必须被正常推进
        assertFalse(live.isDue(dueAt),
                "未移除且在结算点的领域应被推进 nextApplyTick");
    }

    @Test
    @DisplayName("DomainData：到期的领域被移出并置位 removed，重复 tick 无负面影响")
    void tickRemovesExpiredExactlyOnce() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("data_expire");
        DomainData data = new DomainData();

        DomainInstance domain = DomainTestFixtures.domain(key, List.of(DomainTestFixtures.blockSet("expiring")), T0);
        data.add(domain);

        data.tick(T0 + DURATION + 1, null);

        assertEquals(0, data.size(), "到期领域应被移出");
        assertTrue(domain.isRemoved(), "移除时须置位 removed（先 markRemoved 再 end）");

        data.tick(T0 + DURATION + 2, null);
        assertEquals(0, data.size(), "重复 tick 不应产生负面影响");
    }

    @Test
    @DisplayName("DomainData：未到期的领域不会被移出")
    void tickKeepsLiveDomains() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("data_live");
        DomainData data = new DomainData();

        DomainInstance domain = DomainTestFixtures.domain(key, List.of(), T0);
        data.add(domain);

        data.tick(T0 + DURATION - 1, null);

        assertEquals(1, data.size(), "未到期不应被移出");
        assertFalse(domain.isRemoved());
    }

    @Test
    @DisplayName("DomainData：remove 幂等 —— 同一领域被移除两次只收尾一次")
    void removeIsIdempotent() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("data_remove_idem");
        DomainData data = new DomainData();

        DomainInstance domain = DomainTestFixtures.domain(key, List.of(), T0);
        data.add(domain);

        data.remove(null, domain);
        data.remove(null, domain);   // 第二次：domains.remove 返回 false，应直接返回

        assertEquals(0, data.size());
        assertTrue(domain.isRemoved());
    }

    @Test
    @DisplayName("DomainData：clearByCasters 走同一条收尾路径，并报告实际移除数")
    void clearByCastersRemovesAndReports() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("data_clear");
        DomainData data = new DomainData();

        DomainInstance domain = DomainTestFixtures.domain(key, List.of(), T0);
        data.add(domain);

        assertEquals(1, data.clearByCasters(null, Set.of(DomainTestFixtures.CASTER)),
                "应报告移除了 1 个领域");
        assertEquals(0, data.size());
        assertTrue(domain.isRemoved(), "clear 应走与到期相同的收尾路径");

        assertEquals(0, data.clearByCasters(null, Set.of(DomainTestFixtures.CASTER)),
                "幂等：再清一次不应重复计数");
    }

    @Test
    @DisplayName("DomainData：clearByCasters 按施法者过滤，不误删他人领域")
    void clearByCastersFiltersByCaster() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("data_clear_filter");
        DomainData data = new DomainData();

        data.add(DomainTestFixtures.domain(key, List.of(), T0));

        assertEquals(0, data.clearByCasters(null,
                        Set.of(UUID.fromString("00000000-0000-0000-0000-0000000000bb"))),
                "不属于该施法者的领域不得被清除");
        assertEquals(1, data.size(), "领域应仍在");
    }

    @Test
    @DisplayName("DomainData：latest / count 按'施法者 + 技能'匹配，latest 取最新一条")
    void latestAndCountMatchByCasterAndKey() {
        ResourceKey<DragonAbility> keyA = DomainTestFixtures.abilityKey("data_key_a");
        ResourceKey<DragonAbility> keyB = DomainTestFixtures.abilityKey("data_key_b");
        DomainData data = new DomainData();

        DomainInstance older = DomainTestFixtures.domain(keyA, List.of(), T0);
        DomainInstance newer = DomainTestFixtures.domain(keyA, List.of(), T0 + 5);
        DomainInstance other = DomainTestFixtures.domain(keyB, List.of(), T0 + 9);

        data.add(older);
        data.add(newer);
        data.add(other);

        assertEquals(2, data.count(DomainTestFixtures.CASTER, keyA), "同一技能应有 2 条");
        assertEquals(1, data.count(DomainTestFixtures.CASTER, keyB), "另一技能独立计数");

        DomainInstance latest = data.latest(DomainTestFixtures.CASTER, keyA);
        assertNotSame(older, latest, "latest 必须取最近创建的那条（引导续帧判定依赖它）");
        assertEquals(newer.castStartTick(), latest.castStartTick());
    }

    @Test
    @DisplayName("DomainInstance：matches 只在施法者与技能都相同时为真")
    void matchesRequiresBothCasterAndKey() {
        ResourceKey<DragonAbility> keyA = DomainTestFixtures.abilityKey("match_a");
        ResourceKey<DragonAbility> keyB = DomainTestFixtures.abilityKey("match_b");

        DomainInstance domain = DomainTestFixtures.domain(keyA, List.of(), T0);

        assertTrue(domain.matches(DomainTestFixtures.CASTER, keyA), "同施法者同技能：应匹配");
        assertFalse(domain.matches(DomainTestFixtures.CASTER, keyB), "技能不同：不得匹配");
        assertFalse(domain.matches(UUID.randomUUID(), keyA), "施法者不同：不得匹配");
    }
}
