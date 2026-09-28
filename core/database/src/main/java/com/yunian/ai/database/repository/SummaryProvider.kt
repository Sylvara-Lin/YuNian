package com.yunian.ai.database.repository

enum class SummaryPurpose {
    HISTORY,
    MEMORY
}

interface SummaryProvider {

    fun isSummarySupported(): Boolean

    suspend fun summarize(conversationText: String, memoryContext: String = ""): String? {
        return summarize(conversationText, memoryContext, SummaryPurpose.MEMORY)
    }

    suspend fun summarize(
        conversationText: String,
        memoryContext: String,
        purpose: SummaryPurpose,
        selfName: String? = null
    ): String?

    suspend fun identifyCoreMemories(
        conversationText: String,
        memoryContext: String = "",
        selfName: String? = null
    ): String? = null

    /**
     * 滚动摘要增量合并：把新增对话内容（[newMessagesText]）合并进旧摘要
     * [oldSummary]，输出一份去重后的整合摘要。返回 null 表示不支持或失败，
     * 由调用方降级处理。默认实现返回 null，向后兼容既有实现。
     */
    suspend fun summarizeRollingMerge(
        oldSummary: String,
        newMessagesText: String,
        memoryContext: String = "",
        selfName: String? = null
    ): String? = null

    /**
     * 片段摘要：对超长新增内容的单个片段独立生成摘要（后续按序合并）。
     * 返回 null 表示不支持或失败。默认实现返回 null，向后兼容既有实现。
     */
    suspend fun summarizeSegment(
        segmentText: String,
        memoryContext: String = "",
        selfName: String? = null
    ): String? = null
}
