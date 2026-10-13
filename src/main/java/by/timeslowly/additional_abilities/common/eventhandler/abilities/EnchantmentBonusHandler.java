package by.timeslowly.additional_abilities.common.eventhandler.abilities;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus.EnchantmentBonus;
import by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus.EnchantmentBonusHolders;
import by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus.EnchantmentBonuses;
import by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus.EnchantmentBonusThreads;
import by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus.EnchantmentBonusThreads.Support;
import by.timeslowly.additional_abilities.common.ability.entity_effects.HeldItemSlots;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code additional_abilities:enchantment_bonus} 的消费端（服务端结算 + 客户端提示行）——
 * <b>查询期反查</b>：物品上不留"效果事实"，只在被查询的那一刻反查它现在归谁。
 *
 * <h2>一、为什么必须反查</h2>
 * NeoForge 把"游戏性附魔等级查询"全部接到了 {@link GetEnchantmentLevelEvent} 上
 * （{@code EnchantmentHelper#runIterationOnItem} 的两种重载、{@code hasTag}、
 * {@code has(DataComponentType)}、{@code getItemEnchantmentLevel} …），因此这里是本效果唯一的结算入口。
 * 但事件<b>只携带物品栈、不携带持有者</b>，而 1.21 里也没有任何办法从一只栈反查持有者
 * （{@code ItemStack#getEntityRepresentation()} 只由 {@code ItemFrame} / {@code ItemEntity} /
 * {@code Display} 设置；{@code ItemStack} 也不支持 {@code AttachmentType}）。
 * <p>
 * 于是本效果<b>反过来问</b>：遍历 {@link EnchantmentBonusHolders} 的候选实体（身上还挂着本效果实例的那些），
 * 用 {@link HeldItemSlots#holds} 按对象身份确认"这只栈确实在其持有槽里"，命中才用
 * {@link EnchantmentBonus#resolveApplied} 现算条目。
 * <p>
 * 由此得到一条重要的安全性质：<b>结算只取决于"此刻谁真的持有它"</b> ——
 * 物品转手他人、丢在地上、塞进箱子都不会再蹭到加成，而物品身上可能残留的脉冲标记
 * （见 {@code EnchantmentBonus.Instance#sweep}）既不参与结算也不参与显示，因此残留不可能误导。
 * 等级冲突用 {@code upgrade}（取较大值），与 DS 的 {@code HarvestBonuses} 取最大速度同口径 ——
 * 真实附魔比虚拟附魔高时不会被压低。
 *
 * <h2>二、怎么判断"现在是哪一侧"（这里很容易写出线程事故）</h2>
 * 客户端也要看到结果（提示行、客户端侧预测），因此不能简单地"只在服务端结算"；
 * 但也不能用 {@code FMLEnvironment.dist} —— 那是<b>物理端</b>，而单人游戏里物理客户端同时跑着
 * 逻辑客户端与逻辑服务端：按物理端一刀切会取错候选集（甚至让渲染线程摸到服务端实体）。
 * <p>
 * 判定收在 {@link EnchantmentBonusThreads}（T12 / F25），这里只消费它的结论：
 * <ul>
 *     <li>{@code SERVER} → 服务端候选集；</li>
 *     <li>{@code CLIENT} → 客户端候选集（客户端游戏线程）；</li>
 *     <li>{@code UNSUPPORTED} → <b>直接返回"无加成"</b>，不碰候选集、实体与附件。</li>
 * </ul>
 * 第三态是这条修复的要点：该事件由调用点直接 post，<b>不保证在主线程</b> ——
 * 原版重建创造模式搜索索引时就会在 {@code Worker-Main-*} 上问到它
 * （见 {@code EnchantmentBonusThreads} 的类注释）。把这种线程当成"客户端"
 * 会在客户端游戏线程正增删候选集的同时迭代它。
 *
 * <h2>三、提示行（{@link ItemTooltipEvent}）</h2>
 * 本效果<b>不动</b> {@code DataComponents.ENCHANTMENTS}，所以原版不会把虚拟附魔画进提示里
 * （NeoForge 的 {@code GetEnchantmentLevelEvent} javadoc 也明说不影响提示）。
 * 这里用与结算<b>完全相同</b>的判定自行补行：一行灰色说明 + 每个附魔一行
 * （用原版 {@code Enchantment#getFullname}，名称、颜色与罗马数字都与真附魔一致）。
 * 客户端只有"本地玩家"这一个候选实体（其余实体的实例不向客户端同步），
 * 因此实际表现就是"只在自己确实手持 / 身穿时才显示"。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public final class EnchantmentBonusHandler {
    private static final String TOOLTIP_HEADER = "additional_abilities.component.enchantment_bonus";

    private EnchantmentBonusHandler() {}

    @SubscribeEvent
    public static void onGetEnchantmentLevel(final @NotNull GetEnchantmentLevelEvent event) {
        ItemStack stack = event.getStack();

        // 早退：查询的不是一只具体物品（最便宜的一条，放在线程判定之前）
        if (stack.isEmpty()) {
            return;
        }

        // 本事件不保证在主线程上被 post（见类注释第二节）。不受支持的线程一律不结算 ——
        // 既不迭代候选集，也不读实体与附件
        Support support = EnchantmentBonusThreads.current();

        if (support == Support.UNSUPPORTED) {
            return;
        }

        boolean clientSide = support == Support.CLIENT;

        // 早退：该侧此刻没有任何活动实例（绝大多数查询走这条）
        if (EnchantmentBonusHolders.isEmpty(clientSide)) {
            return;
        }

        EnchantmentBonusHolders.forEach(clientSide, holder -> {
            for (EnchantmentBonus.Applied applied : appliedOn(holder, stack)) {
                // 事件可能只问某一个附魔（getEnchantmentLevel 路径），只在该前提下回应它
                if (!event.isTargetting(applied.enchantment())) {
                    continue;
                }

                event.getHolder(applied.enchantment()).ifPresent(enchantment -> event.getEnchantments().upgrade(enchantment, applied.level()));

            }
        });
    }

    @SubscribeEvent
    public static void onItemTooltip(final @NotNull ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();

        if (stack.isEmpty()) {
            return;
        }

        // 与结算入口同一条门控：提示行同样只在受支持线程上补行（理由见类注释第二节）。
        // 语言切换 / 创造模式搜索索引重建都会在工作线程上走到这里
        Support support = EnchantmentBonusThreads.current();

        if (support == Support.UNSUPPORTED) {
            return;
        }

        boolean clientSide = support == Support.CLIENT;

        if (EnchantmentBonusHolders.isEmpty(clientSide)) {
            return;
        }

        // 同一附魔被多个来源同时压上时只显示一行，取较大等级（与结算口径一致）
        Map<ResourceKey<Enchantment>, Integer> levels = new LinkedHashMap<>();

        EnchantmentBonusHolders.forEach(clientSide, holder -> {
            for (EnchantmentBonus.Applied applied : appliedOn(holder, stack)) {
                levels.merge(applied.enchantment(), applied.level(), Math::max);
            }
        });

        if (levels.isEmpty()) {
            return;
        }

        HolderLookup.Provider registries = event.getContext().registries();

        if (registries == null) {
            return;
        }

        HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookup(Registries.ENCHANTMENT).orElse(null);

        if (lookup == null) {
            return;
        }

        List<Component> tooltip = event.getToolTip();
        tooltip.add(Component.translatable(TOOLTIP_HEADER).withStyle(ChatFormatting.GRAY));

        for (Map.Entry<ResourceKey<Enchantment>, Integer> entry : levels.entrySet()) {
            lookup.get(entry.getKey()).ifPresent(enchantment -> tooltip.add(Enchantment.getFullname(enchantment, entry.getValue())));

        }
    }

    /**
     * 该实体此刻对这只物品真正给出的条目；<b>只有当它确实持有这只栈时</b>才非空。
     * <p>
     * 反查的核心一步：身份匹配让"容器里的同款物品"与"别人手里的同款物品"都不会误命中
     * （{@code ItemStack} 未覆写 {@code equals}，{@code ==} 即对象身份）。
     */
    private static @NotNull List<EnchantmentBonus.Applied> appliedOn(final @NotNull LivingEntity holder, final @NotNull ItemStack stack) {
        EnchantmentBonuses storage = holder.getExistingData(AAAttachments.ENCHANTMENT_BONUSES).orElse(null);

        if (storage == null || storage.isEmpty() || !HeldItemSlots.holds(holder, stack)) {
            return List.of();
        }

        List<EnchantmentBonus.Applied> applied = null;

        for (EnchantmentBonus.Instance instance : storage.all()) {
            List<EnchantmentBonus.Applied> fromInstance = EnchantmentBonus.resolveApplied(instance, stack);

            if (fromInstance.isEmpty()) {
                continue;
            }

            if (applied == null) {
                applied = new ArrayList<>(fromInstance.size());
            }

            applied.addAll(fromInstance);
        }

        return applied == null ? List.of() : applied;
    }
}
