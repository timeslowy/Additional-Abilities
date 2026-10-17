### 26.1.2-V2.0.0

> **本版为 Minecraft 26.1.2 适配版，与 1.21.1 分支的 `2.0.0` 不通用，请勿混装。**
> **This is the Minecraft 26.1.2 port; it is not interchangeable with `2.0.0` on the 1.21.1 branch.**

#### 中文

1. **适配 Minecraft `26.1.2` / NeoForge `26.1.2.88` / Java 25**（Gradle 9.6.1）；Dragon Survival 最低版本提升至 `2.1.5`；
2. `ResourceLocation` 全量迁移到 `Identifier`（含 `location()` → `identifier()`），共 71 处；
3. 渲染层按 26.1.2 的帧图管线重写：`RenderLevelStageEvent.Stage` 枚举删除（改为独立子事件）、`GlStateBackup` 与整套立即模式 GL 接口删除、`GuiGraphics` 改为 `GuiGraphicsExtractor`、`PostChain` 改为自建 `RenderPipeline`（半径每帧可变，而原版链的 uniform 只在加载时写死）；
4. 数据附件 `EnchantmentBonuses` 的序列化改到 NeoForge ValueIO（`ValueInput` / `ValueOutput`），与 DS 26.1.2 的 `Storage` 保持同构；
5. `PackFinders` 改用 `JarContents` + `JarContentsResourcesSupplier`（`IModFile#findResource` 与 `BuiltInPackSource#fromName` 均已删除）；KnownPack-free 的覆盖型数据包写法保持不变；
6. `pack.mcmeta` 改为 26.1 新格式（`min_format` / `max_format` = `[101, 1]`），mixin `compatibilityLevel` 提升到 `JAVA_25`；
7. 一批 26.1.2 的 API 收窄一并处理：`ServerPlayer#serverLevel()` → `level()`、`EntityType#create(Level)` 需显式 `EntitySpawnReason`、`CommandSourceStack#hasPermission(int)` → `Commands.hasPermission(LevelCheck)`、`BlockState#isSolidRender(level,pos)` → 无参、`Inventory#items` → `getNonEquipmentItems()`、`Item#getDescription()` → `getName(ItemStack)`、`Holder` 新增 `components()` / `areComponentsBound()`、`TagEntry.Lookup#element` 新增 `required` 形参、`GetEnchantmentLevelEvent#getStack` 放宽为 `ItemInstance`；
8. 单元测试 142 项全绿（`unitTest` 块在 MDG 2.0.143 + 26.1.2 下仍可用）。

#### English

1. **Ported to Minecraft `26.1.2` / NeoForge `26.1.2.88` / Java 25** (Gradle 9.6.1); minimum Dragon Survival raised to `2.1.5`;
2. Migrated every `ResourceLocation` to `Identifier` (including `location()` → `identifier()`), 71 sites;
3. Rendering rewritten against the 26.1.2 frame-graph pipeline: the `RenderLevelStageEvent.Stage` enum is gone (dedicated sub-events now), `GlStateBackup` and the whole immediate-mode GL surface are removed, `GuiGraphics` became `GuiGraphicsExtractor`, and `PostChain` was replaced by a self-registered `RenderPipeline` (the radius changes every frame, while vanilla chains bake their uniforms at load time);
4. `EnchantmentBonuses` serialization moved to NeoForge ValueIO (`ValueInput` / `ValueOutput`), mirroring Dragon Survival 26.1.2's `Storage`;
5. `PackFinders` now uses `JarContents` + `JarContentsResourcesSupplier` (`IModFile#findResource` and `BuiltInPackSource#fromName` were both removed); the KnownPack-free overriding datapack recipe is unchanged;
6. `pack.mcmeta` switched to the 26.1 format (`min_format` / `max_format` = `[101, 1]`), mixin `compatibilityLevel` raised to `JAVA_25`;
7. A batch of narrower 26.1.2 APIs handled: `ServerPlayer#serverLevel()` → `level()`, `EntityType#create(Level)` now needs an `EntitySpawnReason`, `CommandSourceStack#hasPermission(int)` → `Commands.hasPermission(LevelCheck)`, `BlockState#isSolidRender(level,pos)` → no-arg, `Inventory#items` → `getNonEquipmentItems()`, `Item#getDescription()` → `getName(ItemStack)`, `Holder` gained `components()` / `areComponentsBound()`, `TagEntry.Lookup#element` gained a `required` parameter, `GetEnchantmentLevelEvent#getStack` widened to `ItemInstance`;
8. All 142 unit tests pass (the `unitTest` block still works under MDG 2.0.143 + 26.1.2).

### 2.0.0

> **如你已在之前安装过此模组的`1.0.0`版本，务必认真阅读此条更新日志！！！此次更新为颠覆性的更新，请慎重考虑！！！**  
> **If you have installed version `1.0.0` of this mod, please read this update log seriously!!! This update is revolutionary, please consider prudently before updating!!!**

#### 中文

1. 将原有海洋龙和洞穴龙的额外技能的命名空间分别转至`sea_dragon`和`cave_dragon`下；
2. **更新了大量自定义技能组件，详见README；**
3. 新增森林龙技能「草木皆兵」（`forest_dragon:natural_alies`）及配套伤害类型 `forest_dragon:natural_force`；
4. 新增海洋龙技能「威霆显域」（`sea_dragon:lightning_domain`）—— 攻守兼备的范围领域；
5. 新增森林龙技能「光补作用」（`forest_dragon:photorepair`）—— 白日户外的被动修补；
6. 新增翼麒麟原创技能「安身法」（`wing_kirin:fire_ring`）—— 就地布下火圈阵法的阵地型保命技能，随 jar 常驻生效，不需要启用任何数据包；
7. **前置 Dragon Survival 的最低版本提升至 `2.0.71`**（原为 `2.0.68`）—— 本模组贴附了 Dragon Survival 的多处内部结构，低版本会直接拒绝加载；

#### English

1. Changed the namespace of Sea Dragon and Cave Dragon 's additional abilities separately to `sea_dragon`&`cave_dragon`
2. **New custom components abilitiy, please see README;**
3. New ability for Forest Dragon: 'Natural Allies'(`forest_dragon:natural_alies`) with the damage type `forest_dragon:natural_force`;
4. New ability for Sea Dragon: 'Lightning Domain'(`sea_dragon:lightning_domain`) - a two-sided area domain;
5. New ability for Forest Dragon: 'Photorepair'(`forest_dragon:photorepair`) - a passive repair working outdoors by day;
6. New original ability for Wing Kirin: 'Fire Ring'(`wing_kirin:fire_ring`) - a static, life-saving art that plants a ring of fire; it is always on and needs no datapack to be enabled;
7. **Minimum required Dragon Survival version raised to `2.0.71`** (was `2.0.68`) - this mod attaches to several Dragon Survival internals and older builds are rejected at load time;