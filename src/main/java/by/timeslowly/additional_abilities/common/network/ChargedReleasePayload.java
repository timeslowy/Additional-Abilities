package by.timeslowly.additional_abilities.common.network;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.common.ability.ChargedCasts;
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
 * 蓄力释放的<b>客户端 → 服务端</b>请求包。
 * <p>
 * 蓄力档位激活类型需要"松手即按档位释放"，而 {@code Activation} 接口没有松手回调、
 * DS 的客户端处理器又会在松手时直接取消施法，因此由本模组的客户端处理器抢先拦下并发出此包
 * （详见 {@code client.eventhandler.ChargedCastClientHandler}）。
 * <p>
 * 只上报"蓄力时长"而不上报"档位"：档位由服务端按同一套
 * {@link by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargedActivation}
 * 换算规则复算，并先把时长钳制在 {@code [0, cast_time]} 内，
 * 因此客户端无法通过改包直接指定一个超出自身水平的结果。
 *
 * @param ability     技能注册键
 * @param chargeTicks 客户端松手那一刻的已蓄力游戏刻数
 */
public record ChargedReleasePayload(ResourceKey<DragonAbility> ability, int chargeTicks) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ChargedReleasePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Additional_abilities.MOD_ID, "charged_release"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChargedReleasePayload> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(DragonAbility.REGISTRY), ChargedReleasePayload::ability,
            ByteBufCodecs.VAR_INT, ChargedReleasePayload::chargeTicks,
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

            // 未达最低蓄力时长 / 已经蓄满被原生释放过 / 类型不匹配时，
            // fire 会返回 false 并什么都不做，等价于"取消施法"
            ChargedCasts.fire(player, instance, payload.chargeTicks());
        });
    }
}
