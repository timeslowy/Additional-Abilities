package by.timeslowly.additional_abilities.registry.dragon.ability.block_effects;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.AbilityBlockEffect;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * 龙之技能方块效果：熄灭（{@code additional_abilities:extinguish}）。
 * <p>
 * DS 内置 {@code dragonsurvival:fire}（{@link by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.FireEffect}）
 * 的<b>反向版</b>：把目标位置「正在燃烧的东西」扑灭，而不是点着。
 * <p>
 * <b>分支镜像对照</b>（{@code fire} 的四个分支 → 本效果）：
 * <ol>
 *     <li>TNT 引爆 → <b>不实现</b>（已引燃的 TNT 无法取消，不存在反向操作）；</li>
 *     <li>未点燃营火 → 点燃 ⇒ <b>已点燃营火 → 熄灭</b>；</li>
 *     <li>{@code snowy} 方块 → 除雪 → <b>不实现</b>（反向「加雪」与熄灭无语义关系）；</li>
 *     <li>空气位 + 概率 → 放火 ⇒ <b>{@code fire} / {@code soul_fire} + 概率 → 移除</b>。</li>
 * </ol>
 * 另补一支 {@code fire} 没有的：<b>蜡烛 / 蜡烛蛋糕 → 熄灭</b>（同为「燃着的方块」，灭火语义自然覆盖）。
 * <p>
 * <b>为什么不用「灭火 API」</b>：MC 1.21.1 并没有现成的「把火焰方块扑灭」方法
 * （{@code FireBlock} / {@code BaseFireBlock} 均无 {@code extinguish}）。三条判定直接照抄原版喷溅水瓶的
 * {@code ThrownPotion#dowseFire}，行为与原版一致：火焰走 {@code destroyBlock}，
 * 蜡烛走 {@link AbstractCandleBlock#extinguish}，营火走 {@link CampfireBlock#dowse} + 手动置 {@code LIT=false}。
 * <p>
 * <b>概率只作用于「移火」分支</b> —— 与 {@code fire} 的结构严格对称（{@code fire} 的 TNT / 营火 / 除雪三支同样不吃概率），
 * 营火与蜡烛为无条件熄灭。
 * <p>
 * {@code direction} 参数不参与任何判定（灭火不需要朝向），因此天然免疫「{@code direction} 多为 null」的坑。
 * <p>
 * JSON 字段（与 effect_type 平级）：
 * <pre>
 * "extinguish_probability": 1.0                                                  // 可选，默认 1.0，仅作用于火焰方块
 * "extinguish_probability": { "type": "minecraft:linear", "base": 0.5, "per_level_above_first": 0.5 }
 * </pre>
 */
public record ExtinguishEffect(LevelBasedValue extinguishProbability) implements AbilityBlockEffect {
    /** 默认概率：1.0（必灭） */
    public static final float DEFAULT_EXTINGUISH_PROBABILITY = 1.0F;

    public static final MapCodec<ExtinguishEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LevelBasedValue.CODEC.optionalFieldOf("extinguish_probability", LevelBasedValue.constant(DEFAULT_EXTINGUISH_PROBABILITY))
                    .forGetter(ExtinguishEffect::extinguishProbability)
    ).apply(instance, ExtinguishEffect::new));

    @Override
    public void apply(final @NotNull ServerPlayer dragon, final @NotNull DragonAbilityInstance ability,
                      final @NotNull BlockPos position, final @Nullable Direction direction) {
        ServerLevel level = dragon.serverLevel();
        BlockState state = level.getBlockState(position);

        if (state.is(BlockTags.FIRE)) {
            // 与 FireEffect 的「放火」分支互为镜像：这是唯一吃概率的分支。
            // 概率 <= 0 时直接跳过，省掉一次随机数调用。
            float probability = Math.max(0.0F, this.extinguishProbability.calculate(ability.level()));

            if (probability > 0.0F && dragon.getRandom().nextDouble() < probability) {
                // drop_loot = false：火焰没有战利品表，与原技能此前的 dragonsurvival:block_break 行为等价
                level.destroyBlock(position, false, dragon);
            }
        } else if (AbstractCandleBlock.isLit(state)) {
            // 蜡烛 / 蜡烛蛋糕：原版自带熄灭音效、烟雾粒子与 BLOCK_CHANGE 游戏事件
            AbstractCandleBlock.extinguish(dragon, state, level, position);
        } else if (CampfireBlock.isLitCampfire(state)) {
            // 营火 / 灵魂营火：与原版 ThrownPotion#dowseFire 完全一致的三连
            level.levelEvent(null, LevelEvent.SOUND_EXTINGUISH_FIRE, position, 0);
            CampfireBlock.dowse(dragon, level, position, state);
            level.setBlockAndUpdate(position, state.setValue(CampfireBlock.LIT, false));
        }
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        float probability = Math.max(0.0F, this.extinguishProbability.calculate(ability.level()));

        // 概率不足 100% 时才追加说明（与 block_quake / DS 内置 potion 效果同写法）
        MutableComponent chanceText = probability < 1.0F
                ? Component.translatable("additional_abilities.ability.extinguish.chance",
                        DSColors.dynamicValue(Math.round(probability * 100.0F) + "%"))
                : Component.empty();

        return List.of(Component.translatable("additional_abilities.ability.extinguish.description", chanceText));
    }

    @Override
    public MapCodec<? extends AbilityBlockEffect> blockCodec() {
        return CODEC;
    }
}
