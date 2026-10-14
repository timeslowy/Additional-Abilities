package by.timeslowly.additional_abilities.client.eventhandler;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.renderer.ScreenBlurRenderer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 模糊后处理链的生命周期接线（<b>仅客户端加载</b>）。
 * <p>
 * {@link ScreenBlurRenderer} 自己只是个静态持有者，既不知道资源何时被换掉、也不知道游戏何时关闭。
 * 本类把那两条生命周期接回来：
 * <ul>
 *     <li><b>资源重载</b> → {@link ScreenBlurRenderer#invalidate()}：作废旧链并复位失败标志，
 *         下一次渲染按需重建；</li>
 *     <li><b>游戏关闭</b> → {@link ScreenBlurRenderer#shutdown()}：在 GL 上下文仍有效时释放。</li>
 * </ul>
 *
 * <h2>为什么重载监听器要在这里手动注册</h2>
 * {@link RegisterClientReloadListenersEvent} 属于 <b>mod 事件总线</b>，而 NeoForge 21.1 已废弃
 * {@code @EventBusSubscriber#bus()}，只能由主类拿到 {@code IEventBus} 手动 {@code addListener}
 * （与 {@code AAClientSetup} 里注册 GUI 图层同一处理方式，注册点在
 * {@code AAClientSetup#register} 的物理端判定之后，专用服务端不会走到）。
 * <p>
 * 关闭事件 {@link GameShuttingDownEvent} 走的是普通事件总线，用
 * {@code value = Dist.CLIENT} 即可获得物理端门控 —— 该事件在专用服务端同样会被触发
 * （{@code DedicatedServer} 自己就 post 它），所以这道门控是必需的，不是保险。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID, value = Dist.CLIENT)
public final class ScreenBlurLifecycleHandler {
    /**
     * 重载监听器：<b>没有要读的资源</b>，只借"重载发生"这个时机作废旧链。
     * <p>
     * 因此 {@code prepare} 阶段无事可做（{@link Unit} 就是它的全部返回值），
     * 动作挂在 {@code barrier.wait(...)} 之后、并显式指定跑在 <b>game 执行器</b>上。
     * <p>
     * <b>执行器不能省</b>：本类实现的是最原始的 {@link PreparableReloadListener}，
     * 没有任何默认调度 —— 而重载链的发起方在后台线程上完成 future，
     * 直接 {@code thenRun} 就会在后台线程里执行 {@code PostChain#close()}。
     * 那一步会销毁渲染目标并释放 GL 程序对象，两者内部都有
     * {@code RenderSystem.assertOnRenderThread()}，客户端的渲染线程正是游戏线程，
     * 因此必须显式交回 game 执行器（重载由客户端刻发起，通常就是原地执行）。
     */
    private static final PreparableReloadListener RELOAD_LISTENER = new PreparableReloadListener() {
        @Override
        public @NotNull CompletableFuture<Void> reload(final @NotNull PreparationBarrier barrier,
                                                       final @NotNull ResourceManager resourceManager,
                                                       final @NotNull ProfilerFiller preparationProfiler,
                                                       final @NotNull ProfilerFiller reloadProfiler,
                                                       final @NotNull Executor backgroundExecutor,
                                                       final @NotNull Executor gameExecutor) {
            return barrier.wait(Unit.INSTANCE).thenRunAsync(ScreenBlurRenderer::invalidate, gameExecutor);
        }

        @Override
        public @NotNull String getName() {
            return "Additional Abilities Screen Blur";
        }
    };

    private ScreenBlurLifecycleHandler() {
        // 接线入口
    }

    /**
     * mod 总线注册入口：把重载监听器交给客户端的 {@code ReloadableResourceManager}。
     * <p>
     * 由 {@code AAClientSetup#register} 在物理端判定之后调用（本类整体是客户端专属的，
     * 专用服务端连加载都不该加载到它）。
     */
    public static void registerReloadListener(final @NotNull RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(RELOAD_LISTENER);
    }

    /**
     * 游戏关闭：此时 GL 上下文仍有效（该事件自身的契约），可以安全释放链。
     * <p>
     * 不释放也不是"泄漏到进程退出就没事"——{@code PostChain} 持有的临时渲染目标是 GL 侧对象，
     * 由驱动而非 JVM 管理，让它们随着上下文销毁被动回收属于碰运气。
     */
    @SubscribeEvent
    public static void onGameShuttingDown(final @NotNull GameShuttingDownEvent event) {
        ScreenBlurRenderer.shutdown();
    }
}
