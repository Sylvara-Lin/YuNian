package com.yunian.ai.domain

/**
 * 会话范围：标识滚动摘要等上下文状态归属的是单聊还是群聊。
 *
 * 零业务依赖（仅 Kotlin 标准库），core:domain 内合法定义，
 * 供 core:network / feature 层共同引用。
 */
sealed class ConversationScope {

    /** 单聊会话：companionId 为角色 id。 */
    data class Single(val companionId: Long) : ConversationScope()

    /** 群聊会话：groupId 为群 id。 */
    data class Group(val groupId: Long) : ConversationScope()

    /**
     * 持久化存储 key（AppMetaStore KV约定：`<模块>.<用途>`）。
     * 同一会话的所有上下文压缩状态共用一个 key。
     */
    fun storeKey(): String = "context.rolling_summary." + when (this) {
        is Single -> "chat-$companionId"
        is Group -> "group-$groupId"
    }
}
