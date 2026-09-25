package by.timeslowly.additional_abilities.client.state;

import by.dragonsurvivalteam.dragonsurvival.client.render.BlockVisionHandler;
import by.dragonsurvivalteam.dragonsurvival.common.codecs.block_vision.BlockVision;
import by.timeslowly.additional_abilities.common.ability.block_effects.GlowDisplayType;
import by.timeslowly.additional_abilities.common.network.BlockGlowPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 方块发光的客户端状态（<b>仅客户端加载</b>）。
 * <p>
 * 条目键与服务端一致：<b>位置 + 颜色 + 显示类型</b>。服务端每
 * {@code BlockGlows.REFRESH_INTERVAL_TICKS} 刻重发一次存活条目，收到即取<b>较晚</b>的剩余时刻
 * —— 这就是"续命"：引导类技能持续触发时发光常亮，技能停止后服务端不再刷新，客户端各自到期消失。
 * 因此两端都不需要移除包。
 * <p>
 * <b>生命周期</b>：剩余刻逐客户端刻递减，归零即移除。世界实例变化（进出存档、跨维度）时整表作废，
 * 靠比对 {@link ClientLevel} 实例统一清理 —— 这比逐事件挂钩更不容易漏。
 * <p>
 * <b>不做遮挡剔除</b>：被完全遮挡 / 形状不完整的方块已在服务端源头滤掉（见
 * {@code BlockGlows.register}），若两端各判一次等于把同一份计算成本乘以在线人数。
 * 客户端只做「收包后世界已变化」的兜底判定（见渲染层）。
 * <p>
 * 位置 → 包围盒在<b>收包时构造一次</b>：{@link AABB} 不可变，可以安全缓存并供逐帧视锥剔除复用，
 * 避免每帧为每个条目分配。
 */
public final class ClientBlockGlowState {
    /** 同时存活的条目上限，超出直接丢弃（防止大范围技能叠加把内存与渲染拖崩） */
    private static final int MAX_ENTRIES = 4096;

    /** 条目键：与服务端 {@code BlockGlows.GlowKey} 同构 */
    private record EntryKey(long packedPos, int colorARGB, GlowDisplayType displayType) {}

    /** 一条客户端发光记录（可变：剩余刻逐刻递减） */
    public static final class GlowEntry {
        private final BlockPos position;
        private final int colorARGB;
        private final GlowDisplayType displayType;
        private final AABB bounds;
        private int remainingTicks;

        /**
         * 着色器渲染载荷缓存。DS 的 {@code BlockVisionShaderSimple#render} 只接受
         * {@code BlockVisionHandler.Data}，因此必然要构造它；逐帧 new 一条 record 看着小，
         * 乘上帧率与同屏方块数就是可观的分配量 —— 位置固定不变，只有方块状态变了才需要重建。
         */
        private BlockVisionHandler.Data shaderData;
        private BlockState shaderDataState;

        private GlowEntry(final @NotNull BlockPos position, final int colorARGB,
                          final @NotNull GlowDisplayType displayType, final int remainingTicks) {
            this.position = position;
            this.colorARGB = colorARGB;
            this.displayType = displayType;
            this.bounds = new AABB(position);
            this.remainingTicks = remainingTicks;
        }

        public @NotNull BlockPos position() {
            return position;
        }

        public int colorARGB() {
            return colorARGB;
        }

        public @NotNull GlowDisplayType displayType() {
            return displayType;
        }

        public @NotNull AABB bounds() {
            return bounds;
        }

        /** 取（必要时重建）着色器渲染载荷。range / particleRate 对 {@code simple_shader} 分支无用，填中性值 */
        public @NotNull BlockVisionHandler.Data shaderData(final @NotNull BlockState state) {
            if (shaderData == null || shaderDataState != state) {
                shaderData = new BlockVisionHandler.Data(state, BlockVision.NO_RANGE,
                        BlockVision.DisplayType.SIMPLE_SHADER, BlockVision.NO_VALUE,
                        position.getX(), position.getY(), position.getZ());
                shaderDataState = state;
            }

            return shaderData;
        }
    }

    private static final Map<EntryKey, GlowEntry> ENTRIES = new HashMap<>();

    @Nullable
    private static ClientLevel currentLevel;

    private ClientBlockGlowState() {}

    /** 供渲染层遍历。收包与推进都在客户端主线程，渲染也在主线程，无并发问题 */
    public static @NotNull Collection<GlowEntry> entries() {
        return ENTRIES.values();
    }

    public static void onReceive(final @NotNull BlockGlowPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;

        // 世界还没准备好（刚进服 / 正在切维度）时静默丢弃：这些条目最多只差 20 刻就会被重发
        if (level == null) {
            return;
        }

        ensureLevel(level);

        long[] positions = payload.positions();
        int[] remaining = payload.remainingTicks();

        for (int index = 0; index < positions.length; index++) {
            int ticks = remaining[index];
            long packed = positions[index];
            EntryKey key = new EntryKey(packed, payload.colorARGB(), payload.displayType());

            // remainingTicks <= 0 是「该条目已失效」的**移除**语义：服务端调试清空时只发这些位置 ——
            // 条目表按「位置 + 颜色 + 显示类型」共享，没有别的办法单独收回其中一条。
            // 常规刷新路径永远发 >= 1 的值（见 BlockGlows.flush），所以这里不会误删。
            if (ticks <= 0) {
                ENTRIES.remove(key);
                continue;
            }

            GlowEntry existing = ENTRIES.get(key);

            if (existing != null) {
                // 续命：取较晚的到期时刻，避免重发时较短的剩余时长把已有条目缩减
                existing.remainingTicks = Math.max(existing.remainingTicks, ticks);
                continue;
            }

            if (ENTRIES.size() >= MAX_ENTRIES) {
                return;
            }

            ENTRIES.put(key, new GlowEntry(BlockPos.of(packed), payload.colorARGB(), payload.displayType(), ticks));
        }
    }

    /** 每客户端 tick 推进一次倒计时 */
    public static void tick() {
        if (ENTRIES.isEmpty()) {
            return;
        }

        ENTRIES.values().removeIf(glowEntry -> --glowEntry.remainingTicks <= 0);
    }

    /** 断开连接 / 退出存档时清空，避免条目残留到下一个世界 */
    public static void clear() {
        ENTRIES.clear();
        currentLevel = null;
    }

    /** 世界实例变化（进出存档、跨维度）时整表作废：旧坐标在新世界里没有任何意义 */
    private static void ensureLevel(final @NotNull ClientLevel level) {
        if (currentLevel != level) {
            clear();
            currentLevel = level;
        }
    }
}
