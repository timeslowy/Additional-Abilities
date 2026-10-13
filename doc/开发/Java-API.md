# Java API（给附属模组 / 扩展作者）

> 适用：Additional Abilities for DS `2.0.0` · Minecraft `1.21.1` · NeoForge · Dragon Survival `≥ 2.0.71`
> 相关：[README](../../README.md)（数据包字段手册索引）· [doc/中文](../中文/)（数据包用法）
> 本文面向**写 Java 的**扩展作者。只用数据包的话不需要看这里。

---

## 一、这个 API 承诺什么、不承诺什么

### 承诺稳定（`api` 包）

| 类型 | 用途 |
|---|---|
| `api.ChargedCasting` | 触发一次蓄力结算 / 查询档位 |
| `api.ChargedCastResult` | 结算结果（成功 / 取消 / 未接管 + 原因 + 档位） |
| `api.Domains` + `api.DomainInfo` | 领域的只读查询与按施法者清理 |
| `api.Visuals` | 方块发光与屏幕视觉的登记、刷新、清理 |
| `common.ability.entity_effects.ItemDurability` | 物品耐久的原版语义层（改多少 / 加减多少） |

这些类型改名、改签名或改语义会在更新日志里声明，并尽量避免破坏性变更。

### **不**承诺稳定（可以用，但别依赖）

- **网络协议类型**：`common.network` 下的各 `*Payload`，含它们的 `TYPE` / `STREAM_CODEC`
  以及两个哨兵常量 `ChargedReleasePayload.AUTO`(`-1`) / `CANCEL`(`0`)。
  它们描述的是**两端之间的字节格式**，不是给你调用的入口；
- **Mixin 与 DS 适配**：`mixins` 包、借道 DS 注册表注册的附件、`*Data` 系列可变容器。
  这些直接贴着 Dragon Survival 的实现，DS 一升级就可能重写；
- **附件实例与内部表**：例如 `AAAttachments.BLOCK_GLOW` 的 `BlockGlowData`、
  `DomainData` 的领域列表。请走上面的门面，不要自己 `level.getData(...)`；
- **客户端状态与渲染入口**：`client.state` / `client.renderer`；
- **调试指令的返回值与输出格式**。

### 通用契约

| 项 | 约定 |
|---|---|
| 逻辑端与线程 | 全部入口要求**服务端主线程**。它们会读写世界对象；网络包处理器请先 `context.enqueueWork(...)` |
| 逻辑端判定 | 依据是"是不是服务端对象"（`ServerLevel` / `ServerPlayer`），**不是**物理端。单人游戏里物理客户端同时跑着逻辑服务端 |
| 时间单位 | 全部为**游戏刻**（1 秒 = 20 刻） |
| 非法参数 | 一律"夹到合法区间"或"短路返回"，**不抛异常**（输入可能来自网络包）。唯一例外是构造期自检，见 `ChargedCastResult` |
| 集合所有权 | 返回的集合是**不可变快照**或只读视图；传入的集合不会被保存引用 |
| 世界生命周期 | 与维度绑定的状态随 `ServerLevel` 实例生灭，不跨存档复用 |
| 失败语义 | 会失败的入口返回可判定的结果，不靠日志或异常表达 |

### 事件回调的线程归属（给订阅游戏事件的作者）

平台**不保证**每个游戏事件的回调都在主线程上。已知的例外是本模组自己用到的两个：

| 事件 | 为什么可能不在主线程 |
|---|---|
| `GetEnchantmentLevelEvent` | 由 `EventHooks.getEnchantmentLevelSpecific` / `getAllEnchantmentLevels` 在**调用点直接 post**，不做线程切换 |
| `ItemTooltipEvent` | 由 `ItemStack#getTooltipLines` 直接 post，而该方法会被非主线程调用 |

非主线程的实际来源是**原版自己**：`SessionSearchTrees` 用
`CompletableFuture.supplyAsync(..., Util.backgroundExecutor())` 重建创造模式搜索索引，而
`SearchTree.plainText` 为了建后缀数组会**立即**求值传入的 tooltip 函数 ——
于是 `ItemStack#getTooltipLines` 就跑在 `Worker-Main-N` 上。触发时机包括打开 / 重建创造模式
标签页、收到配方与标签包、切换语言。

本模组对这类事件的契约是**「非主线程一律不结算」**：判定为不受支持的线程时静默返回空结果，
既不遍历实体表也不读附件。**不会抛异常，也不会排队回主线程** —— 事件要求当场返回结果
（`getEnchantmentLevelSpecific` 直接拿事件里改过的值当返回值），`enqueueWork` 保留不了查询语义。

> 可以观察到的代价：创造模式物品搜索索引里**不会**收录本模组附加的**临时**附魔名
> （物品自身的真附魔名照常入索引）。搜索索引只用于文本检索、不参与结算，因此这是有意的降级。

判定实现见 `common.ability.entity_effects.enchantment_bonus.EnchantmentBonusThreads`
（三态：服务端主线程 / 客户端游戏线程 / 不受支持）。**不要**用 `FMLEnvironment.dist`
或 `EffectiveSide` 判这件事：前者是物理端（单人游戏里同时跑着两侧），后者在本版本看的是
`SidedThreadGroup`，而 NeoForge 21.1.250 **从未用它创建过客户端的游戏线程**，
结果是一律 fallback 成 CLIENT —— 连 `Worker-Main-*` 都会被算作客户端。

---

## 二、蓄力：`ChargedCasting`

服务于 `additional_abilities:charged` 与 `additional_abilities:optional_charged`。

### 它替你消化了什么

底层实现（`ChargedCasts`）的签名是贴着网络协议写的：形参里有"客户端上报的蓄力时长"
（一个**不可信**的上限声明），档位还允许取 `AUTO`(`-1`) / `CANCEL`(`0`) 两个协议哨兵。
直接照那个签名调用，等于把自己的代码绑在私有协议上。

`ChargedCasting` 把这两件事就地消掉：

- **时长**一律取服务端自己的 `DragonAbilityInstance#getCurrentTick()`。
  服务端复算时本就与它取最小，所以直接传它等于声明"我不虚报"——
  这个入口**天然免疫**"谎报满时长换高档"那条路径；
- **档位**由业务语义表达（`release` / `releaseAt` / `cancel`），不出现哨兵。

### 用法

```java
import by.timeslowly.additional_abilities.api.ChargedCastResult;
import by.timeslowly.additional_abilities.api.ChargedCasting;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;

// instance 必须是"此刻正在施放"的那个实例
DragonAbilityInstance casting = MagicData.getData(player).getCurrentlyCasting();

if (casting != null) {
    ChargedCastResult result = ChargedCasting.release(player, casting);

    // handled() == false 表示"这次请求不该由本模组结算"，服务端什么都没做
    if (result.handled()) {
        switch (result.outcome()) {
            case RELEASED -> {
                int tier = result.releasedLevel();   // 实际释放的档位
                // 动作、初始魔力、冷却、观察者收尾都已经完成
            }
            case CANCELLED -> {
                // 已接管并按取消收尾：不执行动作、不扣初始魔力、不进冷却
                // 典型来源：服务端按真实时间复算后不足最低档
            }
            case REJECTED -> { /* 上面 handled() 已排除，此处不可达 */ }
        }
    }
}
```

### 三个终结入口

| 方法 | 语义 | 与手动松手的关系 |
|---|---|---|
| `release(player, instance)` | 按**已蓄到的档位**释放 | 等价于普通 `charged` 松手 |
| `releaseAt(player, instance, level)` | 请求按**指定档位**释放 | 请求值会被钳到已达成档位；`<= 0` 等同 `cancel` |
| `cancel(player, instance)` | 取消：不执行动作 / 不扣蓝 / **不进冷却** | 手动取消 |
| `releaseOvercharged(player, instance)` | 超限窗口到点的**自动**释放（最高档） | 与 `release` 共用同一份结算 |

> **越档请求不会被拒绝，只会降档。** 拒绝会让服务端停留在"施法中"（客户端早已停手），
> 技能卡在蓄力状态直到玩家再次操作——那比降档更糟。钳制只会把档位降下来，不构成越权放大。

> **`cancel` 为什么不能交给 DS 原生停止流程**：`isApplyingEffects()` 在"按过 `cast_time`
> 仍在蓄力"时会提前返回 `true`，原生路径据此误判为"效果已生效"，会凭空施加冷却。

### 查询（不产生结算）

```java
// 此刻松手会放几档
OptionalInt pending = ChargedCasting.pendingLevel(player, instance);
// empty = 没有可参照的档位（未在蓄力 / 尚未达最低档 / 类型不属于本族系）
// present(0) = 玩家已选定为取消（是一个有效答案）

// 正在蓄力 → 当前档位；否则 → 最近一次实际释放的档位（取消不写入）
int level = ChargedCasting.lastOrCurrentLevel(player, instance);
```

`pendingLevel` 用 `OptionalInt` 而不是哨兵，是因为"未指定"（内部 `-1`）与"选定为取消"
（`0`）是两个不同的事实——把前者也夹成 `0`，调用方就再也分不出来了。

### 自己实现一个蓄力组件

实现 `ChargeableActivation`（它继承 DS 的 `Activation`）即可获得全套档位换算：

```java
public record MyActivation(
        LevelBasedValue chargedDurationPerLevel,
        LevelBasedValue castTime,
        boolean canChargeExceedCastTime,
        Optional<LevelBasedValue> maxOverchargedDuration,
        Optional<LevelBasedValue> cooldown,
        Optional<LevelBasedValue> initialManaCost,
        // …其余字段见 ChargedActivation
) implements ChargeableActivation {
    // 只需声明字段 + CODEC + type()；阈值 / 上限 / 反查全部由接口 default 方法提供
}
```

**tick 值契约（三层一致）**：`cast_time` 与 `max_overcharged_duration` 的判据是
**"折算成整数刻之后 ≥ 1"**，不是"浮点值 > 0"。

| 层 | 行为 |
|---|---|
| Codec（数据包加载期） | `0.5` 这类值**直接报错**（它会被截断成 `0`，让蓄力过程退化） |
| Java 构造（绕过 Codec） | 不受解析期校验约束 |
| 运行时 `getCastTime(level)` | 一律 `Math.max(1, …)` 兜底 → 退化为"最短读条"，而不是"第 0 刻完成" |

解析期**只校验最低计算等级**：`LevelBasedValue` 允许在更高等级返回别的值（例如 `linear`
配负增量），逐级校验等于替数据包作者决定成长曲线，且会拒绝现有正常配置。

---

## 三、领域：`Domains`

```java
import by.timeslowly.additional_abilities.api.Domains;
import by.timeslowly.additional_abilities.api.DomainInfo;

// 某个维度里的全部存活领域（只读快照）
List<DomainInfo> all = Domains.of(level);

// 按施法者 / 按"施法者 + 技能"过滤
List<DomainInfo> mine = Domains.of(level, player.getUUID());
List<DomainInfo> specific = Domains.of(level, player.getUUID(), abilityKey);

// 跨维度清理（返回被移除的领域数）
int removed = Domains.clearByCasters(server, Set.of(uuid));
int removedOne = Domains.clearByCaster(server, uuid);
int removedPlayers = Domains.clearByCasters(players);   // 便捷重载
```

### 为什么给 `DomainInfo` 而不是内部对象

内部的 `DomainInstance` 是**活对象**：带 `join` / `advance` / `markRemoved` 这些改状态的方法，
而且 `DomainData` 每刻都在推它的 `expireTick` / `nextApplyTick`。直接返回等于让调用方拿到
一只"可以改、而且会自己变"的对象——既能越权续命 / 提前催结算，也无法安全地跨刻比较。

`DomainInfo` 是**当刻定格**的值对象：拿到之后不会再变，可以安全地遍历、比较、跨刻保存
（代价是它不反映后续变化，要最新的需重新调用）。

### 时间与坐标都是"该维度自己的轴"

`expireTick` / `nextApplyTick` 与所在 `ServerLevel#getGameTime()` 同轴；
`anchor` 是该维度的绝对坐标。**跨维度比较没有意义**。

### 为什么按"施法者"清理

领域对目标施加的效果来自任意 DS 效果类型（药水 / 属性修饰 / 函数…），
没有一份"这个领域给谁加过什么"的通用账本可供撤销。因此"清除领域"的可靠含义只能是
**移除领域本身**：此后不再结算，也不会再刷新效果；已经落在目标身上的限时效果按各自
`duration` 自然过期。

若技能配了 `remove_effects_on_end: true`，清理会走与"自然到期"完全相同的收尾路径，
因此也会对**当时仍在范围内**的目标执行一次移除。

### 生命周期

领域**不持久化**：只活在内存里，服务端重启即消失。这是有意的取舍
（把 DS 的效果配置编解码进存档会随 DS 版本变动而脆断）。
状态挂在 `ServerLevel` 的数据附件上，随实例回收——**不要**用 `static Map`
按维度 `ResourceKey` 分区：那会在换存档后串味（审查报告 F06）。

---

## 四、视觉：`Visuals`

```java
import by.timeslowly.additional_abilities.api.Visuals;
import by.timeslowly.additional_abilities.common.ability.block_effects.GlowDisplayType;
import by.timeslowly.additional_abilities.common.vision.ScreenVisionType;

// 方块发光：服务端权威，视距内所有玩家看到同一处
Visuals.glow(level, casterUuid, pos, 0xFF00FFFF, GlowDisplayType.OUTLINE, 60, true);

// 屏幕视觉：纯客户端状态，服务端不持副本
Visuals.screenVision(player, ScreenVisionType.SHAKE, 1.0F, 0.15F, 0xFFFFFF, 40);

// 清理
int glowRemoved = Visuals.clearGlowByCasters(server, Set.of(uuid));
int notified = Visuals.clearScreenVision(players);
```

### 不需要自己去重

两个效果都可以被高频重复触发（`area` 目标下每个方块位置每刻都判定一次，
`trigger_rate` 默认为 1）。因此：

- **发光**：同键续命，不新增条目；每 20 刻才重发一次；
- **屏幕视觉**：节流到每玩家每类型 5 刻最多一次，但**延长租期的请求必然放行**
  （否则会出现"先发 1 刻、再发 100 刻被丢弃"）。

直接按每次触发调用即可。

### 为什么"按施法者"清发光，而不是"按被影响玩家的画面"

条目在服务端按「位置 + 颜色 + 显示类型」**共享**（正是这一点让不同施法者的效果可以叠加），
因此服务端并不知道某条发光"属于谁的画面"。而且只清客户端的话，最多 20 刻后服务端就会重发回来。

清除时**只丢贡献、不丢整条**：某条发光还有别的玩家在贡献时原地保留。

### 活视图 vs 快照的分层

| 路径 | 返回 | 理由 |
|---|---|---|
| 对外查询（如 `DamageReflections#active`） | **不可变快照** | 低频调用（仅在受伤且非空时），契约最强 |
| `ClientBlockGlowState#entries()` | **活视图** | 每帧遍历（最多 4096 条），逐帧拷贝是真实的帧开销；调用方只有本模组渲染器，且同在主线程 |

判断标准是**调用频率与是否需要跨刻比较**：低频查询要快照，逐帧渲染要活视图。
`ClientBlockGlowState#entries()` 在源码里已明确标注为"内部渲染入口，不属于正式 API"。

---

## 五、耐久：`ItemDurability`

不含任何技能 JSON 概念，可被任何"消耗 / 修复耐久"的效果直接复用。

```java
import by.timeslowly.additional_abilities.common.ability.entity_effects.ItemDurability;

// 相对增减（正修负磨）；永不销毁物品，耐久钳在 [0, maxDamage]
boolean changed = ItemDurability.addDelta(level, holder, stack, EquipmentSlot.MAINHAND, -10, true);

// 绝对设置；breakItem = 结果耐久归零时是否按原版销毁
boolean set = ItemDurability.setDurability(holder, stack, EquipmentSlot.MAINHAND, 50, false);

// 完全按原版规则消耗（含「耐久」附魔减免、创造模式跳过、销毁 + 破损回调）
boolean consumed = ItemDurability.consumeWithVanillaRules(level, holder, stack, slot, 5);
```

⚠️ **1.21.1 没有"已损坏"状态**：`ItemStack` 不存在 `isBroken()`。耐久被钳到 0 的物品
**仍然可以正常使用**。

---

## 六、伤害类型：`AADamageTypes`

```java
import by.timeslowly.additional_abilities.registry.AADamageTypes;

ResourceKey<DamageType> key = AADamageTypes.COUNTER_SHOCK;              // 注册键，随时可用
Holder<DamageType> holder = AADamageTypes.counterShock(level.registryAccess()); // 取 Holder
```

### 为什么这里**没有** `DeferredHolder`

伤害类型是**纯数据包注册表**：1.21.1 的 `BuiltInRegistries` 里没有 `DAMAGE_TYPE` 这一项
（NeoForge 21.1.250 合并源码中该文件对 `damage` 零命中），只能由
`data/<命名空间>/damage_type/<名称>.json` 定义。

于是 `DeferredRegister` 对它是**无效**的：绑定逻辑只在 `RegisterEvent` 里按
`BuiltInRegistries.REGISTRY` 的注册表名单逐个派发，名单里没有 `damage_type`，那个事件就永远不发，
`DeferredHolder` 永远 `isBound() == false` ——`get()` 会抛
`IllegalStateException("Registry not present for ...")`。

这是一个"看起来能取、一取就炸"的公开字段，因此已改为直接持有 `ResourceKey`：
陷阱在结构上就不存在了，而取 `Holder` 只剩一条正确路径——从世界注册表查。

`counterShock(...)` 在条目缺失时返回 `null`（而不是抛异常）：
伤害结算路径上抛异常会把一次普通近战变成服务端崩溃，而"数据包没装 / 被改坏"应当退化为"不反伤"。

---

## 七、完整示例：一个不依赖内部实现的调用

下面这段只 import `api` 包、DS 的公开类型与 Minecraft 原生类型，**不碰**
payload、Mixin、`*Data` 容器或附件表：

```java
package com.example.myaddon;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.timeslowly.additional_abilities.api.ChargedCastResult;
import by.timeslowly.additional_abilities.api.ChargedCasting;
import by.timeslowly.additional_abilities.api.DomainInfo;
import by.timeslowly.additional_abilities.api.Domains;
import by.timeslowly.additional_abilities.api.Visuals;
import by.timeslowly.additional_abilities.common.ability.block_effects.GlowDisplayType;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.OptionalInt;

public final class MyAddon {
    /** 玩家一松手就按当前档位放出技能，并把结果告诉调用方。 */
    public static int releaseAndReport(ServerPlayer player) {
        DragonAbilityInstance casting = MagicData.getData(player).getCurrentlyCasting();

        if (casting == null) {
            return 0;
        }

        // 先看看会放几档（只读，不结算）
        OptionalInt pending = ChargedCasting.pendingLevel(player, casting);

        if (pending.isEmpty()) {
            return 0;
        }

        ChargedCastResult result = ChargedCasting.release(player, casting);

        if (!result.isReleased()) {
            return 0;   // 已按取消收尾，或本次请求未接管
        }

        return result.releasedLevel();
    }

    /** 把某个玩家留下的领域逐一亮成青色，然后清掉。 */
    public static void highlightThenClear(ServerPlayer player) {
        List<DomainInfo> domains = Domains.of(player.serverLevel(), player.getUUID());

        for (DomainInfo info : domains) {
            // followsCaster 为真时 anchor 不具参考意义（领域跟着人走）
            if (!info.followsCaster()) {
                Visuals.glow(player.serverLevel(), player.getUUID(),
                        net.minecraft.core.BlockPos.containing(info.anchor()),
                        0xFF00FFFF, GlowDisplayType.OUTLINE, 40, true);
            }
        }

        Domains.clearByCaster(player.getServer(), player.getUUID());
    }
}
```

---

## 八、与数据包 API 的关系

两侧是**同一批能力的两个入口**，不是两套实现：

- 数据包作者写 JSON，由本模组的效果类在 `apply` 里调用上面这些 `api` 门面；
- Java 作者可以直接调这些门面，或自己实现 `ChargeableActivation` / DS 的效果接口接入。

**数据包契约不变**：注册 ID、JSON 字段名、存档标记、指令路径与返回值都保持原样
（这是已确认的取舍）。本 API 的正式化只影响 Java 侧，不改任何现有数据包的行为。

---

## 九、已知边界

- **延迟型效果读不到档位**：生成实体后由该实体在后续游戏刻才去读 `ability.level()` 的效果，
  读到的是玩家自身的升级等级而非档位。对本窗口内立即结算的效果无影响；
- **领域效果是否真的落到方块 / 实体上，需要游戏内验收**：现有单元测试只覆盖状态机
  （哪些组被登记、被移除后是否还结算、收尾是否幂等），因为效果回调需要真实的
  `ServerPlayer` + `ServerLevel`，本机造不出来；
- **`Visuals` 的端到端观感**（声源落点、发光撤销、暂停行为）同样只有静态证据。
