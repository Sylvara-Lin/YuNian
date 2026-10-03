package com.yunian.ai.agent.plugin

import android.content.Context
import com.yunian.ai.agent.sticker.StickerPreferenceFacade
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginKind
import com.yunian.ai.domain.plugin.PluginManifest
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
    override val kind: PluginKind = PluginKind.STICKER
    override val requires: Set<String> = setOf(PluginServices.APP_CONTEXT)
    override val configSchema: String? = null

    /**
     * 「插件设置」页展示的一句话说明，对应 [setup] 实际做的事：
     * [StickerPreferenceFacade.ensureInitialized] 接线表情包目录的文件系统变更钩子
     * 并做首启全量同步（文件系统 → 库 → 偏好引擎），引擎据此挑选该发哪张表情包。
     * 停用（未初始化）时引擎不再更新，AI 的表情包挑选随之失效。
     */
    override val description: String = "维护表情包库与使用偏好，决定 AI 选发哪张表情包。"

    override val manifest: PluginManifest = PluginManifest(
        id = ID,
        name = "表情包偏好引擎",
        version = "1.0.0",
        kind = PluginKind.STICKER,
        requires = requires.sorted(),
        description = description,
        configSchema = null,
    )

    override fun setup(ctx: PluginContext) {
        val app = ctx.inject<Context>(PluginServices.APP_CONTEXT)
        StickerPreferenceFacade.ensureInitialized(app)
    }
}
