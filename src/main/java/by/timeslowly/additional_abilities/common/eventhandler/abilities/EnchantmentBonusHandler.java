package by.timeslowly.additional_abilities.common.eventhandler.abilities;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.entity_effects.EnchantmentBonuses;
import by.timeslowly.additional_abilities.common.ability.entity_effects.StampedEnchantments;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import by.timeslowly.additional_abilities.registry.AAComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code additional_abilities:enchantment_bonus} 的消费端（服务端结算 + 客户端提示行）。
 *
 * <h2>一、附魔等级查询：{@link GetEnchantmentLevelEvent}</h2>
 * NeoForge 把"游戏性附魔等级查询"全部接到了这个事件上（{@code EnchantmentHelper#runIterationOnItem}
 * 的两种重载、{@code hasTag}、{@code has(DataComponentType)}、{@code getItemEnchantmentLevel}、
 * {@code getEnchantmentLevel(Holder, LivingEntity)} …），因此这里是本效果<b>唯一的结算入口</b>：
 * 读到物品上的标记就把等级写进 {@code event.getEnchantments()}。
 * <p>
 * 冲突时用 {@code upgrade}（取较大值），与 DS 的 {@code HarvestBonuses} 取最大速度同口径 ——
 * 真实附魔比虚拟附魔高时不会被压低。
 *
 * <h2>二、为什么必须有"来源存活"校验</h2>
 * 标记写在物品上，而物品是会脱离目标手上的：被丢在地上、放进箱子、随死亡掉落。
 * 那些情况下 {@code EnchantmentBonus.Instance} 的清理扫不到它，标记会留在物品的 NBT 上
 * （且随存档持久化）。若不校验，这就会变成<b>永久生效的假附魔</b>。
 * 因此服务端每次命中标记都要回查：该 {@code (owner, effectId)} 的时长实例还在不在。
 * <p>
 * 这带来一条重要的安全性质：<b>即使标记清理失败，也不会产生永久生效的假附魔</b>，
 * 最坏只是某只物品身上留着一个惰性组件。
 *
 * <h2>三、怎么判断"现在是不是逻辑服务端"（这里很容易写出线程事故）</h2>
 * 客户端也需要看到标记（提示行、客户端侧预测），所以不能简单地"只在服务端校验"。
 * 但也不能用 {@code FMLEnvironment.dist} —— 那是<b>物理端</b>，而单人游戏里
 * 物理客户端同时跑着逻辑客户端与逻辑服务端：若按物理端一刀切，
 * 单人世界的逻辑服务端就会跳过校验，留下上面那个漏洞。
 * <p>
 * 这里改用「<b>当前线程就是该服务器的服务器线程</b>」来判定：
 * <ul>
 *     <li>专用服务端 / 单人世界的内置服务端：事件在 Server thread 上触发 → 校验；</li>
 *     <li>客户端的画面线程（提示行、预测）：不是 Server thread → 直接信任标记。</li>
 * </ul>
 * 同时这也<b>回避了跨线程读取服务端状态</b>：{@code getPlayerList().getPlayer(...)}、
 * {@code getExistingData(...)} 这类调用只会在服务器线程上发生，不会出现
 * "渲染线程摸服务端 HashMap"的隐患。{@code getCurrentServer()} 在纯客户端为 {@code null}，
 * 这也是信任分支的另一个自然入口。
 *
 * <h2>四、提示行（{@link ItemTooltipEvent}）</h2>
 * 本效果<b>不动</b> {@code DataComponents.ENCHANTMENTS}，所以原版不会把虚拟附魔画进提示里
 * （NeoForge 的 {@code GetEnchantmentLevelEvent} javadoc 也明说不影响提示）。
 * 这里自己补一组行：一行灰色说明 + 每个附魔一行（用原版 {@code Enchantment#getFullname}，
 * 名称、颜色与罗马数字都与真附魔一致）。
 * <p>
 * ⚠️ 客户端只信任标记（无法校验来源存活），因此<b>已经脱离目标的残留标记在客户端仍会显示提示行</b>
 * —— 属纯视觉残影，服务端不会据此结算。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public final class EnchantmentBonusHandler {
    private static final String TOOLTIP_HEADER = "additional_abilities.component.enchantment_bonus";

    private EnchantmentBonusHandler() {}

    @SubscribeEvent
    public static void onGetEnchantmentLevel(final @NotNull GetEnchantmentLevelEvent event) {
        StampedEnchantments stamped = event.getStack().get(AAComponents.ENCHANTMENT_BONUS.get());

        // 早退：绝大多数物品没有本组件，这里只是一次哈希查
        if (stamped == null) {
            return;
        }

        for (StampedEnchantments.Provider provider : stamped.providers()) {
            if (!isSourceAlive(provider)) {
                continue;
            }

            for (StampedEnchantments.Entry entry : provider.entries()) {
                // 事件可能只问某一个附魔（getEnchantmentLevel 路径），只在该前提下回应它
                if (!event.isTargetting(entry.enchantment())) {
                    continue;
                }

                Holder.Reference<Enchantment> holder = event.getHolder(entry.enchantment()).orElse(null);

                if (holder == null) {
                    continue;
                }

                event.getEnchantments().upgrade(holder, entry.level());
            }
        }
    }

    @SubscribeEvent
    public static void onItemTooltip(final @NotNull ItemTooltipEvent event) {
        StampedEnchantments stamped = event.getItemStack().get(AAComponents.ENCHANTMENT_BONUS.get());

        if (stamped == null) {
            return;
        }

        // 同一附魔被多个来源同时压上时只显示一行，取较大等级（与结算口径一致）
        Map<ResourceKey<Enchantment>, Integer> levels = new LinkedHashMap<>();

        for (StampedEnchantments.Provider provider : stamped.providers()) {
            if (!isSourceAlive(provider)) {
                continue;
            }

            for (StampedEnchantments.Entry entry : provider.entries()) {
                levels.merge(entry.enchantment(), entry.level(), Math::max);
            }
        }

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

        for (Map.Entry<ResourceKey<Enchantment>, Integer> level : levels.entrySet()) {
            Holder.Reference<Enchantment> holder = lookup.get(level.getKey()).orElse(null);

            if (holder == null) {
                continue;
            }

            tooltip.add(Enchantment.getFullname(holder, level.getValue()));
        }
    }

    /**
     * 标记的来源是否仍然有效。
     * <p>
     * 判定规则：<b>只有在服务器线程上</b>才回查服务端权威数据；其余情况（纯客户端、渲染线程）
     * 一律信任标记。理由见类注释第三节。
     */
    private static boolean isSourceAlive(final @NotNull StampedEnchantments.Provider provider) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        if (server == null || !server.isSameThread()) {
            // 纯客户端（专用客户端连服务器）或客户端的画面线程：信任标记
            return true;
        }

        ServerPlayer owner = server.getPlayerList().getPlayer(provider.owner());

        if (owner == null) {
            // 施法者已下线（或该 UUID 从未在线）：来源失效
            return false;
        }

        EnchantmentBonuses storage = owner.getExistingData(AAAttachments.ENCHANTMENT_BONUSES).orElse(null);

        // 实例还在 = 来源仍存活（时长实例的到期 / 超距 / 技能停用都会让它从存储里消失）
        return storage != null && storage.get(provider.effectId()) != null;
    }
}
