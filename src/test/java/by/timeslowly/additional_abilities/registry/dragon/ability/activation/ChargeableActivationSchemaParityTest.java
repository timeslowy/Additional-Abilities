package by.timeslowly.additional_abilities.registry.dragon.ability.activation;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.activation.Notification;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T10：{@code charged} 与 {@code optional_charged} 的 <b>JSON schema 必须保持一致</b>。
 *
 * <h2>这条测试守的是什么</h2>
 * 两个类型的类注释都向数据包作者承诺「字段结构完全一致（同名、同类型、同必填性、同样禁
 * {@code looping}）」，而字段片段在 T10 之前是<b>各写一份</b>。任何一方单独改字段名或默认值，
 * 两端 schema 就会<b>静默漂移</b>：作者按手册写 {@code cooldown}，换一个 {@code activation_type}
 * 就解析失败或拿到别的默认值，而两边都能编译通过。
 * <p>
 * T10 把这些片段收敛到 {@link ChargeableActivationCodecs}，本类的作用就是把那条不变量
 * <b>变成断言</b>：断言写的是"两个 CODEC 编码同一个实例，JSON 必须逐字相同"。
 * 因此它不是"拿实现和自己比" —— 一旦有人把片段改回各写一份、并在其中一边写错字段名或默认值，
 * 这里必然转红（{@code encodeCharged != encodeOptional}）。
 *
 * <h2>为什么比对整棵 JSON 而不是逐个字段读</h2>
 * 逐个读字段只能验证"我关心的那几个字段一样"，而漂移的典型形态恰恰是<b>整个字段被漏掉或改名</b>：
 * 那种情况下读到的字段仍然相等，实现却已经坏了。比对整棵 JSON 覆盖字段的<b>存在与否</b>、
 * 顺序与取值，无遗漏。
 *
 * <h2>两者唯一允许的差异</h2>
 * {@code can_charge_exceed_cast_time} 的默认值：{@code charged} 为 {@code false}、
 * {@code optional_charged} 为 {@code true}。这是<b>有意</b>的（否则满档那一帧会被 DS 原生路径
 * 自动释放，玩家来不及选档）。
 * <p>
 * <b>这条差异在编码结果上比"值不同"更微妙</b>：{@code optionalFieldOf(name, default)} 在
 * <b>值等于默认值时不写出该字段</b>，因此同一个逻辑实例
 * （同样是"允许超限蓄力"）在两个 CODEC 下会编码出<b>不同的字段集</b> ——
 * {@code charged} 因为 {@code true != false} 而写出该字段，{@code optional_charged} 因为
 * {@code true == true} 而省略它。这是本轮实测确认的行为（首版测试把它误写成"两边应逐字相同"，
 * 当场转红暴露了这个假设错误）。
 * <p>
 * 因此本类的断言口径是：<b>剔除该字段后，两份编码必须逐字相同</b>。
 * 这既容忍了那条有意的差异，又保持了对"其余任何字段被改名 / 漏掉 / 改默认值"的区分力。
 *
 * <h2>为什么不触注册表</h2>
 * {@link LevelBasedValue#CODEC} 是 {@code either(Constant.CODEC, DISPATCH_CODEC)}：
 * <b>裸浮点数</b>走前者（纯值，无需注册表），带 {@code "type"} 的对象走后者（需要
 * {@code ENCHANTMENT_LEVEL_BASED_VALUE_TYPE} 注册表）。本类一律用 {@link LevelBasedValue#constant}
 * 与裸浮点 JSON，因此不需要任何 bootstrap，可在普通单元测试里运行。
 */
class ChargeableActivationSchemaParityTest {
    /** 现有正常配置的取值（取自本模组与内置数据包实际使用的量级） */
    private static final LevelBasedValue CHARGED_DURATION = LevelBasedValue.constant(10.0F);
    private static final LevelBasedValue CAST_TIME = LevelBasedValue.constant(60.0F);
    private static final LevelBasedValue OVERCHARGE = LevelBasedValue.constant(20.0F);
    private static final LevelBasedValue COOLDOWN = LevelBasedValue.constant(200.0F);
    private static final LevelBasedValue MANA_COST = LevelBasedValue.constant(2.0F);

    /** 只有两个必填字段（裸浮点形态） */
    private static final String MINIMAL_JSON = "{\"charged_duration_per_level\":10.0,\"cast_time\":60.0}";

    // ================================================================ schema 一致性

    @Test
    @DisplayName("两个 CODEC 的字段集只差 can_charge_exceed_cast_time 一项，其余必须逐字相同")
    void bothCodecsProduceIdenticalJsonApartFromTheOneIntentionalField() {
        assertIdenticalApartFromIntentionalField(charged(true), optional(true),
                "常规实例（可选字段填满、超限窗口开启）");
    }

    @Test
    @DisplayName("可选字段全部缺席时同样只差那一项（缺省形态也不能漂移）")
    void bothCodecsProduceIdenticalJsonForMinimalInstance() {
        assertIdenticalApartFromIntentionalField(minimalCharged(), minimalOptional(),
                "最小实例（只有两个必填字段）");
    }

    @Test
    @DisplayName("超限窗口关闭、can_move_while_casting 显式写出时，仍只差那一项")
    void bothCodecsAgreeWhenEveryOptionalFieldIsSet() {
        assertIdenticalApartFromIntentionalField(charged(false), optional(false),
                "可选字段填满、超限窗口关闭");
    }

    @Test
    @DisplayName("必填字段缺席时两端都拒绝（必填性一致）")
    void bothCodecsRejectMissingRequiredFields() {
        // 只有 cast_time，缺 charged_duration_per_level
        String json = "{\"cast_time\":60.0}";

        assertFalse(decodeCharged(json).result().isPresent(), "charged 应拒绝缺少必填字段的输入");
        assertFalse(decodeOptional(json).result().isPresent(), "optional_charged 应同样拒绝");
    }

    @Test
    @DisplayName("cast_time 的 tick 有效性校验对两端同样生效（含 T08 的 0.5 情景）")
    void castTimeValidationIsSharedByBoth() {
        String fractional = "{\"charged_duration_per_level\":10.0,\"cast_time\":0.5}";

        assertFalse(decodeCharged(fractional).result().isPresent(),
                "0.5 刻折算成 0 刻，charged 必须在解析期拒绝");
        assertFalse(decodeOptional(fractional).result().isPresent(),
                "同一条 cast_time 契约对 optional_charged 同样生效");
    }

    // ================================================================ 唯一允许的差异

    @Test
    @DisplayName("can_charge_exceed_cast_time 缺席时：charged 为 false、optional_charged 为 true")
    void theSingleIntentionalDifferenceIsPreserved() {
        ChargedActivation charged = decodeCharged(MINIMAL_JSON).result().orElseThrow();
        OptionalChargedActivation optional = decodeOptional(MINIMAL_JSON).result().orElseThrow();

        assertFalse(charged.canChargeExceedCastTime(),
                "charged 的默认值必须是 false：它由 DS 原生流程在 cast_time 处自动释放");
        assertTrue(optional.canChargeExceedCastTime(),
                "optional_charged 的默认值必须是 true：否则满档那一帧就被自动释放，玩家来不及选档");
        assertNotEquals(charged.canChargeExceedCastTime(), optional.canChargeExceedCastTime(),
                "这是两者唯一允许的 schema 差异，必须保留在明面上而不是被共享片段抹平");
    }

    @Test
    @DisplayName("字段等于自身默认值时不写出（DFU 语义）；因两者默认值不同，字段集才会不一样")
    void omittedWhenEqualToDefault() {
        // 逻辑上完全相同的两个实例：都"允许超限蓄力"
        String chargedJson = encodeCharged(minimalCharged());
        String optionalJson = encodeOptional(minimalOptional());

        assertTrue(chargedJson.contains("can_charge_exceed_cast_time"),
                "charged 的默认值是 false，而实例是 true → 值不等于默认值 → 必须写出该字段。实际：" + chargedJson);
        assertFalse(optionalJson.contains("can_charge_exceed_cast_time"),
                "optional_charged 的默认值就是 true，实例也是 true → 按 DFU 语义省略该字段。实际：" + optionalJson);

        // 关键：这份差异只影响"是否写出"，不影响解析出的语义 ——
        // 显式写出 true 的 charged 与省略该字段的 optional_charged，读出来都是"允许超限蓄力"
        assertTrue(minimalCharged().canChargeExceedCastTime(),
                "charged 实例的语义是'允许超限蓄力'");
        assertTrue(decodeOptional(optionalJson).result().orElseThrow().canChargeExceedCastTime(),
                "省略该字段后回落到默认值 true，语义仍是'允许超限蓄力'——两端语义一致，只有书写形式不同");
    }

    // ================================================================ 片段接对了 getter

    @Test
    @DisplayName("共享字段逐个核对：解析出的值落在正确的访问器上（片段接错 getter 会被抓到）")
    void sharedFieldsActuallyLandOnTheRightAccessors() {
        String json = "{\"charged_duration_per_level\":7.0,"
                + "\"cast_time\":42.0,"
                + "\"can_charge_exceed_cast_time\":true,"
                + "\"max_overcharged_duration\":11.0,"
                + "\"cooldown\":33.0,"
                + "\"initial_mana_cost\":5.0,"
                + "\"can_move_while_casting\":false}";

        ChrargedFields charged = fieldsOfCharged(decodeCharged(json).result().orElseThrow());
        ChrargedFields optional = fieldsOfOptional(decodeOptional(json).result().orElseThrow());

        assertFields(charged, "charged");
        assertFields(optional, "optional_charged");
    }

    private static void assertFields(final ChrargedFields fields, final String which) {
        assertEquals(7, (int) fields.chargedDurationPerLevel().calculate(1), which + "：charged_duration_per_level");
        assertEquals(42, (int) fields.castTime().calculate(1), which + "：cast_time");
        assertEquals(11, (int) fields.maxOverchargedDuration().orElseThrow().calculate(1),
                which + "：max_overcharged_duration");
        assertEquals(33, (int) fields.cooldown().orElseThrow().calculate(1), which + "：cooldown");
        assertEquals(5, (int) fields.initialManaCost().orElseThrow().calculate(1), which + "：initial_mana_cost");
        assertFalse(fields.canMoveWhileCasting(), which + "：can_move_while_casting 显式写成 false 时必须读到 false");
    }

    // ================================================================ 跨字段校验仍然生效

    @Test
    @DisplayName("cross-field 校验未被片段共享改动：开了窗口却没允许超限蓄力 → 两端都拒绝")
    void overchargeSupportValidationStillApplies() {
        String json = "{\"charged_duration_per_level\":10.0,"
                + "\"cast_time\":60.0,"
                + "\"can_charge_exceed_cast_time\":false,"
                + "\"max_overcharged_duration\":20.0}";

        assertFalse(decodeCharged(json).result().isPresent(),
                "max_overcharged_duration 以 can_charge_exceed_cast_time 为前提，charged 应拒绝");
        assertFalse(decodeOptional(json).result().isPresent(),
                "同一条跨字段校验对 optional_charged 同样生效");
    }

    // ================================================================ 夹具

    /** 只取本类要核对的共享字段，使两个 record 能用同一段断言 */
    private record ChrargedFields(LevelBasedValue chargedDurationPerLevel,
                                  LevelBasedValue castTime,
                                  Optional<LevelBasedValue> maxOverchargedDuration,
                                  Optional<LevelBasedValue> cooldown,
                                  Optional<LevelBasedValue> initialManaCost,
                                  boolean canMoveWhileCasting) {}

    private static ChrargedFields fieldsOfCharged(final ChargedActivation activation) {
        return new ChrargedFields(activation.chargedDurationPerLevel(), activation.castTime(),
                activation.maxOverchargedDuration(), activation.cooldown(),
                activation.initialManaCost(), activation.canMoveWhileCasting());
    }

    private static ChrargedFields fieldsOfOptional(final OptionalChargedActivation activation) {
        return new ChrargedFields(activation.chargedDurationPerLevel(), activation.castTime(),
                activation.maxOverchargedDuration(), activation.cooldown(),
                activation.initialManaCost(), activation.canMoveWhileCasting());
    }

    private static ChargedActivation charged(final boolean canChargeExceed) {
        return new ChargedActivation(CHARGED_DURATION, CAST_TIME, canChargeExceed,
                canChargeExceed ? Optional.of(OVERCHARGE) : Optional.empty(),
                Optional.of(COOLDOWN), Optional.of(MANA_COST),
                Notification.DEFAULT, false, Optional.empty(), Optional.empty());
    }

    private static OptionalChargedActivation optional(final boolean canChargeExceed) {
        return new OptionalChargedActivation(CHARGED_DURATION, CAST_TIME, canChargeExceed,
                canChargeExceed ? Optional.of(OVERCHARGE) : Optional.empty(),
                Optional.of(COOLDOWN), Optional.of(MANA_COST),
                Notification.DEFAULT, false, Optional.empty(), Optional.empty());
    }

    private static ChargedActivation minimalCharged() {
        return new ChargedActivation(CHARGED_DURATION, CAST_TIME, true, Optional.empty(),
                Optional.empty(), Optional.empty(), Notification.DEFAULT, true, Optional.empty(), Optional.empty());
    }

    private static OptionalChargedActivation minimalOptional() {
        return new OptionalChargedActivation(CHARGED_DURATION, CAST_TIME, true, Optional.empty(),
                Optional.empty(), Optional.empty(), Notification.DEFAULT, true, Optional.empty(), Optional.empty());
    }

    /**
     * 核心断言：两份编码<b>剔除 intentionally-different 的那一项之后</b>必须逐字相同。
     * <p>
     * 剔除而不是"期望它一定出现/一定不出现"：是否写出由 DFU 的
     * {@code optionalFieldOf(name, default)} 语义决定（值等于默认值即省略），
     * 而那条规则会随两个类型各自的默认值不同而给出不同结果。把这一点写进断言，
     * 等于把"这是唯一差异"这条不变量钉死；而其余任何字段的漂移都会让本断言转红。
     */
    private static void assertIdenticalApartFromIntentionalField(final ChargedActivation charged,
                                                                 final OptionalChargedActivation optional,
                                                                 final String scenario) {
        String chargedJson = stripIntentionalField(encodeCharged(charged));
        String optionalJson = stripIntentionalField(encodeOptional(optional));

        assertEquals(chargedJson, optionalJson,
                scenario + "：剔除 can_charge_exceed_cast_time 后，两个类型的 schema 必须逐字相同。"
                        + "若此断言转红，说明 T10 的片段共享被改回了各写一份，且其中一边已经漂移"
                        + "（字段名 / 默认值 / 有无该字段）");
    }

    /**
     * 去掉 {@code can_charge_exceed_cast_time} 这一项，使两份编码可比。
     * <p>
     * 该项在两侧<b>可能都不出现、也可能只出现一次</b>：DFU 的
     * {@code optionalFieldOf(name, default)} 在值等于默认值时不写出该字段，
     * 而两个类型的默认值不同，于是同一个逻辑值会得到"写"与"不写"两种结果。
     * 因此这里只做"有就删"，不断言它在不在 —— 那条语义由
     * {@code theSingleIntentionalDifferenceIsPreserved} 与
     * {@code omittedWhenEqualToDefault} 两条用例分别钉住。
     */
    private static String stripIntentionalField(final String canonicalJson) {
        com.google.gson.JsonObject object = JsonParser.parseString(canonicalJson).getAsJsonObject();
        object.remove("can_charge_exceed_cast_time");
        return canonical(object);
    }

    private static String encodeCharged(final ChargedActivation activation) {
        return encode(ChargedActivation.CODEC.codec(), activation);
    }

    private static String encodeOptional(final OptionalChargedActivation activation) {
        return encode(OptionalChargedActivation.CODEC.codec(), activation);
    }

    /**
     * 编码成"字段名有序"的规范文本。
     * <p>
     * 不直接比对 {@code toString()} 的输出顺序：{@code MapCodec} 的编码顺序由 DFU 内部决定，
     * 而本类要钉住的是<b>内容</b>（有哪些字段、各是什么值），不是书写顺序。
     * 把键排序后输出，既消除顺序噪声，又保持"整个字段被漏掉或改名就会转红"的区分力。
     */
    private static <T> String encode(final Codec<T> codec, final T value) {
        DataResult<com.google.gson.JsonElement> result = codec.encodeStart(JsonOps.INSTANCE, value);

        assertTrue(result.result().isPresent(),
                "编码应当成功，却被拒绝：" + result.error().map(DataResult.Error::message).orElse("<无错误信息>"));

        return canonical(result.result().orElseThrow());
    }

    /** 递归把 JSON 对象的键排序，使比较与书写顺序无关 */
    private static String canonical(final com.google.gson.JsonElement element) {
        if (element.isJsonObject()) {
            java.util.TreeMap<String, com.google.gson.JsonElement> sorted = new java.util.TreeMap<>();

            for (var entry : element.getAsJsonObject().entrySet()) {
                sorted.put(entry.getKey(), entry.getValue());
            }

            StringBuilder builder = new StringBuilder("{");
            boolean first = true;

            for (var entry : sorted.entrySet()) {
                if (!first) {
                    builder.append(',');
                }

                first = false;
                builder.append('"').append(entry.getKey()).append("\":").append(canonical(entry.getValue()));
            }

            return builder.append('}').toString();
        }

        if (element.isJsonArray()) {
            StringBuilder builder = new StringBuilder("[");

            for (int index = 0; index < element.getAsJsonArray().size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }

                builder.append(canonical(element.getAsJsonArray().get(index)));
            }

            return builder.append(']').toString();
        }

        return element.toString();
    }

    private static DataResult<ChargedActivation> decodeCharged(final String json) {
        return ChargedActivation.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static DataResult<OptionalChargedActivation> decodeOptional(final String json) {
        return OptionalChargedActivation.CODEC.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }
}
