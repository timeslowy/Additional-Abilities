package by.timeslowly.additional_abilities.common.ability.targeting.domains;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.timeslowly.additional_abilities.testutil.DomainTestFixtures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T08 的只读查询接缝：{@link DomainData#snapshot()} 与 {@link DomainInstance} 的只读访问器。
 *
 * <h2>为什么这些用例是必要的（而不是"顺手加的 getter"）</h2>
 * 对外查询必须给<b>当刻定格的不可变快照</b>（审查报告 F17）。这条契约有两个容易写错的地方，
 * 而且它们都不会在编译期暴露：
 * <ol>
 *     <li><b>快照 vs 活视图</b>：{@code Collections.unmodifiableList(domains)} 也"不可修改"，
 *         却仍是活视图 —— 调用方遍历时只要有领域到期（{@code DomainData#tick} 每刻都在跑）
 *         就会抛 {@code ConcurrentModificationException}。
 *         因此用例必须区分"不可修改"与"不随后续变化"这两件事；</li>
 *     <li><b>返回值的身份</b>：若 {@code snapshot()} 被写成返回内部列表本身，
 *         调用方不仅能看见后续变化，还能<b>直接改动</b>它。</li>
 * </ol>
 *
 * <h2>覆盖边界</h2>
 * 与 {@code DomainInstanceReentrancyTest} 相同：本类只覆盖<b>状态层</b>，
 * 不碰 {@code ServerLevel} / {@code ServerPlayer}。{@code api.Domains} 的门面方法需要
 * {@code ServerLevel#getExistingDataOrNull}，本机造不出来，因此那一层只有静态证据。
 */
class DomainReadOnlyViewTest {
    private static final long T0 = 1_000L;
    private static final UUID CASTER = DomainTestFixtures.CASTER;

    // ================================================================ snapshot：快照语义

    @Test
    @DisplayName("snapshot 是快照：建好之后加进来的领域不会出现在已取到的那份里")
    void snapshotDoesNotObserveLaterAdditions() {
        DomainData data = new DomainData();
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("snapshot_add");

        data.add(DomainTestFixtures.domain(key, List.of(DomainTestFixtures.blockSet("a")), T0));
        List<DomainInstance> taken = data.snapshot();

        assertEquals(1, taken.size());

        // 快照到手之后再加一条：内部表变成 2，但手上那份必须仍是 1
        data.add(DomainTestFixtures.domain(key, List.of(DomainTestFixtures.blockSet("b")), T0));

        assertEquals(2, data.size(), "内部表应当已有两条");
        assertEquals(1, taken.size(), "已取到的快照不应随后续添加而变化");
    }

    @Test
    @DisplayName("snapshot 是快照：遍历途中移除领域不会抛 ConcurrentModificationException")
    void snapshotSurvivesConcurrentRemoval() {
        DomainData data = new DomainData();
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("snapshot_remove");

        for (int index = 0; index < 5; index++) {
            data.add(DomainTestFixtures.domain(key, List.of(DomainTestFixtures.blockSet("set" + index)), T0));
        }

        List<DomainInstance> taken = data.snapshot();

        // 模拟"取到快照之后，这一帧内有领域到期（tick 每刻都在跑）"。
        // 若 snapshot() 返回的是活视图，下面这个循环会抛 ConcurrentModificationException
        for (DomainInstance domain : taken) {
            data.remove(null, domain);
        }

        assertEquals(0, data.size());
        assertEquals(5, taken.size(), "快照仍应完整保留取到时的 5 条");
    }

    @Test
    @DisplayName("snapshot 不可修改：调用方改不动内部状态")
    void snapshotIsImmutable() {
        DomainData data = new DomainData();
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("snapshot_immutable");
        DomainInstance domain = DomainTestFixtures.domain(key, List.of(DomainTestFixtures.blockSet("a")), T0);

        data.add(domain);
        List<DomainInstance> taken = data.snapshot();

        assertThrows(UnsupportedOperationException.class, () -> taken.add(domain));
        assertThrows(UnsupportedOperationException.class, taken::clear);
        assertThrows(UnsupportedOperationException.class, () -> taken.remove(0));

        assertEquals(1, data.size(), "内部表不该被上面的失败操作影响");
    }

    @Test
    @DisplayName("空表也返回不可变结果，且不需要分配新表每次")
    void emptySnapshotIsUsable() {
        DomainData data = new DomainData();
        List<DomainInstance> taken = data.snapshot();

        assertTrue(taken.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> taken.add(null));
    }

    // ================================================================ DomainInstance 只读访问器

    @Test
    @DisplayName("只读访问器如实反映建立时固化的几何与计时参数")
    void readOnlyAccessorsReflectFrozenParameters() {
        ResourceKey<DragonAbility> key = DomainTestFixtures.abilityKey("readonly_params");
        DomainInstance domain = DomainTestFixtures.domain(key, List.of(DomainTestFixtures.blockSet("a")), T0);

        // 夹具固定值（见 DomainTestFixtures#domain）：球形、施法位置锚点、半径/高度 4、间隔/时长取自夹具常量
        assertEquals(DomainShape.SPHERE, domain.shape());
        assertEquals(4.0, domain.radius());
        assertEquals(4.0, domain.height());
        assertEquals(DomainTestFixtures.APPLY_INTERVAL, domain.applyInterval());
        assertEquals(DomainTestFixtures.DURATION, domain.duration());
        assertFalse(domain.removesEffectsOnEnd(), "夹具用的是 removeEffectsOnEnd = false");
        assertEquals(Vec3.ZERO, domain.anchorPosition());
        assertEquals(key, domain.abilityKey());
        assertEquals(CASTER, domain.casterUUID());
    }

    @Test
    @DisplayName("followsCaster 与 anchorPosition 的分工：CASTING_POSITION 锚点不跟随")
    void castingPositionAnchorDoesNotFollow() {
        DomainInstance domain = DomainTestFixtures.domain(
                DomainTestFixtures.abilityKey("readonly_anchor"),
                List.of(DomainTestFixtures.blockSet("a")), T0);

        // 夹具用 DomainAnchor.CASTING_POSITION —— 锚点在建立时固化，这正是"一次施法 = 一份固化快照"
        assertFalse(domain.followsCaster());
        assertEquals(Vec3.ZERO, domain.anchorPosition());
    }

    @Test
    @DisplayName("到期 / 结算时刻如实反映构造期与 advance 的推进")
    void tickAccessorsTrackState() {
        DomainInstance domain = DomainTestFixtures.domain(
                DomainTestFixtures.abilityKey("readonly_ticks"),
                List.of(DomainTestFixtures.blockSet("a")), T0);

        assertEquals(T0 + DomainTestFixtures.DURATION, domain.expireTick());
        assertEquals(T0 + DomainTestFixtures.APPLY_INTERVAL, domain.nextApplyTick());

        domain.advance(T0 + 7);

        assertEquals(T0 + 7 + DomainTestFixtures.APPLY_INTERVAL, domain.nextApplyTick(),
                "advance 应当把下一次结算时刻推后一个间隔");
        assertEquals(T0 + DomainTestFixtures.DURATION, domain.expireTick(),
                "advance 只动结算节拍，不该影响到期时刻（两级节流互不干扰）");
    }

    @Test
    @DisplayName("anchorPosition 交出的是不可变值对象：可以安全地长期持有")
    void anchorPositionIsImmutableValue() {
        DomainInstance domain = DomainTestFixtures.domain(
                DomainTestFixtures.abilityKey("readonly_vec"),
                List.of(DomainTestFixtures.blockSet("a")), T0);

        Vec3 first = domain.anchorPosition();
        Vec3 second = domain.anchorPosition();

        // Vec3 的字段全是 final 且没有任何 setter，因此"交出去"这件事本身不构成可变状态泄漏 ——
        // 这正是它可以直接返回、不必像集合那样复制的原因。
        // 夹具用的是 Vec3.ZERO（常量），故两次取回是同一个引用
        assertSame(first, second, "Vec3 不可变，重复取回同一个常量引用是预期行为");
        assertEquals(Vec3.ZERO, first);
    }

    @Test
    @DisplayName("对外值对象 DomainInfo 不含任何写方法（只读保证落在它身上，而不是内部活对象上）")
    void domainInfoExposesNoMutators() {
        // 划分很重要：DomainInstance 是<b>内部活对象</b>，它本来就有 join / advance / markRemoved 等写方法
        // （DomainData 每刻都在推它）。对外契约的只读保证不是靠"把活对象藏起来"实现的，
        // 而是靠 api.Domains 只交出定格的值对象 —— 因此要断言"无写方法"的是 DomainInfo。
        for (java.lang.reflect.Method method : by.timeslowly.additional_abilities.api.DomainInfo.class.getMethods()) {
            String name = method.getName();

            assertFalse(name.startsWith("set") || name.startsWith("add") || name.startsWith("clear")
                            || name.equals("join") || name.equals("advance") || name.equals("applyNow")
                            || name.equals("applyGroupNow") || name.equals("markRemoved") || name.equals("end"),
                    "DomainInfo 的公开面不应出现写方法：" + name);
        }

        // 反面确认：内部活对象<b>确实</b>带着写方法，否则上面那条断言是废话（说明它本来就不需要封装）
        boolean internalHasWriters = false;

        for (java.lang.reflect.Method method : DomainInstance.class.getMethods()) {
            if (method.getName().equals("join")) {
                internalHasWriters = true;
                break;
            }
        }

        assertTrue(internalHasWriters, "DomainInstance 应当仍是带写方法的内部活对象（这是它被 DomainInfo 包起来的原因）");
    }
}
