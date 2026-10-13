package by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus;

import by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus.EnchantmentBonusThreads.Support;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * T12 / F25：附魔消费端的<b>线程归属判定</b>。
 *
 * <h2>这条测试守的是什么</h2>
 * 旧判据是二值的（{@code server == null || !server.isSameThread()} → 客户端），
 * 于是"不是服务端主线程"的<b>任何</b>线程都会被当成客户端 ——
 * 包括原版重建创造模式搜索索引时用的 {@code Worker-Main-*}。那些线程会走到
 * {@code GetEnchantmentLevelEvent} / {@code ItemTooltipEvent}，然后去迭代
 * 客户端游戏线程正在增删的候选集（{@code IdentityHashMap}，非同步）。
 * <p>
 * 修复把判据变成三态，本类把"哪些组合必须归到哪一态"写成断言。
 * 关键的一条是 {@link #unknownThreadIsUnsupportedNeverClient()}：
 * <b>只要不是客户端游戏线程，就绝不能是 CLIENT</b> —— 这正是旧实现违反的那条。
 *
 * <h2>为什么不触世界</h2>
 * {@link EnchantmentBonusThreads#classify} 是纯函数（三个布尔进、一个枚举出），
 * 因此可以在普通单元测试里穷举全部 8 种组合。本机无外网、无 Mockito，
 * 造不出 {@code Minecraft} / {@code MinecraftServer} 实例，所以判定与取值刻意分开：
 * {@code current()} 那层薄壳无法在这里覆盖（见 §未验证项）。
 */
class EnchantmentBonusThreadsTest {
    /** classify 的全部输入组合，顺序与 (onServerThread, physicalClient, onClientGameThread) 一致 */
    private static final boolean[][] ALL_COMBINATIONS = {
            {false, false, false},
            {false, false, true},
            {false, true, false},
            {false, true, true},
            {true, false, false},
            {true, false, true},
            {true, true, false},
            {true, true, true},
    };

    @Test
    @DisplayName("T12：服务端主线程恒判为 SERVER（其余两个事实无关）")
    void serverThreadAlwaysWins() {
        for (boolean physicalClient : new boolean[] {false, true}) {
            for (boolean onClientGameThread : new boolean[] {false, true}) {
                assertEquals(Support.SERVER,
                        EnchantmentBonusThreads.classify(true, physicalClient, onClientGameThread),
                        "onServerThread=true 时必须是 SERVER；physicalClient=" + physicalClient
                                + " onClientGameThread=" + onClientGameThread);
            }
        }
    }

    @Test
    @DisplayName("T12：客户端游戏线程判为 CLIENT")
    void clientGameThreadIsClient() {
        assertEquals(Support.CLIENT, EnchantmentBonusThreads.classify(false, true, true));
    }

    @Test
    @DisplayName("T12（F25 根因）：未知线程判为 UNSUPPORTED，绝不是 CLIENT")
    void unknownThreadIsUnsupportedNeverClient() {
        // 原版创造模式搜索索引用的 Worker-Main-*：不是服务端线程，也不是客户端游戏线程
        Support worker = EnchantmentBonusThreads.classify(false, true, false);

        assertEquals(Support.UNSUPPORTED, worker, "工作线程必须归入不受支持");
        assertNotEquals(Support.CLIENT, worker,
                "把工作线程当成客户端正是 F25 的误判：会在游戏线程增删候选集的同时迭代它");
    }

    @Test
    @DisplayName("T12：专用服务端上不存在 CLIENT —— 无客户端游戏线程时绝不返回 CLIENT")
    void dedicatedServerNeverReportsClient() {
        for (boolean onServerThread : new boolean[] {false, true}) {
            for (boolean onClientGameThread : new boolean[] {false, true}) {
                Support support = EnchantmentBonusThreads.classify(onServerThread, false, onClientGameThread);

                assertNotEquals(Support.CLIENT, support,
                        "物理服务端上不得判出 CLIENT；onServerThread=" + onServerThread
                                + " onClientGameThread=" + onClientGameThread);
            }
        }
    }

    @Test
    @DisplayName("T12：物理服务端上的非服务端线程归入 UNSUPPORTED（不静默取到任何一侧候选集）")
    void dedicatedServerUnknownThreadIsUnsupported() {
        assertEquals(Support.UNSUPPORTED, EnchantmentBonusThreads.classify(false, false, false));
        assertEquals(Support.UNSUPPORTED, EnchantmentBonusThreads.classify(false, false, true),
                "物理服务端不存在客户端游戏线程，第三个事实为真也只能是脏输入");
    }

    @Test
    @DisplayName("T12：只有恰好一种组合产出 CLIENT，且只有服务端线程产出 SERVER")
    void exactlyOneCombinationPerSideAndTheRestUnsupported() {
        int server = 0;
        int client = 0;

        for (boolean[] combination : ALL_COMBINATIONS) {
            Support support = EnchantmentBonusThreads.classify(combination[0], combination[1], combination[2]);

            switch (support) {
                case SERVER -> server++;
                case CLIENT -> client++;
                case UNSUPPORTED -> { /* 兜底态，数量由总数推出 */ }
            }
        }

        assertEquals(4, server, "onServerThread=true 的 4 种组合全部归 SERVER");
        assertEquals(1, client, "只有 (false, true, true) 这一种组合归 CLIENT");
        assertEquals(3, ALL_COMBINATIONS.length - server - client,
                "其余 3 种组合必须是 UNSUPPORTED（不存在第四态）");
    }
}
