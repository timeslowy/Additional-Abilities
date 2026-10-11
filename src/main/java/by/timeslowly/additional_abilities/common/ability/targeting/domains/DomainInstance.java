package by.timeslowly.additional_abilities.common.ability.targeting.domains;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.AbilityTargeting;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一个已建立、正在存活的<b>领域实例</b>（{@code additional_abilities:domain} 目标类型的运行时载体）。
 * <p>
 * 它是一次施法的<b>固化快照</b>：锚点、形状、半径、间隔、效果列表在<b>首次结算</b>时定格，
 * 之后只由 {@link DomainData} 逐 tick 推进到期时间与结算节拍。
 *
 * <h2>一次施法 = 一个领域，效果按 action「并入」而不是各建一个</h2>
 * 技能 JSON 的 {@code actions[]} 是列表，而 {@code applied_effects} 是
 * {@code Either<block_effect[], entity_effect[]>}（<b>互斥</b>）。因此「同一个领域既影响实体、
 * 又影响方块」只能写成两个 {@code target_selection}，都指向本目标类型。
 * <p>
 * 若每个 action 各建一个领域，同一帧内第二个 action 会把第一个顶掉（{@code max_domains} 语义），
 * 第一个的效果静默丢失。因此本类持有<b>一组成员效果</b>（{@code effectSets}），
 * 同一次施法内的后续 action 通过 {@link #join} 把自己的效果集<b>并入</b>：
 * 同一个领域、同一份锚点与计时，只是效果清单多了几项。
 * <ul>
 *     <li>并入按 {@code equals} 去重——{@code channeled} 每 {@code trigger_rate} tick 都会 join 一次，
 *         不会让清单无限增长；两个字段完全相同的 action 也只会生效一次（重复施加同效果本就无意义）。</li>
 *     <li>{@code max_domains} 因此被解释为「同一技能<b>同时存在几次施法的领域</b>」，
 *         而不是"几个 action"，这才是玩家能理解的语义。</li>
 *     <li><b>每个新增组都立即执行首轮</b>（{@link #applyGroupNow}），不等到下一个
 *         {@code apply_interval}；同组续帧不重复执行。理由见该方法的注释（{@code duration}
 *         小于 {@code apply_interval} 时后续组会彻底丢失）。首批仍由 {@link #applyNow} 统一执行。</li>
 * </ul>
 *
 * <h2>回调重入</h2>
 * 效果回调（{@code AbilityEntityEffect#apply} / {@code AbilityBlockEffect#apply}）可以执行任意技能效果，
 * 包括 {@code run_function} —— 也就是说回调链完全可能反过来触发新施法、{@link #join} 新组、
 * 甚至把本领域整个清掉。本类对此有三道处理：
 * <ol>
 *     <li><b>遍历前先做快照</b>（{@link #effectSets} 字段注释）：避免
 *         {@code ConcurrentModificationException}；</li>
 *     <li><b>执行前复查 {@link #removed}</b>：领域已被移除就不再结算，
 *         且在 {@link #applySet} 里<b>逐目标</b>复查 —— 回调在结算途中移除领域时，
 *         剩余目标不会继续吃效果；</li>
 *     <li><b>收尾幂等</b>（{@link #end} 的 {@code ended} 闸门）：同一次收尾被重入时不会重复施加移除。</li>
 * </ol>
 * 另需注意：快照<b>只</b>解决并发修改异常，不解决"结算顺序"问题 —— 见 {@link DomainData#tick}。
 *
 * <h2>如何识别「同一次施法」——castStartTick（施法起点世界刻）</h2>
 * DS 没有暴露"施法 ID"，但 {@code now - (currentTick - 1)} 在语义上就是
 * <b>本次技能激活的起点世界刻</b>（{@code MagicData#beginCasting} 触发 {@code setActive(true)}，
 * 而 {@code stopCasting} 走 {@code setActive(false)} 会把 {@code currentTick} 归零）。
 * 同一次施法内它恒定，不同次施法必然不同，因此可以用它做"流身份"：
 * <pre>
 * 已有领域的 castStartTick == 本次计算的 castStartTick → 同一次施法 → 续命 + 并入效果
 * 否则                                              → 新一次施法 → 顶替 / 新建
 * </pre>
 * 逐情形验证：
 * <ul>
 *     <li>{@code simple}（{@code cast_time = T}）：每次施法唯一一次结算在 {@code currentTick == T}，
 *         {@code castStartTick = 结算刻 - (T-1)}，两次施法间隔必然 &gt; 0 tick ⇒ 起点不同 ✅</li>
 *     <li>{@code channeled}：{@code cast_time} 帧建立后，后续 {@code currentTick} 递增而
 *         {@code now} 同步递增，{@code castStartTick} <b>保持不变</b> ⇒ 判为续命 ✅；</li>
 *     <li>{@code passive} + {@code cooldown}（{@code cast_time} 恒 0，每次 {@code currentTick == 1}）：
 *         {@code castStartTick = 当前刻} ⇒ 每个周期都是新施法 ✅。</li>
 * </ul>
 * 对比之下，直接用 {@code currentTick} 比较是不行的：{@code simple} 每次施法的
 * {@code currentTick} 序列完全相同（都从 1 走到 T），无法区分新旧施法。
 *
 * <h2>两级节流——为什么用两个「绝对世界 tick」而不是一个 elapsed 计数器</h2>
 * <pre>
 * nextApplyTick : 下一次结算的世界 tick，固定步进 apply_interval
 * expireTick    : 到期（消散）的世界 tick
 * </pre>
 * {@code channeled} 会每 {@code trigger_rate} tick 调一次
 * {@link by.timeslowly.additional_abilities.registry.dragon.ability.targeting.DomainTarget#apply}，
 * 每次都要「续命」。如果把续命实现成「把 elapsed 归零」，就会<b>连带把结算节拍也重置</b>——
 * 于是 {@code trigger_rate: 1} 的引导技能会让 elapsed 永远到不了 {@code apply_interval}，
 * <b>领域内的效果永不结算</b>。用两个独立的绝对 tick 字段后，续命只推后 {@code expireTick}，
 * {@code nextApplyTick} 完全不受影响，两级节流互不干扰。
 *
 * <h2>施法者不可用时的行为</h2>
 * 效果回调（{@code AbilityEntityEffect#apply} / {@code AbilityBlockEffect#apply}）的形参是
 * {@code ServerPlayer}，而且战利品条件上下文（{@code Condition#abilityContext} /
 * {@code #blockContext}）也要读施法者的 {@code serverLevel()}。因此当施法者离线、
 * 或已跨维度离开本领域所在维度时：<b>跳过本次结算，但计时照走</b>——领域不会卡住世界，
 * 也不会出现"施法者回来时领域才继续"的隐式暂停。
 */
public class DomainInstance {
    private final UUID casterUUID;
    private final ResourceKey<DragonAbility> abilityKey;

    /**
     * 领域自持的技能实例：<b>由首次结算时的等级固化而来，不共享施法者的活实例</b>。
     * <p>
     * 之所以要复制而不是直接引用施法者那个实例：DS 每 tick 都会尝试技能升级
     * （{@code MagicData#tickAbilities} 里的 {@code upgrade.attempt}），若共用同一实例，
     * 领域存活期间的等级变化会实时改变 {@code radius} / {@code duration} 等 LevelBasedValue 的求值结果，
     * 破坏"一次施法 = 一份固化快照"的语义。复制一份后，各效果读到的 {@code ability.level()} 恒定。
     * <p>
     * 效果实现只消费 {@code ability.level()} / {@code ability.key()}（已核实：{@code RunFunctionEffect}
     * 甚至完全不使用该参数），因此一个"脱离施法流程"的实例完全够用，且不会被 DS 的 tick 触碰。
     * <p>
     * <b>等级下限为 1</b>（{@link DragonAbilityInstance#MIN_LEVEL_FOR_CALCULATIONS}）：
     * {@code LevelBasedValue} 不接受等级 0（{@code Lookup#calculate(0)} 会 {@code values.get(-1)} 越界，
     * DS 自己也用该常量规避）。领域可能由等级 0 的 {@code passive} 技能建立，
     * 因此构造时统一钳到 1，避免各效果在求值时抛异常。
     */
    private final DragonAbilityInstance ability;

    /**
     * 本领域要施加的效果集列表（每个 {@code target_selection} 一项）。
     * <p>
     * 用列表而非单值，是为了支持"同一技能里一个 action 管实体、另一个管方块"
     * （{@code applied_effects} 是 Either，装不下两者）。详见类注释。
     * <p>
     * <b>遍历它时必须先做快照</b>：效果回调可以执行任意技能效果（含 {@code run_function}），
     * 而那些效果可能反过来触发新的施法，经 {@link #join} 往这个列表里追加 ——
     * 直接在 {@code for-each} 里遍历会抛 {@code ConcurrentModificationException}。
     * 见 {@link #applyNow} / {@link #end}。
     */
    private final List<Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting>> effectSets;

    /**
     * 本领域是否已被移出 {@code DomainData}。
     * <p>
     * 置位后<b>不再产生任何结算</b>（{@link #applyNow} / {@link #applyGroupNow} 直接返回）。
     * 置位时机见 {@link DomainData#remove}：先置位、再 {@link #end}，这样收尾回调里发生的重入
     * 也能看到"本领域已撤"。
     * <p>
     * 为什么需要这个显式标志，而不是只靠"还在不在 DomainData 的表里"：
     * {@link DomainData#tick} 遍历的是表快照，某个领域的效果在回调里清掉了<b>后面的</b>领域时，
     * 快照里那个已被移除的对象仍会被依次访问到 —— 没有这个标志，它还会照常结算一次。
     */
    private boolean removed;

    /**
     * 收尾是否已执行过（幂等闸门）。与 {@link #removed} 分开：那个表示"已被移出、不得再结算"，
     * 这个表示"{@link #end} 已经跑过一遍"，防止重入时把移除回调重复施加到同一批实体上。
     */
    private boolean ended;

    private final DomainShape shape;
    private final DomainAnchor anchor;
    private final double radius;
    private final double height;
    private final boolean heightStartsBelow;
    private final int applyInterval;
    private final int duration;
    private final boolean removeEffectsOnEnd;

    /** 固定锚点（{@link DomainAnchor#CASTING_POSITION} 时有效）。 */
    private final Vec3 anchorPosition;

    /** 本次施法的起点世界刻，即"流身份"。见类注释。 */
    private final long castStartTick;

    private long expireTick;
    private long nextApplyTick;

    public DomainInstance(final UUID casterUUID,
                          final @NotNull DragonAbilityInstance ability,
                          final int level,
                          final Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> effects,
                          final DomainShape shape,
                          final DomainAnchor anchor,
                          final double radius,
                          final double height,
                          final boolean heightStartsBelow,
                          final int applyInterval,
                          final int duration,
                          final boolean removeEffectsOnEnd,
                          final Vec3 anchorPosition,
                          final long now,
                          final long castStartTick) {
        this.casterUUID = casterUUID;
        this.abilityKey = ability.key();
        // 等级固化：见字段注释。level 由调用方保证 ≥ 1（LevelBasedValue 不接受 0 级）
        this.ability = new DragonAbilityInstance(ability.ability(), level);
        this.effectSets = new ArrayList<>();
        this.effectSets.add(effects);
        this.shape = shape;
        this.anchor = anchor;
        this.radius = radius;
        this.height = height;
        this.heightStartsBelow = heightStartsBelow;
        this.applyInterval = applyInterval;
        this.duration = duration;
        this.removeEffectsOnEnd = removeEffectsOnEnd;
        this.anchorPosition = anchorPosition;
        this.castStartTick = castStartTick;

        this.expireTick = now + duration;
        this.nextApplyTick = now + applyInterval;
    }

    public UUID casterUUID() {
        return casterUUID;
    }

    public ResourceKey<DragonAbility> abilityKey() {
        return abilityKey;
    }

    public long castStartTick() {
        return castStartTick;
    }

    /** 同施法者 + 同技能（领域的"流"身份键之一）。 */
    public boolean matches(final UUID caster, final ResourceKey<DragonAbility> key) {
        return casterUUID.equals(caster) && abilityKey.equals(key);
    }

    /**
     * 同一次施法的后续调用：<b>续命 + 并入效果集</b>。
     * <p>
     * 刻意<b>不</b>改变锚点、形状、半径、间隔与 {@code nextApplyTick} —— 理由见类注释与
     * {@link DomainAnchor} 的说明。这样 {@code channeled} 的语义就是"引导期间维持领域，
     * 松手 / 断魔 / 到 {@code max_duration} 之后领域再按 {@code duration} 自然消散"。
     *
     * @param effects 本次 action 的效果集
     * @return {@code true} 表示这是<b>新加入</b>的一组（调用方应对它立即执行首轮，见 F14）；
     *         {@code false} 表示这组已存在（{@code equals}），即 {@code channeled} 的续帧 ——
     *         此时<b>不</b>执行，否则续帧会变成高频重复结算
     */
    public boolean join(final long now, final Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> effects) {
        this.expireTick = Math.max(this.expireTick, now + duration);

        // 已有（内容相等）→ 续帧，返回 false；否则登记为新组并返回 true。
        // 返回值是调用方"要不要跑首轮"的唯一判据，务必与去重判定共用同一次比较
        if (effectSets.contains(effects)) {
            return false;
        }

        effectSets.add(effects);
        return true;
    }

    public boolean isExpired(final long now) {
        return now >= expireTick;
    }

    public boolean isDue(final long now) {
        return now >= nextApplyTick;
    }

    /** 推后下一次结算时刻。必须在 {@link #applyNow} 之前调用，避免效果内部再次触发统计。 */
    public void advance(final long now) {
        this.nextApplyTick = now + applyInterval;
    }

    /**
     * 解析施法者；返回 {@code null} 表示"本 tick 施法者不可用"。
     * <p>
     * 三种不可用情形都要挡成 {@code null}：
     * <ul>
     *     <li><b>离线</b>——拿不到 {@code ServerPlayer}，效果回调无法执行；</li>
     *     <li><b>已跨维度</b>——战利品条件上下文（{@code Condition#abilityContext} /
     *         {@code #blockContext}）会读 {@code dragon.serverLevel()} 的方块状态，
     *         而领域遍历的是本维度的坐标，二者混用会取到错误的世界；</li>
     *     <li><b>没有可用世界</b>（{@code level == null}）——只可能来自单元测试夹具。
     *         生产路径永远传入真实的 {@code ServerLevel}。</li>
     * </ul>
     * 最后一种情形之所以要显式挡下而不是让 NPE 抛出去：本类全部结算入口都先查
     * {@code removed} / 成员资格，再解析施法者。若不挡，单元测试就只能连"守卫是否生效"
     * 这类纯状态机断言都无法进行（会先炸在 {@code level.getServer()} 上）。
     * 挡下之后，{@code null} 世界与"施法者不可用"走完全相同的短路路径，
     * 生产行为不变。
     */
    private @Nullable ServerPlayer resolveCaster(final @Nullable ServerLevel level) {
        if (level == null) {
            return null;
        }

        ServerPlayer caster = level.getServer().getPlayerList().getPlayer(casterUUID);

        if (caster == null || caster.serverLevel() != level) {
            return null;
        }

        return caster;
    }

    /**
     * 按<b>快照</b>遍历效果集并对每组执行一次 {@code action}。
     * <p>
     * 快照是 T04 重入保护的核心：效果回调可以执行任意技能效果（含 {@code run_function}），
     * 完全可能反过来触发新施法、经 {@link #join} 往 {@code effectSets} 里追加。
     * 直接 {@code for-each} 活列表会抛 {@code ConcurrentModificationException}。
     * <p>
     * 抽成独立方法是为了让 {@link #applyNow} 与 {@link #end} 共用同一份遍历语义 ——
     * 两条路径都必须防重入，散在两处容易漏改一处；同时它也是一个可直接断言的接缝
     * （见 {@code DomainInstanceReentrancyTest} 的遍历快照用例）。
     *
     * @param action 对每组执行的动作；<b>不得</b>依赖"本次遍历会看到本次遍历中新增的组"
     */
    void forEachEffectSet(final Consumer<Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting>> action) {
        for (Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> effectSet
                : List.copyOf(effectSets)) {
            action.accept(effectSet);
        }
    }

    /**
     * 立即执行<b>全部</b>效果组一次（用于首次建立领域时"效果即时生效"）。
     * <p>
     * 遍历快照（见 {@link #forEachEffectSet}）：回调里若发生 {@link #join}，
     * 新组不会在本次遍历中被执行 —— 它由调用方通过 {@link #applyGroupNow} 单独跑首轮，
     * 从而保证"每个新增组恰好首轮执行一次"。
     */
    public void applyNow(final ServerLevel level) {
        if (removed) {
            return;
        }

        ServerPlayer caster = resolveCaster(level);

        if (caster == null) {
            return;
        }

        Vec3 origin = resolveOrigin(caster);
        forEachEffectSet(effectSet -> applySet(level, caster, origin, effectSet));
    }

    /**
     * 只对<b>新加入的那一组</b>立即执行一次首轮（F14）。
     * <p>
     * <b>为什么需要它</b>：技能 {@code actions[]} 里的后续 action 通过 {@link #join} 并入同一个领域，
     * 而它们原本要等到下一个 {@code apply_interval} 才第一次生效。当
     * {@code duration < apply_interval} 时，{@link DomainData#tick} 会先发现领域已到期、
     * 直接移出并跳过结算 —— 于是<b>后续组从未执行过</b>，效果静默丢失。
     * 对 {@code simple} 这类"打一下就完"的技能尤其明显：第一组落下了，第二组没有。
     * <p>
     * 刻意只跑新增组、而不再次 {@link #applyNow} 整个领域：否则第一组会在同一次施法里被执行两次，
     * 概率判定、伤害、扣蓝之类都会翻倍。
     *
     * @param effects 要立即执行的组（必须已通过 {@link #join} 加入）
     */
    public void applyGroupNow(final ServerLevel level,
                              final @NotNull Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> effects) {
        if (removed) {
            return;
        }

        // 成员资格先于施法者解析：这是纯粹的本地状态判定，也更便宜
        if (!effectSets.contains(effects)) {
            return;
        }

        ServerPlayer caster = resolveCaster(level);

        if (caster == null) {
            return;
        }

        applySet(level, caster, resolveOrigin(caster), effects);
    }

    /**
     * 领域消散前的收尾：对<b>此刻仍落在领域内且通过阵营/条件筛选</b>的实体逐个执行
     * {@code AbilityEntityEffect#remove(..., isAutoRemoval = false)}。
     * <p>
     * 只在 {@code remove_effects_on_end: true} 时执行。为什么要它：DS 自己的自动移除判据是
     * 「目标与<b>施法者</b>的距离 > {@code getDistance()}」（见 {@code DurationInstance#tick}），
     * 而本组件的锚点通常固定在施法位置——两者口径不同，靠 DS 自动清理会漏掉"施法者已走远、
     * 但目标仍站在领域里"的情形。因此提供一个显式的收尾路径。
     * <p>
     * 注意：方块效果（{@code AbilityBlockEffect}）接口<b>没有</b> remove 方法，故不参与收尾
     * ——方块类效果（点燃、转化、粒子）本身也不需要"撤销"。
     * <p>
     * 施法者离线时跳过收尾（拿不到 {@code ServerPlayer}），残留效果由自身的 duration 过期。
     */
    public void end(final ServerLevel level) {
        if (!removeEffectsOnEnd || ended) {
            return;
        }

        // 幂等标记：收尾会执行任意技能效果（可能经 run_function 反过来触发 clear / 再次移除），
        // 没有这道闸门时同一次收尾可能被重入执行多遍
        ended = true;

        ServerPlayer caster = resolveCaster(level);

        if (caster == null) {
            return;
        }

        Vec3 origin = resolveOrigin(caster);
        AABB area = DomainShape.createArea(shape, origin, radius, height, heightStartsBelow);

        // 快照遍历：收尾回调同样可能触发新施法并向 effectSets 追加（见 forEachEffectSet）
        forEachEffectSet(effectSet -> effectSet.ifRight(entityTarget ->
                level.getEntities(EntityTypeTest.forClass(Entity.class), area,
                        entity -> isInside(origin, entity)
                                && entityTarget.targetingMode().isEntityRelevant(caster, entity, entityTarget.isHarmful())
                                && entityTarget.matches(caster, entity, entity.position())
                ).forEach(entity -> entityTarget.effects().forEach(effect -> effect.remove(caster, ability, entity, false)))));
    }

    /**
     * 标记"本领域已被移出 {@code DomainData}"。由 {@link DomainData#remove} 在调用
     * {@link #end} <b>之前</b>置位，使收尾回调里发生的重入也能立刻看到本领域已撤。
     */
    public void markRemoved() {
        this.removed = true;
    }

    /** 本领域是否已被移出（供调试 / 断言使用）。 */
    public boolean isRemoved() {
        return removed;
    }

    /**
     * 结算单个效果集：方块分支逐格、实体分支逐实体。
     * <p>
     * <b>逐目标复查 {@link #removed}</b>：效果回调可以执行任意技能效果（含 {@code run_function}），
     * 完全可能在第 3 个方块处把本领域整个清掉。若只在组粒度上检查，剩下的上百个格子仍会照常吃一遍效果 ——
     * 那正是"回调重入"最直观的表现（领域已经消失了，效果却还在往外泼）。
     * <p>
     * 这里的写法是"跳过后续目标"而非"中断遍历"（{@code forEach} 无法 break）：代价是剩余目标仍会被
     * 逐个访问一次，但真正的昂贵部分（{@code getBlockState} / {@code matches} / 效果回调）全部跳过，
     * 而"已移除领域不再产生后续结算"这条保证是完整的。
     */
    private void applySet(final ServerLevel level,
                          final ServerPlayer caster,
                          final Vec3 origin,
                          final @NotNull Either<AbilityTargeting.BlockTargeting, AbilityTargeting.EntityTargeting> effectSet) {
        AABB area = DomainShape.createArea(shape, origin, radius, height, heightStartsBelow);

        effectSet.ifLeft(blockTarget -> BlockPos.betweenClosedStream(area).forEach(position -> {
            // 本次结算中途被移除（见方法注释）：不再对后续目标施加效果
            if (removed) {
                return;
            }

            // 必须先挡未加载区块：ServerLevel#getBlockState 会同步加载 / 生成区块，
            // 而 Condition#blockContext 内部就读 getBlockState（DS 内置 area 没做这层保护）。
            // 判据与 annulus / anti_dragon_breath / 收束光束三处完全一致（见 BreathSelection 的说明）。
            if (!level.isLoaded(position)) {
                return;
            }

            // 方块取格中心，与 DS / 本模组既有目标类型取点口径一致
            if (!DomainShape.contains(shape, origin, radius,
                    position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5)) {
                return;
            }

            if (blockTarget.matches(caster, position)) {
                // direction 传 null：领域锚点没有"命中面"概念，与 DS area / disc / annulus 一致
                blockTarget.effects().forEach(effect -> effect.apply(caster, ability, position, null));
            }
        })).ifRight(entityTarget -> level.getEntities(EntityTypeTest.forClass(Entity.class), area,
                entity -> isInside(origin, entity)
                        && entityTarget.targetingMode().isEntityRelevant(caster, entity, entityTarget.isHarmful())
                        && entityTarget.matches(caster, entity, entity.position())
        ).forEach(entity -> {
            if (removed) {
                return;
            }

            entityTarget.effects().forEach(effect -> effect.apply(caster, ability, entity));
        }));
    }

    /** 实体取点口径：{@code entity.position()}（脚部中心），与 DS / 本模组既有目标类型一致。 */
    private boolean isInside(final Vec3 origin, final @NotNull Entity entity) {
        return DomainShape.contains(shape, origin, radius, entity.getX(), entity.getY(), entity.getZ());
    }

    private Vec3 resolveOrigin(final ServerPlayer caster) {
        return anchor.followsCaster() ? caster.position() : anchorPosition;
    }
}
