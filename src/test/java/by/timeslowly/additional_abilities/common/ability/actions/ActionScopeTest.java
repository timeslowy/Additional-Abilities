package by.timeslowly.additional_abilities.common.ability.actions;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.timeslowly.additional_abilities.common.ability.actions.ActionScope.ActionKey;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 复审 P1 的回归测试：动作身份作用域必须<b>可嵌套、可还原</b>，
 * 且"身份未知"绝不能与任何已知身份相等。
 *
 * <h2>为什么这条测试至关重要</h2>
 * 震动发声分组现在依赖 {@code ActionScope} 提供动作身份，而作用域是
 * {@link ThreadLocal} 支撑的<b>可变全局状态</b>。它的两种失效方式都会静默产生错误分组：
 * <ul>
 *     <li><b>泄漏</b>：派发结束后身份没被清掉 → 下一次无关的震动会被并进上一次的动作；</li>
 *     <li><b>覆盖</b>：嵌套派发内层没还原外层 → 领域的身份被内层覆盖，分组错乱。</li>
 * </ul>
 * 这两条都不会抛异常，只会表现为"声音响错地方"。因此这里把它们变成断言。
 *
 * <h2>为什么不触世界</h2>
 * {@code ActionScope} 只操作 {@link ThreadLocal} 与 {@code record} 比较，
 * 不碰 {@code ServerLevel} / {@code Player}，可在普通单元测试里运行。
 * （{@code castStartTickOf} 需要 {@code ServerLevel}，故不在本类覆盖范围内。）
 */
class ActionScopeTest {
    private static final UUID CASTER_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID CASTER_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static final long ACTION_ONE = 1_000L;
    private static final long ACTION_TWO = 5_000L;

    /** 两个技能键：只用于验证"换技能即换动作" */
    private static final ResourceKey<DragonAbility> ABILITY_ONE = abilityKey("action_scope_one");
    private static final ResourceKey<DragonAbility> ABILITY_TWO = abilityKey("action_scope_two");

    private static ResourceKey<DragonAbility> abilityKey(final String path) {
        return ResourceKey.create(DragonAbility.REGISTRY,
                ResourceLocation.fromNamespaceAndPath("additional_abilities_test", path));
    }

    // ================================================================ 作用域的基本语义

    @Test
    @DisplayName("P1：作用域内可见、结束后必然清空（不泄漏到下一次派发）")
    void scopeIsClearedAfterRun() {
        assertNull(ActionScope.current(), "用例开始前不应有残留身份");

        ActionScope.run(ACTION_ONE, () -> assertEquals(ACTION_ONE, ActionScope.current(),
                "作用域内应能读到本次身份"));

        assertNull(ActionScope.current(),
                "派发结束后必须清空，否则下一次无关的震动会被并进这一次的动作");
    }

    @Test
    @DisplayName("P1：嵌套派发时内层身份生效、外层在结束后还原（领域 → 效果的典型形态）")
    void nestedScopeRestoresOuterIdentity() {
        List<Long> observed = new ArrayList<>();

        ActionScope.run(ACTION_ONE, () -> {
            observed.add(ActionScope.current());

            // 内层（例如领域结算里再派发一个效果）
            ActionScope.run(ACTION_TWO, () -> observed.add(ActionScope.current()));

            // 内层结束后，外层身份必须回来
            observed.add(ActionScope.current());
        });

        assertEquals(List.of(ACTION_ONE, ACTION_TWO, ACTION_ONE), observed,
                "内层应生效、结束时应还原外层；这正是领域与效果两层各自设置身份时的必要行为");

        assertNull(ActionScope.current(), "最外层结束后仍要清空");
    }

    @Test
    @DisplayName("P1：异常路径同样还原作用域（finally 而非顺序语句）")
    void scopeIsRestoredEvenWhenActionThrows() {
        ActionScope.run(ACTION_ONE, () -> {
            try {
                ActionScope.run(ACTION_TWO, () -> {
                    throw new IllegalStateException("模拟效果回调抛异常");
                });
            } catch (IllegalStateException expected) {
                // 吞掉，只关心作用域状态
            }

            assertEquals(ACTION_ONE, ActionScope.current(),
                    "内层抛异常也必须还原外层，否则身份会永久泄漏");
        });

        assertNull(ActionScope.current());
    }

    @Test
    @DisplayName("P1：传 null 表示不标记 —— 作用域内读到 null，且不影响外层")
    void nullIdentityMarksNothing() {
        ActionScope.run(null, () -> assertNull(ActionScope.current(),
                "null 表示身份未知，作用域内也应读到 null"));

        ActionScope.run(ACTION_ONE, () -> {
            ActionScope.run(null, () -> assertNull(ActionScope.current(),
                    "未知身份覆盖期间读到 null"));

            assertEquals(ACTION_ONE, ActionScope.current(), "内层结束后还原外层");
        });
    }

    // ================================================================ 身份键的相等语义

    @Test
    @DisplayName("P1：三段全等才算同一次动作")
    void actionKeyRequiresAllThreeComponents() {
        ActionKey base = new ActionKey(CASTER_A, ABILITY_ONE, ACTION_ONE);

        assertEquals(base, new ActionKey(CASTER_A, ABILITY_ONE, ACTION_ONE), "三段全等 → 同一次动作");
        assertNotEquals(base, new ActionKey(CASTER_B, ABILITY_ONE, ACTION_ONE), "换施法者 → 不同动作");
        assertNotEquals(base, new ActionKey(CASTER_A, ABILITY_TWO, ACTION_ONE), "换技能 → 不同动作");
        assertNotEquals(base, new ActionKey(CASTER_A, ABILITY_ONE, ACTION_TWO), "换施法起点 → 不同动作");
    }

    @Test
    @DisplayName("P1：身份未知只与身份未知相等，绝不并入已知动作")
    void unknownIdentityNeverEqualsAKnownOne() {
        ActionKey unknown = new ActionKey(CASTER_A, ABILITY_ONE, null);
        ActionKey known = new ActionKey(CASTER_A, ABILITY_ONE, ACTION_ONE);

        assertNotEquals(unknown, known,
                "未接线的调用方若被并入已知动作，会把那个动作的声源拖到两者之间");

        assertEquals(unknown, new ActionKey(CASTER_A, ABILITY_ONE, null),
                "两个未知身份仍是同一组（同施法者同技能），不应对每个未知项各响一声");
    }
}
