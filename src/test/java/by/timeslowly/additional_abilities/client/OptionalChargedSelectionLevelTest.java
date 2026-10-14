package by.timeslowly.additional_abilities.client;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T10：可选性蓄力的<b>选档换算只有一份实现</b>。
 *
 * <h2>这条测试守的是什么</h2>
 * {@code displayLevel}（HUD 每帧读）与 {@code resolveReleaseLevel}（松手结算读）此前是
 * <b>两个逐字相同的实现</b>，注释还各自描述一遍同样的三条规则。这是审查报告 §5.1 点名的
 * "确切重复"：两份实现一旦有一边被改动（例如以后给"取消档"加上新语义），
 * <b>HUD 显示与实际释放就会不一致</b> —— 而这两个数字在玩家眼里必须是同一个东西。
 * <p>
 * T10 让 {@code displayLevel} 直接委托 {@code resolveReleaseLevel}。本类把这条等式
 * <b>变成断言</b>：对同一状态遍历全部输入组合，两者必须恒等。委托被撤销、或有人重新写一份，
 * 只要两份不一致就会转红。
 *
 * <h2>为什么把状态重置放进用例</h2>
 * 本类是<b>静态状态</b>（{@code currentAbility} / {@code selectedLevel} / {@code manual}），
 * 用例之间会互相污染（JUnit 不保证执行顺序）。因此每条用例都从
 * {@link OptionalChargedSelection#reset()} 开始，并先 {@code onCasting} 绑定一个技能。
 * 这不是为了迁就测试而改生产代码 —— 这两个入口本就是生产路径上真实存在的调用。
 *
 * <h2>为什么不触世界</h2>
 * 本类只做整数比较，{@code ResourceKey} 是纯值对象，因此可在普通单元测试里运行。
 */
class OptionalChargedSelectionLevelTest {
    private static final ResourceKey<DragonAbility> ABILITY = ResourceKey.create(DragonAbility.REGISTRY,
            ResourceLocation.fromNamespaceAndPath("additional_abilities_test", "selection"));

    /** 遍历到的档位上限：覆盖 0 / 1 / 边界 / 较大值 */
    private static final int MAX_LEVEL = 8;

    private static void begin() {
        OptionalChargedSelection.reset();
        OptionalChargedSelection.onCasting(ABILITY);
    }

    @Test
    @DisplayName("T10：auto 跟随状态下 displayLevel 恒等于 resolveReleaseLevel（全部档位）")
    void displayMatchesReleaseInAutoFollow() {
        begin();

        for (int achieved = 0; achieved <= MAX_LEVEL; achieved++) {
            assertEquals(OptionalChargedSelection.resolveReleaseLevel(achieved),
                    OptionalChargedSelection.displayLevel(achieved),
                    "未滚动时为自动跟随，两个入口必须给出同一个档位；achieved=" + achieved);
        }
    }

    @Test
    @DisplayName("T10：手动指定状态下两者仍然恒等（含选定为 0 = 取消）")
    void displayMatchesReleaseWhenManual() {
        begin();

        for (int achieved = 1; achieved <= MAX_LEVEL; achieved++) {
            // 逐级下滚，覆盖 0（取消）到 achieved（恢复自动跟随）之间的每一个状态
            for (int selected = achieved; selected >= 0; selected--) {
                // 回到自动跟随，再下滚到目标档位，使内部 manual / selectedLevel 处于该状态
                OptionalChargedSelection.reset();
                OptionalChargedSelection.onCasting(ABILITY);

                for (int step = 0; step < achieved - selected; step++) {
                    OptionalChargedSelection.adjust(-1, achieved);
                }

                assertEquals(OptionalChargedSelection.resolveReleaseLevel(achieved),
                        OptionalChargedSelection.displayLevel(achieved),
                        "手动选档时两个入口必须一致；achieved=" + achieved + " selected=" + selected);
            }
        }
    }

    @Test
    @DisplayName("达到档位前（achieved < 1）两个入口都给出 0")
    void bothReturnZeroBeforeAnyLevelIsAchieved() {
        begin();

        assertEquals(0, OptionalChargedSelection.resolveReleaseLevel(0));
        assertEquals(0, OptionalChargedSelection.displayLevel(0));
        assertEquals(0, OptionalChargedSelection.resolveReleaseLevel(-1));
        assertEquals(0, OptionalChargedSelection.displayLevel(-1));
    }

    @Test
    @DisplayName("下滚到底为 0（取消），且上滚回到已达档位即恢复自动跟随")
    void rollingDownToZeroMeansCancelAndBackToAchievedRestoresAuto() {
        begin();

        int achieved = 5;

        // 下滚一次：从"自动跟随 5"变为"手动 4"
        assertEquals(true, OptionalChargedSelection.adjust(-1, achieved));
        assertEquals(4, OptionalChargedSelection.resolveReleaseLevel(achieved));
        assertEquals(4, OptionalChargedSelection.displayLevel(achieved));

        // 一路滚到 0 = 取消
        while (OptionalChargedSelection.adjust(-1, achieved)) {
            // 继续
        }

        assertEquals(0, OptionalChargedSelection.resolveReleaseLevel(achieved), "0 表示取消，不释放");
        assertEquals(0, OptionalChargedSelection.displayLevel(achieved));
        assertEquals(0, OptionalChargedSelection.rawForSync(), "手动选定为取消时，同步值是具体档位 0");

        // 上滚回已达档位即恢复自动跟随
        while (OptionalChargedSelection.adjust(1, achieved)) {
            // 继续
        }

        assertEquals(5, OptionalChargedSelection.resolveReleaseLevel(achieved));
        assertEquals(5, OptionalChargedSelection.displayLevel(achieved));
        assertEquals(OptionalChargedSelection.AUTO, OptionalChargedSelection.rawForSync(),
                "回到已达档位即恢复自动跟随，同步值回到 AUTO 哨兵");
    }
}
