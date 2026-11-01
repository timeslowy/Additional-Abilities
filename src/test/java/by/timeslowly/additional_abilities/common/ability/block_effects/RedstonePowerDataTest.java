package by.timeslowly.additional_abilities.common.ability.block_effects;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedstonePowerData} 的纯单元测试：键结构、最大强度聚合、逐笔到期与边沿通知。
 *
 * <h2>为什么要同包</h2>
 * {@code register} / {@code expire} / {@code entryCount} 都是包级可见（只服务于
 * {@code VirtualRedstonePower} 与 tick 监听器），测试类必须同包才能直接驱动状态机。
 * 这些方法本身不触世界：位置、刻数与身份都由参数传入，因此普通 JUnit 即可覆盖。
 *
 * <h2>时间口径</h2>
 * {@code lastTick} 是“最后一个仍生效的刻”（含），在刻 {@code T} 登记、时长 {@code D}
 * 写入 {@code T + D - 1}；清理在刻 {@code now} 的 Post 阶段按 {@code lastTick <= now} 执行。
 * 本类所有断言都按这个口径写，避免把“1 刻脉冲实际持续 2 刻”这类 off-by-one 固化进实现。
 */
class RedstonePowerDataTest {
    private static final ResourceKey<Registry<Object>> TEST_REGISTRY =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("additional_abilities", "test_registry"));
    private static final ResourceKey<Object> ABILITY =
            ResourceKey.create(TEST_REGISTRY, ResourceLocation.fromNamespaceAndPath("additional_abilities", "ability_a"));
    private static final ResourceKey<Object> OTHER_ABILITY =
            ResourceKey.create(TEST_REGISTRY, ResourceLocation.fromNamespaceAndPath("additional_abilities", "ability_b"));

    private static final UUID CASTER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID CASTER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final BlockPos SOURCE = new BlockPos(8, 64, -3);

    // ================================================================ 聚合与键

    @Test
    @DisplayName("同一 (S, D) 上读取仍存活的最高强度")
    void sameKeyReadsHighestPower() {
        RedstonePowerData data = new RedstonePowerData();

        assertTrue(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 5, 0L, 10));
        assertTrue(data.register(CASTER_B, OTHER_ABILITY, SOURCE, Direction.NORTH, 9, 0L, 10));

        assertEquals(9, data.powerAt(0L, SOURCE, Direction.NORTH));
        assertEquals(1, data.entryCount(), "不同施法者 / 技能落在同一个键上，仍是一条键、两笔贡献");
    }

    @Test
    @DisplayName("方向是键的一部分：同一坐标的不同输入面互不串信号")
    void directionIsPartOfTheKey() {
        RedstonePowerData data = new RedstonePowerData();

        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 15, 0L, 10);
        data.register(CASTER_A, ABILITY, SOURCE, Direction.SOUTH, 5, 0L, 10);

        assertEquals(15, data.powerAt(0L, SOURCE, Direction.NORTH));
        assertEquals(5, data.powerAt(0L, SOURCE, Direction.SOUTH));
        assertEquals(0, data.powerAt(0L, SOURCE, Direction.UP), "没登记过的方向必须是 0");
        assertEquals(2, data.entryCount());
    }

    @Test
    @DisplayName("未登记的键 / 空表返回 0")
    void unknownKeyReadsZero() {
        RedstonePowerData data = new RedstonePowerData();

        assertTrue(data.isEmpty());
        assertEquals(0, data.powerAt(0L, SOURCE, Direction.NORTH));
    }

    // ================================================================ 逐笔到期与边沿

    @Test
    @DisplayName("短贡献到期时，若它曾是最大值，清理会报一次从高到低的边沿")
    void fallingEdgeIsReportedOnce() {
        RedstonePowerData data = new RedstonePowerData();

        // A：9 级，1 刻（lastTick = 0）；B：5 级，10 刻（lastTick = 9）
        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 9, 0L, 1);
        data.register(CASTER_B, OTHER_ABILITY, SOURCE, Direction.NORTH, 5, 0L, 10);

        List<RedstonePowerData.PowerChange> changes = data.expire(0L);

        assertEquals(1, changes.size(), "只该报一次聚合变化");
        assertEquals(9, changes.get(0).before());
        assertEquals(5, changes.get(0).after(), "短贡献退场后应回落到 B 的 5");
        assertEquals(5, data.powerAt(0L, SOURCE, Direction.NORTH));
        assertTrue(data.expire(0L).isEmpty(), "同刻重复清理不得重复广播");
    }

    @Test
    @DisplayName("最后一笔到期：聚合降到 0、整键移除，且只报一次")
    void lastContributionReportsZeroAndDropsKey() {
        RedstonePowerData data = new RedstonePowerData();
        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 5, 0L, 10);

        assertTrue(data.expire(8L).isEmpty(), "还没到期时清理应保持安静");
        assertEquals(5, data.powerAt(8L, SOURCE, Direction.NORTH));

        List<RedstonePowerData.PowerChange> changes = data.expire(9L);

        assertEquals(1, changes.size());
        assertEquals(5, changes.get(0).before());
        assertEquals(0, changes.get(0).after());
        assertTrue(data.isEmpty(), "没有任何贡献后整键必须移除");
    }

    @Test
    @DisplayName("已到期但尚未清理的贡献不会被读到（到期刻 <= 当前刻即失效）")
    void expiredContributionIsNotReadBeforeCleanup() {
        RedstonePowerData data = new RedstonePowerData();
        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 9, 0L, 1);

        assertEquals(9, data.powerAt(0L, SOURCE, Direction.NORTH), "登记刻仍生效");
        assertEquals(0, data.powerAt(1L, SOURCE, Direction.NORTH), "下一刻意已失效，即使清理还未跑");
    }

    @Test
    @DisplayName("时长语义含端点：登记刻 T、时长 1 恰好覆盖 T 一个刻")
    void singleTickCoversExactlyTheRegistrationTick() {
        RedstonePowerData data = new RedstonePowerData();
        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 15, 7L, 1);

        assertEquals(15, data.powerAt(7L, SOURCE, Direction.NORTH));
        assertEquals(0, data.powerAt(8L, SOURCE, Direction.NORTH));
    }

    // ================================================================ 同身份合并

    @Test
    @DisplayName("同一施法者 + 技能重复登记：取较晚到期、较高强度，聚合未变时不报边沿")
    void sameIdentityRefreshesWithoutShortening() {
        RedstonePowerData data = new RedstonePowerData();

        assertTrue(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 5, 0L, 10));  // lastTick 9
        assertFalse(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 5, 5L, 3));  // lastTick 7 → 仍取 9

        assertEquals(5, data.powerAt(9L, SOURCE, Direction.NORTH), "较短的续期不得把已有窗口缩短");
        assertEquals(1, data.entryCount());

        assertTrue(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 9, 6L, 3));   // 5 -> 9
        assertEquals(9, data.powerAt(6L, SOURCE, Direction.NORTH));

        assertFalse(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 3, 7L, 3),
                "同身份的低强度续期不得削减已有强度");
        assertEquals(9, data.powerAt(7L, SOURCE, Direction.NORTH));
    }

    @Test
    @DisplayName("同名旧贡献已过期时，重复登记以新强度替换，而不是沿用旧的高强度")
    void staleContributionDoesNotInflateRefresh() {
        RedstonePowerData data = new RedstonePowerData();

        // lastTick = 0，但故意不调用 expire：模拟清理延迟，旧贡献仍留在表中
        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 15, 0L, 1);

        // 第 5 刻重新登记 5 级：旧贡献早已失效，不能靠 max 把 15 带回来
        assertTrue(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 5, 5L, 10));
        assertEquals(5, data.powerAt(5L, SOURCE, Direction.NORTH));
    }

    @Test
    @DisplayName("清理延迟时也补下降沿：世界最后看到 15，退场仍须通知 0")
    void lateCleanupStillReportsFallingEdge() {
        RedstonePowerData data = new RedstonePowerData();

        // 登记后不调用 expire（模拟错过一个刻的 Post），然后在更晚的刻才清理
        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 15, 0L, 1);
        assertEquals(15, data.powerAt(0L, SOURCE, Direction.NORTH));

        List<RedstonePowerData.PowerChange> changes = data.expire(5L);

        assertEquals(1, changes.size(), "世界最后看到的是 15，清理时必须补一次下降沿");
        assertEquals(15, changes.get(0).before());
        assertEquals(0, changes.get(0).after());
        assertTrue(data.isEmpty());
    }

    @Test
    @DisplayName("最大值到期后无需清理即可读到次高值（缓存必须按时失效）")
    void cacheRecomputesWhenMaxExpiresWithoutCleanup() {
        RedstonePowerData data = new RedstonePowerData();

        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 9, 0L, 1);        // lastTick 0
        data.register(CASTER_B, OTHER_ABILITY, SOURCE, Direction.NORTH, 5, 0L, 10); // lastTick 9

        assertEquals(9, data.powerAt(0L, SOURCE, Direction.NORTH), "登记刻取最高值");
        assertEquals(5, data.powerAt(1L, SOURCE, Direction.NORTH),
                "9 级贡献已过期（尚未清理），缓存不得继续返回 9");
        assertEquals(5, data.powerAt(9L, SOURCE, Direction.NORTH));
        assertEquals(0, data.powerAt(10L, SOURCE, Direction.NORTH));
    }

    @Test
    @DisplayName("同身份续期会延长缓存的聚合有效期")
    void cacheExtendsWhenMaxIsRefreshed() {
        RedstonePowerData data = new RedstonePowerData();

        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 9, 0L, 1);  // lastTick 0
        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 9, 0L, 10); // lastTick 9

        assertEquals(9, data.powerAt(5L, SOURCE, Direction.NORTH),
                "续期后 5 刻仍应读到 9；若缓存停在旧有效期会错误返回 0");
    }

    @Test
    @DisplayName("keys() 保留方向键：同一坐标的不同输入面是两条独立键")
    void keysKeepDirection() {
        RedstonePowerData data = new RedstonePowerData();

        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 15, 0L, 10);
        data.register(CASTER_A, ABILITY, SOURCE, Direction.SOUTH, 5, 0L, 10);

        java.util.Set<RedstonePowerData.PowerKey> keys = data.keys();
        assertEquals(2, keys.size());
        assertTrue(keys.contains(new RedstonePowerData.PowerKey(SOURCE, Direction.NORTH)));
        assertTrue(keys.contains(new RedstonePowerData.PowerKey(SOURCE, Direction.SOUTH)));
    }

    @Test
    @DisplayName("不同技能 / 不同施法者各自独立到期，不互相续命")
    void contributionsExpireIndependently() {
        RedstonePowerData data = new RedstonePowerData();

        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 9, 0L, 3);        // lastTick 2
        data.register(CASTER_B, OTHER_ABILITY, SOURCE, Direction.NORTH, 4, 0L, 10); // lastTick 9

        assertEquals(1, data.expire(2L).size(), "A 到期应报边沿（9 -> 4）");
        assertEquals(4, data.powerAt(2L, SOURCE, Direction.NORTH));
        assertEquals(1, data.entryCount(), "B 仍在，键必须保留");

        assertEquals(1, data.expire(9L).size(), "B 到期应报边沿（4 -> 0）");
        assertTrue(data.isEmpty());
    }

    // ================================================================ 非法输入与隔离

    @Test
    @DisplayName("power <= 0 或 duration <= 0 的登记被忽略，不建立键")
    void invalidRegistrationIsIgnored() {
        RedstonePowerData data = new RedstonePowerData();

        assertFalse(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 0, 0L, 10));
        assertFalse(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, -1, 0L, 10));
        assertFalse(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 5, 0L, 0));
        assertFalse(data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 5, 0L, -3));

        assertTrue(data.isEmpty());
        assertEquals(0, data.powerAt(0L, SOURCE, Direction.NORTH));
    }

    @Test
    @DisplayName("空表上的清理是无副作用的空操作")
    void expiringEmptyDataIsNoOp() {
        RedstonePowerData data = new RedstonePowerData();
        assertTrue(data.expire(0L).isEmpty());
        assertTrue(data.isEmpty());
    }

    @Test
    @DisplayName("两个数据实例互不共享状态（对应不同维度 / 存档的隔离）")
    void instancesAreIsolated() {
        RedstonePowerData overworld = new RedstonePowerData();
        RedstonePowerData nether = new RedstonePowerData();

        overworld.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 15, 0L, 10);

        assertEquals(15, overworld.powerAt(0L, SOURCE, Direction.NORTH));
        assertEquals(0, nether.powerAt(0L, SOURCE, Direction.NORTH));
        assertTrue(nether.isEmpty());
    }

    // ================================================================ 方块充能

    @Test
    @DisplayName("方块充能：登记后可读到强度，且与源供能分开记账")
    void blockChargeIsRegisteredSeparately() {
        RedstonePowerData data = new RedstonePowerData();

        assertTrue(data.chargeBlock(CASTER_A, ABILITY, SOURCE, 15, 0L, 20));

        assertEquals(15, data.blockPowerAt(0L, SOURCE));
        assertEquals(0, data.entryCount(), "方块充能不占用 (S, D) 源条目");
        assertEquals(1, data.blockEntryCount());
        assertFalse(data.isEmpty());
    }

    @Test
    @DisplayName("方块充能：同一方块取存活最高强度，逐笔独立到期")
    void blockChargeUsesHighestPowerAndIndependentExpiry() {
        RedstonePowerData data = new RedstonePowerData();

        data.chargeBlock(CASTER_A, ABILITY, SOURCE, 9, 0L, 3);        // lastTick 2
        data.chargeBlock(CASTER_B, OTHER_ABILITY, SOURCE, 4, 0L, 10); // lastTick 9

        assertEquals(9, data.blockPowerAt(2L, SOURCE));
        assertEquals(4, data.blockPowerAt(3L, SOURCE), "9 级贡献过期后应读到 4");
        assertEquals(4, data.blockPowerAt(9L, SOURCE));
        assertEquals(0, data.blockPowerAt(10L, SOURCE));

        List<RedstonePowerData.BlockPowerChange> at2 = data.expireBlocks(2L);
        assertEquals(1, at2.size());
        assertEquals(9, at2.get(0).before());
        assertEquals(4, at2.get(0).after());

        List<RedstonePowerData.BlockPowerChange> at9 = data.expireBlocks(9L);
        assertEquals(1, at9.size());
        assertEquals(4, at9.get(0).before());
        assertEquals(0, at9.get(0).after());
        assertTrue(data.isEmpty());
    }

    @Test
    @DisplayName("方块充能：清理延迟也补下降沿，清空后不重复广播")
    void blockChargeLateCleanupStillReportsFallingEdge() {
        RedstonePowerData data = new RedstonePowerData();
        data.chargeBlock(CASTER_A, ABILITY, SOURCE, 15, 0L, 1); // lastTick 0

        assertEquals(15, data.blockPowerAt(0L, SOURCE), "登记刻仍生效");
        List<RedstonePowerData.BlockPowerChange> changes = data.expireBlocks(5L);

        assertEquals(1, changes.size(), "世界最后看到 15，退场必须补 0 边沿");
        assertEquals(15, changes.get(0).before());
        assertEquals(0, changes.get(0).after());
        assertTrue(data.expireBlocks(5L).isEmpty(), "已经清空后不得重复广播");
        assertTrue(data.isEmpty());
    }

    @Test
    @DisplayName("方块充能：power <= 0 / duration <= 0 被忽略")
    void invalidBlockChargeIsIgnored() {
        RedstonePowerData data = new RedstonePowerData();

        assertFalse(data.chargeBlock(CASTER_A, ABILITY, SOURCE, 0, 0L, 10));
        assertFalse(data.chargeBlock(CASTER_A, ABILITY, SOURCE, 15, 0L, 0));
        assertEquals(0, data.blockEntryCount());
        assertTrue(data.isEmpty());
    }

    @Test
    @DisplayName("方块充能与源供能互不影响：一方到期不删除另一方")
    void blockChargeAndSourceAreIndependent() {
        RedstonePowerData data = new RedstonePowerData();

        data.register(CASTER_A, ABILITY, SOURCE, Direction.NORTH, 15, 0L, 1); // source lastTick 0
        data.chargeBlock(CASTER_A, ABILITY, SOURCE, 15, 0L, 10);               // block lastTick 9

        assertEquals(1, data.entryCount());
        assertEquals(1, data.blockEntryCount());

        assertFalse(data.expire(0L).isEmpty(), "源供能到期应报下降沿");
        assertEquals(0, data.entryCount());
        assertEquals(1, data.blockEntryCount(), "方块充能不应被源供能清理波及");
        assertEquals(15, data.blockPowerAt(0L, SOURCE));

        assertEquals(1, data.expireBlocks(9L).size());
        assertTrue(data.isEmpty());
    }
}
