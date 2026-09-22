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
    override val requires: Set<String> = setOf(PluginServices.APP_CONTEXT)
    override val configSchema: String? = null

    override fun setup(ctx: PluginContext) {
        val app = ctx.inject<Context>(PluginServices.APP_CONTEXT)
        if (!AgentFacade.seedBuiltinChatToolSkill(app)) {
            throw IllegalStateException("内置聊天工具协议技能种子写入失败")
        }
    }
}
