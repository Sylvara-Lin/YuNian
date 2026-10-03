package com.yunian.ai.feature.qqbot.channel

import com.yunian.ai.domain.ChannelKeys
import com.yunian.ai.domain.channel.ChannelAdapter
import com.yunian.ai.domain.channel.ChannelOutboundEvents
import com.yunian.ai.domain.channel.ChannelOutboundRequest
import com.yunian.ai.domain.channel.ChannelOutboundResult
import com.yunian.ai.domain.channel.MutableChannelRegistry
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginManifest
import com.yunian.ai.domain.plugin.PluginServices
import com.yunian.ai.feature.qqbot.QQBotDebugLog
import com.yunian.ai.feature.qqbot.data.QQBotMessageRepository
import com.yunian.ai.feature.qqbot.data.model.QQProactiveSendResult
import com.yunian.ai.feature.qqbot.data.model.QQProactiveTarget
import com.yunian.ai.feature.qqbot.data.model.QQProactiveTargetKind
import com.yunian.ai.feature.qqbot.data.network.ConnectionState
import com.yunian.ai.feature.qqbot.ui.QQBotSettingsSection
import com.yunian.ai.uicommon.plugin.PluginSettingsSections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * 通道插件：QQ 机器人（Cordis 双层插件模板 · 代码插件，kind = **ADAPTER**）。
 *
 * 本插件做三件事，三件都必须可逆（[PluginContext.effect]）：
 * 1. 把 [QQBotChannelAdapter] 注册进 [MutableChannelRegistry]（通道身份 / 能力声明）；
 * 2. **作为订阅方**订阅 [ChannelOutboundEvents.REQUEST]（主动发送请求/应答语义）；
 * 3. **自贡献设置区**：把 [QQBotSettingsSection] 挂进
 *    [com.yunian.ai.uicommon.plugin.PluginSettingsSections]，统一设置页只负责渲染
 *    （设置页不 import 本模块，本模块也不依赖设置模块）。
 *
 * 第 3 件事不改变前两件的任何语义，也不引入后台循环：它只是往一张
 * `ConcurrentHashMap` 里放一个对象，撤销即移除。
 *
 * ## 为什么订阅，而不是让上层调 `send()`
 *
 * §17.6 的架构裁定：`ChannelSession.send(outbound)` 是**传输契约**，把通道当**被调用方**，
 * 与投影模型（通道是**订阅方**）形状不符；「Agent 想主动发一条」的请求/应答语义
 * 在 Cordis 里是 `waterfall` / `bail` 的用途，**不是一个公开的 `send()`**。
 *
 * 因此本插件用 [PluginContext.onBail] 订阅，语义是**路由**：
 * - `request.channelKey` 与 [CHANNEL_KEY] 相等 → 认领，处理完 bail 出
 *   [ChannelOutboundResult]（应答即 bail 值，停止后续派发）；
 * - **不相等 → 返回 null 放行**（`next.next()`），**绝不吞掉别人的请求**——
 *   吞掉会让另一个通道的请求静默变成「无人处理」，把「没启用」误报成「已处理」。
 *
 * ## 卸载即失效（「可自由启用 / 停用」的验收点）
 *
 * 订阅、适配器注册与设置区注册都登记为 effect：卸载 → 逆序执行 → 退订 + 摘掉适配器 +
 * 撤销设置区。此后同一个工具再派发本通道的请求会**没人认领**，工具返回「该通道未启用」的
 * **明确失败**；插件设置页里本通道的设置区也**随之消失**（不再显示可展开的条目内容）。
 *
 * ## 前置状态必须如实失败（禁止假装成功）
 *
 * 未配置账号 / 未连接 / 没有可用目标 → [ChannelOutboundResult.Failed] 并给出具体原因。
 * 本插件**没有任何**「乐观成功」分支。
 *
 * ## 保活红线
 *
 * [setup] **不启动连接、不发心跳、不碰重连、不改变任何消息时序**：
 * 它只注册适配器 + 订阅事件 + 挂设置区（后者只是往一张 map 里放一个对象）。
 * QQ 的连接 / 心跳 / 重连 / 收发时序完全由既有的
 * `QQBotForegroundService` / `QQBotWebSocketClient` / `QQBotChatBridge` 掌握，本批一行未动。
 * 发送是**按需**的（用户请求 → 工具 → 事件 → 一次 HTTP），不产生任何后台循环。
 *
 * ## 依赖 fail-closed
 *
 * [requires] 只声明 [PluginServices.CHANNELS]（注册中心）。
 * 事件订阅（`onBail`）是 [PluginContext] 的**成员**而非服务键，无需声明依赖。
 * 宿主未预置 CHANNELS 时，[com.yunian.ai.domain.plugin.PluginHost.load] 在 setup **之前**
 * 即拒绝装载，不留任何半装配状态（无注册、无 effect、`isLoaded` 保持 false）。
 *
 * @param adapterFactory 适配器工厂（默认产出生产用的 [QQBotChannelAdapter]；
 *   单测可注入替身以避开 Android Context）。
 * @param proactiveSenderSupplier 主动发送供应函数（**只在收到请求时求值**）。
 *   默认返回 null = 未接线 → 请求得到明确失败，而不是假装成功。
 *   生产由 `YuNianApplication` 传入 [QQBotProactiveSender.fromRepository]。
 */
class QQBotChannelPlugin(
    private val adapterFactory: () -> ChannelAdapter = { QQBotChannelAdapter() },
    private val proactiveSenderSupplier: () -> QQBotProactiveSender? = { null },
) : LianYuPlugin {

    companion object {
        /** 插件 id（蓝图 assets/blueprints/default.json 里引用的就是这个字符串）。 */
        const val ID: String = "channel.qqbot"

        /** 通道标识（与 [QQBotChannelAdapter.KEY] 同一常量来源）。 */
        const val CHANNEL_KEY: String = QQBotChannelAdapter.KEY

        /**
         * 群目标的显式前缀：`"group:<group_openid>"`。
         *
         * 有前缀的写法是**无歧义**的（用户 / 模型明确说「这是群」）；
         * 无前缀的裸标识按「用户 openid」解释（单聊是默认语义）。
         */
        const val GROUP_TARGET_PREFIX: String = "group:"

        /** 模型可发现的稳定别名：最近一次收到入站消息的 QQ 群。 */
        const val RECENT_GROUP_TARGET: String = "group"
        const val RECENT_GROUP_TARGET_ALIAS: String = "recent_group"
    }

    override val id: String = ID

    override val name: String = "QQ 机器人通道"

    override val kind: PluginKind = PluginKind.ADAPTER

    override val requires: Set<String> = setOf(PluginServices.CHANNELS)

    override val configSchema: String? = null

    /**
     * 「插件设置」页展示的一句话说明，逐条对应 [setup] 实际做的事：
     * 注册 [QQBotChannelAdapter]（收发消息的身份）、订阅
     * [ChannelOutboundEvents.REQUEST]（认领本通道的主动发送请求）、挂 [QQBotSettingsSection]。
     * 卸载即三件一起撤销（见类 KDoc「卸载即失效」），因此「停用后不再收发」是装配事实。
     */
    override val description: String = "通过 QQ 机器人收发消息，并支持按请求主动发送；停用后不再收发。"

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = "QQ 机器人通道",
        version = "1.0.0",
        kind = PluginKind.ADAPTER,
        requires = listOf(PluginServices.CHANNELS),
        description = description,
        configSchema = null,
    )

    override fun setup(ctx: PluginContext) {
        // ── 1. 通道身份：注册适配器（卸载经 effect 摘掉）──
        val registry = ctx.inject<MutableChannelRegistry>(PluginServices.CHANNELS)
        val adapter = adapterFactory()
        registry.register(id, adapter)
        ctx.effect(
            { registry.unregister(id, adapter.channelKey) },
            "unregister-channel:" + adapter.channelKey,
        )

        // ── 2. 出站请求的**订阅方**（卸载自动退订，见 PluginContextImpl）──
        ctx.onBail(ChannelOutboundEvents.REQUEST) { payload ->
            if (payload !is ChannelOutboundRequest || payload.channelKey != adapter.channelKey) {
                // **不是给我的**：返回 null 放行给下一个订阅者，绝不吞掉。
                null
            } else {
                handleOutboundRequest(payload, proactiveSenderSupplier())
            }
        }

        // ── 3. 自贡献设置区（Cordis 式「插件自贡献 UI」）──
        //
        // 插件把自己的设置界面挂进 core:ui-common 的 PluginSettingsSections，
        // 统一设置页（feature:settings）只负责渲染——**设置页不 import 本模块**，
        // 本模块也不依赖设置模块，两边只靠 pluginId 字符串对齐（见 QQBotSettingsSection）。
        // 与上面两条一样是可逆副作用：卸载 → 逆序撤销 → 设置区自动消失。
        //
        // 这一条**不引入任何后台循环 / 阻塞调用**：它只往一张 ConcurrentHashMap 里放一个对象，
        // 保活链路（QQBotForegroundService / 心跳 / 重连 / 收发时序）一行未动。
        PluginSettingsSections.register(QQBotSettingsSection)
        ctx.effect({ PluginSettingsSections.unregister(ID) }, "settings-section")
    }

    /**
     * 处理一条属于本通道的出站请求（**只由**上面的订阅回调调用）。
     *
     * 内部可见（而非 private）以便单测直接驱动：订阅回调本身需要真实的
     * [com.yunian.ai.agent.plugin.PluginEventBus]，而 :feature:qqbot 不能依赖 :core:agent
     * （core 不得依赖 feature；feature 之间也不得互相依赖）。
     */
    internal fun handleOutboundRequest(
        request: ChannelOutboundRequest,
        sender: QQBotProactiveSender?,
    ): ChannelOutboundResult {
        if (sender == null) {
            return ChannelOutboundResult.Failed(
                "QQ 通道主动发送未接线：请检查应用装配（YuNianApplication 的 " +
                    "channel.qqbot 插件注册）"
            )
        }
        if (!sender.isLoggedIn()) {
            return ChannelOutboundResult.Failed(
                "QQ 机器人未配置账号：请先在予念的 QQ 机器人设置里完成扫码绑定"
            )
        }

        // 目标解析要在派发链上同步完成（resolveTarget 是 suspend：要读 DataStore 里的宿主 openid）。
        val target = try {
            kotlinx.coroutines.runBlocking { resolveTarget(request.target, sender) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        } ?: return ChannelOutboundResult.Failed(
            "没有可用的发送目标：target=" + (request.target ?: "<本人>") +
                "。发给本人需要先捕获用户本人的 user_openid" +
                "（在 QQ 里给机器人发一条消息即可补齐）；" +
                "发到最近群聊请用 target=group（需先在群里 @ 机器人一次）；" +
                "也可显式使用 \"group:<group_openid>\""
        )

        val requestedTarget = request.target
        QQBotDebugLog.log(
            "[Route] proactive target resolved kind=" + target.kind.name +
                " source=" + when {
                    requestedTarget.isNullOrBlank() -> "host"
                    requestedTarget.equals(RECENT_GROUP_TARGET, ignoreCase = true) ||
                        requestedTarget.equals(RECENT_GROUP_TARGET_ALIAS, ignoreCase = true) -> "recent_group"
                    requestedTarget.startsWith(GROUP_TARGET_PREFIX) -> "explicit_group"
                    else -> "explicit_user"
                },
        )

        val connection = sender.connectionState.value
        if (connection != ConnectionState.CONNECTED) {
            return ChannelOutboundResult.Failed(
                "QQ 通道当前未连接（状态 " + connection + "）：消息未发送，请等待通道恢复后重试"
            )
        }

        // 同步等待：本方法是 **bail 派发链**上的一环，而 PluginContext.bail 是同步函数
        // （与 cordis-rs 的 block_on 语义对应）。因此这里用 runBlocking 把一次 HTTP 调用
        // 收敛成一个同步结果——取消照常向上传播，不吞 CancellationException。
        return try {
            kotlinx.coroutines.runBlocking { sender.send(target, request.text) }.fold(
                onSuccess = {
                    QQBotDebugLog.log("[Route] proactive send success kind=" + target.kind.name)
                    ChannelOutboundResult.Sent(messageRef = it.messageRef)
                },
                onFailure = { error ->
                    QQBotDebugLog.log(
                        "[Route] proactive send failed kind=" + target.kind.name +
                            " reason=" + (error.message ?: error.javaClass.simpleName),
                    )
                    ChannelOutboundResult.Failed(
                        "QQ 主动发送失败：" + (error.message ?: error.javaClass.simpleName)
                    )
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // 通道抛异常同样必须如实失败（绝不让异常穿出成「无人认领」，
            // 那会把「发送失败」误报成「通道未启用」）。
            ChannelOutboundResult.Failed(
                "QQ 主动发送异常：" + (failure.message ?: failure.javaClass.simpleName)
            )
        }
    }

    /**
     * 目标解析（**唯一的决策点**，必须 fail-closed）。
     *
     * - `null` / 空白 = **本通道绑定的宿主（用户本人）**：用绑定期或入站 C2C 捕获的
     *   `user_openid`；拿不到就返回 null（调用方明确失败，**绝不猜一个目标**）；
     * - `"group"` / `"recent_group"` = 最近一次入站群聊：从可信持久化状态解析；
     * - `"group:<id>"` = 显式群聊：走 `group_openid` 接口（兼容既有调用）；
     * - 其余 = 用户 openid（单聊是默认语义）。
     */
    private suspend fun resolveTarget(
        raw: String?,
        sender: QQBotProactiveSender,
    ): QQProactiveTarget? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) {
            val hostId = sender.boundHostId()?.trim()
            if (hostId.isNullOrEmpty()) return null
            return QQProactiveTarget(kind = QQProactiveTargetKind.USER, id = hostId)
        }
        if (trimmed.equals(RECENT_GROUP_TARGET, ignoreCase = true) ||
            trimmed.equals(RECENT_GROUP_TARGET_ALIAS, ignoreCase = true)
        ) {
            val groupId = sender.recentGroupId()?.trim()
            if (groupId.isNullOrEmpty()) return null
            return QQProactiveTarget(kind = QQProactiveTargetKind.GROUP, id = groupId)
        }
        if (trimmed.startsWith(GROUP_TARGET_PREFIX)) {
            val groupId = trimmed.removePrefix(GROUP_TARGET_PREFIX).trim()
            if (groupId.isEmpty()) return null
            return QQProactiveTarget(kind = QQProactiveTargetKind.GROUP, id = groupId)
        }
        return QQProactiveTarget(kind = QQProactiveTargetKind.USER, id = trimmed)
    }
}

/**
 * QQ **主动发送**的宿主接缝（订阅方与既有 [QQBotMessageRepository] 之间的唯一接缝）。
 *
 * 单独抽出而不是直接吃仓库：仓库依赖 Android Context / Retrofit / DataStore，
 * 而 `:feature:qqbot` 的测试源集只有 junit、没有 Robolectric。
 * 生产装配把真实仓库包成这个接口（见 [fromRepository]），单测用内存替身驱动——
 * **被测的插件代码本身是真代码**。
 *
 * 与 [QQBotChannelSender] 的分工：后者是 **`ChannelSession.send` 传输通路**的窄接口
 * （被动回复，必须带锚点）；本接口是**事件订阅通路**（主动发送，省略 `msg_id`）。
 * 两条通路刻意不共用类型：它们的失败模式与前置条件不同（一个要锚点，一个要目标）。
 */
interface QQBotProactiveSender {

    /** 是否已配置账号（**同步**：绑定账号存在即可，不碰网络 / DataStore）。 */
    fun isLoggedIn(): Boolean

    /** 既有连接状态（唯一事实来源仍是既有仓库）。 */
    val connectionState: StateFlow<ConnectionState>

    /** 绑定宿主（用户本人）的 `user_openid`；**从未捕获过时如实返回 null**。 */
    suspend fun boundHostId(): String?

    /** 最近一次入站群聊的 `group_openid`；只来自 Gateway 入站事件。 */
    suspend fun recentGroupId(): String?

    /** 主动发送文本（严格省略 msg_id/msg_seq/message_reference）。 */
    suspend fun send(
        target: QQProactiveTarget,
        text: String,
    ): Result<QQProactiveSendResult>

    companion object {
        /** 把既有仓库适配成本接缝（**只转发，不加任何逻辑**）。 */
        fun fromRepository(repository: QQBotMessageRepository): QQBotProactiveSender =
            object : QQBotProactiveSender {
                override fun isLoggedIn(): Boolean = repository.hasAccount()

                override val connectionState: StateFlow<ConnectionState> =
                    repository.connectionState

                override suspend fun boundHostId(): String? = repository.hostUserOpenId()

                override suspend fun recentGroupId(): String? = repository.recentGroupOpenId()

                override suspend fun send(
                    target: QQProactiveTarget,
                    text: String,
                ): Result<QQProactiveSendResult> = repository.sendProactiveText(target, text)
            }
    }
}
