package by.timeslowly.additional_abilities.registry.dragon.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.ClawInventoryData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import by.timeslowly.additional_abilities.common.ability.entity_effects.DurabilityChange;
import by.timeslowly.additional_abilities.common.ability.entity_effects.ItemDurability;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * 龙之技能实体效果：耐久设置（{@code additional_abilities:durability}）。
 * <p>
 * 被选中的生物在<b>指定槽位</b>里，凡是"有耐久值且可以破坏"的物品（必要时再用物品谓词筛一层），
 * 其耐久按指定值增减。
 *
 * <h2>本类只做"入口"这一件事</h2>
 * 本效果被有意拆成三块互不牵扯的关注点，便于分别演进与复用：
 * <ol>
 *     <li><b>本类</b> —— 效果入口：{@code effect_type} 的编解码、遍历规则、以及"改完之后要不要补同步"；</li>
 *     <li>{@link DurabilityChange}（{@code common/ability/entity_effects}）—— <b>一条规则</b>：
 *         JSON 形态与校验、槽位与物品匹配、{@code amount} 的折算、侧栏描述；</li>
 *     <li>{@link ItemDurability}（同包）—— <b>原版语义</b>：「耐久」附魔减免、钳制、销毁与破损回调。
 *         它不认识本效果的 JSON，可被任何"消耗 / 修复耐久"的效果直接复用。</li>
 * </ol>
 *
 * <h2>JSON 字段（与 {@code effect_type} 平级）</h2>
 * <pre>
 * "durability_changes": [                          // 必填，至少一条
 *   {
 *     "slots":     ["mainhand", "claw_sword"],     // 必填，至少一项（见下方槽位表）
 *     "item":      { "items": "#minecraft:swords" },  // 可选；省略 = 该槽位内全部可损坏物品
 *     "operation": "add",                          // 必填：add（加减，正数修 / 负数磨）/ set（设为指定值）
 *     "amount":    { "type": "minecraft:linear", "base": -0.25 },   // 必填，随技能等级计算
 *     "unit":      "percent",                      // 必填：durability（耐久点）/ percent（百分比）
 *     "percent_base": "max_durability",            // unit = percent 时必填：max_durability / current_durability
 *     "break_item": false                          // 可选，默认 false：结果耐久归零时是否按原版销毁物品
 *   }
 * ]
 * </pre>
 * 各字段的语义、换算方式与「耐久」附魔的施加范围见 {@link DurabilityChange}。
 *
 * <h2>槽位（{@code slots}）可选值</h2>
 * <ul>
 *     <li>{@code mainhand} / {@code offhand} —— 主手 / 副手；</li>
 *     <li>{@code head} / {@code chest} / {@code legs} / {@code feet} / {@code body} ——
 *         头盔 / 胸甲 / 护腿 / 靴子 / 动物护甲槽（马铠、狼铠）；</li>
 *     <li>{@code equipment} —— 以上全部装备槽；</li>
 *     <li>{@code hotbar} / {@code inventory} —— 快捷栏 9 格 / 背包 36 格（含快捷栏）；</li>
 *     <li>{@code claw_sword} / {@code claw_pickaxe} / {@code claw_axe} / {@code claw_shovel} ——
 *         龙生爪牙槽的单个格子；{@code claws} —— 爪牙槽 4 格；</li>
 *     <li>{@code all} —— 装备槽 + 背包 36 格 + 爪牙槽 4 格。</li>
 * </ul>
 * 完整实现见 {@code HeldItemSlots.SlotSelector}。
 *
 * <h2>为什么是"纯瞬时"（不进 DS 的时长实例族）</h2>
 * 本效果没有需要跨刻维持的状态：每次 {@link #apply} 就是一次独立结算。
 * 因此它<b>不建附件、不存实例、不占用技能效果 HUD</b>，也不带来任何随实体同步的注册项
 * （{@code PROTOCOL_VERSION} 无需递增）。触发频次完全由技能 JSON 决定 —— 见下一条。
 * <p>
 * 与之相对，{@code additional_abilities:enchantment_bonus} 是时长实例族：它必须逐刻维持
 * 物品上的标记，所以走了 {@code DurationInstanceBase} 那套。
 *
 * <h2>⚠️ 触发频次由技能决定：被动技能务必写 trigger_rate</h2>
 * {@code ActionContainer} 的 {@code trigger_rate} <b>默认是 1（每 tick 一次）</b>。
 * 把它挂在被动技能上而不写 {@code trigger_rate}，效果会每刻结算一次 ——
 * 修复类瞬间拉满、磨损类瞬间掏空。默认值下最安全的用法是主动技能（每次施法结算一次）。
 *
 * <h2>同步（哪些要手动、哪些不用）</h2>
 * <ul>
 *     <li><b>装备槽</b>：原版 {@code LivingEntity#tick} 里的装备变更检测会发现组件变化并广播
 *         {@code ClientboundSetEquipmentPacket}，<b>无需</b>手动通知；</li>
 *     <li><b>玩家背包 36 格</b>：属于 {@code InventoryMenu}，原版每刻自己比对并同步，<b>无需</b>手动通知；</li>
 *     <li><b>龙生爪牙槽</b>：<b>不在任何容器菜单里</b>，必须 {@code ClawInventoryData#sync(player)}。</li>
 * </ul>
 *
 * <h2>换手瞬时的槽位归属按字面判定</h2>
 * 龙生在挖掘 / 攻击的那一瞬会做物理换手（见 {@code ClawInventoryData}），
 * 此时爪牙工具在主手、原主手被寄存到槽外。本效果<b>不还原</b>这次暂换：
 * 主手就是主手，寄存的原主手不在扫描范围内（它不在任何真实槽位里）。
 * 纯瞬时效果通常在施法瞬间结算，撞上这 1 刻的概率极低。
 */
public record DurabilityEffect(List<DurabilityChange> durabilityChanges) implements AbilityEntityEffect {
    public static final MapCodec<DurabilityEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            DurabilityChange.CODEC.listOf()
                    .validate(list -> list.isEmpty()
                            ? DataResult.error(() -> "'durability_changes' must contain at least one entry")
                            : DataResult.success(list))
                    .fieldOf("durability_changes").forGetter(DurabilityEffect::durabilityChanges)
    ).apply(instance, DurabilityEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        // 非生物没有装备槽可言（掉落物、矿车……），直接跳过
        if (!(target instanceof LivingEntity holder)) {
            return;
        }

        // apply 只会在服务端被调用，这里仍是显式判定而非强转：
        // 数据包的加载条件、GameTest 等路径都可能把实体放进一个非服务端的 Level
        if (!(holder.level() instanceof ServerLevel level)) {
            return;
        }

        int abilityLevel = ability.level();
        boolean clawTouched = false;

        for (DurabilityChange change : durabilityChanges) {
            // 每条规则自己扫槽位、自己按栈身份去重（见 DurabilityChange#apply 的说明）
            if (change.apply(level, holder, abilityLevel)) {
                clawTouched = true;
            }
        }

        // 只有爪牙槽需要手动同步：它不在任何容器菜单里，原版那条"每刻比对容器格"的路走不到它。
        // 装备槽与背包由原版自己处理（见类注释）。整批规则都结算完再同步一次，避免同刻重复发包。
        if (clawTouched && holder instanceof Player player && DragonStateProvider.isDragon(player)) {
            ClawInventoryData.getData(player).sync(player);
        }
    }

    @Override
    public @NotNull @Unmodifiable List<MutableComponent> getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        int level = ability.level();
        List<MutableComponent> descriptions = new ArrayList<>(durabilityChanges.size());

        for (DurabilityChange change : durabilityChanges) {
            descriptions.add(change.describe(level));
        }

        return descriptions;
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
