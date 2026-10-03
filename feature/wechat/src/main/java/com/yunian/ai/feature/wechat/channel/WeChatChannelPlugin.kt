package com.yunian.ai.feature.wechat.channel

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
import com.yunian.ai.feature.wechat.ui.WeChatSettingsSection
import com.yunian.ai.uicommon.plugin.PluginSettingsSections
import kotlinx.coroutines.CancellationException

/**
 * 通道插件：微信（Cordis 双层插件模板 · 代码插件，kind = **ADAPTER**）。
 *
 * 与 `channel.qqbot`（P4-1）**对称**：本插件做三件事，三件都必须可逆
 * （[PluginContext.effect]）：
 * 1. 把 [WeChatChannelAdapter] 注册进 [MutableChannelRegistry]（通道身份 / 能力声明）；
 * 2. **作为订阅方**订阅 [ChannelOutboundEvents.REQUEST]（主动发送的请求 / 应答语义）；
 * 3. **自贡献设置区**：把 [WeChatSettingsSection] 挂进
 *    [com.yunian.ai.uicommon.plugin.PluginSettingsSections]，统一设置页只负责渲染
 *    （设置页不 import 本模块，本模块也不依赖设置模块）。
 *
 * 第 3 件事不改变前两件的任何语义，也不引入后台循环：它只是往一张
 * `ConcurrentHashMap` 里放一个对象，撤销即移除。
 *
 * ## 为什么订阅，而不是让上层调 `send()`
 *
 * §17.6 的架构裁定：`ChannelSession.send(outbound)` 是**传输契约**，把通道当**被调用方**，
 * 与投影模型（通道是**订阅方**）形状不符；「Agent 想主动发一条」的请求 / 应答语义
 * 在 Cordis 里是 `waterfall` / `bail` 的用途，**不是一个公开的 `send()`**。
 *
 * 因此本插件用 [PluginContext.onBail] 订阅，语义是**路由**：
 * - `request.channelKey` 与 [CHANNEL_KEY] 相等 → 认领，处理完 bail 出
 *   [ChannelOutboundResult]（应答即 bail 值，停止后续派发）；
 * - **不相等 → 返回 null 放行**（`next.next()`），**绝不吞掉别人的请求**——
 *   吞掉会让 QQ 的请求静默变成「无人处理」，把「没启用」误报成「已处理」。
 *
 * ## W1（修正版）：收件人 = **绑定的那个微信用户**，没有歧义
 *
 * 用户裁定（**权威约束，推翻了本类原先的设计前提**）：微信走 ilink 协议，而
 * **ilink 协议只允许给绑定的那个微信账号发消息**——「发给别人」和「群发」
 * 在协议上**根本不存在**。于是：
 * - `target` 为空 / 空白 → **就是那个绑定的微信用户**，正常发送。
 *   这是**主路径**：用户说「你给我微信发条消息」时模型会省略 `target`，
 *   旧实现（「target 为空 → 需要显式收件人」）让它**必然失败**；
 * - `target` 非空且**等于**绑定用户 → 正常发送；
 * - `target` 非空但**不等于**绑定用户 → **如实失败**：绝不静默忽略用户的显式指定，
 *   也绝不把它改写成绑定用户后照发；
 * - 无法**唯一确定**绑定用户 → **如实失败**，**绝不静默挑一个**。
 *
 * 「不得静默地在多个候选里挑一个」这条原则**保留**，但它现在由
 * [WeChatChannelSender.resolveRecipient] **统一**承担（解析是发送前的一步，
 * 见那里的 KDoc）——本类只负责把解析结果如实映射成 [ChannelOutboundResult]。
 *
 * 为什么收件人解析放在 [WeChatChannelSender] 而不是本类：本类拿不到
 * `companionId`（[ChannelOutboundRequest] **刻意不含**该字段），也拿不到登录态；
 * 而「谁是绑定的微信用户」是**账号事实**，只有握着 `IlinkSessionStore` 的那一侧才知道。
 * 本任务**不扩契约**：[ChannelOutboundRequest] 一个字段都没加。
 *
 * ## 前置状态必须如实失败（禁止静默失败）
 *
 * 未登录 / `context_token` 缺失 / `context_token` 超过 24h / 传输报错 → 一律
 * [ChannelOutboundResult.Failed] 并给出**具体可读**的原因。
 * 本插件**没有任何**「乐观成功」分支，也不复用 `WeChatOutboundPortImpl` 那种
 * 静默返回 `SKIPPED`（空串）的通路。
 *
 * ## 保活红线
 *
 * [setup] **不启动轮询、不发心跳、不碰重连、不改变任何消息时序**：它只注册适配器 +
 * 订阅事件 + 挂设置区（后者只是往一张 map 里放一个对象）。微信的连接 / 心跳 / 重连 / 保活完全由既有的 `WeChatPollingService`
 * （FGS + WakeLock + watchdog）/ `WeChatRestartWorker` / `WeChatOutboxDropLog` 掌握，
 * 本批一行未动。发送是**按需**的（用户请求 → 工具 → 事件 → 一次 transport 调用），
 * 不产生任何后台循环。
 *
 * ## 依赖 fail-closed
 *
 * [requires] 只声明 [PluginServices.CHANNELS]（注册中心）。
 * 事件订阅（`onBail`）是 [PluginContext] 的**成员**而非服务键，无需声明依赖。
 * 宿主未预置 CHANNELS 时，[com.yunian.ai.domain.plugin.PluginHost.load] 在 setup **之前**
 * 即拒绝装载，不留任何半装配状态（无注册、无 effect、`isLoaded` 保持 false）。
 *
 * @param adapterFactory 适配器工厂（默认产出生产用的 [WeChatChannelAdapter]；
 *   单测可注入替身以避开 Android Context）。
 * @param senderSupplier 出站供应函数（**只在收到请求时求值**）。
 *   默认返回 null = 未接线 → 请求得到明确失败，而不是假装成功。
 *   生产由 `YuNianApplication` 传入 [weChatChannelSender]。
 */
class WeChatChannelPlugin(
    private val adapterFactory: () -> ChannelAdapter = { WeChatChannelAdapter() },
    private val senderSupplier: () -> WeChatChannelSender? = { null },
) : LianYuPlugin {

    companion object {
        /** 插件 id（蓝图 assets/blueprints/default.json 里引用的就是这个字符串）。 */
        const val ID: String = "channel.wechat"

        /** 通道标识（与 [WeChatChannelAdapter.KEY] 同一常量来源）。 */
        const val CHANNEL_KEY: String = WeChatChannelAdapter.KEY

        /**
         * 通道**未接线**（`senderSupplier` 返回 null）时的失败原因。
         *
         * 收件人解析需要真实的会话存储，因此「未接线」判定**先于**解析：
         * 没有通道就没有「绑定的微信用户」可言，此时唯一的诚实答复是「未接线」。
         */
        const val NOT_WIRED_REASON: String =
            "微信通道主动发送未接线：请检查应用装配（YuNianApplication 的 " +
                "channel.wechat 插件注册）"
    }

    override val id: String = ID

    override val name: String = "微信通道"

    override val kind: PluginKind = PluginKind.ADAPTER

    override val requires: Set<String> = setOf(PluginServices.CHANNELS)

    override val configSchema: String? = null

    /**
     * 「插件设置」页展示的一句话说明，逐条对应 [setup] 实际做的事：
     * 注册 [WeChatChannelAdapter]（收发消息的身份）、订阅
     * [ChannelOutboundEvents.REQUEST]（认领本通道的主动发送请求）、挂 [WeChatSettingsSection]。
     *
     * 「绑定用户」是 ilink 协议的硬约束（见类 KDoc「W1（修正版）」）：本通道只能给绑定的
     * 那个微信账号发消息，所以文案不说「群发」，也不说「发给任意联系人」。
     */
    override val description: String = "通过微信收发消息，并按请求主动发送给绑定用户；停用后不再收发。"

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = "微信通道",
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
                handleOutboundRequest(payload, senderSupplier())
            }
        }

        // ── 3. 自贡献设置区（Cordis 式「插件自贡献 UI」）──
        //
        // 插件把自己的设置界面挂进 core:ui-common 的 PluginSettingsSections，
        // 统一设置页（feature:settings）只负责渲染——**设置页不 import 本模块**，
        // 本模块也不依赖设置模块，两边只靠 pluginId 字符串对齐（见 WeChatSettingsSection）。
        // 与上面两条一样是可逆副作用：卸载 → 逆序撤销 → 设置区自动消失。
        //
        // 这一条**不引入任何后台循环 / 阻塞调用**：它只往一张 ConcurrentHashMap 里放一个对象，
        // 保活链路（WeChatPollingService / 心跳 / 重连 / 消息时序）一行未动。
        PluginSettingsSections.register(WeChatSettingsSection)
        ctx.effect({ PluginSettingsSections.unregister(ID) }, "settings-section")
    }

    /**
     * 处理一条属于本通道的出站请求（**只由**上面的订阅回调调用）。
     *
     * 内部可见（而非 private）以便单测直接驱动：订阅回调本身需要真实的
     * `com.yunian.ai.agent.plugin.PluginEventBus`，而 `:feature:wechat` 不能依赖
     * `:core:agent`（core 不得依赖 feature；feature 之间也不得互相依赖）。
     */
    internal fun handleOutboundRequest(
        request: ChannelOutboundRequest,
        sender: WeChatChannelSender?,
    ): ChannelOutboundResult {
        if (sender == null) {
            return ChannelOutboundResult.Failed(NOT_WIRED_REASON)
        }

        // 同步等待：本方法是 **bail 派发链**上的一环，而 PluginContext.bail 是同步函数
        // （与 cordis-rs 的 block_on 语义对应）。因此这里用 runBlocking 把
        // 「解析收件人 + 一次发送」收敛成一个同步结果——取消照常向上传播，
        // 不吞 CancellationException。
        return try {
            when (val recipient = kotlinx.coroutines.runBlocking { sender.resolveRecipient(request.target) }) {
                // 收件人不合法 / 无法唯一确定：如实失败，**绝不**改写后照发。
                is WeChatRecipientResolution.Failed -> ChannelOutboundResult.Failed(recipient.reason)
                is WeChatRecipientResolution.Resolved ->
                    deliver(recipient.wechatUserId, request.text, sender)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // 通道抛异常同样必须如实失败（绝不让异常穿出成「无人认领」，
            // 那会把「发送失败」误报成「通道未启用」）。
            ChannelOutboundResult.Failed(
                "微信主动发送异常：" + (failure.message ?: failure.javaClass.simpleName),
            )
        }
    }

    /**
     * 把文本交给通道发出（**收件人已解析完毕**）。
     *
     * 只在 [handleOutboundRequest] 的 `try` 内调用，因此这里抛出的异常同样会被
     * 转成如实失败；[CancellationException] 照常向上传播。
     */
    private fun deliver(
        wechatUserId: String,
        text: String,
        sender: WeChatChannelSender,
    ): ChannelOutboundResult =
        when (val outcome = kotlinx.coroutines.runBlocking { sender.sendText(wechatUserId, text) }) {
            is WeChatSendOutcome.Sent -> ChannelOutboundResult.Sent(messageRef = outcome.messageRef)
            is WeChatSendOutcome.Failed -> ChannelOutboundResult.Failed(outcome.reason)
        }
}
