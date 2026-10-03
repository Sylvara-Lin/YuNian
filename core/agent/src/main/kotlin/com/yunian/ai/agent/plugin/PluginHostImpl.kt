package com.yunian.ai.agent.plugin

import com.yunian.ai.domain.plugin.BlueprintLoadResult
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginBlueprint
import com.yunian.ai.domain.plugin.PluginHost
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginLoadResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * [PluginHost] 实现：注册 / 装载 / 卸载 / 生命周期管理。
 *
 * 注册流程（fail-closed）：[manifestMatchesSelfDescription] 校验 [LianYuPlugin.manifest]
 * 与插件自描述（id/name/kind/requires/configSchema/description）逐字一致；不一致**拒绝注册**
 * （registry 保持原状并记录告警），使该插件永远无法被装载。
 *
 * 装载流程（fail-closed）：
 *   1. 清单校验——manifest 与自描述不一致直接拒绝（防绕过 register 的路径）；
 *   2. requires 依赖校验——缺失的框架服务直接拒绝装载；
 *   3. 配置 JSON Schema 校验——不满足 schema 拒绝装载；
 *   4. 创建隔离的 [PluginContextImpl]（继承宿主框架服务快照）→ [LianYuPlugin.setup]；
 *   5. setup 抛异常 → 逆序回滚全部已注册副作用，返回 Failed。
 *
 * 卸载流程：逆序执行全部 effects（先注册的副作用先清理依赖）。
 *
 * 分派：按 [PluginKind] 提供 [pluginsOf] 视图。ADAPTER（消息通道适配器）与 PIPELINE
 * （回合后处理管道）走与 TOOL/SKILL/STICKER **完全相同**的注册/装载/卸载/回滚路径——
 * 本阶段只固定语义与契约，不实现任何真实通道或管道。
 *
 * 事件总线（P3-3a）：本宿主持有**唯一**一个 [PluginEventBus]，装载时为每个插件新建的
 * [PluginContextImpl] 都指向它——因此插件 A 的 `ctx.emit` 能被插件 B 的 `ctx.on` 收到。
 * 总线实例可以经构造参数注入（单测共享/观察），默认按宿主自建。
 *
 * 线程安全：register/load/unload 内部同步；已装载实例按 id 隔离；事件侧由
 * [PluginEventBus] 的并发容器保证（见其 KDoc）。
 *
 * @param log 日志出口（生产为 [AndroidPluginLog]，单测注入 [NoOpPluginLog]）；
 *   除日志出口外，生命周期语义与迁移前逐字一致。
 * @param eventBus 宿主级共享事件总线；默认按本宿主的 [log] 新建一个。
 */
class PluginHostImpl(
    /** 宿主预置的框架服务（对应 Cordis 宿主 ctx.<service>；键见 [com.yunian.ai.domain.plugin.PluginServices]）。 */
    frameworkServices: Map<String, Any>,
    private val log: PluginLog = AndroidPluginLog(),
    private val eventBus: PluginEventBus = PluginEventBus(log),
) : PluginHost, com.yunian.ai.domain.plugin.PluginEventPublisher {

    private val baseServices: Map<String, Any> = HashMap(frameworkServices)
    private val registry = ConcurrentHashMap<String, LianYuPlugin>()
    private val loaded = ConcurrentHashMap<String, PluginContextImpl>()
    private val loadedIds = ConcurrentSkipListSet<String>()
    /**
     * 已装载插件的当前配置（load 成功记录 / unload 清除；蓝图据此判断是否需要重载）。
     *
     * ⚠️ 值类型必须是**非空** String：`ConcurrentHashMap` 是 Java 实现，运行时对 null 键/值
     * 直接抛 `NullPointerException`（`putVal` 处），Kotlin 写成 `String?` 只是类型系统假象。
     * 因此 `configJson == null` 时不写入条目（缺条目读出来就是 null，比较语义不变）。
     */
    private val loadedConfig = ConcurrentHashMap<String, String>()

    /** ToolHost 等宿主组件的标准发布端；仍进入本宿主持有的唯一 PluginEventBus。 */
    override fun <T : Any> emit(
        key: com.yunian.ai.domain.plugin.EventKey<T>,
        payload: T,
    ) = eventBus.emit(key.name, payload)

    override fun register(plugin: LianYuPlugin) {
        val manifestError = manifestMatchesSelfDescription(plugin)
        if (manifestError != null) {
            log.w(TAG, "register rejected: ${plugin.id} manifest 与自描述不一致: $manifestError")
            return
        }
        registry[plugin.id] = plugin
    }

    override fun unregister(id: String): Boolean {
        unload(id)
        return registry.remove(id) != null
    }

    override fun plugin(id: String): LianYuPlugin? = registry[id]

    override fun isRegistered(id: String): Boolean = registry.containsKey(id)

    override fun plugins(): List<LianYuPlugin> = registry.values.sortedBy { it.id }

    override fun pluginsOf(kind: PluginKind): List<LianYuPlugin> =
        plugins().filter { it.kind == kind }

    override fun load(id: String, configJson: String?): PluginLoadResult = synchronized(this) {
        val plugin = registry[id] ?: return PluginLoadResult.NotFound
        if (loaded.containsKey(id)) return PluginLoadResult.Loaded // 幂等

        // 0. 清单校验（fail-closed；防御绕过 register 的注册路径）
        val manifestError = manifestMatchesSelfDescription(plugin)
        if (manifestError != null) {
            return PluginLoadResult.Failed("插件 ${plugin.id} 清单与自描述不一致: $manifestError")
        }

        // 1. 依赖校验（对齐 Cordis inject：缺依赖 fail-closed）
        val missing = plugin.requires.filter { it !in baseServices }
        if (missing.isNotEmpty()) {
            return PluginLoadResult.Failed("插件 ${plugin.id} 缺少依赖服务: $missing（宿主未预置）")
        }

        // 2. 配置 Schema 校验（fail-closed）
        val schema = plugin.configSchema
        if (configJson != null && !schema.isNullOrBlank()) {
            val instance = runCatching { org.json.JSONTokener(configJson).nextValue() }.getOrNull()
            val errors = JsonSchemaValidator.validate(instance, schema)
            if (errors.isNotEmpty()) {
                return PluginLoadResult.Failed("插件 ${plugin.id} 配置校验失败: ${errors.joinToString("; ")}")
            }
        }

        // 3. 隔离上下文（继承宿主框架服务快照；事件总线是**共享**的）+ 装配
        val ctx = PluginContextImpl(HashMap(baseServices), eventBus, log)
        eventBus.trackContext(ctx)
        return try {
            plugin.setup(ctx)
            loaded[id] = ctx
            loadedIds.add(id)
            // 见 loadedConfig 字段注释：null 值不得写入 ConcurrentHashMap，改为不写条目。
            if (configJson != null) loadedConfig[id] = configJson
            log.i(TAG, "loaded: ${plugin.id}")
            PluginLoadResult.Loaded
        } catch (t: Throwable) {
            // 装配失败：回滚全部副作用（Cordis「卸载不留鸡毛」）
            ctx.disposeAll()
            // 带堆栈打印：仅记 message 会让 NullPointerException（Kotlin `!!` 无消息）失去定位信息。
            log.w(TAG, "load failed: ${plugin.id}", t)
            PluginLoadResult.Failed(
                "插件 ${plugin.id} 装配失败: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    override fun unload(id: String): Boolean = synchronized(this) {
        val ctx = loaded.remove(id) ?: return false
        ctx.disposeAll()
        loadedIds.remove(id)
        loadedConfig.remove(id)
        log.i(TAG, "unloaded: $id")
        true
    }

    override fun isLoaded(id: String): Boolean = loaded.containsKey(id)

    override fun loadedIds(): Set<String> = loadedIds.toSet()

    /**
     * 宿主整体回收：卸载全部已装载插件并清空共享总线的监听表。
     *
     * 正常路径不需要它——[unload] 已按 effect 逆序退订（Cordis「卸载不留鸡毛」）。
     * 本方法只兜「插件忘了把订阅登记成 effect」的情形，因此额外清空总线，
     * 避免残留监听器在宿主销毁后仍被调用。不改变 [load] / [unload] / [loadBlueprint]
     * 的任何既有语义。
     */
    internal fun disposeAll() = synchronized(this) {
        loaded.keys.toList().forEach { unload(it) }
        eventBus.disposeContexts()
    }

    override fun loadBlueprint(blueprint: PluginBlueprint): BlueprintLoadResult = synchronized(this) {
        val loadedNow = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (ref in blueprint.plugins) {
            if (!ref.enabled) {
                unload(ref.id)
                skipped += "disabled:${ref.id}"
                continue
            }
            // 配置变化 → 先卸载再装载（热更新语义）
            if (loaded.containsKey(ref.id) && loadedConfig[ref.id] != ref.configJson) {
                unload(ref.id)
            }
            when (val r = load(ref.id, ref.configJson)) {
                is PluginLoadResult.Loaded -> loadedNow += ref.id
                is PluginLoadResult.NotFound -> skipped += "notfound:${ref.id}"
                is PluginLoadResult.Failed -> skipped += "failed:${ref.id}(${r.reason})"
            }
        }
        log.i(TAG, "blueprint ${blueprint.id} loaded=$loadedNow skipped=$skipped")
        BlueprintLoadResult.Applied(loadedNow, skipped)
    }

    companion object {
        /** 日志 tag（与迁移前 `android.util.Log` 调用逐字一致）。 */
        private const val TAG = "PluginHostImpl"

        /**
         * 校验插件清单与自描述是否一致；一致返回 null，否则返回差异说明（fail-closed 依据）。
         *
         * 校验项：id / name / kind / requires（集合语义）/ configSchema / description /
         * requires 无重复项。
         * [PluginManifest.version] 与 [PluginManifest.toolsets] 属于清单独有的自描述信息，
         * 不参与一致性校验。
         *
         * [PluginManifest.description] 参与校验的理由与其余字段一致：它是「插件设置」页
         * 直接展示给用户的一句话说明，清单独有而代码另说会让页面显示与插件实际行为脱节。
         * 两处都必须逐字相同——包括「两边都留空」这一种情形（空串与空串一致，合法）。
         */
        internal fun manifestMatchesSelfDescription(plugin: LianYuPlugin): String? {
            val m = plugin.manifest
            if (m.id != plugin.id) return "manifest.id=${m.id} 与 plugin.id=${plugin.id} 不一致"
            if (m.name != plugin.name) return "manifest.name=${m.name} 与 plugin.name=${plugin.name} 不一致"
            if (m.kind != plugin.kind) return "manifest.kind=${m.kind} 与 plugin.kind=${plugin.kind} 不一致"
            if (m.description != plugin.description) {
                return "manifest.description=${m.description} 与 plugin.description=${plugin.description} 不一致"
            }
            if (m.requires.toSet() != plugin.requires) {
                return "manifest.requires=${m.requires.sorted()} 与 plugin.requires=${plugin.requires.sorted()} 不一致"
            }
            if (m.requires.size != m.requires.toSet().size) {
                return "manifest.requires 存在重复项: ${m.requires}"
            }
            if (m.configSchema != plugin.configSchema) {
                return "manifest.configSchema 与 plugin.configSchema 不一致"
            }
            return null
        }
    }
}
