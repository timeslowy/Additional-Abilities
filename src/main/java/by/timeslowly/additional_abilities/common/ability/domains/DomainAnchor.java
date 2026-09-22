package by.timeslowly.additional_abilities.common.ability.domains;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * 领域锚点（{@code additional_abilities:domain} 目标类型的 {@code anchor} 字段）
 * —— 决定「领域挂在哪里」。
 *
 * <table border="1">
 *     <caption>两种锚点</caption>
 *     <tr><th>取值</th><th>含义</th><th>适用激活类型</th></tr>
 *     <tr><td>{@link #CASTING_POSITION}</td>
 *         <td>首次结算瞬间的 <b>{@code dragon.position()}（脚部）</b>，此后<b>永久固定</b>。
 *             这是「领域」的本义——施法者可以走开，领域留在原地继续作用。</td>
 *         <td>{@code simple} / {@code channeled}（引导结束后余韵留在原地）</td></tr>
 *     <tr><td>{@link #CASTER}</td>
 *         <td>每 tick 结算时取<b>施法者当时的位置</b>，领域跟随移动。</td>
 *         <td>仅 {@code channeled} 有意义；{@code simple} 下等价于「每隔
 *             {@code apply_interval} 在施法者脚下重放一次 {@code area} 效果」</td></tr>
 * </table>
 *
 * <h2>为什么「续命」不改变锚点</h2>
 * {@code channeled} 会每 {@code trigger_rate} tick 调一次
 * {@link by.timeslowly.additional_abilities.registry.dragon.ability.targeting.DomainTarget#apply}。
 * 若每次都用"当时的位置"重新锚定，领域就会随施法者一跳一跳地迁移，
 * 既不符合语义、也让判定范围不可预期。因此：<b>同一条施法流内，锚点在首次结算时固化，
 * 后续续帧一律不改</b>（详见 {@link DomainInstance#refresh}）。
 */
public enum DomainAnchor implements StringRepresentable {
    CASTING_POSITION("casting_position"),
    CASTER("caster");

    public static final Codec<DomainAnchor> CODEC = StringRepresentable.fromEnum(DomainAnchor::values);

    /** 语言键前缀：{@code additional_abilities.gui.ability_target.domain.anchor.<序列化名>} */
    private static final String LANG_KEY_PREFIX = "additional_abilities.gui.ability_target.domain.anchor.";

    private final String name;

    DomainAnchor(final String name) {
        this.name = name;
    }

    @Override
    public @NotNull String getSerializedName() {
        return name;
    }

    /** 侧边栏显示用的语言键（如「施法位置」/「Casting Position」）。 */
    @Contract(pure = true)
    public @NotNull String translationKey() {
        return LANG_KEY_PREFIX + name;
    }

    /** 领域是否跟随施法者移动（{@link #CASTER}）。 */
    public boolean followsCaster() {
        return this == CASTER;
    }
}
