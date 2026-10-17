package by.timeslowly.additional_abilities.testutil;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Activation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.AbilityBlockEffect;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.AbilityTargeting;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainAnchor;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainInstance;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainShape;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.HolderOwner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 领域相关测试的公共夹具体。
 *
 * <h2>为什么手写 {@link FakeHolder} 而不是用 {@code Holder.direct(...)}</h2>
 * 领域的身份键是 {@link DomainInstance#abilityKey()}，它来自
 * {@code DragonAbilityInstance#key()} → {@code Holder#getKey()}。而 {@code Holder.getKey()} 的默认实现是
 * {@code unwrapKey().orElse(null)}，{@code Holder.Direct#unwrapKey()} <b>恒返回
 * {@code Optional.empty()}</b> —— 用 {@code Holder.direct(...)} 造出来的实例其 {@code key()} 是
 * {@code null}，于是所有按 key 匹配的逻辑（{@code matches} / {@code latest} / {@code count}）
 * 都会退化成"匹配任意领域"，测试将以假乱真地通过。
 * <p>
 * 因此这里手写一个带真实 key 的 {@link Holder}，只实现被测代码实际会走到的部分。
 *
 * <h2>效果替身为什么不做调用记录</h2>
 * 效果回调的第一个形参是 {@code ServerPlayer}，本机<b>无外网且缓存中没有 Mockito</b>
 * （只有 JUnit 5.9.2），造不出这个重型对象；而 {@code resolveCaster} 在"无可用世界"时会短路，
 * 所以传入 {@code null} 关卡时效果<b>永远不会被调用</b>。既然调用与否不可观测，
 * 记录调用次数只会制造"断言恒成立"的空测试。{@link InertBlockEffect} 因此刻意保持无状态，
 * 只用于<b>构造出内容可区分的效果组</b>（{@code DomainInstance} 按 {@code equals} 去重）。
 */
public final class DomainTestFixtures {
    public static final UUID CASTER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** 夹具固定使用的结算间隔与领域时长；测试据此推算到期 / 结算时刻。 */
    public static final int APPLY_INTERVAL = 100;
    public static final int DURATION = 20_000;

    private DomainTestFixtures() {
        // 工具类
    }

    /** 造一个仅供领域使用的技能注册键。 */
    public static ResourceKey<DragonAbility> abilityKey(final String path) {
        return ResourceKey.create(DragonAbility.REGISTRY,
                Identifier.fromNamespaceAndPath("additional_abilities_test", path));
    }

    /**
     * 造一个"可被领域使用"的技能实例。
     * <p>
     * 领域只读 {@code level()} 与 {@code key()}，activation 不会被触碰，故取最简值。
     */
    public static DragonAbilityInstance abilityInstance(final ResourceKey<DragonAbility> key, final int level) {
        DragonAbility ability = new DragonAbility(
                new NoopActivation(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                false,
                null
        );

        return new DragonAbilityInstance(new FakeHolder<>(key, ability), level);
    }

    /**
     * 造一个已建立、尚未到期的领域。
     * <p>
     * 构造器只接受"第一组"，其余组必须像生产路径那样依次 {@code join} 进去 ——
     * 早先这里写成 {@code sets.getFirst()}，把传进来的其余组静默丢掉，
     * 导致"多组"用例实际上只建了一组（已被遍历计数用例抓出）。
     */
    public static DomainInstance domain(final ResourceKey<DragonAbility> key,
                                        final List<Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting>> effectSets,
                                        final long now) {
        List<Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting>> sets =
                new ArrayList<>(effectSets);

        if (sets.isEmpty()) {
            sets.add(blockSet("initial"));
        }

        DomainInstance domain = new DomainInstance(CASTER, abilityInstance(key, 1), 1,
                sets.getFirst(),
                DomainShape.SPHERE, DomainAnchor.CASTING_POSITION,
                4.0, 4.0, false,
                APPLY_INTERVAL, DURATION,
                false,           // removeEffectsOnEnd：收尾相关用例单独构造
                Vec3.ZERO, now, now);

        for (int index = 1; index < sets.size(); index++) {
            domain.join(now, sets.get(index));
        }

        return domain;
    }

    /**
     * 造一个方块效果集。
     * <p>
     * {@code label} 参与效果替身的相等性，因此不同 label 造出的组在
     * {@code DomainInstance} 眼里是"内容不同"的新组 —— 这正是"新增组"用例需要的区分度。
     */
    public static Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> blockSet(final String label) {
        return Either.left(new AbilityTargeting.BlockTargeting(Optional.empty(), List.of(new InertBlockEffect(label))));
    }

    /**
     * 无状态的效果替身，仅用于让效果集可构造、可区分（相等性由 {@code label} 决定）。
     * <p>
     * 它<b>不会</b>被真正调用：效果回调需要 {@code ServerPlayer} + {@code ServerLevel}，
     * 而测试统一传 {@code null} 关卡。保留 {@code label} 是为了给出区分度，不是为了断言调用。
     */
    public record InertBlockEffect(String label) implements AbilityBlockEffect {
        @Override
        public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability,
                          final BlockPos position, final Direction direction) {
            // 测试夹具：不产生副作用
        }

        @Override
        public MapCodec<? extends AbilityBlockEffect> blockCodec() {
            throw new UnsupportedOperationException("测试替身不参与编解码");
        }
    }

    /** 最简 Activation：领域路径不会调用它的任何方法。 */
    private static final class NoopActivation implements Activation {
        @Override
        public Activation.Type type() {
            return Activation.Type.SIMPLE;
        }

        @Override
        public MapCodec<? extends Activation> codec() {
            throw new UnsupportedOperationException("测试替身不参与编解码");
        }
    }

    /** 带真实 key 的 {@link Holder} 最小实现。见类注释：{@code Holder.Direct} 没有 key，不能用。 */
    private record FakeHolder<T>(ResourceKey<T> key, T value) implements Holder<T> {
        @Override
        public boolean isBound() {
            return true;
        }

        // 26.1.2 给 Holder 新增了两个组件相关的方法（原版用它区分「已绑定组件」与「裸引用」）。
        // 本替身不承载任何数据组件：「组件未绑定」是诚实答案（它本来就不是注册表里那条），
        // 而空 DataComponentMap 让任何 components() 读取退化成「没有组件」而不是 NPE。
        @Override
        public boolean areComponentsBound() {
            return false;
        }

        @Override
        public DataComponentMap components() {
            return DataComponentMap.EMPTY;
        }

        @Override
        public boolean is(final Identifier location) {
            return key.identifier().equals(location);
        }

        @Override
        public boolean is(final ResourceKey<T> resourceKey) {
            return key.equals(resourceKey);
        }

        @Override
        public boolean is(final Predicate<ResourceKey<T>> predicate) {
            return predicate.test(key);
        }

        @Override
        public boolean is(final TagKey<T> tagKey) {
            return false;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean is(final Holder<T> holder) {
            return holder.is(key);
        }

        @Override
        public Stream<TagKey<T>> tags() {
            return Stream.of();
        }

        @Override
        public Either<ResourceKey<T>, T> unwrap() {
            return Either.left(key);
        }

        @Override
        public Optional<ResourceKey<T>> unwrapKey() {
            return Optional.of(key);
        }

        @Override
        public ResourceKey<T> getKey() {
            return key;
        }

        @Override
        public Kind kind() {
            return Kind.REFERENCE;
        }

        @Override
        public boolean canSerializeIn(final HolderOwner<T> owner) {
            return true;
        }
    }
}
