package by.timeslowly.additional_abilities.commands;

import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.common.network.ScreenVisionClearPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * 调试指令：{@code /simple-screen-vision clear <targets>}。
 * <p>
 * {@code <targets>} 用原版 {@link EntityArgument#players()}，因此三种写法都支持：
 * 玩家名（{@code Steve}）、目标选择器（{@code @a} / {@code @p} / {@code @a[distance=..10]}）、以及 UUID。
 * 作用于玩家是因为只有玩家有"画面"可言。
 * <p>
 * <b>为什么只发包、服务端不做别的</b>：简单视觉效果全部是客户端的渲染状态
 * （{@code ClientScreenVisionState} 里的分槽），服务端不持有任何副本，能做的只有通知目标玩家清空。
 * 同理，被动技能会在下一拍重新下发，清空只对当下有效——要让效果彻底消失得停用技能。
 * <p>
 * 权限等级 2（与 {@code /effect clear} 同档），单机存档的拥有者默认满足。
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID)
public final class SimpleScreenVisionCommand {
    private static final String ROOT = "simple-screen-vision";
    private static final String CLEAR = "clear";
    private static final String TARGETS = "targets";

    private static final String CLEAR_SUCCESS = "commands.additional_abilities.simple_screen_vision.clear.success";

    private SimpleScreenVisionCommand() {
        // 指令注册类
    }

    @SubscribeEvent
    public static void onRegisterCommands(final @NotNull RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal(ROOT)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal(CLEAR)
                        .then(Commands.argument(TARGETS, EntityArgument.players())
                                .executes(SimpleScreenVisionCommand::clear))));
    }

    private static int clear(final @NotNull CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, TARGETS);

        for (ServerPlayer target : targets) {
            PacketDistributor.sendToPlayer(target, new ScreenVisionClearPayload());
        }

        int count = targets.size();
        context.getSource().sendSuccess(() -> Component.translatable(CLEAR_SUCCESS, count), false);

        return count;
    }
}
