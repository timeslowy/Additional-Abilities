package by.timeslowly.additional_abilities.registry;

import by.timeslowly.additional_abilities.AdditionalAbilities;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforgespi.language.IModInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 注册本模组内置的可选包（数据包 / 资源包）。
 *
 * <h2>如何新增一个内置包（先看这张决策表）</h2>
 * <table border="1">
 *     <tr><th>包的类型</th><th>是否覆盖了「某个模组自身已有的同名文件」</th><th>用哪个方法</th></tr>
 *     <tr><td>服务端数据包<br>（{@code data/…}）</td>
 *         <td><b>是</b> —— 例如覆盖 Wing Kirin 的 {@code dragon_ability} 定义</td>
 *         <td>{@link #addOverridingDataPack}</td></tr>
 *     <tr><td>服务端数据包<br>（{@code data/…}）</td>
 *         <td>否 —— 只<b>新增</b>新 id / 新文件，不与任何模组文件同名</td>
 *         <td>{@link #addSimplePack}（保留传输优化）</td></tr>
 *     <tr><td>客户端资源包<br>（{@code assets/…}）</td>
 *         <td>不适用 —— 纯客户端使用</td>
 *         <td>{@link #addSimplePack}（见下方说明）</td></tr>
 * </table>
 * <p>
 * 新增一个包的完整步骤：
 * <ol>
 *     <li>把包目录放进 {@code src/main/resources/}，例如
 *         {@code src/main/resources/data/<modid>/datapacks/<pack_name>/}；</li>
 *     <li>目录根部<b>必须</b>有 {@code pack.mcmeta}（含 {@code pack.pack_format}）与 {@code pack.png}；</li>
 *     <li>在 {@link #addPackFinders} 里加一行调用，第一个参数写
 *         {@code "data/<modid>/datapacks/<pack_name>"}（<b>必须与实际目录完全一致</b>）；</li>
 *     <li>在 {@code lang/en_us.json} 与 {@code lang/zh_cn.json} 里补上显示名语言键；</li>
 *     <li>{@code ./gradlew compileJava} 后进游戏，用 {@code /datapack list} 确认它出现。</li>
 * </ol>
 *
 * <h2>为什么「覆盖型」必须用 {@link #addOverridingDataPack}（源码 + 实机验证）</h2>
 * <b>刻意不使用 {@link AddPackFindersEvent#addPackFinders}，而是自己构造 {@link Pack}。</b>
 * 原因是一个会「静默失效」的同步陷阱（1.21.1 / NeoForge 21.1.250）：
 *
 * <h3>陷阱：KnownPack 优化会让客户端拿到「未覆盖」的定义</h3>
 * <ol>
 *     <li>DS 的 {@code dragonsurvival:dragon_ability} 是<b>可同步的 datapack registry</b>
 *         （{@code DataPackRegistryEvent.NewRegistry#dataPackRegistry(key, codec, networkCodec)}），
 *         客户端显示的技能定义全部来自服务端下发的 {@code ClientboundRegistryDataPacket}。</li>
 *     <li>服务端打包时（{@code RegistrySynchronization#packRegistries}）有一条「已知包」优化：
 *         若某元素的注册来源包被客户端声明为<i>已知</i>，则该元素
 *         <b>只发 id、不发数据</b>（{@code Optional.empty()}），由客户端用<b>本地资源包</b>解析。</li>
 *     <li>客户端的「已知包」仓库来自 {@code ServerPacksSource#createVanillaTrustedRepository()}，
 *         它内部调用 {@code ResourcePackLoader.populatePackRepository(repo, SERVER_DATA, true)}，
 *         会再次触发 {@link AddPackFindersEvent} —— <b>所以本数据包也在客户端的已知包名单里</b>。</li>
 *     <li>而 {@code mod_data}（装着所有模组数据的 required 包）的
 *         {@code PackLocationInfo.knownPackInfo} 是 {@code Optional.empty()}，
 *         <b>永远无法进入「已知包」名单</b>。于是客户端本地解析时，
 *         {@code PackRepository#rebuildSelected} 会把 {@code mod_data} 通过
 *         {@code Pack.Position.TOP.insert(...)} 追加到本数据包<b>之后</b>
 *         （{@code mod_data} 是 required 包，而 {@code Position.TOP} 的落点就是列表末尾）
 *         连同其隐藏子包（每个模组一个 {@code mod/<modid>}）一起排到末尾。</li>
 * </ol>
 * 净效果：<b>服务端资源管理器里本数据包在最后（覆盖生效），客户端本地解析时顺序被反转
 * （{@code mod_data} 里的模组原版数据获胜）。</b>表现为「数据包已启用、界面文案却完全没变」
 * 而功能层（服务端计算的部分：等级、效果时长、伤害、tag）一切正常 ——
 * 因为只有<b>客户端渲染</b>的部分取自客户端注册表。<b>没有任何日志或报错</b>。
 * <p>
 * <b>解法：不声明 KnownPack</b>（{@code Optional.empty()}）。该元素的
 * {@code RegistrationInfo.knownPackInfo} 随之也为空，第 2 步的优化不成立，
 * 服务端会把数据原样下发给客户端，两端一致。代价仅是「该注册表元素每次连接多传一点数据」，可忽略。
 *
 * <h3>为什么不直接调 addPackFinders</h3>
 * 那个便捷方法内部固定构造
 * {@code new KnownPack("neoforge", "mod/" + packLocation, version)}，无法去掉，故此处展开重写。
 *
 * <h3>为什么客户端资源包不受影响</h3>
 * {@code knownPackInfo()} 在全代码里只有 3 个使用点，全部属于
 * <b>服务端数据注册表的同步协商链</b>：
 * {@code ServerConfigurationPacketListenerImpl:91}（服务端请求名单）、
 * {@code KnownPacksManager:30}（客户端已知包表）、
 * {@code RegistrySynchronization:52}（跳过判定）。
 * 客户端资源包只在客户端使用，不参与这条链，故可以用简便 API。
 *
 * <p>
 * 另注：{@code alwaysActive} 保持 {@code false} —— 数据包默认是<b>可选</b>的，
 * 需要玩家在「数据包」界面手动启用（{@code PackSource.FEATURE} 不会自动启用）。
 * 位置保持 {@link Pack.Position#TOP}（= 已启用列表的末尾 = 最高优先级），
 * 这样它才能覆盖 {@code mod_data} 里 Wing Kirin 的原版技能。
 *
 * <h2>Wing Kirin 是可选依赖（2026-09-26 起）</h2>
 * {@code neoforge.mods.toml} 里 {@code wing_kirin} 已由 {@code required} 改为 {@code optional}：
 * 缺席时本模组其余内容照常工作，只有依赖它的部分被停用。停用分两处：
 * <ul>
 *     <li><b>本文件</b>：不注册 {@link #WINGKIRIN_ABILITIES_PACK}。若照旧注册，玩家一旦启用它，
 *         包内大量 {@code wing_kirin:} 粒子 / 药水效果 / 方块标签 / 贴图都会找不到，
 *         而 datapack registry 只要有一条元素解析失败，{@code RegistryDataLoader#load} 就会抛
 *         {@code IllegalStateException("Failed to load registries due to above errors")}，
 *         世界直接无法加载 —— 不是「跳过该条」。</li>
 *     <li><b>技能 JSON</b>：{@code additional_abilities:entity_marker} 与
 *         {@code additional_abilities:explosion_arrow} 带
 *         {@code "neoforge:conditions": [{"type": "neoforge:mod_loaded", "modid": "wing_kirin"}]}。
 *         它们的 {@code dragon_predicate.dragon_species} 直接引用 {@code dragonsurvival:wing_kirin}
 *         龙种（{@code RegistryFixedCodec} 解析失败同样致命），且 {@code usage_blocked} 本就只允许
 *         翼麒麟龙种使用，故条件跳过不损失任何功能。</li>
 * </ul>
 * 两者都用「模组是否加载」判定，客户端与服务端模组集合一致 → 条件求值结果一致，
 * 不会破坏注册表同步协商。
 */
@EventBusSubscriber(modid = AdditionalAbilities.MOD_ID)
public class PackFinders {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 覆盖 Wing Kirin 技能定义的数据包（必须与 {@code src/main/resources/} 下的实际目录一致）。 */
    private static final String WINGKIRIN_ABILITIES_PACK =
            "data/additional_abilities/datapacks/innovative_wingkirin_abilities";

    /** 可选依赖 Wing Kirin 的 modId；它缺席时 {@link #WINGKIRIN_ABILITIES_PACK} 不注册、也不出现在数据包列表里。 */
    private static final String WINGKIRIN_MOD_ID = "wing_kirin";

    @SubscribeEvent
    public static void addPackFinders(@NotNull AddPackFindersEvent event) {
        // Wing Kirin 是「可选依赖」：它缺席时本数据包内的技能定义会引用一堆只存在于 wing_kirin
        // 的注册表元素（自定义粒子、药水效果、方块标签、贴图），而 datapack registry 只要有一条元素
        // 解析失败，RegistryDataLoader#load 就会抛 IllegalStateException("Failed to load registries
        // due to above errors")，世界直接无法加载 —— 不是「跳过该条」。
        // 所以在源头就不注册它：玩家在「数据包」界面里既看不到也启用不了，存档里残留的启用记录也会
        // 随包缺席而被忽略（非致命）。
        // 注意本方法会按 PackType 各触发一次（客户端资源包 + 服务端数据包），日志只在数据包那次输出。
        if (!ModList.get().isLoaded(WINGKIRIN_MOD_ID)) {
            if (event.getPackType() == PackType.SERVER_DATA) {
                LOGGER.info("[PackFinders] 未检测到模组 {}，跳过注册内置数据包 {}"
                        + "（该数据包完全依赖 Wing Kirin 的内容）", WINGKIRIN_MOD_ID, WINGKIRIN_ABILITIES_PACK);
            }
            return;
        }

        // ① 覆盖型数据包：与别的模组（或本模组）已有的元素同名 → 必须走 known-pack-free 写法
        addOverridingDataPack(event, WINGKIRIN_ABILITIES_PACK,
                "datapack.additional_abilities.innovative_wingkirin_abilities");

        // ② 纯新增型数据包（只加新 id、不与任何模组文件同名）→ 简便写法即可，保留传输优化
        // addSimplePack(event, PackType.SERVER_DATA,
        //         "data/additional_abilities/datapacks/<new_pack>",
        //         "datapack.additional_abilities.<new_pack>", false);

        // ③ 客户端资源包（assets/…）→ 简便写法即可（不参与注册表同步协商）
        // addSimplePack(event, PackType.CLIENT_RESOURCES,
        //         "assets/additional_abilities/resourcepacks/<new_pack>",
        //         "resourcepack.additional_abilities.<new_pack>", false);
    }

    /**
     * 注册一个「会覆盖已有资源」的服务端数据包（可选，默认关闭）。
     *
     * @param path    包在模组资源中的路径，形如 {@code data/<modid>/datapacks/<name>}
     * @param nameKey 显示名语言键，如 {@code datapack.<modid>.<name>}
     */
    public static void addOverridingDataPack(
            @NotNull AddPackFindersEvent event, @NotNull String path, @NotNull String nameKey) {
        addOverridingDataPack(event, path, nameKey, false);
    }

    /**
     * 同上，但可指定 {@code alwaysActive}。
     * <p>
     * 注意：{@code alwaysActive = true} 会让玩家再也无法用 {@code /datapack disable} 关掉它，
     * 且它<b>并不能</b>替代已知包问题的修复（只是让它成为 required 包）。
     */
    public static void addOverridingDataPack(
            @NotNull AddPackFindersEvent event, @NotNull String path, @NotNull String nameKey, boolean alwaysActive) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return; // 覆盖型只可能是数据包；类型不匹配时静默跳过
        }

        IModInfo modInfo = modInfo();
        if (modInfo == null) {
            return;
        }

        Path resourcePath = modInfo.getOwningFile().getFile().findResource(path);

        // 关键：第 4 个参数（knownPackInfo）传 Optional.empty()，
        // 使本包无法参与「已知包」协商，从而让服务端始终下发本数据包覆盖后的注册表元素。
        PackLocationInfo locationInfo = new PackLocationInfo(
                "mod/" + ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, path),
                Component.translatable(nameKey),
                PackSource.FEATURE,
                Optional.empty()
        );

        Pack pack = Pack.readMetaAndCreate(
                locationInfo,
                BuiltInPackSource.fromName(p -> new PathPackResources(p, resourcePath)),
                PackType.SERVER_DATA,
                new PackSelectionConfig(alwaysActive, Pack.Position.TOP, false)
        );

        if (pack == null) {
            // pack.mcmeta 读取失败（路径错误 / 文件损坏）时 readMetaAndCreate 返回 null；
            // 若把 null 塞进 repository，会在 PackRepository#discoverAvailable 里 NPE，
            // 故这里显式拦下并给出可定位的报错。
            LOGGER.error("[PackFinders] 无法读取内置数据包 {} 的 pack.mcmeta，该数据包将不会被注册"
                    + "（检查目录路径是否为 resources 下的真实路径，以及 pack.mcmeta 是否完整）", resourcePath);
            return;
        }

        event.addRepositorySource(consumer -> consumer.accept(pack));
    }

    /**
     * 注册一个「只新增内容」的包（数据包或资源包），直接使用 NeoForge 的简便 API。
     * <p>
     * 适用于：不与任何模组自身文件同名的数据包、以及所有客户端资源包。
     * 这类包保留 {@code KnownPack} 传输优化不会造成两端不一致，
     * 因为客户端本地解析时总能找到<b>唯一</b>的那份定义。
     *
     * @param type         {@link PackType#SERVER_DATA} 或 {@link PackType#CLIENT_RESOURCES}
     * @param path         包在模组资源中的路径（{@code data/…} 或 {@code assets/…}）
     * @param nameKey      显示名语言键
     * @param alwaysActive 是否强制启用（{@code false} = 玩家手动启用）
     */
    public static void addSimplePack(
            @NotNull AddPackFindersEvent event, @NotNull PackType type, @NotNull String path,
            @NotNull String nameKey, boolean alwaysActive) {
        // 该方法内部会自行比对 packType，类型不符时是空操作
        event.addPackFinders(
                ResourceLocation.fromNamespaceAndPath(AdditionalAbilities.MOD_ID, path),
                type,
                Component.translatable(nameKey),
                PackSource.FEATURE,
                alwaysActive,
                Pack.Position.TOP
        );
    }

    @Nullable
    private static IModInfo modInfo() {
        var container = ModList.get().getModContainerById(AdditionalAbilities.MOD_ID).orElse(null);

        if (container == null) {
            LOGGER.error("[PackFinders] 找不到模组 {}，内置包不会被注册", AdditionalAbilities.MOD_ID);
            return null;
        }

        return container.getModInfo();
    }
}
