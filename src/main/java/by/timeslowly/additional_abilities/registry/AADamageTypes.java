package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 本模组随附的<b>数据包伤害类型</b>的注册键。
 *
 * <h2>为什么这里只有 {@link ResourceKey}，没有任何注册器</h2>
 * 伤害类型是<b>纯数据包注册表</b>：1.21.1 的 {@code BuiltInRegistries} 里
 * <b>没有</b> {@code DAMAGE_TYPE} 这一项（已核对 NeoForge 21.1.250 合并源码 ——
 * 该文件对 {@code damage} 零命中），因此它只能由
 * {@code data/<命名空间>/damage_type/<名称>.json} 定义。
 * <p>
 * 这带来一个此前被忽略的后果（审查报告 F18）：<b>{@code DeferredRegister} 对它是无效的</b>。
 * {@code DeferredRegister} 的绑定逻辑只在 {@code RegisterEvent} 里按
 * {@code BuiltInRegistries.REGISTRY} 的注册表名单逐个派发；名单里没有 {@code damage_type}，
 * 那个事件就永远不发，{@code DeferredHolder} 永远 {@code isBound() == false}。
 * 于是 {@code COUNTER_SHOCK.get()} 会抛
 * {@code IllegalStateException("Registry not present for ...")} ——
 * 一个"看起来能取、一取就炸"的公开字段。旧的 {@code DamageReflectionEventHandler}
 * 正是靠"只取 {@code getKey()}、再去世界注册表查 {@code Holder}"绕开了它，
 * 但那个公开的 {@code DeferredHolder} 字段本身仍是个陷阱：任何第三方照直觉调用
 * {@code COUNTER_SHOCK.get()} 都会在运行期崩溃。
 * <p>
 * 改为直接持有 {@link ResourceKey} 之后，这个陷阱在结构上就不存在了 ——
 * {@code getKey()} 语义不变，而"取 {@code Holder}"这件事只有一条正确路径：
 * 从世界（或其它能提供数据包注册表的 {@code HolderLookup.Provider}）里查。
 *
 * <h2>怎么取 {@code Holder}</h2>
 * <pre>{@code
 * Holder<DamageType> type = AADamageTypes.counterShock(level.registryAccess());
 * }</pre>
 * {@link #counterShock} 在注册表里找不到该条目时返回 {@code null}（而不是抛异常）：
 * 伤害结算路径上抛异常会直接把一次普通的近战变成服务端崩溃，
 * 而"这个数据包没装/被改坏"应当退化为"不反伤"，不是让服务器挂掉。
 *
 * <h2>JSON 定义仍在</h2>
 * {@code data/additional_abilities/damage_type/counter_shock.json} 保持不变
 * （用户已确认决策 C02：存档标记与注册 ID 不变）。
 */
public final class AADamageTypes {
    /** 注册 id：{@code additional_abilities:counter_shock}。反伤默认使用的伤害类型。 */
    public static final ResourceKey<DamageType> COUNTER_SHOCK = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "counter_shock"));

    private AADamageTypes() {
        // 常量持有类
    }

    /**
     * 从给定注册表视图取 {@code additional_abilities:counter_shock} 的 {@link Holder}。
     * <p>
     * 唯一正确的取值路径：{@code DeferredHolder} 对数据包注册表<b>永不绑定</b>（见类注释）。
     *
     * @param registries 任意能提供数据包注册表的视图，例如 {@code level.registryAccess()}
     * @return 该伤害类型的 {@link Holder}；条目缺失时为 {@code null}，
     *         调用方应<b>跳过</b>依赖它的结算，而不是继续
     */
    public static @Nullable Holder<DamageType> counterShock(final @NotNull HolderLookup.Provider registries) {
        // 用 lookup 而不是 registryOrThrow：后者在"整个注册表都不存在"时抛异常。
        // 此处两种"缺失"（注册表缺失 / 条目缺失）都应当退化为"不反伤"，而不是让服务端崩掉。
        // get(...) 返回的是 Optional<Holder.Reference<DamageType>>，Reference 即 Holder 的子类型，无需强转
        return registries.lookup(Registries.DAMAGE_TYPE)
                .flatMap(registry -> registry.get(COUNTER_SHOCK))
                .orElse(null);
    }
}
