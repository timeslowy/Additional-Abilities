package by.timeslowly.additional_abilities.common.ability.entity_effects.enchantment_bonus;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;

/**
 * 「这次附魔查询发生在哪条线程上」—— {@code enchantment_bonus} 消费端的<b>线程契约</b>。
 *
 * <h2>要解决的问题</h2>
 * 本效果的结算入口是 NeoForge 的 {@code GetEnchantmentLevelEvent}，而该事件由
 * {@code EventHooks.getEnchantmentLevelSpecific} / {@code getAllEnchantmentLevels}
 * 在<b>调用点直接 post</b>：它不做任何线程切换，也不保证发生在主线程上。
 * 于是"当前线程属于哪一侧"必须由消费端自己判定 —— 判错就会拿错候选集，
 * 甚至在工作线程上遍历实体表与附件。
 *
 * <h2>为什么是三态，而不是"是不是服务端"</h2>
 * 旧判据是 {@code server == null || !server.isSameThread()} → 客户端。
 * 那是个<b>二值</b>判定，等于把"不是服务端主线程"一律当成客户端线程。
 * 而原版自己就会在工作线程上触发本事件：{@code SessionSearchTrees} 用
 * {@code CompletableFuture.supplyAsync(..., Util.backgroundExecutor())} 重建创造模式搜索索引，
 * 而 {@code SearchTree.plainText} 会<b>立即</b>求值传入的 tooltip 函数
 * （它要把所有字符串取出来建后缀数组），于是
 * {@code ItemStack#getTooltipLines} → {@code EventHooks.onItemTooltip} 就跑在
 * {@code Worker-Main-N} 上。触发时机包括：打开 / 重建创造模式标签页、收到配方与标签包、
 * 切换语言。二值判定会把这条线程算作"客户端"，然后在它上面迭代客户端候选集，
 * 而客户端游戏线程正同时在 {@code EnchantmentBonuses#tickData} 里增删同一个
 * {@code IdentityHashMap} 支撑的集合。
 * <p>
 * 因此判据改为三态：明确的服务器主线程、明确的客户端游戏线程，其余一律<b>不受支持</b>。
 * 不受支持时调用方直接返回"无加成"，既不碰候选集，也不碰实体与附件。
 *
 * <h2>为什么不受支持时"静默无加成"，而不是排队回主线程</h2>
 * 该事件要求<b>当场返回</b>结果（{@code getEnchantmentLevelSpecific} 直接把事件里改过的值当返回值用），
 * 所以 {@code enqueueWork} 一类延迟执行无法保留查询语义 —— 轮到时调用方早已取走旧值。
 * 代价是创造模式搜索索引里不会收录本模组的<b>虚拟</b>附魔名（物品自身的真附魔名照常入索引），
 * 属可接受的降级：搜索索引只用于文本检索，不参与任何结算。
 *
 * <h2>为什么判定与取值分开</h2>
 * {@link #classify} 是<b>不碰任何世界对象</b>的纯函数，因此可以在普通单元测试里穷举
 * （本机无外网、无 Mockito，造不出 {@code Minecraft} 实例）。
 * {@link #current()} 只负责采集事实并交给它，是薄壳。
 */
public final class EnchantmentBonusThreads {
    /**
     * 本次查询所在线程的归属。
     * <ul>
     *     <li>{@link #SERVER} —— 该存档服务器自己的主线程（单人集成服务端亦走这条）；</li>
     *     <li>{@link #CLIENT} —— 客户端的游戏线程（即渲染线程，客户端主线程）；</li>
     *     <li>{@link #UNSUPPORTED} —— 其余全部线程，含原版创造模式搜索索引用的
     *         {@code Worker-Main-*} 与任何第三方工作线程。</li>
     * </ul>
     */
    public enum Support {
        SERVER,
        CLIENT,
        UNSUPPORTED
    }

    private EnchantmentBonusThreads() {
        // 判定
    }

    /**
     * 纯判定：由三个布尔事实推出归属，<b>不读世界对象、无副作用</b>。
     *
     * @param onServerThread     当前线程是否是存档服务器的主线程（{@code MinecraftServer#isSameThread}）
     * @param physicalClient     当前进程是否是物理客户端（{@code FMLEnvironment.getDist().isClient()}）
     * @param onClientGameThread 当前线程是否是客户端的游戏线程
     *                           （仅在 {@code physicalClient} 为真时有意义）
     * @return 归属；{@code physicalClient} 为假时<b>绝不</b>返回 {@link Support#CLIENT}
     */
    public static @NotNull Support classify(final boolean onServerThread,
                                            final boolean physicalClient,
                                            final boolean onClientGameThread) {
        // 顺序有意义：单人游戏里集成服务端跑在自己的 "Server thread" 上，
        // 与客户端游戏线程是两个线程，因此"是服务端主线程"先判且足以定论
        if (onServerThread) {
            return Support.SERVER;
        }

        if (physicalClient && onClientGameThread) {
            return Support.CLIENT;
        }

        return Support.UNSUPPORTED;
    }

    /**
     * 采集当前线程的三个事实并判定。
     * <p>
     * 客户端探针<b>只在物理客户端的假分支里求值</b>：{@code Minecraft} 在专用服务端上并不存在，
     * 而 JVM 对符号引用是惰性解析的，因此那个短路足以保证专用服务端不会去加载客户端的类
     * （与 {@code AAClientSetup} 对 GUI 图层 / 后处理链同一处理方式）。
     */
    public static @NotNull Support current() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        // 纯客户端没有服务端（getCurrentServer 为 null），那正是"不存在服务端主线程"这一事实
        boolean onServerThread = server != null && server.isSameThread();
        boolean physicalClient = FMLEnvironment.getDist().isClient();
        boolean onClientGameThread = !onServerThread && physicalClient
                && by.timeslowly.additional_abilities.client.ClientGameThreadProbe.isOnGameThread();

        return classify(onServerThread, physicalClient, onClientGameThread);
    }
}
