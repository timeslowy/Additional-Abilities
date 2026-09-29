package by.timeslowly.additional_abilities.common.ability.entity_effects;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 物品耐久的原版语义层 —— 「把这只物品栈的耐久改成多少 / 加减多少」这件事本身。
 * <p>
 * 本类<b>不认识</b>任何技能 JSON、槽位选择器或百分比基准：它只接收已经算成"耐久点"的数值，
 * 负责按原版规则落地。因此它可以被任何"消耗 / 修复耐久"的效果直接复用
 * （{@code additional_abilities:durability} 是第一个使用者）。
 * <p>
 * 与调用方的分工：
 * <ul>
 *     <li>调用方负责<b>效果的语义</b> —— 扫哪些槽位、匹配哪些物品、百分比按什么基准折算、随技能等级怎么放缩，
 *         以及"何时该走原版销毁路径"；</li>
 *     <li>本类负责<b>原版的语义</b> —— 「耐久」附魔减免、钳制到 {@code [0, maxDamage]}、销毁与破损回调。</li>
 * </ul>
 *
 * <h2>三个入口，三种强度</h2>
 * <table border="1">
 *     <caption>入口对比</caption>
 *     <tr><th>方法</th><th>语义</th><th>「耐久」附魔</th><th>耐久耗尽时</th></tr>
 *     <tr>
 *         <td>{@link #addDelta}</td>
 *         <td>相对增减（正修负磨）</td>
 *         <td>仅损耗时减免</td>
 *         <td><b>不销毁</b>，钳在 0（物品保留）</td>
 *     </tr>
 *     <tr>
 *         <td>{@link #setDurability}</td>
 *         <td>绝对设置</td>
 *         <td><b>不减免</b>（保证"设多少就是多少"）</td>
 *         <td>由 {@code breakItem} 决定</td>
 *     </tr>
 *     <tr>
 *         <td>{@link #consumeWithVanillaRules}</td>
 *         <td>完全按原版"消耗 N 点耐久"</td>
 *         <td>减免（由原版内部完成）</td>
 *         <td><b>总是销毁</b> + 破损回调</td>
 *     </tr>
 * </table>
 * 前两个入口自行处理，第三个整条委托 {@code ItemStack#hurtAndBreak}。
 * 之所以要分开而不是"一个方法带三个开关"，是因为第三个入口有一组<b>无法剥离</b>的原版副作用
 * （见下），不该在"只想精确改个数字"时被无辜带上。
 *
 * <h2>⚠️ 三条必须知道的原版事实</h2>
 * <ol>
 *     <li><b>1.21.1 没有"已损坏"状态</b>：全量检索原版源码，{@code ItemStack} <b>不存在</b> {@code isBroken()}。
 *         因此耐久被钳到 0 的物品<b>仍然可以正常使用</b>，只是它下一次正常损耗时会照常按原版规则销毁
 *         —— 文档与提示文案里都不能写成"已损坏不可用"。</li>
 *     <li><b>{@code hurtAndBreak} 的三个副作用</b>：正数会过 {@link EnchantmentHelper#processDurabilityChange}
 *         （受「耐久」附魔减免，甚至可能被完全抵消）；创造模式（{@code hasInfiniteMaterials()}）会<b>整段跳过</b>；
 *         累计到上限时 {@code shrink(1)} 销毁物品并回调。另外它还会触发成就判据
 *         {@code ITEM_DURABILITY_CHANGED} 与 {@code Item#damageItem} 钩子。</li>
 *     <li><b>「可以破坏」= {@code isDamageableItem()}</b>：它的实现是
 *         {@code has(MAX_DAMAGE) && !has(UNBREAKABLE) && has(DAMAGE)}，所以带 {@code unbreakable} 组件的物品
 *         天然被排除，调用方不需要再判一次。另外 {@code Item.Properties#durability(int)} 会同时写入
 *         {@code DAMAGE=0}，所以<b>全新未使用的工具</b>也满足该条件。</li>
 * </ol>
 */
public final class ItemDurability {
    private ItemDurability() {}

    /**
     * 相对增减物品耐久。<b>永不销毁物品</b>：结果钳在 {@code [0, maxDamage]}，耐久归零只是"留着不能用满值"。
     *
     * @param level                  服务端世界（{@link EnchantmentHelper#processDurabilityChange} 需要）
     * @param holder                 物品的持有者
     * @param stack                  物品栈本体，会被原地修改
     * @param equipmentSlot          该物品所在的装备槽；背包 / 爪牙槽没有对应项时传 {@code null}
     * @param deltaPoints            正数 = 恢复耐久，负数 = 损耗耐久（单位：耐久点）
     * @param reduceLossByUnbreaking 损耗时是否让「耐久」附魔减免
     * @return 是否真的改动了这只物品
     */
    public static boolean addDelta(final @NotNull ServerLevel level, final @NotNull LivingEntity holder,
                                   final @NotNull ItemStack stack, final @Nullable EquipmentSlot equipmentSlot,
                                   final int deltaPoints, final boolean reduceLossByUnbreaking) {
        int maxDamage = stack.getMaxDamage();

        if (!stack.isDamageableItem() || maxDamage <= 0) {
            return false;
        }

        int currentDurability = maxDamage - stack.getDamageValue();

        if (deltaPoints >= 0) {
            // 恢复：只是往 DAMAGE 组件里写一个更小的值，不牵涉任何原版损耗逻辑
            return setDurability(holder, stack, equipmentSlot, currentDurability + deltaPoints, false);
        }

        int loss = -deltaPoints;

        if (reduceLossByUnbreaking) {
            // 可能被完全抵消（附魔等级够高时 processDurabilityChange 会返回 0）→ 后面自然早退为"无改动"
            loss = Math.max(0, EnchantmentHelper.processDurabilityChange(level, stack, loss));
        }

        return setDurability(holder, stack, equipmentSlot, currentDurability - loss, false);
    }

    /**
     * 把物品耐久设为指定值（绝对值，单位：耐久点）。超出 {@code [0, maxDamage]} 的部分会被钳制。
     * <p>
     * <b>不做「耐久」附魔减免</b> —— "设为某个值"不是"消耗"，随机减免只会让设置结果与写下的值不一致。
     *
     * @param targetDurability 目标耐久；{@code <= 0} 表示"耐久耗尽"
     * @param breakItem        耐久耗尽时是否按原版销毁物品（{@code false} 则物品保留，
     *                         ⚠️ 而 1.21.1 没有"已损坏"状态，它<b>仍然可用</b>）
     * @return 是否真的改动了这只物品
     */
    public static boolean setDurability(final @NotNull LivingEntity holder, final @NotNull ItemStack stack,
                                        final @Nullable EquipmentSlot equipmentSlot, final int targetDurability,
                                        final boolean breakItem) {
        int maxDamage = stack.getMaxDamage();

        if (maxDamage <= 0) {
            return false;
        }

        int currentDurability = maxDamage - stack.getDamageValue();
        int clamped = Mth.clamp(targetDurability, 0, maxDamage);

        if (clamped <= 0 && breakItem) {
            destroy(holder, stack, equipmentSlot);
            return true;
        }

        if (clamped == currentDurability) {
            return false;
        }

        stack.setDamageValue(maxDamage - clamped);
        return true;
    }

    /**
     * 完全按原版规则消耗耐久：整条委托 {@code ItemStack#hurtAndBreak}。
     * <p>
     * 会一并带上原版的一组副作用（见类注释第 2 条）：「耐久」附魔减免、创造模式整段跳过、
     * 耐久耗尽即 {@code shrink(1)} 销毁、{@code onEquippedItemBroken} 回调，以及成就判据与
     * {@code Item#damageItem} 钩子。<b>调用方只在确实想要"原版式损耗"时才用它</b>。
     *
     * @param amount 要消耗的耐久点数（正数）
     * @return 是否真的改动了这只物品（被附魔全额抵消、创造模式跳过、或本就是非可损坏物品时为 {@code false}）
     */
    public static boolean consumeWithVanillaRules(final @NotNull ServerLevel level, final @NotNull LivingEntity holder,
                                                  final @NotNull ItemStack stack,
                                                  final @Nullable EquipmentSlot equipmentSlot, final int amount) {
        if (!stack.isDamageableItem() || amount <= 0) {
            return false;
        }

        int before = stack.getDamageValue();

        stack.hurtAndBreak(amount, level, holder, broken -> {
            if (equipmentSlot != null) {
                holder.onEquippedItemBroken(broken, equipmentSlot);
            }
        });

        // 销毁路径上 shrink(1) 会把栈清空，此时 getDamageValue() 不再有参考意义
        return stack.isEmpty() || stack.getDamageValue() != before;
    }

    /** 物品剩余耐久（耐久轴，与原版 {@code DAMAGE} 组件相反） */
    public static int currentDurability(final @NotNull ItemStack stack) {
        return stack.getMaxDamage() - stack.getDamageValue();
    }

    /**
     * 按原版方式销毁物品：{@code shrink(1)} + 破损回调。
     * <p>
     * 与 {@code ItemStack#hurtAndBreak} 的销毁路径等价；装备槽顺带触发
     * {@link LivingEntity#onEquippedItemBroken}，背包 / 爪牙槽没有可传的装备槽故跳过
     * （原版对这两类位置本来也没有对应的回调路径）。
     * <p>
     * ⚠️ 这条路径<b>不会</b>触发 NeoForge 的 {@code PlayerDestroyItemEvent} ——
     * 该事件只从 {@code ServerPlayerGameMode} 的挖掘流程里发出。
     */
    public static void destroy(final @NotNull LivingEntity holder, final @NotNull ItemStack stack,
                               final @Nullable EquipmentSlot equipmentSlot) {
        Item broken = stack.getItem();
        stack.shrink(1);

        if (equipmentSlot != null) {
            holder.onEquippedItemBroken(broken, equipmentSlot);
        }
    }
}
