package com.yunian.ai.domain.wechat

import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object WeChatProactiveSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 把一条 App 内消息镜像到微信。
     *
     * @param companionId 伴侣 id
     * @param messageId 源消息 id（>0 时出站侧可据此回查消息体）
     * @param finalContent 已清洗的正文文本；图片消息传 null
     * @param media 图片等非文本内容的本地引用；不为空时按 [media.kind] 出站，
     *              不再走文本清洗（否则保留标签 `[图片]` 会被误判成表情包名）
     */
    fun enqueue(
        companionId: Long,
        messageId: Long,
        finalContent: String? = null,
        media: WeChatMediaRef? = null,
    ) {
        if (companionId <= 0L) return
        scope.launch {
            runCatching {
                val port = ServiceRegistry.get(WeChatOutboundPort::class.java) ?: return@runCatching
                port.enqueue(
                    WeChatOutboundRequest(
                        companionId = companionId,
                        sourceMessageId = messageId.takeIf { it > 0L },
                        text = finalContent?.takeIf { it.isNotBlank() },
                        media = media,
                    ),
                )
            }
        }
    }
}
