package by.timeslowly.additional_abilities.common.network;

import by.timeslowly.additional_abilities.common.network.charged.ChargedReleasePayload;
import by.timeslowly.additional_abilities.common.network.charged.OptionalChargedSelectionPayload;
import by.timeslowly.additional_abilities.common.network.screenvision.ScreenVisionClearPayload;
import by.timeslowly.additional_abilities.common.network.screenvision.ScreenVisionPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.NotNull;

/**
 * 本模组的网络 payload 注册。
 * <p>
 * NeoForge 1.21.1 没有"通道"这一层：每个 payload 自带
 * {@code ResourceLocation}（如 {@code additional_abilities:screen_vision}），
 * 注册时统一带上 {@link #PROTOCOL_VERSION}，两端版本不一致会在协商阶段被拒绝连接。
 * 本模组当前共注册 <b>6 个</b> payload，命名空间均为 {@code additional_abilities}，
 * 与 DS 的 payload 互不干扰：
 * <table border="1">
 *     <caption>注册清单</caption>
 *     <tr><th>方向</th><th>payload</th><th>用途</th></tr>
 *     <tr><td rowspan="4">S → C</td><td>{@link ScreenVisionPayload}</td><td>屏幕视觉（模糊 / 边光 / 抖动）</td></tr>
 *     <tr><td>{@link ScreenVisionClearPayload}</td><td>屏幕视觉的立即清空</td></tr>
 *     <tr><td>{@link BlockQuakePayload}</td><td>方块震动（假身起跳）</td></tr>
 *     <tr><td>{@link BlockGlowPayload}</td><td>方块发光（线框 / 着色器染色）</td></tr>
 *     <tr><td rowspan="2">C → S</td><td>{@link ChargedReleasePayload}</td><td>蓄力松手 / 取消的结算请求</td></tr>
 *     <tr><td>{@link OptionalChargedSelectionPayload}</td><td>滚轮选档同步（仅供查询指令展示）</td></tr>
 * </table>
 * 增删 payload、改动字段结构、或改动随包传输的枚举<b>序列名</b>取值时，
 * 必须同步递增 {@link #PROTOCOL_VERSION}（历史见该常量的注释）。
 */
public class AANetwork {
    /**
     * 协议版本。两端版本不一致时 NeoForge 会拒绝连接，
     * 因此改动 payload 字段结构、增删 payload 种类、或改动随包传输的
     * 枚举序列名取值时，必须同步递增。
     * <p>
     * 6 → 7：{@link ChargedReleasePayload} 增加 {@code releaseLevel} 字段
     * （支撑 {@code additional_abilities:optional_charged} 的滚轮选档与取消）。
     * <p>
     * 7 → 8：新增 {@link BlockGlowPayload}（方块发光：服务端 → 客户端的位置 + 颜色 + 剩余时长）。
     * <p>
     * 8 → 9：{@link BlockGlowPayload} 的 {@code remainingTicks} 增加「{@code <= 0} 表示该条目已失效、
     * 客户端应移除」的语义（供 {@code /additional-abilities block-glow clear} 使用）。
     * 旧客户端会把它当作"忽略"继续显示，因此属于必须递增的破坏性变更。
     * <p>
     * 9 → 10：{@link ScreenVisionPayload} 增加 {@code size} 与 {@code rgb} 两个字段
     * （支撑 {@code simple_screen_vision} 的新视觉类型 {@code edge_light}：遮罩边缘厚度与颜色）。
     * 字段结构变化即字节格式变化，因此必须递增。
     * <p>
     * 10 → 11：新增物品数据组件 {@code additional_abilities:enchantment_bonus}
     * （{@code AAComponents}，支撑 {@code enchantment_bonus} 实体效果）。
     * 它虽不是 payload，但会<b>随物品栈上线</b>、并进入登录期的注册表同步：
     * 旧客户端收到带该组件的物品栈会解码失败、或直接卡在注册表同步。
     * 递增后版本不匹配会在协商阶段被明确拒绝，而不是在游戏中途炸出难查的解码错误。
     * <p>
     * 11 → 12：{@code additional_abilities:enchantment_bonus} 的载荷由"来源标记（复合结构）"改为
     * "刷新脉冲（一个数值）"（见 {@code EnchantmentBonus#PULSE_CODEC}）。
     * 组件 id 不变，但线上字节格式变了：旧客户端按旧结构解析新值会失败，因此同样递增。
     */
    private static final String PROTOCOL_VERSION = "12";

    @SubscribeEvent
    public static void register(final @NotNull RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        // 屏幕视觉：仅服务端 → 客户端
        registrar.playToClient(ScreenVisionPayload.TYPE, ScreenVisionPayload.STREAM_CODEC, ScreenVisionPayload::handleClient);

        // 屏幕视觉的立即清空（无字段）：仅服务端 → 客户端
        registrar.playToClient(ScreenVisionClearPayload.TYPE, ScreenVisionClearPayload.STREAM_CODEC, ScreenVisionClearPayload::handleClient);

        // 方块震动：仅服务端 → 客户端
        registrar.playToClient(BlockQuakePayload.TYPE, BlockQuakePayload.STREAM_CODEC, BlockQuakePayload::handleClient);

        // 方块发光：仅服务端 → 客户端
        registrar.playToClient(BlockGlowPayload.TYPE, BlockGlowPayload.STREAM_CODEC, BlockGlowPayload::handleClient);

        // 蓄力释放：仅客户端 → 服务端
        registrar.playToServer(ChargedReleasePayload.TYPE, ChargedReleasePayload.STREAM_CODEC, ChargedReleasePayload::handleServer);

        // 可选性蓄力的滚轮选档同步：仅客户端 → 服务端（只为查询指令可见性，不参与结算）
        registrar.playToServer(OptionalChargedSelectionPayload.TYPE, OptionalChargedSelectionPayload.STREAM_CODEC,
                OptionalChargedSelectionPayload::handleServer);
    }
}
