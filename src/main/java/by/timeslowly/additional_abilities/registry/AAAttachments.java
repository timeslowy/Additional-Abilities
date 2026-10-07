package by.timeslowly.additional_abilities.registry;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.DSDataAttachments;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.entity_effects.DamageReflections;
import by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus.EnchantmentBonuses;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

// 注册 NeoForge Data Attachment（附加数据）
// 实体附件：damage_reflection（伤害反震参数载体）
// 维度附件：domain（领域数据，挂在 Level 上而非实体上）
// ⚠️ 另有一个例外：enchantment_bonuses 注册在 DS 自己的注册表里（见该字段的注释，那里说明了原因）
public class AAAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, AdditionalAbilities.MOD_ID);

    /** 伤害反弹数据：由 additional_abilities:damage_reflection 实体效果写入、DamageReflectionEventHandler 读取（被动技能周期刷新，无需序列化） */
    public static final Supplier<AttachmentType<DamageReflections>> DAMAGE_REFLECTION = ATTACHMENTS.register(
            "damage_reflection",
            () -> AttachmentType.builder(DamageReflections::new).build());

    /**
     * 领域数据：由 {@code additional_abilities:domain} 目标类型建立、{@code DomainTickHandler} 逐 tick 推进。
     * <p>
     * <b>刻意不做序列化</b>（{@code AttachmentType.builder(...)} 而非 {@code .serializable(...)}）：
     * 领域是纯内存的短生命周期对象（几十秒到几分钟），把 DS 的效果配置编解码进存档会随 DS 版本变动而脆断。
     * 代价是服务端重启后存活领域消失——这被视为可接受，并已写入文档。
     * <p>
     * 挂在 {@link net.minecraft.world.level.Level} 上（{@code Level extends AttachmentHolder}），
     * 随 Level 实例回收，无需额外的清理钩子。
     */
    public static final Supplier<AttachmentType<DomainData>> DOMAIN = ATTACHMENTS.register(
            "domain",
            () -> AttachmentType.builder(DomainData::new).build());

    /**
     * 临时附魔加成的时长实例存储：由 {@code additional_abilities:enchantment_bonus} 实体效果写入，
     * 挂在<b>目标实体</b>上（而非施法者），逐刻由 {@link EnchantmentBonuses#tickData} 推进。
     * <p>
     * <b>刻意做序列化</b>（{@code .serializable(...)}）：这是「时长实例族」效果，
     * 服务端重启后实例应当继续倒计时并继续维持物品上的标记 —— 否则会出现
     * 「物品上的标记还在、来源却没了」的窗口期（虽因消费端的来源存活校验而无害，但没必要留着）。
     * 这与 {@link #DOMAIN} 的取舍相反：领域是纯内存的短生命周期对象。
     * <p>
     * <b>⚠️ 唯一注册在 DS 注册表里的附件（本类其余两个都在 {@link #ATTACHMENTS}）</b>，
     * 这是被逼出来的：DS 遍历「自己的存储」时走的是
     * {@code DSDataAttachments.getStorages(...)}，而它的实现是
     * {@code REGISTRY.getEntries().forEach(...)} —— 只认 DS 那个绑定 {@code dragonsurvival} 命名空间的
     * {@code DeferredRegister}。第三方附件若注册在自己名下，下面五处<b>全部看不到它</b>：
     * <ol>
     *     <li>{@code ClientEffectProvider#getProviders} → 技能效果 <b>HUD / 物品栏效果列表不显示</b>；</li>
     *     <li>{@code ClearModifiersCommand} → <b>{@code /dragon-modifiers clear} 清不掉</b>；</li>
     *     <li>{@code PlayerLoginHandler#syncDataAttachments} → 登录时不会下发给客户端；</li>
     *     <li>{@code DragonAbilityCommand#refresh(clearStorages=true)} → 刷新技能时不清理（留孤儿）；</li>
     *     <li>{@code CustomPredicates} 的 {@code has_duration_effect} → 数据包条件测不到本效果的 id。</li>
     * </ol>
     * 因此这里借道 DS 的 {@code public static final} 注册表。<b>代价与约束</b>：
     * <ul>
     *     <li>附件 id 会被 {@code DeferredRegister} 强制带上 DS 的命名空间
     *         （{@code ResourceLocation.fromNamespaceAndPath(namespace, name)}），所以实际 id 是
     *         {@code dragonsurvival:additional_abilities_enchantment_bonuses} ——
     *         名字里带 {@code additional_abilities_} 前缀是为了**将来不撞 DS 自己的新增项**；</li>
     *     <li>依赖 DS 保留这个 public 字段（改了会在编译期直接报错，属可见失败）；</li>
     *     <li>注册时机必须在 {@code RegisterEvent} 之前 —— NeoForge 只在事件**之后**才禁止
     *         {@code DeferredRegister#register}（{@code seenRegisterEvent} 检查），而附件注册表的事件
     *         发生在所有 mod 构造之后，因此在本模组构造函数里登记是安全的；</li>
     *     <li>既然进了 DS 的遍历范围，登录同步由 DS 自动完成；但<b>中途施加 / 移除</b>仍需自己发
     *         （见 {@code EnchantmentBonus.Instance#onAddedToStorage}），否则 HUD 要等重登才更新。</li>
     * </ul>
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<EnchantmentBonuses>> ENCHANTMENT_BONUSES =
            DSDataAttachments.REGISTRY.register("additional_abilities_enchantment_bonuses",
                    () -> AttachmentType.serializable(EnchantmentBonuses::new).build());

    public static void register(IEventBus eventBus) {
        ATTACHMENTS.register(eventBus);
    }
}
