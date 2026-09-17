package by.timeslowly.additional_abilities.registry.dragon.ability.block_effects;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.AbilityBlockEffect;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.dragonsurvivalteam.dragonsurvival.util.Functions;
import by.timeslowly.additional_abilities.common.ability.BlockQuakes;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 龙之技能方块效果：方块震动（{@code additional_abilities:block_quake}）。
 * <p>
 * 被目标选择器选中的方块按效果强度<b>跃起</b>一定高度后落回，类似 Boss 砸地激起的地面震跳。
 * <p>
 * <b>纯视觉，不改世界</b>：不调用任何 {@code setBlock} / {@code destroyBlock}，
 * 因此没有邻接更新、光照变化、计划刻，也不会影响方块实体内容与结构。跃起的方块是客户端
 * 本地生成的假身（{@code BlockDisplay}），不参与碰撞、不进存档。
 * <p>
 * <b>筛选链</b>（顺序即开销顺序）：
 * <ol>
 *     <li>概率判定；</li>
 *     <li>{@code valid_blocks} 谓词；</li>
 *     <li>排除空气；</li>
 *     <li>只保留满碰撞方块（台阶 / 楼梯 / 火把 / 草 / 流体被抬起一整格时观感是错的）；</li>
 *     <li>表层判定：上方有遮挡、或上方是流体时跳过（避免把埋在地下的方块也掀起来）。</li>
 * </ol>
 * <p>
 * JSON 字段（与 effect_type 平级）：
 * <pre>
 * "amplifier":    { "type": "minecraft:linear", "base": 0.5, "per_level_above_first": 0.25 }  // 可选，默认 0.5，跃起高度倍率
 * "probability":  0.35                                                                       // 可选，默认 1.0，每次判定独立
 * "valid_blocks": { "type": "minecraft:matching_block_tag", "tag": "minecraft:dirt" }        // 可选，默认全匹配
 * "sound":        "minecraft:item.mace.smash_ground"                                         // 可选，默认无声，跃起时播放的音效 id
 * </pre>
 * 跃起高度 = {@code min(MAX_JUMP_HEIGHT, BASE_JUMP_HEIGHT × amplifier)}。
 * <p>
 * {@code sound} 的 codec 与 DS 的 {@code activation.sound} 同为
 * {@code BuiltInRegistries.SOUND_EVENT.byNameCodec()}，因此直接写音效的注册 id 字符串即可，
 * 与 DS 技能 JSON 的书写风格一致。自定义音效只要已注册进 {@code minecraft:sound_event} 亦可填写。
 */
public record BlockQuakeEffect(LevelBasedValue amplifier, LevelBasedValue probability, BlockPredicate validBlocks,
                               Optional<SoundEvent> sound) implements AbilityBlockEffect {
    /** amplifier = 1.0 时的跃起高度（方块） */
    public static final float BASE_JUMP_HEIGHT = 0.5F;

    /** 跃起高度上限（方块） */
    public static final float MAX_JUMP_HEIGHT = 3.0F;

    private static final float DEFAULT_AMPLIFIER = 0.5F;
    private static final float DEFAULT_PROBABILITY = 1.0F;

    /** 基准跃起高度对应的总时长（刻），见 {@link #durationFor(float)} */
    private static final int BASE_DURATION_TICKS = 10;
    private static final int MIN_DURATION_TICKS = 6;
    private static final int MAX_DURATION_TICKS = 24;

    public static final MapCodec<BlockQuakeEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LevelBasedValue.CODEC.optionalFieldOf("amplifier", LevelBasedValue.constant(DEFAULT_AMPLIFIER)).forGetter(BlockQuakeEffect::amplifier),
            LevelBasedValue.CODEC.optionalFieldOf("probability", LevelBasedValue.constant(DEFAULT_PROBABILITY)).forGetter(BlockQuakeEffect::probability),
            BlockPredicate.CODEC.optionalFieldOf("valid_blocks", BlockPredicate.alwaysTrue()).forGetter(BlockQuakeEffect::validBlocks),
            BuiltInRegistries.SOUND_EVENT.byNameCodec().optionalFieldOf("sound").forGetter(BlockQuakeEffect::sound)
    ).apply(instance, BlockQuakeEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final @NotNull DragonAbilityInstance ability,
                      final @NotNull BlockPos position, final @Nullable Direction direction) {
        int abilityLevel = ability.level();
        float probability = Math.max(0.0F, this.probability.calculate(abilityLevel));

        // 概率：与 DS PotionData 同约定，每次触发独立判定
        if (probability <= 0.0F || dragon.getRandom().nextDouble() > probability) {
            return;
        }

        ServerLevel level = dragon.serverLevel();
        BlockState state = level.getBlockState(position);

        if (!validBlocks.test(level, position) || state.isAir()) {
            return;
        }

        // 只震满碰撞方块：非满方块被抬起一整格时会与相邻方块错位，观感是错的。
        // 流体（水/岩浆）与火把、草、台阶等的碰撞体本就为空或不满，一并在此被滤掉。
        if (!state.isCollisionShapeFullBlock(level, position)) {
            return;
        }

        // 表层判定：上方有遮挡说明该方块被埋住，不该跃起；上方是流体同样跳过
        // （避免"水下的石头穿过水面跳出来"这种观感）
        BlockState above = level.getBlockState(position.above());

        if (above.canOcclude() || !above.getFluidState().isEmpty()) {
            return;
        }

        float height = heightFor(abilityLevel);

        if (height <= 0.0F) {
            return;
        }

        BlockQuakes.trigger(level, position, height, durationFor(height), sound);
    }

    /** 施法一次可用的跃起高度（方块），已钳制到上限 */
    public float heightFor(final int abilityLevel) {
        return Math.min(MAX_JUMP_HEIGHT, BASE_JUMP_HEIGHT * Math.max(0.0F, this.amplifier.calculate(abilityLevel)));
    }

    /**
     * 跃起时长：按自由落体关系随高度以 √ 增长（跳得越高耗时越长），
     * 再钳制到 {@link #MIN_DURATION_TICKS} ~ {@link #MAX_DURATION_TICKS} 的可接受区间。
     */
    private static int durationFor(final float height) {
        int ticks = Math.round(BASE_DURATION_TICKS * (float) Math.sqrt(height / BASE_JUMP_HEIGHT));
        return Math.clamp(ticks, MIN_DURATION_TICKS, MAX_DURATION_TICKS);
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        int abilityLevel = ability.level();
        float height = heightFor(abilityLevel);
        float probability = Math.max(0.0F, this.probability.calculate(abilityLevel));

        // 概率不足 100% 时才追加说明（与 DS 内置 potion 效果同写法）
        MutableComponent chanceText = probability < 1.0F
                ? Component.translatable("additional_abilities.ability.block_quake.chance",
                        DSColors.dynamicValue(Math.round(probability * 100.0F) + "%"))
                : Component.empty();

        // 方块谓词用 DS 2.0.64 起的工具渲染为可读文本（其 11 个语言键 DS 已带中文）
        MutableComponent description = Component.translatable("additional_abilities.ability.block_quake.description",
                DSColors.dynamicValue(String.format(Locale.ROOT, "%.2f", height)),
                Functions.translateBlockPredicate(validBlocks),
                chanceText);

        return List.of(description);
    }

    @Override
    public MapCodec<? extends AbilityBlockEffect> blockCodec() {
        return CODEC;
    }
}
