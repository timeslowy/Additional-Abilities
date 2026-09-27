package by.timeslowly.additional_abilities.registry.dragon.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.duration_instance.DurationInstanceBase;
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
import net.minecraft.resources.ResourceLocation;
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
 *
 * <h2>JSON 字段（与 {@code effect_type} 平级）</h2>
 * <pre>
 * "base":        { "id":       "additional_abilities:screen_vision_shake",                     // 必填，本效果的标识
 *                  "duration": { "type": "minecraft:linear", "base": 40.0, "per_level_above_first": 10.0 } }  // 可选，时长（单位 tick）
 * "type":        "shake"                                                              // 必填，视觉选项
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
 *
 * <h2>结构为什么长这样（与 DS 的时长实例族对齐）</h2>
 * DS 的 {@code modifier} / {@code damage_modification} / {@code glow} / {@code block_vision} 等
 * 实体效果统一采用「{@code base} 子对象承载身份与时长 + 效果专有参数作其兄弟字段」的形态
 * （见 {@link DurationInstanceBase}）。本效果原先是把 {@code duration} 直挂 {@code effect_type} 同级，
 * 现改为同一套形态；<b>单条、不带列表</b>，与 DS 的 {@code summon_entity} 效果同构。
 * <p>
 * <b>{@code base} 的六个字段</b>：{@code id}（必填）、{@code duration}、{@code should_remove_automatically}、
 * {@code early_removal_condition}、{@code custom_icon}、{@code is_hidden}。
 * <p>
 * ⚠️ 其中 {@code should_remove_automatically} / {@code early_removal_condition} / {@code custom_icon} /
 * {@code is_hidden} 是 DS 为「把效果实例存进实体附件、逐刻 tick、可序列化」那套机制准备的开关。
 * 本效果<b>不走该机制</b>（画面状态是接收端本地倒计时自管的一次性视觉，没有需要 tick 或持久化的实例），
 * 因此这四个字段能被 codec 接受但<b>没有运行时语义</b>。{@code id} 的唯一用途是对外声明身份
 * （见 {@link #getEffectIDs()}）。这样取舍是为了保持与 DS 的 JSON 形态一致、并留出将来升级的余地，
 * 代价是「写了不生效」的字段比 DS 多 —— 因此在此显式声明，不靠使用者去猜。
 * <p>
 * 另一处取舍：本类<b>用组合而非继承</b> {@code DurationInstanceBase}
 * （DS 的类都是 {@code extends}）。原因有二：record 不能继承类；且继承会一并带来
 * {@code type()} 与 {@code createInstance()} —— 这两个方法在 {@code DurationInstanceBase} 中
 * 直接抛 {@code AssertionError}，只有真正实现附件存储的子类才该拥有它们。
 */
public record SimpleScreenVisionEffect(DurationInstanceBase<?, ?> base, ScreenVisionType type,
                                       LevelBasedValue amplifier, LevelBasedValue size, TextColor color,
                                       LevelBasedValue probability) implements AbilityEntityEffect {
    private static final float DEFAULT_AMPLIFIER = 1.0F;
    private static final float DEFAULT_PROBABILITY = 1.0F;

    /**
     * {@code base.duration} 缺席时的兜底时长（刻）= 3 秒。
     * <p>
     * {@link DurationInstanceBase} 对缺席的 {@code duration} 给出的是 {@code -1}
     * （DS 语义为「无限时长」）。对一次性画面效果而言"永远抖着 / 永远糊着"没有意义，
     * 因此这里把它读作<b>兜底时长</b>而不是"不生效"。取 60 刻与方块效果
     * {@code registry...block_effects.GlowEffect#DEFAULT_DURATION_TICKS} 保持一致。
     * <p>
     * 注意区分：{@code duration} <b>显式</b>算出 {@code 0} 仍是「不生效」（与改动前一致），
     * 只有「未提供」或算出负数才走本兜底。
     */
    public static final float DEFAULT_DURATION_TICKS = 60.0F;

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
            DurationInstanceBase.CODEC.fieldOf("base").forGetter(SimpleScreenVisionEffect::base),
            ScreenVisionType.CODEC.fieldOf("type").forGetter(SimpleScreenVisionEffect::type),
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

        int durationTicks = durationFor(level);
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
     * 本次触发给出的时长（刻）。
     * <p>
     * {@code base.duration} 缺席时 {@link DurationInstanceBase} 给出 {@code -1}（DS 的"无限时长"），
     * 本效果把它读作 {@link #DEFAULT_DURATION_TICKS}；其余情况向下取整并钳制为非负，
     * 算出 {@code 0} 即"不生效"（由 {@link ScreenVisionSender#send} 兜底拦截）。
     */
    public int durationFor(final int abilityLevel) {
        float calculated = this.base.duration().calculate(abilityLevel);

        if (calculated < 0.0F) {
            return (int) DEFAULT_DURATION_TICKS;
        }

        return Math.max(0, (int) calculated);
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
        int seconds = durationFor(level) / 20;
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

    /**
     * 对外声明本效果的身份，取 {@code base.id}。
     * <p>
     * DS 的时长实例族效果（{@code glow} / {@code climbable} / {@code modifier} 等）都会重写本方法，
     * 把自己每个条目的 id 报上去；DS 的 {@code DurationInstance#tick} 会用它做「施法者超距则提前移除」
     * 的判定。本效果没有 DS 那套实例存储，因此这里只是<b>形态对齐</b>，不产生本地行为。
     * <p>
     * ⚠️ 由此带来一条约定：{@code base.id} 应当与同一技能里其它时长实例效果保持<b>唯一</b>
     * （换言之，不要照抄某个 {@code dragonsurvival:} 下的 id）。否则 DS 做超距剔除时可能把
     * 那个同名的效果实例一并判掉。命名空间已天然隔离，正常命名不会踩到。
     */
    @Override
    public @NotNull @Unmodifiable List<ResourceLocation> getEffectIDs() {
        return List.of(this.base.id());
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
