package com.yunian.ai.domain.timeline

/**
 * 事件溯源账本契约（完整事件溯源增强）。
 *
 * 与现有 [TimelineEvent]/[TimelineStore]（消息投影）互补：
 * - TimelineStore：面向 UI/模型上下文的消息时间线投影（turnId/eventIndex，消息表）；
 * - EventLedger：面向审计/重放/聚合的**不可变追加账本**——流内版本号、幂等键、SHA-256 哈希链、快照。
 *
 * 设计约束：
 * - 只追加不修改/删除（不可变性）；
 * - 同一 streamId 内 sequence 单调递增（并发冲突由实现侧重试解决）；
 * - idempotencyKey 非空时，同 (streamId, key) 重复 append 返回既有事件（重放安全）；
 * - 每条事件携带 prevHash/hash（SHA-256 链），verifyChain 可检测断链/篡改；
 * - 快照保存聚合状态 + 版本，支持从快照重放。
 */

/** 账本事件信封（不可变）。 */
data class LedgerEvent(
    /** 持久化自增 id。 */
    val eventId: Long = 0L,
    /** 流 id（聚合根），如 "agent:companion:42" / "agent:group:7"。 */
    val streamId: String,
    /** 流内版本号（从 1 起，单调递增）。 */
    val sequence: Long,
    /** 事件类型，如 "agent.turn.completed" / "agent.tool.call" / "agent.confirm.request"。 */
    val type: String,
    /** 事件时间戳（ms）。 */
    val timestamp: Long,
    /** 载荷 JSON（不透明字符串，实现侧不做业务解析）。 */
    val payloadJson: String,
    /** 元数据 JSON（关联字段，如 sessionId/scope）。 */
    val metadataJson: String = "{}",
    /** 幂等键（可选；同流内唯一）。 */
    val idempotencyKey: String? = null,
    /** 前一条事件的 hash（首条为空串）。 */
    val prevHash: String = "",
    /** 本事件 SHA-256 链哈希。 */
    val hash: String = "",
)

/** 流快照（聚合状态投影 + 版本）。 */
data class LedgerSnapshot(
    val streamId: String,
    /** 快照对应的最后 sequence。 */
    val version: Long,
    /** 聚合状态 JSON。 */
    val stateJson: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 哈希链校验结果。 */
data class ChainVerification(
    val streamId: String,
    val valid: Boolean,
    val eventsChecked: Int,
    /** 首个断链/篡改事件的 sequence（valid=false 时）。 */
    val firstBrokenSequence: Long? = null,
)

/** 并发追加冲突（同一流内 sequence 被并发占用；调用方可重试）。 */
class LedgerAppendConflict(val streamId: String, val sequence: Long) :
    IllegalStateException("event_ledger append conflict: stream=$streamId seq=$sequence")

/** 事件账本端口（实现在 core:database，经 ServiceRegistry 注入）。 */
interface EventLedger {
    /**
     * 追加事件。幂等：同 (streamId, idempotencyKey) 重复调用返回既有事件；
     * 并发序列冲突抛出 [LedgerAppendConflict]。
     */
    suspend fun append(
        streamId: String,
        type: String,
        payloadJson: String,
        idempotencyKey: String? = null,
        metadataJson: String = "{}",
    ): LedgerEvent

    /** 读取流内全部事件（按 sequence 升序）；afterSequence 用于增量重放。 */
    suspend fun readStream(streamId: String, afterSequence: Long = 0L): List<LedgerEvent>

    /** 校验整条流哈希链（完整性/篡改检测）。 */
    suspend fun verifyChain(streamId: String): ChainVerification

    /** 保存 / 加载流快照。 */
    suspend fun saveSnapshot(snapshot: LedgerSnapshot)
    suspend fun loadSnapshot(streamId: String): LedgerSnapshot?

    /** 最近事件（跨流，审计/诊断用）。 */
    suspend fun recent(limit: Int): List<LedgerEvent>

    /**
     * 快照自动持久化：读取当前流全部事件 → [LedgerProjection.compose] 聚合投影 → 保存快照。
     * 返回保存的快照（流为空返回 null）。
     */
    suspend fun snapshotStream(streamId: String): LedgerSnapshot?
}

/**
 * 事件流聚合投影（纯函数，JVM 可测）：事件列表 → 快照状态 JSON。
 * 用于快照自动持久化与重放起点。
 */
object LedgerProjection {
    fun compose(streamId: String, events: List<LedgerEvent>): LedgerSnapshot {
        val version = events.maxOfOrNull { it.sequence } ?: 0L
        val lastType = events.lastOrNull()?.type.orEmpty()
        val counts = events.groupingBy { it.type }.eachCount()
        val stateJson = buildString {
            append("{\"events\":").append(events.size)
            append(",\"lastSequence\":").append(version)
            append(",\"lastEventType\":\"").append(escape(lastType)).append("\"")
            append(",\"types\":{")
            counts.entries.forEachIndexed { index, (type, count) ->
                if (index > 0) append(",")
                append("\"").append(escape(type)).append("\":").append(count)
            }
            append("}}")
        }
        return LedgerSnapshot(streamId = streamId, version = version, stateJson = stateJson)
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

/**
 * SHA-256 事件链哈希（纯函数；domain 与实现共用，保证校验一致）。
 *
 * 规范化输入：prevHash \u0000 streamId \u0000 sequence \u0000 type \u0000 timestamp \u0000 payloadJson。
 */
object LedgerHash {
    fun hash(
        prevHash: String,
        streamId: String,
        sequence: Long,
        type: String,
        timestamp: Long,
        payloadJson: String,
    ): String {
        val canonical = listOf(prevHash, streamId, sequence.toString(), type, timestamp.toString(), payloadJson)
            .joinToString("\u0000")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
