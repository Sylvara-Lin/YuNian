package com.yunian.ai.feature.chat.plugin

import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.ToolFinishStatus
import com.yunian.ai.domain.plugin.ToolFinished
import com.yunian.ai.domain.plugin.ToolLifecycleEvents
import com.yunian.ai.domain.plugin.ToolStarted
import com.yunian.ai.domain.plugin.on

/** Chat 自己的工具生命周期订阅插件；不向 ToolHost 注入回调。 */
class ChatToolLifecyclePlugin(
    instanceId: String,
    private val subscriber: ChatToolLifecycleSubscriber,
) : LianYuPlugin {
    override val id: String = "$ID_PREFIX.$instanceId"
    override val name: String = "Chat tool lifecycle cards"
    override val kind: PluginKind = PluginKind.PIPELINE
    override val requires: Set<String> = emptySet()
    override val configSchema: String? = null

    /**
     * 「插件设置」页展示的一句话说明。本类不覆写 [com.yunian.ai.domain.plugin.LianYuPlugin.manifest]，
     * 默认合成实现直接取本字段，因此它与清单 description 天然逐字一致。
     *
     * 后半句是**装配语义**而非话术：订阅经 [PluginContext.on] 登记为可逆副作用
     * （见 PluginContextImpl「订阅即 effect：卸载/回滚时自动退订」），插件停用即退订，
     * 工具卡片随之停止投影。
     */
    override val description: String =
        "把工具执行状态投影成会话里的工具卡片；停用后不再显示。"

    override fun setup(ctx: PluginContext) {
        ctx.on(ToolLifecycleEvents.STARTED, subscriber::onStarted)
        ctx.on(ToolLifecycleEvents.FINISHED, subscriber::onFinished)
    }

    companion object {
        const val ID_PREFIX: String = "chat.tool-lifecycle-cards"
    }
}

/** 订阅插件与具体 ViewModel/卡片投影之间的 feature 内端口。 */
interface ChatToolLifecycleSubscriber {
    fun onStarted(event: ToolStarted)
    fun onFinished(event: ToolFinished)
}

/**
 * 一轮聊天的事件投影器。streamId 由本轮 ToolHost 随机构造，避免串台；
 * 输出只含工具名/状态/时间，不可能把参数或结果写进 TOOL_ACTIVITY。
 */
class ChatToolLifecycleProjection(
    private val streamId: String,
    private val onChanged: (List<ChatToolLifecycleItem>) -> Unit,
) : ChatToolLifecycleSubscriber {
    private val items = LinkedHashMap<String, ChatToolLifecycleItem>()

    @Synchronized
    override fun onStarted(event: ToolStarted) {
        if (event.streamId != streamId) return
        items[event.callId] = ChatToolLifecycleItem(
            id = event.callId,
            toolName = event.toolName,
            status = ChatToolLifecycleStatus.RUNNING,
            startedAtMs = event.startedAtMs,
        )
        onChanged(items.values.toList())
    }

    @Synchronized
    override fun onFinished(event: ToolFinished) {
        if (event.streamId != streamId) return
        val existing = items[event.callId]
        items[event.callId] = ChatToolLifecycleItem(
            id = event.callId,
            toolName = existing?.toolName ?: event.toolName,
            status = if (event.status == ToolFinishStatus.SUCCEEDED) {
                ChatToolLifecycleStatus.DONE
            } else {
                ChatToolLifecycleStatus.FAILED
            },
            startedAtMs = existing?.startedAtMs ?: event.startedAtMs,
        )
        onChanged(items.values.toList())
    }

    @Synchronized
    fun snapshot(): List<ChatToolLifecycleItem> = items.values.toList()
}

enum class ChatToolLifecycleStatus { RUNNING, DONE, FAILED }

data class ChatToolLifecycleItem(
    val id: String,
    val toolName: String,
    val status: ChatToolLifecycleStatus,
    val startedAtMs: Long,
)