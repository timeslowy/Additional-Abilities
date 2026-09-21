package by.timeslowly.additional_abilities.registry.dragon.ability.activation.trigger;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.datagen.Translation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.trigger.ActivationTrigger;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.dragonsurvivalteam.dragonsurvival.util.Functions;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 使用技能后触发（{@code additional_abilities:on_ability_cast}）—— 用于「放了 A 技能，就连带触发被动 P」这类连携设计。
 * <p>
 * 字段只有一个可选的 {@code abilities}（技能集合，省略即<b>任意主动技能</b>），与 {@code trigger_type}
 * <b>平级</b>书写：
 * <pre>{@code
 * "activation": {
 *   "activation_type": "dragonsurvival:passive",
 *   "cooldown": 20.0,
 *   "trigger": {
 *     "trigger_type": "additional_abilities:on_ability_cast",
 *     "abilities": ["additional_abilities:explosion_arrow"]
 *   }
 * }
 * }</pre>
 *
 * <h2>字段编解码（照抄 {@code dragonsurvival:cooldown_recovery} 的 {@code abilities}）</h2>
 * {@code RegistryCodecs.homogeneousList(DragonAbility.REGISTRY).optionalFieldOf("abilities")} →
 * {@link Optional}{@code <}{@link HolderSet}{@code <}{@link DragonAbility}{@code >>}。因此：
 * <ul>
 *     <li>标签：{@code "abilities": "#dragonsurvival:wing_kirin"} ✅（DS 的
 *         {@code test_cooldown_recovery.json} 即此写法）；</li>
 *     <li>id 列表：{@code "abilities": ["additional_abilities:explosion_arrow", "…"]} ✅；</li>
 *     <li>裸单个 id 字符串：{@code "abilities": "additional_abilities:explosion_arrow"} ✅
 *         —— {@code RegistryCodecs.homogeneousList(…)} 内部走的是
 *         {@code homogeneousList(…, disallowInline = false)}，也就是
 *         {@code Codec.either(元素列表, 单元素)}（见 1.21.1 {@code HolderSetCodec#homogenousList}），
 *         故「单个」与「单元素列表」两种写法等价；</li>
 *     <li>省略 → 空 {@link Optional}，即<b>任意主动技能</b>施法都会触发（与 {@code cooldown_recovery}
 *         的「未指定即全部」约定一致）。</li>
 * </ul>
 *
 * <h2>触发时机：施法「结算」而非「起手」</h2>
 * 挂点见 {@link by.timeslowly.additional_abilities.mixins.MagicDataMixin} —— 位于
 * {@code MagicData#stopCasting(Player, DragonAbilityInstance, boolean)} 的 {@code withCooldown == true} 分支，
 * 其值来自 {@code instance.isApplyingEffects()}，含义是「本次施法的效果<b>已经真正生效</b>」：
 * <table border="1">
 *     <caption>各施法结局是否触发</caption>
 *     <tr><th>结局</th><th>触发</th></tr>
 *     <tr><td>simple 读条完成，动作已执行</td><td>✅</td></tr>
 *     <tr><td>channeled 引导中松手 / 达到 {@code max_duration}</td><td>✅（一次）</td></tr>
 *     <tr><td>本模组蓄力族系按档位释放（{@code ChargedCasts#fire}）</td><td>✅</td></tr>
 *     <tr><td>本模组蓄力族系「改为取消」（滚到 0 档松手）</td><td>❌ 传 {@code withCooldown = false}</td></tr>
 *     <tr><td>松手早于 {@code cast_time}（客户端提前停手）</td><td>❌ 效果未生效</td></tr>
 *     <tr><td>用另一个技能打断上一次施法</td><td>❌ 走 {@code release()} 而非 {@code stopCasting}</td></tr>
 * </table>
 * 之所以不用 {@code MagicData#attemptCast}：那样「起手即触发」，会把「中途取消 / 魔力不足 / 被自己打断」
 * 也算作「用过」。也不用 {@code DragonAbilityInstance#tickActions} 里 {@code currentTick == castTime}
 * 那段：本模组的 {@code optional_charged} 会把完成判定撑成 {@code Integer.MAX_VALUE}，该分支永不进入。
 *
 * <h2>防环：结构性免疫，无需 {@code exclude_this}</h2>
 * DS 里<b>被动技能永远不可能被「使用」</b>：{@code MagicData#addAbility} / {@code #refresh} 只把
 * <b>非被动</b>技能写进 hotbar，而 {@code attemptCast} 经 {@code fromSlot → getHotbar} 取实例。
 * 本触发器又在 {@link #trigger} 里显式跳过 {@code instance.isPassive()}，因此：
 * <ul>
 *     <li>把本被动<b>自身的 id 写进 {@code abilities} 也绝不会自环</b>（它永远不会成为「被使用的技能」）；</li>
 *     <li>被动自身被触发时，其 {@code tickActions} 末尾的 {@code stopCasting}（带冷却的被动才有）走到
 *         {@code MagicDataMixin} 也会因 {@code isPassive()} 被拦下，不会递归。</li>
 * </ul>
 * 与 {@code on_block_break} / {@code on_block_placed} / {@code on_item_consumed} 一致，
 * 这里<b>不检查</b> {@code DragonAbilityInstance#triggered}：该标志由 DS 的玩家 tick 循环每刻重置，
 * 在「连续两刻各放一次技能」的时序下会出现「上一刻置位、本刻尚未重置」的窗口，反而会<b>漏触发</b>一次。
 * 需要限制触发频率时请给被动配 {@code cooldown}（被动带冷却时也会被 DS 的原生流程正确施加冷却）。
 *
 * <h2>语言键</h2>
 * 恒为 {@code trigger_type.additional_abilities.on_ability_cast}（省略 {@code abilities} 时其 {@code %s}
 * 填 {@code trigger_type.additional_abilities.on_ability_cast.any}）
 * —— <b>不能</b>借用 DS 的 {@code Translation.Type.TRIGGER_TYPE.wrap(...)}：该枚举前缀硬编码为
 * {@code trigger_type.dragonsurvival.}，单参重载会生成 DS 命名空间下的键。
 */
public record OnAbilityCast(Optional<HolderSet<DragonAbility>> abilities) implements ActivationTrigger<Holder<DragonAbility>> {
    /** 侧边栏展示名（见 {@code dragonsurvival.gui.ability.activation_trigger} 的 %s 占位符） */
    private static final String TRANSLATION = "trigger_type." + AdditionalAbilities.MOD_ID + ".on_ability_cast";

    /** 省略 {@code abilities} 时填进 {@code %s} 的文案（语义：任意主动技能都会触发） */
    private static final String ANY_ABILITY = TRANSLATION + ".any";

    public static final MapCodec<OnAbilityCast> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RegistryCodecs.homogeneousList(DragonAbility.REGISTRY).optionalFieldOf("abilities").forGetter(OnAbilityCast::abilities)
    ).apply(instance, OnAbilityCast::new));

    /**
     * 事件分发：由 {@code mixins.MagicDataMixin} 在 {@code MagicData#stopCasting} 的
     * {@code withCooldown == true} 分支调用（那里只做「是否该分发」的廉价判定，全部业务逻辑在这里）。
     * <p>
     * 守卫顺序与 {@code OnBlockPlaced#trigger} 一致：先排除不可能被「使用」的被动，再要求调用方是
     * 服务端玩家、且处于龙形态；随后把「被使用的技能」交给 {@link #test} 判定集合归属。
     *
     * @param player   施法者（应已由调用方确认为服务端玩家）
     * @param cast     刚完成结算的那个技能实例（即「被使用的技能」）
     */
    public static void trigger(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance cast) {
        // 被动技能不构成「使用」：hotbar 只放非被动，attemptCast 也只从 hotbar 取实例，
        // 因此这一条同时是「把自身写进 abilities 也不会自环」的结构性保证
        if (cast.isPassive()) {
            return;
        }

        DragonStateHandler handler = DragonStateProvider.getData(player);

        if (!handler.isDragon()) {
            return;
        }

        Holder<DragonAbility> used = cast.ability();

        MagicData.getData(player)
                .filterPassiveByTrigger(trigger -> trigger instanceof OnAbilityCast onAbilityCast && onAbilityCast.test(used))
                .forEach(ability -> ability.tick(player));
    }

    @Override
    public boolean test(final @NotNull Holder<DragonAbility> used) {
        return this.abilities.map(set -> set.contains(used)).orElse(true);
    }

    /**
     * 侧边栏名称：展开被指定的技能名；为标签时显示标签名（经
     * {@code Functions#translateHolderSet} 统一处理）；省略时显示「任意技能」。
     */
    @Contract(value = " -> new", pure = true)
    @Override
    public @NotNull Component translation() {
        MutableComponent target = abilities
                .map(set -> Functions.translateHolderSet(set, Translation.Type.ABILITY))
                .orElse(Component.translatable(ANY_ABILITY));

        return Component.translatable(TRANSLATION, DSColors.dynamicValue(target));
    }

    @Override
    public MapCodec<? extends ActivationTrigger<?>> codec() {
        return CODEC;
    }
}
