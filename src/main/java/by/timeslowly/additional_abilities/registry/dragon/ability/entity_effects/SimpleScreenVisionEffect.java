package by.timeslowly.additional_abilities.registry.dragon.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.timeslowly.additional_abilities.common.network.screenvision.ScreenVisionSender;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionType;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
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
 * "size":        0.2                                                                  // 可选，默认 0.15，仅 edge_light 使用
 * "color":       "gold"                                                               // 可选，默认 white，仅 edge_light 使用
 * "probability": 0.5                                                                  // 可选，默认 1.0，每次触发独立判定
 * </pre>
 * 可用的视觉选项（见 {@link ScreenVisionType}，新增类型只需在那里加枚举值）：
 * <ul>
 *     <li>{@code shake} —— 镜头抖动，amplifier 1.0 ≈ 1.5° 的 roll 偏移；</li>
 *     <li>{@code blur} —— 画面模糊（近似近视），amplifier 1.0 ≈ 1 像素的模糊半径，
 *         界面不受影响；换算常量在 {@code client.ClientScreenVisionState}；</li>
 *     <li>{@code edge_light} —— 画面边缘由边向内渐隐的遮罩：{@code amplifier} 是不透明度（强度，
 *         {@code > 1} 按 1 算）、{@code size} 是边缘厚度（占屏幕<b>短边</b>的比例，钳制 0~0.5）、
 *         {@code color} 是遮罩颜色（写 {@code black} 即从"亮边"变成压暗的"暗角"）。</li>
 * </ul>
 * <p>
 * ⚠️ {@code size} 与 {@code color} 是 {@code edge_light} 专有参数：在抖动/模糊的条目里写了它们
 * <b>不报错也不生效</b>。此处刻意不做硬校验 —— codec 抛异常会在数据包加载期直接废掉整个数据包，
 * 代价远大于收益。
 */
// TODO:可将时长转移至`id`结构内，其他字段放于与`id`同级的`parameter`结构里，对齐龙生
public record SimpleScreenVisionEffect(ScreenVisionType type, LevelBasedValue duration, LevelBasedValue amplifier,
                                       LevelBasedValue size, TextColor color, LevelBasedValue probability)
        implements AbilityEntityEffect {
    private static final float DEFAULT_AMPLIFIER = 1.0F;
    private static final float DEFAULT_PROBABILITY = 1.0F;

    /** 边缘厚度的默认值（占屏幕短边的比例） */
    public static final float DEFAULT_SIZE = 0.15F;

    /**
     * 边缘厚度的下限与上限（占屏幕短边的比例）。
     * 上限取 0.5 是因为再大就是「整屏都是遮罩」，与"边光"的意图相悖。
     */
    public static final float MIN_SIZE = 0.0F;
    public static final float MAX_SIZE = 0.5F;

    /**
     * 遮罩的默认颜色：白色 —— 白 = 把画面<b>提亮</b>成边缘发光；
     * 想让观感回到经典的"暗角"，把 {@code color} 写成 {@code black} 即可（同一套渲染，只是色值不同）。
     * <p>
     * ⚠️ 1.21.1 的 {@code TextColor} <b>没有</b> {@code WHITE} 常量（只有 {@code CODEC} 与 {@code fromRgb}），
     * 只能这样构造。
     */
    public static final TextColor DEFAULT_COLOR = TextColor.fromRgb(0xFFFFFF);

    public static final MapCodec<SimpleScreenVisionEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ScreenVisionType.CODEC.fieldOf("type").forGetter(SimpleScreenVisionEffect::type),
            LevelBasedValue.CODEC.fieldOf("duration").forGetter(SimpleScreenVisionEffect::duration),
            LevelBasedValue.CODEC.optionalFieldOf("amplifier", LevelBasedValue.constant(DEFAULT_AMPLIFIER)).forGetter(SimpleScreenVisionEffect::amplifier),
            LevelBasedValue.CODEC.optionalFieldOf("size", LevelBasedValue.constant(DEFAULT_SIZE)).forGetter(SimpleScreenVisionEffect::size),
            TextColor.CODEC.optionalFieldOf("color", DEFAULT_COLOR).forGetter(SimpleScreenVisionEffect::color),
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
        float edgeSize = sizeFor(level);

        // 边光的「边缘厚度为 0」等价于完全不可见（遮罩厚度为零），与不透明度为 0 同理：
        // 不必占用带宽。其他类型不读 size，因此这条判断对它们天然不成立。
        if (type == ScreenVisionType.EDGE_LIGHT && edgeSize <= 0.0F) {
            return;
        }

        ScreenVisionSender.send(player, type, amplitude, edgeSize, color.getValue(), durationTicks);
    }

    /**
     * 边缘厚度（占屏幕短边的比例），已钳制到 {@code [MIN_SIZE, MAX_SIZE]}。
     * <p>
     * 钳制放在这里而不是客户端，是因为 {@code size} 是 {@code edge_light} <b>专有</b>字段，
     * 钳它不会波及其他类型 —— 而共用的 {@code amplifier} 就<b>不能</b>在这里钳制
     * （{@code blur} 的合法强度高达 20，钳到 1 会直接废掉它），
     * 所以强度只在消费端（侧边栏描述与渲染）按"不透明度"语义钳到 {@code [0, 1]}。
     */
    public float sizeFor(final int abilityLevel) {
        return Math.clamp(this.size.calculate(abilityLevel), MIN_SIZE, MAX_SIZE);
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        int level = ability.level();
        int seconds = Math.max(0, (int) this.duration.calculate(level)) / 20;
        float amplitude = Math.max(0.0F, this.amplifier.calculate(level));
        float probability = Math.max(0.0F, this.probability.calculate(level));

        // 概率不足 100% 时才追加说明（与 DS 内置 potion 效果同写法）；
        // 必定生效时留空，不占版面
        MutableComponent chanceText = probability < 1.0F
                ? Component.translatable("additional_abilities.ability.simple_screen_vision.chance",
                        DSColors.dynamicValue(Math.round(probability * 100.0F) + "%"))
                : Component.empty();

        // 边光用的是「颜色 + 尺寸 + 不透明度」三个参数，与抖动/模糊的单一"强度"不是同一套说法，
        // 因此走专用句式 —— 否则会拼出「使目标屏幕边光」这种读不通的句子，
        // 而且把 0~0.5 的比例值当"强度"报出来也不准确。
        if (type == ScreenVisionType.EDGE_LIGHT) {
            // 带颜色的是动词短语而非"强度"，所以句子里由 §6 包住类型名（与通用句式同款高亮）
            MutableComponent typeText = Component.translatable(
                    "additional_abilities.ability.simple_screen_vision.type." + type.getSerializedName());

            // 色值直接渲染成它自己的写法（颜色名或 #RRGGBB），并染上该颜色本身，一眼能看出遮罩是什么色。
            // 用 withColor(int) 而不是 DSColors.withColor：后者内部会走 I18n.exists（客户端专属类），
            // 而本类登记在公共源码集、专用服务端也会加载它。
            MutableComponent colorText = Component.literal(this.color.serialize())
                    .withColor(this.color.getValue());

            // 比例值以百分比呈现，比裸的 0.2 直观
            MutableComponent sizeText = DSColors.dynamicValue(Math.round(sizeFor(level) * 100.0F) + "%");
            MutableComponent opacityText = DSColors.dynamicValue(
                    Math.round(Math.clamp(amplitude, 0.0F, 1.0F) * 100.0F) + "%");

            return List.of(Component.translatable("additional_abilities.ability.simple_screen_vision.description.edge_light",
                    typeText, colorText, sizeText, opacityText,
                    DSColors.dynamicValue(String.valueOf(seconds)),
                    chanceText));
        }

        // 视觉选项以金色高亮，与蓝色的时长 / 强度数值区分
        MutableComponent typeText = DSColors.withColor(
                Component.translatable("additional_abilities.ability.simple_screen_vision.type." + type.getSerializedName()),
                DSColors.GOLD);

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
