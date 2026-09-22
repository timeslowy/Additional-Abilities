package by.timeslowly.additional_abilities.common.eventhandler.abilities;

import by.timeslowly.additional_abilities.common.ability.domains.DomainData;
import by.timeslowly.additional_abilities.registry.AAAttachments;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 领域逐 tick 推进的接线点。
 *
 * <h2>为什么用 {@code LevelTickEvent.Post}</h2>
 * <ul>
 *     <li>它按<b>维度</b>触发，天然与「领域属于某个 {@code ServerLevel}」的数据布局对齐，
 *         不需要像 {@code ServerTickEvent.Post} 那样再自己枚举 {@code server.getAllLevels()}；</li>
 *     <li>它<b>在客户端也会触发</b>（{@code ClientLevel}），因此必须用
 *         {@code instanceof ServerLevel} 过滤——客户端不需要、也没有这些领域；</li>
 *     <li>触发时机在 {@code MinecraftServer#tickChildren} 中「维度 tick 代码块之后」
 *         （{@code EventHooks#onPostLevelTick}），因此它<b>晚于</b> {@code PlayerTickEvent.Post}。
 *         这一点对顺序很关键：DS 的 {@code MagicData#tickAbilities} 挂在
 *         {@code PlayerTickEvent.Post} 上，施法者在那一帧才建出领域；等他建完，
 *         本监听器随后才推进领域，同 tick 内不会出现"刚建就被结算一次又结算一次"的重复。</li>
 * </ul>
 *
 * 首次结算（效果即时生效）由 {@code DomainTarget#apply} 在建立领域时直接调用，
 * 不等本监听器；本监听器只负责后续的 {@code apply_interval} 结算与到期移除。
 */
public final class DomainTickHandler {
    private DomainTickHandler() { /* 工具类 */ }

    public static void onLevelTick(final @NotNull LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        DomainData data = level.getData(AAAttachments.DOMAIN);
        data.tick(level);
    }
}
