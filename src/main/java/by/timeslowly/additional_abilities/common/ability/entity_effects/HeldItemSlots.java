package by.timeslowly.additional_abilities.common.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.ClawInventoryData;
import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「持有中的物品」枚举 —— 本模组所有"按槽位找物品"的效果共用的单一真源。
 *
 * <h2>扫描范围</h2>
 * <ul>
 *     <li><b>装备槽</b>（主手 / 副手 / 头盔 / 胸甲 / 护腿 / 靴子 / 动物护甲，即 {@code EquipmentSlot.values()}）——
 *         <b>手持 / 身穿</b>的本体；</li>
 *     <li><b>龙生的爪牙槽</b>（4 格：剑 / 镐 / 斧 / 锹）—— 见下方"爪牙槽"说明；</li>
 *     <li><b>玩家背包 36 格</b>（前 9 格即快捷栏）。</li>
 * </ul>
 *
 * <h2>两个入口：{@link #of} 与 {@link #allOf}</h2>
 * <ul>
 *     <li>{@link #of}（<b>去重</b>）—— 按 {@code ItemStack} 的<b>对象身份</b>去重，同一只栈只出现一次。
 *         "这只物品该不该带某个标记"这类<b>幂等</b>查询用它：重复处理没有意义，只会造成多余写入与同步
 *         （{@code additional_abilities:enchantment_bonus} 走这条）。</li>
 *     <li>{@link #allOf}（<b>不去重</b>）—— 每个真实的格子各出一条记录。
 *         "按格子的语义施加一次变更"这类<b>非幂等</b>操作必须用它：既不漏掉"被去重吞掉"的格子
 *         （例如选中的快捷栏物品同时是主手），也让调用方自己用身份集合决定"同一只栈只改一次"。
 *         （{@code additional_abilities:durability} 走这条。）</li>
 * </ul>
 * 两个入口都<b>跳过空格子</b>（对空格子做任何事都没有意义）。
 *
 * <h2>爪牙槽为什么必须单独扫</h2>
 * DS 的爪牙槽是挂在玩家上的 {@link SimpleContainer} 附件，<b>既不是 {@code EquipmentSlot}，
 * 也不属于任何 {@code AbstractContainerMenu}</b> —— 也就是说不换手时它不在任何原版枚举得到的地方。
 * <p>
 * 而 DS 解决"爪牙工具要让附魔 / 附魔效果生效"的办法是<b>物理换手</b>
 * （{@link ClawInventoryData#swapStart} 把爪牙工具塞进主手、原主手寄存到
 * {@code storedMainHandTool}），所以挖掘 / 攻击那一刻爪牙工具<b>就是主手物品</b>。
 * 但非换手状态的判定（例如 {@code ClawToolHandler} 里 mending 的经验修补）
 * 仍然直接查爪槽里的那只栈，因此这里必须主动把它扫进来。
 *
 * <h2>两个容易踩的点</h2>
 * <ol>
 *     <li><b>快捷栏与主手是同一只栈</b>：{@code Player#getMainHandItem()} 就是
 *         {@code getInventory().items} 里选中的那一格。{@link #of} 用<b>身份去重</b>
 *         （{@code ItemStack} 未覆写 {@code equals}，天然按引用比较）并且<b>先处理持有槽、后处理背包</b>，
 *         于是选中的那格在结果里标记为 {@link SlotKind#MAINHAND} 而不是 {@link SlotKind#HOTBAR} ——
 *         这正是"持有优先"的语义。</li>
 *     <li><b>换手期间原主手被寄存到了槽外</b>：{@code switchStart} 期间原主手既不在装备槽、
 *         也不在爪槽，而是躺在 {@code ClawInventoryData.storedMainHandTool}。
 *         {@link #of} 会把它当作"持有中"补进去（否则 {@code enchantment_bonus} 的标记会在换手那一瞬
 *         被撤销、换回来时再重建，造成同步刷屏）；{@link #allOf} <b>不</b>包含它 ——
 *         它此刻<b>不在任何一个真实槽位里</b>，对"按槽位施加变更"的效果而言不算数。</li>
 * </ol>
 *
 * <h2>非玩家生物</h2>
 * 只有 {@code EquipmentSlot.values()}（含马 / 狼的 {@link SlotKind#BODY} 槽）；
 * 它们没有背包与爪牙槽的概念。
 */
public final class HeldItemSlots {
    /** 扫描来源：决定改动后要用哪种方式通知客户端 */
    public enum Source {
        /** 背包 36 格 —— 属于容器菜单，原版每刻自己会同步，无需额外处理 */
        INVENTORY,
        /** 装备槽 —— 原版 {@code LivingEntity#tick} 里的装备变更检测会自己广播，无需额外处理 */
        EQUIPMENT,
        /** 龙生爪牙槽 —— 不在任何容器菜单里，需要 {@code ClawInventoryData#sync(player)} */
        CLAW
    }

    /**
     * <b>精确</b>槽位标识：一个格子只对应一个值。
     * <p>
     * 与面向 JSON 的 {@link SlotSelector} 区分开 —— 后者是"选择器"，含 {@code equipment} / {@code claws} /
     * {@code all} 这类<b>分组</b>，一个选择器可以命中多个 {@code SlotKind}。
     */
    public enum SlotKind {
        MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET, BODY,
        HOTBAR, INVENTORY,
        CLAW_SWORD, CLAW_PICKAXE, CLAW_AXE, CLAW_SHOVEL;

        /** 该槽位对应的原版装备槽；背包与爪牙槽没有对应项，返回 {@code null} */
        public @Nullable EquipmentSlot equipmentSlot() {
            return switch (this) {
                case MAINHAND -> EquipmentSlot.MAINHAND;
                case OFFHAND -> EquipmentSlot.OFFHAND;
                case HEAD -> EquipmentSlot.HEAD;
                case CHEST -> EquipmentSlot.CHEST;
                case LEGS -> EquipmentSlot.LEGS;
                case FEET -> EquipmentSlot.FEET;
                case BODY -> EquipmentSlot.BODY;
                // 背包 / 快捷栏 / 爪牙槽：原版与 DS 都没有与之对应的 EquipmentSlot，
                // 因此"物品损坏回调"这类需要槽位参数的 API 只能退化为不回调
                case HOTBAR, INVENTORY, CLAW_SWORD, CLAW_PICKAXE, CLAW_AXE, CLAW_SHOVEL -> null;
            };
        }

        private static @NotNull SlotKind of(final @NotNull EquipmentSlot slot) {
            return switch (slot) {
                case MAINHAND -> MAINHAND;
                case OFFHAND -> OFFHAND;
                case HEAD -> HEAD;
                case CHEST -> CHEST;
                case LEGS -> LEGS;
                case FEET -> FEET;
                case BODY -> BODY;
            };
        }
    }

    /**
     * 面向 JSON 的槽位选择器（{@code slots} 字段的可用值）。
     * <p>
     * 序列化名即枚举名的小写形式，与 {@link SlotKind} 中同名者语义一致；
     * 另有三类分组：{@code equipment}（全部装备槽）、{@code claws}（爪牙槽 4 格）、
     * {@code all}（装备槽 + 背包 36 格 + 爪牙槽 4 格）。
     * <p>
     * {@code hotbar} 只命中快捷栏 9 格本身；其中"选中的那一格"在 {@link #allOf} 里还会以
     * {@link SlotKind#MAINHAND} 再出现一次（它是同一只栈）—— 调用方按栈身份去重即可避免重复施加。
     */
    public enum SlotSelector implements StringRepresentable {
        MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET, BODY,
        EQUIPMENT, HOTBAR, INVENTORY,
        CLAW_SWORD, CLAW_PICKAXE, CLAW_AXE, CLAW_SHOVEL, CLAWS,
        ALL;

        public static final Codec<SlotSelector> CODEC = StringRepresentable.fromEnum(SlotSelector::values);

        /** 本选择器是否命中某个精确槽位 */
        public boolean matches(final @NotNull SlotKind kind) {
            return switch (this) {
                // 与 SlotKind 同名的 7 个装备槽 + 4 个爪牙槽：按名字一一对应
                case MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET, BODY,
                     CLAW_SWORD, CLAW_PICKAXE, CLAW_AXE, CLAW_SHOVEL -> kind.name().equals(name());
                case EQUIPMENT -> kind.equipmentSlot() != null;
                case HOTBAR -> kind == SlotKind.HOTBAR;
                case INVENTORY -> kind == SlotKind.HOTBAR || kind == SlotKind.INVENTORY;
                case CLAWS -> kind == SlotKind.CLAW_SWORD || kind == SlotKind.CLAW_PICKAXE
                        || kind == SlotKind.CLAW_AXE || kind == SlotKind.CLAW_SHOVEL;
                case ALL -> true;
            };
        }

        @Override
        public @NotNull String getSerializedName() {
            return name().toLowerCase(Locale.ENGLISH);
        }
    }

    /**
     * @param stack  物品栈本体（<b>是原对象</b>，可直接原地改组件）
     * @param held   是否算"手持 / 身穿"（决定该不该带标记）
     * @param source 所在来源（决定改动后如何同步）
     * @param kind   精确槽位
     */
    public record HeldSlot(@NotNull ItemStack stack, boolean held, @NotNull Source source, @NotNull SlotKind kind) {}

    private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();

    /** 索引与 {@link ClawInventoryData.Slot} 的枚举顺序（剑 / 镐 / 斧 / 锹）一一对应 */
    private static final SlotKind[] CLAW_KINDS = {
            SlotKind.CLAW_SWORD, SlotKind.CLAW_PICKAXE, SlotKind.CLAW_AXE, SlotKind.CLAW_SHOVEL
    };

    /** 背包 36 格中前 9 格为快捷栏（{@code Inventory#items} 的既定布局） */
    private static final int HOTBAR_SIZE = 9;

    private HeldItemSlots() {}

    /** 目标实体的全部相关格子，<b>按物品栈身份去重</b>（持有槽在前、背包在后） */
    public static @NotNull List<HeldSlot> of(final @NotNull LivingEntity entity) {
        List<HeldSlot> slots = new ArrayList<>();
        Set<ItemStack> seen = new HashSet<>();

        // ① 装备槽（含主手）：手持 / 身穿的本体
        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS) {
            add(slots, seen, entity.getItemBySlot(equipmentSlot), true, Source.EQUIPMENT, SlotKind.of(equipmentSlot));
        }

        // ② 龙生的爪牙槽与换手期间寄存的原主手（仅龙形态玩家有这一套）
        if (entity instanceof Player player && DragonStateProvider.isDragon(player)) {
            ClawInventoryData claws = ClawInventoryData.getData(player);
            SimpleContainer container = claws.getContainer();

            for (int index = 0; index < container.getContainerSize(); index++) {
                add(slots, seen, container.getItem(index), true, Source.CLAW, clawKind(index));
            }

            if (claws.switchedTool) {
                // 换手中的原主手：它此刻不在任何槽位里，但换回来就是主手物品，
                // 因此按主手处理（避免标记在换手来回时被来回增删）
                add(slots, seen, claws.storedMainHandTool, true, Source.EQUIPMENT, SlotKind.MAINHAND);
            }
        }

        // ③ 背包：只用于"挪出持有槽即撤销标记"，所以放在最后（持有优先）
        if (entity instanceof Player player) {
            for (ItemStack stack : player.getInventory().items) {
                add(slots, seen, stack, false, Source.INVENTORY, SlotKind.INVENTORY);
            }
        }

        return slots;
    }

    /**
     * 目标实体的<b>全部格子逐格列出</b>，不去重、不合并。
     * <p>
     * 与 {@link #of} 的差别有三处：① 不去重（选中的快捷栏物品会同时以
     * {@link SlotKind#MAINHAND} 与 {@link SlotKind#HOTBAR} 出现两次）；
     * ② 背包格区分快捷栏与主背包（{@link SlotKind#HOTBAR} / {@link SlotKind#INVENTORY}）；
     * ③ <b>不含</b>换手期间寄存的原主手（它此刻不在任何真实槽位里）。
     */
    public static @NotNull List<HeldSlot> allOf(final @NotNull LivingEntity entity) {
        List<HeldSlot> slots = new ArrayList<>();

        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS) {
            addRaw(slots, entity.getItemBySlot(equipmentSlot), true, Source.EQUIPMENT, SlotKind.of(equipmentSlot));
        }

        if (entity instanceof Player player && DragonStateProvider.isDragon(player)) {
            SimpleContainer container = ClawInventoryData.getData(player).getContainer();

            for (int index = 0; index < container.getContainerSize(); index++) {
                addRaw(slots, container.getItem(index), true, Source.CLAW, clawKind(index));
            }
        }

        if (entity instanceof Player player) {
            List<ItemStack> items = player.getInventory().items;

            for (int index = 0; index < items.size(); index++) {
                addRaw(slots, items.get(index), false, Source.INVENTORY,
                        index < HOTBAR_SIZE ? SlotKind.HOTBAR : SlotKind.INVENTORY);
            }
        }

        return slots;
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

    /**
     * 这只栈是否正被该实体<b>持有 / 身穿</b>（装备槽、爪牙槽、换手期间寄存的原主手）。
     * <p>
     * 与 {@link #of} 的持有范围一致，但按<b>对象身份</b>比较、且不分配中间列表 ——
     * 供"每一次附魔查询都要确认物品归属"的 {@code additional_abilities:enchantment_bonus} 使用。
     * 刻意不用按值相等（{@code ItemStack#matches}）：那会误命中容器里 / 别人手里的同款物品。
     */
    public static boolean holds(final @NotNull LivingEntity entity, final @NotNull ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS) {
            if (entity.getItemBySlot(equipmentSlot) == stack) {
                return true;
            }
        }

        if (entity instanceof Player player && DragonStateProvider.isDragon(player)) {
            ClawInventoryData claws = ClawInventoryData.getData(player);
            SimpleContainer container = claws.getContainer();

            for (int index = 0; index < container.getContainerSize(); index++) {
                if (container.getItem(index) == stack) {
                    return true;
                }
            }

            // 换手中的原主手：此刻不在任何槽位里，但换回来就是主手物品（与 of 的口径一致）
            return claws.switchedTool && claws.storedMainHandTool == stack;
        }

        return false;
    }

    private static @NotNull SlotKind clawKind(final int index) {
        if (index < 0 || index >= CLAW_KINDS.length) {
            // 容器尺寸理论上恒为 4，这里只是防御：索引越界时不猜槽位，按第一个爪槽归类
            return SlotKind.CLAW_SWORD;
        }

        return CLAW_KINDS[index];
    }

    private static void add(final @NotNull List<HeldSlot> slots, final @NotNull Set<ItemStack> seen,
                            final @NotNull ItemStack stack, final boolean held, final @NotNull Source source,
                            final @NotNull SlotKind kind) {
        if (stack.isEmpty() || !seen.add(stack)) {
            return;
        }

        slots.add(new HeldSlot(stack, held, source, kind));
    }

    private static void addRaw(final @NotNull List<HeldSlot> slots, final @NotNull ItemStack stack,
                               final boolean held, final @NotNull Source source, final @NotNull SlotKind kind) {
        if (stack.isEmpty()) {
            return;
        }

        slots.add(new HeldSlot(stack, held, source, kind));
    }
}
