package by.timeslowly.additional_abilities.common.ability.entity_effects;

import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 「一条耐久变更规则」—— {@code additional_abilities:durability} 的载荷。
 *
 * <h2>它是一条规则的全部：数据 + 行为 + 展示</h2>
 * 与 {@code additional_abilities:enchantment_bonus} 的 {@code EnchantmentBonus} 同构，承担三件事：
 * <ol>
 *     <li><b>JSON 形态</b>：字段定义、{@link #CODEC}、解码期硬校验
 *         （写错的字段组合在数据包加载期<b>当场</b>报错，与 DS {@code DragonAbility#validate} 的取向一致）；</li>
 *     <li><b>行为</b>：{@link #apply} 扫槽位、按栈身份去重、逐只物品结算；</li>
 *     <li><b>展示</b>：{@link #describe} 生成技能侧栏的那一行。</li>
 * </ol>
 * 它<b>不认识的</b>是"这条规则属于哪个技能、什么时候触发" —— 那是
 * {@code registry.dragon.ability.entity_effects.DurabilityEffect} 的事；
 * <b>也不认识的</b>是"怎么把耐久真正写进物品" —— 那是 {@link ItemDurability} 的事。
 *
 * <h2>JSON 形态</h2>
 * <pre>
 * {
 *     "slots":     ["mainhand", "claw_sword"],     // 必填，至少一项（见 HeldItemSlots.SlotSelector）
 *     "item":      { "items": "#minecraft:swords" },  // 可选；省略 = 该槽位内全部可损坏物品
 *     "operation": "add",                          // 必填：add（加减，正数修 / 负数磨）/ set（设为指定值）
 *     "amount":    { "type": "minecraft:linear", "base": -0.25 },   // 必填，随技能等级计算
 *     "unit":      "percent",                      // 必填：durability（耐久点）/ percent（百分比）
 *     "percent_base": "max_durability",            // unit = percent 时必填：max_durability / current_durability
 *     "break_item": false                          // 可选，默认 false：结果耐久归零时是否按原版销毁物品
 * }
 * </pre>
 *
 * <h2>四条执行语义</h2>
 * <ol>
 *     <li><b>换算</b>：{@code unit = durability} 时增减量就是 {@code amount}（四舍五入到点）；
 *         {@code unit = percent} 时是 {@code amount} × 基准，基准按 {@code percent_base} 取
 *         <b>最大耐久</b>或<b>当前耐久</b>。</li>
 *     <li><b>「耐久」附魔</b>：只有 {@code operation = add} 且折算后为<b>减少</b>时，减少量才过
 *         {@code EnchantmentHelper#processDurabilityChange}（含"概率完全抵消"），
 *         <b>因此实际磨损可能小于指定值</b>。修复与 {@code set} 一律不走减免，保证写多少就是多少。</li>
 *     <li><b>钳制</b>：结果一律钳在 {@code [0, maxDamage]}。</li>
 *     <li><b>归零</b>：{@code break_item} <b>只</b>决定"结果耐久 ≤ 0 时是否按原版销毁物品"，
 *         不影响未归零时的表现 ——
 *         <ul>
 *             <li>归零且 {@code break_item = true} → 整条交给原版 {@code hurtAndBreak}
 *                 （附魔减免 + 销毁 + 破损回调，代价是创造模式会被原版整段跳过）；</li>
 *             <li>其余情况 → 精确写回 {@code DAMAGE} 组件，耐久钳在 0、物品保留
 *                 （⚠️ 1.21.1 没有"已损坏"状态，它<b>仍然可用</b>）。</li>
 *         </ul>
 *         细节见 {@link ItemDurability}。</li>
 * </ol>
 *
 * <h2>一个条目内的去重口径</h2>
 * 扫描走 {@link HeldItemSlots#allOf}（逐格、不去重），因此"选中的快捷栏物品同时是主手"这类同一只栈
 * 出现在多处的情况由本类按<b>栈身份</b>收口，保证同一只栈在本条目下只结算一次。
 * 而<b>不同条目</b>命中同一只栈是刻意的（两条规则的语义不同，理应各结算一次）。
 *
 * @param slots       槽位选择器，至少一项
 * @param item        物品谓词；{@link Optional#empty()} 表示该槽位内全部可损坏物品
 * @param operation   变更方式
 * @param amount      增减量或目标值（随技能等级计算）
 * @param unit        {@code amount} 的单位
 * @param percentBase {@code unit = percent} 时的百分比基准
 * @param breakItem   结果耐久归零时是否按原版销毁物品
 */
public record DurabilityChange(List<HeldItemSlots.SlotSelector> slots, Optional<ItemPredicate> item,
                               Operation operation, LevelBasedValue amount, Unit unit,
                               Optional<PercentBase> percentBase, boolean breakItem) {
    public static final Codec<DurabilityChange> CODEC = RecordCodecBuilder.<DurabilityChange>create(instance -> instance.group(
            HeldItemSlots.SlotSelector.CODEC.listOf().fieldOf("slots").forGetter(DurabilityChange::slots),
            ItemPredicate.CODEC.optionalFieldOf("item").forGetter(DurabilityChange::item),
            Operation.CODEC.fieldOf("operation").forGetter(DurabilityChange::operation),
            LevelBasedValue.CODEC.fieldOf("amount").forGetter(DurabilityChange::amount),
            Unit.CODEC.fieldOf("unit").forGetter(DurabilityChange::unit),
            PercentBase.CODEC.optionalFieldOf("percent_base").forGetter(DurabilityChange::percentBase),
            Codec.BOOL.optionalFieldOf("break_item", false).forGetter(DurabilityChange::breakItem)
    ).apply(instance, DurabilityChange::new)).validate(DurabilityChange::validate);

    /** 描述里最多逐个列出几个具体物品，超出后收成一句"等 N 种" */
    private static final int MAX_LISTED_ITEMS = 4;

    private static final String LANG_PREFIX = "additional_abilities.ability.durability.";

    /** 解码期硬校验：能让写错的数据包当场报出来的，就不要留到运行时静默忽略 */
    private static DataResult<DurabilityChange> validate(final @NotNull DurabilityChange change) {
        if (change.slots().isEmpty()) {
            return DataResult.error(() -> "'slots' must contain at least one entry");
        }

        if (change.unit() == Unit.PERCENT && change.percentBase().isEmpty()) {
            return DataResult.error(() -> "'percent_base' is required when 'unit' is 'percent'");
        }

        if (change.unit() == Unit.DURABILITY && change.percentBase().isPresent()) {
            return DataResult.error(() -> "'percent_base' must be omitted when 'unit' is 'durability'");
        }

        return DataResult.success(change);
    }

    /**
     * 对目标实体结算本条目。
     *
     * @return 是否动到了<b>龙生爪牙槽</b>里的物品（调用方据此决定要不要补一次
     *         {@code ClawInventoryData#sync}；装备槽与背包由原版自己同步，无需处理）
     */
    public boolean apply(final @NotNull ServerLevel level, final @NotNull LivingEntity holder, final int abilityLevel) {
        Set<ItemStack> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean clawTouched = false;

        for (HeldItemSlots.HeldSlot slot : HeldItemSlots.allOf(holder)) {
            if (!matchesSlot(slot.kind())) {
                continue;
            }

            ItemStack stack = slot.stack();

            // isDamageableItem() 的定义即"有 MAX_DAMAGE 且没有 UNBREAKABLE 且带 DAMAGE"，
            // 所以"有耐久值且可以破坏"这条要求已经由它一并保证 —— 带 unbreakable 组件的物品天然被排除
            if (!stack.isDamageableItem() || !matchesItem(stack)) {
                continue;
            }

            if (!handled.add(stack)) {
                continue;
            }

            if (applyTo(level, holder, stack, slot.kind(), abilityLevel)
                    && slot.source() == HeldItemSlots.Source.CLAW) {
                clawTouched = true;
            }
        }

        return clawTouched;
    }

    /** 该槽位是否被本条目命中 */
    public boolean matchesSlot(final @NotNull HeldItemSlots.SlotKind kind) {
        for (HeldItemSlots.SlotSelector selector : slots) {
            if (selector.matches(kind)) {
                return true;
            }
        }

        return false;
    }

    /** 物品谓词是否命中（省略谓词时恒真） */
    public boolean matchesItem(final @NotNull ItemStack stack) {
        return item.isEmpty() || item.get().test(stack);
    }

    /**
     * 对一只物品栈结算本条目：先把 {@code amount} 按单位折算成"耐久点"，再交给
     * {@link ItemDurability} 落地。
     *
     * @return 是否真的改动了这只物品
     */
    private boolean applyTo(final @NotNull ServerLevel level, final @NotNull LivingEntity holder,
                            final @NotNull ItemStack stack, final @NotNull HeldItemSlots.SlotKind kind,
                            final int abilityLevel) {
        int maxDamage = stack.getMaxDamage();

        if (maxDamage <= 0) {
            return false;
        }

        int currentDurability = ItemDurability.currentDurability(stack);
        int raw = Math.round(this.amount.calculate(abilityLevel) * scale(maxDamage, currentDurability));
        EquipmentSlot equipmentSlot = kind.equipmentSlot();

        if (operation == Operation.SET) {
            // 绝对值语义：不经过「耐久」附魔（保证"设多少就是多少"）
            return ItemDurability.setDurability(holder, stack, equipmentSlot, raw, breakItem);
        }

        // 磨损且这一下会把耐久打到 0、又允许销毁 → 整条交给原版（附魔减免 + shrink 销毁 + 破损回调）。
        // ⚠️ 只有"确实会归零"时才走它：原版路径还带着创造模式整段跳过、成就判据等一组副作用，
        // 不该在"只是磨掉几点"时被无辜带上 —— break_item 的语义严格限定为"归零时是否销毁"。
        if (breakItem && raw < 0 && currentDurability + raw <= 0) {
            return ItemDurability.consumeWithVanillaRules(level, holder, stack, equipmentSlot, -raw);
        }

        return ItemDurability.addDelta(level, holder, stack, equipmentSlot, raw, true);
    }

    /** {@code amount} 到"耐久点"的换算系数 */
    private float scale(final int maxDamage, final int currentDurability) {
        return switch (unit) {
            case DURABILITY -> 1.0F;
            case PERCENT -> switch (percentBase.orElseThrow()) {
                case MAX_DURABILITY -> maxDamage;
                case CURRENT_DURABILITY -> currentDurability;
            };
        };
    }

    /** 侧边栏一句话描述 */
    public @NotNull MutableComponent describe(final int abilityLevel) {
        // 描述里拿不到运行时的具体物品（也就不知道它的 maxDamage），因此百分比直接按写下的比例报出来，
        // 耐久点则按技能等级算出整数报出来
        float calculated = this.amount.calculate(abilityLevel);
        String valueText;

        if (unit == Unit.PERCENT) {
            int percent = Math.round(calculated * 100.0F);
            // add 是"变化量"，带正负号；set 是"目标值"，不带号
            valueText = (operation == Operation.ADD && percent >= 0 ? "+" : "") + percent + "%";
        } else {
            int points = Math.round(calculated);
            valueText = operation == Operation.ADD ? String.format(Locale.ROOT, "%+d", points) : String.valueOf(points);
        }

        String key = LANG_PREFIX + (operation == Operation.ADD ? "description.add" : "description.set");

        // 数值直接染 DS 的"动态值"蓝（DSColors.BLUE 是编译期常量）。
        // 刻意不走 DSColors.dynamicValue(Object)：它对非 Component 入参内部会调 I18n.exists，
        // 而 DSColors 顶部就 import 了客户端专属的 I18n —— 本类落在公共源码集，
        // 虽然描述只有客户端侧栏会调，但没有理由留这一处隐患。
        MutableComponent value = Component.literal(valueText).withColor(DSColors.BLUE).append(unitSuffix());

        return Component.translatable(key, describeSlots(), describeItem(), value);
    }

    /**
     * 数值后面的单位说明。
     * <p>
     * 中英两种语序在这里恰好都是"数值 + 单位后缀"，因此可以安全地拼成一段带译文的文本；
     * 若将来出现语序相反的语言，应改为把数值与单位一起塞进整句模板。
     */
    private @NotNull MutableComponent unitSuffix() {
        if (unit == Unit.DURABILITY) {
            return Component.translatable(LANG_PREFIX + "unit.durability");
        }

        boolean ofMax = percentBase.orElse(PercentBase.MAX_DURABILITY) == PercentBase.MAX_DURABILITY;
        return Component.translatable(LANG_PREFIX + (ofMax ? "unit.max_percent" : "unit.current_percent"));
    }

    private @NotNull MutableComponent describeSlots() {
        MutableComponent text = null;

        for (HeldItemSlots.SlotSelector selector : slots) {
            MutableComponent name = Component.translatable(LANG_PREFIX + "slot." + selector.getSerializedName());
            text = join(text, name);
        }

        return text == null ? Component.empty() : text;
    }

    private @NotNull MutableComponent describeItem() {
        MutableComponent any = Component.translatable(LANG_PREFIX + "any_item");
        HolderSet<Item> items = item.flatMap(ItemPredicate::items).orElse(null);

        if (items == null) {
            // 谓词省略，或写了 components / predicates 但没有 items 列表 —— 都归为"不限定具体物品"
            return any;
        }

        MutableComponent text = null;
        int listed = 0;
        int total = items.size();

        for (Holder<Item> holder : items) {
            if (listed >= MAX_LISTED_ITEMS) {
                break;
            }

            text = join(text, Component.empty().append(holder.value().getDescription()));
            listed++;
        }

        if (text == null) {
            return any;
        }

        if (total > listed) {
            text.append(Component.translatable(LANG_PREFIX + "item.more", total - listed));
        }

        return text;
    }

    /** 用统一的顿号连接两个组件；{@code text} 为 {@code null} 时直接返回后者 */
    private static @NotNull MutableComponent join(final MutableComponent text, final @NotNull MutableComponent addition) {
        if (text == null) {
            return addition;
        }

        return text.append(Component.translatable(LANG_PREFIX + "slot.separator")).append(addition);
    }

    /** {@code operation} 的可用值 */
    public enum Operation implements StringRepresentable {
        /** 在当前耐久上加减（{@code amount} 正数 = 修、负数 = 磨） */
        ADD("add"),
        /** 把耐久设为 {@code amount} 折算出的值 */
        SET("set");

        public static final Codec<Operation> CODEC = StringRepresentable.fromEnum(Operation::values);

        private final String name;

        Operation(final String name) {
            this.name = name;
        }

        @Override
        public @NotNull String getSerializedName() {
            return name;
        }
    }

    /** {@code unit} 的可用值 */
    public enum Unit implements StringRepresentable {
        /** 直接是耐久点数 */
        DURABILITY("durability"),
        /** 占某个基准的百分比（基准由 {@code percent_base} 指定） */
        PERCENT("percent");

        public static final Codec<Unit> CODEC = StringRepresentable.fromEnum(Unit::values);

        private final String name;

        Unit(final String name) {
            this.name = name;
        }

        @Override
        public @NotNull String getSerializedName() {
            return name;
        }
    }

    /** {@code percent_base} 的可用值：百分比的取值基准 */
    public enum PercentBase implements StringRepresentable {
        /** 占<b>最大耐久</b>的比例（原版 {@code set_damage} 同构，但原版走的是 damage 轴，注意区分） */
        MAX_DURABILITY("max_durability"),
        /** 占<b>当前耐久</b>的比例 */
        CURRENT_DURABILITY("current_durability");

        public static final Codec<PercentBase> CODEC = StringRepresentable.fromEnum(PercentBase::values);

        private final String name;

        PercentBase(final String name) {
            this.name = name;
        }

        @Override
        public @NotNull String getSerializedName() {
            return name;
        }
    }
}
