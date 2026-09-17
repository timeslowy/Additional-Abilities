package by.timeslowly.additional_abilities.client.eventhandler;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.codecs.ability.ActionContainer;
import by.dragonsurvivalteam.dragonsurvival.compat.Compat;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.Additional_abilities;
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
 * 在 <b>F3+B</b>（碰撞箱显示）开启时，用线框画出本模组目标选择器的实际作用箱体
 * （{@code additional_abilities:anti_dragon_breath}）。
 * <p>
 * 对应 DS 侧的实现在 {@code ClientDragonRenderer#renderAbilityHitbox(RenderLevelStageEvent)}
 * —— 它用一个 {@code targeting instanceof XxxTarget} 的分支链渲染内置的 5 种目标类型，
 * 附属模组的类型不在其中。DS 那个分派是硬编码的、没有扩展点，所以这里<b>另起一个同款监听器</b>
 * 补齐本模组的类型，而不是去 Mixin 改 DS 的私有方法。
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
 * 绿（looking_at / disc）。本类型取黄色
 */
@EventBusSubscriber(modid = Additional_abilities.MOD_ID, value = Dist.CLIENT)
public class AbilityHitboxEventHandler {
    /** 调试箱颜色（黄）：与 DS 的红/蓝/绿全部错开，便于区分归属 */
    private static final float DEBUG_COLOR_R = 1.0F;
    private static final float DEBUG_COLOR_G = 1.0F;
    private static final float DEBUG_COLOR_B = 0.0F;

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
            }
        }

        pose.popPose();
    }
}
