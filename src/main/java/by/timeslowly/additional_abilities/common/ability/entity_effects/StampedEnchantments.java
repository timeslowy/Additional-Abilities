package by.timeslowly.additional_abilities.common.ability.entity_effects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 打在物品上的「临时附魔来源标记」——{@code additional_abilities:enchantment_bonus} 的核心通信载体。
 *
 * <h2>为什么需要它（NeoForge 的附魔钩子拿不到"这只物品属于谁"）</h2>
 * 本效果的生效路径是 NeoForge 的 {@code GetEnchantmentLevelEvent}：任何一次游戏性附魔等级查询
 * （{@code EnchantmentHelper} 的 {@code runIterationOnItem} / {@code hasTag} / {@code has(组件)} …）
 * 都会带着「被查询的物品栈」触发该事件，我们据此把等级写进去。
 * <p>
 * 但事件<b>不携带持有者</b>，而 1.21 里也没有别的途径能反查：
 * <ul>
 *     <li>{@code ItemStack#getEntityRepresentation()} 只由 {@code ItemFrame} / {@code ItemEntity} /
 *         {@code Display} 设置，装备槽里的物品恒为 {@code null}；</li>
 *     <li>{@code ItemStack} <b>不支持</b> NeoForge 的 {@code AttachmentType}
 *         （只实现 {@code DataComponentHolder}，不是 {@code IAttachmentHolder}）。</li>
 * </ul>
 * 于是唯一可行的做法就是：<b>把来源写在物品自己身上</b>。事件处理器用一次
 * {@code stack.get(AAComponents.ENCHANTMENT_BONUS)} 哈希查即可早退（绝大多数物品没有该组件，
 * 开销可忽略），命中的再按 {@code (owner, effectId)} 去服务端校验来源是否存活。
 *
 * <h2>为什么存 {@link ResourceKey} 而不是 {@code Holder}</h2>
 * {@code Holder} 走 {@code RegistryFixedCodec} 编解码，<b>解码时要求注册表里必须有该项</b>；
 * 一旦客户端缺这个附魔，整只物品栈解码失败会直接把玩家踢下线。
 * 存 key 则最坏只是查不到、跳过该条 —— 故障面小得多。
 *
 * <h2>为什么是"多来源列表"</h2>
 * 同一只物品可以被多个技能、多个施法者同时点中（例如两个队友各给一层附魔加成），
 * 因此标记必须能容纳多个 {@link Provider}，各自独立增删。等级冲突时由消费端取<b>较大值</b>
 * （与 DS 的 {@code HarvestBonuses} 取最大速度同口径）。
 *
 * <h2>为什么它不是"真附魔"</h2>
 * 本标记<b>不碰</b> {@code DataComponents.ENCHANTMENTS}，所以铁砧 / 砂轮 / 修复合成 /
 * {@code /enchant} 一律看不到它（不会出现"用假附魔骗砂轮经验"），代价是附魔光效与
 * 原版提示行也不会出现 —— 提示行由 {@code EnchantmentBonusHandler} 自己补。
 *
 * @param providers 当前压在物品上的全部来源条目；空列表等价于"没有标记"（此时调用方应移除组件）
 */
public record StampedEnchantments(List<Provider> providers) {
    public static final Codec<StampedEnchantments> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Provider.CODEC.listOf().fieldOf("providers").forGetter(StampedEnchantments::providers)
    ).apply(instance, StampedEnchantments::new));

    /**
     * 组件是随物品栈上线并随存档持久化的，两端都要能解 —— 故 StreamCodec 用 {@code ByteBuf} 作缓冲类型，
     * 它天然满足 {@code DataComponentType.Builder#networkSynchronized(StreamCodec<? super RegistryFriendlyByteBuf, T>)}。
     */
    public static final StreamCodec<ByteBuf, StampedEnchantments> STREAM_CODEC = StreamCodec.composite(
            Provider.STREAM_CODEC.apply(ByteBufCodecs.list()), StampedEnchantments::providers, StampedEnchantments::new);

    public StampedEnchantments {
        providers = List.copyOf(providers);
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }

    /** 找出某个来源在当前物品上留下的条目；没有则返回 {@code null} */
    public @Nullable Provider find(final @NotNull UUID owner, final @NotNull ResourceLocation effectId) {
        for (Provider provider : providers) {
            if (provider.matches(owner, effectId)) {
                return provider;
            }
        }

        return null;
    }

    /** 替换（或追加）某个来源的条目。键相同则整条替换，因此调用方无需先 {@link #without} */
    public @NotNull StampedEnchantments with(final @NotNull Provider provider) {
        List<Provider> result = new ArrayList<>(providers.size() + 1);
        boolean replaced = false;

        for (Provider existing : providers) {
            if (existing.matches(provider.owner(), provider.effectId())) {
                if (!replaced) {
                    result.add(provider);
                    replaced = true;
                }
            } else {
                result.add(existing);
            }
        }

        if (!replaced) {
            result.add(provider);
        }

        return new StampedEnchantments(result);
    }

    /** 移除某个来源的条目（无该项时原样返回，调用方可用 {@code ==} 之外的对象等价性判断"有没有变") */
    public @NotNull StampedEnchantments without(final @NotNull UUID owner, final @NotNull ResourceLocation effectId) {
        List<Provider> result = new ArrayList<>(providers.size());

        for (Provider existing : providers) {
            if (!existing.matches(owner, effectId)) {
                result.add(existing);
            }
        }

        return result.size() == providers.size() ? this : new StampedEnchantments(result);
    }

    /**
     * 一个来源（= 一次技能效果实例）留在物品上的全部条目。
     *
     * @param owner    施法者 UUID（{@code CommonData.source()}）
     * @param effectId 时长实例 id（{@code base.id}），用来区分同一施法者的多个效果实例
     * @param entries  该物品<b>适用</b>的附魔与已钳制过的等级；为空表示这个来源此刻不该压在物品上
     */
    public record Provider(UUID owner, ResourceLocation effectId, List<Entry> entries) {
        public static final Codec<Provider> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Provider::owner),
                ResourceLocation.CODEC.fieldOf("effect_id").forGetter(Provider::effectId),
                Entry.CODEC.listOf().fieldOf("entries").forGetter(Provider::entries)
        ).apply(instance, Provider::new));

        public static final StreamCodec<ByteBuf, Provider> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Provider::owner,
                ResourceLocation.STREAM_CODEC, Provider::effectId,
                Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), Provider::entries,
                Provider::new);

        public Provider {
            entries = List.copyOf(entries);
        }

        public boolean matches(final @NotNull UUID owner, final @NotNull ResourceLocation effectId) {
            return this.owner.equals(owner) && this.effectId.equals(effectId);
        }
    }

    /**
     * 单个附魔条目。
     *
     * @param enchantment 附魔的资源键
     * @param level       已钳制到 {@code [1, 附魔 maxLevel]} 的等级，写标记时就已算好
     *                    （因此消费端不必再回查技能等级，客户端也能直接渲染）
     */
    public record Entry(ResourceKey<Enchantment> enchantment, int level) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.ENCHANTMENT).fieldOf("enchantment").forGetter(Entry::enchantment),
                Codec.intRange(1, Enchantment.MAX_LEVEL).fieldOf("level").forGetter(Entry::level)
        ).apply(instance, Entry::new));

        public static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ResourceKey.streamCodec(Registries.ENCHANTMENT), Entry::enchantment,
                ByteBufCodecs.VAR_INT, Entry::level,
                Entry::new);
    }
}
