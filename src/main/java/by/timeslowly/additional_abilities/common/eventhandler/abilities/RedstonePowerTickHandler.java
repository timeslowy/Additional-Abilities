package by.timeslowly.additional_abilities.common.eventhandler.abilities;

import by.timeslowly.additional_abilities.common.ability.block_effects.VirtualRedstonePower;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 虚拟红石供能的逐维度刻清理接线点。
 *
 * <h2>为什么用 {@code LevelTickEvent.Post}</h2>
 * <ul>
 *     <li>状态挂在 {@code ServerLevel} 的数据附件上，按维度触发天然与数据布局对齐，
 *         直接拿到本维度实例与它自己的 {@code getGameTime()}；</li>
 *     <li>它<b>在客户端也会触发</b>（{@code ClientLevel}），因此必须用 {@code instanceof ServerLevel} 过滤；</li>
 *     <li>时机晚于本维度的方块 / 实体 / 玩家 tick：当刻 {@code apply} 登记的贡献在当刻就能被消费者读到，
 *         而在本刻末尾才统一退场。这样 {@code pulse_duration = 1} 恰好覆盖“登记它的那一刻”。</li>
 * </ul>
 *
 * 本监听器只做清理与边沿通知，不建立任何状态：从未使用过红石效果的维度不会被建出空附件
 * （{@link VirtualRedstonePower#expire(ServerLevel)} 内部走 {@code getExistingDataOrNull}）。
 */
public final class RedstonePowerTickHandler {
    private RedstonePowerTickHandler() {
        // 工具类
    }

    public static void onLevelTick(final @NotNull LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        VirtualRedstonePower.expire(level);
    }
}
