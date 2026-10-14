package by.timeslowly.additional_abilities.client;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.NotNull;

/**
 * 「当前线程是不是客户端的游戏线程」—— 客户端专属的探针（<b>仅客户端加载</b>）。
 *
 * <h2>为什么需要单独一个类</h2>
 * 判定必须问 {@link Minecraft#isSameThread()}，而 {@code Minecraft} 在专用服务端上<b>根本不存在</b>。
 * 因此这个引用被关在一个客户端专属类里，由调用方先做物理端判定再进来
 * （与 {@code AAClientSetup} 对 GUI 图层 / 后处理链同一处理方式）。
 *
 * <h2>为什么不能用 {@code FMLEnvironment.dist} 或 {@code EffectiveSide}</h2>
 * 前者是<b>物理端</b>：单人游戏里物理客户端同时跑着逻辑服务端，按物理端一刀切会取错候选集。
 * 后者（{@code net.neoforged.fml.util.thread.EffectiveSide}）看的是线程组是不是
 * {@code SidedThreadGroup}，而 NeoForge 21.1.250 里 {@code SidedThreadGroups.CLIENT}
 * <b>从未被用来创建任何线程</b>（客户端的游戏线程是 {@code Main} 里裸 {@code new Thread} 后
 * 改名为 "Render thread"），因此它只会走 fallback 一律返回 CLIENT ——
 * 连 {@code Worker-Main-*} 后台线程也会被算成客户端，正是要避免的那个误判。
 *
 * <h2>为什么是"查"而不是"记"</h2>
 * {@code Minecraft} 的构造器里就 {@code this.gameThread = Thread.currentThread()}，
 * 而 {@code getInstance()} 在构造期即已可用，因此本探针无需任何生命周期接线，
 * 也不存在"第一次调用时还没登记"的窗口。
 */
public final class ClientGameThreadProbe {
    private ClientGameThreadProbe() {
        // 探针
    }

    /**
     * 当前线程是否是客户端的游戏线程（= 渲染线程，客户端的主线程）。
     * <p>
     * {@code Minecraft} 实例尚未构造时为 {@code false} —— 那一刻没有任何世界对象可读，
     * 归入"不受支持"是正确的。
     */
    public static boolean isOnGameThread() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.isSameThread();
    }

    /** 供日志与断言使用的线程名；仅用于诊断，不参与判定 */
    public static @NotNull String currentThreadName() {
        return Thread.currentThread().getName();
    }
}
