package by.timeslowly.additional_abilities.registry.dragon.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.timeslowly.additional_abilities.common.network.ScreenVisionSender;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionType;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Locale;

/**
 * 龙之技能实体效果：简单屏幕视觉（{@code additional_abilities:simple_screen_vision}）。
 * <p>
 * 被目标选择器选中的实体按其选择的视觉选项受到画面影响。<b>仅对玩家生效</b>——
 * 非玩家实体没有"画面"可言，直接跳过。
 * <p>
 * 本效果是<b>纯客户端视觉</b>：不改伤害、移动或属性，服务端零副作用。
 * 由于 {@link AbilityEntityEffect#apply} 只在服务端执行，实际链路为
 * 「服务端鉴权并计算参数 → {@link ScreenVisionSender} 下发 → 客户端渲染」，
 * 与 DS 内置视觉类效果（如 {@code block_vision}）的做法一致。
 * <p>
 * JSON 字段（与 effect_type 平级）：
 * <pre>
 * "type":        "shake"                                                              // 必填，视觉选项
 * "duration":    { "type": "minecraft:linear", "base": 40.0, "per_level_above_first": 10.0 }  // 必填，时长（单位 tick）
 * "amplifier":   { "type": "minecraft:linear", "base": 1.0,  "per_level_above_first": 0.0  }  // 可选，默认 1.0，强度倍率
 * "probability": 0.5                                                                  // 可选，默认 1.0，每次触发独立判定
 * </pre>
 * 可用的视觉选项（见 {@link ScreenVisionType}，新增类型只需在那里加枚举值）：
 * <ul>
 *     <li>{@code shake} —— 镜头抖动，amplifier 1.0 ≈ 1.5° 的 roll 偏移；</li>
 *     <li>{@code blur} —— 画面模糊（近似近视），amplifier 1.0 ≈ 1 像素的模糊半径，
 *         界面不受影响；换算常量在 {@code client.ClientScreenVisionState}。</li>
 * </ul>
 */
public record SimpleScreenVisionEffect(ScreenVisionType type, LevelBasedValue duration, LevelBasedValue amplifier, LevelBasedValue probability) implements AbilityEntityEffect {
    private static final float DEFAULT_AMPLIFIER = 1.0F;
    private static final float DEFAULT_PROBABILITY = 1.0F;

    public static final MapCodec<SimpleScreenVisionEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ScreenVisionType.CODEC.fieldOf("type").forGetter(SimpleScreenVisionEffect::type),
            LevelBasedValue.CODEC.fieldOf("duration").forGetter(SimpleScreenVisionEffect::duration),
            LevelBasedValue.CODEC.optionalFieldOf("amplifier", LevelBasedValue.constant(DEFAULT_AMPLIFIER)).forGetter(SimpleScreenVisionEffect::amplifier),
            LevelBasedValue.CODEC.optionalFieldOf("probability", LevelBasedValue.constant(DEFAULT_PROBABILITY)).forGetter(SimpleScreenVisionEffect::probability)
    ).apply(instance, SimpleScreenVisionEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        // 仅作用于玩家：非玩家实体没有画面可言
        if (!(target instanceof ServerPlayer player)) {
            return;
        }

        int level = ability.level();

        // 概率：与 DS PotionData 同约定，每次触发独立判定
        float probability = Math.max(0.0F, this.probability.calculate(level));
        if (probability <= 0.0F || player.getRandom().nextDouble() > probability) {
            return;
        }

        // 计算值钳制为非负；时长向下取整为刻
        int durationTicks = Math.max(0, (int) this.duration.calculate(level));
        float amplitude = Math.max(0.0F, this.amplifier.calculate(level));

        ScreenVisionSender.send(player, type, amplitude, durationTicks);
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        int level = ability.level();
        int seconds = Math.max(0, (int) this.duration.calculate(level)) / 20;
        float amplitude = Math.max(0.0F, this.amplifier.calculate(level));
        float probability = Math.max(0.0F, this.probability.calculate(level));

        // 视觉选项以金色高亮，与蓝色的时长 / 强度数值区分
        MutableComponent typeText = DSColors.withColor(
                Component.translatable("additional_abilities.ability.simple_screen_vision.type." + type.getSerializedName()),
                DSColors.GOLD);

        // 概率不足 100% 时才追加说明（与 DS 内置 potion 效果同写法）；
        // 必定生效时留空，不占版面
        MutableComponent chanceText = probability < 1.0F
                ? Component.translatable("additional_abilities.ability.simple_screen_vision.chance",
                        DSColors.dynamicValue(Math.round(probability * 100.0F) + "%"))
                : Component.empty();

        MutableComponent description = Component.translatable("additional_abilities.ability.simple_screen_vision.description",
                typeText,
                DSColors.dynamicValue(String.valueOf(seconds)),
                DSColors.dynamicValue(String.format(Locale.ROOT, "%.2f", amplitude)),
                chanceText);

        return List.of(description);
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
