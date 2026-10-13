package by.timeslowly.additional_abilities.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagEntry;
import net.minecraft.tags.TagFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 / F23 的回归测试：{@code dragonsurvival:dragon_ability} 的两个标签里，
 * <b>三项依赖 Wing Kirin 的引用必须是可选（{@code required: false}）</b>。
 *
 * <h2>这条测试守的是什么</h2>
 * 三项条件性引用的定义都带 {@code neoforge:mod_loaded: wing_kirin} 条件：
 * <ul>
 *     <li>{@code wing_kirin:fire_ring} —— 由本模组在 {@code data/wing_kirin/} 下提供
 *         （<b>不是</b> WK 本体：已核对 {@code wing_kirin-3.3.0.jar}，其内无 {@code fire_ring}）；</li>
 *     <li>{@code additional_abilities:entity_marker} 与 {@code additional_abilities:explosion_arrow}
 *         —— 本模组自带，但 {@code dragon_predicate.dragon_species} 指向 {@code dragonsurvival:wing_kirin}。</li>
 * </ul>
 * WK 缺席时这三条技能定义被条件跳过、注册表里不存在，而引用它们的标签文件仍会加载 ——
 * 裸字符串在 NeoForge 1.21.1 的标签格式里等价于 {@code required: true}。
 *
 * <h2>为什么"必需引用缺失"的后果是整条标签失效</h2>
 * 已核对 1.21.1 的 {@code TagLoader#build}（{@code neoforge-21.1.250-sources.jar}）：
 * <pre>
 * return list.isEmpty() ? Either.right(List.copyOf(builder)) : Either.left(list);
 * </pre>
 * 只要有<b>一条</b>必需引用找不到，整条标签就走 {@code Either.left} 分支，
 * 调用点只记一条 {@code "Couldn't load tag … as it is missing following references"} 错误，
 * <b>不把标签写入结果</b>。所以这不是"跳过缺失的那一条"，而是<b>整条标签消失</b>。
 * <p>
 * 对 {@code order.json} 而言后果被放大：DS 的 {@code DragonAbilityScreen} 用
 * {@code getTag(ORDER).ifPresent(...)} 排序技能，标签缺失是<b>静默</b>的 ——
 * 界面不报错，只是所有技能（含六个正常条目）的排序一起失效。
 * <p>
 * 另一条被放大的路径：WK 的 {@code dragon_species/wing_kirin.json} 用
 * {@code "abilities": "#dragonsurvival:wing_kirin"} 引用本标签，而 DS 的
 * {@code MagicData#setSpecies} 遍历 {@code currentSpecies.value().abilities()} 建技能表 ——
 * 标签消失意味着<b>该龙种一个技能都没有</b>。
 *
 * <h2>断言口径</h2>
 * 本类<b>直接解析实际发布的资源文件</b>（走 classpath），而不是在测试里另抄一份期望值 ——
 * 后者只能证明"我抄对了"，改坏 JSON 时依然全绿。解析用的是原版
 * {@link TagFile#CODEC} 与 {@link TagEntry#build}，即生产线上的同一套代码，
 * 因此"可选 / 必需"的判定与游戏内完全一致。
 */
class DragonAbilityTagOptionalityTest {
    /** WK 缺席时会缺失的三项（条件性技能） */
    private static final Set<String> WING_KIRIN_DEPENDENT = Set.of(
            "wing_kirin:fire_ring",
            "additional_abilities:entity_marker",
            "additional_abilities:explosion_arrow");

    /** order.json 里必须保持必需（无 WK 条件、由本模组或 DS 无条件提供）的六项 */
    private static final List<String> ALWAYS_REQUIRED_IN_ORDER = List.of(
            "sea_dragon:extinguish_breath",
            "sea_dragon:lightning_domain",
            "cave_dragon:smoke_breath",
            "cave_dragon:piercing_eye",
            "forest_dragon:natural_alies",
            "forest_dragon:photorepair");

    private static final String TAG_DIR = "/data/dragonsurvival/tags/dragonsurvival/dragon_ability/";

    // ================================================================ 直接读取实际资源

    private static TagFile readTagFile(final String fileName) {
        try (InputStream stream = DragonAbilityTagOptionalityTest.class.getResourceAsStream(TAG_DIR + fileName)) {
            assertNotNull(stream, "找不到资源文件（它必须随主源集一起发布）：" + TAG_DIR + fileName);
            JsonElement json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));

            return TagFile.CODEC.parse(new Dynamic<>(JsonOps.INSTANCE, json)).getOrThrow();
        } catch (Exception exception) {
            throw new AssertionError("解析标签文件失败：" + fileName, exception);
        }
    }

    private static String idOf(final TagEntry entry) {
        return entry.getId().toString();
    }

    // ================================================================ F23：核心断言

    @Test
    @DisplayName("F23：wing_kirin 标签的三项 WK 条件性引用全部是 required=false")
    void wingKirinTagMarksConditionalEntriesOptional() {
        assertConditionalEntriesOptional("wing_kirin.json", readTagFile("wing_kirin.json"));
    }

    @Test
    @DisplayName("F23：order.json 的三项 WK 条件性引用全部是 required=false")
    void orderTagMarksConditionalEntriesOptional() {
        assertConditionalEntriesOptional("order.json", readTagFile("order.json"));
    }

    private static void assertConditionalEntriesOptional(final String fileName, final TagFile tagFile) {
        assertFalse(tagFile.replace(),
                fileName + " 必须保持 replace=false，否则会清空 DS/WK 已写入的同类技能");

        List<String> seen = new ArrayList<>();

        for (TagEntry entry : tagFile.entries()) {
            String id = idOf(entry);
            seen.add(id);

            if (WING_KIRIN_DEPENDENT.contains(id)) {
                assertFalse(entry.isRequired(),
                        fileName + " 中 " + id + " 依赖 Wing Kirin，必须写成 {\"id\": …, \"required\": false}；"
                                + "裸字符串等价 required=true，WK 缺席时会让【整条标签】加载失败（不只是跳过这一条）");
            }
        }

        assertEquals(WING_KIRIN_DEPENDENT.size(),
                seen.stream().filter(WING_KIRIN_DEPENDENT::contains).count(),
                fileName + " 应当仍然包含那三项条件性引用（本次修复只改必需性，不删条目）");
    }

    @Test
    @DisplayName("F23：order.json 的其余六项保持必需，且顺序未被本次修复打乱")
    void orderTagKeepsRemainingEntriesRequiredAndOrdered() {
        TagFile order = readTagFile("order.json");
        List<String> ids = order.entries().stream().map(DragonAbilityTagOptionalityTest::idOf).toList();

        // 六项「正常必需」条目按原顺序出现在三项条件性引用之后
        assertEquals(ALWAYS_REQUIRED_IN_ORDER, ids.subList(WING_KIRIN_DEPENDENT.size(), ids.size()),
                "其余六项的顺序与内容必须原样保留：它们由本模组或 DS 无条件提供，不需要放宽必需性");

        for (TagEntry entry : order.entries()) {
            if (ALWAYS_REQUIRED_IN_ORDER.contains(idOf(entry))) {
                assertTrue(entry.isRequired(),
                        idOf(entry) + " 没有 WK 条件，必须保持必需（放宽它会让真正的缺失被静默吞掉）");
            }
        }
    }

    // ================================================================ 语义：用生产代码验证后果

    @Test
    @DisplayName("F23：WK 缺席时两个标签都能加载成功（不再整条消失）")
    void bothTagsSurviveWingKirinBeingAbsent() {
        // 模拟 WK 缺席：只有六项无条件条目可解析，三项条件性引用取不到
        TagEntry.Lookup<Object> wkAbsent = lookupResolving(ALWAYS_REQUIRED_IN_ORDER::contains);

        for (String fileName : List.of("wing_kirin.json", "order.json")) {
            assertTrue(buildsWithoutMissingReferences(readTagFile(fileName), wkAbsent),
                    fileName + " 在 WK 缺席时必须仍能加载：三项可选引用在取不到时返回 !required 即视为成功。"
                            + "若此断言转红，说明有人把 required=false 改回了裸字符串，整条标签会从注册表里消失");
        }

        // 前提自检：该替身确实让三项条件性引用解析失败，否则上面两条断言是空测试
        assertFalse(wkAbsent.element(ResourceLocation.parse("wing_kirin:fire_ring")) != null,
                "替身必须让 wing_kirin:fire_ring 解析失败，否则本用例测不到东西");
        assertFalse(wkAbsent.element(ResourceLocation.parse("additional_abilities:entity_marker")) != null,
                "替身必须让 additional_abilities:entity_marker 解析失败");
        assertFalse(wkAbsent.element(ResourceLocation.parse("additional_abilities:explosion_arrow")) != null,
                "替身必须让 additional_abilities:explosion_arrow 解析失败");
    }

    @Test
    @DisplayName("必需引用缺失 → 标签整体失败（证明这条契约不是'跳过缺失项'）")
    void requiredMissingReferenceFailsTheWholeTag() {
        TagFile tagFile = readTagFile("order.json");

        // 一切都不存在的替身：若三项仍是必需的，整条标签必然失败
        assertFalse(buildsWithoutMissingReferences(tagFile, lookupResolving(name -> false)),
                "若有必需引用取不到，整条标签就不该被注册（这正是 F23 要避免的后果）");

        // 六项必需引用齐全、三项可选引用缺失时：因为已改为可选，必须成功
        assertTrue(buildsWithoutMissingReferences(tagFile, lookupResolving(ALWAYS_REQUIRED_IN_ORDER::contains)),
                "六项必需引用齐全、三项可选引用缺失时，标签必须能加载");
    }

    /**
     * 用原版 {@link TagEntry#build} 判定"这条标签能否加载成功"。
     * <p>
     * 与 {@code TagLoader#build} 的判据逐字对应：只要<b>任意一条</b> {@code build} 返回 {@code false}
     * （即必需引用缺失），整条标签就失败；全部返回 {@code true} 才算成功。
     * <p>
     * 替身的元素类型取 {@code Object} 且只实现"具名元素"这一路（本模组这几个标签都不含
     * {@code #tag} 形式的嵌套引用），因此不触任何注册表。
     */
    private static boolean buildsWithoutMissingReferences(final TagFile tagFile, final TagEntry.Lookup<Object> lookup) {
        List<Object> collected = new ArrayList<>();

        for (TagEntry entry : tagFile.entries()) {
            if (!entry.build(lookup, collected::add)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 构造一个"只认某些 id"的 {@link TagEntry.Lookup} 替身。
     *
     * @param resolvable 该 id 是否存在于注册表；返回 {@code false} 即"缺失"
     */
    private static TagEntry.Lookup<Object> lookupResolving(final Predicate<String> resolvable) {
        return new TagEntry.Lookup<>() {
            @Override
            public Object element(final ResourceLocation id) {
                return resolvable.test(id.toString()) ? new Object() : null;
            }

            @Override
            public Collection<Object> tag(final ResourceLocation id) {
                // 本模组这几个标签只用具名元素，不含 #tag 嵌套引用
                return null;
            }
        };
    }

    // ================================================================ 反向：防止判据恒真

    @Test
    @DisplayName("对照：本类确实能识别出'裸字符串 = 必需'这一形态")
    void bareStringParsesAsRequired() {
        JsonElement json = JsonParser.parseString("{\"values\":[\"wing_kirin:fire_ring\"]}");
        TagFile tagFile = TagFile.CODEC.parse(new Dynamic<>(JsonOps.INSTANCE, json)).getOrThrow();

        assertEquals(1, tagFile.entries().size());
        assertTrue(tagFile.entries().getFirst().isRequired(),
                "裸字符串必须解析为 required=true —— 这是 NeoForge 1.21.1 的标签格式契约，也是 F23 的成因。"
                        + "若此断言转红，说明 TagEntry.CODEC 的语义变了，本类需要重新评估");

        JsonElement optional = JsonParser.parseString(
                "{\"values\":[{\"id\":\"wing_kirin:fire_ring\",\"required\":false}]}");
        TagFile optionalFile = TagFile.CODEC.parse(new Dynamic<>(JsonOps.INSTANCE, optional)).getOrThrow();

        assertFalse(optionalFile.entries().getFirst().isRequired(), "显式 required=false 必须解析为可选");
    }
}
