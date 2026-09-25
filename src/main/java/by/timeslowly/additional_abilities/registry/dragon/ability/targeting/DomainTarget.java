package by.timeslowly.additional_abilities.registry.dragon.ability.targeting;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.AbilityTargeting;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.dragonsurvivalteam.dragonsurvival.util.Functions;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainAnchor;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainData;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainInstance;
import by.timeslowly.additional_abilities.common.ability.targeting.domains.DomainShape;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * 龙之技能目标选择器：<b>领域</b>（{@code additional_abilities:domain}）。
 *
 * <h2>它和内建目标类型的根本区别</h2>
 * DS 全部 5 种内置目标类型（{@code self} / {@code area} / {@code disc} / {@code looking_at} /
 * {@code dragon_breath}）都是<b>一次性</b>的：{@code apply} 被调用的那一瞬间决定作用对象、
 * 立刻执行效果，之后什么都不留下。本类型改为<b>在锚点建立一块持续存在的区域</b>，
 * 按 {@code apply_interval} 反复结算，{@code duration} 到期后消失。
 *
 * <h2>为什么做成目标类型，而不是激活类型或实体效果</h2>
 * <ul>
 *     <li>作用链是 {@code actions[] → target_selection → applied_effects}，
 *         {@code applied_effects}（{@code Either<BlockTargeting, EntityTargeting>}）只在这一层，
 *         并由 {@link AbilityTargeting#codecStart} 暴露。</li>
 *     <li>只有做成<b>目标类型</b>才能原样复用这一字段，从而让 DS 的<b>全部 33 种实体效果与全部方块效果</b>
 *         （{@code modifier} / {@code potion} / {@code run_function} / {@code damage} /
 *         {@code summon_entity} / {@code particle} / {@code fire} / {@code conversion} …）零适配可用，
 *         且上游新增效果自动生效。</li>
 *     <li>做成激活类型拿不到 {@code applied_effects}（它在 {@code target_selection} 里），
 *         只能把效果字段整套重定义一遍；而且 {@code Activation#type()} 是枚举
 *         （PASSIVE / SIMPLE / CHANNELED），自定义激活也跳不出这三类生命周期。</li>
 *     <li>做成实体效果则无法表达"领域"这个空间概念（实体效果的形参只有单个目标）。</li>
 * </ul>
 *
 * <h2>推荐搭配的激活类型</h2>
 * <table border="1">
 *     <caption>两种激活下的语义</caption>
 *     <tr><th>activation</th><th>行为</th></tr>
 *     <tr><td>{@code dragonsurvival:simple}</td>
 *         <td>{@code apply} 只在读条完成那一帧被调用一次（{@code currentTick == cast_time}），
 *             领域从此独活到 {@code duration} 到期。这是「领域」的主用法。</td></tr>
 *     <tr><td>{@code dragonsurvival:channeled}</td>
 *         <td>引导期间每 {@code trigger_rate} tick 调一次 {@code apply}。本类型把这类连续调用
 *             识别为<b>同一条施法流的续帧</b>并只做「续命」（见
 *             {@link #apply}），因此语义是「引导期间维持领域，松手 / 断魔 / 到
 *             {@code max_duration} 之后领域再按 {@code duration} 自然消散」。</td></tr>
 * </table>
 *
 * <h2>两级节流</h2>
 * <pre>
 * trigger_rate    （激活层） 决定 apply 被调用的频率
 * apply_interval  （本类型） 决定领域内部真正的结算频率
 * </pre>
 * 两者独立：续命<b>不会</b>重置 {@code apply_interval} 的节拍（实现见
 * {@link DomainInstance} 的类注释）。{@code channeled} 下若把 {@code trigger_rate}
 * 设在 {@code apply_interval} 以下，效果结算仍严格按 {@code apply_interval} 进行。
 *
 * <h2>JSON 用法</h2>
 * <pre>
 * "target_selection": {
 *   "target_type": "additional_abilities:domain",
 *   "radius": 6.0,                        // 必填。球形/方形=每轴半边长；柱形=水平半径
 *   "duration": 200.0,                    // 必填。领域存活 tick；channeled 下语义为"最后一次结算后还能活多久"
 *   "shape": "sphere",                    // 可选，默认 sphere；另可 cube / cylinder
 *   "height": 3.0,                        // 可选，默认 = radius；仅 cylinder
 *   "height_starts_below": false,         // 可选，默认 false；仅 cylinder
 *   "apply_interval": 20.0,               // 可选，默认 20（1 秒结算一次）
 *   "anchor": "casting_position",         // 可选，默认 casting_position；另可 caster
 *   "max_domains": 1,                     // 可选，默认 1；超出时顶掉最旧的一个
 *   "remove_effects_on_end": false,       // 可选，默认 false；true 时消散前对范围内目标调 remove
 *   "applied_effects": { "entity_effect": [ ... ], "targeting_mode": "allies_and_self" }
 * }
 * </pre>
 * {@code radius} / {@code duration} / {@code height} / {@code apply_interval} 均支持原版
 * {@link LevelBasedValue} 全部写法（{@code linear} / {@code lookup} / {@code constant} …）。
 *
 * <h2>注意事项</h2>
 * <ul>
 *     <li><b>{@code cast_time ≥ 1} 是硬性要求</b>（{@code simple} 下）：{@code ActionContainer#tick}
 *         用 {@code actualTick = currentTick - cast_time} 做节流，若 {@code cast_time = 0}，
 *         首次结算的 {@code actualTick = 1}；此时 {@code trigger_rate > 1} 会让 {@code 1 % rate != 0}，
 *         <b>{@code apply} 永不执行、领域根本不会生成</b>。{@code channeled} 下同样的配置只是
 *         把首次结算推迟到第 {@code trigger_rate} tick，不会永不触发。</li>
 *     <li>领域<b>不持久化</b>：只活在内存里，服务端重启即消失。理由见 {@link DomainData} 类注释。</li>
 *     <li>{@code should_remove_automatically}（各类 DurationInstance 效果的字段）判的是
 *         「目标与<b>施法者</b>的距离」，而本组件的锚点通常固定在施法位置，两者口径不同。
 *         需要"离开领域即失效"时请用 {@link #removeEffectsOnEnd}。</li>
 *     <li>方块分支传入的 {@code direction} 为 {@code null}（领域锚点没有命中面概念），
 *         与 DS {@code area} / {@code disc} 一致。</li>
 * </ul>
 */
public record DomainTarget(Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> target,
                           LevelBasedValue radius,
                           LevelBasedValue duration,
                           DomainShape shape,
                           Optional<LevelBasedValue> height,
                           boolean heightStartsBelow,
                           LevelBasedValue applyInterval,
                           DomainAnchor anchor,
                           int maxDomains,
                           boolean removeEffectsOnEnd) implements AbilityTargeting {
    /**
     * 方块分支目标描述。参数依次为：
     * <b>形状</b> / <b>锚点</b> / <b>半径</b> / <b>持续秒数</b> / <b>结算间隔秒数</b>。
     * <p>
     * 中英两版<b>参数位置必须一致</b>——翻译参数是按位置取值的，一旦英文把形状与锚点调换，
     * 中文改英文时就会出现「在球形生成施法位置领域」这种错位。
     */
    private static final String DOMAIN_TARGET_BLOCK = "additional_abilities.gui.ability_target.domain.block";

    /**
     * 实体分支目标描述。参数依次为：
     * <b>形状</b> / <b>锚点</b> / <b>半径</b> / <b>持续秒数</b> / <b>结算间隔秒数</b> / <b>阵营模式</b>。
     */
    private static final String DOMAIN_TARGET_ENTITY = "additional_abilities.gui.ability_target.domain.entity";

    /** {@code apply_interval} 缺省值：20 tick（1 秒）。与 DS 内置范围技能习惯的 10~20 一致。 */
    private static final int DEFAULT_APPLY_INTERVAL = 20;

    /**
     * 本类型共 11 个字段，<b>超过了 {@code AbilityTargeting#codecStart} 的链式上限</b>，因此这里
     * 直接写 {@code instance.group(...)} 并内联 {@code applied_effects}。
     *
     * <h2>为什么不能照抄其它目标类型的 {@code codecStart(...).and(...)} 写法</h2>
     * DFU 的 {@code Products.P8} <b>没有</b> {@code and} 方法——{@code .and()} 链在 P8 截止
     * （已核对 {@code datafixerupper-8.0.16-sources.jar}：{@code P1..P7} 各有若干 {@code and}，
     * {@code P8} 只有 {@code apply}）。也就是说 {@code codecStart}（P1）+ 7 次 {@code .and()}
     * = 最多 <b>8 个组件</b>；本类型要 11 个，链不下去了。
     * <p>
     * 而 {@code group} 是 {@code Kind1} 上的 <b>default 方法</b>（同一份源码里 {@code Kind1.java}），
     * 提供 1~16 个参数的重载，因此单次 {@code group(...11 个...)} → {@code P11} →
     * {@code P11.apply(Function11)} 完全可行。
     * <p>
     * 内联的那一行与 {@code AbilityTargeting#codecStart} 的<b>实现逐字一致</b>
     * （{@code codecStart} 本身也只是 {@code instance.group(Codec.either(...).fieldOf("applied_effects")...)}），
     * 因此解析出的 JSON 结构、字段名、默认值行为与其它目标类型没有任何差别。
     */
    public static final MapCodec<DomainTarget> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            // 公共字段 applied_effects：与 AbilityTargeting#codecStart 内联的写法完全一致
            Codec.either(AbilityTargeting.BlockTargeting.CODEC, AbilityTargeting.EntityTargeting.CODEC)
                    .fieldOf("applied_effects").forGetter(DomainTarget::target),
            LevelBasedValue.CODEC.fieldOf("radius").forGetter(DomainTarget::radius),
            LevelBasedValue.CODEC.fieldOf("duration").forGetter(DomainTarget::duration),
            DomainShape.CODEC.optionalFieldOf("shape", DomainShape.SPHERE).forGetter(DomainTarget::shape),
            LevelBasedValue.CODEC.optionalFieldOf("height").forGetter(DomainTarget::height),
            Codec.BOOL.optionalFieldOf("height_starts_below", false).forGetter(DomainTarget::heightStartsBelow),
            LevelBasedValue.CODEC.optionalFieldOf("apply_interval", LevelBasedValue.constant(DEFAULT_APPLY_INTERVAL)).forGetter(DomainTarget::applyInterval),
            DomainAnchor.CODEC.optionalFieldOf("anchor", DomainAnchor.CASTING_POSITION).forGetter(DomainTarget::anchor),
            Codec.INT.optionalFieldOf("max_domains", 1).forGetter(DomainTarget::maxDomains),
            Codec.BOOL.optionalFieldOf("remove_effects_on_end", false).forGetter(DomainTarget::removeEffectsOnEnd)
    ).apply(instance, DomainTarget::new));

    /**
     * 建立领域，或并入已有的同一次施法的领域。
     *
     * <h2>如何区分「同一次施法」与「新一次施法」——castStartTick</h2>
     * DS 没有暴露"施法 ID"，但 {@code now - (currentTick - 1)} 在语义上就是
     * <b>本次技能激活的起点世界刻</b>（{@code MagicData#beginCasting} → {@code setActive(true)}；
     * {@code stopCasting} → {@code setActive(false)}，而该分支把 {@code currentTick} 归零）。
     * 同一次施法内它<b>恒定</b>，不同次施法必然不同：
     * <pre>
     * 已有领域的 castStartTick == 本次的 castStartTick → 同一次施法 → 续命 + 并入效果集
     * 否则                                            → 新一次施法 → 顶替（旧领域收尾）/ 新建
     * </pre>
     * 逐情形验证：
     * <ul>
     *     <li>{@code simple}（{@code cast_time = T}）：每次施法唯一一次结算在 {@code currentTick == T}，
     *         两次施法的起点必然相隔 &gt; 0 tick ⇒ 起点不同，每次都是新施法 ✅
     *         （<b>不能</b>直接用 {@code currentTick} 比较：{@code simple} 每次的
     *         {@code currentTick} 序列完全相同，无法区分新旧施法）；</li>
     *     <li>{@code channeled}：{@code cast_time} 帧建立后 {@code currentTick} 与 {@code now}
     *         同步递增，{@code castStartTick} 保持不变 ⇒ 全部判为同一次施法 ⇒ 续命 ✅；</li>
     *     <li>{@code passive} + {@code cooldown}（周期性自动释放，{@code cast_time} 恒为 0，
     *         每次 {@code currentTick == 1}）：{@code castStartTick = 当前刻} ⇒ 每个周期都是新施法 ✅。</li>
     * </ul>
     * <b>同一帧内多个 domain action 也会命中"同一次施法"</b>：技能的 {@code actions[]} 里若同时写了
     * 一个实体效果、一个方块效果（{@code applied_effects} 是 Either，装不下两者），
     * 两者共享同一个 {@code castStartTick}，于是后者会 {@link DomainInstance#join} 进同一个领域
     * ——<b>一个领域、一份锚点计时、两组效果</b>，而不是互相顶替。详见 {@link DomainInstance} 类注释。
     *
     * <h2>首次结算即时生效</h2>
     * 新建领域后立刻调用 {@link DomainInstance#applyNow}，不等到下一个
     * {@code apply_interval}。这样 {@code simple} 的体验与直接使用 {@code area} 一致
     * （读条完成的瞬间效果就落下），之后才进入周期性结算。
     */
    @Override
    public void apply(final @NotNull ServerPlayer dragon, final @NotNull DragonAbilityInstance ability) {
        ServerLevel level = dragon.serverLevel();
        DomainData data = level.getData(AAAttachments.DOMAIN);
        long now = level.getGameTime();
        int currentTick = ability.getCurrentTick();
        // LevelBasedValue 不接受等级 0（Lookup#calculate(0) 会 values.get(-1) 越界），DS 自己也用
        // MIN_LEVEL_FOR_CALCULATIONS = 1 规避。领域可能由等级 0 的 passive 技能建立，故统一钳到 1，
        // 并把同一个值固化进 DomainInstance —— 否则后续各效果仍会以 0 级求值。
        int abilityLevel = Math.max(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS, ability.level());
        // 施法起点世界刻 = 流身份。currentTick 在 apply 时至少为 1（tickActions 先 ++ 再执行动作）
        long castStartTick = now - (currentTick - 1);

        DomainInstance latest = data.latest(dragon.getUUID(), ability.key());

        // 同一次施法（含 channeled 的续帧、以及同一帧里的后续 action）：续命 + 并入效果集。
        // 刻意不动锚点——否则 channeled 下领域会跟着施法者一跳一跳地迁移。
        if (latest != null && latest.castStartTick() == castStartTick) {
            latest.join(now, target);
            return;
        }

        // 新一次施法：参数在此刻固化
        int resolvedDuration = Math.max(1, (int) duration.calculate(abilityLevel));
        int resolvedInterval = Math.max(1, (int) applyInterval.calculate(abilityLevel));
        double resolvedRadius = Math.max(0.0, radius.calculate(abilityLevel));
        // height 未配置时跟随 radius：让"柱形"在默认情况下与球形 / 方形同尺度，而不是退化成一片扁盘
        // 注意 LevelBasedValue#calculate 返回 float，必须显式转 double 才能与 orElse 的 double 相容
        double resolvedHeight = height.map(value -> (double) value.calculate(abilityLevel)).orElse(resolvedRadius);
        int limit = Math.max(1, maxDomains);

        // 超出上限：顶掉最旧的同键领域（会走同一条收尾路径）
        while (data.count(dragon.getUUID(), ability.key()) >= limit
                && data.evictOldest(level, dragon.getUUID(), ability.key())) {
            // 腾出位置为止
        }

        DomainInstance domain = new DomainInstance(dragon.getUUID(), ability, abilityLevel, target, shape, anchor,
                resolvedRadius, resolvedHeight, heightStartsBelow, resolvedInterval, resolvedDuration,
                removeEffectsOnEnd, dragon.position(), now, castStartTick);

        data.add(domain);
        // 首次结算立即生效（simple：读条完成即落下；channeled：引导正式开始那一帧即落下）
        domain.applyNow(level);
    }

    /** 领域半径（已按技能等级求值）。侧边栏 / F3+B 调试箱用。 */
    public double resolveRadius(final @NotNull DragonAbilityInstance ability) {
        return radius.calculate(ability.level());
    }

    /** 柱形厚度；未配置 {@code height} 时跟随半径。 */
    public double resolveHeight(final @NotNull DragonAbilityInstance ability) {
        // LevelBasedValue#calculate 返回 float，显式转 double 以匹配返回类型
        return height.map(value -> (double) value.calculate(ability.level())).orElseGet(() -> resolveRadius(ability));
    }

    /** 领域包围盒（粗筛区）：{@code origin} 为锚点，通常传施法者的 {@code position()} 作预览。 */
    public @NotNull AABB calculateArea(final @NotNull Vec3 origin, final @NotNull DragonAbilityInstance ability) {
        return DomainShape.createArea(shape, origin, resolveRadius(ability), resolveHeight(ability), heightStartsBelow);
    }

    @Override
    public @NotNull MutableComponent getDescription(final Player dragon, final @NotNull DragonAbilityInstance ability) {
        // 与 DS DiscTarget / 本模组 AnnulusTarget 相同的双分支文本选择：方块分支没有阵营前缀参数
        Component targetingComponent = target.map(block -> null, entity -> entity.targetingMode().translation());
        Component anchorComponent = Component.translatable(anchor.translationKey());
        Component shapeComponent = Component.translatable(shape.translationKey());
        Component radiusComponent = DSColors.dynamicValue(FORMAT.format(resolveRadius(ability)));
        Component durationComponent = DSColors.dynamicValue(FORMAT.format(Functions.ticksToSeconds((int) duration.calculate(ability.level()))));
        Component intervalComponent = DSColors.dynamicValue(FORMAT.format(Functions.ticksToSeconds((int) applyInterval.calculate(ability.level()))));

        // 参数顺序：形状 / 锚点 / 半径 / 持续秒 / 结算间隔秒（/[阵营模式]）——中英一致，见常量注释
        if (targetingComponent == null) {
            return Component.translatable(DOMAIN_TARGET_BLOCK,
                    shapeComponent, anchorComponent, radiusComponent, durationComponent, intervalComponent);
        }

        return Component.translatable(DOMAIN_TARGET_ENTITY,
                shapeComponent, anchorComponent, radiusComponent, durationComponent, intervalComponent,
                DSColors.dynamicValue(targetingComponent));
    }

    @Override
    public float getDistance(final Player dragon, final @NotNull DragonAbilityInstance instance) {
        return (float) resolveRadius(instance);
    }

    @Override
    public MapCodec<? extends AbilityTargeting> codec() {
        return CODEC;
    }
}
