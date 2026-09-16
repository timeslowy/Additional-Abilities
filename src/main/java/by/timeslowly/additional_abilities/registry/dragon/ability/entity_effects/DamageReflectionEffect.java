package by.timeslowly.additional_abilities.registry.dragon.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.timeslowly.additional_abilities.common.ability.DamageReflections;
import by.timeslowly.additional_abilities.common.eventhandler.abilities.DamageReflectionEventHandler;
import com.mojang.serialization.Codec;
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

/**
 * 龙之技能实体效果：伤害反弹（类似原版荆棘附魔）。
 * <p>
 * 技能目标（通常为 self 被动技能的施法者本人）受到伤害时，
 * 按比例将所受伤害反弹给自身周围一定范围内的敌对实体。
 * 仅作用于玩家，作用于非玩家实体时直接跳过。
 * <p>
 * 本效果只负责把按技能等级计算后的参数写入目标附件
 * {@link DamageReflections}；实际反弹结算由
 * {@link DamageReflectionEventHandler} 完成。
 * <p>
 * JSON 字段（与 effect_type 平级）：
 * <pre>
 * "reflection_percentage": { "type": "minecraft:linear", "base": 0.25, "per_level_above_first": 0.05 }  // 必填，0~1 小数
 * "reflection_range":      { "type": "minecraft:linear", "base": 8.0,  "per_level_above_first": 1.0  }  // 必填，计算后取整
 * "use_same_damage_type":  false  // 可选，默认 false；为 true 时沿用原始伤害类型，否则用 additional_abilities:counter_shock
 * </pre>
 */
public record DamageReflectionEffect(LevelBasedValue reflectionPercentage, LevelBasedValue reflectionRange, boolean useSameDamageType) implements AbilityEntityEffect {
    public static final MapCodec<DamageReflectionEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LevelBasedValue.CODEC.fieldOf("reflection_percentage").forGetter(DamageReflectionEffect::reflectionPercentage),
            LevelBasedValue.CODEC.fieldOf("reflection_range").forGetter(DamageReflectionEffect::reflectionRange),
            Codec.BOOL.optionalFieldOf("use_same_damage_type", false).forGetter(DamageReflectionEffect::useSameDamageType)
    ).apply(instance, DamageReflectionEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        // 仅作用于玩家：作用于非玩家实体时直接跳过
        if (!(target instanceof Player)) {
            return;
        }

        int level = ability.level();
        // 计算值钳制为非负（满足 >= 0 约束）
        float percentage = Math.max(0.0F, reflectionPercentage.calculate(level));
        int range = Math.max(0, (int) reflectionRange.calculate(level));

        // 记录写入时的游戏刻，供事件处理器做有效期判定（见 DamageReflections#REFLECTION_TTL_TICKS）
        long appliedTick = target.level().getGameTime();
        DamageReflections.getData(target).set(ability.id(), new DamageReflections.Entry(percentage, range, useSameDamageType, appliedTick));
    }

    @Override
    public void remove(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity entity, final boolean isAutoRemoval) {
        if (!(entity instanceof Player)) {
            return;
        }

        // 主动移除与自动移除（isAutoRemoval）均立即清除：
        // 本附件是瞬态注册，靠施法期间/被动周期刷新维持，无自身过期机制，必须在此清理。
        // 注意：DS 的 remove 回调仅对被动常驻 self 技能可靠（setActive 停止主动技能时不会调用），
        // 其余场景依赖 DamageReflections 的有效期机制兜底，勿恢复「isAutoRemoval 时跳过」的写法。
        DamageReflections.getData(entity).remove(ability.id());
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        int level = ability.level();
        String percentage = Math.round(Math.max(0.0F, reflectionPercentage.calculate(level)) * 100.0F) + "%";
        int range = Math.max(0, (int) reflectionRange.calculate(level));

        MutableComponent description =  Component.translatable("additional_abilities.ability.damage_reflection.description",
                DSColors.dynamicValue(percentage), DSColors.dynamicValue(range));

        return List.of(description);
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
