package by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.Condition;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.trigger.ActivationTrigger;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.activation.PendingBlockPlaces;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
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
 *     "condition": {
 *       "condition": "minecraft:location_check",
 *       "predicate": { "block": { "blocks": "#minecraft:logs" } }
 *     }
 *   }
 * }
 * }</pre>
 * ⚠️ {@code condition} 是<b>战利品条件</b>（{@code minecraft:loot_condition_type} 注册表），
 * <b>不是</b>方块谓词 —— 写成 {@code minecraft:matching_block_tag}（属 {@code block_predicate_type}）
 * 会在数据加载阶段报 {@code Unknown registry key …loot_condition_type}，并导致世界无法加载。
 * 按标签判方块用 {@code minecraft:location_check}，按单个方块 id 用 {@code minecraft:block_state_property}。
 * <p>
 * <b>事件源</b>：{@link BlockEvent.EntityPlaceEvent}（NeoForge 21.1.250 中由
 * {@code ItemStack#useOn} → {@code CommonHooks#onPlaceItemIntoWorld} 在<b>服务端</b>抛出）。
 * {@link BlockEvent.EntityMultiPlaceEvent} 是它的子类、且 {@code CommonHooks} 用 if/else 二选一，
 * 因此监听父类即可同时覆盖「单方块」与「一次放置产生多方块」（床 / 门 / 高植被），且不会重复触发。
 * <p>
 * <b>只响应最终未被取消的放置</b>：本触发器是<b>两阶段</b>的 ——
 * {@link #enqueue} 只登记候选，{@code PendingBlockPlaces} 在下一个维度刻读到事件的最终取消状态后，
 * 才由 {@link #dispatch} 执行技能。因此被别的模组（如领地保护）拒绝的放置<b>不会</b>触发技能。
 * 代价是技能比放置晚最多 1 个服务端刻执行，完整推理见 {@code PendingBlockPlaceData}。
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
    private static final String TRANSLATION = "trigger_type." + AdditionalAbilities.MOD_ID + ".on_block_placed";

    public static final MapCodec<OnBlockPlaced> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            LootItemCondition.DIRECT_CODEC.optionalFieldOf("condition").forGetter(OnBlockPlaced::condition)
    ).apply(instance, OnBlockPlaced::new));

    /**
     * 事件分发：接线见主类 {@code AdditionalAbilities} 构造函数
     * （{@code NeoForge.EVENT_BUS.addListener(OnBlockPlaced::enqueue)}，游戏中事件总线）。
     * <p>
     * <b>本方法只登记，不执行技能</b>：事件正在派发、最终取消状态未知，
     * 此刻执行会让"被别的模组拒绝的放置"也触发技能。真正的分发在
     * {@link #dispatch(ServerPlayer, net.minecraft.core.BlockPos, BlockState)}，
     * 由 {@code PendingBlockPlaces} 在下一个维度刻、读到最终取消状态之后调用。
     */
    public static void enqueue(final BlockEvent.@NotNull EntityPlaceEvent event) {
        PendingBlockPlaces.enqueue(event);
    }

    /**
     * 确认后的实际分发：判定流程与 {@code OnBlockBreak#trigger} 逐行对应 ——
     * {@link Lazy} 延迟构建方块上下文（仅在确有技能需要判定时才创建）→
     * 遍历该玩家全部被动技能筛出本触发器 → 逐个 {@code tick}。
     *
     * <h2>为什么不再检查"是不是龙"</h2>
     * 龙形态判定已经在 {@link #enqueue} 阶段做过一次（那才是"事件发生当时"的正确状态）。
     * 这里若再判一次，反而会把「放置瞬间是龙、确认前一刻变形回人」这种正常情况误杀。
     * 状态取放置当时的口径，才与"放置者自己的技能因放置而触发"这一语义一致。
     *
     * @param state 放置<b>当时</b>的方块状态（由登记侧复制），用于构建方块战利品上下文
     */
    public static void dispatch(final @NotNull ServerPlayer player, final @NotNull BlockPos position,
                                final @NotNull BlockState state) {
        Lazy<LootContext> context = Lazy.of(() -> Condition.blockContext(player, position, state));

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
