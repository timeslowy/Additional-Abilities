package by.timeslowly.additional_abilities.commands;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 本模组独立指令树 {@code /additional-abilities …} 的<b>唯一注册点</b>。
 *
 * <h2>为什么由单一类持有根节点</h2>
 * Brigadier 的 {@code CommandDispatcher#register} 对同名根节点是"合并子节点"语义
 * （{@code CommandNode#addChild} 在发现同名子节点时只把后者的 children 挂过去），
 * 而<b>根节点自身的 {@code requires} 判定保留最早注册的那一份</b>、{@code executes} 则会被后者覆盖。
 * 若让每个子指令各自注册一次根节点，权限门槛究竟由谁生效就取决于注册顺序 —— 隐式且脆弱。
 * 因此这里由唯一一处注册根节点并统一施加权限判定，各子指令只提供自己的子树。
 *
 * <h2>子指令</h2>
 * <ul>
 *     <li>{@code /additional-abilities simple-screen-vision clear <targets>}
 *         —— 清空目标玩家身上的屏幕视觉（{@link SimpleScreenVisionCommand}）；</li>
 *     <li>{@code /additional-abilities domain clear <targets>}
 *         —— 清除目标玩家留下的领域（{@link DomainCommand}）；</li>
 *     <li>{@code /additional-abilities block-glow clear <targets>}
 *         —— 清除目标玩家造成的方块发光（{@link BlockGlowCommand}）。</li>
 * </ul>
 *
 * <b>权限等级 2</b>（与 {@code /effect clear} 同档，单机存档的拥有者默认满足），
 * 施加在根节点上，因此对全部子指令一致生效。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public final class AACommands {
    /** 指令根名（含连字符，与模组 id 的书写习惯一致）。 */
    public static final String ROOT = "additional-abilities";

    /** 目标选择器参数名，两条子指令共用。 */
    public static final String TARGETS = "targets";

    private AACommands() {
        // 指令注册类
    }

    @SubscribeEvent
    public static void onRegisterCommands(final @NotNull RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal(ROOT)
                .requires(source -> source.hasPermission(2))
                .then(SimpleScreenVisionCommand.subtree())
                .then(DomainCommand.subtree())
                .then(BlockGlowCommand.subtree()));
    }
}
