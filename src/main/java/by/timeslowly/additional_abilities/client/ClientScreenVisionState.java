package by.timeslowly.additional_abilities.client;

import by.timeslowly.additional_abilities.common.vision.ScreenVisionType;
import org.jetbrains.annotations.NotNull;

/**
 * 客户端屏幕视觉状态（<b>仅客户端加载</b>）。
 * <p>
 * 这是全屏效果，同一时刻只需保留一份状态，因此不按技能 ID 分槽。
 * <p>
 * <b>合并规则（刷新取较大值）</b>：收到新包时剩余刻取 max、强度取 max，
 * 因此被动技能高频刷新既不会把效果无限延长，也不会无限加强；
 * 类型字段按"后到者覆盖"处理——目前只有 {@link ScreenVisionType#SCREEN_SHAKE} 一个选项，
 * 将来新增选项时若需要多类型并存，此处需要补一条优先级规则。
 * <p>
 * 抖动相位由 {@link #clock}（本地刻）+ partialTick 驱动，做成确定性多频正弦叠加：
 * 相比每帧取随机数，它不会随帧率变化，高帧率下也不会退化成"高频闪烁"。
 */
public final class ClientScreenVisionState {
    /** amplifier = 1.0 时的最大镜头偏移（度）。0 = 不震 */
    private static final float MAX_OFFSET_DEGREES = 1.5F;

    /** 收尾淡出时长（刻）：剩余时间不足此值时按比例衰减，避免抖动"硬切" */
    private static final int FADE_OUT_TICKS = 10;

    /** 三档互不成整数倍的正弦频率（rad/s），叠加后近似不规则抖动 */
    private static final double FREQ_A = 38.0;
    private static final double FREQ_B = 61.0;
    private static final double FREQ_C = 97.0;

    /** 各分量权重，合计为 1.0，使合成波形落在 [-1, 1] */
    private static final double WEIGHT_A = 0.6;
    private static final double WEIGHT_B = 0.3;
    private static final double WEIGHT_C = 0.1;

    /** 当前生效的视觉选项 */
    private static ScreenVisionType type = null;
    /** 当前峰值偏移（度，已含 amplifier 倍率） */
    private static float amplitudeDegrees = 0.0F;
    /** 剩余时长（刻） */
    private static int remainingTicks = 0;
    /** 效果开始以来的本地刻数，用于计算抖动相位 */
    private static double clock = 0.0;

    private ClientScreenVisionState() {}

    public static void onReceive(final @NotNull ScreenVisionType visionType, final float amplifier, final int durationTicks) {
        if (durationTicks <= 0 || amplifier <= 0.0F) {
            return;
        }

        // 之前没有活动效果 → 这是一次全新的抖动，相位归零让镜头从中位开始
        if (remainingTicks <= 0) {
            clock = 0.0;
        }

        type = visionType;
        remainingTicks = Math.max(remainingTicks, durationTicks);
        amplitudeDegrees = Math.max(amplitudeDegrees, MAX_OFFSET_DEGREES * amplifier);
    }

    /** 每客户端 tick 递减剩余时长；归零后清空状态 */
    public static void tick() {
        clock++;

        if (remainingTicks > 0) {
            remainingTicks--;

            if (remainingTicks == 0) {
                clear();
            }
        }
    }

    /**
     * 当前帧应叠加到镜头 roll 上的偏移（度）。
     *
     * @param partialTick 部分刻，保证相位与帧率无关
     */
    public static float rollOffset(final double partialTick) {
        if (type != ScreenVisionType.SCREEN_SHAKE || remainingTicks <= 0) {
            return 0.0F;
        }

        double seconds = (clock + partialTick) / 20.0;
        double wave = WEIGHT_A * Math.sin(seconds * FREQ_A)
                + WEIGHT_B * Math.sin(seconds * FREQ_B + 1.7)
                + WEIGHT_C * Math.sin(seconds * FREQ_C + 4.1);

        float envelope = Math.min(1.0F, remainingTicks / (float) FADE_OUT_TICKS);
        return (float) (amplitudeDegrees * envelope * wave);
    }

    public static void clear() {
        type = null;
        amplitudeDegrees = 0.0F;
        remainingTicks = 0;
    }
}
