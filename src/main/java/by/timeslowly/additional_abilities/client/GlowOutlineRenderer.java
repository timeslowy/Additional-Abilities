package by.timeslowly.additional_abilities.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.neoforged.neoforge.client.GlStateBackup;
import org.jetbrains.annotations.NotNull;

/**
 * 方块发光的线框渲染器（<b>仅客户端加载</b>）。绘制一个立方体的 12 条棱。
 * <p>
 * <b>为什么不直接用 DS 的 {@code BlockVisionOutline}</b>：那个实现内部写死
 * {@code RenderSystem.disableDepthTest()}（穿墙 x-ray —— 那正是矿石视觉需要的效果），
 * 与本效果「被遮挡就不显示」的定位相反，且它没开混合，颜色的 alpha 会被忽略。
 * 线框绘制是通用几何代码，这里自己写一份、按需控制管线状态；DS 的着色器与 RenderType
 * 属于其资产，本模组只调用不复制。
 * <p>
 * <b>防 z-fighting 的做法</b>：把立方体整体<b>外扩</b> {@link #INFLATE} 格。不能用
 * {@code RenderSystem.polygonOffset} —— 那只作用于多边形填充（GL 的多边形偏移线模式
 * {@code POLYGON_OFFSET_LINE} 没有在 {@code RenderSystem} 上暴露），对 {@code DEBUG_LINES} 无效。
 * 外扩后棱线略在方块表面之外，深度上稳定胜过方块自身的面；而被更靠前的方块挡住时仍按正常遮挡关系隐藏。
 * <p>
 * <b>观感</b>：开启深度测试后，远侧的棱会被方块自身遮住，实际只看到朝向观察者那几面的棱
 * —— 正好是「这个方块自己在发光」的样子，而不是透视。
 */
public final class GlowOutlineRenderer {
    /**
     * 立方体四周的外扩量（格）。取值远小于一个像素在世界里的跨度，肉眼看不出放大，
     * 但足以让棱线在深度上压过方块自身的面。
     */
    private static final float INFLATE = 0.005F;

    private static BufferBuilder buffer;
    private static GlStateBackup backup;

    private GlowOutlineRenderer() {}

    public static void beginBatch() {
        backup = new GlStateBackup();
        RenderSystem.backupGlState(backup);
        prepare();
        buffer = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
    }

    /** 画一个以 {@code (0,0,0)~(1,1,1)} 为单位立方体的线框，调用方需先把 pose 平移到方块坐标 */
    public static void render(final @NotNull PoseStack pose, final int colorARGB) {
        // 每个方块之间可能有其它绘制改过全局状态，逐次重设最省心
        prepare();

        PoseStack.Pose last = pose.last();
        float min = -INFLATE;
        float max = 1.0F + INFLATE;

        // 底面四边
        line(last, min, min, min, max, min, min, colorARGB);
        line(last, max, min, min, max, min, max, colorARGB);
        line(last, max, min, max, min, min, max, colorARGB);
        line(last, min, min, max, min, min, min, colorARGB);

        // 顶面四边
        line(last, min, max, min, max, max, min, colorARGB);
        line(last, max, max, min, max, max, max, colorARGB);
        line(last, max, max, max, min, max, max, colorARGB);
        line(last, min, max, max, min, max, min, colorARGB);

        // 四条竖棱
        line(last, min, min, min, min, max, min, colorARGB);
        line(last, max, min, min, max, max, min, colorARGB);
        line(last, max, min, max, max, max, max, colorARGB);
        line(last, min, min, max, min, max, max, colorARGB);
    }

    public static void endBatch() {
        // 收批前同样重设：中间可能有其它渲染阶段插进来改过状态
        prepare();

        if (buffer != null) {
            MeshData meshData = buffer.build();

            if (meshData != null) {
                BufferUploader.drawWithShader(meshData);
            }
        }

        RenderSystem.restoreGlState(backup);

        backup = null;
        buffer = null;
    }

    private static void line(final PoseStack.Pose pose, final float fromX, final float fromY, final float fromZ,
                             final float toX, final float toY, final float toZ, final int color) {
        vertex(pose, fromX, fromY, fromZ, color);
        vertex(pose, toX, toY, toZ, color);
    }

    private static void vertex(final PoseStack.Pose pose, final float x, final float y, final float z, final int color) {
        // POSITION_COLOR 顶点格式只有位置与颜色：不要调 setNormal / setUv（格式不匹配）
        buffer.addVertex(pose, x, y, z).setColor(color);
    }

    /** 设置线框绘制所需的管线状态；两处调用（开批、收批）都依赖它，因此必须是幂等的 */
    private static void prepare() {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        // 与本模组的定位一致：不穿墙。DS 的 BlockVisionOutline 在这里是 disableDepthTest()
        RenderSystem.enableDepthTest();
        // 让 alpha 字段真正生效 —— 不开混合的话 alpha 通道会被直接忽略（DS 的线框路径就没开）
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 线宽只有 1px，写深度没有意义，反而可能把随后绘制的实体沿棱线削掉一条像素
        RenderSystem.depthMask(false);
    }
}
