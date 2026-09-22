package com.yunian.ai.domain.wechat

import kotlinx.coroutines.flow.Flow

interface WeChatChannelGateway {
    suspend fun isLinked(): Boolean
    fun connectionState(): Flow<WeChatConnectionSnapshot>
}

interface WeChatOutboundPort {

    suspend fun enqueue(request: WeChatOutboundRequest): String
}

interface WeChatDialoguePort {
    suspend fun generateReply(request: WeChatDialogueRequest): WeChatDialogueResult
}

interface WeChatIdentityMapPort {
    suspend fun resolveCompanionId(wechatUserId: String): Long?
    suspend fun getOrCreateMapping(wechatUserId: String): Long?
    suspend fun listMappings(): List<WeChatUserMapping>
    suspend fun bind(wechatUserId: String, companionId: Long)
    suspend fun unbind(wechatUserId: String)
}
