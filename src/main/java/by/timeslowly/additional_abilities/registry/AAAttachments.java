package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.DamageReflections;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

// 注册实体 Data Attachment（NeoForge 实体附加数据）
public class AAAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(
            NeoForgeRegistries.ATTACHMENT_TYPES, AdditionalAbilities.MOD_ID);

    /** 伤害反弹数据：由 additional_abilities:damage_reflection 实体效果写入、DamageReflectionEventHandler 读取（被动技能周期刷新，无需序列化） */
    public static final Supplier<AttachmentType<DamageReflections>> DAMAGE_REFLECTION = ATTACHMENTS.register(
            "damage_reflection",
            () -> AttachmentType.builder(DamageReflections::new).build());

    public static void register(IEventBus eventBus) {
        ATTACHMENTS.register(eventBus);
    }
}
