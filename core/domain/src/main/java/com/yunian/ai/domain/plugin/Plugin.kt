package com.yunian.ai.domain.plugin

/**
 * 双层插件模型契约（对齐 Cordis 插件生态模板的投影，零 Android 依赖）。
 *
 * 模板对应关系：
 * - [PluginManifest]  ← Cordis package.json（id/name/version/依赖/toolsets）
 * - [LianYuPlugin]    ← Cordis 插件契约（`name` + `inject` + `apply(ctx)`）
 * - [PluginContext]   ← Cordis Context 最小面（provide/inject/effect/on/emit 五种派发模式）
 * - [PluginHost]      ← Cordis 宿主（注册/装载/卸载/生命周期）
 * - [PluginServices]  ← 框架服务键（对应 Cordis 宿主提供的 ctx.<service>）
 * - [PluginEventResult] / [PluginEventListener] ← Cordis 事件派发契约（bail 值 + 监听器签名）
 *
 * 双层模型：
 * 1. 代码插件（builtin）：实现 [LianYuPlugin]，编译期打进 APK，与壳/加固体系共存；
 * 2. 配置插件（runtime）：蓝图文件组合内置代码插件与内置能力，不执行任意代码。
 *
 * 消息通道地基（P1）：本文件已把 [PluginKind.ADAPTER]（消息通道适配器）与
 * [PluginKind.PIPELINE]（回合后处理管道）的**语义与契约**固定下来，宿主按 [PluginKind]
 * 校验与分派；P1 **不包含任何真实通道或管道的实现**——通道的收发能力、管道的分段/气泡/
 * 降级策略均由后续阶段以插件形态接入，本层只保证「一个通道一个插件、管道可组合可替换」
 * 的装载契约不被破坏。
 */

/**
 * 插件类别（对齐 Cordis 生态的插件定位）。
 *
 * 宿主按本类别做校验与分派（[PluginHost.pluginsOf]）；类别只决定**装配语义**，
 * 不决定插件能注入哪些服务（那由 [LianYuPlugin.requires] 声明）。
 *
 * - [TOOL]：工具集插件。装配期把 [com.yunian.ai.domain.AiTool] 注册进 ToolRegistry，
 *   卸载时逐工具注销（工具名即对外契约，迁移/重构不得改变）。
 * - [SKILL]：技能插件。装配期写入/接入技能资产（如内置聊天工具协议技能），幂等。
 * - [STICKER]：表情包插件。装配期接线表情包偏好引擎（存储钩子 / 首启同步），幂等。
 * - [ADAPTER]：**消息通道适配器**——一个消息通道一个插件。
 * - [PIPELINE]：**回合后处理管道**——分段 / 气泡 / 表情 / 降级策略，可组合、可替换。
 */
enum class PluginKind { TOOL, SKILL, STICKER, ADAPTER, PIPELINE }

/**
 * 插件清单（对应 Cordis package.json 的投影）。
 *
 * 清单是插件的**声明式身份**：宿主在注册（[PluginHost.register]）时用它校验插件自描述
 * 是否自洽（id / name / kind / requires / configSchema / description 必须与 [LianYuPlugin]
 * 的同名成员一致），在装载（[PluginHost.load]）时用它判定依赖缺失并 **fail-closed** 拒绝装载。
 * 清单不参与运行期行为：它不改变 [LianYuPlugin.setup] 的装配语义，也不参与版本解析。
 */
data class PluginManifest(
    val id: String,
    val name: String,
    /** 清单版本（对齐 Cordis package.json 的 version）。仅用于自描述/诊断，不参与依赖解析。 */
    val version: String,
    val kind: PluginKind,
    /**
     * 依赖的框架服务键（对齐 Cordis `inject`）。
     *
     * **fail-closed**：任一键不在宿主预置框架服务中，宿主拒绝装载该插件并给出明确原因；
     * 不允许「缺依赖照装、运行期再抛」。
     */
    val requires: List<String>,
    /** 归属工具集（对齐 Hermes TOOLSETS / cordis toolsets）。 */
    val toolsets: List<String> = emptyList(),
    /**
     * 面向用户的**一句话说明**（「插件设置」页的行副标题就是它）。
     *
     * 与 [LianYuPlugin.description] **必须逐字一致**——宿主在 [PluginHost.register] 与
     * [PluginHost.load] 两处都做逐字比对，不一致即 **fail-closed** 拒绝注册 / 装载
     * （与 id / name / kind / requires / configSchema 同一套语义）。
     * 空串 = 未声明说明；此时设置页把行副标题回落为插件 id，不显示空行。
     *
     * ⚠️ **参数位置**：它刻意排在 [toolsets] 与 [configSchema] 之间，且**带默认值**，
     * 因此既有实现方无论用位置参数还是具名参数构造清单都**源码级兼容**
     * （先例见 [PluginContext] 的契约扩展兼容性说明）。
     */
    val description: String = "",
    /** 配置 JSON Schema 文本；null = 无配置。校验失败拒绝加载（fail-closed）。 */
    val configSchema: String? = null,
)

/**
 * 事件派发结果（对应 cordis-rs 0.6.2 的 `EventResult = Result<Option<EventValue>>`）。
 *
 * 语义**逐条对齐** `cordis-rs-0.6.2/src/events.rs:23` 的 `is_bailed`：
 * `Some(value)` 即 bail（`value` 可以是任何值，含 `Unit` / `null`），`None` 表示继续派发。
 * Rust 用 `Option` 取代 JavaScript 的 truthiness——**不是**「返回值非空才 bail」，
 * 因此本类的 bail 标志与 bail 值是两个独立字段：`EventResult.bail(null)` 仍然是 bail。
 *
 * 用途：仅 [PluginEventListener] / [PluginWaterfallListener] 的返回值有意义；
 * [PluginContext.emit] / [PluginContext.parallel] 忽略返回值，[PluginContext.serial] /
 * [PluginContext.bail] / [PluginContext.waterfall] 用它决定是否停止后续派发。
 */
class PluginEventResult private constructor(
    /** 是否已 bail（对齐 `Option::is_some`）。 */
    val isBailed: Boolean,
    /** bail 携带的值；[isBailed] 为 false 时恒为 null。 */
    val bailValue: Any?,
) {
    override fun toString(): String =
        if (isBailed) "EventResult.bail($bailValue)" else "EventResult.none"

    companion object {
        /** 未 bail（对齐 `Ok(None)`）：继续派发。 */
        val none: PluginEventResult = PluginEventResult(isBailed = false, bailValue = null)

        /**
         * bail 并携带值（对齐 `Ok(Some(value))`）：停止后续派发。
         *
         * [value] 允许为 null——`bail(null)` 与 [none] 不是同一件事（见类注释）。
         */
        fun bail(value: Any?): PluginEventResult = PluginEventResult(isBailed = true, bailValue = value)
    }
}

/**
 * 可返回 bail 值的事件监听器（对齐 cordis-rs 的 `Fn(Event) -> EventResult`）。
 *
 * 返回值约定：
 * - 返回 null → 未 bail，继续派发（最省事的写法：把返回值当「无 bail」）；
 * - 返回 [PluginEventResult] → 按其 [PluginEventResult.isBailed] 判定；
 * - 返回其他任何非 null 值 → 视为 `bail(value)`。
 *
 * 该便利映射只覆盖「返回 null = 不 bail」这一种情形；需要 **bail 携带 null** 或需要
 * 返回更复杂结果时，显式返回 [PluginEventResult.bail] / [PluginEventResult.none]。
 */
fun interface PluginEventListener {
    fun onEvent(event: Any): Any?
}

/**
 * waterfall 派发中交给监听器的「继续」回调（对齐 cordis-rs 的 `Event::call_next`）。
 *
 * 调用它才会执行链上的下一个监听器；链尾之后是 [PluginContext.waterfall] 传入的 inner 回调。
 * 不调用它即「短路」——inner 与后续监听器都不会被执行。
 */
fun interface PluginEventNext {
    /** 执行下一个监听器（或 inner）；返回其结果。 */
    fun next(): PluginEventResult
}

/**
 * waterfall 监听器（对齐 cordis-rs 的 `Fn(Event) -> EventResult` + `Event::call_next`）。
 *
 * @param next 继续回调；调用 [PluginEventNext.next] 才会往下走。
 */
fun interface PluginWaterfallListener {
    fun onEvent(event: Any, next: PluginEventNext): PluginEventResult
}

/**
 * 插件装配上下文（对应 Cordis Context 的最小投影）。
 *
 * - [provide]/[inject]：服务注入（依赖图由宿主预置框架服务 + 插件运行期提供）；
 * - [effect]：可逆副作用——卸载时按注册逆序执行（Cordis「卸载不留鸡毛」语义）；
 * - [on]/[onBail]/[onWaterfall]：事件订阅（dispose 时自动退订）；
 * - [emit]/[parallel]/[serial]/[bail]/[waterfall]：Cordis 五种事件派发模式。
 *
 * ## 事件总线是**宿主级共享**的
 *
 * 订阅表**不是**每个上下文私有的：同一宿主装载的全部插件共用一张监听表，因此
 * 插件 A [emit] 的事件能被插件 B 的 [on] 收到。契约层只承诺「同一个宿主实例内共享」；
 * 表的持有者与并发模型由实现决定（core:agent 的 `PluginHostImpl` 持有单一总线）。
 *
 * ## 派发模式（语义对齐 cordis-rs 0.6.2 `Context::emit/parallel/serial/bail/waterfall`）
 *
 * | 模式 | 签名 | 语义 |
 * |------|------|------|
 * | [emit] | 同步 | 同步依次调用全部监听器，**忽略返回值**，全部调用完才返回 |
 * | [parallel] | suspend | 全部监听器**并发**执行，等待全部结束，失败聚合上报 |
 * | [serial] | suspend | 顺序 await，**遇 bail 值停止**，返回该 bail 结果 |
 * | [bail] | 同步 | 同 serial 的同步版：顺序调用，遇 bail 值停止 |
 * | [waterfall] | 同步 | 把监听器按注册顺序**包在 inner 回调外面**（首个监听器在最外层） |
 *
 * 返回值约定：全部模式都返回 [PluginEventResult]；[emit]/[parallel] 的返回值恒为
 * [PluginEventResult.none]（它们不短路），[serial]/[bail]/[waterfall] 返回实际结果。
 *
 * 异常约定（与 cordis-rs 对齐）：监听器抛出的异常**不中断** [emit] / [parallel] / [bail] /
 * [waterfall] 的派发，由实现记录日志后继续；[serial] 会把它作为失败向上抛
 * （Rust 侧 `?` 传播）。[parallel] 的全部失败聚合为一个
 * [PluginDispatchException]（对齐 `EventsService::parallel` 的 `{n} event listener(s) failed`）。
 *
 * ## 协程与线程
 *
 * [parallel] / [serial] 是 suspend 函数：并发由**协程**表达（`async` + `awaitAll`），
 * 不阻塞调用线程；[emit] / [bail] / [waterfall] 保持同步，与 Rust 侧的 `block_on` 语义对应。
 * [parallel] 的实现必须让监听器**真的并发**——实现须显式切到多线程调度器
 * （core:agent 的实现用 `Dispatchers.Default`）：只写 `async` 而继承调用方上下文时，
 * 在单线程调度器（Android 主线程 / `runBlocking`）上监听器会被排到同一线程顺序执行，
 * 「并发」就退化成 [emit]。总线本身必须支持多线程访问（订阅/退订/派发的线程安全）。
 *
 * ## 契约扩展的兼容性
 *
 * 本接口在 P3-3a 之前只有 provide/inject/effect/on 四个成员，仓库外可能已有实现方。
 * 因此本批新增的成员**都带默认实现**（抛 [UnsupportedOperationException]）：
 * 老实现方**源码级与二进制级都能继续编译**，只是调用新 API 会立刻抛异常而不是静默丢弃事件
 * ——fail-closed，不制造「订阅了却永远收不到」的假象。新实现方（含 core:agent 的
 * `PluginContextImpl`）必须覆写全部新增成员。
 */
interface PluginContext {
    /** 提供服务（插件运行期可用，覆盖宿主预置的同名服务）。 */
    fun <T : Any> provide(key: String, service: T)

    /** 按键取服务；缺失抛 [IllegalStateException]（fail-fast）。 */
    fun <T : Any> inject(key: String): T

    /** 注册可逆副作用；卸载/装配失败回滚时逆序执行。 */
    fun effect(disposer: () -> Unit, label: String)

    /** 订阅事件（仅观察，返回值被忽略）；卸载时自动退订。 */
    fun on(event: String, handler: (Any) -> Unit)

    /**
     * 订阅可返回 bail 值的事件；卸载时自动退订。
     *
     * 供 [serial] / [bail] / [waterfall] 派发使用——[on] 的监听器返回值被忽略，
     * 无法参与 bail 短路。返回值语义见 [PluginEventListener]。
     */
    fun onBail(event: String, handler: PluginEventListener) {
        throw unsupported("onBail")
    }

    /**
     * 订阅 waterfall 监听器（可调用 [PluginEventNext.next] 决定是否继续）；卸载时自动退订。
     */
    fun onWaterfall(event: String, handler: PluginWaterfallListener) {
        throw unsupported("onWaterfall")
    }

    /**
     * **同步广播**：同步依次调用全部监听器，忽略返回值，全部调用完才返回。
     *
     * 对齐 cordis-rs `Context::emit`（`context.rs:476` → `EventsService::emit`）。
     * 单个监听器抛异常不影响其余监听器（实现记日志后继续）。
     */
    fun emit(event: String, payload: Any) {
        throw unsupported("emit")
    }

    /**
     * **并发派发**：全部监听器并发执行并等待全部结束。
     *
     * 对齐 cordis-rs `Context::parallel`（`context.rs:485`）。返回值恒为
     * [PluginEventResult.none]（不短路）；失败聚合为 [PluginDispatchException]。
     */
    suspend fun parallel(event: String, payload: Any): PluginEventResult {
        throw unsupported("parallel")
    }

    /**
     * **顺序派发 + bail 短路**：顺序 await 每个监听器，遇 bail 值立即停止并返回它。
     *
     * 对齐 cordis-rs `Context::serial`（`context.rs:494`）。全部监听器都不 bail 时返回
     * [PluginEventResult.none]。
     */
    suspend fun serial(event: String, payload: Any): PluginEventResult {
        throw unsupported("serial")
    }

    /**
     * **同步顺序派发 + bail 短路**：同 [serial] 的同步版。
     *
     * 对齐 cordis-rs `Context::bail`（`context.rs:503`）。
     */
    fun bail(event: String, payload: Any): PluginEventResult {
        throw unsupported("bail")
    }

    /**
     * **waterfall**：把监听器按注册顺序包在 [inner] 外面（首个注册的监听器在最外层）。
     *
     * 对齐 cordis-rs `Context::waterfall`（`context.rs:512`）：监听器调用
     * [PluginEventNext.next] 才会走到下一个监听器，链尾之后是 [inner]；监听器不调用
     * `next` 即短路（inner 不执行）。
     */
    fun waterfall(event: String, payload: Any, inner: () -> PluginEventResult): PluginEventResult {
        throw unsupported("waterfall")
    }

    companion object {
        /** 契约扩展的 fail-closed 默认实现（见接口 KDoc「契约扩展的兼容性」）。 */
        private fun unsupported(name: String): UnsupportedOperationException =
            UnsupportedOperationException(
                "PluginContext 实现未覆写 $name（P3-3a 新增的 Cordis 事件派发成员）；" +
                    "core:agent 的 PluginContextImpl 已实现全部成员"
            )
    }
}

/**
 * 代码插件契约（对应 Cordis `export const name + inject + apply(ctx)`）。
 *
 * 实现方（feature 模块）只依赖本契约；装配内的一切副作用必须经 [PluginContext.effect]
 * 注册，保证可逆（卸载/热更新安全）。
 *
 * 自描述一致性：宿主用 [manifest] 校验 [id]/[name]/[kind]/[requires]/[configSchema]/[description]；
 * 实现方必须让这六项与清单逐字一致，否则宿主拒绝注册（fail-closed）。
 * 默认实现由本接口的同名成员合成清单，因此实现方通常只需覆写 [manifest] 以补充
 * [PluginManifest.version]/[PluginManifest.toolsets] 并显式声明依赖。
 */
interface LianYuPlugin {
    /** 全局唯一插件 id（如 `ui.assists`）。 */
    val id: String

    /** 展示名。 */
    val name: String

    /**
     * 插件类别（对齐 [PluginManifest.kind]）。宿主据此做分派与查询（[PluginHost.pluginsOf]）。
     *
     * 类别决定**装配语义**，不改变装配时序：装载/卸载流程对全部类别一致
     * （依赖校验 → 配置 Schema 校验 → setup；卸载逆序执行 effects）。
     */
    val kind: PluginKind

    /** 清单版本；仅用于自描述/诊断（默认 `1.0.0`），不参与依赖解析。 */
    val version: String get() = DEFAULT_VERSION

    /** 依赖的框架服务键集合（对齐 Cordis `inject`）；缺失时宿主 fail-closed 拒绝装载。 */
    val requires: Set<String>

    /** 配置 JSON Schema 文本；null = 无配置。 */
    val configSchema: String?

    /**
     * 面向用户的**一句话说明**：这个插件替用户做了什么。
     *
     * 它是「插件设置」页的行副标题（页面读取本字段；空串时回落为 [id]），
     * 也是该页搜索的命中范围之一——写清「做了什么」比复述 id / 展示名有用得多。
     *
     * 约定：
     * - **空串 = 未声明说明**（默认值）。此时设置页显示插件 id，不显示空行；
     * - 必须与 [PluginManifest.description] **逐字一致**，否则宿主在 [PluginHost.register]
     *   与 [PluginHost.load] 两处 fail-closed 拒绝该插件；
     * - 用中文一句话，**与实际装配的工具 / 行为一致**，不复述 id 或展示名。
     *
     * 带默认实现：既有实现方**源码级兼容**（先例见 [PluginContext] 的契约扩展兼容性说明）。
     */
    val description: String get() = ""

    /**
     * 插件清单（对应 Cordis package.json 的投影）。
     *
     * 默认由本接口的同名成员合成；覆写时必须保持
     * id/name/kind/requires/configSchema/description 与本接口一致，
     * 否则宿主在 [PluginHost.register] 阶段拒绝注册。
     */
    val manifest: PluginManifest
        get() = PluginManifest(
            id = id,
            name = name,
            version = version,
            kind = kind,
            requires = requires.sorted(),
            description = description,
            configSchema = configSchema,
        )

    /** 装配（对齐 Cordis `apply(ctx)`）；抛异常视为装配失败并回滚全部副作用。 */
    fun setup(ctx: PluginContext)

    companion object {
        /** [version] 的默认值（对齐 Cordis package.json 的初始版本号）。 */
        const val DEFAULT_VERSION: String = "1.0.0"
    }
}

/** 插件装载结果。 */
sealed class PluginLoadResult {
    /** 装载成功。 */
    object Loaded : PluginLoadResult()

    /** 插件未注册。 */
    object NotFound : PluginLoadResult()

    /**
     * 装载失败，已回滚副作用。reason 覆盖四类原因：
     * 清单与自描述不一致 / 依赖服务缺失 / 配置校验失败 / 装配异常。
     */
    data class Failed(val reason: String) : PluginLoadResult()
}

/**
 * 插件运行时（对应 Cordis 宿主）：注册 / 装载 / 卸载 / 生命周期。
 *
 * 生命周期：`register`（清单校验 → 入册）→ `load`（清单校验 → 依赖校验 →
 * 配置 Schema 校验 → setup）→ `unload`（逆序执行全部 effects）。
 * `load` 幂等；`unload` 未装载时返回 false；`loadBlueprint` 语义不变。
 *
 * 分派：宿主按 [PluginKind] 建立可查询视图（[pluginsOf]），ADAPTER / PIPELINE 与
 * TOOL / SKILL / STICKER 走**完全相同**的注册、装载、卸载与回滚路径——P1 只定义
 * 通道与管道的契约语义，不实现任何真实通道或管道。
 */
interface PluginHost {
    /**
     * 注册代码插件（同名覆盖）。
     *
     * 注册前校验 [LianYuPlugin.manifest] 与插件自描述是否一致；不一致则**拒绝注册**
     * （registry 保持原状并记录告警），使该插件无法被装载——fail-closed。
     */
    fun register(plugin: LianYuPlugin)

    /** 注销代码插件（先卸载再移除）。 */
    fun unregister(id: String): Boolean

    /** 按 id 查插件。 */
    fun plugin(id: String): LianYuPlugin?

    /** 是否已注册（注册被清单校验拒绝时为 false）。 */
    fun isRegistered(id: String): Boolean

    /** 全部已注册插件（按 id 排序）。 */
    fun plugins(): List<LianYuPlugin>

    /** 按类别查已注册插件（按 id 排序）；宿主据此分派 TOOL / SKILL / STICKER / ADAPTER / PIPELINE。 */
    fun pluginsOf(kind: PluginKind): List<LianYuPlugin>

    /** 装载插件（幂等）。configJson 为 null 时使用插件默认行为。 */
    fun load(id: String, configJson: String?): PluginLoadResult

    /** 卸载插件（逆序执行 effects），返回是否已装载。 */
    fun unload(id: String): Boolean

    /** 是否已装载。 */
    fun isLoaded(id: String): Boolean

    /** 当前已装载插件 id 集合。 */
    fun loadedIds(): Set<String>

    /**
     * 按蓝图装载（对应 cordis.yml 组合语义）：对每个引用执行 load/unload；
     * 已装载且配置未变的插件跳过（幂等），配置变化则先卸载再装载。
     */
    fun loadBlueprint(blueprint: PluginBlueprint): BlueprintLoadResult
}

/**
 * 蓝图中的插件引用（对应 cordis.yml 的 `- id/name/config` 条目）。
 */
data class BlueprintPluginRef(
    val id: String,
    /** false = 该插件在此蓝图中禁用（已装载则卸载）。 */
    val enabled: Boolean = true,
    /** 插件配置 JSON 文本；null = 插件默认行为。 */
    val configJson: String? = null,
)

/**
 * 插件蓝图（对应 cordis.yml：plugins + patches + inserts 组合语义）。
 *
 * 解析结果（[com.yunian.ai.agent.plugin.PluginBlueprintParser] 产出）：
 * - `plugins`：基准插件列表；
 * - `patches`：按 id 覆盖基准条目的 enabled / 深合并 config（cordis patch）；
 * - `inserts`：追加到列表尾部的新插件条目（cordis insert）。
 */
data class PluginBlueprint(
    val id: String,
    val name: String,
    val plugins: List<BlueprintPluginRef>,
)

/** 蓝图装载结果。 */
sealed class BlueprintLoadResult {
    /** 已装载插件 id 列表 + 跳过项（disabled/notfound/failed 及原因）。 */
    data class Applied(val loaded: List<String>, val skipped: List<String>) : BlueprintLoadResult()

    /** 蓝图本身非法（解析失败等）。 */
    data class Failed(val reason: String) : BlueprintLoadResult()
}

/**
 * 并发派发（[PluginContext.parallel]）的失败聚合异常（对齐 cordis-rs
 * `EventsService::parallel` 把全部监听器失败聚合成一个 `CordisError`）。
 *
 * 语义要点：**只要有监听器失败，同一次派发里的其他监听器仍然全部执行完**——
 * 因此异常携带的是失败清单而非「首个失败」。失败以 `"<异常类名>: <message>"` 的形式
 * 记录；异常对象本身不随异常携带（契约层不引入额外的结果类型）。
 */
class PluginDispatchException(
    /** 事件名。 */
    val event: String,
    /** 全部失败描述（至少一项）。 */
    val failures: List<String>,
) : RuntimeException(
    "${failures.size} event listener(s) failed [event=$event]: ${failures.joinToString("; ")}"
)

/** 框架服务键（对齐 Cordis 宿主提供的 `ctx.<service>`）。 */
object PluginServices {
    /** Application/Context（Android 实例由宿主注入，契约层不依赖 Android）。 */
    const val APP_CONTEXT = "appContext"

    /** 工具注册中心（ToolRegistry）。 */
    const val TOOLS = "tools"

    /**
     * Agent 门面（[com.yunian.ai.domain.DialogueCoordinator] 能力）。
     *
     * 宿主（`YuNianApplication.initBusiness`）在构造 PluginHostImpl 时把**已注册的同一个**
     * DialogueCoordinator 实例预置到本键，因此声明 `requires = [AGENT]` 的通道插件可以
     * `ctx.inject<DialogueCoordinator>(AGENT)` 拿到生产实例。
     * 未预置时装载 fail-closed（依赖校验拒绝装载），与其余服务键语义一致。
     */
    const val AGENT = "agent"

    /** 技能选择/存储。 */
    const val SKILLS = "skills"

    /** 记忆存储。 */
    const val MEMORY = "memory"

    /** 表情包偏好引擎。 */
    const val STICKER = "sticker"

    /**
     * 通道注册中心（[com.yunian.ai.domain.channel.ChannelRegistry]）。
     *
     * 消息通道适配器（[PluginKind.ADAPTER]）经本键注入注册中心，把自己挂上去；
     * 注册中心的内容来源于 [PluginHost.pluginsOf] `(PluginKind.ADAPTER)`：注册请求要回查
     * 该分派视图，查询结果再按「是否处于装载态」过滤，因此卸载后适配器随之消失
     * （Cordis「卸载不留鸡毛」）。
     */
    const val CHANNELS = "channels"
}
