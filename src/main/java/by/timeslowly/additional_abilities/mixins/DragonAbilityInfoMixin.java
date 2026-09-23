package by.timeslowly.additional_abilities.mixins;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Activation;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 在技能的**详细信息面板**（按住 Shift 展开的「信息」侧栏）首行补一行
 * <b>激活类型</b> —— 龙生本体只对被动技能显示「触发条件」，从不显示
 * {@code activation.activation_type} 本身，而本模组的
 * {@code charged} / {@code optional_charged} 恰恰是"同一个 {@code Type.SIMPLE} 之下的两种不同玩法"，
 * 玩家在界面上完全看不出差别。
 *
 * <h2>为什么要动 Mixin</h2>
 * 「信息」侧栏的全部内容（施法时间 / 冷却时间 / 初始魔力消耗 / 效果数值）都来自
 * {@link DragonAbility#getInfo}，而它是 record 上的普通实例方法 ——
 * {@code Activation} 接口只有取值器、没有任何"描述"回调，也没有可挂的事件钩子，
 * 数据驱动的激活类型无法从外部扩展这一行。唯一调用方是
 * {@code client.render.AbilityAndPenaltyTooltipRenderer#drawAbilityTooltip}，
 * 因此在这里补一行即可同时覆盖技能界面与背包内的技能悬浮提示。
 *
 * <h2>注入点为什么选尾端 RETURN</h2>
 * {@code getInfo} 内部只有一个 {@code return info;}（尾端 {@code areturn}，
 * 已对 DS 2.0.70 的 class 文件核实），且 {@code info} 是方法内新建的 {@link java.util.ArrayList}。
 * 因此 {@link At @At("RETURN")} 只触发一次，且可以直接<b>就地修改返回值</b>：
 * 插到索引 0 即为首行 —— 与龙生给被动技能放「触发条件」行的位置语义一致
 * （都是"这个技能是什么性质"的元信息，排在时长与费用之前）。
 *
 * <h2>激活类型名怎么取</h2>
 * 不用 {@code instanceof} 硬编码分支，而是拿激活类型实例回查它自己的注册表：
 * {@code Activation.REGISTRY.getKey(activation.codec())}。
 * 理由是这个注册表是数据驱动的 —— 注册项是各类型的静态 {@code MapCodec}，
 * {@code codec()} 每次返回同一实例，因此按 {@code equals}（等同按引用）能稳定反查到
 * {@code additional_abilities:optional_charged} 这样的 ID。
 * 这样<b>全部</b>激活类型（龙生内置三种 + 本模组两种 + 未来任何附属模组注册的类型）
 * 都自动获得这一行，不需要跟着上游增删而改代码。
 *
 * <h2>译名与兜底</h2>
 * 译名键沿用本模组既有的 {@code <类型>.{命名空间}.{路径}} 风格：
 * {@code activation_type.additional_abilities.optional_charged} —— 与
 * {@code trigger_type.additional_abilities.*} 同构，也与龙生自身的
 * {@code trigger_type.dragonsurvival.*} 一致。
 * <p>
 * 判断"译名是否存在"用的是 {@link Language}（<b>common</b> 类），而<b>不是</b>客户端专属的
 * {@code I18n}：本 Mixin 登记在 {@code mixins} 公共数组里，服务端同样要能链接到这个类，
 * 一旦方法体引用了 client 类，专用服务端在链接期就可能抛 {@code NoClassDefFoundError}。
 * {@code Language#getInstance()} 在两端都存在（服务端返回未加载任何语言的默认实例，
 * {@code has} 恒为 {@code false}），语义正确且无跨端风险。
 * <p>
 * 没有任何译名的第三方激活类型退回显示原始 ID（如 {@code some_mod:my_type}），
 * 不会把 {@code activation_type.x.y} 这种机器键名画到界面上。
 *
 * <h2>风险与对策</h2>
 * 本 Mixin 依赖 {@code getInfo} 的<b>方法签名</b>与"只有一个 return"这条事实。
 * DS 若重构该方法，{@code mixins.json} 的 {@code defaultRequire: 1} 会让它在加载期直接报错，
 * 而不是静默失效（不会出现"界面少一行但没人发现"的情况）。
 * 作用域严格限制在"往列表首行插一个组件"，不改动 DS 原有的任何一行取值逻辑。
 *
 * <h2>相关</h2>
 * 行格式键 {@code additional_abilities.gui.ability.activation_type}，
 * 五个译名键 {@code activation_type.*} 见两个语言文件；
 * 文档见 {@code doc/中文/04-激活类型.md} §「技能信息面板」。
 */
@Mixin(DragonAbility.class)
public abstract class DragonAbilityInfoMixin {
    /** 「信息」侧栏的行格式：{@code §6■ 激活类型：§r %s} */
    @Unique
    private static final String ACTIVATION_TYPE_LINE = "additional_abilities.gui.ability.activation_type";

    /** 激活类型译名键前缀：{@code activation_type.<命名空间>.<路径>} */
    @Unique
    private static final String ACTIVATION_TYPE_KEY_PREFIX = "activation_type.";

    @Inject(
            method = "getInfo(Lnet/minecraft/world/entity/player/Player;"
                    + "Lby/dragonsurvivalteam/dragonsurvival/registry/dragon/ability/DragonAbilityInstance;)"
                    + "Ljava/util/List;",
            at = @At("RETURN")
    )
    private void additional_abilities$showActivationType(final @NotNull CallbackInfoReturnable<List<Component>> callback) {
        List<Component> info = callback.getReturnValue();

        if (info == null) {
            return;
        }

        DragonAbility self = (DragonAbility) (Object) this;
        ResourceLocation id = Activation.REGISTRY.getKey(self.activation().codec());

        if (id == null) {
            // 理论上不会发生：激活类型只能经 RegisterEvent 进入该注册表。
            // 万一拿不到 ID，宁可不显示，也不显示一个残缺的行。
            return;
        }

        String translationKey = ACTIVATION_TYPE_KEY_PREFIX + id.getNamespace() + "." + id.getPath();

        Component value = Language.getInstance().has(translationKey)
                ? Component.translatable(translationKey)
                : Component.literal(id.toString());

        info.addFirst(Component.translatable(ACTIVATION_TYPE_LINE, value));
    }
}
