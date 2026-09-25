package by.timeslowly.additional_abilities.commands;

import by.timeslowly.additional_abilities.common.ability.block_effects.BlockGlows;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 调试子指令：{@code /additional-abilities block-glow clear <targets>}。
 *
 * <h2>语义</h2>
 * 清除 {@code <targets>} 选中的玩家<b>作为施法者</b>所造成的、当前仍然存活的方块发光
 * （即 {@code additional_abilities:glow} 方块效果留下的那些条目）——
 * 与 {@link DomainCommand} 的口径一致：{@code <targets>} 选的是<b>造成者</b>，不是"画面被影响的人"。
 *
 * <h2>为什么"按施法者"而不是"按被影响玩家的画面"</h2>
 * 发光条目按「位置 + 颜色 + 显示类型」在服务端<b>共享</b>（正是这一点让不同施法者的效果可以叠加），
 * 因此服务端并不知道"某条发光只属于谁的画面"。而条目若只清客户端、服务端照旧刷新，
 * 最多 20 刻后（{@code BlockGlows.REFRESH_INTERVAL_TICKS}）就会重新亮起来，清空没有意义。
 * 所以可靠的语义只能是<b>撤销这些施法者的贡献</b>：某条发光只在它已无任何贡献者时才整条消失，
 * 还有其他玩家在贡献时原样保留 —— 不会误伤别人。
 *
 * <h2>清除后客户端如何立刻知道</h2>
 * 条目消失时服务端给附近玩家补发一份 {@code remainingTicks = 0} 的
 * {@code BlockGlowPayload}；客户端把该值理解为"这条已失效"并直接移除
 * （见 {@code client.ClientBlockGlowState#onReceive}）。这样不必新增一种包，也不需要移除专用的通道。
 *
 * <h2>返回值</h2>
 * 返回实际被<b>整条</b>移除的发光条目数（可被 {@code /execute store result} 取用）；
 * 目标玩家名下没有存活贡献时为 {@code 0}，并如实提示。
 */
public final class BlockGlowCommand {
    private static final String NAME = "block-glow";
    private static final String CLEAR = "clear";

    /** 语言键：`已清除 %1$s 名玩家造成的 %2$s 处方块发光`。 */
    private static final String CLEAR_SUCCESS = "commands.additional_abilities.block_glow.clear.success";

    private BlockGlowCommand() {
        // 指令注册类
    }

    /** 本子指令的子树；由 {@link AACommands} 挂到 {@code /additional-abilities} 下。 */
    public static LiteralCommandNode<CommandSourceStack> subtree() {
        return Commands.literal(NAME)
                .then(Commands.literal(CLEAR)
                        .then(Commands.argument(AACommands.TARGETS, EntityArgument.players())
                                .executes(BlockGlowCommand::clear)))
                .build();
    }

    private static int clear(final @NotNull CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, AACommands.TARGETS);
        Set<UUID> casters = targets.stream().map(ServerPlayer::getUUID).collect(Collectors.toSet());

        int removed = BlockGlows.clearByCasters(context.getSource().getServer(), casters);

        context.getSource().sendSuccess(
                () -> Component.translatable(CLEAR_SUCCESS, targets.size(), removed), false);

        return removed;
    }
}
