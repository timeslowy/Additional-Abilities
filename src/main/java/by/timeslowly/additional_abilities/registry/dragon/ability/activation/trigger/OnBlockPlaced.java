package by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.codecs.Condition;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.trigger.ActivationTrigger;
import by.timeslowly.additional_abilities.Additional_abilities;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.util.Lazy;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 放置方块时触发（{@code additional_abilities:on_block_placed}）—— DS 内置
 * {@code dragonsurvival:on_block_break} 的<b>判定反向版</b>：结构与字段完全相同，
 * 只是事件源从「破坏」换成「放置」，因此<b>同样使用方块战利品上下文</b>。
 * <p>
 * 字段与 {@code on_block_break} 一致：仅一个可选的 {@code condition}（{@link LootItemCondition}，
 * 省略即恒为真），与 {@code trigger_type} <b>平级</b>书写：
 * <pre>{@code
 * "activation": {
 *   "activation_type": "dragonsurvival:passive",
 *   "trigger": {
 *     "trigger_type": "additional_abilities:on_block_placed",
 *     "condition": { "condition": "minecraft:matching_block_tag", "tag": "minecraft:logs" }
 *   }
 * }
 * }</pre>
 * <p>
 * <b>事件源</b>：{@link BlockEvent.EntityPlaceEvent}（NeoForge 21.1.250 中由
 * {@code ItemStack#useOn} → {@code CommonHooks#onPlaceItemIntoWorld} 在<b>服务端</b>抛出）。
 * {@link BlockEvent.EntityMultiPlaceEvent} 是它的子类、且 {@code CommonHooks} 用 if/else 二选一，
 * 因此监听父类即可同时覆盖「单方块」与「一次放置产生多方块」（床 / 门 / 高植被），且不会重复触发。
 * <p>
 * <b>覆盖范围（重要）</b>：本触发器只覆盖<b>玩家手持物品右键放置</b>的路径。以下来源<b>不会</b>触发：
 * 桶 / 瓶倒出流体（{@code BucketItem} 不捕获 BlockSnapshot）、发射器与潜影盒发射器、
 * 落沙落地、末影人搬方块、{@code /setblock}、{@code /fill}、结构生成与世界生成、树苗长树、流体蔓延。
 * <p>
 * <b>与 on_block_break 的有意差异</b>：
 * <ol>
 *     <li>不复刻「技能改动过方块则取消事件」的收尾逻辑 —— 放置事件在方块<b>已放下之后</b>才抛出，
 *         取消意味着回滚放置并退还物品，语义与破坏侧完全不同，故此处仅作「观察」，不取消任何东西；</li>
 *     <li>不检查 {@code DragonAbilityInstance#triggered} 防环标志（与 {@code on_block_break} 一致）；</li>
 *     <li>多方块放置只按 {@code snapshot[0]}（{@code event.getPos()} / {@code event.getState()}）
 *         判定与触发一次。</li>
 * </ol>
 * <p>
 * 语言键恒为 {@code trigger_type.additional_abilities.on_block_placed}
 * —— <b>不能</b>借用 DS 的 {@code Translation.Type.TRIGGER_TYPE.wrap(...)}：该枚举前缀硬编码为
 * {@code trigger_type.dragonsurvival.}，单参重载会生成 DS 命名空间下的键（DS 与 wiki 文档此处均有误导）。
 */
public record OnBlockPlaced(Optional<LootItemCondition> condition) implements ActivationTrigger<LootContext> {
    /** 侧边栏展示名（见 {@code dragonsurvival.gui.ability.activation_trigger} 的 %s 占位符） */
    private static final String TRANSLATION = "trigger_type." + Additional_abilities.MOD_ID + ".on_block_placed";

    public static final MapCodec<OnBlockPlaced> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LootItemCondition.DIRECT_CODEC.optionalFieldOf("condition").forGetter(OnBlockPlaced::condition)
    ).apply(instance, OnBlockPlaced::new));

    /**
     * 事件分发：接线见主类 {@code Additional_abilities} 构造函数
     * （{@code NeoForge.EVENT_BUS.addListener(OnBlockPlaced::trigger)}，游戏中事件总线）。
     * <p>
     * 判定流程与 {@code OnBlockBreak#trigger} 逐行对应：非玩家 / 非龙直接跳过 →
     * {@link Lazy} 延迟构建方块上下文（仅在确有技能需要判定时才创建）→
     * 遍历该玩家全部被动技能筛出本触发器 → 逐个 {@code tick}。
     */
    public static void trigger(final BlockEvent.@NotNull EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        DragonStateHandler handler = DragonStateProvider.getData(player);

        if (!handler.isDragon()) {
            return;
        }

        Lazy<LootContext> context = Lazy.of(() -> Condition.blockContext(player, event.getPos(), event.getState()));

        MagicData.getData(player).filterPassiveByTrigger(trigger -> trigger instanceof OnBlockPlaced onBlockPlaced && onBlockPlaced.test(context.get()))
                .forEach(ability -> ability.tick(player));
    }

    @Override
    public boolean test(final @NotNull LootContext context) {
        return this.condition.map(condition -> condition.test(context)).orElse(true);
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
