package by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.trigger.ActivationTrigger;
import by.timeslowly.additional_abilities.Additional_abilities;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 消耗物品时触发（{@code additional_abilities:on_item_consumed}）—— 结构与字段对齐原版进度准则
 * {@code minecraft:consume_item}（{@code ItemPredicate}，1.21.1 核对）。
 * <p>
 * 字段：仅一个可选的 {@code item}（{@link ItemPredicate}，省略即恒为真），与 {@code trigger_type}
 * <b>平级</b>书写：
 * <pre>{@code
 * "activation": {
 *   "activation_type": "dragonsurvival:passive",
 *   "trigger": {
 *     "trigger_type": "additional_abilities:on_item_consumed",
 *     "item": { "items": "#minecraft:meat" }
 *   }
 * }
 * }</pre>
 * <p>
 * <b>事件源</b>：{@link LivingEntityUseItemEvent.Finish}（NeoForge 在
 * {@code LivingEntity#completeUsingItem()} 的
 * {@code EventHooks#onItemUseFinish} 处抛出）。
 * <p>
 * <b>为什么不用 Mixin（机制核对结论，NeoForge 21.1.250 源码）</b>：原版 {@code consume_item} 准则
 * <b>没有单一钩子</b>，它有 5 个分散在物品类里的触发点：
 * <ol>
 *     <li>{@code Player#eat}（{@code Player.java:2115}）—— 所有<b>可食用</b>物品（迷之炖菜、紫颂果
 *         经 {@code super.finishUsingItem} 也汇入此处）；</li>
 *     <li>{@code PotionItem#finishUsingItem}（:49）—— 药水；</li>
 *     <li>{@code HoneyBottleItem#finishUsingItem}（:26）—— 蜂蜜瓶；</li>
 *     <li>{@code MilkBucketItem#finishUsingItem}（:22）—— 奶桶；</li>
 *     <li>{@code OminousBottleItem#finishUsingItem}（:32）—— 不祥之瓶。</li>
 * </ol>
 * 但这 5 处全都位于 {@code LivingEntity#completeUsingItem()} 内部（:3203-3204）：
 * <pre>{@code
 * ItemStack copy = this.useItem.copy();                       // 消耗前副本
 * ItemStack itemstack = EventHooks.onItemUseFinish(this, copy, getUseItemRemainingTicks(),
 *         this.useItem.finishUsingItem(this.level(), this));  // ← 上述 5 处都在这里
 * //                     ↑ 紧接着 post LivingEntityUseItemEvent.Finish
 * }</pre>
 * 且 {@code Item#useOnRelease} 仅对<b>弩</b>返回 {@code true}（{@code Item.java:342-343}），
 * 故除弩之外的有限读条物品都会走到这里。因此本触发器接公共事件即可覆盖全部 5 处，<b>零 Mixin</b>。
 * <p>
 * <b>与原版 {@code consume_item} 的语义偏差（重要，均为上界）</b>：
 * <table border="1">
 *     <caption>覆盖对照</caption>
 *     <tr><th>场景</th><th>原版 {@code consume_item}</th><th>本触发器</th></tr>
 *     <tr><td>食物 / 药水 / 蜂蜜瓶 / 奶桶 / 不祥之瓶 / 迷之炖菜 / 紫颂果</td><td>✅</td><td>✅</td></tr>
 *     <tr><td>山羊角（读条 140t 结束）</td><td>❌</td><td>⚠️ 会触发</td></tr>
 *     <tr><td>望远镱（读条 1200t 结束）</td><td>❌</td><td>⚠️ 会触发</td></tr>
 *     <tr><td>刷子（读条 200t）</td><td>❌</td><td>⚠️ 可能触发</td></tr>
 *     <tr><td>蛋糕 / 蜡烛蛋糕（方块交互，走 {@code FoodData#eat}）</td><td>❌</td><td>❌</td></tr>
 *     <tr><td>其它模组直接调用 {@code Player#eat}</td><td>✅</td><td>❌</td></tr>
 * </table>
 * 上界偏差都是「<b>非消耗品</b>」，写正常 {@code item} 谓词即可天然过滤；下界偏差只影响绕过
 * {@code completeUsingItem} 的第三方模组。要逐字一致只能对上述 5 处做 Mixin，本模组有意不采用。
 * <p>
 * <b>两个易踩的坑</b>：
 * <ol>
 *     <li>{@code item.count} 求值的是<b>消耗前</b>的数量 —— {@code Player#eat} 的
 *         {@code CONSUME_ITEM.trigger}（:2115）早于 {@code LivingEntity#eat} 的
 *         {@code consume(1, this)}（:3478），而本事件拿到的是 {@code completeUsingItem} 里
 *         {@code useItem.copy()} 的副本。故 {@code "count": 1} 对一叠 64 个面包<b>不匹配</b>。
 *         此行为与原版一致。</li>
 *     <li>匹配的是<b>消耗前</b>的物品：蜂蜜瓶 → 玻璃瓶、奶桶 → 空桶的转换不影响匹配结果。</li>
 * </ol>
 * <p>
 * 不检查 {@code DragonAbilityInstance#triggered} 防环标志（与 {@code on_block_break} /
 * {@code on_block_placed} 一致）；若某技能的效果会再引发一次消耗，请自行加 {@code cooldown}。
 * <p>
 * 语言键恒为 {@code trigger_type.additional_abilities.on_item_consumed}
 * —— <b>不能</b>借用 DS 的 {@code Translation.Type.TRIGGER_TYPE.wrap(...)}：该枚举前缀硬编码为
 * {@code trigger_type.dragonsurvival.}，单参重载会生成 DS 命名空间下的键。
 */
public record OnItemConsumed(Optional<ItemPredicate> item) implements ActivationTrigger<ItemStack> {
    /** 侧边栏展示名（见 {@code dragonsurvival.gui.ability.activation_trigger} 的 %s 占位符） */
    private static final String TRANSLATION = "trigger_type." + Additional_abilities.MOD_ID + ".on_item_consumed";

    /**
     * 字段与原版 {@code minecraft:consume_item} 同构，仅一个可选的 {@code item}
     * （{@link ItemPredicate#CODEC}），故这里不需要构建 {@link net.minecraft.world.level.storage.loot.LootContext}。
     */
    public static final MapCodec<OnItemConsumed> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ItemPredicate.CODEC.optionalFieldOf("item").forGetter(OnItemConsumed::item)
    ).apply(instance, OnItemConsumed::new));

    /**
     * 事件分发：接线见主类 {@code Additional_abilities} 构造函数
     * （{@code NeoForge.EVENT_BUS.addListener(OnItemConsumed::trigger)}，游戏中事件总线）。
     * <p>
     * 该事件对<b>所有生物</b>都会抛出，故此处先筛出服务端玩家、再要求其处于龙形态 —— 与
     * {@code OnBlockPlaced#trigger} 的守卫顺序一致。物品栈取
     * {@link LivingEntityUseItemEvent.Finish#getItem()}，即<b>使用前的副本</b>
     * （{@code completeUsingItem} 里 {@code useItem.copy()} 的产物），因此判定对象是「被消耗的那个物品」，
     * 而非消耗后替换回来的容器（玻璃瓶 / 空桶）。
     */
    public static void trigger(final LivingEntityUseItemEvent.@NotNull Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        DragonStateHandler handler = DragonStateProvider.getData(player);

        if (!handler.isDragon()) {
            return;
        }

        ItemStack consumed = event.getItem();

        MagicData.getData(player)
                .filterPassiveByTrigger(trigger -> trigger instanceof OnItemConsumed onItemConsumed && onItemConsumed.test(consumed))
                .forEach(ability -> ability.tick(player));
    }

    @Override
    public boolean test(final @NotNull ItemStack stack) {
        return this.item.map(predicate -> predicate.test(stack)).orElse(true);
    }

    @Contract(value = " -> new", pure = true)
    @Override
    public @NotNull Component translation() {
        return Component.translatable(TRANSLATION);
    }

    @Override
    public MapCodec<? extends ActivationTrigger<?>> codec() {
        return CODEC;
    }
}
