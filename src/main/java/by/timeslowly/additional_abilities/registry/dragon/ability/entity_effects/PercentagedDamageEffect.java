package by.timeslowly.additional_abilities.registry.dragon.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.MiscCodecs;
import by.dragonsurvivalteam.dragonsurvival.common.handlers.magic.ClawToolHandler;
import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.ClawInventoryData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.dragonsurvivalteam.dragonsurvival.util.Expression;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 龙之技能实体效果：百分比伤害（{@code additional_abilities:percentaged_damage}）。
 * <p>
 * 与 {@code dragonsurvival:damage} 的区别：伤害量不再由 {@code amount} 直接给出，
 * 而是按目标生命值的百分比计算——被技能 target 选择器选中的实体
 * 依计算类型（最大 / 当前生命值）受到其百分比的伤害。
 * <p>
 * JSON 字段（与 effect_type 平级）：
 * <pre>
 * "damage_type":    "additional_abilities:counter_shock"                                // 必填，伤害类型（同 damage）
 * "percentage":     { "type": "minecraft:linear", "base": 0.25, "per_level_above_first": 0.05 }  // 必填，0~1 小数，计算后钳制为非负
 * "calculate_type": "max_health" / "current_health"                                    // 必填，计算类型
 * "scale":          属性 Holder                                                          // 可选，默认 dragonsurvival:dragon_ability_damage（同 damage）
 * "expression":     表达式字符串                                                         // 可选，默认 "amount * scale"（同 damage；amount = 百分比伤害基础值）
 * "use_claw":       布尔                                                                 // 可选，默认 false（同 damage，是否临时切换龙爪剑攻击判定）
 * </pre>
 * <p>
 * 仅作用于 {@link LivingEntity}；非生物目标（矿车、掉落物等）无生命值概念，直接跳过。
 */
public record PercentagedDamageEffect(Holder<DamageType> damageType, LevelBasedValue percentage, CalculateType calculateType, Holder<Attribute> scale, Expression expression, boolean useClaw) implements AbilityEntityEffect {
    public static final Expression DEFAULT_EXPRESSION = new Expression("amount * scale");

    /** 伤害百分比的计算基数：目标最大生命值 / 当前生命值 */
    public enum CalculateType implements StringRepresentable {
        MAX_HEALTH("max_health"),
        CURRENT_HEALTH("current_health");

        public static final Codec<CalculateType> CODEC = StringRepresentable.fromEnum(CalculateType::values);

        private final String serializedName;

        CalculateType(final String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public @NotNull String getSerializedName() {
            return serializedName;
        }
    }

    public static final MapCodec<PercentagedDamageEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            DamageType.CODEC.fieldOf("damage_type").forGetter(PercentagedDamageEffect::damageType),
            LevelBasedValue.CODEC.fieldOf("percentage").forGetter(PercentagedDamageEffect::percentage),
            CalculateType.CODEC.fieldOf("calculate_type").forGetter(PercentagedDamageEffect::calculateType),
            Attribute.CODEC.optionalFieldOf("scale", DSAttributes.DRAGON_ABILITY_DAMAGE).forGetter(PercentagedDamageEffect::scale),
            MiscCodecs.expressionCodec("amount", "scale").optionalFieldOf("expression", DEFAULT_EXPRESSION).forGetter(PercentagedDamageEffect::expression),
            Codec.BOOL.optionalFieldOf("use_claw", false).forGetter(PercentagedDamageEffect::useClaw)
    ).apply(instance, PercentagedDamageEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        // 仅作用于生物：非生物目标没有生命值概念
        if (!(target instanceof LivingEntity livingTarget)) {
            return;
        }

        boolean swap = swap(dragon);
        livingTarget.hurt(new DamageSource(damageType, dragon), calculate(dragon, livingTarget, ability.level()));

        // Used by 'OwnerHurtTargetGoal'
        dragon.setLastHurtMob(target);

        if (swap) {
            ClawInventoryData.getData(dragon).swapFinish(dragon);
        }
    }

    /** 基础伤害 = percentage × 目标生命值（按 calculate_type 取最大 / 当前），再经 expression（变量 amount / scale）计算最终值 */
    private float calculate(final @NotNull Player player, final LivingEntity target, final int abilityLevel) {
        // 计算值钳制为非负（满足 percentage >= 0 约束）
        float percentage = Math.max(0.0F, this.percentage.calculate(abilityLevel));
        float health = calculateType == CalculateType.MAX_HEALTH ? target.getMaxHealth() : target.getHealth();
        float base = percentage * health;

        // Sadly, we cannot re-use GeckoLib expressions since it calls 'MolangQueries' which loads client classes
        expression.setVariable("amount", new BigDecimal(base));
        expression.setVariable("scale", new BigDecimal(player.getAttributeValue(this.scale)));
        return expression.eval().floatValue();
    }

    private boolean swap(final Player player) {
        boolean swap = this.useClaw;

        if (swap) {
            ItemStack sword = ClawToolHandler.getDragonSword(player);

            if (!sword.isEmpty()) {
                ClawInventoryData.getData(player).swapStart(player, sword, ClawInventoryData.Slot.SWORD.ordinal());
            } else {
                swap = false;
            }
        }

        return swap;
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        int level = ability.level();
        float percentage = Math.max(0.0F, this.percentage.calculate(level));
        String percentageText = Math.round(percentage * 100.0F) + "%";
        // 计算方式（最大 / 当前生命值）以金色高亮，与蓝色的百分比数值区分，突出伤害计算基数
        MutableComponent calculateTypeText = DSColors.withColor(
                Component.translatable("additional_abilities.ability.percentaged_damage.type." + calculateType.getSerializedName()),
                DSColors.GOLD);

        MutableComponent description = Component.translatable("additional_abilities.ability.percentaged_damage.description",
                DSColors.dynamicValue(percentageText), calculateTypeText);

        return List.of(description);
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
