package com.yunian.ai.database.repository

import com.yunian.ai.database.dao.UnifiedMemoryDao
import com.yunian.ai.database.model.MemoryRecord
import com.yunian.ai.database.model.MemoryScope
import com.yunian.ai.database.model.MemorySource
import com.yunian.ai.database.model.MemoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UnifiedMemoryRepository(
    private val dao: UnifiedMemoryDao,
    private val deviceId: String,
    private val embeddingProvider: EmbeddingProvider? = null,
    private val summaryProvider: SummaryProvider? = null
) {

    fun getStableMemories(scope: MemoryScope, sourceId: Long): Flow<List<MemoryRecord>> {
        return dao.getByScope(deviceId, scope, sourceId)
            .map { list -> list.filter { it.memoryType != MemoryType.WORKING } }
    }

    fun getWorkingMemories(scope: MemoryScope, sourceId: Long, limit: Int = 50): Flow<List<MemoryRecord>> {
        return dao.getWorkingMemoriesFlow(deviceId, scope, sourceId, limit)
    }

    fun getPreferences(): Flow<List<MemoryRecord>> {
        return dao.getByType(deviceId, MemoryType.PREFERENCE)
    }

    fun getRelationships(): Flow<List<MemoryRecord>> {
        return dao.getByType(deviceId, MemoryType.RELATIONSHIP)
    }

    suspend fun addMemory(
        content: String,
        type: MemoryType = MemoryType.SEMANTIC,
        scope: MemoryScope = MemoryScope.COMPANION,
        sourceId: Long = 0L,
        source: MemorySource = MemorySource.CHAT,
        importance: Float = 0.5f,
        confidence: Float = 1.0f,
        summary: String = "",
        tags: String = "",
        expiresAt: Long? = null,
        observedAt: Long = System.currentTimeMillis()
    ): Long {

        val exact = dao.findByContent(deviceId, content)
        if (exact != null) {

            dao.updateImportance(
                exact.id,
                minOf(1.0f, exact.importance + 0.05f)
            )
            dao.touch(exact.id)
            return exact.id
        }

        val existing = dao.getByScopeSync(deviceId, scope, sourceId)
        val similar = existing.firstOrNull { existingMemory ->
            jaccardSimilarityBigram(content, existingMemory.content) > 0.75f
        }

        if (similar != null) {

            val newImportance = minOf(1.0f, similar.importance + importance * 0.2f)
            dao.updateImportance(similar.id, newImportance)
            dao.touch(similar.id)
            return similar.id
        }

        val record = MemoryRecord(
            memoryType = type,
            scope = scope,
            source = source,
            content = content,
            summary = summary,
            confidence = confidence,
            importance = importance,
            sourceId = sourceId,
            observedAt = observedAt,
            expiresAt = expiresAt,
            tags = tags,
            deviceId = deviceId
        )
        val newId = dao.insert(record)

        return newId
    }

    suspend fun updateMemory(record: MemoryRecord) {
        dao.insert(record.copy(version = record.version + 1, updatedAt = System.currentTimeMillis()))
    }

    suspend fun addWorkingMemory(
        content: String,
        scope: MemoryScope,
        sourceId: Long,
        ttlMs: Long = 7_200_000L
    ): Long {
        val record = MemoryRecord(
            memoryType = MemoryType.WORKING,
            scope = scope,
            source = MemorySource.CHAT,
            content = content,
            confidence = 0.5f,
            importance = 0.2f,
            sourceId = sourceId,
            expiresAt = System.currentTimeMillis() + ttlMs,
            deviceId = deviceId
        )
        return dao.insert(record)
    }

    suspend fun extractAndSaveMemories(
        scope: MemoryScope,
        sourceId: Long,
        userInput: String,
        aiResponse: String? = null,
        selfName: String? = null
    ) {
        val trimmed = userInput.trim()
        if (trimmed.length < 2) return

        if (aiResponse != null) {
            // selfName 非空时用角色名做标签，让后续摘要以角色视角书写；为空保持原有 AI 标签。
            val aiLabel = selfName?.trim()?.takeIf { it.isNotEmpty() } ?: "AI"
            addWorkingMemory(
                content = "用户: $trimmed | $aiLabel: ${aiResponse.take(200)}",
                scope = scope,
                sourceId = sourceId
            )

            dao.cleanupExpiredWorkingMemories()
        }

        if (aiResponse == null) return

        val now = System.currentTimeMillis()

        if (matchesAny(trimmed, SEMANTIC_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.SEMANTIC,
                scope = scope,
                sourceId = sourceId,
                importance = 0.8f,
                confidence = 0.85f,
                tags = "self-declaration"
            )
        }

        if (matchesAny(trimmed, PREFERENCE_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.PREFERENCE,
                scope = scope,
                sourceId = sourceId,
                importance = 0.7f,
                confidence = 0.8f,
                tags = "preference"
            )
        }

        if (matchesAny(trimmed, EMOTION_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.EPISODIC,
                scope = scope,
                sourceId = sourceId,
                importance = 0.65f,
                confidence = 0.75f,
                tags = "emotion",
                observedAt = now
            )
        }

        if (matchesAny(trimmed, EVENT_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.EPISODIC,
                scope = scope,
                sourceId = sourceId,
                importance = 0.6f,
                confidence = 0.7f,
                tags = "event",
                observedAt = now
            )
        }

        if (matchesAny(trimmed, RELATIONSHIP_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.RELATIONSHIP,
                scope = scope,
                sourceId = sourceId,
                importance = 0.7f,
                confidence = 0.7f,
                tags = "relationship"
            )
        }

        if (matchesAny(trimmed, PROCEDURAL_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.PROCEDURAL,
                scope = scope,
                sourceId = sourceId,
                importance = 0.35f,
                confidence = 0.4f,
                tags = "interaction-pattern"
            )
        }
    }

    suspend fun postProcessMemories(scope: MemoryScope, sourceId: Long, selfName: String? = null) {

        if (embeddingProvider != null) {
            runCatching { backfillEmbeddings(batchSize = 5) }
        }

        runCatching { summarizeAndCompress(scope, sourceId, selfName = selfName) }
    }

    suspend fun buildMemoryContext(
        scope: MemoryScope,
        sourceId: Long,
        userQuery: String,
        limit: Int = 10
    ): String {
        val now = System.currentTimeMillis()

        val allMemories = dao.getByScopeSync(deviceId, scope, sourceId)
            .filter { it.memoryType != MemoryType.WORKING }

        if (allMemories.isEmpty()) return ""

        val semanticBoosts = mutableMapOf<Long, Float>()
        if (embeddingProvider != null && userQuery.length >= 2) {
            val semanticResults = runCatching {
                semanticSearch(scope, sourceId, userQuery, limit * 2)
            }.getOrDefault(emptyList())
            semanticResults.forEachIndexed { index, memory ->

                val boost = (limit - index).coerceAtLeast(0).toFloat() * 0.15f
                semanticBoosts[memory.id] = boost
            }
        }

        val queryTokens = tokenize(userQuery)

        data class Scored(val memory: MemoryRecord, val score: Float)

        val scored = allMemories.map { memory ->
            var score = decayScore(memory, now)

            val matchCount = queryTokens.count { token ->
                memory.content.contains(token, ignoreCase = true) ||
                memory.summary.contains(token, ignoreCase = true) ||
                memory.tags.contains(token, ignoreCase = true)
            }
            score += matchCount * 0.1f

            score += semanticBoosts[memory.id] ?: 0f

            Scored(memory, score)
        }

        val top = scored
            .sortedByDescending { it.score }
            .take(limit)

        if (top.isEmpty()) return ""

        return top.joinToString("\n") { (memory, _) ->
            val timeAgo = formatTimeAgo(now - memory.observedAt)
            val typeLabel = when (memory.memoryType) {
                MemoryType.SEMANTIC -> "事实"
                MemoryType.EPISODIC -> "记忆"
                MemoryType.PREFERENCE -> "偏好"
                MemoryType.RELATIONSHIP -> "关系"
                MemoryType.PROCEDURAL -> "模式"
                else -> "记忆"
            }
            "[$typeLabel | $timeAgo] ${memory.content}"
        }
    }

    suspend fun semanticSearch(
        scope: MemoryScope,
        sourceId: Long,
        query: String,
        limit: Int = 10
    ): List<MemoryRecord> {
        val provider = embeddingProvider ?: return dao.searchInScope(deviceId, scope, sourceId, query, limit)

        val queryEmbedding = provider.embed(query) ?: return dao.searchInScope(deviceId, scope, sourceId, query, limit)

        val candidates = dao.getWithEmbeddings(deviceId, scope, sourceId)
        if (candidates.isEmpty()) return dao.searchInScope(deviceId, scope, sourceId, query, limit)

        val now = System.currentTimeMillis()
        val coarseFiltered = candidates
            .sortedByDescending { decayScore(it, now) }
            .take(limit * 2)

        data class SemanticResult(val memory: MemoryRecord, val similarity: Float)

        val ranked = coarseFiltered.mapNotNull { memory ->
            val embeddingBytes = memory.embedding ?: return@mapNotNull null
            val memoryEmbedding = provider.bytesToFloats(embeddingBytes)
            val similarity = provider.cosineSimilarity(queryEmbedding, memoryEmbedding)
            SemanticResult(memory, similarity)
        }
            .sortedByDescending { it.similarity }
            .take(limit)

        ranked.forEach { dao.touch(it.memory.id) }

        return ranked.map { it.memory }
    }

    suspend fun generateEmbeddingForMemory(memoryId: Long) {
        val provider = embeddingProvider ?: return
        if (!provider.isEmbeddingSupported()) return

        val all = dao.getAllActiveSync(deviceId)
        val memory = all.find { it.id == memoryId } ?: return
        if (memory.embedding != null) return

        val embedding = provider.embed(memory.content) ?: return
        val modelName = provider.getEmbeddingModelName()
        dao.updateEmbedding(memoryId, provider.floatsToBytes(embedding), modelName)
    }

    suspend fun backfillEmbeddings(batchSize: Int = 20): Int {
        val provider = embeddingProvider ?: return 0
        if (!provider.isEmbeddingSupported()) return 0

        val pending = dao.getWithoutEmbeddings(deviceId, batchSize)
        if (pending.isEmpty()) return 0

        val modelName = provider.getEmbeddingModelName()
        var count = 0
        for (memory in pending) {
            val embedding = provider.embed(memory.content) ?: continue
            dao.updateEmbedding(memory.id, provider.floatsToBytes(embedding), modelName)
            count++
        }
        return count
    }

    suspend fun summarizeAndCompress(
        scope: MemoryScope,
        sourceId: Long,
        threshold: Int = 5,
        compressCount: Int = 8,
        selfName: String? = null
    ): Boolean {
        val workingCount = dao.countWorkingMemories(deviceId, scope, sourceId)
        if (workingCount < threshold) return false

        val oldMemories = dao.getOldestWorkingMemories(deviceId, scope, sourceId, compressCount)
        if (oldMemories.isEmpty()) return false

        val conversationText = oldMemories.joinToString("\n") { it.content }

        val memoryContext = buildMemoryContext(scope, sourceId, "", limit = 5)

        val summary = if (summaryProvider != null && summaryProvider.isSummarySupported()) {
            summaryProvider.summarize(
                conversationText,
                memoryContext,
                SummaryPurpose.MEMORY,
                selfName = selfName
            )
        } else null

        var finalSummary = summary ?: buildLocalSummaryFallback(oldMemories)

        if (finalSummary.isBlank()) return false

        if (summary != null) {
            finalSummary = saveAiIdentifiedCoreMemories(summary, scope, sourceId)
        }

        addMemory(
            content = finalSummary,
            type = MemoryType.EPISODIC,
            scope = scope,
            sourceId = sourceId,
            importance = 0.55f,
            confidence = 0.7f,
            summary = finalSummary,
            tags = "conversation-summary",
            observedAt = System.currentTimeMillis()
        )

        val idsToDelete = oldMemories.map { it.id }
        dao.softDeleteByIds(idsToDelete)

        return true
    }

    suspend fun recognizeCoreMemories(
        conversationText: String,
        scope: MemoryScope,
        sourceId: Long,
        selfName: String? = null
    ) {
        val provider = summaryProvider ?: return
        if (conversationText.isBlank()) return
        val memoryContext = buildMemoryContext(scope, sourceId, "", limit = 5)
        val summary = provider.identifyCoreMemories(conversationText, memoryContext, selfName = selfName) ?: return
        if (!summary.contains("【重要记忆】")) return
        saveAiIdentifiedCoreMemories(summary, scope, sourceId)
    }

    private suspend fun saveAiIdentifiedCoreMemories(summary: String, scope: MemoryScope, sourceId: Long): String {
        val section = Regex("【重要记忆】([\\s\\S]*?)(?=【|$)").find(summary)?.groupValues?.get(1)?.trim()
        if (section.isNullOrBlank()) return summary

        section.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(3)
            .forEach { line ->
                val parts = line.split("|", limit = 2)
                val category = parts.getOrNull(0)?.trim().orEmpty()
                val content = parts.getOrNull(1)?.trim().orEmpty()
                if (content.length < 2) return@forEach

                val (type, importance) = when (category) {
                    "偏好" -> MemoryType.PREFERENCE to 0.85f
                    "关系" -> MemoryType.RELATIONSHIP to 0.85f
                    "事件" -> MemoryType.EPISODIC to 0.7f
                    else -> MemoryType.SEMANTIC to 0.85f
                }
                runCatching {
                    addMemory(
                        content = content,
                        type = type,
                        scope = scope,
                        sourceId = sourceId,
                        importance = importance,
                        confidence = 0.85f,
                        tags = "ai-identified-core",
                        observedAt = System.currentTimeMillis()
                    )
                }
            }

        return summary
            .replace(Regex("【重要记忆】([\\s\\S]*?)(?=【|$)"), "")
            .trim()
    }

    private fun buildLocalSummaryFallback(memories: List<MemoryRecord>): String {
        if (memories.isEmpty()) return ""

        val userMentions = mutableListOf<String>()
        val aiMentions = mutableListOf<String>()
        val earliest = memories.minOfOrNull { it.observedAt } ?: 0L
        val latest = memories.maxOfOrNull { it.observedAt } ?: 0L

        memories.forEach { mem ->
            val content = mem.content
            when {
                content.startsWith("用户:") ->
                    userMentions.add(content.removePrefix("用户:").trim())
                content.startsWith("AI:") ->
                    aiMentions.add(content.removePrefix("AI:").trim())
                else -> userMentions.add(content.trim())
            }
        }

        val timeSpan = when {
            earliest <= 0L || latest <= 0L -> "未明确"
            earliest == latest -> formatTimeAgo(System.currentTimeMillis() - latest)
            else -> {
                val spanMin = ((latest - earliest) / 60000L).coerceAtLeast(0)
                "约${spanMin}分钟跨度，最近 ${formatTimeAgo(System.currentTimeMillis() - latest)}"
            }
        }

        return buildString {
            appendLine("时间：$timeSpan；覆盖 ${memories.size} 条工作记忆")
            appendLine(
                "事件：" + if (userMentions.isNotEmpty()) {
                    userMentions.distinct().take(6).joinToString("；")
                } else "未明确"
            )
            appendLine("人物：用户与 AI 对话")
            appendLine(
                "驱动：" + if (aiMentions.isNotEmpty()) {
                    aiMentions.distinct().take(4).joinToString("；")
                } else "未明确"
            )
            append("情绪：未明确（本地回退摘要）")
        }.trim()
    }

    private fun decayScore(memory: MemoryRecord, now: Long): Float {
        val importance = memory.importance.coerceIn(0f, 1f)
        val accessBonus = 1f + kotlin.math.log10((memory.accessCount + 1).toFloat()) * 0.2f
        val rawScore = importance * accessBonus

        val ageMs = now - memory.observedAt
        val ageDays = (ageMs / 86_400_000f).coerceAtLeast(0f)
        val lambda = 0.05f
        val decayFactor = kotlin.math.exp(-lambda * ageDays)

        return rawScore * decayFactor
    }

    suspend fun softDelete(id: Long) {
        dao.softDelete(id)
    }

    suspend fun softDeleteByScopeAndSource(scope: MemoryScope, sourceId: Long, source: MemorySource) {
        dao.softDeleteByScopeAndSource(deviceId, scope, sourceId, source)
    }

    suspend fun hardDeleteByScope(scope: MemoryScope, sourceId: Long) {
        dao.hardDeleteByScope(deviceId, scope, sourceId)
    }

    suspend fun cleanupExpired() {
        dao.cleanupExpiredWorkingMemories()
    }

    suspend fun count(scope: MemoryScope, sourceId: Long): Int {
        return dao.countByScope(deviceId, scope, sourceId)
    }

    private fun tokenize(text: String): List<String> {
        return text.split("""\s+""".toRegex())
            .flatMap { word ->

                if (word.any { it in '\u4e00'..'\u9fff' }) {
                    word.windowed(2, 1).filter { it.length == 2 }
                } else {
                    listOf(word.lowercase())
                }
            }
            .filter { it.length >= 2 }
    }

    private fun jaccardSimilarityBigram(a: String, b: String): Float {
        if (a.length < 2 || b.length < 2) {
            return if (a == b) 1.0f else 0.0f
        }
        val bigrams1 = a.windowed(2).toSet()
        val bigrams2 = b.windowed(2).toSet()
        val intersection = bigrams1.intersect(bigrams2).size
        val union = bigrams1.union(bigrams2).size
        return if (union == 0) 0.0f else intersection.toFloat() / union
    }

    private fun matchesAny(text: String, patterns: List<String>): Boolean {
        return patterns.any { text.contains(it) }
    }

    private fun formatTimeAgo(deltaMs: Long): String {
        val seconds = deltaMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 7 -> "${days}天前"
            days < 30 -> "${days / 7}周前"
            days < 365 -> "${days / 30}个月前"
            else -> "${days / 365}年前"
        }
    }

    companion object {

        val SEMANTIC_PATTERNS = listOf(
            "我叫", "我是", "我来自", "我工作", "职业是", "我的", "我姓",
            "我住在", "我住", "我学", "专业是", "我是做", "我在",
            "年龄", "岁", "生日", "星座", "血型", "身高", "体重",
            "电话", "微信", "qq", "邮箱", "地址", "公司", "学校"
        )

        val PREFERENCE_PATTERNS = listOf(
            "我喜欢", "我讨厌", "我爱吃", "我不爱吃", "我最爱", "我不喜欢",
            "我最讨厌", "我反感", "我厌恶", "我热衷", "我痴迷", "我感兴趣", "我没兴趣",
            "好吃", "难吃", "好看", "难看", "好听", "好玩", "无聊"
        )

        val EMOTION_PATTERNS = listOf(
            "我很开心", "我很难过", "我很生气", "我很感动", "我很兴奋",
            "好开心", "好难过", "好生气", "好感动", "好失望",
            "好累", "好烦", "好爽", "好委屈", "好害怕", "好担心",
            "压力大", "心情不好", "心情很好", "情绪不好",
            "想哭", "哭了", "笑死", "笑哭了"
        )

        val EVENT_PATTERNS = listOf(
            "今天", "昨天", "明天", "上周", "下周", "周末", "放假", "考试",
            "出差", "旅行", "聚会", "约会", "面试", "入职", "离职", "搬家"
        )

        val RELATIONSHIP_PATTERNS = listOf(
            "你是我的", "你是我", "我们之间", "我觉得你", "你对我",
            "最好的朋友", "男朋友", "女朋友", "老公", "老婆", "宝贝",
            "我想你", "我爱你", "我喜欢你", "我离不开你"
        )

        val PROCEDURAL_PATTERNS = listOf(
            "你总是", "你每次都", "你从来不", "你一直", "你别再",
            "你应该", "你不要", "你能不能不"
        )
    }
}
