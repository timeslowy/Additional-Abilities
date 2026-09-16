package by.timeslowly.additional_abilities.common.eventhandler.abilities;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.TargetingMode;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.common.ability.DamageReflections;
import by.timeslowly.additional_abilities.registry.AADamageTypes;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 伤害反弹结算（服务端）。
 * <p>
 * 玩家受到伤害（{@link LivingDamageEvent.Post}）后，读取 {@link DamageReflections} 附件中由
 * additional_abilities:damage_reflection 实体效果写入的参数，将伤害按比例反弹给玩家周围范围内的敌对实体
 * （敌对判定复用 DS 的 {@link TargetingMode#ENEMIES}，含 PvP 配置与队伍逻辑）。
 * <p>
 * 反伤基数取该伤害序列的<b>原始伤害</b>（{@code getOriginalDamage()}，护甲/抗性减免前）：
 * 即使抗性 V 等 100% 减免导致实际扣血为 0，仍按原始伤害 × 比例反伤。
 * <p>
 * 效果生命周期：附件条目带有效期（{@link DamageReflections#REFLECTION_TTL_TICKS}），
 * 施法期间/被动技能周期刷新维持，技能结束后自动失效——主动技能停用时 DS 不会回调
 * remove（其仅对被动常驻 self 技能可靠），因此不依赖 remove 清理。
 * <p>
 * 三重防循环守卫：
 * <ol>
 *     <li>伤害来源于玩家自己（如自己的弹射物命中自己）时跳过；</li>
 *     <li>所受伤害类型为 additional_abilities:counter_shock 时跳过；</li>
 *     <li>重入标志：反伤结算期间产生的伤害不再触发反伤
 *         （覆盖 use_same_damage_type 开启时双方互弹的场景）。</li>
 * </ol>
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID)
public class DamageReflectionEventHandler {
    /** 反伤结算重入标志：反伤造成的伤害不再触发反伤，防止无限互相反弹 */
    private static boolean isReflecting = false;

    @SubscribeEvent
    public static void onLivingDamage(final LivingDamageEvent.@NotNull Post event) {
        // 仅作用于玩家：作用于非玩家实体时直接跳过（ServerPlayer 同时保证服务端执行）
        if (isReflecting || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        DamageReflections reflections = DamageReflections.getData(player);
        if (reflections.isEmpty()) {
            return;
        }

        DamageSource source = event.getSource();

        // 守卫①：伤害来源于自己时跳过，避免逻辑异常
        if (source.getEntity() == player) {
            return;
        }

        // 守卫②：所受伤害本身为反伤伤害时跳过，防止双方无限反弹
        if (source.is(AADamageTypes.COUNTER_SHOCK.getKey())) {
            return;
        }

        // getOriginalDamage()：本段伤害序列的原始伤害（护甲 / 抗性等减免之前）。
        // 采用减免前数值作为反伤基数——抗性 V 等 100% 减免（实际扣血为 0）时依然按原始伤害反伤。
        float amount = event.getOriginalDamage();
        if (amount <= 0.0F) {
            return;
        }

        // 只处理有效期内（未过期）的条目：主动技能施法结束 / 被动技能条件不满足后条目自然失效，
        // 反伤随技能结束自动消失（见 DamageReflections#active / REFLECTION_TTL_TICKS）
        // 多个技能各自独立结算（每个技能各按自身比例反弹一次）
        for (DamageReflections.Entry entry : reflections.active(player.level().getGameTime())) {
            reflect(player, source, amount, entry);
        }
    }

    private static void reflect(final ServerPlayer player, final DamageSource original, final float amount, final DamageReflections.@NotNull Entry entry) {
        float reflected = amount * entry.percentage();
        if (reflected <= 0.0F || entry.range() <= 0) {
            return;
        }

        List<Entity> targets = player.level().getEntities(player, player.getBoundingBox().inflate(entry.range()),
                entity -> entity instanceof LivingEntity && TargetingMode.ENEMIES.isEntityRelevant(player, entity, true));
        if (targets.isEmpty()) {
            return;
        }

        // 守卫③（重入标志）：本段结算期间造成的任何伤害（含目标玩家的反伤）都不再触发反伤
        isReflecting = true;
        try {
            // 默认使用 additional_abilities:counter_shock；开启同类型反伤时沿用原始伤害类型
            // 注意：counter_shock 必须从世界的数据包注册表（registryAccess）中取 Holder。
            // AADamageTypes 里的 DeferredHolder 指向 BuiltInRegistries，而 damage_type 是纯数据包注册表、
            // 静态注册表集合中不存在该注册表，DeferredHolder 永远无法绑定——直接传入会导致
            // ClientboundDamageEventPacket 在网络编码时抛 IllegalStateException，踢出客户端。
            Holder<DamageType> damageType = entry.useSameDamageType()
                    ? original.typeHolder()
                    : player.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(AADamageTypes.COUNTER_SHOCK.getKey());
            DamageSource reflection = new DamageSource(damageType, player);

            for (Entity target : targets) {
                target.hurt(reflection, reflected);
                // 与 DS DamageEffect 保持一致：使宠物等正确仇恨反伤目标
                player.setLastHurtMob(target);
            }
        } finally {
            isReflecting = false;
        }
    }
}
