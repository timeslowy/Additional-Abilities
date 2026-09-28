package by.timeslowly.additional_abilities.registry.dragon.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.timeslowly.additional_abilities.common.ability.entity_effects.EnchantmentBonus;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * 龙之技能实体效果：临时附魔加成（{@code additional_abilities:enchantment_bonus}）。
 * <p>
 * 被选中的生物在<b>手持 / 身穿</b>（含龙生的爪牙槽）某条指定的附魔的<b>适用物品</b>时，
 * 该物品临时获得这条附魔；效果结束（或物品被挪出持有槽）即消失。
 * 等级不得超过该附魔自身的最大等级（常数越界在解码期直接报错，随技能等级浮动的等级在运行时钳制）；
 * 宝藏附魔<b>不禁用</b> —— 本效果给的是临时附魔，随时可被移除。
 *
 * <h2>JSON 字段（与 {@code effect_type} 平级）</h2>
 * <pre>
 * "enchantment_bonuses": [                       // 必填，至少一条；每条自带 base，形态对齐 dragonsurvival:harvest_bonus
 *   {
 *     "base": { "id": "…", "duration": { … } },  // 必填，时长实例基础数据（6 个字段见 DurationInstanceBase）
 *     "enchantments": [                          // 必填，至少一条
 *       { "enchantment": "minecraft:efficiency", "level": 3 },   // level 缺省为 1
 *       { "enchantment": "minecraft:unbreaking", "level": { "type": "minecraft:linear", "base": 1.0 } }
 *     ]
 *   }
 * ]
 * </pre>
 *
 * <h2>它是怎么生效的（关键在 NeoForge 的官方钩子）</h2>
 * 本效果<b>不改物品自己的 {@code ENCHANTMENTS} 组件</b>，而是：
 * <ol>
 *     <li>{@link EnchantmentBonus.Instance} 逐刻把「本效果给这只物品的附魔与等级」写进
 *         物品上的 {@code additional_abilities:enchantment_bonus} 组件（见 {@code StampedEnchantments}）；</li>
 *     <li>NeoForge 的 {@code GetEnchantmentLevelEvent} 在每次游戏性附魔等级查询时触发
 *         （{@code EnchantmentHelper} 的 {@code runIterationOnItem} / {@code hasTag} / {@code has(组件)}
 *         等出口都已被 NeoForge 改道到它），事件处理器把等级补进去。</li>
 * </ol>
 * 因此伤害、保护、挖掘速度、耐久与经验修补、弩 / 三叉戟 / 钓鱼、以及<b>附魔属性修饰符</b>
 * 都会按"真附魔"的方式生效，同时：
 * <ul>
 *     <li>铁砧 / 砂轮 / 修复合成 / {@code /enchant} 读的是 NBT，天然看不到它 ——
 *         不会出现"用假附魔骗砂轮经验"这类漏洞；</li>
 *     <li>附魔光效与原版提示行也不会出现（前者无钩子，后者由
 *         {@code common.eventhandler.abilities.EnchantmentBonusHandler} 自行补一行说明）。</li>
 * </ul>
 *
 * <h2>只作用于 {@link LivingEntity}</h2>
 * 非生物（掉落物、矿车……）没有装备槽可言，直接跳过；玩家与其它生物走同一套逻辑
 * （差别只在"爪牙槽"这一额外持有位置）。
 *
 * <h2>与龙的爪牙槽</h2>
 * 龙生并不让附魔 API 去认爪牙槽，而是<b>物理换手</b>：挖掘（{@code ServerPlayerGameModeStart/EndMixin}）
 * 与攻击（{@code PlayerStartMixin} / {@code PlayerEndMixin}）时把爪牙工具塞进主手，
 * 因此"使用的那一刻"爪牙工具就是主手物品，本效果自然生效；
 * 而在<b>不换手</b>的状态（例如 {@code ClawToolHandler} 里按 mending 判定经验修补）也需要生效，
 * 所以标记的扫描范围显式包含爪牙槽 4 格与换手期间寄存的原主手。
 */
public record EnchantmentBonusEffect(List<EnchantmentBonus> enchantmentBonuses) implements AbilityEntityEffect {
    public static final MapCodec<EnchantmentBonusEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            EnchantmentBonus.CODEC.listOf().fieldOf("enchantment_bonuses").forGetter(EnchantmentBonusEffect::enchantmentBonuses)
    ).apply(instance, EnchantmentBonusEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        if (!(target instanceof LivingEntity)) {
            return;
        }

        // 每条各自写入自己的时长实例（id 不同 → 互不覆盖）；同一技能等级与时长重复触发时
        // DurationInstanceBase#apply 会自行早退，因此这里不必做去重
        enchantmentBonuses.forEach(bonus -> bonus.apply(dragon, ability, target));
    }

    /**
     * 与 DS 的 {@code HarvestBonusEffect} 同口径：手动移除（{@code isAutoRemoval == false}）
     * 一律执行；自动移除时只有该条自己声明了 {@code should_remove_automatically} 才执行
     * （没声明的靠 {@code Storage#tick} 的时长到期回收）。
     */
    @Override
    public void remove(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity entity, final boolean isAutoRemoval) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }

        enchantmentBonuses.forEach(bonus -> {
            if (!isAutoRemoval || bonus.shouldRemoveAutomatically()) {
                bonus.remove(entity);
            }
        });
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        List<MutableComponent> descriptions = new ArrayList<>();

        for (EnchantmentBonus bonus : enchantmentBonuses) {
            // 该等级下一条有效附魔都没有时整条不显示（不占版面、也不产生空行）
            descriptions.addAll(bonus.getDescriptions(ability.level()));
        }

        return descriptions;
    }

    @Override
    public @NotNull @Unmodifiable List<ResourceLocation> getEffectIDs() {
        List<ResourceLocation> ids = new ArrayList<>(enchantmentBonuses.size());

        for (EnchantmentBonus bonus : enchantmentBonuses) {
            ids.add(bonus.id());
        }

        return ids;
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
