package by.timeslowly.additional_abilities.common;

import by.timeslowly.additional_abilities.client.hud.ChargedIndicatorLayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端专属的 mod 事件总线注册入口。
 *
 * <h2>为什么需要这一层间接</h2>
 * {@code RegisterGuiLayersEvent} 属于 <b>mod 事件总线</b>，而 NeoForge 21.1 已废弃
 * {@code @EventBusSubscriber#bus()}，只能由主类拿到 {@code IEventBus} 手动 {@code addListener}。
 * 但主类在专用服务端同样会被加载，而 GUI 图层相关的类型（{@code GuiGraphics} 等）
 * 在服务端并不存在 —— 因此这里加一道物理端判定：
 * <p>
 * 本类本身只引用通用类型（{@code IEventBus} / {@code FMLEnvironment} / {@code Dist}），
 * 对客户端类 {@link ChargedIndicatorLayer} 的引用只出现在 {@code register} 的<b>方法体</b>里。
 * 方法体中的符号引用是运行到该行时才解析的，专用服务端在上面那行 {@code return} 就结束了，
 * 永远不会加载客户端类型。
 */
public final class AAClientSetup {
    private AAClientSetup() {
        // 注册入口
    }

    public static void register(final @NotNull IEventBus modEventBus) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }

        // 蓄力档位数字提示图层：叠加在 DS 的魔力 HUD（含蓄力条）之上
        modEventBus.addListener(ChargedIndicatorLayer::registerLayers);
    }
}
