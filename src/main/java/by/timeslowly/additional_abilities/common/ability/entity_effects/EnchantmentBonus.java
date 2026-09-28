package by.timeslowly.additional_abilities.common.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.DragonSurvival;
import by.dragonsurvivalteam.dragonsurvival.common.codecs.duration_instance.CommonData;
import by.dragonsurvivalteam.dragonsurvival.common.codecs.duration_instance.DurationInstance;
import by.dragonsurvivalteam.dragonsurvival.common.codecs.duration_instance.DurationInstanceBase;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.ClawInventoryData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import by.timeslowly.additional_abilities.registry.AAComponents;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.neoforged.neoforge.attachment.AttachmentType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 「时长实例族」数据：临时附魔加成（{@code additional_abilities:enchantment_bonus} 的载荷）。
 *
 * <h2>JSON 形态</h2>
 * <pre>
 * "enchantment_bonuses": [
 *   {
 *     "base": { "id": "…", "duration": { … }, "should_remove_automatically": false, … },
 *     "enchantments": [
 *       { "enchantment": "minecraft:efficiency", "level": 3 },
 *       { "enchantment": "minecraft:unbreaking", "level": { "type": "minecraft:linear", "base": 1.0 } }
 *     ]
 *   }
 * ]
 * </pre>
 * 与 {@code dragonsurvival:harvest_bonus} 同构：<b>列表里每个元素各自带一个 {@code base}</b>
 * （独立的 id / 时长 / 自动移除开关），因此同一个技能可以同时挂几组时长不同的附魔加成。
 *
 * <h2>本类承担的三件事</h2>
 * <ol>
 *     <li><b>解码期硬校验</b>：写成常量的等级必须落在 {@code [1, 附魔 maxLevel]}；
 *         {@code enchantments} 不允许为空。放在解码期而不是运行时静默忽略，是为了让写错的数据包
 *         <b>当场</b>报出来（与 DS {@code DragonAbility#validate} 的取向一致）。
 *         <b>不限制宝藏附魔</b>（见 {@link EnchantmentEntry#validate} 的注释）。</li>
 *     <li><b>侧边栏描述</b>（{@link #getDescriptions(int)}）：技能详情面板里那一行"临时附魔：…"。</li>
 *     <li><b>{@link Instance}：逐刻校准物品标记</b> —— 这是本效果真正的执行体，见 {@link Instance#tick}。</li>
 * </ol>
 *
 * <h2>为什么是 {@code extends DurationInstanceBase} 而不是像 {@code simple_screen_vision} 那样组合</h2>
 * 那个效果是"一次性视觉"，没有需要 tick / 持久化的实例，所以只是<b>形态对齐</b>。
 * 本效果必须真正借用 DS 的实例存储：{@code base.duration} 要真的倒计时、
 * {@code should_remove_automatically} 要真的在施法者超距 / 技能停用时回收，
 * 而 area 目标那边 DS <b>根本不会调用</b> {@code AbilityEntityEffect#remove}
 * （只有 {@code SelfTarget} 会转发），所以清理只能靠 {@code Storage#tick} 这条线。
 */
public class EnchantmentBonus extends DurationInstanceBase<EnchantmentBonuses, EnchantmentBonus.Instance> {
    /**
     * 单条"附魔 + 等级"。
     *
     * @param enchantment 附魔本体（数据包注册表引用，故 JSON 里写 {@code "minecraft:sharpness"}）
     * @param level       应用等级，随技能等级计算的 {@link LevelBasedValue}（也接受裸数字）；缺省为 1
     */
    public record EnchantmentEntry(Holder<Enchantment> enchantment, LevelBasedValue level) {
        public static final Codec<EnchantmentEntry> CODEC = RecordCodecBuilder.<EnchantmentEntry>create(instance -> instance.group(
                Enchantment.CODEC.fieldOf("enchantment").forGetter(EnchantmentEntry::enchantment),
                LevelBasedValue.CODEC.optionalFieldOf("level", LevelBasedValue.constant(1.0F)).forGetter(EnchantmentEntry::level)
        ).apply(instance, EnchantmentEntry::new)).validate(EnchantmentEntry::validate);

        /**
         * 解码期校验：写成常量的等级必须落在 {@code [1, 附魔 maxLevel]}。
         * <p>
         * 动态 {@code LevelBasedValue} 的取值只有到运行时才知道，因此那种情况只能运行时钳制
         * （见 {@link #resolveLevel}）。
         * <p>
         * ⚠️ <b>不限制宝藏附魔</b>（2026-09-28 放宽）：早期版本在这里拒绝 {@code #minecraft:treasure}
         * （mending / frost_walker / soul_speed / swift_sneak / 两种诅咒 / wind_burst），
         * 现已改为允许 —— 本效果给的是<b>临时</b>附魔、随时可被移除，不存在"刷出宝藏附魔装备"的问题。
         */
        private static DataResult<EnchantmentEntry> validate(final @NotNull EnchantmentEntry entry) {
            Holder<Enchantment> holder = entry.enchantment();
            int maxLevel = holder.value().getMaxLevel();

            if (entry.level() instanceof LevelBasedValue.Constant(float value)) {
                int fixed = Math.round(value);

                if (fixed < 1 || fixed > maxLevel) {
                    return DataResult.error(() -> "Fixed level " + fixed + " of enchantment '" + name(holder)
                            + "' is outside the allowed range [1, " + maxLevel + "]");
                }
            }

            return DataResult.success(entry);
        }

        private static @NotNull String name(final @NotNull Holder<Enchantment> holder) {
            return holder.unwrapKey().map(key -> key.location().toString()).orElse("<unknown enchantment>");
        }
    }

    public static final Codec<EnchantmentBonus> CODEC = RecordCodecBuilder.<EnchantmentBonus>create(instance -> instance.group(
            DurationInstanceBase.CODEC.fieldOf("base").forGetter(identity -> identity),
            EnchantmentEntry.CODEC.listOf().fieldOf("enchantments").forGetter(EnchantmentBonus::enchantments)
    ).apply(instance, EnchantmentBonus::new)).validate(bonus -> bonus.enchantments().isEmpty()
            ? DataResult.error(() -> "'enchantments' must contain at least one entry")
            : DataResult.success(bonus));

    private final List<EnchantmentEntry> enchantments;

    public EnchantmentBonus(final DurationInstanceBase<?, ?> base, final List<EnchantmentEntry> enchantments) {
        super(base);
        this.enchantments = List.copyOf(enchantments);
    }

    public List<EnchantmentEntry> enchantments() {
        return enchantments;
    }

    /**
     * 某条目在给定技能等级下的<b>实际</b>应用等级。
     *
     * @return {@code 0} 表示这条目本次不生效（算出 {@code <= 0}）；否则已钳制到
     *         {@code [1, 附魔 getMaxLevel()]}
     */
    public static int resolveLevel(final @NotNull EnchantmentEntry entry, final int abilityLevel) {
        int level = Math.round(entry.level().calculate(abilityLevel));

        if (level <= 0) {
            return 0;
        }

        return Math.min(level, entry.enchantment().value().getMaxLevel());
    }

    /** 侧边栏描述：本等级下所有可生效条目的名称（带等级罗马数字）+ 时长；无条目可生效时返回空列表 */
    public @NotNull List<MutableComponent> getDescriptions(final int abilityLevel) {
        MutableComponent names = null;

        for (EnchantmentEntry entry : enchantments) {
            int level = resolveLevel(entry, abilityLevel);

            if (level <= 0) {
                continue;
            }

            // Enchantment.getFullname 返回的是 Component（原版把它建成不可变副本），这里不需要可变性
            Component name = Enchantment.getFullname(entry.enchantment(), level);

            if (names == null) {
                names = Component.empty().append(name);
            } else {
                names.append(Component.translatable(DESCRIPTION_SEPARATOR)).append(name);
            }
        }

        if (names == null) {
            return List.of();
        }

        float duration = duration().calculate(abilityLevel);

        if (duration < 0.0F) {
            return List.of(Component.translatable(DESCRIPTION_INFINITE, names));
        }

        // 时长用 DS 的高亮色，与附魔名自身的原版颜色区分开。
        // 这里刻意不调 DSColors.dynamicValue(Object)：它对非 Component 入参会走 I18n.exists（客户端专属类），
        // 而本类落在公共源码集、专用服务端也会加载。颜色常量是编译期常量，取用无任何运行时依赖。
        MutableComponent seconds = Component.literal(String.valueOf(Math.round(duration / 20.0F))).withColor(DSColors.BLUE);
        return List.of(Component.translatable(DESCRIPTION, names, seconds));
    }

    @Override
    public Instance createInstance(final @NotNull ServerPlayer dragon, final @NotNull DragonAbilityInstance ability, final int currentDuration) {
        return new Instance(this, CommonData.from(id(), dragon, ability, customIcon(), shouldRemoveAutomatically()), currentDuration);
    }

    @Override
    public AttachmentType<EnchantmentBonuses> type() {
        return AAAttachments.ENCHANTMENT_BONUSES.get();
    }

    private static final String DESCRIPTION = "additional_abilities.ability.enchantment_bonus.description";
    private static final String DESCRIPTION_INFINITE = "additional_abilities.ability.enchantment_bonus.description.infinite";
    private static final String DESCRIPTION_SEPARATOR = "additional_abilities.ability.enchantment_bonus.separator";

    /**
     * 本效果的运行时实例：除了父类的倒计时与自动移除，它额外负责<b>逐刻校准物品上的标记</b>。
     *
     * <h2>为什么是"逐刻校准"而不是"施加时写一次"</h2>
     * 目标手里的物品是随时会变的（换手、换装、把剑塞回背包、龙生换手时把爪牙工具塞进主手……），
     * 而标记又必须跟着物品走。逐刻把「持有中的适用物品」与「物品上现有的标记」对齐，
     * 是唯一能覆盖全部这些情况的做法，代价是每个受影响实体每刻扫约 45 个格子 ——
     * 一次 {@code ItemStack#get(组件)} 级别的哈希查，可忽略。
     *
     * <h2>清理收口只有一处</h2>
     * {@link #onRemovalFromStorage} —— {@code Storage#remove} / {@code Storage#tick}（到期）/ {@code Storage#clear}
     * 三条路都会调它，因此时长到期、施法者超距、技能停用、显式 remove 全部能正确撤标记。
     *
     * <h2>与装备属性附魔的关系</h2>
     * 附魔的属性修饰符由 {@code LivingEntity#collectEquipmentChanges()} 收集，
     * 而它每刻比对的正是 {@code ItemStack.matches}（含组件）。标记变化 → 装备被视为"变了" →
     * 同刻内重新收集属性。因此<b>不需要</b>任何手动通知（{@code detectEquipmentUpdates} 在本版本是 private 的）。
     */
    public static class Instance extends DurationInstance<EnchantmentBonus> {
        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(instance ->
                DurationInstance.codecStart(instance, () -> EnchantmentBonus.CODEC).apply(instance, Instance::new));

        public Instance(final EnchantmentBonus baseData, final CommonData commonData, final int currentDuration) {
            super(baseData, commonData, currentDuration);
        }

        @Override
        public boolean tick(final Entity storageHolder) {
            // 先问父类要不要移除：要移除的话没必要先写一遍标记再清掉（那会白做一次组件与同步）
            boolean shouldRemove = super.tick(storageHolder);

            // 非生物没有装备槽概念（效果本身也只在 apply 时接受 LivingEntity，这里是防御性判定）
            if (!shouldRemove && storageHolder instanceof LivingEntity living) {
                sweep(living, true);
            }

            return shouldRemove;
        }

        /**
         * 实例写入存储时，把整份存储推给目标玩家自己的客户端 —— 这是技能效果 HUD 能立刻出现图标的关键。
         * <p>
         * DS 只在<b>登录</b>时统一同步自己的存储（{@code PlayerLoginHandler#syncDataAttachments}），
         * 中途新增的实例不会自己飞过去；DS 自家的 {@code HarvestBonus} 等也是这样各自补自己的同步包。
         * 这里直接借 DS 的 {@code Storage#sync}（发它自己的 {@code dragonsurvival:sync_data}），
         * 于是<b>不必新增网络包</b>。
         */
        @Override
        public void onAddedToStorage(final Entity storageHolder) {
            syncToClient(storageHolder);
        }

        @Override
        public void onRemovalFromStorage(final Entity storageHolder) {
            if (storageHolder instanceof LivingEntity living) {
                sweep(living, false);
            }

            // 不同步的话，HUD 上的图标会赖着不走，直到重登
            syncToClient(storageHolder);
        }

        /** 把本实体的整份存储推给该玩家自己的客户端（仅服务端、仅玩家；其余实体没有需要同步的接收端） */
        private void syncToClient(final Entity holder) {
            if (!(holder instanceof ServerPlayer player) || player.level().isClientSide()) {
                return;
            }

            player.getExistingData(AAAttachments.ENCHANTMENT_BONUSES).ifPresent(storage -> storage.sync(player));

        }

        @Override
        public Component getDescription() {
            List<MutableComponent> descriptions = baseData().getDescriptions(appliedAbilityLevel());
            return descriptions.isEmpty() ? Component.empty() : descriptions.getFirst();
        }

        /**
         * 把「本来源的标记」与目标当前的持有状态对齐。
         *
         * @param enable {@code true} = 按"手持 / 身穿 + 物品适用"写上标记；
         *               {@code false} = 一律撤掉本来源的标记（清理收口用）
         */
        private void sweep(final @NotNull LivingEntity holder, final boolean enable) {
            if (holder.level().isClientSide()) {
                return;
            }

            UUID owner = source().orElse(null);
            ResourceLocation effectId = id();

            if (owner == null) {
                return;
            }

            List<EnchantmentEntry> configured = baseData().enchantments();
            int abilityLevel = appliedAbilityLevel();
            boolean clawChanged = false;

            for (HeldItemSlots.HeldSlot slot : HeldItemSlots.of(holder)) {
                List<StampedEnchantments.Entry> wanted = enable && slot.held()
                        ? applicable(slot.stack(), configured, abilityLevel)
                        : List.of();

                if (!update(slot.stack(), owner, effectId, wanted)) {
                    continue;
                }

                if (slot.source() == HeldItemSlots.Source.CLAW) {
                    clawChanged = true;
                }
            }

            // 装备槽与背包不需要任何手动通知：
            // 组件变化会让 ItemStack.matches 判定为"装备变了"，原版每刻的 collectEquipmentChanges 自会处理
            // （属性修饰符的增删都发生在那里），因此这里只处理不在任何容器菜单里的龙生爪牙槽。
            if (clawChanged && holder instanceof Player player && DragonStateProvider.isDragon(player)) {
                ClawInventoryData.getData(player).sync(player);
            }
        }

        /** 该物品当前应当带上的条目（物品不适用于某附魔时逐条剔除；全都不适用则返回空） */
        private @NotNull List<StampedEnchantments.Entry> applicable(final @NotNull ItemStack stack,
                                                                   final @NotNull List<EnchantmentEntry> configured,
                                                                   final int abilityLevel) {
            // 附魔书对"任何附魔"都返回 supportsEnchantment = true（原版为附魔台/铁砧准备的语义），
            // 但书拿在手里/穿在身上没有任何"作用"，因此这里直接排除，避免往书上写一堆没意义的标记
            if (stack.isEmpty() || stack.is(Items.ENCHANTED_BOOK)) {
                return List.of();
            }

            List<StampedEnchantments.Entry> result = null;

            for (EnchantmentEntry entry : configured) {
                // 与铁砧同口径的"物品是否适用该附魔"判定（NeoForge 的现代 API，非弃用的 Enchantment#canEnchant）
                if (!stack.supportsEnchantment(entry.enchantment())) {
                    continue;
                }

                int level = resolveLevel(entry, abilityLevel);

                if (level <= 0) {
                    continue;
                }

                ResourceKey<Enchantment> key = entry.enchantment().unwrapKey().orElse(null);

                if (key == null) {
                    continue;
                }

                if (result == null) {
                    result = new ArrayList<>(configured.size());
                }

                result.add(new StampedEnchantments.Entry(key, level));
            }

            return result == null ? List.of() : result;
        }

        /**
         * 把某个来源的条目写进（或从）物品组件，<b>只动自己那一条</b>，其他来源原样保留。
         *
         * @return 是否真的发生了变化（没变时返回 {@code false}，避免无谓的组件写入与网络同步）
         */
        private static boolean update(final @NotNull ItemStack stack, final @NotNull UUID owner,
                                      final @NotNull ResourceLocation effectId,
                                      final @NotNull List<StampedEnchantments.Entry> wanted) {
            StampedEnchantments current = stack.get(AAComponents.ENCHANTMENT_BONUS.get());

            if (wanted.isEmpty()) {
                if (current == null) {
                    return false;
                }

                StampedEnchantments next = current.without(owner, effectId);

                // without 在"本来就没有本来源"时原样返回同一对象（见其实现），以此判定无变化
                if (next == current) {
                    return false;
                }

                if (next.isEmpty()) {
                    stack.remove(AAComponents.ENCHANTMENT_BONUS.get());
                } else {
                    stack.set(AAComponents.ENCHANTMENT_BONUS.get(), next);
                }

                return true;
            }

            StampedEnchantments.Provider mine = new StampedEnchantments.Provider(owner, effectId, wanted);

            if (current == null) {
                stack.set(AAComponents.ENCHANTMENT_BONUS.get(), new StampedEnchantments(List.of(mine)));
                return true;
            }

            StampedEnchantments next = current.with(mine);

            if (next.equals(current)) {
                return false;
            }

            stack.set(AAComponents.ENCHANTMENT_BONUS.get(), next);
            return true;
        }

        public Tag save(final @NotNull HolderLookup.Provider provider) {
            return CODEC.encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), this).getOrThrow();
        }

        public static @Nullable Instance load(final @NotNull HolderLookup.Provider provider, final @NotNull CompoundTag nbt) {
            return CODEC.parse(provider.createSerializationContext(NbtOps.INSTANCE), nbt)
                    .resultOrPartial(DragonSurvival.LOGGER::error)
                    .orElse(null);
        }
    }
}
