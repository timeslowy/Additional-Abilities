package by.timeslowly.additional_abilities.client.state;

import by.timeslowly.additional_abilities.common.network.BlockQuakePayload;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 方块震动的客户端假身管理（<b>仅客户端加载</b>）。
 * <p>
 * 假身是本地生成的 {@link Display.BlockDisplay}：不进存档、不参与碰撞、不产生任何世界副作用
 * —— 这正是"用假身代替真实方块"的目的。服务端只发位置，方块状态由客户端从自己的世界读取。
 * <p>
 * 抛物线为 {@code h(u) = 4H·u(1-u)}（u 为进度，峰值在 u=0.5），落地即销毁。
 * 位置每刻更新一次，靠原版实体渲染的位置插值在任意帧率下都是平滑的。
 * <p>
 * <b>同位置去重</b>：若该位置已有假身在空中，直接忽略新触发。
 * 这样即便服务端冷却（10 刻）短于一次跃起时长（最长 24 刻），也不会出现"跳到半空被拽回地面"
 * 的瞬移；代价是持续震动时以首次触发的高度为准。
 * <p>
 * <b>光照必须显式写入</b>：假身的渲染亮度默认只来自实体光照探针
 * （{@code EntityRenderer#getPackedLightCoords} →
 * {@code LightTexture.pack(level.getBrightness(BLOCK, P), level.getBrightness(SKY, P))}，
 * 其中 {@code P = BlockPos.containing(entity.getEyePosition(partialTick))}）。
 * 而 {@code P} 等于实体自身坐标，我们的假身又精确落在源方块的<b>整数坐标</b>上
 * （方块模型正好占据 [y, y+1]），于是探针取的是<b>源方块自己那一格</b>。
 * <p>
 * <b>关键点</b>：对不透明方块，它「自身所在格」的天空光与方块光都是 0 ——
 * MC 渲染方块时参考的是<b>其上方那一格</b>的光照（中文 Wiki 亦如此注明）。
 * 原版 display 实体通常被放在方块上方而不是与之同格，所以不会暴露这个问题；
 * 我们必然会踩到，表现为整块发黑。
 * <p>
 * 解决办法是按方块渲染的口径就地取样（本格与上方格取较大值，对地表方块即等于上方空气格的光照），
 * 写进 display 的 {@code brightness} 覆盖：该值存在时渲染会<b>整体替换</b>探针结果，
 * 于是假身获得与它替换掉的那个真实方块完全一致的光照，且升空过程中亮度保持不变。
 */
public final class ClientBlockQuakeState {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 临时诊断开关：首次生成假身时打印一次取样值，确认修复后删除 */
    private static boolean diagnosticsLogged = false;

    /** 同时存在的假身上限，超出直接丢弃（防止大范围技能造成实体爆炸） */
    private static final int MAX_ACTIVE = 512;

    /** 位置（打包 long）→ 假身。收包经 enqueueWork、推进在客户端 tick，均在主线程访问 */
    private static final Map<Long, Instance> ACTIVE = new HashMap<>();

    /** 客户端专属实体使用负数 id，避免与真实实体（正数）撞号 */
    private static int nextEntityId = -1;

    private static final class Instance {
        private final Display.BlockDisplay display;
        private final BlockPos position;
        private final float height;
        private final int durationTicks;
        private int elapsed;

        private Instance(final Display.BlockDisplay display, final BlockPos position,
                         final float height, final int durationTicks) {
            this.display = display;
            this.position = position;
            this.height = height;
            this.durationTicks = durationTicks;
        }
    }

    private ClientBlockQuakeState() {}

    /**
     * 按方块渲染的口径就地取样光照，构造 display 的 {@code brightness} 覆盖值。
     * <p>
     * <b>取本格与上方格的较大值</b>：对不透明方块，其自身所在格的光照为 0，
     * 有意义的数值在它上方那一格（这也是 MC 渲染方块时的参考格）。
     * 对地表方块而言结果即等于上方空气格的光照，夜间随天空光曲线一起变暗。
     */
    private static @NotNull CompoundTag sampleBrightness(final @NotNull ClientLevel level,
                                                         final @NotNull BlockPos position,
                                                         final @NotNull BlockState state) {
        CompoundTag brightness = new CompoundTag();

        if (state.emissiveRendering(level, position)) {
            brightness.putInt("block", 15);
            brightness.putInt("sky", 15);
            return brightness;
        }

        BlockPos above = position.above();
        brightness.putInt("block", Math.max(level.getBrightness(LightLayer.BLOCK, position),
                level.getBrightness(LightLayer.BLOCK, above)));
        brightness.putInt("sky", Math.max(level.getBrightness(LightLayer.SKY, position),
                level.getBrightness(LightLayer.SKY, above)));
        return brightness;
    }

    /** 临时诊断：打印一次本格 / 上方格的实际光照值，用于确认取样口径 */
    private static void logLuminanceOnce(final @NotNull ClientLevel level, final @NotNull BlockPos position,
                                         final @NotNull BlockState state) {
        if (diagnosticsLogged) {
            return;
        }

        diagnosticsLogged = true;
        BlockPos above = position.above();
        LOGGER.info("[block_quake] 取样诊断 pos={} state={} 本格(block={}, sky={}) 上方(block={}, sky={})",
                position, state,
                level.getBrightness(LightLayer.BLOCK, position), level.getBrightness(LightLayer.SKY, position),
                level.getBrightness(LightLayer.BLOCK, above), level.getBrightness(LightLayer.SKY, above));
    }

    public static void onReceive(final @NotNull BlockQuakePayload payload) {
        ClientLevel level = Minecraft.getInstance().level;

        if (level == null || payload.height() <= 0.0F || payload.durationTicks() <= 0) {
            return;
        }

        for (long packed : payload.positions()) {
            if (ACTIVE.containsKey(packed)) {
                continue;
            }

            if (ACTIVE.size() >= MAX_ACTIVE) {
                return;
            }

            BlockPos position = BlockPos.of(packed);
            BlockState state = level.getBlockState(position);

            // 该区块未加载、或方块已被移除时读到空气，跳过
            if (state.isAir()) {
                continue;
            }

            logLuminanceOnce(level, position, state);

            Display.BlockDisplay display = EntityType.BLOCK_DISPLAY.create(level);

            if (display == null) {
                continue;
            }

            // BlockDisplay#setBlockState 是 private，只能借 NBT 装载方块状态
            CompoundTag tag = new CompoundTag();
            tag.put(Display.BlockDisplay.TAG_BLOCK_STATE, NbtUtils.writeBlockState(state));
            // 显式写入亮度覆盖，避免假身因探针取不到光照而发黑（详见类注释）
            tag.put(Display.TAG_BRIGHTNESS, sampleBrightness(level, position, state));
            display.load(tag);

            display.setId(nextEntityId--);
            display.setPos(position.getX(), position.getY(), position.getZ());
            // 不做这一步的话，首帧会从 (0,0,0) 插值过来拉出一条残影
            display.setOldPosAndRot();

            level.addEntity(display);
            ACTIVE.put(packed, new Instance(display, position, payload.height(), payload.durationTicks()));
        }
    }

    /** 每客户端 tick 推进一次抛物线 */
    public static void tick() {
        if (ACTIVE.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<Long, Instance>> iterator = ACTIVE.entrySet().iterator();

        while (iterator.hasNext()) {
            Instance instance = iterator.next().getValue();

            if (++instance.elapsed >= instance.durationTicks) {
                instance.display.discard();
                iterator.remove();
                continue;
            }

            double progress = (double) instance.elapsed / instance.durationTicks;
            double offsetY = 4.0 * instance.height * progress * (1.0 - progress);
            instance.display.setPos(instance.position.getX(), instance.position.getY() + offsetY, instance.position.getZ());
        }
    }

    /** 断开连接 / 退出存档时清空，避免假身残留到下一个世界 */
    public static void clear() {
        ACTIVE.values().forEach(instance -> instance.display.discard());
        ACTIVE.clear();
    }
}
