package by.timeslowly.additional_abilities.registry.dragon.ability.block_effects;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.TargetDirection;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.AbilityBlockEffect;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.dragonsurvivalteam.dragonsurvival.util.Functions;
import by.timeslowly.additional_abilities.common.ability.block_effects.VirtualRedstonePower;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;

/**
 * 龙之技能方块效果：红石供能（{@code additional_abilities:redstone_powering}）。
 * <p>
 * 给目标方块注入一次<b>虚拟红石输入</b>，让红石线、继电器、灯、活塞、发射器、比较器等原版元件
 * 按它们自己的代码作出反应 —— 世界中<b>不会</b>出现任何红石源方块。
 *
 * <h2>两种模式</h2>
 * <ul>
 *     <li><b>有 {@code target_direction}</b>：设目标方块为 {@code P}、方向为 {@code D}，
 *         在虚拟源 {@code S = P.relative(D)} 上登记一笔强度 {@code power} 的供能。
 *         {@code SignalGetter} 的 {@code getSignal(S, D)} / {@code getDirectSignal(S, D)} /
 *         {@code getControlInputSignal(S, D, false)} 返回 {@code max(真实值, 虚拟值)}，
 *         于是消费者把它当成“从 {@code D} 那一侧来的红石源”（最后一条覆盖比较器侧面输入）；</li>
 *     <li><b>省略 {@code target_direction}</b>：不建立 S 处的源，只对 {@code P} 自己调用一次
 *         {@code level.neighborChanged(P, Blocks.REDSTONE_BLOCK, P)}，让方块的邻居激活逻辑重新检查一遍。
 *         这不是模拟玩家右键：按钮 / 拉杆 / 压力板 / 绊线等需要玩家或实体交互的方块不会被按下。</li>
 * </ul>
 *
 * <h2>方块充能（目标本身是导电方块时）</h2>
 * 若 {@code P} 满足 {@code state.isRedstoneConductor(level, P)}（原木、铁块、石头等原版“可被弱充能”的方块），
 * 则<b>无论有没有 {@code target_direction}</b>，都会把 {@code P} 本身登记成一个虚拟充能方块：
 * <ul>
 *     <li>{@code getSignal(P, 任意面)} / {@code getDirectSignal(P, 任意面)} /
 *         {@code getControlInputSignal(P, 任意面, false)} 在 {@code pulse_duration} 内返回
 *         {@code max(真实值, power)} —— 即向六个面放能；</li>
 *     <li>充能建立 / 撤销时对 {@code P} 的六个邻居补发邻居更新，贴着 {@code P} 的中继器、红石火把、
 *         灯等会重新求值；</li>
 *     <li>有方向时，上一节的“S 处源供能”仍会建立，因此“P 向四周放能”和“P 自身作为消费者被读取”
 *         两种行为可以同时存在。</li>
 * </ul>
 * 非导电目标（空气、中继器、比较器等）不建立方块充能，保持原有行为。
 * 方块充能不改变 {@code P} 的真实方块状态，所以不会触发观察者；无方向模式下的红石灯会被“充能”，
 * 但灯本身是在读邻居而不是读自己，不会因此点亮 —— 需要点亮它请给一个方向，让它同时获得 S 处的源。
 *
 * <h2>方向怎么解释（{@code target_direction}）</h2>
 * 统一表示“信号从目标方块哪一侧输入”，复用 DS 的 {@link TargetDirection}：
 * <table>
 *     <tr><th>JSON</th><th>运行时方向 {@code D}</th></tr>
 *     <tr><td>{@code {"direction":"north"}} 等六个固定方向</td><td>直接使用该 {@link Direction}</td></tr>
 *     <tr><td>{@code {"direction":"looking_at"}}</td><td>施法者视线最近轴</td></tr>
 *     <tr><td>{@code {"direction":"towards_entity"}}</td><td>目标方块中心指向施法者眼睛的最近轴</td></tr>
 *     <tr><td>省略</td><td>无方向，走“直接激活”路径</td></tr>
 * </table>
 * {@code AbilityBlockEffect#apply} 的 {@code direction} 形参是 DS 视线目标传入的<b>命中面</b>方向，
 * 与这里的 {@code target_direction} 是两套概念；本效果第一版不把它隐式当成输入方向。
 *
 * <h2>脉冲与到期</h2>
 * 每次 {@code apply} 登记一笔贡献，到期刻为 {@code now + pulse_duration - 1}（含端点）。
 * 同一 {@code (S, D)} 上的多笔贡献（不同施法者 / 不同技能）取仍存活的最高强度，逐笔独立到期；
 * 同一“施法者 + 技能”重复登记只续命，不会在同一 tick 反复排队相同更新。
 * 聚合值发生变化时由 {@code VirtualRedstonePower} 补发一次原版邻居更新；到期归零时同样补一次。
 *
 * <h2>JSON 字段（与 {@code effect_type} 平级）</h2>
 * <pre>
 * "power":           15                                                     // 必填；等级数值，四舍五入后钳制到 0..15
 * "target_direction": { "direction": "north" }                              // 可选；省略 = 无方向直接激活
 * "probability":      0.5                                                   // 可选，默认 1.0，每方块独立判定
 * "valid_blocks":     { "type": "minecraft:matching_block_tag", "tag": "minecraft:buttons" }  // 可选，默认全匹配
 * "pulse_duration":   2                                                     // 可选，默认 1 刻，钳制到 1..20
 * </pre>
 * <b>{@code power} 在无方向模式下仍必填</b>（保持 schema 一致）；对导电目标它决定方块充能的强度，
 * 对非导电目标只作为配置完整性字段使用、不建立任何虚拟信号。{@code power <= 0} 的供能会被静默跳过。
 *
 * <h2>边界与限制</h2>
 * <ul>
 *     <li>不放置、替换或短暂写入红石块；{@code Blocks.REDSTONE_BLOCK} 只作为邻居更新回调的
 *         {@code changedBlock} 标识；</li>
 *     <li>不为效果主动加载区块：目标或虚拟源所在区块未加载时本次触发直接跳过；</li>
 *     <li>不手改红石线 {@code POWER}、不复制 {@code RedStoneWireBlock} / {@code DiodeBlock} 算法，
 *         红石实验特性与更新上限完全交给原版；</li>
 *     <li>方块充能只对 {@code isRedstoneConductor} 的目标建立：普通实体方块会向六个面放能，
 *         非导电目标（空气、中继器、比较器等）不会；</li>
 *     <li>只保证遵循 1.21.1 {@code SignalGetter} / 邻居更新契约的实现。直接读自己 capability、
 *         BlockEntity 或事件的自定义红石方块不保证被识别；</li>
 *     <li>按钮、拉杆、压力板、绊线等“红石源 / 交互触发”方块不会因为本效果被按下；
 *         发射器、命令方块、TNT 等会产生真实副作用，请用 {@code valid_blocks} / {@code target_conditions}
 *         限制目标。</li>
 * </ul>
 */
public record RedstonePoweringEffect(
        LevelBasedValue power,
        Optional<TargetDirection> targetDirection,
        LevelBasedValue probability,
        BlockPredicate validBlocks,
        LevelBasedValue pulseDuration
) implements AbilityBlockEffect {
    /** 信号强度下限（0 = 不供能） */
    public static final int MIN_POWER = 0;

    /** 信号强度上限（原版红石信号只有 0..15） */
    public static final int MAX_POWER = 15;

    /** 默认生效概率 */
    public static final float DEFAULT_PROBABILITY = 1.0F;

    /** 默认脉冲时长（刻）：一次极短的“瞬时脉冲” */
    public static final float DEFAULT_PULSE_DURATION_TICKS = 1.0F;

    /** 脉冲时长下限（刻） */
    public static final int MIN_PULSE_DURATION_TICKS = 1;

    /**
     * 脉冲时长上限（刻）= 1 秒。
     * <p>
     * 硬上限的意义是防止被动技能 / 高频触发把虚拟信号长期留在世界里：
     * 供能本身是“技能的一瞬”，不是可持久化的红石源。
     */
    public static final int MAX_PULSE_DURATION_TICKS = 20;

    public static final MapCodec<RedstonePoweringEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LevelBasedValue.CODEC.fieldOf("power").forGetter(RedstonePoweringEffect::power),
            TargetDirection.CODEC.optionalFieldOf("target_direction").forGetter(RedstonePoweringEffect::targetDirection),
            LevelBasedValue.CODEC.optionalFieldOf("probability", LevelBasedValue.constant(DEFAULT_PROBABILITY))
                    .forGetter(RedstonePoweringEffect::probability),
            BlockPredicate.CODEC.optionalFieldOf("valid_blocks", BlockPredicate.alwaysTrue())
                    .forGetter(RedstonePoweringEffect::validBlocks),
            LevelBasedValue.CODEC.optionalFieldOf("pulse_duration", LevelBasedValue.constant(DEFAULT_PULSE_DURATION_TICKS))
                    .forGetter(RedstonePoweringEffect::pulseDuration)
    ).apply(instance, RedstonePoweringEffect::new));

    @Override
    public void apply(final @NotNull ServerPlayer dragon, final @NotNull DragonAbilityInstance ability,
                      final @NotNull BlockPos position, final @Nullable Direction direction) {
        ServerLevel level = dragon.serverLevel();

        // 1. 目标区块未加载时直接跳过：不为技能效果主动加载远处区块
        if (!level.isLoaded(position)) {
            return;
        }

        // 2. 效果自身的方块过滤（DS 的 target_conditions 在更外层已经执行过）
        if (!validBlocks.test(level, position)) {
            return;
        }

        int abilityLevel = ability.level();
        float probability = Math.max(0.0F, this.probability.calculate(abilityLevel));

        // 3. 概率：与 DS PotionData / 本模组其它方块效果同约定，每个目标位置独立判定
        if (probability <= 0.0F || dragon.getRandom().nextDouble() > probability) {
            return;
        }

        // 4. 信号强度 / 脉冲时长：先钳制再用，避免把 -3 / 40 / NaN 这类配置值带进虚拟层
        int clampedPower = powerFor(abilityLevel);
        int pulseDuration = pulseDurationFor(abilityLevel);

        // 5. 解析输入方向（省略 target_direction 时为空）
        Optional<Direction> inputDirection = resolveInputDirection(dragon, position);

        // 6. 目标本身是原版导电方块时：无论有无方向，都让 P 成为“被充能的方块”。
        //    这是贴着 P 的中继器 / 火把能读到 getSignal(P, side) 的关键：
        //    只通知 S 的邻居覆盖不到它们，必须额外通知 P 的六个邻居。
        if (clampedPower > 0 && level.getBlockState(position).isRedstoneConductor(level, position)) {
            VirtualRedstonePower.chargeBlock(level, dragon.getUUID(), ability.key(), position,
                    clampedPower, pulseDuration);
        }

        if (inputDirection.isEmpty()) {
            // 7a. 无方向：让目标方块自己重新走一次 neighborChanged。
            // 第三个参数不能传 null：CollectingNeighborUpdater 会立刻对它调用 immutable()（见规划 §3.2 的修正）。
            // 传目标自身既保持“只触发 P 自己”，也不会被误认成某个侧面输入。
            // 先转成不可变坐标：目标选择器可能递来 MutableBlockPos，而原版三参数邻居更新不会替我们复制 pos
            BlockPos targetPos = position.immutable();
            level.neighborChanged(targetPos, Blocks.REDSTONE_BLOCK, targetPos);
            return;
        }

        // 7b. 有方向：在 S 处登记源供能（power <= 0 或源区块未加载时内部会静默跳过）
        VirtualRedstonePower.register(level, dragon.getUUID(), ability.key(), position,
                inputDirection.get(), clampedPower, pulseDuration);
    }

    /**
     * 本次触发给出的信号强度，已四舍五入并钳制到 {@link #MIN_POWER}..{@link #MAX_POWER}。
     * <p>
     * {@link Math#round(float)} 对 {@code NaN} 返回 0、对 {@code ±Infinity} 返回 {@code int} 两端，
     * 再钳制一次即可覆盖规划要求的“非有限值”情形，不需要额外分支。
     */
    public int powerFor(final int abilityLevel) {
        return Math.clamp(Math.round(this.power.calculate(abilityLevel)), MIN_POWER, MAX_POWER);
    }

    /**
     * 本次触发给出的脉冲时长（刻），已钳制到
     * {@link #MIN_PULSE_DURATION_TICKS}..{@link #MAX_PULSE_DURATION_TICKS}。
     * <p>
     * 与 {@link #powerFor(int)} 同口径处理非有限值；JSON 里写 0 或负数会被抬到 1 刻，
     * 超过上限则压到 20 刻 —— 采用静默钳制而非解析期报错，是因为等级函数的取值要到运行时才知道。
     */
    public int pulseDurationFor(final int abilityLevel) {
        return Math.clamp(Math.round(this.pulseDuration.calculate(abilityLevel)),
                MIN_PULSE_DURATION_TICKS, MAX_PULSE_DURATION_TICKS);
    }

    /** 用施法者的实时视线 / 眼睛位置解析输入方向（无方向模式返回空） */
    public Optional<Direction> resolveInputDirection(final @NotNull ServerPlayer dragon,
                                                     final @NotNull BlockPos position) {
        return resolveInputDirection(this.targetDirection, dragon.getLookAngle(), dragon.getEyePosition(), position);
    }

    /**
     * 方向解析的纯函数版本（不触世界，可在单元测试里直接调用）。
     * <p>
     * {@code towards_entity} 在方块目标上没有“实体”，按规划解释为“目标方块指向施法者”：
     * 取目标方块中心到施法者眼睛的偏移的最近轴，与 DS {@code PushEffect} 里
     * {@code dragon.getEyePosition().subtract(target.getEyePosition())} 的口径一致。
     *
     * @param targetDirection 效果字段；空表示无方向直接激活
     * @param lookAngle       施法者视线向量（{@code Entity#getLookAngle}）
     * @param eyePosition     施法者眼睛位置（{@code Entity#getEyePosition}）
     * @param targetPosition  目标方块位置 {@code P}
     */
    public static Optional<Direction> resolveInputDirection(final @NotNull Optional<TargetDirection> targetDirection,
                                                            final @NotNull Vec3 lookAngle,
                                                            final @NotNull Vec3 eyePosition,
                                                            final @NotNull BlockPos targetPosition) {
        if (targetDirection.isEmpty()) {
            return Optional.empty();
        }

        TargetDirection target = targetDirection.get();
        Optional<Direction> fixed = target.direction().right();

        if (fixed.isPresent()) {
            return fixed;
        }

        TargetDirection.Type type = target.direction().left().orElse(null);

        if (type == null) {
            // Either 的两侧都为空只可能来自手工构造的非法实例；退化为无方向，而不是抛异常打断施法
            return Optional.empty();
        }

        return Optional.of(switch (type) {
            case LOOKING_AT -> Direction.getNearest(lookAngle);
            case TOWARDS_ENTITY -> Direction.getNearest(Vec3.atCenterOf(targetPosition).vectorTo(eyePosition));
        });
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon,
                                                                       final @NotNull DragonAbilityInstance ability) {
        int abilityLevel = ability.level();
        float probability = Math.max(0.0F, this.probability.calculate(abilityLevel));

        // 方块谓词只在非“全匹配”时说明（与 glow 同口径）；概率不足 100% 时同样追加
        MutableComponent suffix = Component.empty();

        if (validBlocks != BlockPredicate.alwaysTrue()) {
            suffix = suffix.append(Component.translatable("additional_abilities.ability.redstone_powering.restricted",
                    Functions.translateBlockPredicate(validBlocks)));
        }

        if (probability < 1.0F) {
            suffix = suffix.append(Component.translatable("additional_abilities.ability.redstone_powering.chance",
                    Component.literal(Math.round(probability * 100.0F) + "%").withColor(DSColors.BLUE)));
        }

        if (this.targetDirection.isEmpty()) {
            return List.of(Component.translatable(
                    "additional_abilities.ability.redstone_powering.description.direct", suffix));
        }

        // 一律用 Component#withColor 而不是 DSColors.withColor：本类登记在公共源码集、专用服务端也会加载它
        MutableComponent powerText = Component.literal(Integer.toString(powerFor(abilityLevel))).withColor(DSColors.BLUE);
        MutableComponent durationText = Component.literal(Integer.toString(pulseDurationFor(abilityLevel)))
                .withColor(DSColors.BLUE);

        return List.of(Component.translatable("additional_abilities.ability.redstone_powering.description",
                powerText, directionText(this.targetDirection.get()), durationText, suffix));
    }

    /** 方向的可读写法：六个固定方向各一个键，两种动态方向各一个键 */
    private static @NotNull MutableComponent directionText(final @NotNull TargetDirection targetDirection) {
        Optional<Direction> fixed = targetDirection.direction().right();

        if (fixed.isPresent()) {
            return Component.translatable("additional_abilities.ability.redstone_powering.direction."
                    + fixed.get().getSerializedName());
        }

        TargetDirection.Type type = targetDirection.direction().left().orElse(TargetDirection.Type.LOOKING_AT);
        return Component.translatable("additional_abilities.ability.redstone_powering.direction." + type.getSerializedName());
    }

    @Override
    public MapCodec<? extends AbilityBlockEffect> blockCodec() {
        return CODEC;
    }
}
