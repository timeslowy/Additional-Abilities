package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.entity_effects.DamageReflections;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

// 注册 NeoForge Data Attachment（附加数据）
// 实体附件：damage_reflection（伤害反震参数载体）
// 维度附件：domain（领域数据，挂在 Level 上而非实体上）
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

    public static void register(IEventBus eventBus) {
        ATTACHMENTS.register(eventBus);
    }
}
