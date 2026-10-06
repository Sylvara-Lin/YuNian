package com.yunian.ai.domain

import kotlinx.coroutines.flow.Flow

/**
 * 首页会话列表项的会话类型（单聊 / 群聊）。
 *
 * 与 `conversation_summary.sessionType` 的取值（"chat" / "group"）一一对应；
 * 独立成枚举是为了让 domain 契约不携带存储层字符串约定。
 */
enum class HomeSessionType { CHAT, GROUP }

/**
 * 首页会话长按操作的领域契约（删除该聊天 / 顶置该聊天 / 隐藏该聊天）。
 *
 * 语义（与微信交互对齐，2026-10-06 需求定稿）：
 * - **删除该聊天**：只清除该会话的聊天记录与会话摘要，**不删除角色/群本身**；
 *   会话入口随摘要行一起消失，新消息（含 AI 主动消息）落库时摘要行重建、会话重新出现。
 * - **顶置该聊天**：会话在列表内置顶排序（isPinned），再次长按可取消顶置（切换语义）。
 * - **隐藏该聊天**：仅隐藏会话入口，聊天记录完整保留；记录隐藏时刻，
 *   摘要的 lastMessageTimestamp 晚于该时刻（即有新消息，含 AI 主动消息）时会话重新出现。
 *
 * 实现方：`core:database` 的 HomeSessionListStore（AppMetaStore 持久化 + summary 表排序）；
 * 消费方：`feature:profile` 的 HomeViewModel（UI 层不触碰存储细节）。
 */
interface HomeSessionListOperator {

    /**
     * 删除该聊天：清除会话消息 + 会话摘要（记录保留与否见各会话类型实现），
     * 并清除该会话的隐藏标记（删除语义已覆盖隐藏语义，避免残留状态影响重显判定）。
     */
    suspend fun deleteConversation(sessionId: Long, type: HomeSessionType)

    /**
     * 顶置 / 取消顶置（切换）：置顶的会话排序列表最前，再次长按可取消。
     *
     * **持久化在 AppMetaStore（不依赖 conversation_summary 是否有摘要行）**——
     * 新角色/清记录后的会话没有 summary 行，仍应能顶置。
     */
    suspend fun togglePinned(sessionId: Long, type: HomeSessionType)

    /** 隐藏该聊天：记录隐藏时刻；聊天记录不动。 */
    suspend fun hideConversation(sessionId: Long, type: HomeSessionType)

    /**
     * 当前隐藏时刻表（sessionId -> hiddenAtMs），供列表装配时判定
     * 「隐藏后是否有新消息」（有则重显）。
     */
    suspend fun hiddenAtMap(type: HomeSessionType): Map<Long, Long>

    /**
     * 隐藏时刻表的响应式流（隐藏/取消/删除操作时自动刷新）。
     * combine 进列表装配即可让 UI 实时响应，无需手动刷新缓存。
     */
    fun observeHiddenAt(type: HomeSessionType): Flow<Map<Long, Long>>

    /**
     * 当前顶置状态表（sessionId -> isPinned），供列表装配排序用。
     * 与隐藏同一持久化层（AppMetaStore），不依赖 summary 表是否有摘要行。
     */
    suspend fun pinnedMap(type: HomeSessionType): Map<Long, Boolean>

    /**
     * 顶置状态表的响应式流（顶置/取消顶置时自动刷新）。
     */
    fun observePinned(type: HomeSessionType): Flow<Map<Long, Boolean>>
}
