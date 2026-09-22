package by.timeslowly.additional_abilities.commands;

import by.timeslowly.additional_abilities.registry.AAAttachments;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 调试子指令：{@code /additional-abilities domain clear <targets>}。
 *
 * <h2>语义</h2>
 * 清除 {@code <targets>} 选中的玩家<b>作为施法者</b>所建立的、当前仍然存活的所有领域
 * （即 {@code additional_abilities:domain} 目标类型留下的那些区域）。
 * 走的是与"自然到期"完全相同的移除路径，因此 {@code remove_effects_on_end: true} 的领域
 * 同样会对范围内目标执行一次收尾 {@code remove}。
 *
 * <h2>为什么跨维度扫描</h2>
 * 领域挂在各维度自己的 {@code Level} 附件上（{@code AAAttachments.DOMAIN}）。
 * 施法者一旦换了维度，旧维度里的领域<b>不会</b>自动消失 —— 它只是"施法者不可用"而被跳过结算，
 * 仍会照常倒计时。所以只扫施法者当前所在维度会漏掉这些残留，必须遍历
 * {@code MinecraftServer#getAllLevels()}。
 *
 * <h2>为什么按施法者而不是按"被影响的实体"</h2>
 * 领域是施法者留下的对象（去重键为「施法者 + 技能」），而领域对目标施加的效果来自任意
 * DS 效果类型（药水 / 属性修饰 / 函数…），没有一份"这个领域给谁加过什么"的通用账本可供撤销。
 * 因此"清除领域"的可靠含义就是<b>移除这些领域本身</b>：此后不再结算，也不会再刷新效果；
 * 已经落在目标身上的限时效果则按各自的 duration 自然过期。
 *
 * <h2>返回值</h2>
 * 返回实际被移除的领域数量（可直接被 {@code /execute store result} 取用）；
 * 若目标玩家名下没有存活领域则为 {@code 0}，并如实提示。
 */
public final class DomainCommand {
    private static final String NAME = "domain";
    private static final String CLEAR = "clear";

    /** 语言键：`已清除 %1$s 名玩家建立的 %2$s 个领域`。 */
    private static final String CLEAR_SUCCESS = "commands.additional_abilities.domain.clear.success";

    private DomainCommand() {
        // 指令注册类
    }

    /** 本子指令的子树；由 {@link AACommands} 挂到 {@code /additional-abilities} 下。 */
    public static LiteralCommandNode<CommandSourceStack> subtree() {
        return Commands.literal(NAME)
                .then(Commands.literal(CLEAR)
                        .then(Commands.argument(AACommands.TARGETS, EntityArgument.players())
                                .executes(DomainCommand::clear)))
                .build();
    }

    private static int clear(final @NotNull CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, AACommands.TARGETS);
        Set<UUID> casters = targets.stream().map(ServerPlayer::getUUID).collect(Collectors.toSet());

        MinecraftServer server = context.getSource().getServer();
        int removed = 0;

        for (ServerLevel level : server.getAllLevels()) {
            removed += level.getData(AAAttachments.DOMAIN).clearByCasters(level, casters);
        }

        int cleared = removed;
        context.getSource().sendSuccess(
                () -> Component.translatable(CLEAR_SUCCESS, targets.size(), cleared), false);

        return cleared;
    }
}
