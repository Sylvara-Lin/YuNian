package com.yunian.ai.database.repository

import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.dao.AppMetaDao
import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.HomeSessionListOperator
import com.yunian.ai.domain.HomeSessionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * [HomeSessionListOperator] 的存储层实现（首页会话长按操作，2026-10-06）。
 *
 * 语义落地（Schema Freeze：不加表不加列，全部复用既有设施）：
 * - 删除该聊天：复用 `ChatRepository.clearChatHistory` / `GroupMessageRepository.clearGroupHistory`
 *   （消息 + 会话摘要一起清，新消息落库时摘要行重建、会话重新出现）；
 *   同时清掉该会话的隐藏标记，避免「删了还被隐藏标记压着」的残留状态。
 * - 顶置：`conversation_summary.isPinned`（既有字段 + 既有 DAO 查询已按 isPinned DESC 排序）。
 * - 隐藏：AppMetaStore 键值持久化（key: home.hidden.chat / home.hidden.group），
 *   value = sessionId -> hiddenAtMs；重显判定由列表装配方完成
 *   （summary.lastMessageTimestamp > hiddenAtMs 即有新消息 → 重显）。
 */
class HomeSessionListStore(
    private val chatRepository: ChatRepository,
    private val groupMessageRepository: GroupMessageRepository,
    private val appMetaStore: AppMetaStore,
    private val appMetaDao: AppMetaDao,
    private val database: AppDatabase,
) : HomeSessionListOperator {

    private val json = Json { ignoreUnknownKeys = true }

    private fun serializer(): KSerializer<Map<Long, Long>> =
        MapSerializer(Long.serializer(), Long.serializer())

    private fun metaKey(type: HomeSessionType): String = when (type) {
        HomeSessionType.CHAT -> KEY_HIDDEN_CHAT
        HomeSessionType.GROUP -> KEY_HIDDEN_GROUP
    }

    override suspend fun deleteConversation(sessionId: Long, type: HomeSessionType) {
        when (type) {
            HomeSessionType.CHAT -> chatRepository.clearChatHistory(sessionId)
            HomeSessionType.GROUP -> groupMessageRepository.clearGroupHistory(sessionId)
        }
        // 隐藏标记随删除一并清除：删除语义已覆盖隐藏语义（两者都指望新消息重显，
        // 留着隐藏时刻只会让重显判定多一个“晚于旧隐藏时刻”的假门槛）。
        val key = metaKey(type)
        val map = appMetaStore.get(key, serializer()) ?: emptyMap()
        if (sessionId in map) {
            appMetaStore.put(key, map - sessionId, serializer())
        }
    }

    /**
     * 顶置 / 取消顶置（切换）。
     *
     * 用 SQL 原子翻转（`isPinned = 1 - isPinned`）而非读-改-写，
     * 消除双击竞态：两次连续调用各自翻转一次，结果确定（回到原值）。
     */
    override suspend fun togglePinned(sessionId: Long, type: HomeSessionType) {
        val sessionType = when (type) {
            HomeSessionType.CHAT -> "chat"
            HomeSessionType.GROUP -> "group"
        }
        database.conversationSummaryDao().togglePinned(sessionId, sessionType)
    }

    override suspend fun hideConversation(sessionId: Long, type: HomeSessionType) {
        val key = metaKey(type)
        val map = appMetaStore.get(key, serializer()) ?: emptyMap()
        appMetaStore.put(key, map + (sessionId to System.currentTimeMillis()), serializer())
    }

    override suspend fun hiddenAtMap(type: HomeSessionType): Map<Long, Long> =
        appMetaStore.get(metaKey(type), serializer()) ?: emptyMap()

    override fun observeHiddenAt(type: HomeSessionType): Flow<Map<Long, Long>> =
        appMetaDao.getFlow(metaKey(type))
            .map { raw ->
                raw?.let {
                    runCatching { json.decodeFromString(serializer(), it) }
                        // 与 AppMetaStore.get 同一模式：损坏记 error（不静默），按无值处理
                        .onFailure { e -> SecureLog.e(TAG, "decode failed, treating as absent. key=${metaKey(type)}", e) }
                        .getOrNull()
                } ?: emptyMap()
            }

    companion object {
        private const val TAG = "HomeSessionListStore"
        private const val KEY_HIDDEN_CHAT = "home.hidden.chat"
        private const val KEY_HIDDEN_GROUP = "home.hidden.group"
    }
}
