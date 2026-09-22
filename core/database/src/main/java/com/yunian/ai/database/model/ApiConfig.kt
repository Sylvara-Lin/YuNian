package com.yunian.ai.database.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yunian.ai.common.SuFlowApi
import kotlinx.serialization.Serializable

@Entity(tableName = "api_configs")
@Serializable
data class ApiConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val provider: ApiProvider,
    val name: String = "",
    val apiKey: String,
    val extraApiKeys: String = "",
    val baseUrl: String,
    val model: String,
    val temperature: Float = 0.7f,
    val maxTokens: Int? = null,
    val isEnabled: Boolean = true,
    val connectionTested: Boolean = false,
    val connectionTestedAt: Long = 0L,
    val latencyMs: Long = 0L,
    val formatHint: String = "openai"
) {
    companion object {
        val BUILTIN_KEYS = mapOf<ApiProvider, List<String>>(

        )
    }

    fun getAllApiKeys(): List<String> {
        val keys = mutableListOf<String>()
        val mainKey = apiKey.trim()
        if (mainKey.isNotEmpty()) keys.add(mainKey)
        if (extraApiKeys.isNotBlank()) {
            keys.addAll(extraApiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }

        if (provider != ApiProvider.PARTNER) {
            val builtin = BUILTIN_KEYS[provider]
            if (builtin != null) {
                keys.addAll(builtin)
            }
        }
        return keys.distinct()
    }

    fun getUserApiKeys(): List<String> {
        val keys = mutableListOf<String>()
        val mainKey = apiKey.trim()
        if (mainKey.isNotEmpty()) keys.add(mainKey)
        if (extraApiKeys.isNotBlank()) {
            keys.addAll(extraApiKeys.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        return keys.distinct()
    }

    fun hasUserKeys(): Boolean = getUserApiKeys().isNotEmpty()

    fun isBuiltinKey(key: String): Boolean {
        return BUILTIN_KEYS[provider]?.contains(key) == true
    }
}

enum class ApiProvider(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    OPENAI("OpenAI", "https://api.openai.com/v1/", "gpt-4o-mini"),
    ANTHROPIC("Claude", "https://api.anthropic.com/v1/", "claude-3-5-sonnet-20241022"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-2.5-flash"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-v4-pro"),
    DASHSCOPE("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/", "qwen-plus"),
    KIMI("Kimi", "https://api.moonshot.cn/v1/", "kimi-k2.6"),
    XIAOMI("小米 MiMo", "https://api.xiaomimimo.com/v1/", "mimo-v2.5-pro"),
    ZHIPU("智谱清言", "https://open.bigmodel.cn/api/paas/v4/", "glm-4-flash"),
    SILICONFLOW("硅基流动", "https://api.siliconflow.cn/v1/", "Qwen/Qwen2.5-7B-Instruct"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/", "openai/gpt-4o-mini"),
    GROQ("Groq", "https://api.groq.com/openai/v1/", "llama-3.1-8b-instant"),

    PARTNER("Clove API", SuFlowApi.CHAT_BASE_URL, "auto"),
    CUSTOM("自定义 API", "", ""),
    IFLYTEK("讯飞星火", "https://spark-api-open.xf-yun.com/v1/", "generalv3.5")
}

@Entity(
    tableName = "api_provider_presets",
    indices = [Index(value = ["provider"], unique = true)]
)
data class ApiProviderPreset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val provider: ApiProvider,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    val formatHint: String = "openai",
    val sortOrder: Int = 0,
    val isVisible: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)
