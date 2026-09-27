package by.timeslowly.additional_abilities.client.renderer;

import by.timeslowly.additional_abilities.client.state.ClientScreenVisionState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

/**
 * 「边光」遮罩的渲染（<b>仅客户端加载</b>）：画面边缘覆盖一层由边向内渐隐的矩形遮罩。
 * <p>
 * 施加点是 {@code RenderGuiLayerEvent.Post} + {@code VanillaGuiLayers.CAMERA_OVERLAYS}
 * （见 {@code client.eventhandler.ScreenVisionEventHandler}）。选这个落点是因为该图层正是原版
 * 晕影 / 水 / 火 / 望远镜覆盖层所在之处，<b>排在准星与快捷栏之前</b> —— 遮罩因此天然垫在 HUD 之下，
 * 与"画面边缘"的语义吻合，也不会盖住血条与物品栏。
 * <p>
 * <h2>为什么是「同心矩形环」而不是四边各画一条渐变</h2>
 * 每条边单独画（先上下、后左右之类）时，<b>四个角上会互相覆盖</b>：
 * 后画的那条边会把先画那条边的纵向渐隐整片盖掉，角上出现一道看得见的接缝。
 * 这里改成"回"字形的同心环：第 i 环取外框 {@code inset(i·t)} 与内框 {@code inset((i+1)·t)} 之间，
 * 用 4 次 {@code fill} 画成（上 / 下横跨外框全宽，左 / 右只跨内框高度）。
 * <ul>
 *     <li>四块<b>互不重叠</b> ⇒ 每个像素恰好被画一次，alpha <b>不会叠加</b>；</li>
 *     <li>像素落在第几环 = ⌊到最近边的距离 ÷ t⌋ ⇒ 遮罩强度 = f(<b>到最近边的距离</b>)，
 *         角上自动取最深，无需任何特判，也没有接缝。</li>
 * </ul>
 * 环数按"每环约 {@value #TARGET_BAND_PIXELS} 像素"自适应、上限 {@value #MAX_RINGS} 环
 * （最坏 96×4 = 384 个 quad/帧，相对全屏 GUI 绘制可忽略），保证渐隐足够平滑、看不出色阶。
 * <p>
 * <b>与 {@code blur} 的关系</b>：模糊走 {@code RenderGuiEvent.Pre} 的后处理链，本遮罩在更晚的
 * GUI 图层里绘制，因此遮罩<b>不会被糊掉</b>，两种视觉可以同时生效、互不干扰。
 * <p>
 * 与 WK 的定身遮罩（{@code DingshenEffectEventHandler#onRenderDingShenVignette}）的差异：
 * 那边是固定颜色 / 固定厚度的金色遮罩，且四边分开绘制（角上有上述接缝）；
 * 这边把厚度、不透明度、颜色都做成技能参数，并换成无接缝的同心环画法。
 */
// FIXME：会破坏Jade玉的信息显示
public final class EdgeLightRenderer {
    /** 单个环的目标像素厚度：越小越平滑，代价是 quad 数线性增长 */
    private static final int TARGET_BAND_PIXELS = 4;

    /** 环数下限：屏幕很小时也要有足够的层次，否则边缘会糊成一条硬边 */
    private static final int MIN_RINGS = 8;

    /** 环数上限：96 环已有约 2.6% 的 alpha 步进，再细已看不出差别 */
    private static final int MAX_RINGS = 96;

    private EdgeLightRenderer() {}

    /**
     * 按当前边光强度渲染一帧遮罩。
     *
     * @param graphics 当前 GUI 绘制上下文（图层事件给的实例）
     */
    public static void render(final @NotNull GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();

        // 世界里才有"画面"可遮；F1 隐藏 HUD 时也不该画。
        // ⚠️ hideGui 必须自检：GuiLayerManager 把 !hideGui 包在**图层体内部**，
        // 而 RenderGuiLayerEvent.Post 是在包装**之外**发出的 —— 按 F1 时事件照常触发、只是图层体不画。
        if (minecraft.level == null || minecraft.options.hideGui) {
            return;
        }

        ClientScreenVisionState.EdgeLight mask = ClientScreenVisionState.edgeLight();
        if (mask == null) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int shortSide = Math.min(width, height);

        // size 是"占短边的比例"，这里用短边换算成像素 ⇒ 四条边的厚度在视觉上一致。
        // 上限取短边的一半：再大就没有"中心区域"了。
        int edge = Mth.clamp(Math.round(mask.size() * shortSide), 0, shortSide / 2);
        int maxAlpha = Math.round(mask.alpha() * 255.0F);

        if (edge <= 0 || maxAlpha <= 0) {
            return;
        }

        int rings = Mth.clamp(edge / TARGET_BAND_PIXELS, MIN_RINGS, MAX_RINGS);
        int lastIndex = Math.max(1, rings - 1);

        for (int index = 0; index < rings; index++) {
            // 用 round 逐环推进而不是整数除法累加，保证相邻环的边界严丝合缝（既无 1 像素缝隙也不重叠）
            int outer = Math.round((float) edge * index / rings);
            int inner = Math.round((float) edge * (index + 1) / rings);

            if (inner <= outer) {
                // 整数取整后这一环退化成零厚度，跳过（不影响相邻环的衔接）
                continue;
            }

            // 最外环满不透明度，向内线性衰减到 0（最内环因此会被上面的 alpha 判断跳过）
            int alpha = Math.round(maxAlpha * (1.0F - (float) index / lastIndex));

            if (alpha <= 0) {
                continue;
            }

            int color = (alpha << 24) | mask.rgb();

            graphics.fill(outer, outer, width - outer, inner, color);
            graphics.fill(outer, height - inner, width - outer, height - outer, color);
            graphics.fill(outer, inner, inner, height - inner, color);
            graphics.fill(width - inner, inner, width - outer, height - inner, color);
        }
    }
}
