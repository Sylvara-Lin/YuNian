package com.yunian.ai.agent.plugin

import com.yunian.ai.domain.plugin.BlueprintLoadResult
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginBlueprint
import com.yunian.ai.domain.plugin.PluginHost
import com.yunian.ai.domain.plugin.PluginLoadResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * [PluginHost] 实现：注册 / 装载 / 卸载 / 生命周期管理。
 *
 * 装载流程（fail-closed）：
 *   1. requires 依赖校验——缺失的框架服务直接拒绝装载；
 *   2. 配置 JSON Schema 校验——不满足 schema 拒绝装载；
 *   3. 创建隔离的 [PluginContextImpl]（继承宿主框架服务快照）→ [LianYuPlugin.setup]；
 *   4. setup 抛异常 → 逆序回滚全部已注册副作用，返回 Failed。
 *
 * 卸载流程：逆序执行全部 effects（先注册的副作用先清理依赖）。
 * 线程安全：register/load/unload 内部同步；已装载实例按 id 隔离。
 */
class PluginHostImpl(
    /** 宿主预置的框架服务（对应 Cordis 宿主 ctx.<service>；键见 [com.yunian.ai.domain.plugin.PluginServices]）。 */
    frameworkServices: Map<String, Any>,
) : PluginHost {

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

    override fun register(plugin: LianYuPlugin) {
        registry[plugin.id] = plugin
    }

    override fun unregister(id: String): Boolean {
        unload(id)
        return registry.remove(id) != null
    }

    override fun plugin(id: String): LianYuPlugin? = registry[id]

    override fun plugins(): List<LianYuPlugin> = registry.values.sortedBy { it.id }

    override fun load(id: String, configJson: String?): PluginLoadResult = synchronized(this) {
        val plugin = registry[id] ?: return PluginLoadResult.NotFound
        if (loaded.containsKey(id)) return PluginLoadResult.Loaded // 幂等

        // 1. 依赖校验（对齐 Cordis inject：缺依赖 fail-fast）
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

        // 3. 隔离上下文（继承宿主框架服务快照）+ 装配
        val ctx = PluginContextImpl(HashMap(baseServices))
        return try {
            plugin.setup(ctx)
            loaded[id] = ctx
            loadedIds.add(id)
            // 见 loadedConfig 字段注释：null 值不得写入 ConcurrentHashMap，改为不写条目。
            if (configJson != null) loadedConfig[id] = configJson
            android.util.Log.i("PluginHostImpl", "loaded: ${plugin.id}")
            PluginLoadResult.Loaded
        } catch (t: Throwable) {
            // 装配失败：回滚全部副作用（Cordis「卸载不留鸡毛」）
            ctx.disposeAll()
            // 带堆栈打印：仅记 message 会让 NullPointerException（Kotlin `!!` 无消息）失去定位信息。
            android.util.Log.w("PluginHostImpl", "load failed: ${plugin.id}", t)
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
        android.util.Log.i("PluginHostImpl", "unloaded: $id")
        true
    }

    override fun isLoaded(id: String): Boolean = loaded.containsKey(id)

    override fun loadedIds(): Set<String> = loadedIds.toSet()

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
        android.util.Log.i("PluginHostImpl", "blueprint ${blueprint.id} loaded=$loadedNow skipped=$skipped")
        BlueprintLoadResult.Applied(loadedNow, skipped)
    }
}
