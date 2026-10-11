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
import by.timeslowly.additional_abilities.common.network.charged.ChargedReleasePayload;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

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
 * <h2>已知边界</h2>
 * "延迟型效果"（例如生成实体后由该实体在后续游戏刻才去读 {@code ability.level()}）会读到
 * 玩家自身的升级等级而非档位。对本窗口内立即结算的效果（伤害、弹射物生成、方块效果、概率判定）
 * 无影响。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public final class ChargedCasts {
    /**
     * 最近一次<b>实际释放</b>所用的档位，按"玩家 + 技能"记录，仅供查询指令展示，
     * 不参与任何数值结算。取消施法不会写入这里（取消不是"释放"）。
     */
    private static final Map<PlayerAbility, Integer> LAST_CHARGED_LEVELS = new HashMap<>();

    /**
     * 玩家当前用滚轮指定的释放档位（{@link ChargedReleasePayload#AUTO} 表示"自动跟随已达档位"），
     * 按"玩家 + 技能"记录。
     * <p>
     * 只由客户端在滚动时主动同步（频率极低），服务于
     * {@code /dragon-ability query … current_selected_level}；不参与任何数值结算 ——
     * 真正生效的释放档位随 {@link ChargedReleasePayload} 单独上报，并在 {@link #fire} 中重新校验。
     * <p>
     * 这里存的是<b>原始值</b>（可能为哨兵 {@code AUTO}）；查询时由
     * {@link #resolveSelectedLevel} 把哨兵解析成"已蓄到的档位"，不要把原始值直接透给调用方。
     */
    private static final Map<PlayerAbility, Integer> SELECTED_LEVELS = new HashMap<>();

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
     * @return 是否已接管本次松手；{@code false} 表示这次请求根本不该由本模组结算
     *         （不是蓄力类型 / 不是此刻正在施法的实例 / 技能未解锁），调用方应什么都不做
     */
    public static boolean fire(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance,
                               final int payloadTicks, final int requestedLevel) {
        if (!(instance.value().activation() instanceof ChargeableActivation chargeable)) {
            return false;
        }

        MagicData magic = MagicData.getData(player);

        // 必须是"此刻正在施法的那个技能实例"：
        // 主动技能的 isCasting / getCurrentlyCasting 只在 beginCasting 时建立、stopCasting 时清除，
        // 所以已经蓄满并被 DS 原生释放过的技能会在这里被拦下，避免重复结算
        if (magic.getCurrentlyCasting() != instance) {
            return false;
        }

        // 等级 0 表示技能未解锁（DS 也会在 checkCast 阶段拦下），
        // 且部分 LevelBasedValue（如 lookup）不接受 0，故直接短路
        int playerLevel = instance.level();

        if (playerLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            return false;
        }

        int achievedLevel = chargeable.getChargedLevel(
                authoritativeChargeTicks(instance, chargeable, playerLevel, payloadTicks), playerLevel);

        settle(player, instance, chargeable, magic, achievedLevel, requestedLevel,
                clientPredictedLevel(chargeable, payloadTicks, playerLevel, requestedLevel));

        return true;
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
     * @return 是否已接管本次自动完成；{@code false} 表示调用方应放行 DS 原生流程
     */
    public static boolean settleOvercharged(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance) {
        if (!(instance.value().activation() instanceof ChargeableActivation chargeable)
                || !chargeable.canChargeExceedCastTime()) {
            return false;
        }

        MagicData magic = MagicData.getData(player);

        if (magic.getCurrentlyCasting() != instance) {
            return false;
        }

        // 到点即满档：每档所需时长都被 chargeCapTicks(<= cast_time) 封顶，
        // 而超限窗口的释放点严格晚于该上限（见 ChargeableActivation#getHoldLimitTicks），
        // 因此此处"已达成档位"必为玩家自身最高档
        int playerLevel = instance.level();

        if (playerLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            return false;
        }

        // 本地预测与最终结果同为最高档，故不会产生纠正包
        settle(player, instance, chargeable, magic, playerLevel, ChargedReleasePayload.AUTO, playerLevel);

        return true;
    }

    /**
     * 三条终结路径唯一的结算入口。
     *
     * @param achievedLevel  服务端权威的"已达成档位"（{@link ChargeableActivation#NO_CHARGED_LEVEL} 表示不足）
     * @param requestedLevel 请求档位；{@link ChargedReleasePayload#AUTO} 表示采用 {@code achievedLevel}
     * @param predictedLevel 客户端本地已经按哪一档做了预测；{@code 0} 表示它预测为"取消、未释放"
     */
    private static void settle(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance,
                               final @NotNull ChargeableActivation chargeable, final @NotNull MagicData magic,
                               final int achievedLevel, final int requestedLevel, final int predictedLevel) {
        // 本轮施法到此结束，无论释放还是取消都清掉选档记录，避免残留到下一次施法
        SELECTED_LEVELS.remove(new PlayerAbility(player.getUUID(), instance.key()));

        int releasedLevel = ChargeableActivation.NO_CHARGED_LEVEL;

        if (achievedLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            // 服务端按真实时间复算后不足最低档。客户端可能已经按"释放"做了本地预测，
            // 但服务端必须照常收尾 —— 客户端那边早已停手，放着不管会留下永远按住的施法状态
            cancel(player, instance);
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
            } else {
                release(player, instance, chargeable, magic, finalLevel);
                releasedLevel = finalLevel;
            }
        }

        if (predictedLevel != releasedLevel) {
            syncSettlement(player, instance, magic);
        }

        if (releasedLevel >= DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            LAST_CHARGED_LEVELS.put(new PlayerAbility(player.getUUID(), instance.key()), releasedLevel);
        }
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
     * 记录玩家当前的选档（客户端滚动后同步过来）。
     *
     * @param player        玩家
     * @param ability       技能注册键
     * @param selectedLevel 选定档位；{@link ChargedReleasePayload#AUTO} 表示"自动跟随已达档位"
     */
    public static void onSelectionChanged(final @NotNull Player player, final @NotNull ResourceKey<DragonAbility> ability,
                                          final int selectedLevel) {
        SELECTED_LEVELS.put(new PlayerAbility(player.getUUID(), ability), selectedLevel);
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
        if (!(instance.value().activation() instanceof ChargeableActivation chargeable)) {
            return ChargedReleasePayload.AUTO;
        }

        Integer stored = SELECTED_LEVELS.get(new PlayerAbility(player.getUUID(), instance.key()));

        // 手动指定（含 0 = 取消）：直接返回玩家选定的档位
        if (stored != null && stored != ChargedReleasePayload.AUTO) {
            return stored;
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
     * 正在蓄力 → 返回当前蓄力所对应的档位；否则 → 返回最近一次实际释放所用的档位。
     * <p>
     * 技能的激活类型不是蓄力档位族系时一律返回 {@link ChargeableActivation#NO_CHARGED_LEVEL}，
     * 避免把别的技能的档位错报过来。
     */
    public static int resolveQueryLevel(final @NotNull Player player, final @NotNull DragonAbilityInstance instance) {
        if (!(instance.value().activation() instanceof ChargeableActivation chargeable)) {
            return ChargeableActivation.NO_CHARGED_LEVEL;
        }

        if (instance.getCurrentTick() > 0 && instance.level() >= DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            int live = chargeable.getChargedLevel(instance.getCurrentTick(), instance.level());

            if (live > ChargeableActivation.NO_CHARGED_LEVEL) {
                return live;
            }
        }

        return LAST_CHARGED_LEVELS.getOrDefault(
                new PlayerAbility(player.getUUID(), instance.key()), ChargeableActivation.NO_CHARGED_LEVEL);
    }

    @SubscribeEvent
    public static void onLoggedOut(final @NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        LAST_CHARGED_LEVELS.keySet().removeIf(key -> key.player().equals(uuid));
        SELECTED_LEVELS.keySet().removeIf(key -> key.player().equals(uuid));
    }

    /** "玩家 + 技能"复合键：同一个玩家的多个蓄力技能各记各的档位。 */
    private record PlayerAbility(UUID player, ResourceKey<DragonAbility> ability) { }
}
