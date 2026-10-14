package by.timeslowly.additional_abilities.common.network.charged;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.activation.ChargedCasts;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 蓄力释放的<b>客户端 → 服务端</b>请求包，服务于所有"蓄力档位"族系激活类型
 * （{@code additional_abilities:charged} 与 {@code additional_abilities:optional_charged}）。
 * <p>
 * 蓄力类激活类型需要"松手即按档位释放"，而 {@code Activation} 接口没有松手回调、
 * DS 的客户端处理器又会在松手时直接取消施法，因此由本模组的客户端处理器抢先拦下并发出此包
 * （详见 {@code client.eventhandler.ChargedCastClientHandler}）。
 *
 * <h2>服务端权威性</h2>
 * 只上报"蓄力时长"与"请求的档位"，<b>不信任任何换算结果</b>：
 * 档位由服务端按同一套
 * {@link by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation}
 * 规则复算，并校验请求档位不超过"已达成档位"，
 * 因此客户端无法通过改包直接指定一个超出自身水平的结果。
 * <p>
 * <b>上报时长只是上限声明</b>：服务端还会与自己的刻计数
 * （{@code DragonAbilityInstance#getCurrentTick()}，由 DS 的玩家 tick 循环维护、客户端改不到）
 * 以及 {@code cast_time} 三者取最小。仅按 {@code cast_time} 钳制是不够的 ——
 * 合法起手后立刻谎报 {@code cast_time} 就能跳到自身最高档。因此本包<b>不保证</b>
 * "上报多少就按多少结算"：边界处可能低一档，甚至不足最低档而改为取消
 * （此时服务端会回传权威的法力与冷却来纠正客户端预测，见
 * {@link ChargedCasts#fire}）。
 *
 * @param ability     技能注册键
 * @param chargeTicks 客户端松手那一刻的已蓄力游戏刻数；服务端只把它当作上限声明
 * @param releaseLevel 请求的释放档位：
 *                     <ul>
 *                         <li>{@link #AUTO}（{@code -1}）—— 采用服务端算出的"已达成档位"，
 *                             即 {@code charged} 的语义；</li>
 *                         <li>{@link #CANCEL}（{@code 0}）—— 取消施法：不执行动作、不扣初始魔力、不进冷却；</li>
 *                         <li>{@code >= 1} —— 指定档位，必须不超过服务端复算出的"已达成档位"
 *                             （{@code optional_charged} 由鼠标滚轮选出的档位）。</li>
 *                     </ul>
 *                     <b>不要把本字段当作解析后的业务档位</b>：它是"请求值"，
 *                     {@link #AUTO} 是哨兵而非档位。
 */
public record ChargedReleasePayload(ResourceKey<DragonAbility> ability, int chargeTicks, int releaseLevel) implements CustomPacketPayload {
    /** 释放档位取值：采用服务端复算出的"已达成档位"（{@code charged} 使用）。 */
    public static final int AUTO = -1;
    /** 释放档位取值：取消施法，不释放。 */
    public static final int CANCEL = 0;

    public static final CustomPacketPayload.Type<ChargedReleasePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "charged_release"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChargedReleasePayload> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(DragonAbility.REGISTRY), ChargedReleasePayload::ability,
            ByteBufCodecs.VAR_INT, ChargedReleasePayload::chargeTicks,
            ByteBufCodecs.VAR_INT, ChargedReleasePayload::releaseLevel,
            ChargedReleasePayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 服务端接收入口。
     * <p>
     * {@code enqueueWork} 保证逻辑跑在服务端主线程上 —— 这一步是必须的：
     * {@link ChargedCasts#fire} 会临时改写技能实例的等级字段并执行技能动作，
     * 绝不能在工作线程上发生。
     */
    public static void handleServer(final @NotNull ChargedReleasePayload payload, final @NotNull IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }

            MagicData magic = MagicData.getData(player);
            DragonAbilityInstance instance = magic.getAbility(payload.ability());

            if (instance == null) {
                return;
            }

            // 返回值刻意不处理：未接管（{@code handled() == false}）表示"这次请求不该由本模组结算"
            // （类型不匹配 / 已经蓄满被原生释放过 / 技能未解锁），
            // 此时什么都不做才是正确的 —— 服务端既不该释放也不该额外收尾，
            // 客户端的停机由它自己的本地收尾完成。
            // 而"服务端复算后档位不足"属于<b>已经接管</b>的情形，由 fire 内部按取消收尾并回传权威值
            ChargedCasts.fire(player, instance, payload.chargeTicks(), payload.releaseLevel());
        });
    }
}
