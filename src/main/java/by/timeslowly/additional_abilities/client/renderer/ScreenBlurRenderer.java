package by.timeslowly.additional_abilities.client.renderer;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.state.ClientScreenVisionState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 屏幕模糊的后处理链（<b>仅客户端加载</b>）。
 * <p>
 * 施加点是 NeoForge 的 {@code RenderGuiEvent.Pre}：此时世界（含极致画质下的透明层合成）
 * 已经画完、HUD 与任何界面尚未绘制，所以对主渲染目标做一遍模糊，<b>只糊画面、不糊 UI</b>。
 * <p>
 * 后处理链来自本模组资源 {@code additional_abilities:shaders/post/blur.json}，
 * 由 6 个 pass（3 轮 × 横/纵）组成可分离盒式模糊，半径由 {@code Radius} uniform
 * 每帧写入（见 {@link ClientScreenVisionState#blurRadius()}）——着色器名一律走本模组命名空间，
 * 不复用原版的 {@code box_blur}（{@code EffectInstance#close()} 会连带释放其缓存中的程序对象，
 * 与别的链共用名字属于不必要的耦合）。
 * <p>
 * 链按需构建（构建时会编译 GLSL），随后常驻复用；只在主渲染目标实例被替换时重建。
 *
 * <h2>生命周期为什么要外接</h2>
 * 早先本类<b>从不释放</b>链，理由是"与原版对 {@code GameRenderer#blurEffect} 的处理一致"——
 * <b>这条理由是错的</b>：原版 {@code GameRenderer#close()} 与 {@code #loadBlurEffect} 都会
 * {@code close()} 自己的 blurEffect（已从 {@code neoforge-21.1.250-sources.jar} 核实）。
 * 原版之所以看起来"不管"，是因为它的链由 {@code GameRenderer} 那个<b>资源重载监听器</b>
 * （{@code createReloadListener}，{@code getName()} 为 {@code "Shader Loader"}）负责重建。
 * <p>
 * 本类早先两头都缺，于是有两个后果：
 * <ul>
 *     <li><b>F3+T / 换资源包后旧链继续用</b>：链持有的是旧 {@code ResourceProvider} 上编出来的
 *         GL 程序，且 {@code unavailable} 这个"失败即永久放弃"的标志再没有任何东西能重置 ——
 *         修好坏的 shader 资源后，本次游戏内 blur 也永远不会恢复；</li>
 *     <li><b>关闭时资源不释放</b>：进程退出前 GL 上下文仍然有效，
 *         {@code PostChain#close()} 该做的事（释放临时渲染目标、程序对象）就没人做。</li>
 * </ul>
 * 修法是把两个生命周期接回来：{@link #invalidate()} 挂在<b>客户端资源重载</b>上
 * （下一次渲染按需重建，并顺带把 {@code unavailable} 一并复位），
 * {@link #shutdown()} 挂在<b>游戏关闭</b>上（GL 上下文仍有效时释放）。接线见
 * {@code client.eventhandler.ScreenBlurLifecycleHandler}。
 */
public final class ScreenBlurRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本模组的后处理链定义 */
    private static final ResourceLocation CHAIN_LOCATION =
            ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "shaders/post/blur.json");

    /** 低于该半径时着色器内部 {@code round()} 后为 0（等于没有模糊），整条链直接跳过 */
    private static final float MIN_VISIBLE_RADIUS = 0.5F;

    private static PostChain chain = null;
    /** 建链时所绑的主渲染目标，用于检测"目标被换掉"（分辨率/画质变化）后重建 */
    private static RenderTarget boundTarget = null;
    private static int chainWidth = -1;
    private static int chainHeight = -1;
    /**
     * 构建或编译失败后不再重试，避免每帧刷日志。
     * <p>
     * <b>它不是永久的</b>：{@link #invalidate()} 会把它一并复位 ——
     * 否则"资源重载后修好了 shader"这条恢复路径就是死的。
     */
    private static boolean unavailable = false;

    private ScreenBlurRenderer() {}

    /**
     * 作废当前链，使下一次渲染按需重建（客户端资源重载时调用）。
     * <p>
     * <b>为什么连 {@code unavailable} 一起复位</b>：那个标志的语义是"本次加载的资源构建不出链"，
     * 而资源重载恰恰会换掉这批判资源。不复位的话，一次坏资源就会把本次游戏内的 blur 永久判死，
     * 修好文件再 F3+T 也救不回来。
     */
    public static void invalidate() {
        discard();
        unavailable = false;
    }

    /**
     * 释放当前链（游戏关闭时调用）。
     * <p>
     * <b>必须在 GL 上下文仍有效时调用</b>：{@code PostChain#close()} 会销毁临时渲染目标并
     * 释放 GL 程序对象，这些调用内部都有 {@code RenderSystem.assertOnRenderThread()}。
     * NeoForge 的 {@code GameShuttingDownEvent} 正是为此提供的位置 ——
     * 它在客户端 GL 上下文仍有效时触发（见该事件自身的 javadoc）。
     */
    public static void shutdown() {
        discard();
    }

    /**
     * 按当前模糊强度渲染一帧。
     *
     * @param partialTick 部分刻（仅供后处理链内部的 Time uniform 使用）
     */
    public static void render(final float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();

        // 世界里才有"画面"可糊；开着界面时让位给原版菜单模糊，避免两层模糊叠加
        if (minecraft.level == null || minecraft.screen != null) {
            return;
        }

        float radius = ClientScreenVisionState.blurRadius();
        if (radius < MIN_VISIBLE_RADIUS) {
            return;
        }

        RenderTarget mainTarget = minecraft.getMainRenderTarget();

        PostChain postChain = getOrCreate(minecraft, mainTarget);
        if (postChain == null) {
            return;
        }

        // ⚠️ 这两行必须与后处理成对出现，否则「UI 全部消失」（症状等同按下 F1）：
        // ① PostPass#process 结尾会 outTarget.unbindWrite()，渲染完帧缓冲没有任何写入目标，
        //    紧接着绘制的 HUD 会落到默认帧缓冲上，随后被 blitToScreen 用主渲染目标的内容覆盖；
        // ② 后处理链绘制的全屏四边形若开着深度测试会写入深度，HUD 元素随后的 LEQUAL 测试会失败。
        //    禁用深度测试同时也阻止了深度写入，主渲染目标的深度缓冲保持原样。
        // 与原版 Screen#renderBlurredBackground 的 NeoForge 补丁同一处理方式。
        RenderSystem.disableDepthTest();

        try {
            postChain.setUniform("Radius", radius);
            postChain.process(partialTick);
        } finally {
            // 重新绑定主渲染目标，后续的 HUD / 界面才画在正确的缓冲上
            mainTarget.bindWrite(false);
            RenderSystem.enableDepthTest();
        }
    }

    /**
     * 取得（必要时构建）后处理链；失败时返回 {@code null} 并永久放弃本次启动的重试。
     */
    private static @Nullable PostChain getOrCreate(final @NotNull Minecraft minecraft, final @NotNull RenderTarget mainTarget) {
        if (unavailable) {
            return null;
        }

        // 主渲染目标被换掉后旧链已指向废弃目标，必须重建
        if (chain != null && boundTarget != mainTarget) {
            discard();
        }

        try {
            if (chain == null) {
                chain = new PostChain(
                        minecraft.getTextureManager(),
                        minecraft.getResourceManager(),
                        mainTarget,
                        CHAIN_LOCATION);
                boundTarget = mainTarget;
                chainWidth = -1;
                LOGGER.info("[screen_vision] 已构建模糊后处理链 {}", CHAIN_LOCATION);
            }

            // 窗口尺寸变化时同步内部临时目标，否则模糊范围不覆盖全屏
            if (chainWidth != mainTarget.width || chainHeight != mainTarget.height) {
                chain.resize(mainTarget.width, mainTarget.height);
                chainWidth = mainTarget.width;
                chainHeight = mainTarget.height;
            }

            return chain;
        } catch (Exception exception) {
            unavailable = true;
            discard();
            LOGGER.error("[screen_vision] 模糊后处理链构建失败，本次游戏内 blur 将不再生效", exception);
            return null;
        }
    }

    /** 丢弃当前链（下次需要时按需重建） */
    private static void discard() {
        if (chain != null) {
            chain.close();
            chain = null;
        }

        boundTarget = null;
        chainWidth = -1;
        chainHeight = -1;
    }
}
