package com.yunian.ai.agent.plugin

import android.content.Context
import com.yunian.ai.agent.AgentFacade
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginManifest
import com.yunian.ai.domain.plugin.PluginServices

/**
 * 内置技能插件：`builtin_chat_tool_protocol`（微信聊天红线 + 气泡工具协议）。
 *
 * 迁移自 LianYuApplication 的直接调用 `AgentFacade.seedBuiltinChatToolSkill(app)`：
 * 同一幂等种子，改为插件形态（kind=SKILL，依赖 appContext），后续可经 PluginHost
 * 卸载 / 蓝图配置管理。
 */
class BuiltinChatSkillPlugin : LianYuPlugin {

    companion object {
        const val ID = "skill.builtin_chat_protocol"
    }

    override val id: String = ID
    override val name: String = "内置聊天工具协议技能"
    override val kind: PluginKind = PluginKind.SKILL
    override val requires: Set<String> = setOf(PluginServices.APP_CONTEXT)
    override val configSchema: String? = null

    /**
     * 「插件设置」页展示的一句话说明，对应 [setup] 实际做的事：幂等写入内置技能
     * builtin_chat_tool_protocol（见 [AgentFacade.seedBuiltinChatToolSkill]）。
     * 该技能内容规定了 AI 何时改用工具输出（emit_bubble / emit_segmented / send_sticker），
     * 因此它直接决定回复的气泡分段与节奏；停用（未写入）时 AI 退回普通文本回复。
     */
    override val description: String = "内置聊天规范技能，规定 AI 何时改用气泡与表情工具输出。"

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = "内置聊天工具协议技能",
        version = "1.0.0",
        kind = PluginKind.SKILL,
        requires = requires.sorted(),
        description = description,
        configSchema = null,
    )

    override fun setup(ctx: PluginContext) {
        val app = ctx.inject<Context>(PluginServices.APP_CONTEXT)
        if (!AgentFacade.seedBuiltinChatToolSkill(app)) {
            throw IllegalStateException("内置聊天工具协议技能种子写入失败")
        }
    }
}
