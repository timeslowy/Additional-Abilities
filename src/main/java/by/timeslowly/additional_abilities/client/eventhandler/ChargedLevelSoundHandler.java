package by.timeslowly.additional_abilities.client.eventhandler;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.AdditionalAbilities;
import by.timeslowly.additional_abilities.client.OptionalChargedSelection;
import by.timeslowly.additional_abilities.common.config.AAClientConfig;
import by.timeslowly.additional_abilities.common.config.LevelUpSound;
import by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 蓄力档位族系激活类型的<b>档位提示音</b>与<b>客户端状态生命周期</b>（仅客户端加载），
 * 同时服务 {@code additional_abilities:charged} 与 {@code additional_abilities:optional_charged}。
 *
 * <h2>为什么音效不待在 HUD 图层里</h2>
 * 原先这两件事都写在 {@code client.hud.ChargedIndicatorLayer} 的 {@code render} 里：渲染读数区的同时，
 * 顺带做"档位是否跨升一级"的边沿检测（一个 {@code lastRenderedLevel} 静态字段）并播提示音。
 * 那有两个问题：
 * <ol>
 *     <li><b>边沿检测被绑在渲染回调上</b>：触发时机随<b>帧率</b>漂移，而不是与<b>逻辑刻</b>对齐；</li>
 *     <li><b>音效连带受 GUI 可见性摆布</b>：按 F1（{@code hideGui}）时 HUD 会早退并顺手清状态，
 *         于是"隐藏 HUD"这个纯视觉动作把提示音也一起静音了 —— 没人设计过这个联动，
 *         文档里也没写过，属于耦合带出来的副作用。</li>
 * </ol>
 * 因此本类把这两件事挪到 {@link ClientTickEvent.Post}（客户端每刻一次，与逻辑刻对齐、
 * 与帧率无关），并<b>刻意不检查 {@code hideGui}</b> —— 声音是听觉反馈，不该由视觉层的可见性决定。
 *
 * <h2>为什么连 {@link OptionalChargedSelection} 的生命周期也放在这里</h2>
 * 原先"未在施法就把选档状态清掉"是 {@code ChargedIndicatorLayer} 在早退分支里做的，
 * 也就是<b>渲染器在改交互状态</b>。既然本类已经需要一个每刻运行、且判据完全一致
 * （无玩家 / 旁观 / 非龙 / 未施法 / 非蓄力类型）的入口，就把这份清理一并接管 ——
 * {@code ChargedIndicatorLayer} 由此变成纯只读视图。
 * <p>
 * 副作用（有意为之）：按 F1 期间滚轮选档<b>不再</b>被清掉，选定的档位会保留到松手或施法结束。
 *
 * <h2>为什么每次播放的都是同一个音频变体</h2>
 * 原版允许一个音效 id 在 {@code sounds.json} 里对应<b>多个音频文件</b>（各带权重），播放时随机挑一个：
 * {@code AbstractSoundInstance#resolve} 里执行 {@code weighedsoundevents.getSound(this.random)}，
 * 而 {@code WeighedSoundEvents#getSound} 的实现是 {@code randomSource.nextInt(总权重)} 再按权重逐个扣减。
 * 决定变体的就是这个<b>实例自带的 {@code RandomSource}</b> —— 而 {@code SimpleSoundInstance.forUI(...)}
 * 传入的是 {@code SoundInstance.createUnseededRandom()}，于是每次播放都重新掷一次骰子。
 * 对"每升一档响一次"的提示音而言，表现就是<b>同一个档位的提示音色每次不同</b>，连续性很差。
 * <p>
 * 解法是把随机源换成<b>固定种子</b>（{@link #VARIANT_SEED}）：{@code nextInt} 恒返回同值，
 * 因此每次播放都命中同一个变体。注意 {@code WeighedSoundEvents} 的变体列表是私有字段、没有取值器，
 * 所以只能固定"某个确定的变体"、无法指定"第 N 个"—— 想要换一个变体就改那个种子（见该常量的注释）。
 *
 * <h2>档位换算不重复实现</h2>
 * 一律走 {@link ChargeableActivation#getChargedLevelOf} 这个共享入口 —— 与 HUD 显示、客户端松手拦截
 * 用的是同一套换算，所以"听到的声音"与"显示的数字"（以及松手时实际释放的档位）必然一致。
 *
 * <h2>可配置</h2>
 * 全部来自客户端配置 {@link AAClientConfig} 的 {@code [charged_indicator]} 分区：
 * <ul>
 *     <li>{@link AAClientConfig#playSound()} —— 本音与滚轮选档音共用的总开关；</li>
 *     <li>{@link AAClientConfig#soundVolume()} —— 共用的音量总倍率（叠在各自基准音量之上）；</li>
 *     <li>{@link AAClientConfig#levelUpSound()} —— 用哪个预设音效（配置界面下拉框，8 选 1）；</li>
 *     <li>{@link AAClientConfig#levelUpPitch()} / {@link AAClientConfig#levelUpPitchPerLevel()} /
 *         {@link AAClientConfig#levelUpMaxPitch()} —— 音高曲线，
 *         公式 {@code min(起始音高 + 每档增量 × 档位, 音高上限)}。</li>
 * </ul>
 * 关闭开关只影响本模组的提示音：龙之生存自己的 {@code charging} / {@code start} / {@code end} 音效照常。
 * <p>
 * 默认值（{@code NOTE_PLING} + {@code 0.9 / 0.12 / 2.0}）复现旧版行为，因此不做任何配置时听感不变。
 * "为什么音效只有固定预设"与"为什么音高上限是 2.0"，分别见 {@link LevelUpSound} 与
 * {@link AAClientConfig} 的类注释。
 *
 * <h2>两种提示音的分工</h2>
 * <ul>
 *     <li><b>档位提升音</b>（本类）：每跨升一级播一次，音效与音高曲线都可客户端配置，
 *         且已锁死音频变体（见上）；</li>
 *     <li><b>选档点击音</b>（{@code OptionalChargedScrollHandler}）：滚轮真正改变档位时播一声更短的点击音，
 *         选定为"取消"（档位 0）时用低音区分，音高曲线固定不随本类的配置变化；
 *         <b>未</b>做变体锁定（仍走 {@code forUI}，对应事件有多个变体时会随机挑）。</li>
 * </ul>
 * 两者都只在客户端本地播放 —— 这是"给自己听的档位提示"，不需要发给其他玩家。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID, value = Dist.CLIENT)
public final class ChargedLevelSoundHandler {
    /**
     * 档位提升音的基准音量。配置项 {@code sound_volume} 是叠在它之上的总倍率，
     * 因此默认（倍率 1.0）时响度与升级前完全一致。
     * <p>
     * 与滚轮选档音的基准音量（{@code 0.6}）刻意不同：本音更长更响，选档音更短更轻。
     */
    private static final float LEVEL_UP_BASE_VOLUME = 0.7F;

    /**
     * 变体选择的固定随机种子。
     * <p>
     * 原版按 {@code WeighedSoundEvents#getSound(RandomSource)} 里的 {@code nextInt(总权重)} 加权挑变体，
     * 而随机源是播放实例自带的 —— 传固定种子即可让每次播放都命中同一个变体（理由见类注释）。
     * <p>
     * 想换一个变体就改这个值：同一个音效在不同种子下可能落到不同音频文件。
     * 这是目前唯一"换个变体听听"的手段 —— 原版没有暴露变体列表，
     * 拿到的必定是某个确定的变体，而不是"第 N 个"。
     */
    private static final long VARIANT_SEED = 0L;

    /** 上一次处理的施法技能；换技能即从 0 重新逐级提示，避免把上一个技能的档位带过来。 */
    private static @Nullable ResourceKey<DragonAbility> lastAbility = null;

    /**
     * 上一刻的已达档位，用于判定"是否刚刚跨过一级"。
     * <p>
     * 只比较"变大"：蓄力过程中档位单调上升，用 {@code >} 可以保证同一档只播一次；
     * 档位回退（理论上不该发生）也不会触发重播。
     * <p>
     * 只在客户端主线程（客户端刻 + 渲染）访问，无需同步。
     */
    private static int lastLevel = ChargeableActivation.NO_CHARGED_LEVEL;

    private ChargedLevelSoundHandler() {
        // 事件订阅类
    }

    @SubscribeEvent
    public static void onClientTick(final @NotNull ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;

        // 与 HUD 图层原先的判据保持一致：无玩家 / 旁观 / 非龙形态都不参与
        if (player == null || player.isSpectator() || !DragonStateProvider.isDragon(player)) {
            reset();
            return;
        }

        MagicData magic = MagicData.getData(player);

        if (!magic.isCasting()) {
            reset();
            return;
        }

        DragonAbilityInstance casting = magic.getCurrentlyCasting();

        if (casting == null || !(casting.value().activation() instanceof ChargeableActivation chargeable)) {
            reset();
            return;
        }

        if (!casting.key().equals(lastAbility)) {
            lastAbility = casting.key();
            lastLevel = ChargeableActivation.NO_CHARGED_LEVEL;
        }

        // 此处刻意不检查 hideGui：隐藏 HUD（F1）只应影响画面，不应影响听觉反馈
        int level = ChargeableActivation.getChargedLevelOf(casting, casting.getCurrentTick());

        if (level > lastLevel) {
            playLevelUpSound(level);
        }

        lastLevel = level;

        // 选档状态的归属对齐：只对"可选性蓄力"有意义，且幂等（与滚轮 / 松手处理器共用同一份状态）
        if (chargeable.selectsReleaseLevel()) {
            OptionalChargedSelection.onCasting(casting.key());
        }
    }

    /**
     * 到档提示音：音效与音高曲线都取自客户端配置；音量 = 基准 × 配置倍率。
     * <p>
     * 音高按 {@code min(起始音高 + 每档增量 × 档位, 音高上限)} 求值 —— 三个参数都是配置项，
     * 默认值复现旧版曲线（{@code 0.9 / 0.12 / 2.0}）。
     * 引擎侧还会把音高钳到 {@code [0.5, 2.0]}，所以配置范围直接取该区间，不存在"设了没效果"的取值。
     * <p>
     * 开关关闭时直接返回，连 {@link SimpleSoundInstance} 都不构造。
     */
    private static void playLevelUpSound(final int level) {
        if (!AAClientConfig.playSound()) {
            return;
        }

        float pitch = Math.min(
                (float) AAClientConfig.levelUpPitch() + (float) AAClientConfig.levelUpPitchPerLevel() * level,
                (float) AAClientConfig.levelUpMaxPitch());
        float volume = LEVEL_UP_BASE_VOLUME * (float) AAClientConfig.soundVolume();
        SoundEvent sound = AAClientConfig.levelUpSound().resolve();

        Minecraft.getInstance().getSoundManager().play(createInstance(sound, pitch, volume));
    }

    /**
     * 构造播放实例：参数与 {@code SimpleSoundInstance.forUI(...)} <b>逐项等价</b>
     * （{@code MASTER} 通道、相对坐标、{@code Attenuation.NONE}、无循环、无延迟），
     * 唯一的差别是随机源换成固定种子 {@link #VARIANT_SEED}，从而锁死音频变体（理由见类注释）。
     * <p>
     * ⚠️ 该构造函数的参数顺序是 {@code (…, volume, pitch, …)}，与
     * {@code forUI(sound, pitch, volume)} 的书写顺序<b>相反</b>，别照抄顺序。
     */
    private static @NotNull SimpleSoundInstance createInstance(final @NotNull SoundEvent sound,
                                                              final float pitch, final float volume) {
        return new SimpleSoundInstance(
                sound.getLocation(),
                SoundSource.MASTER,
                volume,
                pitch,
                RandomSource.create(VARIANT_SEED),
                false,
                0,
                SoundInstance.Attenuation.NONE,
                0.0,
                0.0,
                0.0,
                true);
    }

    /**
     * 离开蓄力状态时清空：下一次蓄力从 0 重新逐级提示。
     * <p>
     * 顺带清掉可选性蓄力的选档状态 —— 这份清理原先由 HUD 图层在早退分支里做，
     * 现在集中到这里，HUD 不再改交互状态。
     */
    private static void reset() {
        lastAbility = null;
        lastLevel = ChargeableActivation.NO_CHARGED_LEVEL;
        OptionalChargedSelection.reset();
    }
}
