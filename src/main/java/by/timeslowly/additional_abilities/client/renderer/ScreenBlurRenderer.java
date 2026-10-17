package by.timeslowly.additional_abilities.client.renderer;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.state.ClientScreenVisionState;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 屏幕模糊（<b>仅客户端加载</b>）。
 * <p>
 * 只糊画面、不糊 HUD：施加点是 {@code RenderLevelStageEvent.AfterLevel}
 * （见 {@code client.eventhandler.ScreenVisionEventHandler}）。
 * 那一刻世界（含透明层与粒子）已经画进主渲染目标，而手持物、屏幕特效、HUD 与界面都还没开始画，
 * 因此对主渲染目标做一遍后处理恰好只影响世界画面。
 *
 * <h2>26.1.2 为什么不能再沿用 {@code PostChain}</h2>
 * 1.21.1 的写法是 {@code new PostChain(...)} + {@code setUniform("Radius", …)} + {@code process(partialTick)}。
 * 26.1.2 里这三样全没了：
 * <ul>
 *     <li>{@code PostChain} 构造器私有，只能由
 *         {@code ShaderManager#getPostChain(id, allowedTargets)} 从
 *         {@code assets/<ns>/post_effect/<path>.json} 加载；</li>
 *     <li>链的 uniform 在<b>加载时</b>由 JSON 一次性写进 UBO，<b>没有</b>任何运行时入口 ——
 *         而本效果的半径每帧都变（{@link ClientScreenVisionState#blurRadius()}）；</li>
 *     <li>{@code process(RenderTarget, …)} 已标记 {@code @Deprecated}，官方路径是帧图。</li>
 * </ul>
 * 原版界面那条路（{@code GuiGraphicsExtractor#blurBeforeThisStratum} → {@code minecraft:blur}）
 * 也用不了：它的半径固定取自 {@code menuBackgroundBlurriness} 选项，技能参数插不进去。
 * <p>
 * 因此这里自己搭一条最小管线：{@link RenderPipeline} 用
 * {@code POST_PROCESSING_SNIPPET}（全屏三角形、顶点由 {@code gl_VertexID} 生成，
 * 顶点着色器直接复用原版的 {@code minecraft:core/screenquad}），
 * 半径与其方向步长通过 {@link MappableRingBuffer} 每帧写进 UBO。
 * 这与原版 {@code GameRenderer#processBlurEffect} 是同一套机制，区别只在本模组持有自己的管线、半径来自技能。
 *
 * <h2>可分离盒式模糊</h2>
 * 与 1.21.1 的 {@code blur.json} 保持同样的三档衰减（横纵各一遍、倍率 1 / 0.5 / 0.25），
 * 共 6 趟。每趟都需要一张临时渲染目标 —— 同一张贴图不能既当输入又当输出 ——
 * 于是「主 → 临时 → 主」交替进行，只需一张临时目标。
 *
 * <h2>生命周期</h2>
 * 管线持有 UBO 与 GPU 资源。接线见 {@code client.eventhandler.ScreenBlurLifecycleHandler}：
 * 资源重载 → {@link #invalidate()}（作废旧状态并复位 {@code unavailable}，下一次渲染按需重建），
 * 游戏关闭 → {@link #shutdown()}。
 */
public final class ScreenBlurRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 本模组后处理管线的注册键（必须全局唯一：重复 location 会在资源重载时抛异常） */
    private static final Identifier PIPELINE_ID =
            Identifier.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "pipeline/screen_blur");

    /** 片元着色器：可分离盒式模糊（{@code assets/additional_abilities/shaders/post/aa_box_blur.fsh}） */
    private static final Identifier FRAGMENT_SHADER =
            Identifier.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "post/aa_box_blur");

    /**
     * 顶点着色器：<b>直接复用原版</b>的 {@code minecraft:core/screenquad}。
     * <p>
     * 它只输出 {@code texCoord}、用 {@code gl_VertexID} 生成全屏三角形，不依赖任何 uniform，
     * 因此这条管线既不需要 {@code ProjMat} 也不需要 {@code ModelViewMat}，也就不必
     * {@code RenderSystem.bindDefaultUniforms} 里的投影矩阵。
     */
    private static final Identifier VERTEX_SHADER = Identifier.withDefaultNamespace("core/screenquad");

    /** UBO 布局：{@code vec2 Step} + {@code float Radius}（std140 下 8 + 4，按 16 对齐） */
    private static final int UNIFORM_SIZE = new Std140SizeCalculator().putVec2().putFloat().get();

    /** 三档衰减倍率，对应 1.21.1 {@code blur.json} 里六个 pass 的 {@code RadiusMultiplier} */
    private static final float[] RADIUS_MULTIPLIERS = {1.0F, 0.5F, 0.25F};

    /** 低于该半径时着色器内部 {@code round()} 后为 0（等于没有模糊），整条链直接跳过 */
    private static final float MIN_VISIBLE_RADIUS = 0.5F;

    /**
     * 模糊管线。
     * <p>
     * <b>必须是常量并由 {@code RegisterRenderPipelinesEvent} 注册一次</b>：
     * {@code ShaderManager#apply} 在每次资源重载时会遍历
     * {@code RenderPipelines#getStaticPipelines()} 重新编译，而注册表按 location 去重
     * （重复会抛 {@code IllegalStateException("Duplicate RenderPipeline registration")}）。
     * <p>
     * 深度状态刻意留空（{@code Optional.empty()}）：后处理是全屏覆盖，既不该读深度也不该写深度。
     * 若写入深度，随后绘制的 HUD 会被自己写下的深度值挡掉。
     */
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(PIPELINE_ID)
            .withVertexShader(VERTEX_SHADER)
            .withFragmentShader(FRAGMENT_SHADER)
            .withSampler("InSampler")
            .withUniform("BlurConfig", UniformType.UNIFORM_BUFFER)
            .withDepthStencilState(Optional.empty())
            .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
            .build();

    /** 参数 UBO 的环形缓冲（每趟写一次，3 缓冲轮转以免与上一趟的绘制竞争） */
    private static @Nullable MappableRingBuffer uniformBuffer = null;

    /** 主目标 ↔ 临时的中转目标（懒分配，尺寸跟随主目标） */
    private static @Nullable RenderTarget scratch = null;

    /**
     * 构建或编译失败后不再重试，避免每帧刷日志。
     * <p>
     * <b>它不是永久的</b>：{@link #invalidate()} 会把它一并复位 ——
     * 否则"资源重载后修好了 shader"这条恢复路径就是死的。
     */
    private static boolean unavailable = false;

    private ScreenBlurRenderer() {}

    /** 供 {@code RegisterRenderPipelinesEvent} 注册用 */
    public static @NotNull RenderPipeline pipeline() {
        return PIPELINE;
    }

    /**
     * 作废当前状态，使下一次渲染按需重建（客户端资源重载时调用）。
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
     * 释放 GPU 资源（游戏关闭时调用）。
     * <p>
     * <b>必须在 GL 上下文仍有效时调用</b>：{@link MappableRingBuffer#close()} 与
     * {@code RenderTarget#destroyBuffers()} 都会释放 GPU 对象，内部有
     * {@code RenderSystem.assertOnRenderThread()}。NeoForge 的 {@code GameShuttingDownEvent}
     * 正是为此提供的位置（见该事件自身的契约）。
     */
    public static void shutdown() {
        discard();
    }

    /**
     * 按当前模糊强度抹一帧。
     * <p>
     * 调用点必须满足两个条件：① 世界已经画完；② 手持物 / 屏幕特效 / HUD / 界面都还没开始画。
     * {@code RenderLevelStageEvent.AfterLevel} 恰好落在两者之间。
     */
    public static void render() {
        Minecraft minecraft = Minecraft.getInstance();

        // 世界里才有"画面"可糊；开着界面时让位给原版菜单模糊，避免两层模糊叠加
        if (minecraft.level == null || minecraft.screen != null) {
            return;
        }

        float radius = ClientScreenVisionState.blurRadius();

        if (radius < MIN_VISIBLE_RADIUS) {
            return;
        }

        if (unavailable) {
            return;
        }

        RenderTarget mainTarget = minecraft.getMainRenderTarget();

        if (mainTarget.getColorTextureView() == null || mainTarget.getColorTexture() == null) {
            return;
        }

        try {
            RenderTarget scratchTarget = scratchTarget(mainTarget);
            GpuTextureView mainView = mainTarget.getColorTextureView();
            GpuTextureView scratchView = scratchTarget.getColorTextureView();

            if (mainView == null || scratchView == null) {
                return;
            }

            boolean toScratch = true;

            for (float multiplier : RADIUS_MULTIPLIERS) {
                // 横向：X 方向步长 = 1/宽
                pass(toScratch ? mainView : scratchView, toScratch ? scratchTarget : mainTarget, radius * multiplier, true);
                // 纵向：Y 方向步长 = 1/高
                pass(toScratch ? scratchView : mainView, toScratch ? mainTarget : scratchTarget, radius * multiplier, false);
                toScratch = !toScratch;
            }
        } catch (RuntimeException exception) {
            unavailable = true;
            discard();
            LOGGER.error("[screen_vision] 模糊后处理执行失败，本次游戏内 blur 将不再生效", exception);
        }
    }

    /**
     * 一趟全屏盒式模糊：从 {@code source} 读、往 {@code destination} 写。
     *
     * @param horizontal {@code true} 表示横向（步长取 1/宽），{@code false} 表示纵向（1/高）
     */
    private static void pass(final @NotNull GpuTextureView source, final @NotNull RenderTarget destination,
                             final float radius, final boolean horizontal) {
        GpuBufferSlice config = writeConfig(destination.width, destination.height, radius, horizontal);

        if (destination.getColorTextureView() != null) {
            try (RenderPass renderPass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(() -> "Additional Abilities screen blur", destination.getColorTextureView(),
                            OptionalInt.empty())) {
                renderPass.setPipeline(PIPELINE);
                renderPass.setUniform("BlurConfig", config);
                renderPass.bindTexture("InSampler", source,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                // 全屏三角形：3 个顶点，无顶点缓冲（顶点由着色器里的 gl_VertexID 生成）
                renderPass.draw(0, 3);
            }
        }
    }

    /** 把方向步长与半径写进环形 UBO，返回可绑定的切片 */
    private static @NotNull GpuBufferSlice writeConfig(final int width, final int height,
                                                       final float radius, final boolean horizontal) {
        MappableRingBuffer buffer = uniformBuffer();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                    // Step 是「一个像素在 UV 空间里的位移」，方向由 BlurDir 决定；
                    // 把除法放在 CPU 侧，着色器里就只剩一次乘加。
                    .putVec2(horizontal ? 1.0F / Math.max(1, width) : 0.0F,
                            horizontal ? 0.0F : 1.0F / Math.max(1, height))
                    .putFloat(radius)
                    .get();

            GpuBuffer gpuBuffer = buffer.currentBuffer();

            try (GpuBuffer.MappedView view = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .mapBuffer(gpuBuffer.slice(), false, true)) {
                view.data().put(data);
            }

            GpuBufferSlice slice = gpuBuffer.slice();
            buffer.rotate();
            return slice;
        }
    }

    private static @NotNull MappableRingBuffer uniformBuffer() {
        if (uniformBuffer == null) {
            uniformBuffer = new MappableRingBuffer(() -> "Additional Abilities blur config",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM, UNIFORM_SIZE);
        }

        return uniformBuffer;
    }

    /** 中转渲染目标（懒分配 + 尺寸跟随主目标；窗口变化时销毁重建） */
    private static @NotNull RenderTarget scratchTarget(final @NotNull RenderTarget mainTarget) {
        if (scratch == null || scratch.width != mainTarget.width || scratch.height != mainTarget.height) {
            if (scratch != null) {
                scratch.destroyBuffers();
            }

            scratch = new TextureTarget("Additional Abilities blur scratch",
                    mainTarget.width, mainTarget.height, false);
        }

        return scratch;
    }

    /** 释放 GPU 资源（下次需要时按需重建） */
    private static void discard() {
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }

        if (scratch != null) {
            scratch.destroyBuffers();
            scratch = null;
        }
    }
}
