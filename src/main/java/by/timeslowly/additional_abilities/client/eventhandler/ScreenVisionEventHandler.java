package by.timeslowly.additional_abilities.client.eventhandler;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.renderer.EdgeLightRenderer;
import by.timeslowly.additional_abilities.client.renderer.ScreenBlurRenderer;
import by.timeslowly.additional_abilities.client.state.ClientScreenVisionState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端屏幕视觉渲染（<b>仅客户端加载</b>，标注 {@code value = Dist.CLIENT} 后
 * NeoForge 在专用服务端不会加载本类）。
 * <p>
 * 各视觉类型的施加点：
 * <ul>
 *     <li>抖动 —— {@link ViewportEvent.ComputeCameraAngles}：每帧渲染相机前触发，
 *         无需 Mixin 即可改写镜头角度；</li>
 *     <li>模糊 —— {@link RenderLevelStageEvent.AfterLevel}：世界（含透明层与粒子）已画完，
 *         手持物 / 屏幕特效 / HUD / 界面都还没开始画，对主渲染目标做后处理
 *         <b>只糊画面、不糊 UI</b>；</li>
 *     <li>边光 —— {@link RenderGuiLayerEvent.Post} 的 {@code CAMERA_OVERLAYS} 图层：
 *         该图层正是原版晕影 / 水 / 火 / 望远镜覆盖层所在处，<b>排在准星与快捷栏之前</b>，
 *         遮罩因此垫在 HUD 之下，而绘制时机又晚于模糊 ⇒ 遮罩不会被糊掉。</li>
 * </ul>
 *
 * <h2>26.1.2 的两处落点变化</h2>
 * <ol>
 *     <li><b>模糊从 {@code RenderGuiEvent.Pre} 改到 {@code RenderLevelStageEvent.AfterLevel}</b>。
 *         26.1.2 的 GUI 是「先 extract 成渲染状态、再统一提交绘制」的两段式，
 *         {@code RenderGuiEvent} 发生在 <b>extract 阶段</b>（GUI 绘制命令一条都还没提交）。
 *         在那里做后处理，模糊的是上一帧的画面，而本帧 GUI 随后整批画上去 ——
 *         结果是「模糊晚一帧」外加「HUD 不糊」的双重错误。
 *         {@code AfterLevel} 落在 {@code LevelRenderer#renderLevel} 返回之后、
 *         {@code renderItemInHand} / 屏幕特效 / {@code guiRenderer.render} 之前，
 *         正好是「世界已画完、UI 未开始」的那一刻。</li>
 *     <li><b>{@code GuiGraphics} 已删除</b>，改用 {@code GuiGraphicsExtractor}
 *         （{@link RenderGuiLayerEvent#getGuiGraphics()} 的返回类型也随之改变）。
 *         {@link RenderGuiLayerEvent.Post} 的构造参数变为
 *         {@code (GuiGraphicsExtractor, DeltaTracker, Identifier, GuiLayer)}，
 *         但 handler 里只需要 {@code getName()} 与 {@code getGuiGraphics()}，调用方式不变。</li>
 * </ol>
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID, value = Dist.CLIENT)
public class ScreenVisionEventHandler {
    @SubscribeEvent
    public static void onComputeCameraAngles(final @NotNull ViewportEvent.ComputeCameraAngles event) {
        float offset = ClientScreenVisionState.rollOffset(event.getPartialTick());

        if (offset != 0.0F) {
            // 只动 roll：视觉上是镜头晃动，但不改变准星指向，不干扰玩家操作
            event.setRoll(event.getRoll() + offset);
        }
    }

    /**
     * 模糊：世界已画完、UI 尚未开始。
     * <p>
     * 选 {@code AfterLevel} 而不是 {@code AfterTranslucentParticles}：
     * 后者在 {@code renderLevel} <b>内部</b>、天气与三维准星等 pass 之前，
     * 而定点要的是「世界彻底画完」。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(final @NotNull RenderLevelStageEvent.AfterLevel event) {
        ScreenBlurRenderer.render();
    }

    @SubscribeEvent
    public static void onRenderGuiLayerPost(final @NotNull RenderGuiLayerEvent.Post event) {
        if (!event.getName().equals(VanillaGuiLayers.CAMERA_OVERLAYS)) {
            return;
        }

        // 边光：叠在原版晕影之后、准星与快捷栏之前。
        // ⚠️ 不能指望"图层没渲染就不会发这个事件"来省掉 hideGui 自检：
        // GuiLayerManager 是把 !hideGui 包在**图层体内部**、而 Post 事件在包装**之外**发出的，
        // 所以按 F1 时本事件照常触发（自检在 EdgeLightRenderer#render 里）。
        EdgeLightRenderer.render(event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void onClientTick(final @NotNull ClientTickEvent.Post event) {
        ClientScreenVisionState.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(final @NotNull ClientPlayerNetworkEvent.LoggingOut event) {
        // 断线/退出存档时清空，避免状态残留到下一个世界
        ClientScreenVisionState.clear();
    }
}
