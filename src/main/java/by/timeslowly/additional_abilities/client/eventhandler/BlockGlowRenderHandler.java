package by.timeslowly.additional_abilities.client.eventhandler;

import by.dragonsurvivalteam.dragonsurvival.client.render.block_vision.BlockVisionShaderSimple;
import by.dragonsurvivalteam.dragonsurvival.compat.Compat;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.state.ClientBlockGlowState;
import by.timeslowly.additional_abilities.common.config.AAClientConfig;
import by.timeslowly.additional_abilities.client.state.ClientBlockGlowState.GlowEntry;
import by.timeslowly.additional_abilities.client.renderer.GlowOutlineRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 方块发光的客户端入口（<b>仅客户端加载</b>）：渲染订阅 + 条目倒计时推进 + 断线清理。无 Mixin。
 * <p>
 * <b>两条通道与各自的剔除措施</b>：
 * <table border="1">
 *     <tr><th>通道</th><th>每块代价</th><th>施加的剔除</th></tr>
 *     <tr>
 *         <td>{@code outline}</td>
 *         <td>恒定 12 条棱 / 24 顶点，与方块形状无关</td>
 *         <td>距离 + 视锥（GPU 深度测试已免费处理"被遮挡"）</td>
 *     </tr>
 *     <tr>
 *         <td>{@code simple_shader}</td>
 *         <td>逐帧 {@code getBlockModel} + 7 次 {@code getQuads} + 顶点重写</td>
 *         <td>服务端源头剔除（遮挡 / 非完整形状）+ 距离 + 视锥 + 数量上限</td>
 *     </tr>
 * </table>
 * 线框之所以不做遮挡剔除：它的 CPU 代价与是否可见无关且极小，而开启深度测试后 GPU 会免费把它挡住；
 * 着色器通道则相反 —— CPU 代价固定产生，因此必须在服务端就把不值得画的方块拦下来。
 * <p>
 * <b>stage 分派是显式的</b>：{@code simple_shader} 走核心着色器，装了 Iris 时只能在 {@code AFTER_LEVEL}
 * 绘制（Iris 几乎不支持核心着色器），没装时在 {@code AFTER_CUTOUT_BLOCKS}（该阶段能透过水面看到）。
 * 两个阶段可能落在<b>同一次事件</b>上，因此本类只用一个订阅方法、方法内按 stage 显式分流，
 * 不依赖「同 stage 内多个订阅方法的调用顺序」。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID, value = Dist.CLIENT)
public class BlockGlowRenderHandler {
    // 两条通道的可见距离不在这里写死 —— 它们是客户端配置项
    // （AAClientConfig 的 block_glow.outline_distance / shader_distance），改配置即时生效、无需重启。
    // 「包能发到多远」由服务端的 BlockGlows.VIEW_MARGIN 决定，而配置的上限就钉在那个常量上，
    // 因此不可能配出「客户端想画、服务端却没发包」的静默截断。

    /** 单帧参与核心着色器渲染的方块数上限，超限按距离由近到远截断（确定性兜底） */
    private static final int MAX_SHADER_BLOCKS = 256;

    /** 本帧待走着色器的方块。跨 stage 传递（Iris 场景下收集与绘制不在同一个 stage） */
    private static final List<GlowEntry> SHADER_QUEUE = new ArrayList<>();

    @SubscribeEvent
    public static void onRenderLevelStage(final @NotNull RenderLevelStageEvent event) {
        // 阴影 pass 里不该出现发光（与 DS 的 BlockVisionHandler 同处理）
        if (Compat.isRenderingShadows()) {
            return;
        }

        RenderLevelStageEvent.Stage stage = event.getStage();
        boolean corePass = stage == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS;
        boolean shaderPass = Compat.isShaderActive()
                ? stage == RenderLevelStageEvent.Stage.AFTER_LEVEL
                : corePass;

        if (!corePass && !shaderPass) {
            return;
        }

        if (corePass) {
            renderCorePass(event);
        }

        if (shaderPass) {
            renderShaderPass(event);
            SHADER_QUEUE.clear();
        }
    }

    /** 收集可见条目 → 就地画线框 → 把走着色器的条目排进队列 */
    private static void renderCorePass(final @NotNull RenderLevelStageEvent event) {
        // 每帧重建：装了 Iris 时本次只收集、不发队列，避免异常情况下队列跨帧累积
        SHADER_QUEUE.clear();

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null) {
            return;
        }

        Collection<GlowEntry> entries = ClientBlockGlowState.entries();

        if (entries.isEmpty()) {
            return;
        }

        Frustum frustum = event.getFrustum();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.mulPose(event.getModelViewMatrix());
        pose.translate(-camera.x(), -camera.y(), -camera.z());

        GlowOutlineRenderer.beginBatch();

        for (GlowEntry entry : entries) {
            if (!isVisible(entry, frustum, camera)) {
                continue;
            }

            if (entry.displayType().isShader()) {
                SHADER_QUEUE.add(entry);
                continue;
            }

            pose.pushPose();
            pose.translate(entry.position().getX(), entry.position().getY(), entry.position().getZ());
            GlowOutlineRenderer.render(pose, entry.colorARGB());
            pose.popPose();
        }

        GlowOutlineRenderer.endBatch();
        pose.popPose();

        trimShaderQueue(camera);
    }

    /** 距离 + 视锥剔除。包围盒在收包时已构造好并缓存在条目里，这里不产生任何分配 */
    private static boolean isVisible(final @NotNull GlowEntry entry, final @NotNull Frustum frustum,
                                     final @NotNull Vec3 camera) {
        // 逐帧读配置：ModConfigSpec 的取值自带缓存，改配置即时生效，这里不需要另做缓存
        double maxDistance = entry.displayType().isShader()
                ? AAClientConfig.glowShaderDistance()
                : AAClientConfig.glowOutlineDistance();

        return entry.bounds().getCenter().distanceToSqr(camera) <= maxDistance * maxDistance
                && frustum.isVisible(entry.bounds());
    }

    /**
     * 超限时只保留最近的若干块。
     * <p>
     * 排序比较器按<b>固定的相机位置</b>现算距离，因此不需要额外的平行距离数组；
     * 只有确实超限时才排序，常规情况（同屏方块数在上限内）完全跳过。
     */
    private static void trimShaderQueue(final @NotNull Vec3 camera) {
        if (SHADER_QUEUE.size() <= MAX_SHADER_BLOCKS) {
            return;
        }

        SHADER_QUEUE.sort(Comparator.comparingDouble(entry -> entry.bounds().getCenter().distanceToSqr(camera)));
        SHADER_QUEUE.subList(MAX_SHADER_BLOCKS, SHADER_QUEUE.size()).clear();
    }

    /** 走 DS 的核心着色器通道：整块方块的模型按颜色重新染色 */
    private static void renderShaderPass(final @NotNull RenderLevelStageEvent event) {
        if (SHADER_QUEUE.isEmpty()) {
            return;
        }

        ClientLevel level = Minecraft.getInstance().level;

        if (level == null) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        pose.pushPose();

        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        pose.mulPose(event.getModelViewMatrix());
        pose.translate(-camera.x(), -camera.y(), -camera.z());

        BlockVisionShaderSimple.beginBatch();

        for (GlowEntry entry : SHADER_QUEUE) {
            BlockPos position = entry.position();
            BlockState state = level.getBlockState(position);

            // 收包后世界可能已变化（区块未加载读到空气、方块被换掉）：这里与 BlockGlows 建立条目时的
            // 服务端筛选口径保持一致。isCollisionShapeFullBlock 命中的是 BlockState 自带缓存，几乎免费。
            if (state.isAir() || !state.isCollisionShapeFullBlock(level, position)) {
                continue;
            }

            pose.pushPose();
            pose.translate(position.getX(), position.getY(), position.getZ());
            BlockVisionShaderSimple.render(entry.shaderData(state), pose, entry.colorARGB());
            pose.popPose();
        }

        BlockVisionShaderSimple.endBatch();
        pose.popPose();
    }

    /** 每客户端刻推进一次倒计时 */
    @SubscribeEvent
    public static void onClientTick(final @NotNull ClientTickEvent.Post event) {
        ClientBlockGlowState.tick();
    }

    /** 断线 / 退出存档时清空，避免条目残留到下一个世界（跨维度由状态层比对世界实例自行处理） */
    @SubscribeEvent
    public static void onLoggingOut(final @NotNull ClientPlayerNetworkEvent.LoggingOut event) {
        ClientBlockGlowState.clear();
    }
}
