package by.timeslowly.additional_abilities.common.vision;

/**
 * 屏幕视觉的<b>跨端计时契约</b>：把「服务端多久刷一次」与「客户端能撑多久」放在同一处，
 * 使两者无法各自漂移。
 *
 * <h2>为什么需要这个类</h2>
 * 下发节流原本只写着「5 刻内且参数没变就跳过」，却完全没有记录<b>客户端手里还剩多少时间</b>。
 * 于是「先发 1 刻、再在同一窗口内发 100 刻」时后一次被当成重复而丢弃，
 * 客户端只拿到 1 刻 —— 即「短时长覆盖长时长」。
 * <p>
 * 修法是让服务端记住自己承诺过的覆盖范围（{@code coveredUntil}）。但只记住还不够：
 * 「余量到什么程度必须刷新」这个阈值<b>取决于客户端的淡出时长</b> ——
 * 若客户端余量已经掉进淡出区，刷出来的效果会先变暗再被拉回满值，表现为周期性脉动。
 * 那个淡出常量原本是 {@code ClientScreenVisionState} 的私有实现细节，服务端无从得知，
 * 正是本节要消除的<b>隐式耦合</b>，因此把它提到这里作为共享契约。
 *
 * <h2>为什么不把淡入也放进来</h2>
 * 淡入只影响「新触发那一瞬间」的观感，不构成任何跨端承诺 —— 服务端不需要知道它。
 * 只把<b>真正被两端共同依赖</b>的常量放进来，契约的表面才够小、才不容易被误改。
 */
public final class ScreenVisionTiming {
    /**
     * 收尾淡出时长（刻）：客户端剩余时间不足此值时按比例衰减（{@code ClientScreenVisionState}）。
     * <p>
     * 对服务端的含义是：<b>客户端余量低于这个值时画面已经开始变暗</b>，
     * 所以任何"提前刷新"都必须发生在它之上，否则会刷出一次可见的明暗脉动。
     */
    public static final int FADE_OUT_TICKS = 10;

    /** 同一玩家同一视觉类型两次发包的最小间隔（刻），由 {@code ScreenVisionSender} 使用 */
    public static final int THROTTLE_TICKS = 5;

    /**
     * 刷新安全余量（刻）：服务端算出的客户端余量低于此值就必须重发，不能等到下一个节流窗口。
     * <p>
     * <b>为什么是 {@code FADE_OUT_TICKS + THROTTLE_TICKS} 而不是单独取一个数</b>：
     * 判据要回答的是「现在跳过，客户端能不能撑到<b>下一次允许发包</b>那一刻仍不变暗」。
     * 下一次允许发包最早也要等满一个节流窗口，所以最坏情况下需要撑过
     * {@code THROTTLE_TICKS} 刻的等待，再加上必须留住的 {@code FADE_OUT_TICKS} 刻满强度余量。
     * <p>
     * 两者相加是<b>保守</b>的：服务端时钟与客户端倒计时之间有网络延迟 δ，
     * 实际余量为 {@code coveredUntil - now + δ}，因此真实余量只会比这里估计的更多，不会更少。
     */
    public static final int COVERAGE_SAFETY_TICKS = FADE_OUT_TICKS + THROTTLE_TICKS;

    private ScreenVisionTiming() {
        // 常量持有类
    }
}
