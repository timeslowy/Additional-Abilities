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
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
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
 * <h2>复刻的 DS 做法（四点缺一不可）</h2>
 * <ol>
 *     <li>阶段：{@link RenderLevelStageEvent.Stage#AFTER_SOLID_BLOCKS}
 *         —— 与 DS 同阶段，因此线框会和 DS 内置目标类型的调试箱同层、同样被后续方块正确遮挡；</li>
 *     <li>开关：{@code EntityRenderDispatcher#shouldRenderHitBoxes()} —— 即 F3+B，监听器本身不做按键判断；</li>
 *     <li>只在 <b>Iris 未在渲染阴影 pass</b> 时画（{@link Compat#isRenderingShadows()}）——
 *         阴影 pass 的 pose stack 与世界不一致，此时画的线框会错位（DS 同样在此处提前返回）；</li>
 *     <li>坐标：{@code RenderLevelStageEvent} 的 pose stack 需要自行平移到相机相对坐标系
 *         （{@code translate(-camera)}），之后 {@link LevelRenderer#renderLineBox} 直接吃世界坐标的 AABB。</li>
 * </ol>
 *
 * <h2>只画"当前选中槽位"的技能</h2>
 * 与 DS 保持一致：只取 {@code MagicData#getSelectedAbility()}（即当前选中技能槽），
 * 遍历它的所有 {@code actions}，命中本模组类型才画。这样同一玩家身上一次只出现一个箱体，
 * 不会把背包/热键栏里所有技能的范围一起糊在屏幕上。
 * <p>
 * <b>刻意使用与 DS 不同的颜色</b>：DS 内置目标类型的调试箱是红（dragon_breath）、蓝（area）、
 * 绿（looking_at / disc）。本模组取黄（反向龙息锥形）、青 / 暗青（环形）与洋红（收束光束）。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID, value = Dist.CLIENT)
public class AbilityHitboxEventHandler {
    /** 调试箱颜色（黄）：与 DS 的红/蓝/绿全部错开，便于区分归属 —— 反向龙息锥形 */
    private static final float DEBUG_COLOR_R = 1.0F;
    private static final float DEBUG_COLOR_G = 1.0F;
    private static final float DEBUG_COLOR_B = 0.0F;

    /** 调试箱颜色（青，外框）：环形目标；与上面的黄再错开一档 */
    private static final float ANNULUS_OUTER_R = 0.0F;
    private static final float ANNULUS_OUTER_G = 1.0F;
    private static final float ANNULUS_OUTER_B = 1.0F;

    /** 调试箱颜色（暗青，内框）：环形目标的挖空内圈 */
    private static final float ANNULUS_INNER_R = 0.0F;
    private static final float ANNULUS_INNER_G = 0.45F;
    private static final float ANNULUS_INNER_B = 0.45F;

    /** 调试箱颜色（洋红）：龙息收束后的真实判定光束，与上面全部错开 */
    private static final float BEAM_R = 1.0F;
    private static final float BEAM_G = 0.2F;
    private static final float BEAM_B = 1.0F;

    @SubscribeEvent
    public static void onRenderLevelStage(final @NotNull RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }

        // 即 F3+B；关闭时整个监听器直接空转
        if (!Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes()) {
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

        VertexConsumer buffer = Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(RenderType.LINES);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();

        pose.pushPose();
        // 世界坐标 → 相机相对（RenderLevelStageEvent 的标准写法，DS 同款）
        pose.translate(-camera.x(), -camera.y(), -camera.z());

        for (ActionContainer action : ability.value().actions()) {
            if (action.effect() instanceof AntiDragonBreathTarget reverseCone) {
                LevelRenderer.renderLineBox(pose, buffer, reverseCone.calculateReverseBreathArea(player, ability),
                        DEBUG_COLOR_R, DEBUG_COLOR_G, DEBUG_COLOR_B, 1.0F);
            } else if (action.effect() instanceof AnnulusTarget annulus) {
                // 环形无法用单个包围盒表达：外框给出环带外沿，内框给出被挖空的中心
                Vec3 origin = player.position();
                double height = annulus.resolveHeight(ability);
                double inner = annulus.resolveInnerRadius(ability);
                double outer = annulus.resolveOuterRadius(ability);

                LevelRenderer.renderLineBox(pose, buffer, annulus.calculateArea(origin, outer, height),
                        ANNULUS_OUTER_R, ANNULUS_OUTER_G, ANNULUS_OUTER_B, 1.0F);

                // 内半径 ≤ 0 时内框退化为一个点，没必要画
                if (inner > 0) {
                    LevelRenderer.renderLineBox(pose, buffer, annulus.calculateArea(origin, inner, height),
                            ANNULUS_INNER_R, ANNULUS_INNER_G, ANNULUS_INNER_B, 1.0F);
                }
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

    /**
     * 画旋转长方体的 12 条棱。
     * <p>
     * {@link LevelRenderer#renderLineBox} 只吃轴对齐盒，所以这里自行写顶点：
     * {@link RenderType#LINES} 的顶点格式是 {@code POSITION_COLOR_NORMAL}，
     * 三个属性必须齐全（法线用该棱的方向，对线段渲染没有实际影响，仅占位）。
     */
    private static void renderBeam(final PoseStack pose, final VertexConsumer buffer, final @NotNull BreathBeam beam) {
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
            line(pose, buffer, corners[edge[0]], corners[edge[1]]);
        }
    }

    private static void line(final @NotNull PoseStack pose, final VertexConsumer buffer, final Vec3 from, final @NotNull Vec3 to) {
        PoseStack.Pose last = pose.last();
        Vec3 normal = to.subtract(from).normalize();

        for (Vec3 point : new Vec3[]{from, to}) {
            buffer.addVertex(last, (float) point.x, (float) point.y, (float) point.z)
                    .setColor(BEAM_R, BEAM_G, BEAM_B, 1.0F)
                    .setNormal(last, (float) normal.x, (float) normal.y, (float) normal.z);
        }
    }
}
