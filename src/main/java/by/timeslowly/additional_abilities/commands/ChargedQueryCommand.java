package by.timeslowly.additional_abilities.commands;

import by.dragonsurvivalteam.dragonsurvival.commands.arguments.DragonAbilityArgument;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.DSCommands;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.datagen.Translation;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.util.DSColors;
import by.timeslowly.additional_abilities.Additional_abilities;
import by.timeslowly.additional_abilities.common.ability.ChargedCasts;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicNCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.ToIntBiFunction;

/**
 * 扩展现有查询指令，为蓄力档位族系激活类型补两条只读查询：
 * <ul>
 *     <li>{@code /dragon-ability query <target> <dragon_ability> current_charged_level}
 *         —— 当前已蓄到的档位；</li>
 *     <li>{@code /dragon-ability query <target> <dragon_ability> current_selected_level}
 *         —— {@code additional_abilities:optional_charged} 下"此刻松手会放几档"
 *         （{@code 0} 表示已选定为取消、{@code -1} 表示无有效档位可参照）。</li>
 * </ul>
 *
 * <h2>为什么是"扩展"而不是"新建"</h2>
 * 指令树由 DS 的 {@code DragonAbilityCommand} 注册为
 * {@code dragon-ability → query → target → dragon_ability → {level, max_level, …}}。
 * 这里以 {@link EventPriority#LOWEST} 优先级挂到 {@link RegisterCommandsEvent} 上，
 * 保证在 DS（默认优先级）完成注册之后再往同一个节点上 {@code addChild}，
 * 因此不会与 DS 抢根节点，也不会影响原有子命令（尤其是 {@code level} 的语义保持不变）。
 *
 * <h2>语义</h2>
 * 蓄力档位激活类型下，"技能输出的实际等级"未必等于玩家的升级等级，因此单列查询：
 * <ul>
 *     <li>{@code current_charged_level}：正在蓄力 → 当前蓄力所对应的档位；
 *         未在蓄力 → 最近一次实际释放所用的档位（取消不写入）；技能不属于本族系 → 0。</li>
 *     <li>{@code current_selected_level}：手动选定过 → 该选定值（含 0 = 取消）；
 *         自动跟随 → 已蓄到的档位；未在蓄力 / 尚未达最低档 / 不属于本族系 → -1。
 *         详见 {@link ChargedCasts#resolveSelectedLevel}。</li>
 * </ul>
 * 输出文案直接复用 DS 自己的 {@code ability.query_result} 语言键，
 * 与 {@code level} / {@code cast_time} 等既有条目的显示格式完全一致。
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID)
public final class ChargedQueryCommand {
    private static final String DRAGON_ABILITY = "dragon-ability";
    private static final String QUERY = "query";
    private static final String CURRENT_CHARGED_LEVEL = "current_charged_level";
    private static final String CURRENT_SELECTED_LEVEL = "current_selected_level";

    /** 复用 DS 的查询结果文案：{@code %s of the ability %s from player %s has the value %s}。 */
    private static final String QUERY_RESULT = Translation.Type.COMMAND.wrap("ability.query_result");
    /** 复用 DS 的"玩家没有该技能"文案。 */
    private static final String UNKNOWN_ABILITY = Translation.Type.COMMAND.wrap("ability.unknown");

    private static final DynamicNCommandExceptionType UNKNOWN_ABILITY_EXCEPTION =
            new DynamicNCommandExceptionType(data -> Component.translatable(UNKNOWN_ABILITY, data));

    private ChargedQueryCommand() {
        // 指令注册类
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRegisterCommands(final @NotNull RegisterCommandsEvent event) {
        CommandNode<CommandSourceStack> abilityNode = findAbilityNode(event);

        // DS 未按预期结构注册时静默跳过：宁可没有这条子命令，也不要让指令注册整体失败
        if (abilityNode == null) {
            return;
        }

        abilityNode.addChild(Commands.literal(CURRENT_CHARGED_LEVEL)
                .executes(source -> query(source, ChargedCasts::resolveQueryLevel))
                .build());

        abilityNode.addChild(Commands.literal(CURRENT_SELECTED_LEVEL)
                .executes(source -> query(source, ChargedCasts::resolveSelectedLevel))
                .build());
    }

    /** 沿 {@code dragon-ability → query → target → dragon_ability} 找到要挂子命令的节点。 */
    private static @Nullable CommandNode<CommandSourceStack> findAbilityNode(final @NotNull RegisterCommandsEvent event) {
        CommandNode<CommandSourceStack> node = event.getDispatcher().getRoot().getChild(DRAGON_ABILITY);

        if (node != null) {
            node = node.getChild(QUERY);
        }

        if (node != null) {
            node = node.getChild(DSCommands.TARGET);
        }

        return node == null ? null : node.getChild(DragonAbilityArgument.ID);
    }

    /**
     * 两个子命令共用的查询主体：解析目标与技能，再交给具体的取值函数。
     *
     * @param source   指令上下文
     * @param resolver 取值函数，见 {@link ChargedCasts#resolveQueryLevel} /
     *                 {@link ChargedCasts#resolveSelectedLevel}
     * @return 取到的档位值（同时作为指令返回值，便于 {@code /execute store} 使用）
     */
    private static int query(final @NotNull CommandContext<CommandSourceStack> source,
                             final @NotNull ToIntBiFunction<Player, DragonAbilityInstance> resolver) throws CommandSyntaxException {
        Player player = EntityArgument.getPlayer(source, DSCommands.TARGET);
        Holder<DragonAbility> ability = DragonAbilityArgument.get(source);

        // 与 DS 的 query 保持一致的报错条件：目标不是龙、或目标没有该技能
        if (!DragonStateProvider.isDragon(player)) {
            throw UNKNOWN_ABILITY_EXCEPTION.create(null, player.getDisplayName(), ability.getRegisteredName());
        }

        MagicData magic = MagicData.getData(player);
        DragonAbilityInstance instance = magic.getAbility(ability.getKey());

        if (instance == null) {
            throw UNKNOWN_ABILITY_EXCEPTION.create(null, player.getDisplayName(), ability.getRegisteredName());
        }

        int result = resolver.applyAsInt(player, instance);

        source.getSource().sendSuccess(() -> Component.translatable(
                QUERY_RESULT,
                DSColors.withColor(source.getNodes().getLast().getNode().getName(), DSColors.GOLD),
                DSColors.withColor(ability.getRegisteredName(), DSColors.GOLD),
                DSColors.withColor(player.getDisplayName(), DSColors.GOLD),
                DSColors.withColor(result, DSColors.GOLD)
        ), false);

        return result;
    }
}
