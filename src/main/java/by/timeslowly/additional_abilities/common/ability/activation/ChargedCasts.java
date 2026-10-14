package by.timeslowly.additional_abilities.common.ability.activation;

import by.dragonsurvivalteam.dragonsurvival.common.handlers.magic.ManaHandler;
import by.dragonsurvivalteam.dragonsurvival.network.animation.StopAbilityAnimation;
import by.dragonsurvivalteam.dragonsurvival.network.sound.StopTickingSound;
import by.dragonsurvivalteam.dragonsurvival.network.syncing.SyncCooldown;
import by.dragonsurvivalteam.dragonsurvival.network.syncing.SyncMana;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.api.ChargedCastResult;
import by.timeslowly.additional_abilities.common.network.charged.ChargedReleasePayload;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 蓄力档位族系激活类型的服务端统一结算服务：
 * <b>按档位释放 / 超限到点自动释放 / 取消</b>，以及查询用的档位记录。
 *
 * <h2>为什么需要它</h2>
 * {@code Activation} 接口只有取值器，<b>没有任何 tick / 松手回调</b>；而
 * {@code DragonAbilityInstance#tickActions} 的流程是硬编码的：读条完成（{@code currentTick == castTime}）
 * 就自动执行一次默认动作并立刻停止。也就是说 DS 原生只支持"蓄满释放"，
 * "提前松手按当前档位释放"必须由本模组补上：
 * <ol>
 *     <li>客户端松手时发出
 *         {@link ChargedReleasePayload}；</li>
 *     <li>服务端在 {@link #fire} 里校验、换算档位，并执行一次默认动作（或按请求取消）。</li>
 * </ol>
 *
 * <h2>三类终结为什么必须汇到同一处</h2>
 * 一次蓄力有三条终结路径：手动松手（{@link #fire}）、超限窗口到点的自动释放
 * （{@link #settleOvercharged}，由 {@code mixins.DragonAbilityInstanceMixin} 在完成点接管）、
 * 以及取消。三条路径若各自复制一遍"播起始音效 → 扣初始魔力 → 执行动作 → 收尾"，
 * 就会在扣蓝、动作时钟、冷却、查询记录和观察者声音上产生差异
 * （例如超限自动释放曾把越限后的 {@code currentTick} 直接交给动作，
 * 使 {@code trigger_rate > 1} 的动作被静默吞掉，却照样扣蓝、进冷却并触发连携）。
 * 因此这里只保留一份 {@link #settle}，三条路径只负责算出各自的"已达成档位"。
 *
 * <h2>等级如何传递到"技能内的伤害 / 弹射物 / 方块效果"</h2>
 * 已核实：DS 里<b>所有</b>实体效果、方块效果、弹射物、伤害与概率，取等级的唯一来源都是
 * {@link DragonAbilityInstance#level()}（源码中一律写作 {@code ability.level()}）。
 * 因此"按档位释放"的落地手法就是：<b>在这段同步执行窗口内临时
 * {@code setLevel(档位)}，执行完立刻还原</b>。
 * <ul>
 *     <li>该字段会被持久化，且客户端与服务端各持一份副本 —— 所以必须还原，
 *         否则会把"临时档位"写进玩家存档，造成升级数据被篡改；</li>
 *     <li>整个窗口是同 tick 内的同步代码，中途不发送任何同步包，
 *         因此不会出现"客户端看到档位被改"的中间态。</li>
 * </ul>
 *
 * <h2>取消路径为什么不能交给 DS</h2>
 * {@code DragonAbilityInstance#isApplyingEffects()} 的判定是
 * {@code isActive && canBeCast() && currentTick >= getCastTime(level)}。
 * 当 {@code can_charge_exceed_cast_time} 为 {@code true} 时，玩家"按过 {@code cast_time} 仍在蓄力"
 * 这一段会让它<b>提前返回 true</b>；而 {@code MagicData#stopCasting(player, instance)} 正是用它
 * 决定是否施加冷却。若把"蓄满后改主意取消"交给 DS 原生路径，
 * 就会按"效果已生效"结算 —— <b>凭空进冷却，还播一遍结束音效与结束动画</b>。
 * <p>
 * 因此取消分支必须自己收尾：显式 {@code stopCasting(…, false)}（不进冷却、不扣魔力、不播结束音效），
 * 并由本类自行广播 {@code StopTickingSound} + {@code StopAbilityAnimation}。
 * 后者不能省略：DS 的 {@code SyncStopCast#handleServer} 在
 * {@code isApplyingEffects() && hasEndAnimation()} 时<b>不会</b>下发停止动画包
 * （它假设"效果阶段"会由结束动画自然收场），照搬会让其他玩家一直停留在蓄力循环动画上。
 *
 * <h2>服务端权威：为什么不信客户端上报的蓄力时长</h2>
 * 只把客户端上报的时长当作"上限声明"，真正的天花板是服务端自己的
 * {@link DragonAbilityInstance#getCurrentTick()}（由 DS 的玩家 tick 循环递增，客户端改不到）：
 * <pre>
 * effectiveTicks = min(max(payloadTicks, 0), max(serverCurrentTick, 0), max(castTime, 0))
 * </pre>
 * 仅"按 {@code cast_time} 钳制"是不够的：合法开始施法后立刻上报 {@code chargeTicks = cast_time}
 * 就能直接跳到自身最高档。加进服务端实际耗时后，这种请求只能拿到服务端真正蓄到的那一档。
 * <p>
 * 代价是零网络容差（见用户已确认的取舍）：客户端与服务端的刻计数相差 1 刻时，
 * 边界处服务端可能算出<b>低一档甚至不足最低档</b>。低一档按真实档位结算；
 * 不足最低档则必须<b>照常收尾</b>（见下），不能因为"客户端说它够了"就放着不管。
 *
 * <h2>客户端预测的纠正</h2>
 * 客户端在松手那一刻就本地补了"起始音效 + 扣初始魔力 + 按档位冷却"，
 * 而它依据的是自己那份刻计数。服务端复算结果与之不同时必须回传权威值，否则会出现
 * "服务端扣了 3 档的蓝、客户端显示扣了 5 档的蓝"这种永久性偏差。
 * 本类用客户端上报的原始时长反推出它本地预测的档位（{@link #clientPredictedLevel}），
 * 只有两者不一致时才补发 {@code SyncMana}（全量）与 {@code SyncCooldown}。
 * <p>
 * 故意只纠正"法力 + 冷却"这两个会被玩家直接看到的数值：客户端可能已经多播了一次结束音效 /
 * 结束动画，那属于观感残差，用它换来"不加新包、不动协议版本"。
 *
 * <h2>查询记录的生命周期</h2>
 * 本类维护两份仅供查询指令使用的记录，两者的生命周期口径刻意不同：
 * <ul>
 *     <li>{@link #LAST_CHARGED_LEVELS}（最近一次实际释放的档位）——按"玩家 + 技能"记录，
 *         因为"上次放了几档"本就是每个技能各自的属性；取消不写入（取消不是释放）；</li>
 *     <li>{@link #SELECTED_LEVELS}（本次施法的选档）——<b>每名玩家至多一条</b>，
 *         因为同一时刻只可能在施放一个技能（理由见该字段的注释）。</li>
 * </ul>
 * 选档的<b>写入</b>受 {@link #onSelectionChanged} 校验（必须正在施放该技能 + 允许选档 + 档位钳制），
 * <b>清理</b>则统一挂在 {@link #onCastingStopped}（由 {@code MagicDataMixin} 在
 * {@code MagicData#stopCasting} 尾部调用）—— 那里是所有终结路径的共同漏斗，
 * 覆盖"手动松手 / 超限自动释放 / 取消 / DS 原生读条完成"全部四条，包括 T01 统一结算服务
 * 覆盖不到的第四条。两条记录都在玩家下线时清空。
 *
 * <h2>已知边界</h2>
 * "延迟型效果"（例如生成实体后由该实体在后续游戏刻才去读 {@code ability.level()}）会读到
 * 玩家自身的升级等级而非档位。对本窗口内立即结算的效果（伤害、弹射物生成、方块效果、概率判定）
 * 无影响。
 *
 * <h2>正式入口</h2>
 * 第三方应当经 {@link by.timeslowly.additional_abilities.api.ChargedCasting} 调用本类，
 * 而不是直接依赖这里的签名 —— 本类的取值器与结算方法随时可能随内部结构调整，
 * {@link ChargedCastResult} 才是被承诺稳定的结果契约。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public final class ChargedCasts {
    /**
     * 最近一次<b>实际释放</b>所用的档位，按"玩家 + 技能"记录，仅供查询指令展示，
     * 不参与任何数值结算。取消施法不会写入这里（取消不是"释放"）。
     * <p>
     * 按技能分别记录是有意义的：同一名玩家的多个蓄力技能各有各的"上次放了几档"。
     * 表大小因此天然被"该玩家拥有的技能数"限住，并在下线时清空。
     */
    private static final Map<PlayerAbility, Integer> LAST_CHARGED_LEVELS = new HashMap<>();

    /**
     * 玩家<b>当前这次施法</b>用滚轮指定的释放档位，<b>每名玩家至多一条</b>。
     * <p>
     * 只由客户端在滚动时主动同步（频率极低），服务于
     * {@code /dragon-ability query … current_selected_level}；不参与任何数值结算 ——
     * 真正生效的释放档位随 {@link ChargedReleasePayload} 单独上报，并在 {@link #fire} 中重新校验。
     * <p>
     * <b>为什么是"每玩家一条"而不是"每玩家 + 每技能一条"</b>：同一个玩家同一时刻只可能在施放一个技能
     * （{@code MagicData} 只有一份 {@code currentlyCasting}），所以按技能分开存只会让
     * "曾经施放过的技能各留一条"慢慢堆积，而其中除了当前那个之外<b>永远读不到</b>。
     * 收敛成单槽后，记录数量与施法数量同阶，结构上不可能随在线时长增长。
     * <p>
     * 写入前必须通过 {@link #onSelectionChanged} 的校验（当前正在施法 + 允许选档 + 档位钳制），
     * 因此这个槽位不会被任意技能键占用。
     */
    private static final Map<UUID, Selection> SELECTED_LEVELS = new HashMap<>();

    /**
     * 一次施法内的选档记录。
     *
     * @param ability 归属的技能注册键。查询时与目标实例比对，避免把别的技能的选档错报过来
     * @param level   选定档位；{@link ChargedReleasePayload#AUTO} 表示"自动跟随已达档位"
     */
    private record Selection(ResourceKey<DragonAbility> ability, int level) { }

    private ChargedCasts() {
        // 工具类
    }

    /**
     * 手动松手结算：以指定档位释放一次技能，或按请求取消施法。
     *
     * @param player       施法者（服务端玩家）
     * @param instance     正在施法的技能实例
     * @param payloadTicks 客户端上报的已蓄力游戏刻数；<b>只作为上限声明</b>，
     *                     实际采用值还要与服务端自己的刻计数和 {@code cast_time} 取最小
     * @param requestedLevel 客户端请求的释放档位：{@link ChargedReleasePayload#AUTO}（采用已达档位）、
     *                     {@link ChargedReleasePayload#CANCEL}（取消）、或 {@code >= 1} 的指定档位
     * @return 本次终结的结算结果；{@link ChargedCastResult#handled()} 为 {@code false}
     *         （即 {@code REJECTED}）表示这次请求根本不该由本模组结算
     *         （不是蓄力类型 / 不是此刻正在施法的实例 / 技能未解锁），调用方应什么都不做
     */
    public static @NotNull ChargedCastResult fire(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance,
                                                  final int payloadTicks, final int requestedLevel) {
        if (!(instance.value().activation() instanceof ChargeableActivation chargeable)) {
            return ChargedCastResult.rejected(ChargedCastResult.Reason.NOT_CHARGEABLE);
        }

        MagicData magic = MagicData.getData(player);

        // 必须是"此刻正在施法的那个技能实例"：
        // 主动技能的 isCasting / getCurrentlyCasting 只在 beginCasting 时建立、stopCasting 时清除，
        // 所以已经蓄满并被 DS 原生释放过的技能会在这里被拦下，避免重复结算
        if (magic.getCurrentlyCasting() != instance) {
            return ChargedCastResult.rejected(ChargedCastResult.Reason.NOT_CURRENTLY_CASTING);
        }

        // 等级 0 表示技能未解锁（DS 也会在 checkCast 阶段拦下），
        // 且部分 LevelBasedValue（如 lookup）不接受 0，故直接短路
        int playerLevel = instance.level();

        if (playerLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            return ChargedCastResult.rejected(ChargedCastResult.Reason.ABILITY_NOT_UNLOCKED);
        }

        int achievedLevel = chargeable.getChargedLevel(
                authoritativeChargeTicks(instance, chargeable, playerLevel, payloadTicks), playerLevel);

        return settle(player, instance, chargeable, magic, achievedLevel, requestedLevel,
                clientPredictedLevel(chargeable, payloadTicks, playerLevel, requestedLevel));
    }

    /**
     * 超限窗口到点的<b>自动</b>释放结算（由 {@code mixins.DragonAbilityInstanceMixin} 在
     * DS 的完成判定那一帧接管，避免随后再进入 DS 原生的扣蓝 / 动作分支）。
     * <p>
     * 与手动释放共用 {@link #settle}，因此两者的动作时钟、扣蓝、冷却、连携触发与观察者收尾完全一致。
     * 全程不读取任何客户端上报值 —— 到点这一事实完全由服务端自己的刻计数得出。
     * <p>
     * 保留既有公开语义：到点以<b>玩家自身最高档</b>释放，忽略滚轮选定的低档或"选定为取消"。
     *
     * @param player   施法者（服务端玩家）
     * @param instance 已按满超限窗口的技能实例
     * @return 本次终结的结算结果；{@link ChargedCastResult#handled()} 为 {@code false}
     *         表示调用方应放行 DS 原生流程
     */
    public static @NotNull ChargedCastResult settleOvercharged(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance) {
        if (!(instance.value().activation() instanceof ChargeableActivation chargeable)) {
            return ChargedCastResult.rejected(ChargedCastResult.Reason.NOT_CHARGEABLE);
        }

        if (!chargeable.canChargeExceedCastTime()) {
            return ChargedCastResult.rejected(ChargedCastResult.Reason.OVERCHARGE_NOT_ENABLED);
        }

        MagicData magic = MagicData.getData(player);

        if (magic.getCurrentlyCasting() != instance) {
            return ChargedCastResult.rejected(ChargedCastResult.Reason.NOT_CURRENTLY_CASTING);
        }

        // 到点即满档：每档所需时长都被 chargeCapTicks(<= cast_time) 封顶，
        // 而超限窗口的释放点严格晚于该上限（见 ChargeableActivation#getHoldLimitTicks），
        // 因此此处"已达成档位"必为玩家自身最高档
        int playerLevel = instance.level();

        if (playerLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            return ChargedCastResult.rejected(ChargedCastResult.Reason.ABILITY_NOT_UNLOCKED);
        }

        // 本地预测与最终结果同为最高档，故不会产生纠正包
        return settle(player, instance, chargeable, magic, playerLevel, ChargedReleasePayload.AUTO, playerLevel);
    }

    /**
     * 三条终结路径唯一的结算入口。
     *
     * @param achievedLevel  服务端权威的"已达成档位"（{@link ChargeableActivation#NO_CHARGED_LEVEL} 表示不足）
     * @param requestedLevel 请求档位；{@link ChargedReleasePayload#AUTO} 表示采用 {@code achievedLevel}
     * @param predictedLevel 客户端本地已经按哪一档做了预测；{@code 0} 表示它预测为"取消、未释放"
     * @return 本次终结的结算结果（必为 {@code RELEASED} 或 {@code CANCELLED} —— 走到这里就已经接管了）
     */
    private static @NotNull ChargedCastResult settle(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance,
                                                     final @NotNull ChargeableActivation chargeable, final @NotNull MagicData magic,
                                                     final int achievedLevel, final int requestedLevel, final int predictedLevel) {
        ChargedCastResult result;

        if (achievedLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            // 服务端按真实时间复算后不足最低档。客户端可能已经按"释放"做了本地预测，
            // 但服务端必须照常收尾 —— 客户端那边早已停手，放着不管会留下永远按住的施法状态
            cancel(player, instance);
            result = ChargedCastResult.cancelled(ChargedCastResult.Reason.INSUFFICIENT_CHARGE);
        } else {
            // AUTO 表示"用已达成档位"；其余（含 0 = 取消、>=1 = 指定）直接采用。
            // 越档请求（改包，或客户端与服务端时序错位）一律钳制到已达成档位，而不是拒绝：
            // 拒绝会让服务端继续停留在"施法中"（客户端那边早已停手），
            // 技能会卡在蓄力状态直到玩家再次操作，是比越档更糟的硬失步。
            // 钳制只会把档位降下来，因此不构成可利用的越权放大
            int finalLevel = Math.min(
                    requestedLevel == ChargedReleasePayload.AUTO ? achievedLevel : requestedLevel, achievedLevel);

            if (finalLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
                cancel(player, instance);
                result = ChargedCastResult.cancelled(ChargedCastResult.Reason.CANCELLED_BY_REQUEST);
            } else {
                release(player, instance, chargeable, magic, finalLevel);
                result = ChargedCastResult.released(finalLevel);
            }
        }

        if (predictedLevel != result.releasedLevel()) {
            syncSettlement(player, instance, magic);
        }

        // 这里刻意<b>不</b>记 LAST_CHARGED_LEVELS / 不清 SELECTED_LEVELS：
        // 两条记录统一由 onCastingStopped 在所有终结路径的共同漏斗上处理（见该方法的注释）。
        // 本方法体内必定会调用 release / cancel，两者都经由 MagicData#stopCasting，
        // 因此清理与记账都会在本次调用返回前完成
        return result;
    }

    /**
     * 按档位释放：刻意与 {@code DragonAbilityInstance#tickActions} 中"读条完成瞬间"的执行顺序保持一致
     * （先扣初始魔力，再执行默认动作，最后走 DS 原生收尾），因为这条路径绕过了那段代码，必须自己补齐。
     */
    private static void release(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance,
                                final @NotNull ChargeableActivation chargeable, final @NotNull MagicData magic,
                                final int finalLevel) {
        int playerLevel = instance.level();

        // 临时代替等级：窗口内所有效果都会读到这个档位
        instance.setLevel(finalLevel);

        try {
            // ① 起始音效 —— 服务端这一路 playSound 会把施法者本人排除在外，
            //    本人由客户端本地播放，因此两端都调不会重复出声。
            //    同时向追踪该玩家的客户端广播 StartTickingSound（循环音效）
            chargeable.playStartAndLoopingSound(player, instance);

            // ② 扣初始魔力 —— consumeMana 只改本端 MagicData 的 currentMana，
            //    且 DS 不逐刻同步法力，所以客户端那份由 ChargedCastClientHandler 同步扣一次，
            //    这里负责服务端的权威值
            ManaHandler.consumeMana(player, chargeable.getInitialManaCost(finalLevel));

            // ③ 执行动作。currentTick 必须传 getCastTime(档位)：
            // ActionContainer 内部按 actualTick = currentTick - getCastTime(level) 对 trigger_rate 取模，
            // 传该值才能得到 actualTick == 0，从而保证 trigger_rate > 1 的动作不被吞掉
            instance.value().tickDefaultActions(player, instance, chargeable.getCastTime(finalLevel));

            // 收尾统一走 DS 原生路径：冷却按档位结算（release 内部读 getCooldown(level)），
            // 同时补齐结束音效、结束动画与 currentTick 归零
            magic.stopCasting(player, instance, true);
        } finally {
            // 该字段会被持久化，必须还原成玩家真实的升级等级
            instance.setLevel(playerLevel);
        }

        broadcastCastEnd(player, instance);
    }

    /**
     * 告诉追踪该玩家的其他客户端"这次施法结束了"。
     * <p>
     * 不能省：服务端 {@code MagicData#stopCasting} 只调用 {@code instance.stopSound()}，
     * 而 DS 的服务端 Proxy 把这个方法实现为<b>空操作</b>，真正负责广播的是
     * {@code SyncStopCast#handleServer} —— 本模组不经过那条路径。
     * 循环音效因此会在观察者那边一直响下去。
     * <p>
     * 动画的停止包只在"没有结束动画"时补发：有结束动画时 DS 的
     * {@code stopCasting} 已经下发了 {@code SyncAbilityAnimation}，再补一个停止包会把刚播上的结束动画立刻掐掉。
     */
    private static void broadcastCastEnd(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance) {
        PacketDistributor.sendToPlayersTrackingEntity(player,
                new StopTickingSound(instance.location().withSuffix(player.getStringUUID())));

        if (!instance.hasEndAnimation()) {
            PacketDistributor.sendToPlayersTrackingEntity(player, new StopAbilityAnimation(player.getId()));
        }
    }

    /**
     * 取消施法：不执行动作、不扣初始魔力、<b>不进冷却</b>、不播结束音效与结束动画。
     * <p>
     * 为什么不能走 {@code SyncStopCast#handleServer}：见类注释"取消路径为什么不能交给 DS"。
     *
     * @param player   施法者
     * @param instance 被取消的技能实例
     */
    private static void cancel(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance) {
        // 让追踪该玩家的其他客户端停掉循环音效与蓄力动画。
        // 停止动画包必须无条件发送（不能照搬 DS 里那条 isApplyingEffects 条件判断），
        // 否则其他玩家会一直停留在蓄力循环动画上
        PacketDistributor.sendToPlayersTrackingEntity(player,
                new StopTickingSound(instance.location().withSuffix(player.getStringUUID())));
        PacketDistributor.sendToPlayersTrackingEntity(player,
                new StopAbilityAnimation(player.getId()));

        // withCooldown = false：不进冷却，也不播结束音效 / 结束动画
        MagicData.getData(player).stopCasting(player, instance, false);
    }

    /**
     * 客户端本地预测与服务端结算结果不一致时，回传权威的法力与冷却。
     * <p>
     * 复用 DS 现成的两个包（{@code SyncMana} 全量 / {@code SyncCooldown}），因此不新增 payload、
     * 不需要递增协议版本。法力用全量而非增量：客户端可能已经按错误的档位扣过一次，
     * 增量纠正会把这个误差一直留着。
     */
    private static void syncSettlement(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance,
                                       final @NotNull MagicData magic) {
        PacketDistributor.sendToPlayer(player, new SyncMana(magic.getCurrentMana(), true));
        PacketDistributor.sendToPlayer(player, new SyncCooldown(instance.key(), instance.cooldown()));
    }

    /**
     * 服务端权威的已蓄力时长：客户端上报值只是上限声明。
     * <p>
     * {@code serverCurrentTick} 来自 DS 玩家 tick 循环维护的计数器，客户端无法影响，
     * 因此"合法起手后立刻谎报满时长"最多只能拿到服务端真正蓄到的那一档。
     *
     * @return {@code min(max(payloadTicks, 0), max(serverCurrentTick, 0), max(castTime, 0))}
     */
    private static int authoritativeChargeTicks(final @NotNull DragonAbilityInstance instance,
                                                final @NotNull ChargeableActivation chargeable,
                                                final int playerLevel, final int payloadTicks) {
        int serverTicks = Math.max(instance.getCurrentTick(), 0);
        int castTime = Math.max(chargeable.getCastTime(playerLevel), 0);

        return Math.min(Math.clamp(payloadTicks, 0, serverTicks), castTime);
    }

    /**
     * 反推客户端本地预测所用的档位，用来判断要不要回传权威值。
     * <p>
     * 客户端 {@code ChargedCastClientHandler} 是用它自己那份刻计数换算档位的，
     * 而那个数就是随包一起上报的 {@code payloadTicks}，因此这里能精确复现它的判断：
     * <ul>
     *     <li>{@link ChargedReleasePayload#AUTO} —— 普通 {@code charged}：按上报时长换算"已达成档位"预测；</li>
     *     <li>其余（含 {@code 0} = 取消、{@code >= 1} = 指定）—— 客户端解析后的业务档位本身就是它的预测值。</li>
     * </ul>
     *
     * @return 客户端预测的释放档位；{@code 0} 表示它预测为"取消、未释放"
     */
    private static int clientPredictedLevel(final @NotNull ChargeableActivation chargeable, final int payloadTicks,
                                            final int playerLevel, final int requestedLevel) {
        if (requestedLevel != ChargedReleasePayload.AUTO) {
            return Math.max(requestedLevel, ChargedReleasePayload.CANCEL);
        }

        return chargeable.getChargedLevel(Math.max(payloadTicks, 0), playerLevel);
    }

    /**
     * 记录玩家当前的选档（客户端滚动后同步过来）。<b>这是唯一的写入入口，且会先校验。</b>
     *
     * <h2>为什么要校验</h2>
     * 本方法由网络包直接驱动，因此调用方是<b>不可信</b>的。若照单全收，在线期间只要持续发包，
     * 就能用任意技能键（包括根本不存在的技能、非蓄力类型的技能）往表里塞条目 ——
     * 记录数随发包次数无上限增长，构成一个稳定的服务端内存增长面。
     * <p>
     * 校验分三层，与 {@link #fire} 的口径保持一致：
     * <ol>
     *     <li><b>必须正在施法，且就是这个技能</b> —— 只有此刻真正在蓄力的那个实例才谈得上"选档"。
     *         这一条同时挡掉了"随便挑个技能键乱写"：没在施法就一律不记；</li>
     *     <li><b>该激活类型必须允许选档</b>（{@link ChargeableActivation#selectsReleaseLevel()}）——
     *         {@code additional_abilities:charged} 不接受选档，给它写记录没有意义；</li>
     *     <li><b>档位钳制到 {@code [0, 已达档位]}</b> —— 与客户端
     *         {@code OptionalChargedSelection} 的取值域一致。{@link ChargedReleasePayload#AUTO}
     *         作为哨兵原样放行（它表示"自动跟随"，不是档位）。</li>
     * </ol>
     * 校验不过就<b>原样返回、不写任何东西</b>（不新增、也不清除）——
     * 清掉反而是给攻击者一个"用脏包把别人选档清空"的能力。
     *
     * @param player        玩家
     * @param ability       技能注册键
     * @param selectedLevel 选定档位；{@link ChargedReleasePayload#AUTO} 表示"自动跟随已达档位"
     */
    public static void onSelectionChanged(final @NotNull Player player, final @NotNull ResourceKey<DragonAbility> ability,
                                          final int selectedLevel) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        MagicData magic = MagicData.getData(serverPlayer);

        // getCurrentlyCasting() 内部已经判过 isCasting，未施法时返回 null，因此不需要再判一次
        DragonAbilityInstance casting = magic.getCurrentlyCasting();

        // 只有"此刻正在施放的那个技能"能登记选档：其余技能键一律忽略，表因此不会增长
        if (casting == null || !casting.key().equals(ability)) {
            return;
        }

        if (!(casting.value().activation() instanceof ChargeableActivation chargeable)
                || !chargeable.selectsReleaseLevel()) {
            return;
        }

        int clamped = selectedLevel;

        if (selectedLevel != ChargedReleasePayload.AUTO) {
            int achieved = chargeable.getChargedLevel(casting.getCurrentTick(), casting.level());
            clamped = Mth.clamp(selectedLevel, ChargeableActivation.NO_CHARGED_LEVEL, achieved);
        }

        SELECTED_LEVELS.put(serverPlayer.getUUID(), new Selection(ability, clamped));
    }

    /**
     * 施法终结时统一清理选档记录。<b>由 {@code mixins.MagicDataMixin} 在
     * {@code MagicData#stopCasting(Player, DragonAbilityInstance, boolean)} 的尾部调用。</b>
     *
     * <h2>为什么挂在这里而不是各结算分支里</h2>
     * 一次蓄力有<b>四条</b>终结路径，而 T01 的统一结算服务只覆盖其中三条：
     * <ul>
     *     <li>手动松手提前释放 → {@link #fire} ✅ 覆盖；</li>
     *     <li>超限窗口到点自动释放 → {@link #settleOvercharged} ✅ 覆盖；</li>
     *     <li>松手时档位不足 / 选定为取消 → {@link #settle} 的取消分支 ✅ 覆盖；</li>
     *     <li><b>DS 原生读条完成</b>（{@code can_charge_exceed_cast_time} 为 {@code false} 的
     *         默认配置，按满 {@code cast_time} 自动释放）→ <b>完全不经过本类</b>，
     *         走的是 {@code SyncStopCast#handleServer} → {@code MagicData#stopCasting}。</li>
     * </ul>
     * 这正是 F05 记录的那条漏洞：{@code optional_charged} 也可以显式把
     * {@code can_charge_exceed_cast_time} 配成 {@code false}（手册里明确允许），
     * 此时玩家先滚轮选个低档、再一路按到自动释放，选档记录就会残留到下一次施法，
     * 使下一次施法的查询继承上一次的选择。
     * <p>
     * 与其在第四条路径上再补一次调用（容易再漏），不如把清理挂在<b>所有路径的共同漏斗</b>上：
     * DS 的三个 {@code stopCasting} 重载最终都汇聚到带 {@code withCooldown} 的那个，
     * 因此挂它的尾部即可覆盖全部四条路径，且将来 DS 新增终结路径时也自动涵盖。
     * <p>
     * <b>打断也走这里</b>：{@code attemptCast} 切技能时服务端调用的是 {@code release()} 而非
     * {@code stopCasting}，但客户端在按下另一个技能键时已经先发过 {@code SyncStopCast}
     * （见 DS 的 {@code ClientCastingHandler#handleSlotSelection}），那条路径同样经过这里。
     * 即使某条极端路径真的绕过了清理，{@link #resolveSelectedLevel} 还会用技能键比对兜住 ——
     * 残留记录只在<b>同一个技能</b>再次施法时才会被读到，而那种情况下它本就是同一次施法延续的语义。
     *
     * @param instance 被终结的技能实例；{@code null} 表示"停止当前施法但未能解析出具体实例"
     *                 （例如 {@code SyncStopCast} 携带了一个该玩家并没有的技能键）
     * @param withCooldown 本次终结是否算一次<b>真正的释放</b>（DS 对"效果已生效"的官方判定）
     */
    public static void onCastingStopped(final @NotNull Player player, final @Nullable DragonAbilityInstance instance,
                                        final boolean withCooldown) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        Selection selection = SELECTED_LEVELS.get(serverPlayer.getUUID());

        if (selection != null) {
            // instance == null 时 stopCasting 必定把 isCasting 置为 false（它那条判定的左半边就是这个条件），
            // 也就是本次施法<b>已经确定结束</b>，选档记录必然过期 —— 直接清，不必也无法再问是哪个技能。
            //
            // 这里不能改用 getCurrentlyCasting() 兜底推断：本方法注入在 TAIL，
            // 此时 isCasting 已被置 false，而 getCurrentlyCasting() 的实现是
            // `isCasting ? fromSlot(...) : null`，因此它<b>恒返回 null</b>，兜底会退化成"永不清除"。
            if (instance == null || instance.key().equals(selection.ability())) {
                SELECTED_LEVELS.remove(serverPlayer.getUUID());
            }
        }

        // 最近一次"实际释放"的档位同样在这里记录 —— 理由与清理相同：这是所有终结路径的共同漏斗。
        // 尤其是 DS 原生读条完成那条（can_charge_exceed_cast_time = false 的默认配置）：
        // 它完全不经过本类的结算服务，若不在这里记，查询会一直停留在上一次的值。
        // 此刻 instance.level() 就是本次实际使用的档位（本类的 release 会在窗口内临时换成档位；
        // DS 原生路径用的则是玩家自身的升级等级，即满档），因此无需再换算。
        if (withCooldown && instance != null
                && instance.value().activation() instanceof ChargeableActivation) {
            LAST_CHARGED_LEVELS.put(new PlayerAbility(serverPlayer.getUUID(), instance.key()), instance.level());
        }
    }

    /**
     * 查询指令用的"当前将要释放的档位"解析。
     * <p>
     * 回答的始终是同一个问题 —— <b>"此刻松手会放几档"</b>，因此<b>自动跟随会被解析成
     * "已蓄到的档位"</b>，而不是把"自动 / 手动"这个内部状态原样透出去。
     * 这一点很重要：玩家滚回顶端（或从未滚动）时客户端会把选档恢复为自动跟随，
     * 若直接透出哨兵值，查询就会在一个明明能算出具体档位的时刻报告"未指定"。
     * <ul>
     *     <li>手动选定过（含 {@code 0} = 取消）→ 直接返回选定值，不做任何换算；</li>
     *     <li>自动跟随（含尚未收到同步）→ 返回以当前蓄力时长换算出的<b>已蓄到档位</b>；</li>
     *     <li>未在蓄力，或蓄力尚未达最低档 → 没有可参照的档位，返回
     *         {@link ChargedReleasePayload#AUTO} 表示"未指定"；</li>
     *     <li>技能的激活类型不是蓄力档位族系 → 同样返回 {@link ChargedReleasePayload#AUTO}。</li>
     * </ul>
     * 不存在"手动选到恰好等于已蓄档位"这种需要与自动跟随区分的状态：
     * 客户端一旦滚到顶端就恢复自动跟随，两者在效果上完全等同。
     */
    public static int resolveSelectedLevel(final @NotNull Player player, final @NotNull DragonAbilityInstance instance) {
        // 这里刻意<b>只</b>判"是否蓄力族系"，不额外要求 selectsReleaseLevel：
        // 对不接受选档的 charged 而言，"此刻松手会放几档"同样是已蓄到的档位，查询应当照答。
        // 它的选档记录永远不会存在（onSelectionChanged 会拒绝写入），自然落到下面的自动跟随分支。
        if (!(instance.value().activation() instanceof ChargeableActivation chargeable)) {
            return ChargedReleasePayload.AUTO;
        }

        Selection stored = SELECTED_LEVELS.get(player.getUUID());

        // 手动指定（含 0 = 取消）：直接返回玩家选定的档位。
        // 记录归属别的技能时不采用（清理漏网时的兜底，见 onCastingStopped）
        if (stored != null && stored.ability().equals(instance.key())
                && stored.level() != ChargedReleasePayload.AUTO) {
            return stored.level();
        }

        // 自动跟随：答案为"已蓄到的档位"。未在蓄力时 currentTick 为 0，没有可参照的档位
        if (instance.getCurrentTick() <= 0) {
            return ChargedReleasePayload.AUTO;
        }

        int achieved = chargeable.getChargedLevel(instance.getCurrentTick(), instance.level());

        // 蓄力尚未达最低档时 achieved 为 0，但那是"还没得选"而不是"选了取消"，仍按未指定处理
        return achieved > ChargeableActivation.NO_CHARGED_LEVEL ? achieved : ChargedReleasePayload.AUTO;
    }

    /**
     * 查询指令用的档位解析：
     * <b>正在蓄力</b> → 返回<b>当前</b>蓄力所对应的档位（可能为 0 = 尚未达最低档）；
     * <b>没有正在蓄力</b> → 返回最近一次实际释放所用的档位。
     * <p>
     * 技能的激活类型不是蓄力档位族系时一律返回 {@link ChargeableActivation#NO_CHARGED_LEVEL}，
     * 避免把别的技能的档位错报过来。
     *
     * <h2>为什么"正在蓄力"必须早于回退（复审 P2）</h2>
     * 旧实现把"当前换算值 &gt; 0"当作回退条件，于是<b>施法刚开始、尚未达最低档</b>时
     * （换算值为 0）会掉进回退分支，报出<b>上一次</b>释放的档位。
     * 玩家上一轮放过 3 档、这一轮刚按下还没蓄到档，查询却答 3，
     * 与指令文档承诺的"正在蓄力时返回当前蓄力所对应的档位"直接矛盾。
     * <p>
     * 判据改为"<b>实例是否处于施法中</b>"（{@code currentTick > 0}）：
     * <ul>
     *     <li>施法中 ⇒ 只报当前换算值，<b>含 0</b>。
     *         0 在这里是有意义的答案（还没蓄到最低档），把它换成旧值就是撒谎；</li>
     *     <li>未施法（{@code currentTick == 0}，见 {@code setActive(false)} / {@code release}
     *         都会把它归零）⇒ 才是"读最近一次释放记录"的场合。</li>
     * </ul>
     * <b>等级 &lt; 1 时仍然回退</b>：{@code LevelBasedValue} 不接受 0 级
     * （{@code Lookup#calculate(0)} 会越界），DS 自己也用
     * {@link DragonAbilityInstance#MIN_LEVEL_FOR_CALCULATIONS} 规避，
     * 因此这种情况下取不到任何"当前档位"，只能沿用最近一次真实释放的记录。
     */
    public static int resolveQueryLevel(final @NotNull Player player, final @NotNull DragonAbilityInstance instance) {
        if (!(instance.value().activation() instanceof ChargeableActivation chargeable)) {
            return ChargeableActivation.NO_CHARGED_LEVEL;
        }

        int currentTick = instance.getCurrentTick();
        int level = instance.level();

        // 三态判据（见 resolveQueryLevelPure）：可求值 / 未施法 / 等级过低无法求值。
        // 这里只负责取"当前换算值"与"最近一次记录"两个事实，决策本身在纯函数里
        boolean evaluating = currentTick > 0 && level >= DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS;

        return resolveQueryLevelPure(evaluating,
                evaluating ? chargeable.getChargedLevel(currentTick, level) : ChargeableActivation.NO_CHARGED_LEVEL,
                LAST_CHARGED_LEVELS.getOrDefault(
                        new PlayerAbility(player.getUUID(), instance.key()), ChargeableActivation.NO_CHARGED_LEVEL));
    }

    /**
     * {@link #resolveQueryLevel} 的<b>决策部分</b>（纯函数，不触世界对象）。
     * <p>
     * 单独抽出来只为<b>可测性</b>：真正的入口需要 {@code Player} 与 {@code DragonAbilityInstance}
     * 两个重型对象，而本机无外网、造不出来。决策只依赖调用方已经取好的事实。
     * <p>
     * <b>刻意不收 {@code currentTick}</b>：决策只看「能否求值」这一个布尔，
     * 与"已经蓄了几刻"无关。把刻数传进来会让人误以为判据与它有关，
     * 也会让"换个刻数答案就该变"这类错误直觉有可乘之机。
     *
     * @param evaluating {@code currentTick > 0} <b>且</b>等级足以求值（{@code LevelBasedValue}
     *                   不接受 0 级），此时 {@code liveLevel} 才是有效答案
     * @param liveLevel  当前蓄力换算出的档位（{@code evaluating} 为 false 时无意义）
     * @param lastLevel  最近一次实际释放记录；无记录时传
     *                   {@link ChargeableActivation#NO_CHARGED_LEVEL}
     * @return 当前档位（{@code evaluating} 时，<b>含 0</b>）；否则为最近一次释放记录
     */
    static int resolveQueryLevelPure(final boolean evaluating, final int liveLevel, final int lastLevel) {
        // 正在施法：只报当前值（含 0 = 尚未达最低档），绝不回退到上一轮的记录。
        // 旧实现把"当前值 > 0"当回退条件，于是刚按下、还没蓄到档时会答出上一轮的档位（复审 P2）
        if (evaluating) {
            return liveLevel;
        }

        // 未在施法（或等级过低无法求值）：最近一次实际释放的档位；没有记录则无档位
        return lastLevel;
    }

    @SubscribeEvent
    public static void onLoggedOut(final @NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        LAST_CHARGED_LEVELS.keySet().removeIf(key -> key.player().equals(uuid));
        SELECTED_LEVELS.remove(uuid);
    }

    /** "玩家 + 技能"复合键：同一个玩家的多个蓄力技能各记各的档位。 */
    private record PlayerAbility(UUID player, ResourceKey<DragonAbility> ability) { }
}
