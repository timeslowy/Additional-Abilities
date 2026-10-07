package by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus;

import by.timeslowly.additional_abilities.common.ability.entity_effects.HeldItemSlots;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 「当前挂着临时附魔实例的实体」注册表 —— {@code enchantment_bonus} 查询期反查的候选集。
 * <p>
 * 结算发生在 {@code GetEnchantmentLevelEvent} 里，而该事件<b>只携带被查询的物品栈、不携带持有者</b>，
 * 1.21 也没有任何途径能从一只栈反查持有者。因此"这只物品现在归谁"只能反过来问：
 * 枚举<b>身上还挂着本效果实例</b>的实体，逐个用 {@link HeldItemSlots#holds} 确认持有关系。
 * 本类只负责第一件事。
 * <p>
 * 集合按<b>侧别分开</b>：单人世界里客户端与服务端共存于同一 JVM，混在一起会让渲染线程
 * （物品提示行）摸到服务端实体。分开后服务端线程只看服务端实体，客户端只有"本地玩家"
 * 可能带实例（其余实体的实例不向客户端同步）。
 * <p>
 * 生命周期：注册有两条路 —— {@code Instance#onAddedToStorage}（应用当刻、零延迟）与
 * {@code EnchantmentBonuses#tickData}（每帧自愈，覆盖重登 / 读档 —— DS 的同步包在客户端
 * 只做 {@code deserializeNBT}、不会调 {@code onAddedToStorage}）；注销由 {@code tickData} 的
 * "存储变空"分支负责，另在遍历时惰性剔除已被移出世界的实体（下线玩家、卸载区块里的生物）。
 */
public final class EnchantmentBonusHolders {
    private static final Set<LivingEntity> SERVER = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<LivingEntity> CLIENT = Collections.newSetFromMap(new IdentityHashMap<>());

    private EnchantmentBonusHolders() {}

    /** 登记一个可能带实例的实体；非生物会被忽略（本效果只作用于生物） */
    public static void register(final @NotNull Entity holder) {
        if (holder instanceof LivingEntity living) {
            setFor(living).add(living);
        }
    }

    public static void unregister(final @NotNull Entity holder) {
        if (holder instanceof LivingEntity living) {
            setFor(living).remove(living);
        }
    }

    /** 该侧此刻是否一个候选都没有 —— 绝大多数附魔查询靠它零成本早退 */
    public static boolean isEmpty(final boolean clientSide) {
        return holders(clientSide).isEmpty();
    }

    /** 遍历候选实体；顺带剔除已从世界中移除的那些 */
    public static void forEach(final boolean clientSide, final @NotNull Consumer<LivingEntity> action) {
        Iterator<LivingEntity> iterator = holders(clientSide).iterator();

        while (iterator.hasNext()) {
            LivingEntity holder = iterator.next();

            if (holder.isRemoved()) {
                iterator.remove();
                continue;
            }

            action.accept(holder);
        }
    }

    private static @NotNull Set<LivingEntity> holders(final boolean clientSide) {
        return clientSide ? CLIENT : SERVER;
    }

    private static @NotNull Set<LivingEntity> setFor(final @NotNull LivingEntity holder) {
        return holders(holder.level().isClientSide());
    }
}
