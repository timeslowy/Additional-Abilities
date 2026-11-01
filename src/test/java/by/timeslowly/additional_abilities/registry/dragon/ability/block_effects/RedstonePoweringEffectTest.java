package by.timeslowly.additional_abilities.registry.dragon.ability.block_effects;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.TargetDirection;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code additional_abilities:redstone_powering} 的纯单元测试：
 * Codec 形状、数值钳制与方向解析。
 *
 * <h2>为什么不在这里测 Mixin / 世界行为</h2>
 * 接口 Mixin 只在真实游戏加载期应用，普通 JUnit 里拿不到被注入后的 {@code SignalGetter}；
 * 邻居更新、红石灯 / 中继器 / Observer 的行为也必须有真实 {@code ServerLevel}。
 * 这些留给 GameTest / 手工集成验证；本类守住的是「配置怎么读、数值怎么算、方向怎么落」，
 * 并顺带守住 {@link RedstonePowerDataTest} 里那块不依赖世界的虚拟状态逻辑。
 *
 * <h2>为什么等级数值只用裸浮点 / 直接构造</h2>
 * {@code LevelBasedValue.CODEC} 对带 {@code "type"} 的对象走
 * {@code ENCHANTMENT_LEVEL_BASED_VALUE_TYPE} 注册表，普通单元测试没有 bootstrap；
 * 裸浮点（常数）与 {@link LevelBasedValue#perLevel(float, float)} 直接构造的实例都不触注册表，
 * 因此既覆盖了等级函数求值，又不需要起一个游戏环境。
 */
class RedstonePoweringEffectTest {
    private static DataResult<RedstonePoweringEffect> decode(final String json) {
        return RedstonePoweringEffect.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static RedstonePoweringEffect effect(final String json) {
        DataResult<RedstonePoweringEffect> result = decode(json);
        return result.result().orElseThrow(() -> new AssertionError("应能解析 " + json + "，实际错误：" + result));
    }

    private static RedstonePoweringEffect withPower(final LevelBasedValue power) {
        return new RedstonePoweringEffect(power, Optional.empty(), LevelBasedValue.constant(1.0F),
                BlockPredicate.alwaysTrue(), LevelBasedValue.constant(1.0F));
    }

    private static Optional<Direction> resolve(final TargetDirection direction, final Vec3 lookAngle,
                                               final Vec3 eyePosition, final BlockPos targetPosition) {
        return RedstonePoweringEffect.resolveInputDirection(Optional.of(direction), lookAngle, eyePosition, targetPosition);
    }

    // ================================================================ Codec 形状

    @Test
    @DisplayName("power 是必填字段：缺失时解析失败")
    void powerIsRequired() {
        assertTrue(decode("{\"target_direction\":{\"direction\":\"north\"}}").error().isPresent(),
                "缺 power 必须拒绝，而不是静默取默认值");
    }

    @Test
    @DisplayName("最小 JSON 使用规划约定的默认值")
    void minimalJsonUsesDefaults() {
        RedstonePoweringEffect effect = effect("{\"power\":15}");

        assertEquals(Optional.empty(), effect.targetDirection(), "省略 target_direction = 无方向");
        assertEquals(1.0F, effect.probability().calculate(1), 1.0E-6F);
        assertSame(BlockPredicate.alwaysTrue(), effect.validBlocks(), "valid_blocks 默认全匹配");
        assertEquals(15, effect.powerFor(1));
        assertEquals(1, effect.pulseDurationFor(1), "pulse_duration 默认 1 刻");
    }

    @Test
    @DisplayName("固定方向、looking_at、towards_entity 都能解析")
    void directionVariantsParse() {
        assertEquals(Optional.of(Direction.NORTH),
                effect("{\"power\":15,\"target_direction\":{\"direction\":\"north\"}}")
                        .targetDirection().orElseThrow().direction().right());

        assertEquals(Optional.of(TargetDirection.Type.LOOKING_AT),
                effect("{\"power\":15,\"target_direction\":{\"direction\":\"looking_at\"}}")
                        .targetDirection().orElseThrow().direction().left());

        assertEquals(Optional.of(TargetDirection.Type.TOWARDS_ENTITY),
                effect("{\"power\":15,\"target_direction\":{\"direction\":\"towards_entity\"}}")
                        .targetDirection().orElseThrow().direction().left());
    }

    @Test
    @DisplayName("TargetDirection 的编码 / 解析可往返（六个固定方向 + 两种动态方向）")
    void targetDirectionRoundTrips() {
        for (Direction direction : Direction.values()) {
            assertRoundTrip(TargetDirection.of(direction));
        }

        assertRoundTrip(TargetDirection.lookingAt());
        assertRoundTrip(TargetDirection.towardsEntity());
    }

    private static void assertRoundTrip(final TargetDirection direction) {
        JsonElement encoded = TargetDirection.CODEC.encodeStart(JsonOps.INSTANCE, direction)
                .result().orElseThrow(() -> new AssertionError("应能编码 " + direction));
        TargetDirection decoded = TargetDirection.CODEC.parse(JsonOps.INSTANCE, encoded)
                .result().orElseThrow(() -> new AssertionError("应能解析 " + encoded + "（源 " + direction + "）"));
        assertEquals(direction, decoded);
    }

    @Test
    @DisplayName("probability 与 pulse_duration 按等级数值解析")
    void probabilityAndDurationParse() {
        RedstonePoweringEffect effect = effect(
                "{\"power\":7,\"probability\":0.5,\"pulse_duration\":5}");

        assertEquals(7, effect.powerFor(1));
        assertEquals(0.5F, effect.probability().calculate(1), 1.0E-6F);
        assertEquals(5, effect.pulseDurationFor(1));
    }

    // ================================================================ 数值钳制

    @Test
    @DisplayName("power：四舍五入后钳制到 0..15，非有限值不逃逸")
    void powerIsRoundedAndClamped() {
        assertPower(LevelBasedValue.constant(-5.0F), 0);
        assertPower(LevelBasedValue.constant(0.0F), 0);
        assertPower(LevelBasedValue.constant(7.5F), 8);
        assertPower(LevelBasedValue.constant(15.0F), 15);
        assertPower(LevelBasedValue.constant(15.4F), 15);
        assertPower(LevelBasedValue.constant(15.6F), 15);
        assertPower(LevelBasedValue.constant(40.0F), 15);
        assertPower(LevelBasedValue.constant(Float.NaN), 0);
        assertPower(LevelBasedValue.constant(Float.POSITIVE_INFINITY), 15);
        assertPower(LevelBasedValue.constant(Float.NEGATIVE_INFINITY), 0);
    }

    private static void assertPower(final LevelBasedValue value, final int expected) {
        assertEquals(expected, withPower(value).powerFor(1), "power=" + value);
    }

    @Test
    @DisplayName("power：等级数值按技能等级求值（linear 每级 +2.5）")
    void powerEvaluatesPerLevel() {
        LevelBasedValue linear = LevelBasedValue.perLevel(5.0F, 2.5F);
        RedstonePoweringEffect effect = withPower(linear);

        assertEquals(5, effect.powerFor(1));
        assertEquals(8, effect.powerFor(2), "7.5 四舍五入为 8");
        assertEquals(10, effect.powerFor(3));
    }

    @Test
    @DisplayName("power：等级数值越过上限时封顶 15")
    void powerFromLinearIsCapped() {
        RedstonePoweringEffect effect = withPower(LevelBasedValue.perLevel(10.0F, 10.0F));

        assertEquals(15, effect.powerFor(2), "10 + 10 × 1 = 20 → 15");
    }

    @Test
    @DisplayName("pulse_duration：钳制到 1..20 刻，JSON 里的 0 / 负数抬到 1")
    void pulseDurationIsClamped() {
        assertPulse(LevelBasedValue.constant(-3.0F), 1);
        assertPulse(LevelBasedValue.constant(0.0F), 1);
        assertPulse(LevelBasedValue.constant(1.0F), 1);
        assertPulse(LevelBasedValue.constant(20.0F), 20);
        assertPulse(LevelBasedValue.constant(20.4F), 20);
        assertPulse(LevelBasedValue.constant(21.0F), 20);
        assertPulse(LevelBasedValue.constant(Float.NaN), 1);
        assertPulse(LevelBasedValue.constant(Float.POSITIVE_INFINITY), 20);
        assertPulse(LevelBasedValue.constant(Float.NEGATIVE_INFINITY), 1);
    }

    private static void assertPulse(final LevelBasedValue value, final int expected) {
        RedstonePoweringEffect effect = new RedstonePoweringEffect(LevelBasedValue.constant(15.0F),
                Optional.empty(), LevelBasedValue.constant(1.0F), BlockPredicate.alwaysTrue(), value);
        assertEquals(expected, effect.pulseDurationFor(1), "pulse_duration=" + value);
    }

    // ================================================================ 方向解析

    @Test
    @DisplayName("省略 target_direction：不解析方向（走直接激活）")
    void omittedDirectionIsEmpty() {
        assertTrue(RedstonePoweringEffect.resolveInputDirection(Optional.empty(), Vec3.ZERO, Vec3.ZERO, BlockPos.ZERO)
                .isEmpty());
    }

    @Test
    @DisplayName("固定方向：原样返回，不受视线影响")
    void fixedDirectionWinsOverLook() {
        assertEquals(Optional.of(Direction.UP),
                resolve(TargetDirection.of(Direction.UP), new Vec3(0.0, 0.0, -1.0), Vec3.ZERO, BlockPos.ZERO));
    }

    @Test
    @DisplayName("looking_at：视线最近轴")
    void lookingAtUsesNearestLookAxis() {
        assertLook(new Vec3(0.0, 0.0, -2.0), Direction.NORTH);
        assertLook(new Vec3(0.0, 0.0, 2.0), Direction.SOUTH);
        assertLook(new Vec3(3.0, 0.0, 0.0), Direction.EAST);
        assertLook(new Vec3(-3.0, 0.0, 0.0), Direction.WEST);
        assertLook(new Vec3(0.0, 5.0, 0.0), Direction.UP);
        assertLook(new Vec3(0.0, -1.0, 0.0), Direction.DOWN);
    }

    private static void assertLook(final Vec3 lookAngle, final Direction expected) {
        assertEquals(Optional.of(expected),
                resolve(TargetDirection.lookingAt(), lookAngle, Vec3.ZERO, BlockPos.ZERO), "look=" + lookAngle);
    }

    @Test
    @DisplayName("towards_entity：目标方块中心指向施法者眼睛的最近轴")
    void towardsEntityPointsFromTargetToCaster() {
        BlockPos target = new BlockPos(10, 64, 10);
        Vec3 center = Vec3.atCenterOf(target);

        assertTowards(center.add(0.0, 0.0, 5.0), target, Direction.SOUTH);
        assertTowards(center.add(0.0, 0.0, -5.0), target, Direction.NORTH);
        assertTowards(center.add(5.0, 0.0, 0.0), target, Direction.EAST);
        assertTowards(center.add(-5.0, 0.0, 0.0), target, Direction.WEST);
        assertTowards(center.add(0.0, 5.0, 0.0), target, Direction.UP);
        assertTowards(center.add(0.0, -5.0, 0.0), target, Direction.DOWN);
    }

    private static void assertTowards(final Vec3 eyePosition, final BlockPos target, final Direction expected) {
        assertEquals(Optional.of(expected),
                resolve(TargetDirection.towardsEntity(), Vec3.ZERO, eyePosition, target),
                "eye=" + eyePosition + " target=" + target);
    }

    @Test
    @DisplayName("towards_entity：眼睛与方块中心重合时退化为原版 getNearest(0,0,0) 的 NORTH")
    void towardsEntityDegeneratesToNorth() {
        BlockPos target = new BlockPos(3, 70, -2);
        assertEquals(Optional.of(Direction.NORTH),
                resolve(TargetDirection.towardsEntity(), Vec3.ZERO, Vec3.atCenterOf(target), target));
    }

    // ================================================================ 随附测试技能 JSON

    /** 六个 action 各自的方块谓词目标：用来把六种场景隔离开，便于逐个验证 */
    private static final String[] TEST_ABILITY_TARGETS = {
            "minecraft:observer",
            "minecraft:redstone_lamp",
            "minecraft:note_block",
            "minecraft:copper_bulb",
            "minecraft:comparator",
            "minecraft:oak_log"
    };

    /** 铜灯泡 action 用方块标签覆盖 8 个变种；其余 action 用精确方块匹配 */
    private static final int COPPER_BULB_ACTION_INDEX = 3;

    @Test
    @DisplayName("随附测试技能 JSON 的六个 action 都能被本效果 Codec 解析")
    void bundledTestAbilityJsonParses() throws Exception {
        String resource = "/data/additional_abilities/dragonsurvival/dragon_ability/test_redstone_powering.json";

        try (InputStream stream = RedstonePoweringEffectTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "测试技能 JSON 必须在测试 classpath 上：" + resource);

            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray actions = root.getAsJsonArray("actions");
            assertEquals(6, actions.size(), "测试技能应恰好包含六个可独立验证的 action");

            List<RedstonePoweringEffect> effects = new ArrayList<>();

            for (int index = 0; index < actions.size(); index++) {
                JsonObject blockEffect = actions.get(index).getAsJsonObject()
                        .getAsJsonObject("target_selection")
                        .getAsJsonObject("applied_effects")
                        .getAsJsonArray("block_effect").get(0)
                        .getAsJsonObject()
                        .deepCopy();

                // valid_blocks 的方块匹配谓词要在真实 minecraft:block 注册表里核对 id，
                // 普通单测拿不到那份注册表（游戏内由数据包加载完成校验）。
                // 这里先单独核对 JSON 形状与目标 id，再摘掉它验证本效果其余字段。
                JsonObject validBlocks = blockEffect.getAsJsonObject("valid_blocks");
                assertNotNull(validBlocks, "每个 action 都应用 valid_blocks 隔离目标方块");

                if (index == COPPER_BULB_ACTION_INDEX) {
                    // 铜灯泡有 8 个氧化 / 打蜡变种，测试技能用一个标签覆盖全部
                    assertEquals("minecraft:matching_block_tag", validBlocks.get("type").getAsString());
                    assertEquals("additional_abilities:copper_bulbs", validBlocks.get("tag").getAsString(),
                            "action 4 的铜灯泡标签");
                } else {
                    assertEquals("minecraft:matching_blocks", validBlocks.get("type").getAsString());
                    assertEquals(TEST_ABILITY_TARGETS[index],
                            validBlocks.getAsJsonArray("blocks").get(0).getAsString(),
                            "action " + (index + 1) + " 的目标方块");
                }

                blockEffect.remove("valid_blocks");

                DataResult<RedstonePoweringEffect> result =
                        RedstonePoweringEffect.CODEC.codec().parse(JsonOps.INSTANCE, blockEffect);
                effects.add(result.result().orElseThrow(() -> new AssertionError("解析失败：" + result + "，源：" + blockEffect)));
            }

            assertTrue(effects.get(0).targetDirection().isEmpty(), "action 1：无方向直接激活");
            assertEquals(Optional.of(Direction.UP),
                    effects.get(1).targetDirection().orElseThrow().direction().right(), "action 2：固定向上");
            assertEquals(2, effects.get(1).pulseDurationFor(1), "action 2：显式 pulse_duration = 2");
            assertEquals(Optional.of(TargetDirection.Type.LOOKING_AT),
                    effects.get(2).targetDirection().orElseThrow().direction().left(), "action 3：looking_at");
            assertEquals(Optional.of(TargetDirection.Type.TOWARDS_ENTITY),
                    effects.get(3).targetDirection().orElseThrow().direction().left(), "action 4：towards_entity");
            assertEquals(Optional.of(Direction.EAST),
                    effects.get(4).targetDirection().orElseThrow().direction().right(),
                    "action 5：固定东侧（比较器侧面输入）");
            assertEquals(20, effects.get(4).pulseDurationFor(1), "action 5：20 刻脉冲，便于观察灯灭");
            assertTrue(effects.get(5).targetDirection().isEmpty(), "action 6：原木无方向（纯方块充能）");
            assertEquals(20, effects.get(5).pulseDurationFor(1), "action 6：20 刻脉冲");

            for (RedstonePoweringEffect effect : effects) {
                assertEquals(15, effect.powerFor(1), "六个 action 都应读到 power = 15");
            }
        }
    }
}
