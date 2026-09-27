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
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
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
 *     <li>模糊 —— {@link RenderGuiEvent.Pre}：世界已画完、HUD 与界面尚未绘制，
 *         此时对主渲染目标做后处理，正好只糊画面不糊 UI；</li>
 *     <li>边光 —— {@link RenderGuiLayerEvent.Post} 的 {@code CAMERA_OVERLAYS} 图层：
 *         该图层正是原版晕影 / 水 / 火 / 望远镜覆盖层所在处，<b>排在准星与快捷栏之前</b>，
 *         遮罩因此垫在 HUD 之下，而绘制时机又晚于模糊的后处理链 ⇒ 遮罩不会被糊掉。</li>
 * </ul>
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

    @SubscribeEvent
    public static void onRenderGuiPre(final @NotNull RenderGuiEvent.Pre event) {
        // 模糊：此刻世界已渲染完毕，界面还未绘制
        ScreenBlurRenderer.render(event.getPartialTick().getGameTimeDeltaPartialTick(false));
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
