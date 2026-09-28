package by.timeslowly.additional_abilities.common.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.ClawInventoryData;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「持有中的物品」枚举 —— {@code additional_abilities:enchantment_bonus} 逐刻校准标记时的扫描对象。
 *
 * <h2>扫描范围</h2>
 * <ul>
 *     <li><b>装备槽</b>（主手 / 副手 / 头盔 / 胸甲 / 护腿 / 靴子，即 {@code EquipmentSlot.values()}）——
 *         <b>手持 / 身穿</b>的本体；</li>
 *     <li><b>龙生的爪牙槽</b>（4 格：剑 / 镐 / 斧 / 锹）—— 见下方"爪牙槽"说明；</li>
 *     <li><b>玩家背包 36 格</b>—— 只需要在<b>不持有</b>时把它扫出来，用于撤销"挪走即失效"的标记。</li>
 * </ul>
 *
 * <h2>爪牙槽为什么必须单独扫</h2>
 * DS 的爪牙槽是挂在玩家上的 {@link SimpleContainer} 附件，<b>既不是 {@code EquipmentSlot}，
 * 也不属于任何 {@code AbstractContainerMenu}</b> —— 也就是说不换手时它不在任何原版枚举得到的地方。
 * <p>
 * 而 DS 解决"爪牙工具要让附魔生效"的办法是<b>物理换手</b>
 * （{@link ClawInventoryData#swapStart} 把爪牙工具塞进主手、原主手寄存到
 * {@code storedMainHandTool}），所以挖掘 / 攻击那一刻爪牙工具<b>就是主手物品</b>。
 * 但非换手状态的判定（例如 {@code ClawToolHandler} 里 mending 的经验修补）
 * 仍然直接查爪槽里的那只栈，因此这里必须主动把它扫进来打标记。
 *
 * <h2>两个容易踩的点</h2>
 * <ol>
 *     <li><b>快捷栏与主手是同一只栈</b>：{@code Player#getMainHandItem()} 就是
 *         {@code getInventory().items} 里选中的那一格。若两处各处理一次，
 *         "先按背包撤销、再按主手重建"会让标记每刻反复横跳（同步刷屏）。
 *         因此这里用<b>身份去重</b>（{@code ItemStack} 未覆写 {@code equals}，天然按引用比较）
 *         并且<b>先处理持有槽、后处理背包</b>。</li>
 *     <li><b>换手期间原主手被寄存到了槽外</b>：{@code switchStart} 期间原主手既不在装备槽、
 *         也不在爪槽，而是躺在 {@code ClawInventoryData.storedMainHandTool}。
 *         不扫它会让它的标记在换手那一瞬被撤销、换回来时再重建（同样是刷屏）。</li>
 * </ol>
 *
 * <h2>非玩家生物</h2>
 * 只扫 {@code EquipmentSlot.values()}（含马 / 狼的 {@code BODY} 槽）；它们没有背包与爪牙槽的概念。
 */
public final class HeldItemSlots {
    /** 扫描来源：决定标记变化后要用哪种方式通知客户端 */
    public enum Source {
        /** 背包 36 格 —— 属于容器菜单，原版每刻自己会同步，无需额外处理 */
        INVENTORY,
        /** 装备槽 —— 需要 {@code LivingEntity#detectEquipmentUpdates()} 才会广播 */
        EQUIPMENT,
        /** 龙生爪牙槽 —— 不在任何容器菜单里，需要 {@code ClawInventoryData#sync(player)} */
        CLAW
    }

    /**
     * @param stack  物品栈本体（<b>是原对象</b>，可直接原地改组件）
     * @param held   是否算"手持 / 身穿"（决定该不该带标记）
     * @param source 所在来源（决定标记变化后如何同步）
     */
    public record HeldSlot(@NotNull ItemStack stack, boolean held, @NotNull Source source) {}

    private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();

    private HeldItemSlots() {}

    /** 目标实体的全部相关格子（持有槽在前、背包在后，已按物品栈身份去重） */
    public static @NotNull List<HeldSlot> of(final @NotNull LivingEntity entity) {
        List<HeldSlot> slots = new ArrayList<>();
        Set<ItemStack> seen = new HashSet<>();

        // ① 装备槽（含主手）：手持 / 身穿的本体
        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS) {
            add(slots, seen, entity.getItemBySlot(equipmentSlot), true, Source.EQUIPMENT);
        }

        // ② 龙生的爪牙槽与换手期间寄存的原主手（仅龙形态玩家有这一套）
        if (entity instanceof Player player && DragonStateProvider.isDragon(player)) {
            ClawInventoryData claws = ClawInventoryData.getData(player);
            SimpleContainer container = claws.getContainer();

            for (int index = 0; index < container.getContainerSize(); index++) {
                add(slots, seen, container.getItem(index), true, Source.CLAW);
            }

            if (claws.switchedTool) {
                // 换手中的原主手：它此刻不算"被持有"的槽位，但换回来就是主手物品，
                // 因此按持有处理（避免标记在换手来回时被来回增删）
                add(slots, seen, claws.storedMainHandTool, true, Source.EQUIPMENT);
            }
        }

        // ③ 背包：只用于"挪出持有槽即撤销标记"，所以放在最后（持有优先）
        if (entity instanceof Player player) {
            for (ItemStack stack : player.getInventory().items) {
                add(slots, seen, stack, false, Source.INVENTORY);
            }
        }

        return slots;
    }

    private static void add(final @NotNull List<HeldSlot> slots, final @NotNull Set<ItemStack> seen,
                            final @NotNull ItemStack stack, final boolean held, final @NotNull Source source) {
        if (stack.isEmpty() || !seen.add(stack)) {
            return;
        }

        slots.add(new HeldSlot(stack, held, source));
    }

    /** 只取"手持 / 身穿"的那些格子（不需要背包撤销逻辑时用） */
    public static @NotNull List<HeldSlot> held(final @NotNull LivingEntity entity) {
        List<HeldSlot> result = new ArrayList<>();

        for (HeldSlot slot : of(entity)) {
            if (slot.held()) {
                result.add(slot);
            }
        }

        return result;
    }
}
