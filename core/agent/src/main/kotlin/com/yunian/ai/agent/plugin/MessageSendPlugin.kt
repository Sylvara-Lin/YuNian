package com.yunian.ai.agent.plugin

import com.yunian.ai.agent.tools.ChannelSendTool
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginManifest
import com.yunian.ai.domain.plugin.PluginServices

/**
 * 消息发送**发起方**插件（Cordis 双层插件模板 · 代码插件，kind = **TOOL**）。
 *
 * ## 它做什么
 *
 * [setup] 里向 [ToolRegistry] 注册唯一一个工具 [ChannelSendTool]，并用
 * [PluginContext.effect] 登记注销副作用——**工具与插件同生共死**
 * （Cordis「卸载不留鸡毛」）：插件卸载 → 工具立即从注册表消失，
 * 不存在「工具还在、事件订阅已经没了」的僵尸窗口。
 *
 * ## 为什么发起方是**独立插件**，而不是挂在某个通道插件里
 *
 * §17.6 的架构裁定把「请求方」与「订阅方」分开了：
 * - **请求方**（本插件）只负责「把用户的意图表达成一次出站请求事件」，
 *   它**不认识任何具体通道**——不 import QQ、不 import 微信、不持有任何适配器；
 * - **订阅方**（各通道插件，如 `channel.qqbot`）只负责「认领属于自己 `channelKey` 的请求」
 *   并如实应答。
 *
 * 这样「可自由启用 / 停用通道」才有正确语义：通道插件卸载后，
 * 本插件的工具仍然存在（注册表里还在），但派发该通道的请求会**没人认领**，
 * 于是工具返回「该通道未启用」的**明确失败**——而不是成功、也不是异常。
 * 把发起方塞进通道插件会让工具随通道一起消失，用户看到的是「工具不存在」，
 * 分不清「没启用通道」与「工具坏了」。
 *
 * ## 依赖 fail-closed
 *
 * [requires] 声明 [PluginServices.TOOLS]。宿主未预置该服务时，
 * [com.yunian.ai.domain.plugin.PluginHost.load] 在 setup **之前**即拒绝装载，
 * 不留下任何半装配状态（无注册、无 effect、`isLoaded` 保持 false）。
 *
 * **不**声明 [PluginServices.CHANNELS]：本插件不碰通道注册中心。
 * 事件能力（`bail` / `onBail`）是 [PluginContext] 的**成员**，不是服务键，
 * 因此无需声明依赖即可派发。
 */
class MessageSendPlugin : LianYuPlugin {

    companion object {
        /** 插件 id（蓝图 `assets/blueprints/default.json` 里引用的就是这个字符串）。 */
        const val ID: String = "message.send"

        /** 本插件注册的 Agent 工具名（唯一来源是 [ChannelSendTool.NAME]）。 */
        const val TOOL_NAME: String = ChannelSendTool.NAME
    }

    override val id: String = ID

    override val name: String = "消息发送"

    override val kind: PluginKind = PluginKind.TOOL

    override val requires: Set<String> = setOf(PluginServices.TOOLS)

    override val configSchema: String? = null

    /**
     * 「插件设置」页展示的一句话说明，对应 [setup] 注册的唯一工具
     * [ChannelSendTool]（名字见 [TOOL_NAME] = send_channel_message）。
     *
     * 「需确认」不是修饰语：该工具 requiresConfirmation = true，且 appLocalOnly = true
     * （只在 App 内会话可见，见 [ChannelSendTool] 的「渠道隔离」）。
     */
    override val description: String = "让 AI 把一条消息经指定通道发出（需确认）；停用后 AI 无法主动发消息。"

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = "消息发送",
        version = "1.0.0",
        kind = PluginKind.TOOL,
        requires = requires.sorted(),
        description = description,
        configSchema = null,
    )

    override fun setup(ctx: PluginContext) {
        val registry = ctx.inject<ToolRegistry>(PluginServices.TOOLS)
        // 工具持有**装配期拿到的同一个 ctx**：execute() 里用它派发通道出站事件。
        val tool = ChannelSendTool(ctx)
        registry.register(tool)
        ctx.effect({ registry.unregister(tool.name) }, "unregister:" + tool.name)
    }
}
