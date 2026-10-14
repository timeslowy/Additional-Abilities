package by.timeslowly.additional_abilities.common.network;

import by.timeslowly.additional_abilities.common.ability.block_effects.GlowDisplayType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockGlowPayload} 的<b>数组一致性契约</b>（审查报告 F17：
 * "BlockGlowPayload 的两数组还需要长度一致与数量校验"）。
 *
 * <h2>为什么这条值得单独测</h2>
 * 两个数组是<b>按索引并行</b>读取的：{@code positions[i]} 配 {@code remainingTicks[i]}。
 * 一旦长度不一致，客户端读到的剩余时长就与位置张冠李戴 —— 表现为"某个方块永远不消失"
 * 或"刚亮就灭"，而且<b>不会有任何异常</b>：{@code long[]} 的越界访问只在真的越界时才抛，
 * 而"长度 5 配长度 3"这种错位在索引 0..2 上是完全合法的读取。
 * <p>
 * 原本的校验只存在于 {@code decode} 侧，且只校验 {@code count}（数组长度由它决定，必然自洽）
 * —— 也就是说<b>编码侧此前毫无约束</b>。现在闸门放进构造器，两端走同一条。
 * <p>
 * 本类不碰世界，可在普通单元测试里运行。
 */
class BlockGlowPayloadTest {
    private static final GlowDisplayType TYPE = GlowDisplayType.OUTLINE;

    @Test
    @DisplayName("长度一致时正常构造，内容原样保留")
    void matchingLengthsAreAccepted() {
        long[] positions = {1L, 2L, 3L};
        int[] remaining = {10, 20, 30};

        BlockGlowPayload payload = assertDoesNotThrow(() -> new BlockGlowPayload(positions, remaining, 0xFF00FF, TYPE));

        assertTrue(payload.positions().length == 3 && payload.remainingTicks().length == 3);
        assertTrue(payload.remainingTicks()[2] == 30);
    }

    @Test
    @DisplayName("空数组合法（服务端确实会构造无位置的空批）")
    void emptyArraysAreAccepted() {
        assertDoesNotThrow(() -> new BlockGlowPayload(new long[0], new int[0], 0, TYPE));
    }

    @Test
    @DisplayName("长度不一致被拒绝：位置多、剩余时长少")
    void longerPositionsAreRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new BlockGlowPayload(new long[]{1L, 2L, 3L}, new int[]{10, 20}, 0, TYPE));

        // 报错必须能定位到具体长度，否则只看到"包构造失败"
        assertTrue(error.getMessage().contains("3") && error.getMessage().contains("2"), error.getMessage());
    }

    @Test
    @DisplayName("长度不一致被拒绝：剩余时长多、位置少")
    void longerRemainingTicksAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BlockGlowPayload(new long[]{1L}, new int[]{10, 20, 30}, 0, TYPE));
    }

    @Test
    @DisplayName("数量上限：恰好 MAX_POSITIONS 合法，多一个就被拒绝")
    void countLimitIsInclusive() {
        int max = BlockGlowPayload.MAX_POSITIONS;

        assertDoesNotThrow(() -> new BlockGlowPayload(new long[max], new int[max], 0, TYPE));

        assertThrows(IllegalArgumentException.class,
                () -> new BlockGlowPayload(new long[max + 1], new int[max + 1], 0, TYPE));
    }

    @Test
    @DisplayName("数量为负不可能（数组长度天然非负），但上限判定不因长度检查而漏掉")
    void countCheckIsIndependentOfLengthCheck() {
        // 两个数组等长但都超限：必须走数量分支而不是被长度分支放过去
        int over = BlockGlowPayload.MAX_POSITIONS + 5;

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new BlockGlowPayload(new long[over], new int[over], 0, TYPE));

        assertTrue(error.getMessage().contains(String.valueOf(over)), error.getMessage());
    }
}
