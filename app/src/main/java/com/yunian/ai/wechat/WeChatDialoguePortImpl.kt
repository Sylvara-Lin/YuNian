package com.yunian.ai.wechat

import android.content.Context
import com.yunian.ai.domain.DialogueCoordinator
import com.yunian.ai.domain.DialogueRequest
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatDialoguePort
import com.yunian.ai.domain.wechat.WeChatDialogueRequest
import com.yunian.ai.domain.wechat.WeChatDialogueResult
import com.yunian.ai.domain.wechat.WeChatInboundMessage
import com.yunian.ai.feature.wechat.WeChatDebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 微信入站 → AI 回复的 app 侧适配（纯净桥接层）。
 *
 * 职责：解析 inbound → 调用统一 AI 对话中间层 [DialogueCoordinator] → 组装
 * [WeChatDialogueResult]（提取 stickerLabels 供 Outbox 表情链路）。
 *
 * AI 回合、输入/输出安全检查、封禁判定、落库、记忆提取全部收敛在中间层
 * （`core:agent` 的 `AgentDialogueCoordinator` 实现），本类不再触碰任何 AI 管线 /
 * 仓库 / 安全组件。通道侧（CDN、Outbox、表情字节）仍由 `feature:wechat` Bridge 负责。
 */
class WeChatDialoguePortImpl(
    private val appContext: Context,
) : WeChatDialoguePort {

    private val dialogueCoordinator: DialogueCoordinator
        get() = ServiceRegistry.getOrThrow(DialogueCoordinator::class.java)

    override suspend fun generateReply(request: WeChatDialogueRequest): WeChatDialogueResult =
        withContext(Dispatchers.IO) {
            val companionId = request.companionId
            val inbound = request.inbound
            WeChatDebugLog.log("[Dialogue] generateReply START companion=$companionId")

            val imagePath = inbound.primaryImagePath()
            val text = inbound.primaryText?.trim().orEmpty()

            // 纯净桥接：AI 回合 / 安全过滤 / 封禁判定 / 落库 / 记忆全部收敛在中间层。
            val result = dialogueCoordinator.generateReply(
                DialogueRequest(
                    companionId = companionId,
                    text = if (imagePath == null) text else null,
                    imagePath = imagePath,
                )
            )

            WeChatDebugLog.log(
                "[Dialogue] generateReply done companion=$companionId blocked=${result.blocked} reply_len=${result.replyText.length}"
            )
            WeChatDialogueResult(
                replyText = result.replyText,
                stickerLabels = extractStickerLabels(result.replyText),
                blocked = result.blocked,
                assistantMessageId = result.assistantMessageId,
                assistantMessageIds = result.assistantMessageId?.let { listOf(it) } ?: emptyList(),
            )
        }

    private fun WeChatInboundMessage.primaryImagePath(): String? {
        parts.firstOrNull { it.kind == WeChatContentKind.IMAGE }
            ?.media
            ?.localPath
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return parts.firstNotNullOfOrNull { part ->
            part.media?.localPath?.takeIf { path ->
                path.isNotBlank() && part.kind == WeChatContentKind.IMAGE
            }
        }
    }

    /**
     * 粗提取 `[标签]` 供 ContentPipeline 后续解析；通道侧仍可用完整 replyText 做 StickerManager 匹配。
     */
    private fun extractStickerLabels(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val systemTags = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")
        return Regex("\\[([^\\[\\]]+?)\\]")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && it !in systemTags }
            .distinct()
            .toList()
    }
}
