package by.timeslowly.additional_abilities.common.network.charged;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.activation.ChargedCasts;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * 滚轮选档的<b>客户端 → 服务端</b>同步包（服务于 {@code additional_abilities:optional_charged}）。
 *
 * <h2>为什么需要单独同步</h2>
 * "选定档位"本身是纯客户端交互状态，<b>服务端不需要它也能正确结算</b> ——
 * 真正生效的档位随 {@link ChargedReleasePayload} 单独上报，并由服务端重新校验。
 * 本包存在的唯一理由是让服务端侧具备"当前选档"的可见性，
 * 以支撑 {@code /dragon-ability query … current_selected_level} 这条调试子命令。
 * <p>
 * 因此它<b>只在玩家滚轮调整档位时发送</b>（一次施法内至多几次），不做逐刻同步。
 *
 * <h2>服务端会校验，不是照单全收</h2>
 * 本包的调用方不可信。{@link ChargedCasts#onSelectionChanged} 会先确认
 * "此刻正在施放的就是这个技能"、且该技能允许选档，再把档位钳到 {@code [0, 已达档位]}；
 * 任一条不成立就直接忽略。因此持续用任意技能键发包<b>不会</b>让服务端记录增长。
 * <p>
 * 记录的清除发生在<b>所有</b>施法终结路径的共同收尾漏斗上
 * （{@link ChargedCasts#onCastingStopped}），包括不经过本模组结算服务的
 * DS 原生读条完成，因此不会残留到下一次施法。
 *
 * @param ability       技能注册键
 * @param selectedLevel 选定档位；{@link ChargedReleasePayload#AUTO}（{@code -1}）表示"自动跟随已达档位"
 */
public record OptionalChargedSelectionPayload(ResourceKey<DragonAbility> ability, int selectedLevel) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OptionalChargedSelectionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "optional_charged_selection"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OptionalChargedSelectionPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(DragonAbility.REGISTRY), OptionalChargedSelectionPayload::ability,
            ByteBufCodecs.VAR_INT, OptionalChargedSelectionPayload::selectedLevel,
            OptionalChargedSelectionPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端接收入口：只做记录，不做任何校验或结算。 */
    public static void handleServer(final @NotNull OptionalChargedSelectionPayload payload, final @NotNull IPayloadContext context) {
        context.enqueueWork(() -> ChargedCasts.onSelectionChanged(context.player(), payload.ability(), payload.selectedLevel()));
    }
}
