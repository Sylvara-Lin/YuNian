package com.yunian.ai.database.model

import com.yunian.ai.common.CompanionRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("RP")
data class RoleProfile(
    val role: CompanionRole = CompanionRole.GIRLFRIEND,
    val name: String,
    val age: Int? = null,

    val avatarUrl: String? = null,
    val personality: String,
    val backstory: String? = null,
    val speakingStyle: String? = null,
    val rawPrompt: String? = null,
    val systemPrompt: String? = null,
    val tags: String? = null,
    val bodyType: String? = null,
    val profession: String? = null,
    val personalityTags: String? = null
) {

    fun applyTo(companion: CompanionEntity): CompanionEntity = companion.copy(
        name = name,
        age = age,
        avatarUrl = avatarUrl ?: companion.avatarUrl,
        personality = personality,
        backstory = backstory,
        speakingStyle = speakingStyle,
        rawPrompt = rawPrompt ?: personality,
        systemPrompt = resolvedSystemPrompt(),
        tags = tags,
        updatedAt = System.currentTimeMillis()
    )

    fun createCompanion(now: Long = System.currentTimeMillis()): CompanionEntity = CompanionEntity(
        name = name,
        avatarUrl = avatarUrl,
        age = age,
        personality = personality,
        backstory = backstory,
        speakingStyle = speakingStyle,
        tags = tags,
        rawPrompt = rawPrompt ?: personality,
        systemPrompt = resolvedSystemPrompt(),
        createdAt = now,
        updatedAt = now
    )

    fun resolvedSystemPrompt(): String {
        systemPrompt?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return buildString {
            appendLine("名字：$name")
            age?.let { appendLine("年龄：${it}岁") }
            appendLine("人设：$personality")
            speakingStyle?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("说话风格：$it")
            }
            backstory?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("背景：$it")
            }
            rawPrompt?.trim()?.takeIf {
                it.isNotBlank() && it != personality && !personality.contains(it)
            }?.let {
                appendLine("补充设定：$it")
            }
        }.trim()
    }
}
