package com.yunian.ai.agent.plugin

import com.yunian.ai.domain.plugin.PluginContext

/**
 * [PluginContext] 实现：服务注入 + 可逆副作用（LIFO 逆序 dispose）+ 事件订阅。
 *
 * 生命周期约束：
 * - [effect] 注册的副作用在 [disposeAll] 时**逆序**执行（Cordis「卸载不留鸡毛」语义），
 *   保证先注册的先清理依赖（如先注册工具再注册可用性钩子 → 先摘钩子再注销工具）；
 * - [on] 自动把退订注册为 effect，卸载即退订；
 * - 装配抛异常时由宿主调用 [disposeAll] 回滚已注册的全部副作用。
 */
internal class PluginContextImpl(
    private val services: MutableMap<String, Any>,
) : PluginContext {

    private data class EffectEntry(val disposer: () -> Unit, val label: String)

    private val effects = mutableListOf<EffectEntry>()
    private val listeners = mutableMapOf<String, MutableList<(Any) -> Unit>>()

    override fun <T : Any> provide(key: String, service: T) {
        services[key] = service
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> inject(key: String): T {
        return services[key] as? T
            ?: throw IllegalStateException("插件依赖服务未提供: $key（请检查插件 requires 声明与宿主预置服务）")
    }

    override fun effect(disposer: () -> Unit, label: String) {
        effects.add(EffectEntry(disposer, label))
    }

    override fun on(event: String, handler: (Any) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(handler)
        effect({ listeners[event]?.remove(handler) }, "unsubscribe:$event")
    }

    /** 逆序执行全部副作用并清空（卸载 / 装配失败回滚）。 */
    fun disposeAll() {
        effects.asReversed().forEach { entry ->
            runCatching { entry.disposer() }.onFailure {
                android.util.Log.w("PluginContextImpl", "dispose 失败 [${entry.label}]: ${it.message}")
            }
        }
        effects.clear()
    }

    /** 派发事件（快照遍历，处理器异常不影响其他订阅者）。 */
    fun emit(event: String, payload: Any) {
        listeners[event]?.toList()?.forEach { handler ->
            runCatching { handler(payload) }.onFailure {
                android.util.Log.w("PluginContextImpl", "事件处理失败 [$event]: ${it.message}")
            }
        }
    }
}
