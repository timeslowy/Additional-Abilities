package by.timeslowly.additional_abilities.client.renderer;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;

/**
 * 方块发光的线框渲染（<b>仅客户端加载</b>）。绘制一个立方体的 12 条棱。
 * <p>
 * <b>为什么不直接用 DS 的 {@code BlockVisionOutline}</b>：那个实现内部写死
 * {@code DepthStencilState(CompareOp.ALWAYS_PASS, false)}（穿墙 x-ray —— 那正是矿石视觉需要的效果），
 * 与本效果「被遮挡就不显示」的定位相反。线框绘制是通用几何代码，这里自己写一份、
 * 按需控制管线状态；DS 的着色器与 RenderType 属于其资产，本模组只调用不复制。
 *
 * <h2>26.1.2 的重写要点</h2>
 * 1.21.1 那版靠自己管 GL：{@code RenderSystem.backupGlState(GlStateBackup)} +
 * {@code setShader} / {@code enableDepthTest} / {@code depthMask} + 手工
 * {@code Tesselator} 打包提交。26.1.2 里<b>这一整套都没了</b>：
 * <ul>
 *     <li>{@code GlStateBackup} 从 NeoForge 移除；</li>
 *     <li>{@code RenderSystem} 只保留投影 / 雾 / 全局 uniform 之类，立即模式的
 *         深度、混合、着色器切换接口全部消失；</li>
 *     <li>取而代之的是 {@link RenderPipeline}：深度测试、深度写入、混合、剔除全部
 *         在管线对象上<b>声明</b>，由后端在提交时一次性应用，调用方不再需要（也无法）备份恢复。</li>
 * </ul>
 * 因此这里改为声明一条自己的管线 {@link #PIPELINE}，几何仍写进
 * {@code bufferSource.getBuffer(renderType())} 的统一缓冲。原先
 * {@code beginBatch} / {@code endBatch} 里的 GL 备份与恢复一并删除 —— 它们要做的事
 * 已经由管线的 {@link DepthStencilState} 表达。
 *
 * <h2>防 z-fighting 的做法</h2>
 * 把立方体整体<b>外扩</b> {@link #INFLATE} 格。不能用多边形偏移：管线上的
 * {@code depthBiasScaleFactor} / {@code depthBiasConstant} 只作用于多边形填充，
 * 对线图元没有效果。外扩后棱线略在方块表面之外，深度上稳定胜过方块自身的面；
 * 而被更靠前的方块挡住时仍按正常遮挡关系隐藏。
 * <p>
 * <b>观感</b>：开启深度测试后，远侧的棱会被方块自身遮住，实际只看到朝向观察者那几面的棱
 * —— 正好是「这个方块自己在发光」的样子，而不是透视。
 */
public final class GlowOutlineRenderer {
    /** 立方体四周的外扩量（格）。取值远小于一个像素在世界里的跨度，肉眼看不出放大，
     *  但足以让棱线在深度上压过方块自身的面。 */
    private static final float INFLATE = 0.005F;

    /** 线宽（像素）：1.0 即原版调试线宽度 */
    private static final float LINE_WIDTH = 1.0F;

    private static final Identifier PIPELINE_ID =
            Identifier.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, "pipeline/block_glow_outline");

    /**
     * 线框管线。
     * <p>
     * 基于原版 {@link RenderPipelines#LINES_SNIPPET}（{@code core/rendertype_lines}，
     * 顶点格式 {@code POSITION_COLOR_NORMAL_LINE_WIDTH}、透明混合、不剔除背面），
     * 只把深度写入关掉：线宽只有 1px，写深度没有意义，反而可能把随后绘制的实体
     * 沿棱线削掉一条像素。
     * <p>
     * 深度<b>测试</b>保留（{@code LESS_THAN_OR_EQUAL}）：这正是「被遮挡就不显示」的来源，
     * 与 DS 那个 {@code ALWAYS_PASS} 的穿墙版本相反。
     */
    private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(PIPELINE_ID)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .build();

    /**
     * 对应的 RenderType。
     * <p>
     * {@code VIEW_OFFSET_Z_LAYERING} 与原版 {@code RenderTypes.lines()} 一致
     * （按视图方向给深度加一点偏移，进一步压住 z-fighting）。
     * 输出目标用 {@link OutputTarget#MAIN_TARGET}：方块发光是就地叠加在世界上的，
     * 不该像实体描边那样被分到单独的 target。
     */
    private static final RenderType RENDER_TYPE = RenderType.create(
            "additional_abilities_block_glow_outline",
            RenderSetup.builder(PIPELINE)
                    .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .setOutputTarget(OutputTarget.MAIN_TARGET)
                    .createRenderSetup());

    private GlowOutlineRenderer() {}

    /** 供 {@code RegisterRenderPipelinesEvent} 注册用 */
    public static @NotNull RenderPipeline pipeline() {
        return PIPELINE;
    }

    public static @NotNull RenderType renderType() {
        return RENDER_TYPE;
    }

    /**
     * 画一个以 {@code (0,0,0)~(1,1,1)} 为单位立方体的线框，调用方需先把 pose 平移到方块坐标。
     */
    public static void render(final @NotNull PoseStack pose, final @NotNull VertexConsumer buffer, final int colorARGB) {
        PoseStack.Pose last = pose.last();
        float min = -INFLATE;
        float max = 1.0F + INFLATE;

        // 底面四边
        line(last, buffer, min, min, min, max, min, min, colorARGB);
        line(last, buffer, max, min, min, max, min, max, colorARGB);
        line(last, buffer, max, min, max, min, min, max, colorARGB);
        line(last, buffer, min, min, max, min, min, min, colorARGB);

        // 顶面四边
        line(last, buffer, min, max, min, max, max, min, colorARGB);
        line(last, buffer, max, max, min, max, max, max, colorARGB);
        line(last, buffer, max, max, max, min, max, max, colorARGB);
        line(last, buffer, min, max, max, min, max, min, colorARGB);

        // 四条竖棱
        line(last, buffer, min, min, min, min, max, min, colorARGB);
        line(last, buffer, max, min, min, max, max, min, colorARGB);
        line(last, buffer, max, min, max, max, max, max, colorARGB);
        line(last, buffer, min, min, max, min, max, max, colorARGB);
    }

    private static void line(final PoseStack.Pose pose, final VertexConsumer buffer,
                             final float fromX, final float fromY, final float fromZ,
                             final float toX, final float toY, final float toZ, final int color) {
        // 法线取该棱的方向（对线段渲染没有实际影响，但 POSITION_COLOR_NORMAL_LINE_WIDTH
        // 这个顶点格式要求必须提供，否则顶点步长对不上）
        float nx = toX - fromX;
        float ny = toY - fromY;
        float nz = toZ - fromZ;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);

        if (length > 1.0E-6F) {
            nx /= length;
            ny /= length;
            nz /= length;
        } else {
            nx = 0.0F;
            ny = 1.0F;
            nz = 0.0F;
        }

        vertex(pose, buffer, fromX, fromY, fromZ, color, nx, ny, nz);
        vertex(pose, buffer, toX, toY, toZ, color, nx, ny, nz);
    }

    private static void vertex(final PoseStack.Pose pose, final VertexConsumer buffer,
                               final float x, final float y, final float z, final int color,
                               final float nx, final float ny, final float nz) {
        buffer.addVertex(pose, x, y, z)
                .setColor(color)
                .setNormal(pose, nx, ny, nz)
                .setLineWidth(LINE_WIDTH);
    }
}
