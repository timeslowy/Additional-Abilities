package by.timeslowly.additional_abilities.common;

import by.timeslowly.additional_abilities.client.eventhandler.ScreenBlurLifecycleHandler;
import by.timeslowly.additional_abilities.client.hud.ChargedIndicatorLayer;
import by.timeslowly.additional_abilities.client.renderer.GlowOutlineRenderer;
import by.timeslowly.additional_abilities.client.renderer.ScreenBlurRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端专属内容的注册入口
 * （蓄力档位 HUD 图层 + 模糊管线 + 模糊链的生命周期接线 + 配置界面扩展点）。
 *
 * <h2>为什么需要这一层间接</h2>
 * {@code RegisterGuiLayersEvent} / {@code RegisterRenderPipelinesEvent} /
 * {@code AddClientReloadListenersEvent} 都属于 <b>mod 事件总线</b>，而 NeoForge 21.1 已废弃
 * {@code @EventBusSubscriber#bus()}，只能由主类拿到 {@code IEventBus} 手动 {@code addListener}。
 * 但主类在专用服务端同样会被加载，而 GUI 图层 / 渲染管线 / 后处理相关的类型
 * （{@code GuiGraphicsExtractor}、{@code RenderPipeline} 等）在服务端并不存在 ——
 * 因此这里加一道物理端判定：
 * <p>
 * 本类本身只引用通用类型（{@code IEventBus} / {@code ModContainer} / {@code FMLEnvironment}），
 * 对客户端类（{@link ChargedIndicatorLayer}、{@link ScreenBlurLifecycleHandler}、
 * {@link ScreenBlurRenderer}、{@code ConfigurationScreen}）的引用只出现在 {@link #register}
 * 的<b>方法体</b>里。方法体中的符号引用是运行到该行时才解析的，
 * 专用服务端在上面那行 {@code return} 就结束了，永远不会加载客户端类型。
 *
 * <h2>为什么必须注册 {@code IConfigScreenFactory}</h2>
 * 「模组列表 → 选中本模组 → 配置」这个按钮的<b>可点与否</b>只取决于该模组有没有注册这个扩展点：
 * {@code ModListScreen} 里的判定是
 * {@code configButton.active = IConfigScreenFactory.getForMod(selectedMod).isPresent();}。
 * 只调 {@code ModContainer#registerConfig} 是不够的 —— 那样配置文件会照常生成，
 * 但按钮始终是灰的、点不动。
 * <p>
 * 注册动作本身只是往 {@code ModContainer} 的 {@code extensionPoints} 里放一个条目，
 * 没有任何时序要求（构造期即可完成）；传方法引用还能让它保持懒求值，
 * 因此注册的这一刻并不会加载 {@code ConfigurationScreen}。
 *
 * <h2>为什么模糊管线必须在这里注册</h2>
 * 26.1.2 的 {@code ShaderManager#apply} 在每次资源重载时都会遍历
 * {@code RenderPipelines#getStaticPipelines()} 重新编译，而注册表按 location 去重。
 * 也就是说管线<b>只能注册一次</b>、且必须通过 {@link RegisterRenderPipelinesEvent} 进注册表；
 * 若每次渲染重建一条，重载时会直接抛
 * {@code IllegalStateException("Duplicate RenderPipeline registration ...")}。
 */
public final class AAClientSetup {
    private AAClientSetup() {
        // 注册入口
    }

    public static void register(final @NotNull IEventBus modEventBus, final @NotNull ModContainer modContainer) {
        if (!FMLEnvironment.getDist().isClient()) {
            return;
        }

        // 蓄力档位数字提示图层：叠加在 DS 的魔力 HUD（含蓄力条）之上
        modEventBus.addListener(ChargedIndicatorLayer::registerLayers);

        // 本模组的两条渲染管线：
        //   ① 方块发光的线框（26.1.2 起深度/混合状态由管线描述，不再有 GlStateBackup）
        //   ② 屏幕模糊的后处理（26.1.2 起后处理由 RenderPipeline 承担，不再是 PostChain）
        modEventBus.addListener(AAClientSetup::registerRenderPipelines);

        // 模糊后处理链的生命周期：资源重载时作废、游戏关闭时释放。
        // 26.1.2：RegisterClientReloadListenersEvent 已改名 AddClientReloadListenersEvent，
        // 且注册方式从「自带 key」变成「key + listener」(SortedReloadListenerEvent#addListener)。
        modEventBus.addListener(ScreenBlurLifecycleHandler::registerReloadListener);

        // 配置界面扩展点：让模组列表里的「配置」按钮变为可点，
        // 点进去用 NeoForge 自带的 ConfigurationScreen 渲染 additional_abilities-client.toml
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    private static void registerRenderPipelines(final @NotNull RegisterRenderPipelinesEvent event) {
        event.registerPipeline(GlowOutlineRenderer.pipeline());
        event.registerPipeline(ScreenBlurRenderer.pipeline());
    }
}
