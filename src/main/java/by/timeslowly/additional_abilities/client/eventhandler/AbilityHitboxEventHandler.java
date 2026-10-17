package by.timeslowly.additional_abilities.client.eventhandler;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.codecs.ability.ActionContainer;
import by.dragonsurvivalteam.dragonsurvival.compat.Compat;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.DragonBreathTarget;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.common.ability.geometry.BreathBeam;
import by.timeslowly.additional_abilities.registry.dragon.ability.targeting.AnnulusTarget;
import by.timeslowly.additional_abilities.registry.dragon.ability.targeting.AntiDragonBreathTarget;
import by.timeslowly.additional_abilities.registry.dragon.ability.targeting.DomainTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 在 <b>F3+B</b>（碰撞箱显示）开启时，用线框画出本模组目标选择器的实际作用箱体：
 * <ul>
 *     <li>{@code additional_abilities:anti_dragon_breath} —— 单个包围盒，黄；</li>
 *     <li>{@code additional_abilities:annulus} —— 内外双包围盒，青（外框）/ 暗青（内框）；</li>
 *     <li>{@code additional_abilities:domain} —— 单个包围盒，橙（画在玩家当前脚下，
 *         表示"现在施法会在原地留下多大的领域"）；</li>
 *     <li>{@code dragonsurvival:dragon_breath} <b>处于收束状态</b>时 —— 真正的判定光束，洋红。</li>
 * </ul>
 * <p>
 * 对应 DS 侧的实现在 {@code ClientDragonRenderer#renderAbilityHitbox(RenderLevelStageEvent)}
 * —— 它用一个 {@code targeting instanceof XxxTarget} 的分支链渲染内置的 5 种目标类型，
 * 附属模组的类型不在其中。DS 那个分派是硬编码的、没有扩展点，所以这里<b>另起一个同款监听器</b>
 * 补齐本模组的类型，而不是去 Mixin 改 DS 的私有方法。
 *
 * <h2>第三条分支（龙息收束光束）为什么必要</h2>
 * 「龙息范围收束」生效后，DS 画的那个红框已经**不再是判定区**，而只是服务端的「粗筛范围」
 * （一个必须包住斜向光束的轴对齐盒，斜视时会明显偏大）。
 * 若只有红框，玩家会以为判定区真的那么大 —— 这正是收束第一版暴露出来的误读来源。
 * 因此这里把真正的判定体（{@link BreathBeam} 的旋转长方体）单独画出来：
 * 洋红色 12 条棱，与 DS 的红/蓝/绿、本模组的黄/青全部错开。
 *
 * <h2>26.1.2 的重写要点</h2>
 * <ol>
 *     <li><b>阶段枚举没了</b>：{@code RenderLevelStageEvent.Stage} 整体删除，改为独立子事件。
 *         旧代码的 {@code AFTER_SOLID_BLOCKS} 对应新的
 *         {@link RenderLevelStageEvent.AfterOpaqueBlocks}（DS 自己也是这么改的）。</li>
 *     <li><b>相机位置换了来源</b>：{@code event.getCamera()} 不存在了，改从
 *         {@code event.getLevelRenderState().cameraRenderState.pos} 取
 *         （{@code CameraRenderState#pos}，与老的 {@code Camera#getPosition()} 同值）。</li>
 *     <li><b>F3+B 的判定换了来源</b>：{@code EntityRenderDispatcher#shouldRenderHitBoxes()} 已删除，
 *         改成查调试项表 {@code Minecraft#debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES)}
 *         —— DS 的 {@code ClientDragonRenderer} 同样改成了这个。</li>
 *     <li><b>{@code LevelRenderer#renderLineBox} 没了</b>：26.1.2 的线框走顶点提交，
 *         这里自己往 {@link RenderTypes#lines()} 的缓冲里写棱。</li>
 * </ol>
 * <p>
 * 仍然保留「只在 Iris 未渲染阴影 pass 时画」与「只画当前选中槽位的技能」两条老规矩。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID, value = Dist.CLIENT)
public class AbilityHitboxEventHandler {
    /** 调试箱颜色（黄）：与 DS 的红/蓝/绿全部错开，便于区分归属 —— 反向龙息锥形 */
    private static final int DEBUG_COLOR = ARGB.color(255, 255, 255, 0);

    /** 调试箱颜色（青，外框）：环形目标；与上面的黄再错开一档 */
    private static final int ANNULUS_OUTER_COLOR = ARGB.color(255, 0, 255, 255);

    /** 调试箱颜色（暗青，内框）：环形目标的挖空内圈 */
    private static final int ANNULUS_INNER_COLOR = ARGB.color(255, 0, 115, 115);

    /** 调试箱颜色（洋红）：龙息收束后的真实判定光束，与上面全部错开 */
    private static final int BEAM_COLOR = ARGB.color(255, 255, 51, 255);

    /** 调试箱颜色（橙）：领域目标的粗筛盒，与上面全部错开 */
    private static final int DOMAIN_COLOR = ARGB.color(255, 255, 140, 0);

    /** 线宽（像素）：比原版调试线的默认 1.0 略粗，便于在远处辨认 */
    private static final float LINE_WIDTH = 1.0F;

    @SubscribeEvent
    public static void onRenderLevelStage(final @NotNull RenderLevelStageEvent.AfterOpaqueBlocks event) {
        // 即 F3+B；关闭时整个监听器直接空转。
        // 26.1.2：EntityRenderDispatcher#shouldRenderHitBoxes 已删除，改查调试项表。
        if (!Minecraft.getInstance().debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES)) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;

        if (player == null) {
            return;
        }

        if (Compat.isRenderingShadows()) {
            return;
        }

        if (!DragonStateProvider.isDragon(player)) {
            return;
        }

        DragonAbilityInstance ability = MagicData.getData(player).getSelectedAbility();

        if (ability == null) {
            return;
        }

        VertexConsumer buffer = Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(RenderTypes.lines());
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack pose = event.getPoseStack();

        pose.pushPose();
        // 世界坐标 → 相机相对（RenderLevelStageEvent 的标准写法，DS 同款）
        pose.translate(-camera.x, -camera.y, -camera.z);

        for (ActionContainer action : ability.value().actions()) {
            if (action.effect() instanceof AntiDragonBreathTarget reverseCone) {
                box(pose, buffer, reverseCone.calculateReverseBreathArea(player, ability), DEBUG_COLOR);
            } else if (action.effect() instanceof AnnulusTarget annulus) {
                // 环形无法用单个包围盒表达：外框给出环带外沿，内框给出被挖空的中心
                Vec3 origin = player.position();
                double height = annulus.resolveHeight(ability);
                double inner = annulus.resolveInnerRadius(ability);
                double outer = annulus.resolveOuterRadius(ability);

                box(pose, buffer, annulus.calculateArea(origin, outer, height), ANNULUS_OUTER_COLOR);

                // 内半径 ≤ 0 时内框退化为一个点，没必要画
                if (inner > 0) {
                    box(pose, buffer, annulus.calculateArea(origin, inner, height), ANNULUS_INNER_COLOR);
                }
            } else if (action.effect() instanceof DomainTarget domain) {
                // 领域真正的锚点是"首次结算瞬间的位置"，客户端拿不到那次施法的坐标，
                // 因此这里与 DS 对 area / disc 的调试画法同一思路：以玩家当前脚下为锚点画粗筛盒，
                // 表达的是"现在施法会留下多大的领域"。球形/柱形的盒内还有一次形状精筛
                // （球会切掉 8 个角、柱形会切掉水平四角），所以线框比真实判定区略大，属预期。
                box(pose, buffer, domain.calculateArea(player.position(), ability), DOMAIN_COLOR);
            } else if (action.effect() instanceof DragonBreathTarget breath) {
                // 收束生效时 DS 的红框只剩「粗筛区」的含义（斜视时会明显偏大），
                // 真正的判定体是这个旋转长方体，必须单独画出来，否则「看到的框 ≠ 判定范围」。
                BreathBeam beam = BreathBeam.restricted(player, breath.rangeMultiplier(), ability.level());

                if (beam != null) {
                    renderBeam(pose, buffer, beam);
                }
            }
        }

        pose.popPose();
    }

    /** 画一个轴对齐包围盒的 12 条棱 */
    private static void box(final @NotNull PoseStack pose, final @NotNull VertexConsumer buffer,
                            final @NotNull AABB area, final int color) {
        Vec3[] corners = {
                new Vec3(area.minX, area.minY, area.minZ),
                new Vec3(area.maxX, area.minY, area.minZ),
                new Vec3(area.maxX, area.minY, area.maxZ),
                new Vec3(area.minX, area.minY, area.maxZ),
                new Vec3(area.minX, area.maxY, area.minZ),
                new Vec3(area.maxX, area.maxY, area.minZ),
                new Vec3(area.maxX, area.maxY, area.maxZ),
                new Vec3(area.minX, area.maxY, area.maxZ)};

        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},     // 底面
                {4, 5}, {5, 6}, {6, 7}, {7, 4},     // 顶面
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};    // 四条竖棱

        for (int[] edge : edges) {
            line(pose, buffer, corners[edge[0]], corners[edge[1]], color);
        }
    }

    /**
     * 画旋转长方体的 12 条棱。
     * <p>
     * 26.1.2 的 {@link RenderTypes#lines()} 顶点格式是
     * {@code POSITION_COLOR_NORMAL_LINE_WIDTH} —— 位置 / 颜色 / 法线 / 线宽四样必须齐全
     * （法线用该棱的方向，对线段渲染没有实际影响，仅占位）。
     */
    private static void renderBeam(final @NotNull PoseStack pose, final @NotNull VertexConsumer buffer, final @NotNull BreathBeam beam) {
        Vec3 forward = beam.direction().scale(beam.length());
        Vec3 side = beam.right().scale(beam.halfWidth());
        Vec3 vertical = beam.up().scale(beam.halfHeight());

        // 位编码：bit0 = 横向 ±，bit1 = 纵向 ±，bit2 = 近端(0) / 远端(1)
        Vec3[] corners = new Vec3[8];

        for (int index = 0; index < 8; index++) {
            corners[index] = beam.origin()
                    .add(forward.scale((index & 4) == 0 ? 0.0 : 1.0))
                    .add(side.scale((index & 1) == 0 ? -1.0 : 1.0))
                    .add(vertical.scale((index & 2) == 0 ? -1.0 : 1.0));
        }

        int[][] edges = {
                {0, 1}, {1, 3}, {3, 2}, {2, 0},     // 近端面
                {4, 5}, {5, 7}, {7, 6}, {6, 4},     // 远端面
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};    // 四条侧棱

        for (int[] edge : edges) {
            line(pose, buffer, corners[edge[0]], corners[edge[1]], BEAM_COLOR);
        }
    }

    private static void line(final @NotNull PoseStack pose, final @NotNull VertexConsumer buffer,
                             final @NotNull Vec3 from, final @NotNull Vec3 to, final int color) {
        PoseStack.Pose last = pose.last();
        Vec3 normal = to.subtract(from).normalize();

        for (Vec3 point : new Vec3[]{from, to}) {
            buffer.addVertex(last, (float) point.x, (float) point.y, (float) point.z)
                    .setColor(color)
                    .setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z)
                    .setLineWidth(LINE_WIDTH);
        }
    }
}
