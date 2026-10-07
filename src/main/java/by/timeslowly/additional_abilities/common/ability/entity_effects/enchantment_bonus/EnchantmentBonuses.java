package by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.DSDataAttachments;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.Storage;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.NotNull;

/**
 * 临时附魔加成的实例存储（挂在<b>目标实体</b>上的附件），与 DS 的
 * {@code registry.attachments.HarvestBonuses} 同构。
 * <p>
 * 完全复用 DS 的 {@link Storage}：
 * 倒计时、超距 / 技能停用的自动移除、NBT 存取都由父类完成，本类只负责
 * <b>把 tick 接上</b>（父类没有自驱动力）与把存取委托给 {@link EnchantmentBonus.Instance}。
 *
 * <h2>为什么挂在目标身上而不是施法者身上</h2>
 * 时长实例的语义是"这个效果正压在<b>这个目标</b>身上多久"：施法者超距时，
 * 要移除的是<b>目标</b>身上那条实例（见 {@code DurationInstance#tick} 里按
 * {@code storageHolder.distanceTo(source)} 的判定），因此存储必须按目标存放。
 *
 * <h2>为什么用 {@code EntityTickEvent.Pre} 而不是 {@code .Post}</h2>
 * DS 的 {@code HarvestBonuses} 用的是 Post，但那是"读取当前状态"（挖掘速度）的语义。
 * 本效果要逐刻校准的刷新脉冲（并在同一处维护反查候选集），紧接着会被原版自己的
 * {@code LivingEntity#collectEquipmentChanges()}（在 {@code LivingEntity#tick()} 的<b>尾部</b>、
 * 而 {@code EntityTickEvent.Pre/Post} 分别在头部 / 尾部触发）拿去比对 {@code ItemStack.matches} ——
 * 用 Pre 才能让"标记换来"与"属性附魔因此生效"落在<b>同一刻</b>，而不是差一刻。
 *
 * <h2>只做序列化，不做手写序列化缓存</h2>
 * {@code AttachmentType.serializable(...)} 让它随存档走：服务端重启后实例能恢复，
 * 与物品上已持久化的标记继续保持自洽（否则会出现"标记还在、来源却没了"的窗口期）。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public class EnchantmentBonuses extends Storage<EnchantmentBonus.Instance> {
    /**
     * 逐刻推进存储 —— <b>刻意两端都跑，不能加 {@code isClientSide()} 早退</b>。
     *
     * <h2>为什么客户端也必须 tick</h2>
     * DS 的 {@code DurationInstance#tick} 是<b>两端各自扣自己那份 {@code currentDuration}</b>：
     * 客户端扣完就 {@code return false}（永不移除），只有服务端会返回 {@code true} 决定移除。
     * 而效果列表 / HUD 上的倒计时读的正是本地的 {@code currentDuration}
     * （{@code EffectRenderingInventoryScreenMixin#dragonSurvival$formatDuration} →
     * {@code provider.currentDuration()}）。
     * <p>
     * 因此客户端不 tick 的话，客户端那份时长<b>永远停在下发时的数值</b>：
     * 图标不淡出、倒计时不动，直到服务端到期发来移除同步才整体消失 ——
     * 也就是「倒计时停在最大时长、但到期消失正常」这个现象。
     * <p>
     * DS 自家的 {@code HarvestBonuses} / {@code ModifiersWithDuration} / {@code OxygenBonuses} /
     * {@code GlowData} / {@code BlockVisionData} <b>全都是两端无差别 tick 的</b>，原因就在这里：
     * 侧别差异由 {@code DurationInstance#tick} 内部处理，这里不该重复判断。
     *
     * <h2>两端都跑为什么仍然安全</h2>
     * 真正有副作用的两件事各自都有更强的守卫，够不到客户端：
     * <ul>
     *     <li>写物品标记：{@code Instance#sweep} 开头就 {@code isClientSide()} 早退；</li>
     *     <li>下发同步：{@code Instance#syncToClient} 只对服务器上的 {@link ServerPlayer} 生效。</li>
     * </ul>
     * 至于「到期移除」，客户端这条路径上 {@code tick} 恒返回 {@code false}，因此
     * {@code onRemovalFromStorage} 在客户端只会因「服务端发来空存储」而间接触发，
     * 那正是我们要的清理时机。
     */
    @SubscribeEvent
    public static void tickData(final EntityTickEvent.@NotNull Pre event) {
        Entity entity = event.getEntity();

        entity.getExistingData(AAAttachments.ENCHANTMENT_BONUSES).ifPresent(storage -> {
            storage.tick(entity);

            if (storage.isEmpty()) {
                // 空存储直接摘掉附件，避免实体身上长期挂一个空壳（与 DS HarvestBonuses 同处理）
                entity.removeData(AAAttachments.ENCHANTMENT_BONUSES);
                EnchantmentBonusHolders.unregister(entity);
                return;
            }

            // 反查候选集的"每帧自愈"：重登 / 读档那条路不会走 onAddedToStorage
            //（DS 的同步包在客户端只做 deserializeNBT），靠这里补登记；重复登记是幂等的
            EnchantmentBonusHolders.register(entity);
        });
    }

    @Override
    protected Tag save(final @NotNull HolderLookup.Provider provider, final EnchantmentBonus.@NotNull Instance entry) {
        return entry.save(provider);
    }

    @Override
    protected EnchantmentBonus.Instance load(final @NotNull HolderLookup.Provider provider, final @NotNull CompoundTag tag) {
        return EnchantmentBonus.Instance.load(provider, tag);
    }

    @Override
    public AttachmentType<?> type() {
        return AAAttachments.ENCHANTMENT_BONUSES.get();
    }

    /** 服务端读取入口（与 DS {@code HarvestBonuses} 的 {@code getData(player)} 同风格） */
    public static @NotNull EnchantmentBonuses getData(final @NotNull Entity entity) {
        return entity.getData(AAAttachments.ENCHANTMENT_BONUSES);
    }

    /**
     * 启动自检：确认「借道 DS 附件注册表」这个非显然的前提仍然成立。
     * <p>
     * 本存储是<b>注册在 DS 的 {@code DSDataAttachments.REGISTRY} 里</b>的（原因见
     * {@code AAAttachments.ENCHANTMENT_BONUSES} 的注释）。这条借用关系一旦被 DS 的更新打断，
     * 表现会是<b>静默降级</b>：效果照常生效，但技能效果 HUD 不再显示、{@code /dragon-modifiers clear}
     * 也清不掉 —— 正是最容易被忽略的那类故障。所以这里在服务端启动后主动验证一次，
     * 只在异常时吵，正常时留一行 info 便于在日志里核对。
     * <p>
     * 三件要验证的事：① 后置注册是否被接受（{@code DeferredHolder} 能否绑定）；
     * ② 注册表里拿得到键；③ 该键确实在 DS 的 {@code REGISTRY.getEntries()} 里
     * （{@code DSDataAttachments.getStorages} 正是遍历它）。
     */
    @SubscribeEvent
    public static void verifyBorrowedRegistration(final @NotNull ServerStartedEvent event) {
        AttachmentType<EnchantmentBonuses> type;

        try {
            // 后置注册只有在 RegisterEvent 之前完成才会绑定成功；未绑定会抛 IllegalStateException
            type = AAAttachments.ENCHANTMENT_BONUSES.get();
        } catch (RuntimeException exception) {
            AdditionalAbilities.LOGGER.error("enchantment_bonus: attachment 'additional_abilities_enchantment_bonuses' "
                    + "was not bound - registering into Dragon Survival's attachment registry no longer works. "
                    + "The effect itself still works, but the ability HUD and '/dragon-modifiers clear' will not see it.", exception);
            return;
        }

        ResourceLocation key = NeoForgeRegistries.ATTACHMENT_TYPES.getKey(type);
        boolean enumerableByDragonSurvival = key != null && DSDataAttachments.REGISTRY.getEntries().stream()
                .anyMatch(entry -> key.equals(entry.getId()));

        if (!enumerableByDragonSurvival) {
            AdditionalAbilities.LOGGER.error("enchantment_bonus: attachment [{}] is not enumerable by Dragon Survival "
                    + "(DSDataAttachments.REGISTRY) - the ability HUD and '/dragon-modifiers clear' will not see this effect. "
                    + "The effect itself still works.", key);
        } else {
            AdditionalAbilities.LOGGER.info("enchantment_bonus: attachment [{}] is registered in Dragon Survival's "
                    + "attachment registry (ability HUD + '/dragon-modifiers clear' are wired up).", key);
        }
    }
}
