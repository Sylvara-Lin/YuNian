package com.yunian.ai.domain.plugin

/**
 * 双层插件模型契约（对齐 Cordis 插件生态模板的投影，零 Android 依赖）。
 *
 * 模板对应关系：
 * - [PluginManifest]  ← Cordis package.json（id/name/version/依赖/toolsets）
 * - [LianYuPlugin]    ← Cordis 插件契约（`name` + `inject` + `apply(ctx)`）
 * - [PluginContext]   ← Cordis Context 最小面（provide/inject/effect/on）
 * - [PluginHost]      ← Cordis 宿主（注册/装载/卸载/生命周期）
 * - [PluginServices]  ← 框架服务键（对应 Cordis 宿主提供的 ctx.<service>）
 *
 * 双层模型：
 * 1. 代码插件（builtin）：实现 [LianYuPlugin]，编译期打进 APK，与壳/加固体系共存；
 * 2. 配置插件（runtime）：蓝图文件组合内置代码插件与内置能力，不执行任意代码。
 */

/** 插件类别（对齐 Cordis 生态的插件定位）。 */
enum class PluginKind { TOOL, SKILL, STICKER, ADAPTER, PIPELINE }

/** 插件清单（对应 Cordis package.json 的投影）。 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val kind: PluginKind,
    /** 依赖的框架服务键（对齐 Cordis `inject`；缺失时宿主 fail-fast）。 */
    val requires: List<String>,
    /** 归属工具集（对齐 Hermes TOOLSETS / cordis toolsets）。 */
    val toolsets: List<String> = emptyList(),
    /** 配置 JSON Schema 文本；null = 无配置。校验失败拒绝加载（fail-closed）。 */
    val configSchema: String? = null,
)

/**
 * 插件装配上下文（对应 Cordis Context 的最小投影）。
 *
 * - [provide]/[inject]：服务注入（依赖图由宿主预置框架服务 + 插件运行期提供）；
 * - [effect]：可逆副作用——卸载时按注册逆序执行（Cordis「卸载不留鸡毛」语义）；
 * - [on]：事件订阅（dispose 时自动退订）。
 */
interface PluginContext {
    /** 提供服务（插件运行期可用，覆盖宿主预置的同名服务）。 */
    fun <T : Any> provide(key: String, service: T)

    /** 按键取服务；缺失抛 [IllegalStateException]（fail-fast）。 */
    fun <T : Any> inject(key: String): T

    /** 注册可逆副作用；卸载/装配失败回滚时逆序执行。 */
    fun effect(disposer: () -> Unit, label: String)

    /** 订阅事件；卸载时自动退订。 */
    fun on(event: String, handler: (Any) -> Unit)
}

/**
 * 代码插件契约（对应 Cordis `export const name + inject + apply(ctx)`）。
 *
 * 实现方（feature 模块）只依赖本契约；装配内的一切副作用必须经 [PluginContext.effect]
 * 注册，保证可逆（卸载/热更新安全）。
 */
interface LianYuPlugin {
    /** 全局唯一插件 id（如 `coffee.luckin`）。 */
    val id: String

    /** 展示名。 */
    val name: String

    /** 依赖的框架服务键集合（对齐 Cordis `inject`）。 */
    val requires: Set<String>

    /** 配置 JSON Schema 文本；null = 无配置。 */
    val configSchema: String?

    /** 装配（对齐 Cordis `apply(ctx)`）；抛异常视为装配失败并回滚全部副作用。 */
    fun setup(ctx: PluginContext)
}

/** 插件装载结果。 */
sealed class PluginLoadResult {
    /** 装载成功。 */
    object Loaded : PluginLoadResult()

    /** 插件未注册。 */
    object NotFound : PluginLoadResult()

    /** 装载失败（依赖缺失 / 配置校验失败 / 装配异常），已回滚副作用。 */
    data class Failed(val reason: String) : PluginLoadResult()
}

/**
 * 插件运行时（对应 Cordis 宿主）：注册 / 装载 / 卸载 / 生命周期。
 *
 * 生命周期：`register` → `load`（依赖校验 → 配置 Schema 校验 → setup）→ `unload`
 * （逆序执行全部 effects）。`load` 幂等；`unload` 未装载时返回 false。
 */
interface PluginHost {
    /** 注册代码插件（同名覆盖）。 */
    fun register(plugin: LianYuPlugin)

    /** 注销代码插件（先卸载再移除）。 */
    fun unregister(id: String): Boolean

    /** 按 id 查插件。 */
    fun plugin(id: String): LianYuPlugin?

    /** 全部已注册插件（按 id 排序）。 */
    fun plugins(): List<LianYuPlugin>

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

/** 框架服务键（对齐 Cordis 宿主提供的 `ctx.<service>`）。 */
object PluginServices {
    /** Application/Context（Android 实例由宿主注入，契约层不依赖 Android）。 */
    const val APP_CONTEXT = "appContext"

    /** 工具注册中心（ToolRegistry）。 */
    const val TOOLS = "tools"

    /** Agent 门面（AgentFacade 能力）。 */
    const val AGENT = "agent"

    /** 技能选择/存储。 */
    const val SKILLS = "skills"

    /** 记忆存储。 */
    const val MEMORY = "memory"

    /** 表情包偏好引擎。 */
    const val STICKER = "sticker"
}
