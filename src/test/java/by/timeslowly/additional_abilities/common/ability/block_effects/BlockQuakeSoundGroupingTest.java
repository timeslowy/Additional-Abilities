package by.timeslowly.additional_abilities.common.ability.block_effects;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.timeslowly.additional_abilities.common.ability.block_effects.BlockQuakes.Batches;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T07 / F13 的回归测试：方块震动的<b>发声分组必须按「原施法动作」划分</b>，
 * 而视觉包可以继续跨施法者合并。
 *
 * <h2>覆盖的固定情景</h2>
 * 报告给的是「相距 100 格的两名玩家同刻施放同参数震动」。
 * 旧实现只有一套按「高度 + 时长 + 音效」的分组，两批方块会并成一组，
 * 声源取合并后的<b>几何中心</b> —— 落点在两人之间，而音量固定 1.0
 * （可听半径约 {@code volume × 16} 格），于是<b>两个施法位置都听不到</b>。
 *
 * <h2>为什么断言的是「分组内容」而不是「听不听得见」</h2>
 * 可听距离是原版声音引擎的行为，本机既没有声卡也没有游戏实例。
 * 可以直接钉住的是<b>分组这一层契约</b>：每一组的方块是否只来自同一次动作。
 * 声源由 {@code BlockQuakes.center} 取该组几何中心 —— 只要分组对了，
 * 中心必然落在该动作自己的方块上，可听性随之成立。
 *
 * <h2>测试为什么放在生产包内</h2>
 * {@code BlockQuakes.group}、{@code QuakeShape}、{@code QuakeSource}、
 * {@code BlockQuakeData.enqueue} / {@code pending()} 都是包级可见
 * （只服务于 {@code BlockQuakes}，没必要公开），因此测试类必须同包。
 * 这里不涉及任何需要真实世界的调用：分组只读待发表内容。
 */
class BlockQuakeSoundGroupingTest {
    private static final UUID CASTER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID CASTER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static final ResourceKey<DragonAbility> ABILITY_ONE = abilityKey("quake_one");
    private static final ResourceKey<DragonAbility> ABILITY_TWO = abilityKey("quake_two");

    private static final SoundEvent SOUND = SoundEvent.createVariableRangeEvent(
            ResourceLocation.withDefaultNamespace("item.mace.smash_ground"));

    private static final float HEIGHT = 1.0F;
    private static final int DURATION = 10;

    /** 旧实现的两处声源相距 100 格时，合并中心到任一端的距离都远超可听半径 */
    private static final int FAR_APART = 100;

    private static ResourceKey<DragonAbility> abilityKey(final String path) {
        return ResourceKey.create(DragonAbility.REGISTRY,
                ResourceLocation.fromNamespaceAndPath("additional_abilities_test", path));
    }

    private static BlockQuakeData data() {
        return new BlockQuakeData();
    }

    /** 登记一次震动；{@code now} 固定为 0，冷却不参与本类用例（位置两两不同） */
    private static void enqueue(final BlockQuakeData data, final BlockPos position, final UUID caster,
                                final ResourceKey<DragonAbility> ability, final Optional<SoundEvent> sound) {
        boolean accepted = data.enqueue(position, 0L, HEIGHT, DURATION, sound, caster, ability);
        assertTrue(accepted, "位置两两不同，登记不应被冷却丢弃：" + position);
    }

    // ================================================================ F13：报告固定情景

    @Test
    @DisplayName("F13：相距 100 格的两名玩家同参数震动 —— 视觉合并为 1 组，声音分为 2 组")
    void distantCastersShareVisualBatchButKeepSeparateSoundSources() {
        BlockQuakeData data = data();

        // A 在本地的三个方块
        enqueue(data, new BlockPos(0, 64, 0), CASTER_A, ABILITY_ONE, Optional.of(SOUND));
        enqueue(data, new BlockPos(1, 64, 0), CASTER_A, ABILITY_ONE, Optional.of(SOUND));
        enqueue(data, new BlockPos(2, 64, 0), CASTER_A, ABILITY_ONE, Optional.of(SOUND));

        // B 在 100 格外的三个方块，参数完全相同
        enqueue(data, new BlockPos(FAR_APART, 64, 0), CASTER_B, ABILITY_ONE, Optional.of(SOUND));
        enqueue(data, new BlockPos(FAR_APART + 1, 64, 0), CASTER_B, ABILITY_ONE, Optional.of(SOUND));
        enqueue(data, new BlockPos(FAR_APART + 2, 64, 0), CASTER_B, ABILITY_ONE, Optional.of(SOUND));

        Batches batches = BlockQuakes.group(data);

        assertEquals(1, batches.visual().size(),
                "视觉包必须跨施法者合并：同参数的两批方块在画面上无法区分，合并是纯收益");
        assertEquals(6, batches.visual().values().iterator().next().size(),
                "合并后的那一组应含全部 6 个位置");

        assertEquals(2, batches.sound().size(),
                "声音必须按原施法动作分开：合并后声源会落在两人之间，两边都听不到");

        // 逐组核对归属：每一组只含自己那一次动作的方块（这才是"声源落在当地"的充要条件）
        List<List<Long>> groups = List.copyOf(batches.sound().values());
        assertEquals(3, groups.get(0).size());
        assertEquals(3, groups.get(1).size());
        assertEquals(List.of((long) new BlockPos(0, 64, 0).asLong(),
                        (long) new BlockPos(1, 64, 0).asLong(),
                        (long) new BlockPos(2, 64, 0).asLong()),
                groups.get(0), "A 那一组只能含 A 的方块");
        assertEquals(List.of((long) new BlockPos(FAR_APART, 64, 0).asLong(),
                        (long) new BlockPos(FAR_APART + 1, 64, 0).asLong(),
                        (long) new BlockPos(FAR_APART + 2, 64, 0).asLong()),
                groups.get(1), "B 那一组只能含 B 的方块");
    }

    // ================================================================ 「一次范围动作只响一次」

    @Test
    @DisplayName("F13：同一施法者的同一次动作仍只合并为一声")
    void oneActionStillSoundsOnce() {
        BlockQuakeData data = data();

        // 一次范围震动抬起几十个方块：全部同施法者、同技能、同参数
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                enqueue(data, new BlockPos(x, 64, z), CASTER_A, ABILITY_ONE, Optional.of(SOUND));
            }
        }

        Batches batches = BlockQuakes.group(data);

        assertEquals(1, batches.visual().size());
        assertEquals(1, batches.sound().size(), "一次动作只能有一声，否则听感是爆音而不是'一下砸地'");
        assertEquals(64, batches.sound().values().iterator().next().size());
    }

    @Test
    @DisplayName("F13：同一施法者、同参数但不同技能 —— 是两个动作，应各响一声")
    void differentAbilitiesOfTheSameCasterSoundSeparately() {
        BlockQuakeData data = data();

        enqueue(data, new BlockPos(0, 64, 0), CASTER_A, ABILITY_ONE, Optional.of(SOUND));
        enqueue(data, new BlockPos(FAR_APART, 64, 0), CASTER_A, ABILITY_TWO, Optional.of(SOUND));

        Batches batches = BlockQuakes.group(data);

        assertEquals(1, batches.visual().size(), "形状相同，视觉仍合并");
        assertEquals(2, batches.sound().size(),
                "不同技能是两次独立动作，合并同样会把声源放到两者之间");
    }

    // ================================================================ 分组的其余口径

    @Test
    @DisplayName("无声震动不产生发声分组")
    void silentQuakesProduceNoSoundBatches() {
        BlockQuakeData data = data();

        enqueue(data, new BlockPos(0, 64, 0), CASTER_A, ABILITY_ONE, Optional.empty());
        enqueue(data, new BlockPos(FAR_APART, 64, 0), CASTER_B, ABILITY_ONE, Optional.empty());

        Batches batches = BlockQuakes.group(data);

        assertEquals(1, batches.visual().size(), "无声也要发包，方块照跳");
        assertTrue(batches.sound().isEmpty(), "没有音效就不该建立发声分组");
    }

    @Test
    @DisplayName("形状不同仍是不同的视觉分组（高度 / 时长 / 音效都参与键）")
    void differentShapesStayInSeparateVisualBatches() {
        BlockQuakeData data = data();

        data.enqueue(new BlockPos(0, 64, 0), 0L, 1.0F, 10, Optional.of(SOUND), CASTER_A, ABILITY_ONE);
        data.enqueue(new BlockPos(1, 64, 0), 0L, 2.0F, 10, Optional.of(SOUND), CASTER_A, ABILITY_ONE);
        data.enqueue(new BlockPos(2, 64, 0), 0L, 1.0F, 20, Optional.of(SOUND), CASTER_A, ABILITY_ONE);
        data.enqueue(new BlockPos(3, 64, 0), 0L, 1.0F, 10, Optional.empty(), CASTER_A, ABILITY_ONE);

        Batches batches = BlockQuakes.group(data);

        assertEquals(4, batches.visual().size(), "高度 / 时长 / 音效各变一项都应另起一组");
        assertEquals(3, batches.sound().size(), "音效为空的那一项没有声音，故发声分组只有 3 组");
    }

    @Test
    @DisplayName("发声与视觉分组各自独立计数，互不影响对方的规模")
    void visualAndSoundGroupingCountsAreIndependent() {
        BlockQuakeData data = data();

        // 两个施法者、各一种技能、参数相同 → 视觉 1 组、声音 2 组
        enqueue(data, new BlockPos(0, 64, 0), CASTER_A, ABILITY_ONE, Optional.of(SOUND));
        enqueue(data, new BlockPos(1, 64, 0), CASTER_B, ABILITY_ONE, Optional.of(SOUND));

        Batches batches = BlockQuakes.group(data);

        assertEquals(1, batches.visual().size());
        assertEquals(2, batches.sound().size());

        // 反过来：同一动作、参数不同 → 视觉 2 组、声音 2 组（视觉拆分不会把声音并回去）
        BlockQuakeData other = data();
        other.enqueue(new BlockPos(0, 64, 0), 0L, 1.0F, 10, Optional.of(SOUND), CASTER_A, ABILITY_ONE);
        other.enqueue(new BlockPos(1, 64, 0), 0L, 2.0F, 10, Optional.of(SOUND), CASTER_A, ABILITY_ONE);

        Batches split = BlockQuakes.group(other);

        assertEquals(2, split.visual().size());
        assertEquals(2, split.sound().size(), "形状是发声键的一部分，拆开后自然不再同组");
    }

    @Test
    @DisplayName("空待发表产生空分组（tick 路径的早退判据之外仍要安全）")
    void emptyPendingProducesEmptyBatches() {
        Batches batches = BlockQuakes.group(data());

        assertTrue(batches.visual().isEmpty());
        assertTrue(batches.sound().isEmpty());
    }
}
