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
 * 因此它<b>只在玩家滚轮调整档位时发送</b>（一次施法内至多几次），不做逐刻同步；
 * 服务端在 {@link ChargedCasts#fire} 收尾时清掉记录，避免残留到下一次施法。
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
