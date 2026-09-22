package com.yunian.ai.database.model

import kotlinx.serialization.Serializable

/**
 * 记忆时间锚点：显式记录"何时发生、多久前、是否周期性"。
 *
 * 序列化为 JSON 存储在 unified_memories.temporalAnchor 列中。
 *
 * @param occurredAt      事件发生的绝对时间戳（毫秒）
 * @param relativeLabel   相对时间标签（如 "昨天" "上周" "3天前"）
 * @param recurrencePattern 周期性模式（如 "每周一" "每月初"），null 表示非周期性
 * @param isStillValid    该记忆在当前时间是否仍然有效
 * @param decayLambda     时间衰减系数（0=不衰减，越大越快衰减）
 */
@Serializable
data class MemoryTemporalAnchor(
    val occurredAt: Long = 0L,
    val relativeLabel: String = "",
    val recurrencePattern: String? = null,
    val isStillValid: Boolean = true,
    val decayLambda: Float = 0.05f
)
