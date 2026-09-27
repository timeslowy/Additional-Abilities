package by.timeslowly.additional_abilities.client.state;

import by.timeslowly.additional_abilities.common.vision.ScreenVisionType;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端屏幕视觉状态（<b>仅客户端加载</b>）。
 * <p>
 * <b>按视觉类型分槽</b>：每种 {@link ScreenVisionType} 各持一份"参数组 / 剩余刻 / 时钟"，
 * 因此不同视觉可以同时生效（例如一次施法里 shake 与 blur 并存），互不覆盖。
 * 槽内合并规则仍是<b>刷新取较大值</b>——同一类型的重复下发既不会把效果无限延长，
 * 也不会无限加强（被动技能高频刷新因此是安全的）。
 * <p>
 * 抖动相位由各槽自己的 {@code clock}（本地刻）+ partialTick 驱动，做成确定性多频正弦叠加：
 * 相比每帧取随机数，它不会随帧率变化，高帧率下也不会退化成"高频闪烁"。
 * 模糊与边光的强度则统一走 {@code fadeIn()} / {@code fadeOut()} 的缓动。
 */
public final class ClientScreenVisionState {
    /** amplifier = 1.0 时的最大镜头偏移（度）。0 = 不震 */
    private static final float MAX_OFFSET_DEGREES = 1.5F;

    /**
     * 模糊强度换算：单个 amplifier 对应的模糊半径（像素）。
     * 取 1.0 即「amplifier 数值 = 半径像素数」；手感不合适时只改这一处常量。
     */
    private static final float BLUR_RADIUS_PER_AMPLIFIER = 1.0F;

    /**
     * 模糊半径上限（像素）：再高已近乎全糊。
     * 取 20 是为了配合技能 JSON 里 amplifier 随等级成长到 20 的写法；
     * 单 pass 采样数约为 半径/2 + 2，6 个 pass 满档也只有 ~72 次采样，开销可忽略。
     */
    private static final float MAX_BLUR_RADIUS = 20.0F;

    /**
     * 边光的不透明度上限。
     * <p>
     * {@code amplifier} 是三类视觉共用的字段，服务端<b>不能</b>为边光把它钳到 1
     * （{@code blur} 的合法强度高达 20），因此"不透明度"这条语义只在消费端成立 ——
     * 这里就是那个消费端。
     */
    private static final float MAX_EDGE_LIGHT_OPACITY = 1.0F;

    /** 收尾淡出时长（刻）：剩余时间不足此值时按比例衰减，避免效果"硬切" */
    private static final int FADE_OUT_TICKS = 10;

    /** 起始淡入时长（刻）：让效果平滑出现 */
    private static final int FADE_IN_TICKS = 5;

    /** 三档互不成整数倍的正弦频率（rad/s），叠加后近似不规则抖动 */
    private static final double FREQ_A = 38.0;
    private static final double FREQ_B = 61.0;
    private static final double FREQ_C = 97.0;

    /** 各分量权重，合计为 1.0，使合成波形落在 [-1, 1] */
    private static final double WEIGHT_A = 0.6;
    private static final double WEIGHT_B = 0.3;
    private static final double WEIGHT_C = 0.1;

    /** 每个视觉选项一份独立状态（下标 = {@link ScreenVisionType#ordinal()}） */
    private static final Slot[] SLOTS = createSlots();

    private ClientScreenVisionState() {}

    /**
     * 一帧边光遮罩的完整参数。
     *
     * @param size  边缘厚度，占屏幕<b>短边</b>的比例（服务端已钳到 0~0.5）
     * @param alpha 当前帧的不透明度（0~1，已含淡入淡出与 {@link #MAX_EDGE_LIGHT_OPACITY} 钳制）
     * @param rgb   遮罩颜色（{@code 0xRRGGBB}）
     */
    public record EdgeLight(float size, float alpha, int rgb) {}

    public static void onReceive(final @NotNull ScreenVisionType visionType, final float amplifier,
                                 final float size, final int rgb, final int durationTicks) {
        if (durationTicks <= 0 || amplifier <= 0.0F) {
            return;
        }

        slot(visionType).onReceive(amplifier, size, rgb, durationTicks);
    }

    /** 每客户端 tick 递减各槽的剩余时长；归零后清空该槽 */
    public static void tick() {
        for (Slot slot : SLOTS) {
            slot.tick();
        }
    }

    /**
     * 当前帧应叠加到镜头 roll 上的偏移（度）。
     *
     * @param partialTick 部分刻，保证相位与帧率无关
     */
    public static float rollOffset(final double partialTick) {
        Slot slot = slot(ScreenVisionType.SHAKE);

        if (!slot.active()) {
            return 0.0F;
        }

        double seconds = (slot.clock + partialTick) / 20.0;
        double wave = WEIGHT_A * Math.sin(seconds * FREQ_A)
                + WEIGHT_B * Math.sin(seconds * FREQ_B + 1.7)
                + WEIGHT_C * Math.sin(seconds * FREQ_C + 4.1);

        return (float) (MAX_OFFSET_DEGREES * slot.amplitude * slot.fadeOut() * wave);
    }

    /**
     * 当前帧应施加的模糊半径（像素）。
     * <p>
     * 返回 {@code 0} 表示此刻不需要模糊，调用方应直接跳过整条后处理链。
     */
    public static float blurRadius() {
        Slot slot = slot(ScreenVisionType.BLUR);

        if (!slot.active()) {
            return 0.0F;
        }

        float radius = slot.amplitude * BLUR_RADIUS_PER_AMPLIFIER * slot.fadeIn() * slot.fadeOut();
        return Mth.clamp(radius, 0.0F, MAX_BLUR_RADIUS);
    }

    /**
     * 当前帧应绘制的边光遮罩参数；此刻不该画时返回 {@code null}。
     * <p>
     * 把"不该画"的三种情形（未激活 / 不透明度为 0 / 边缘厚度为 0）在这里一次性判掉，
     * 渲染器就只剩"把比例换算成像素"这一件事 —— 与 {@code blurRadius()} 的早退口径一致。
     */
    public static @Nullable EdgeLight edgeLight() {
        Slot slot = slot(ScreenVisionType.EDGE_LIGHT);

        if (!slot.active() || slot.size <= 0.0F) {
            return null;
        }

        float alpha = Mth.clamp(slot.amplitude * slot.fadeIn() * slot.fadeOut(), 0.0F, MAX_EDGE_LIGHT_OPACITY);

        if (alpha <= 0.0F) {
            return null;
        }

        return new EdgeLight(slot.size, alpha, slot.rgb);
    }

    /** 清空全部视觉（断线 / 退出存档时用） */
    public static void clear() {
        for (Slot slot : SLOTS) {
            slot.clear();
        }
    }

    private static @NotNull Slot slot(final @NotNull ScreenVisionType type) {
        return SLOTS[type.ordinal()];
    }

    private static @NotNull Slot @NotNull [] createSlots() {
        ScreenVisionType[] types = ScreenVisionType.values();
        Slot[] slots = new Slot[types.length];

        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
        }

        return slots;
    }

    /** 单个视觉类型的状态 */
    private static final class Slot {
        /** 当前强度（= 下发的 amplifier 原始值，由各类型自行换算；对 edge_light 即不透明度） */
        private float amplitude = 0.0F;
        /** 遮罩边缘厚度（占屏幕短边比例）；仅 edge_light 使用 */
        private float size = 0.0F;
        /** 遮罩颜色（0xRRGGBB）；仅 edge_light 使用 */
        private int rgb = 0;
        /** 剩余时长（刻） */
        private int remainingTicks = 0;
        /** 效果开始以来的本地刻数，用于计算抖动相位与淡入进度 */
        private double clock = 0.0;

        private void onReceive(final float amplifier, final float size, final int rgb, final int durationTicks) {
            if (remainingTicks <= 0) {
                // 新的触发：相位归零、强度从零开始，让画面平滑淡入
                clock = 0.0;
                this.amplitude = 0.0F;
                this.size = 0.0F;
                this.rgb = rgb;
            }

            remainingTicks = Math.max(remainingTicks, durationTicks);

            // 参数组<b>整组</b>替换：强度更高的那一组覆盖较弱的一组（颜色与尺寸跟着一起走），
            // 避免出现「强度取新的、颜色还是旧的」这种撕裂组合。
            // 等号取"替换"：同强度下以最后到达的为准，换色 / 改尺寸才能即时可见。
            if (amplifier >= this.amplitude) {
                this.amplitude = amplifier;
                this.size = size;
                this.rgb = rgb;
            }
        }

        private void tick() {
            clock++;

            if (remainingTicks > 0 && --remainingTicks == 0) {
                amplitude = 0.0F;
            }
        }

        private boolean active() {
            return remainingTicks > 0;
        }

        /** 起始淡入系数（0~1）：效果出现时不"啪"地一下 */
        private float fadeIn() {
            return Math.min(1.0F, (float) clock / FADE_IN_TICKS);
        }

        /** 收尾淡出系数（0~1） */
        private float fadeOut() {
            return Math.min(1.0F, remainingTicks / (float) FADE_OUT_TICKS);
        }

        private void clear() {
            amplitude = 0.0F;
            size = 0.0F;
            rgb = 0;
            remainingTicks = 0;
            clock = 0.0;
        }
    }
}
