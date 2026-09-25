package by.timeslowly.additional_abilities.registry.dragon.ability.block_effects;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.AbilityBlockEffect;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.dragonsurvivalteam.dragonsurvival.util.Functions;
import by.timeslowly.additional_abilities.common.ability.block_effects.BlockGlows;
import by.timeslowly.additional_abilities.common.ability.block_effects.GlowDisplayType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Locale;

/**
 * 龙之技能方块效果：方块发光（{@code additional_abilities:glow}）。
 * <p>
 * 让<b>范围选择器选中的方块</b>发光，且是<b>服务端广播的固定世界坐标</b>——
 * 视距内的任何玩家都会看到同一处、同一颜色、同一时长的发光。
 * <p>
 * <b>与 {@code dragonsurvival:glow} 的区别</b>（本效果存在的理由）：
 * <ul>
 *     <li>{@code dragonsurvival:glow} 是<b>实体效果</b>，把 {@code Glow.Instance} 挂到玩家身上，
 *         客户端只给<b>该玩家自己的龙模型</b>描边 —— 位置跟着人走，且只有本人看得见；</li>
 *     <li>本效果是<b>方块效果</b>，目标是方块位置，由服务端同步给附近所有玩家 —— 位置固定在世界里，
 *         谁路过都看得见。</li>
 * </ul>
 * <b>与 {@code dragonsurvival:block_vision} 的关系</b>：DS 那套系统（矿石视觉等）已经实现了
 * {@code outline} / {@code simple_shader} 两种显示方式，但它是挂在玩家身上的<b>客户端本地扫描</b>，
 * 完全不经网络同步给其他人。本效果复用了它的<b>渲染表现</b>（见
 * {@code client.eventhandler.BlockGlowRenderHandler}），换掉了它的<b>同步与生命周期语义</b>。
 * <p>
 * <b>纯视觉，不改世界</b>：不调用 {@code setBlock}、不写光照数据、不产生方块更新，
 * 因此<b>不影响实际方块亮度</b>；线框与着色器染色都只是绘制。
 * <p>
 * <b>可以重叠</b>：不同颜色 / 不同显示类型的发光在服务端是不同条目，客户端各自绘制 →
 * 不同施法、不同玩家的效果自然叠加，互不顶替（同色同类型会合并，视觉上本就无法区分）。
 * <p>
 * JSON 字段（与 effect_type 平级）：
 * <pre>
 * "color":         "aqua"                                   // 必填；原版 TextColor：16 个颜色名或 "#RRGGBB"
 * "alpha":         0.6                                      // 可选，默认 1.0，0~1
 * "display_type":  "outline"                                // 可选，默认 outline；outline | simple_shader
 * "duration":      { "type": "minecraft:linear", "base": 60.0, "per_level_above_first": 20.0 }  // 可选，默认 60 刻
 * "probability":   0.5                                      // 可选，默认 1.0
 * "valid_blocks":  { "type": "minecraft:matching_block_tag", "tag": "minecraft:ores" }          // 可选，默认全匹配
 * "hide_occluded": true                                     // 可选，默认 true；仅对 simple_shader 生效
 * </pre>
 * <b>颜色写法注意</b>：{@link TextColor} 的 {@code #} 分支是「按 16 进制整数解析」而非 CSS 简写，
 * 所以 {@code "#FFF"} 是 {@code 0x000FFF}（深蓝）而不是白色 —— 必须写满 6 位。且<b>不支持 alpha</b>
 * （8 位会超出 {@code 0xFFFFFF} 直接报错），半透明请用独立的 {@code alpha} 字段。
 */
public record GlowEffect(TextColor color, float alpha, GlowDisplayType displayType, LevelBasedValue duration,
                         LevelBasedValue probability, BlockPredicate validBlocks, boolean hideOccluded)
        implements AbilityBlockEffect {
    /** 默认时长（刻）= 3 秒 */
    public static final float DEFAULT_DURATION_TICKS = 60.0F;

    /** 时长下限（刻）：0 无意义，1 刻是最短的一次可见 */
    public static final int MIN_DURATION_TICKS = 1;

    /** 时长上限（刻）= 60 秒，防止 JSON 写出一个永不消失的发光 */
    public static final int MAX_DURATION_TICKS = 1200;

    /** 默认不透明度 */
    public static final float DEFAULT_ALPHA = 1.0F;

    public static final MapCodec<GlowEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TextColor.CODEC.fieldOf("color").forGetter(GlowEffect::color),
            Codec.floatRange(0.0F, 1.0F).optionalFieldOf("alpha", DEFAULT_ALPHA).forGetter(GlowEffect::alpha),
            GlowDisplayType.CODEC.optionalFieldOf("display_type", GlowDisplayType.OUTLINE)
                    .forGetter(GlowEffect::displayType),
            LevelBasedValue.CODEC.optionalFieldOf("duration", LevelBasedValue.constant(DEFAULT_DURATION_TICKS))
                    .forGetter(GlowEffect::duration),
            LevelBasedValue.CODEC.optionalFieldOf("probability", LevelBasedValue.constant(1.0F))
                    .forGetter(GlowEffect::probability),
            BlockPredicate.CODEC.optionalFieldOf("valid_blocks", BlockPredicate.alwaysTrue())
                    .forGetter(GlowEffect::validBlocks),
            Codec.BOOL.optionalFieldOf("hide_occluded", true).forGetter(GlowEffect::hideOccluded)
    ).apply(instance, GlowEffect::new));

    @Override
    public void apply(final @NotNull ServerPlayer dragon, final @NotNull DragonAbilityInstance ability,
                      final @NotNull BlockPos position, final @Nullable Direction direction) {
        int abilityLevel = ability.level();
        float probability = Math.max(0.0F, this.probability.calculate(abilityLevel));

        // 概率：与 DS PotionData 同约定，每次触发独立判定（area 目标下即每个方块位置独立掷一次）
        if (probability <= 0.0F || dragon.getRandom().nextDouble() > probability) {
            return;
        }

        ServerLevel level = dragon.serverLevel();

        // direction 在本效果里无意义（发光与命中面无关），因此天然免疫「direction 多为 null」的坑
        if (!validBlocks.test(level, position)) {
            return;
        }

        int durationTicks = durationFor(abilityLevel);

        if (durationTicks <= 0) {
            return;
        }

        float clampedAlpha = Math.clamp(this.alpha, 0.0F, 1.0F);

        // alpha 为 0 等于完全不可见：建立条目纯属浪费（线框还要白跑一遍 GL），直接短路
        if (clampedAlpha <= 0.0F) {
            return;
        }

        // TextColor#getValue() 返回的是纯 RGB，alpha 位恒为 0；直接当 ARGB 用会渲染出「全透明」。
        // 必须显式补 alpha 字节 —— DS 自己在 GlowData / BlockVision 里也是这么补的。
        int colorARGB = DSColors.withAlpha(this.color.getValue(), clampedAlpha);

        BlockGlows.register(level, dragon.getUUID(), position, colorARGB, this.displayType,
                durationTicks, this.hideOccluded);
    }

    /** 本次触发给出的时长（刻），已钳制到可接受区间 */
    public int durationFor(final int abilityLevel) {
        return Math.clamp(Math.round(this.duration.calculate(abilityLevel)), MIN_DURATION_TICKS, MAX_DURATION_TICKS);
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon,
                                                                       final @NotNull DragonAbilityInstance ability) {
        int abilityLevel = ability.level();
        float probability = Math.max(0.0F, this.probability.calculate(abilityLevel));
        float clampedAlpha = Math.clamp(this.alpha, 0.0F, 1.0F);

        // 色值直接渲染成它自己的写法（颜色名或 #RRGGBB），并染上该颜色本身，一眼能看出效果长什么样
        MutableComponent colorText = colored(Component.literal(this.color.serialize()), this.color.getValue());
        MutableComponent secondsText = colored(
                Component.literal(String.format(Locale.ROOT, "%.1f", durationFor(abilityLevel) / 20.0F)), DSColors.BLUE);
        MutableComponent suffix = Component.empty();

        // 方块谓词只在非「全匹配」时才说明，避免每一条描述都拖着「任意方块」（复用 DS 2.0.64 起的渲染工具）
        if (validBlocks != BlockPredicate.alwaysTrue()) {
            suffix = suffix.append(Component.translatable("additional_abilities.ability.glow.restricted",
                    Functions.translateBlockPredicate(validBlocks)));
        }

        if (clampedAlpha < 1.0F) {
            suffix = suffix.append(Component.translatable("additional_abilities.ability.glow.alpha",
                    colored(Component.literal(Math.round(clampedAlpha * 100.0F) + "%"), DSColors.BLUE)));
        }

        // 概率不足 100% 时才追加说明（与 block_quake / extinguish / DS 内置 potion 效果同写法）
        if (probability < 1.0F) {
            suffix = suffix.append(Component.translatable("additional_abilities.ability.glow.chance",
                    colored(Component.literal(Math.round(probability * 100.0F) + "%"), DSColors.BLUE)));
        }

        return List.of(Component.translatable("additional_abilities.ability.glow.description",
                colorText, secondsText, suffix));
    }

    /**
     * 给组件染色。
     * <p>
     * <b>不使用 {@code DSColors.withColor}</b>：那个方法内部会走 {@code I18n.exists}（客户端专属类），
     * 而本类登记在公共源码集、专用服务端也会加载它 —— 虽然方法体引用是惰性解析，
     * 但本效果没有任何「把值当翻译键」的需求，直接 {@code withColor} 既等价又少一条客户端类引用。
     */
    private static @NotNull MutableComponent colored(final @NotNull MutableComponent text, final int color) {
        return text.withColor(color);
    }

    @Override
    public MapCodec<? extends AbilityBlockEffect> blockCodec() {
        return CODEC;
    }
}
