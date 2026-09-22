package com.yunian.ai.agent.plugin

import android.content.Context
import com.yunian.ai.agent.sticker.StickerPreferenceFacade
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginServices

/**
 * 内置表情包偏好插件（kind=STICKER）。
 *
 * 迁移自 LianYuApplication 的直接调用 `StickerPreferenceFacade.ensureInitialized(app)`：
 * 接线文件系统变更钩子 + 首启全量同步，必须在 markInitialized 之前装载。
 */
class StickerPreferencePlugin : LianYuPlugin {

    companion object {
        const val ID = "sticker.preference"
    }

    override val id: String = ID
    override val name: String = "表情包偏好引擎"
    override val requires: Set<String> = setOf(PluginServices.APP_CONTEXT)
    override val configSchema: String? = null

    override fun setup(ctx: PluginContext) {
        val app = ctx.inject<Context>(PluginServices.APP_CONTEXT)
        StickerPreferenceFacade.ensureInitialized(app)
    }
}
