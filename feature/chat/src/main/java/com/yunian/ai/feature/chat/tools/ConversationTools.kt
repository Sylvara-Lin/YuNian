package com.yunian.ai.feature.chat.tools

import com.yunian.ai.database.AppDatabase
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.ConversationSearchArgs
import com.yunian.ai.domain.ConversationSummary as DomainConversationSummary
import com.yunian.ai.domain.RecentChatsArgs
import com.yunian.ai.domain.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ConversationTools(
    private val database: AppDatabase,
    private val json: Json
) {

    private class RecentChatsTool(
        private val database: AppDatabase,
        private val json: Json
    ) : AiTool {
        override val name = "recent_chats"
        override val description = """
            获取最近的对话会话列表（单聊 + 群聊）。
            用于回答"我们最近聊了什么"、"有哪些活跃对话"等问题。
            返回会话标题、最后消息预览、时间、未读数。
        """.trimIndent()
        override val parametersJsonSchema = """
            {"type":"object","properties":{"limit":{"type":"integer","description":"最多返回数量，默认 20"},"include_groups":{"type":"boolean","description":"是否包含群聊，默认 true"}}}
        """.trimIndent()

        override fun systemPrompt() = "recent_chats: 列出最近会话。参数 {limit?: int, include_groups?: bool}。返回会话数组。"

        override suspend fun execute(argumentsJson: String): String {
            val args = try {
                json.decodeFromString<RecentChatsArgs>(argumentsJson)
            } catch (e: Exception) {
                return buildError("Invalid arguments: ${e.message}")
            }

            return withContext(Dispatchers.IO) {
                val dao = database.conversationSummaryDao()
                val companionSummaries = dao.getSummariesByTypeSync("companion")
                val groupSummaries = dao.getSummariesByTypeSync("group")

                val allSummaries = (companionSummaries + groupSummaries)
                    .sortedByDescending { it.lastMessageTimestamp }
                    .take(args.limit)

                buildJsonObject {
                    put("ok", true)
                    put("chats", buildJsonArray {
                        allSummaries.forEach { summary ->
                            add(buildJsonObject {
                                put("session_id", summary.sessionId)
                                put("session_type", summary.sessionType)
                                put("title", summary.lastMessagePreview.take(50))
                                put("last_message_preview", summary.lastMessagePreview)
                                put("last_message_timestamp", summary.lastMessageTimestamp)
                                put("unread_count", summary.unreadCount)
                                put("companion_id", if (summary.sessionType == "companion") summary.sessionId else null)
                                put("group_id", if (summary.sessionType == "group") summary.sessionId else null)
                            })
                        }
                    })
                }.toString()
            }
        }

        private fun buildError(msg: String) = buildJsonObject {
            put("ok", false)
            put("error", msg)
        }.toString()
    }

    private class ConversationSearchTool(
        private val database: AppDatabase,
        private val json: Json
    ) : AiTool {
        override val name = "conversation_search"
        override val description = """
            在对话历史中全文搜索。
            用于查找特定话题、关键词、事实等。
            支持按会话类型、伴侣、群组筛选。
        """.trimIndent()
        override val parametersJsonSchema = """
            {"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"},"limit":{"type":"integer","description":"最多返回数量，默认 10"},"session_type":{"type":"string","enum":["companion","group"],"description":"会话类型筛选"},"companion_id":{"type":"integer","description":"限定伴侣 ID"},"group_id":{"type":"integer","description":"限定群组 ID"}},"required":["query"]}
        """.trimIndent()

        override fun systemPrompt() = "conversation_search: 搜索历史对话。参数 {query: string, limit?: int, session_type?: 'companion'|'group', companion_id?: int, group_id?: int}。返回匹配片段数组。"

        override suspend fun execute(argumentsJson: String): String {
            val args = try {
                json.decodeFromString<ConversationSearchArgs>(argumentsJson)
            } catch (e: Exception) {
                return buildError("Invalid arguments: ${e.message}")
            }

            if (args.query.isBlank()) {
                return buildError("Query cannot be empty")
            }

            return withContext(Dispatchers.IO) {
                val dao = database.messageDao()
                val results = dao.searchMessages(
                    conversationId = args.companionId ?: 0,
                    type = args.sessionType ?: "companion",
                    query = args.query,
                    limit = args.limit
                ).map { storedMsg ->
                    val msg = storedMsg.toChatMessage()
                    Pair(msg, DomainConversationSummary(
                        sessionId = args.companionId ?: 0,
                        sessionType = args.sessionType ?: "companion",
                        title = "Conversation",
                        lastMessagePreview = msg.content.take(100),
                        lastMessageTimestamp = msg.timestamp,
                        unreadCount = 0,
                        companionId = args.companionId,
                        groupId = args.groupId
                    ))
                }

                buildJsonObject {
                    put("ok", true)
                    put("query", args.query)
                    put("results", buildJsonArray {
                        results.forEach { (msg, summary) ->
                            add(buildJsonObject {
                                put("session_id", summary.sessionId)
                                put("session_type", summary.sessionType)
                                put("title", summary.title)
                                put("matched_content", msg.content.take(200))
                                put("timestamp", msg.timestamp)
                                put("role", if (msg.isFromUser) "user" else "assistant")
                            })
                        }
                    })
                }.toString()
            }
        }

        private fun buildError(msg: String) = buildJsonObject {
            put("ok", false)
            put("error", msg)
        }.toString()
    }

    companion object {
        fun registerAll(database: AppDatabase) {
            val json = Json { ignoreUnknownKeys = true }
            ToolRegistry.register(RecentChatsTool(database, json))
            ToolRegistry.register(ConversationSearchTool(database, json))
        }
    }
}
