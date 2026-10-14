package by.timeslowly.additional_abilities.common.ability.block_effects;

import by.timeslowly.additional_abilities.common.ability.block_effects.BlockGlowData.Entry;
import by.timeslowly.additional_abilities.common.ability.block_effects.BlockGlowData.GlowKey;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T06 / F10 的回归测试：方块发光的到期时间必须<b>按施法者逐笔记账</b>，
 * 而不是整条共用一笔。
 *
 * <h2>测试为什么放在生产包内</h2>
 * {@code BlockGlowData.Entry}、{@code entries()}、{@code dropExpired()} 都是包级可见
 * （它们只服务于 {@code BlockGlows}，没必要公开），因此测试类必须同包才能直接构造条目、
 * 逐刻驱动过期逻辑。这里不涉及任何需要真实世界的调用：条目本身只持方块坐标，
 * {@code BlockPos} 无需关卡即可构造。
 *
 * <h2>覆盖的固定情景</h2>
 * 报告给出的形态是「A 贡献 1200 刻、B 贡献 20 刻，100 刻后清除 A」。
 * 旧实现把两名施法者记进一个 {@code Set} 并共用一笔 {@code expireTick}，
 * 于是 B 那笔在 20 刻就已结束的贡献会一直留在集合里撑着条目 ——
 * 清除 A 时整条<b>撤不掉</b>（指令报 0），条目还会继续存活到 1200 刻。
 */
class BlockGlowContributionTest {
    private static final UUID CASTER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID CASTER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final int COLOR = 0xFF00FFFF;
    private static final GlowDisplayType TYPE = GlowDisplayType.OUTLINE;

    private static GlowKey key() {
        return new GlowKey(new BlockPos(4, 70, -9).asLong(), COLOR, TYPE);
    }

    /** 按生产路径的形态建立一条条目：首个施法者随构造进入，其余用 {@code extendTo} 追加 */
    private static Entry entry(final long expireA, final long expireB) {
        Entry entry = new Entry(new BlockPos(4, 70, -9), COLOR, TYPE, expireA, CASTER_A);
        entry.extendTo(CASTER_B, expireB);
        return entry;
    }

    /** 只有一名施法者的条目：此时共享到期刻就等于那一笔，便于直接观察单笔的变化 */
    private static Entry singleCasterEntry(final long expireA) {
        return new Entry(new BlockPos(4, 70, -9), COLOR, TYPE, expireA, CASTER_A);
    }

    // ================================================================ F10：报告固定情景

    @Test
    @DisplayName("F10：短时贡献到期后，清除长时贡献能整条撤掉（旧实现撤不掉）")
    void expiredShortContributionDoesNotKeepEntryAlive() {
        BlockGlowData data = new BlockGlowData();
        data.entries().put(key(), entry(1200L, 20L));

        // 按 LevelTickEvent.Post 的真实节拍逐刻推进：B 的 20 刻在 21 刻时被清掉，A 的 1200 刻还在
        data.dropExpired(21L);

        Entry survivor = data.entries().get(key());
        assertEquals(1, data.entries().size(), "A 仍在贡献，整条不该消失");
        assertEquals(1200L, survivor.expireTick(), "B 退场后共享到期刻应回到 A 的那笔");

        // 100 刻时清除 A：B 早已过期，此时条目已无任何有效贡献 → 必须整条移除
        List<Entry> dropped = data.dropCasters(Set.of(CASTER_A));

        assertEquals(1, dropped.size(), "指令口径：应报告 1 条被整条移除");
        assertTrue(data.isEmpty(), "没有任何有效贡献后条目不该继续存在");
    }

    @Test
    @DisplayName("F10：清除 A 时 B 仍然有效，条目必须原地保留")
    void entrySurvivesWhenAnotherContributionIsStillValid() {
        BlockGlowData data = new BlockGlowData();
        data.entries().put(key(), entry(1200L, 5000L));

        data.dropExpired(100L);
        List<Entry> dropped = data.dropCasters(Set.of(CASTER_A));

        assertTrue(dropped.isEmpty(), "还有 B 撑着，不算整条移除");
        assertEquals(1, data.entries().size(), "条目本体必须保留");
        assertEquals(5000L, data.entries().get(key()).expireTick(), "共享到期刻应取 B 的那笔");
    }

    // ================================================================ 共享到期刻的重算

    @Test
    @DisplayName("共享到期刻取各笔贡献的最大值")
    void sharedExpiryIsTheLatestContribution() {
        assertEquals(1200L, entry(1200L, 20L).expireTick());
        assertEquals(1200L, entry(20L, 1200L).expireTick());
        assertEquals(30L, entry(20L, 30L).expireTick());
    }

    @Test
    @DisplayName("每笔贡献按各自的到期刻退场，退场不影响他人的时长")
    void contributionsExpireIndependently() {
        BlockGlowData data = new BlockGlowData();
        data.entries().put(key(), entry(20L, 30L));

        // 21 刻：A 那笔过期，B 还剩 9 刻 → 条目存活，且到期刻随之收缩
        data.dropExpired(21L);
        assertEquals(1, data.entries().size());
        assertEquals(30L, data.entries().get(key()).expireTick());

        // 30 刻仍不算过期（判据是 now > expireTick）
        data.dropExpired(30L);
        assertEquals(1, data.entries().size(), "恰好到期那一该刻不该提前移除");

        data.dropExpired(31L);
        assertTrue(data.isEmpty(), "最后一笔也过期后整条移除");
    }

    @Test
    @DisplayName("同一施法者重复触发只推晚自己那笔，不会削减已有的更长时长")
    void extendToKeepsTheLaterExpiry() {
        // 用单施法者条目：此时共享到期刻就等于 A 那一笔，可以直接观察续期效果
        Entry entry = singleCasterEntry(100L);

        entry.extendTo(CASTER_A, 40L);
        assertEquals(100L, entry.expireTick(), "较短的续期不该把已有的长时长改小");

        entry.extendTo(CASTER_A, 900L);
        assertEquals(900L, entry.expireTick(), "较长的续期应当生效");

        // 追加第二名施法者后，共享到期刻改由较晚的那笔决定
        entry.extendTo(CASTER_B, 1200L);
        assertEquals(1200L, entry.expireTick(), "共享到期刻取较晚者");
    }

    // ================================================================ 与旧行为的边界

    @Test
    @DisplayName("同色同显示类型仍合并为一条（表键不含施法者）")
    void sameColourAndTypeStillMergeIntoOneEntry() {
        BlockGlowData data = new BlockGlowData();

        // 两次 register 命中同一个 GlowKey：第二次走 extendTo 分支，不新增条目
        data.entries().put(key(), entry(100L, 200L));

        assertEquals(1, data.entries().size(), "同键必须只有一条");
        assertEquals(200L, data.entries().get(key()).expireTick(), "合并后到期刻取较晚者");
    }

    @Test
    @DisplayName("清除未贡献过的施法者不影响条目，也不报告移除")
    void clearingUnrelatedCasterIsANoOp() {
        BlockGlowData data = new BlockGlowData();
        Entry original = entry(100L, 200L);
        data.entries().put(key(), original);

        UUID stranger = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        List<Entry> dropped = data.dropCasters(Set.of(stranger));

        assertTrue(dropped.isEmpty());
        assertEquals(1, data.entries().size());
        assertSame(original, data.entries().get(key()));
    }

    @Test
    @DisplayName("从未发送过的条目保持 NEVER_SENT，使其下一服务端刻立即发出")
    void newEntryIsDueImmediately() {
        assertEquals(BlockGlows.NEVER_SENT, entry(100L, 200L).lastSentTick);
    }

    @Test
    @DisplayName("单一施法者的常见情形：到期即整条移除")
    void singleCasterEntryDropsWhenExpired() {
        BlockGlowData data = new BlockGlowData();
        data.entries().put(key(), entry(50L, 50L));

        data.dropExpired(50L);
        assertFalse(data.isEmpty(), "恰好到期不该移除");

        data.dropExpired(51L);
        assertTrue(data.isEmpty());
    }
}
