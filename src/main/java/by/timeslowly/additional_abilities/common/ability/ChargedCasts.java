package by.timeslowly.additional_abilities.common.ability;

import by.dragonsurvivalteam.dragonsurvival.common.handlers.magic.ManaHandler;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargedActivation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 蓄力档位激活类型的服务端落地：<b>按档位释放</b>与查询用的档位记录。
 *
 * <h2>为什么需要它</h2>
 * {@code Activation} 接口只有取值器，<b>没有任何 tick / 松手回调</b>；而
 * {@code DragonAbilityInstance#tickActions} 的流程是硬编码的：读条完成（{@code currentTick == castTime}）
 * 就自动执行一次默认动作并立刻停止。也就是说 DS 原生只支持"蓄满释放"，
 * "提前松手按当前档位释放"必须由本模组补上：
 * <ol>
 *     <li>客户端松手时发出
 *         {@link by.timeslowly.additional_abilities.common.network.ChargedReleasePayload}；</li>
 *     <li>服务端在这里校验、换算档位，并执行一次默认动作。</li>
 * </ol>
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
 * <h2>已知边界</h2>
 * "延迟型效果"（例如生成实体后由该实体在后续游戏刻才去读 {@code ability.level()}）会读到
 * 玩家自身的升级等级而非档位。对本窗口内立即结算的效果（伤害、弹射物生成、方块效果、概率判定）
 * 无影响。
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID)
public final class ChargedCasts {
    /**
     * 最近一次<b>实际释放</b>所用的档位，按"玩家 + 技能"记录，仅供查询指令展示，
     * 不参与任何数值结算。
     */
    private static final Map<PlayerAbility, Integer> LAST_CHARGED_LEVELS = new HashMap<>();

    private ChargedCasts() {
        // 工具类
    }

    /**
     * 以指定蓄力时长对应的档位释放一次技能。
     * <p>
     * 执行顺序刻意与 {@code DragonAbilityInstance#tickActions} 中"读条完成瞬间"的顺序保持一致：
     * 先扣初始魔力，再执行默认动作，最后走 DS 原生收尾（冷却 / 结束音效 / 结束动画）。
     *
     * @param player      施法者（服务端玩家）
     * @param instance    正在施法的技能实例
     * @param chargeTicks 客户端上报的已蓄力游戏刻数
     * @return 是否真的按档位释放了；{@code false} 表示应视为取消施法
     */
    public static boolean fire(final @NotNull ServerPlayer player, final @NotNull DragonAbilityInstance instance, final int chargeTicks) {
        if (!(instance.value().activation() instanceof ChargedActivation charged)) {
            return false;
        }

        MagicData magic = MagicData.getData(player);

        // 必须是"此刻正在施法的那个技能实例"：
        // 主动技能的 isCasting / getCurrentlyCasting 只在 beginCasting 时建立、stopCasting 时清除，
        // 所以已经蓄满并被 DS 原生释放过的技能会在这里被拦下，避免重复结算
        if (magic.getCurrentlyCasting() != instance) {
            return false;
        }

        int playerLevel = instance.level();

        // 等级 0 表示技能未解锁（DS 也会在 checkCast 阶段拦下），
        // 且部分 LevelBasedValue（如 lookup）不接受 0，故直接短路
        if (playerLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            return false;
        }

        // 客户端权威 + 服务端复算：只信任"时长"，档位由服务端重新换算，
        // 并把时长钳制在 [0, cast_time] 之内
        int clampedTicks = Mth.clamp(chargeTicks, 0, Math.max(0, charged.getCastTime(playerLevel)));
        int chargedLevel = charged.getChargedLevel(clampedTicks, playerLevel);

        // 未达最低蓄力时长：交回调用方走 DS 原生取消路径（无冷却、无效果）
        if (chargedLevel < DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            return false;
        }

        // 临时代替等级：窗口内所有效果都会读到这个档位
        instance.setLevel(chargedLevel);

        try {
            ManaHandler.consumeMana(player, charged.getInitialManaCost(chargedLevel));

            // currentTick 必须传 getCastTime(档位)：
            // ActionContainer 内部按 actualTick = currentTick - getCastTime(level) 对 trigger_rate 取模，
            // 传该值才能得到 actualTick == 0，从而保证 trigger_rate > 1 的动作不被吞掉
            instance.value().tickDefaultActions(player, instance, charged.getCastTime(chargedLevel));

            // 收尾统一走 DS 原生路径：冷却按档位结算（release 内部读 getCooldown(level)），
            // 同时补齐结束音效、结束动画与 currentTick 归零
            magic.stopCasting(player, instance, true);
        } finally {
            // 该字段会被持久化，必须还原成玩家真实的升级等级
            instance.setLevel(playerLevel);
        }

        LAST_CHARGED_LEVELS.put(new PlayerAbility(player.getUUID(), instance.key()), chargedLevel);
        return true;
    }

    /**
     * 查询指令用的档位解析：
     * 正在蓄力 → 返回当前蓄力所对应的档位；否则 → 返回最近一次实际释放所用的档位。
     * <p>
     * 技能的激活类型不是蓄力档位时一律返回 {@link ChargedActivation#NO_CHARGED_LEVEL}，
     * 避免把别的技能的档位错报过来。
     */
    public static int resolveQueryLevel(final @NotNull Player player, final @NotNull DragonAbilityInstance instance) {
        if (!(instance.value().activation() instanceof ChargedActivation charged)) {
            return ChargedActivation.NO_CHARGED_LEVEL;
        }

        if (instance.getCurrentTick() > 0 && instance.level() >= DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS) {
            int live = charged.getChargedLevel(instance.getCurrentTick(), instance.level());

            if (live > ChargedActivation.NO_CHARGED_LEVEL) {
                return live;
            }
        }

        return LAST_CHARGED_LEVELS.getOrDefault(new PlayerAbility(player.getUUID(), instance.key()), ChargedActivation.NO_CHARGED_LEVEL);
    }

    @SubscribeEvent
    public static void onLoggedOut(final @NotNull PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        LAST_CHARGED_LEVELS.keySet().removeIf(key -> key.player().equals(uuid));
    }

    /** "玩家 + 技能"复合键：同一个玩家的多个蓄力技能各记各的档位。 */
    private record PlayerAbility(UUID player, ResourceKey<DragonAbility> ability) { }
}
