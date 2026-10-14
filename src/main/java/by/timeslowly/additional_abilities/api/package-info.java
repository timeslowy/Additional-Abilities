/**
 * Additional Abilities 面向 Java 扩展作者的<b>正式、稳定</b>接口面。
 *
 * <h2>这里承诺什么</h2>
 * 本包内的类型与方法是<b>对外契约</b>：改名、改签名、改语义都会在更新日志里声明，
 * 并尽量避免破坏性变更。承诺范围刻意只有四类已有能力：
 * <ol>
 *     <li><b>蓄力结算</b> —— {@link by.timeslowly.additional_abilities.api.ChargedCasting}
 *         与 {@link by.timeslowly.additional_abilities.api.ChargedCastResult}：以明确的结果类型
 *         触发一次"按档位释放 / 取消 / 超限自动释放"，并查询档位。
 *         蓄力组件本身的扩展契约是
 *         {@link by.timeslowly.additional_abilities.registry.dragon.ability.activation.ChargeableActivation}
 *         （第三方可实现它来提供自己的蓄力规则）；</li>
 *     <li><b>领域</b> —— {@link by.timeslowly.additional_abilities.api.Domains}：只读查询与按施法者清理；</li>
 *     <li><b>视觉</b> —— {@link by.timeslowly.additional_abilities.api.Visuals}：方块发光与屏幕视觉的登记、刷新与清理；</li>
 *     <li><b>耐久</b> ——
 *         {@link by.timeslowly.additional_abilities.common.ability.entity_effects.ItemDurability}：
 *         「把这只物品栈的耐久改成多少 / 加减多少」的原版语义层，不含任何技能 JSON 概念。</li>
 * </ol>
 *
 * <h2>这里不承诺什么</h2>
 * 以下都是<b>内部实现</b>，可以随时改动，不要依赖：
 * <ul>
 *     <li><b>网络协议类型</b> —— {@code common.network} 下的各 {@code *Payload}
 *         （含其 {@code TYPE} / {@code STREAM_CODEC} / 常量哨兵 {@code AUTO} / {@code CANCEL}）。
 *         它们描述的是<b>两端之间的字节格式</b>，不是给第三方调用的入口；</li>
 *     <li><b>Mixin 与 DS 适配</b> —— {@code mixins} 包、借道 DS 注册表注册的附件、
 *         以及 {@code *Data} 系列的可变容器。这些直接贴着 Dragon Survival 的实现，
 *         DS 一升级就可能重写；</li>
 *     <li><b>附件实例与内部表</b> —— 例如 {@code AAAttachments.BLOCK_GLOW} 的
 *         {@code BlockGlowData}、{@code DomainData} 的领域列表。
 *         请经本包的门面读写，不要自己去 {@code level.getData(...)}；</li>
 *     <li><b>客户端状态与渲染入口</b> —— {@code client.state} / {@code client.renderer}。
 *         其中 {@code ClientBlockGlowState#entries()} 等逐帧路径刻意返回<b>活视图</b>
 *         （见 {@link by.timeslowly.additional_abilities.api.Visuals} 的说明）；</li>
 *     <li><b>调试指令的返回值与输出格式</b>；</li>
 *     <li><b>数据包 JSON 契约</b> —— 那一侧另有文档（{@code doc/中文}、{@code doc/English}），
 *         且受"注册 ID 与字段名保持不变"的约束。</li>
 * </ul>
 *
 * <h2>通用契约</h2>
 * <ul>
 *     <li><b>逻辑端与线程</b>：本包全部入口都要求<b>服务端主线程</b>。
 *         它们会读取 / 改写世界对象（{@code ServerLevel}、{@code ServerPlayer}、附件），
 *         在工作线程上调用属于未定义行为。网络包处理器请照既有做法先
 *         {@code context.enqueueWork(...)}；</li>
 *     <li><b>事件回调的线程归属</b>：平台<b>不保证</b>每一个游戏事件的回调都发生在主线程上。
 *         已知的例外是 {@code GetEnchantmentLevelEvent} 与 {@code ItemTooltipEvent} ——
 *         它们由调用点直接 post，而原版重建创造模式搜索索引时会在 {@code Worker-Main-*} 上
 *         问到它们。本模组对这类事件的契约是
 *         <b>「非主线程一律不结算」</b>：判定为"不受支持的线程"时静默返回空结果，
 *         既不遍历实体表也不读附件 —— 不会抛异常，也不会排队到主线程（事件要求当场返回）。
 *         因此工作线程上查不到本模组附加的<b>临时</b>附魔等级，这是有意的降级；</li>
 *     <li><b>逻辑端归属</b>：判定依据是"这个对象是不是服务端对象"（{@code ServerLevel} /
 *         {@code ServerPlayer}），<b>不是</b>物理端。单人游戏里物理客户端同时跑着逻辑服务端；</li>
 *     <li><b>时间单位</b>：全部时长为<b>游戏刻</b>（1 秒 = 20 刻）；</li>
 *     <li><b>非法参数</b>：一律"夹到合法区间后继续"或"短路返回"，不抛异常
 *         （数据包来源的输入不可信，抛异常等于把攻击面扩大到崩溃）；
 *         唯一的例外是构造期自检，见 {@link by.timeslowly.additional_abilities.api.ChargedCastResult}；</li>
 *     <li><b>集合所有权</b>：返回的集合一律是<b>不可变快照</b>或只读视图，调用方不得假设其可变；
 *         传入的集合同样不会被本模组保存引用（需要保存时自行复制）；</li>
 *     <li><b>世界生命周期</b>：凡与维度绑定的状态都随 {@code ServerLevel} 实例生灭，
 *         不跨存档复用。<b>不要用静态表重新引入</b>：按 {@code ResourceKey} 分区的静态表会跨存档存活，
 *         使旧存档的到期时间污染新存档；</li>
 *     <li><b>失败语义</b>：会失败的入口都返回可判定的结果（布尔或
 *         {@link by.timeslowly.additional_abilities.api.ChargedCastResult}），不靠日志或异常表达。</li>
 * </ul>
 *
 * <h2>用法示例</h2>
 * 见开发文档 {@code doc/开发/Java-API.md}（含"只依赖本包"的完整调用样例）。
 * 一句话版本：
 * <pre>{@code
 * ChargedCastResult result = ChargedCasting.release(player, casting);
 *
 * if (result.isReleased()) {
 *     int tier = result.releasedLevel();
 * }
 * }</pre>
 */
package by.timeslowly.additional_abilities.api;
