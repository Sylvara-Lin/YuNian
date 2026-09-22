package com.yunian.ai.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsStore(context: Context) {

    private val dataStore = context.applicationContext.appSettingsDataStore

    private companion object {
        private val SHOW_REASONING_KEY = booleanPreferencesKey("show_reasoning")
        private const val DEFAULT_SHOW_REASONING = false

        private val REASONING_RESPONSE_FIELD_KEY = stringPreferencesKey("reasoning_response_field")
        private const val DEFAULT_REASONING_RESPONSE_FIELD = "reasoning_content"

        private val REASONING_REQUEST_FIELD_KEY = stringPreferencesKey("reasoning_request_field")
        private const val DEFAULT_REASONING_REQUEST_FIELD = "reasoning_content"

        private val SEND_REASONING_KEY = booleanPreferencesKey("send_reasoning")
        private const val DEFAULT_SEND_REASONING = false

        private val AUTO_COLLAPSE_REASONING_KEY = booleanPreferencesKey("auto_collapse_reasoning")
        private const val DEFAULT_AUTO_COLLAPSE_REASONING = true

        private val VISION_ENABLED_KEY = booleanPreferencesKey("vision_enabled")
        private const val DEFAULT_VISION_ENABLED = true

        private val VISION_MODEL_KEY = stringPreferencesKey("vision_model")
        private const val DEFAULT_VISION_MODEL = "auto"

        private val VISION_PROVIDER_KEY = stringPreferencesKey("vision_provider")
        private const val DEFAULT_VISION_PROVIDER = "auto"

        private val VISION_API_URL_KEY = stringPreferencesKey("vision_api_url")
        private const val DEFAULT_VISION_API_URL = ""

        private val VISION_API_KEY_KEY = stringPreferencesKey("vision_api_key")
        private const val DEFAULT_VISION_API_KEY = ""

        private val DIARY_ENABLED_KEY = booleanPreferencesKey("diary_enabled")
        private const val DEFAULT_DIARY_ENABLED = false

        private val DIARY_MODEL_KEY = stringPreferencesKey("diary_model")
        private const val DEFAULT_DIARY_MODEL = ""

        private val DIARY_BASE_URL_KEY = stringPreferencesKey("diary_base_url")
        private const val DEFAULT_DIARY_BASE_URL = ""

        private val DIARY_API_KEY_KEY = stringPreferencesKey("diary_api_key")
        private const val DEFAULT_DIARY_API_KEY = ""

        private val INNER_THOUGHT_ENABLED_KEY = booleanPreferencesKey("inner_thought_enabled")
        private const val DEFAULT_INNER_THOUGHT_ENABLED = false

        private val YANDERE_MODE_ENABLED_KEY = booleanPreferencesKey("yandere_mode_enabled")
        private const val DEFAULT_YANDERE_MODE_ENABLED = false

        private val YANDERE_MODE_USAGE_STATS_KEY = booleanPreferencesKey("yandere_mode_usage_stats")
        private const val DEFAULT_YANDERE_MODE_USAGE_STATS = true

        private val YANDERE_MODE_INSTALLED_APPS_KEY = booleanPreferencesKey("yandere_mode_installed_apps")
        private const val DEFAULT_YANDERE_MODE_INSTALLED_APPS = true

        private val SHOW_TYPING_SPINNER_KEY = booleanPreferencesKey("show_typing_spinner")
        private const val DEFAULT_SHOW_TYPING_SPINNER = false
        private val SEARCH_API_KEY_KEY = stringPreferencesKey("search_api_key")
        private const val DEFAULT_SEARCH_API_KEY = ""

        // ---------------- AI 生图（OpenAI 兼容 /images/generations） ----------------
        private val IMAGE_GEN_ENABLED_KEY = booleanPreferencesKey("image_gen_enabled")
        private const val DEFAULT_IMAGE_GEN_ENABLED = false

        private val IMAGE_GEN_PROVIDER_KEY = stringPreferencesKey("image_gen_provider")
        private const val DEFAULT_IMAGE_GEN_PROVIDER = "auto"

        private val IMAGE_GEN_BASE_URL_KEY = stringPreferencesKey("image_gen_base_url")
        private const val DEFAULT_IMAGE_GEN_BASE_URL = ""

        private val IMAGE_GEN_API_KEY_KEY = stringPreferencesKey("image_gen_api_key")
        private const val DEFAULT_IMAGE_GEN_API_KEY = ""

        private val IMAGE_GEN_MODEL_KEY = stringPreferencesKey("image_gen_model")
        private const val DEFAULT_IMAGE_GEN_MODEL = ""

        private val IMAGE_GEN_MODEL_LIST_KEY = stringPreferencesKey("image_gen_model_list")
        private const val DEFAULT_IMAGE_GEN_MODEL_LIST = ""

        private val IMAGE_GEN_SIZE_KEY = stringPreferencesKey("image_gen_size")
        private const val DEFAULT_IMAGE_GEN_SIZE = "1024x1024"

        private val IMAGE_GEN_COUNT_KEY = intPreferencesKey("image_gen_count")
        private const val DEFAULT_IMAGE_GEN_COUNT = 1

        private val IMAGE_GEN_TRIGGER_PROBABILITY_KEY = intPreferencesKey("image_gen_trigger_probability")
        private const val DEFAULT_IMAGE_GEN_TRIGGER_PROBABILITY = 0

        private val IMAGE_GEN_KEYWORDS_KEY = stringPreferencesKey("image_gen_keywords")
        // 与 ImageGenDefaults.DEFAULT_KEYWORDS 共用同一份定义，避免两处漂移
        private val DEFAULT_IMAGE_GEN_KEYWORDS: String =
            ImageGenDefaults.joinKeywords(ImageGenDefaults.DEFAULT_KEYWORDS)

        private val IMAGE_GEN_COOLDOWN_MINUTES_KEY = intPreferencesKey("image_gen_cooldown_minutes")
        private const val DEFAULT_IMAGE_GEN_COOLDOWN_MINUTES = 3

        private val IMAGE_GEN_PROMPT_TEMPLATE_KEY = stringPreferencesKey("image_gen_prompt_template")
        private const val DEFAULT_IMAGE_GEN_PROMPT_TEMPLATE = "{content}"

        private val IMAGE_GEN_TOAST_ENABLED_KEY = booleanPreferencesKey("image_gen_toast_enabled")
        private const val DEFAULT_IMAGE_GEN_TOAST_ENABLED = true

        /** 冷却时间戳按会话持久化，key 形如 image_gen_last_at_12 */
        private fun imageGenLastAtKey(companionId: Long) = longPreferencesKey("image_gen_last_at_$companionId")
    }

    object VisionModels {
        const val VISION_AUTO = "auto"
        const val VISION_GPT4O = "gpt-4o"
        const val VISION_GPT4_VISION = "gpt-4-vision-preview"
        const val VISION_CLAUDE_SONNET = "claude-3-5-sonnet-20241022"
        const val VISION_GEMINI_PRO = "gemini-1.5-pro-vision"
        const val VISION_DEEPSEEK_VL = "deepseek-vl"
        const val VISION_KIMI_K26 = "kimi-k2.6"

        val VISION_MODEL_OPTIONS = listOf(
            Triple(VISION_AUTO, "自动检测", "根据当前AI提供商自动选择视觉模型"),
            Triple(VISION_GPT4O, "GPT-4o", "OpenAI 多模态模型"),
            Triple(VISION_GPT4_VISION, "GPT-4 Vision", "OpenAI 视觉模型"),
            Triple(VISION_CLAUDE_SONNET, "Claude 3.5 Sonnet Vision", "Anthropic 视觉模型"),
            Triple(VISION_GEMINI_PRO, "Gemini Pro Vision", "Google 视觉模型"),
            Triple(VISION_DEEPSEEK_VL, "DeepSeek-VL", "DeepSeek 视觉模型"),
            Triple(VISION_KIMI_K26, "Kimi K2.6", "Moonshot AI 最新视觉模型")
        )

        fun getVisionModelDisplayName(modelId: String): String {
            return VISION_MODEL_OPTIONS.find { it.first == modelId }?.second ?: modelId
        }

        fun resolveVisionModel(visionModelSetting: String, providerName: String): String {
            if (visionModelSetting != VISION_AUTO) return visionModelSetting
            return when (providerName.lowercase()) {
                "openai", "partner" -> VISION_GPT4O
                "anthropic" -> VISION_CLAUDE_SONNET
                "gemini", "google" -> VISION_GEMINI_PRO
                "deepseek" -> VISION_DEEPSEEK_VL
                "kimi", "moonshot" -> VISION_KIMI_K26
                "custom" -> VISION_GPT4O
                else -> VISION_GPT4O
            }
        }
    }

    /**
     * AI 生图模块的默认值与常量。
     *
     * 配置整体走 DataStore，不做数据库变更；单聊覆盖见 feature:chat 的
     * CompanionChatDetailSettings。
     */
    object ImageGenDefaults {
        val SIZE_OPTIONS = listOf(
            "512x512" to "512 × 512（最快）",
            "768x768" to "768 × 768",
            "1024x1024" to "1024 × 1024（推荐）",
            "1024x1792" to "1024 × 1792（竖版）",
            "1792x1024" to "1792 × 1024（横版）"
        )

        val DEFAULT_KEYWORDS = listOf(
            // 用户侧说法
            "画一张", "画个", "画一下", "给我画", "画出来", "画一幅", "画一副",
            // "生成一张" 必须单独收：单靠 "生成图片" 会漏掉「再生成一张」这种最常见的追问（真实踩坑）
            "生成一张", "生成图片", "来张图", "来一张", "生图",
            // AI 侧说法：模型答应「帮你生成」时也要能触发
            "帮你生成", "帮你画", "给你画", "给你生成", "帮你生成一张",
        )

        /** 概率预设档位，用于设置页快捷选择。 */
        val PROBABILITY_PRESETS = listOf(
            0 to "关闭",
            10 to "低 10%",
            30 to "中 30%",
            60 to "高 60%",
            100 to "总是 100%"
        )

        fun parseKeywords(raw: String): List<String> =
            raw.split('\n', ',', '，')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        fun joinKeywords(keywords: List<String>): String =
            keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
    }

    val showReasoningFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHOW_REASONING_KEY] ?: DEFAULT_SHOW_REASONING
    }

    suspend fun getShowReasoning(): Boolean = showReasoningFlow.first()

    suspend fun setShowReasoning(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SHOW_REASONING_KEY] = enabled }
    }

    val reasoningResponseFieldFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[REASONING_RESPONSE_FIELD_KEY] ?: DEFAULT_REASONING_RESPONSE_FIELD
    }

    suspend fun getReasoningResponseField(): String = reasoningResponseFieldFlow.first()

    suspend fun setReasoningResponseField(field: String) {
        dataStore.edit { prefs ->
            prefs[REASONING_RESPONSE_FIELD_KEY] =
                field.ifBlank { DEFAULT_REASONING_RESPONSE_FIELD }
        }
    }

    val reasoningRequestFieldFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[REASONING_REQUEST_FIELD_KEY] ?: DEFAULT_REASONING_REQUEST_FIELD
    }

    suspend fun getReasoningRequestField(): String = reasoningRequestFieldFlow.first()

    suspend fun setReasoningRequestField(field: String) {
        dataStore.edit { prefs ->
            prefs[REASONING_REQUEST_FIELD_KEY] =
                field.ifBlank { DEFAULT_REASONING_REQUEST_FIELD }
        }
    }

    val sendReasoningFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SEND_REASONING_KEY] ?: DEFAULT_SEND_REASONING
    }

    suspend fun getSendReasoning(): Boolean = sendReasoningFlow.first()

    suspend fun setSendReasoning(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SEND_REASONING_KEY] = enabled }
    }

    val autoCollapseReasoningFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_COLLAPSE_REASONING_KEY] ?: DEFAULT_AUTO_COLLAPSE_REASONING
    }

    suspend fun getAutoCollapseReasoning(): Boolean = autoCollapseReasoningFlow.first()

    suspend fun setAutoCollapseReasoning(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[AUTO_COLLAPSE_REASONING_KEY] = enabled }
    }

    suspend fun saveThinkingSettings(
        showReasoning: Boolean,
        autoCollapseReasoning: Boolean,
        responseField: String,
        requestField: String,
        sendReasoning: Boolean
    ) {
        dataStore.edit { prefs ->
            prefs[SHOW_REASONING_KEY] = showReasoning
            prefs[AUTO_COLLAPSE_REASONING_KEY] = autoCollapseReasoning
            prefs[REASONING_RESPONSE_FIELD_KEY] =
                responseField.ifBlank { DEFAULT_REASONING_RESPONSE_FIELD }
            prefs[REASONING_REQUEST_FIELD_KEY] =
                requestField.ifBlank { DEFAULT_REASONING_REQUEST_FIELD }
            prefs[SEND_REASONING_KEY] = sendReasoning
        }
    }

    val visionEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[VISION_ENABLED_KEY] ?: DEFAULT_VISION_ENABLED
    }

    suspend fun getVisionEnabled(): Boolean = visionEnabledFlow.first()

    suspend fun setVisionEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[VISION_ENABLED_KEY] = enabled }
    }

    val visionModelFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[VISION_MODEL_KEY] ?: DEFAULT_VISION_MODEL
    }

    suspend fun getVisionModel(): String = visionModelFlow.first()

    suspend fun setVisionModel(model: String) {
        dataStore.edit { prefs -> prefs[VISION_MODEL_KEY] = model }
    }

    val visionProviderFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[VISION_PROVIDER_KEY] ?: DEFAULT_VISION_PROVIDER
    }

    suspend fun getVisionProvider(): String = visionProviderFlow.first()

    suspend fun setVisionProvider(provider: String) {
        dataStore.edit { prefs -> prefs[VISION_PROVIDER_KEY] = provider }
    }

    val visionApiUrlFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[VISION_API_URL_KEY] ?: DEFAULT_VISION_API_URL
    }

    suspend fun getVisionApiUrl(): String = visionApiUrlFlow.first()

    suspend fun setVisionApiUrl(url: String) {
        dataStore.edit { prefs -> prefs[VISION_API_URL_KEY] = url }
    }

    val visionApiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[VISION_API_KEY_KEY] ?: DEFAULT_VISION_API_KEY
    }

    suspend fun getVisionApiKey(): String = visionApiKeyFlow.first()

    suspend fun setVisionApiKey(key: String) {
        dataStore.edit { prefs -> prefs[VISION_API_KEY_KEY] = key }
    }

    val diaryEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DIARY_ENABLED_KEY] ?: DEFAULT_DIARY_ENABLED
    }

    suspend fun getDiaryEnabled(): Boolean = diaryEnabledFlow.first()

    suspend fun setDiaryEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[DIARY_ENABLED_KEY] = enabled }
    }

    val diaryModelFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[DIARY_MODEL_KEY] ?: DEFAULT_DIARY_MODEL
    }

    suspend fun getDiaryModel(): String = diaryModelFlow.first()

    suspend fun setDiaryModel(model: String) {
        dataStore.edit { prefs -> prefs[DIARY_MODEL_KEY] = model }
    }

    val diaryBaseUrlFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[DIARY_BASE_URL_KEY] ?: DEFAULT_DIARY_BASE_URL
    }

    suspend fun getDiaryBaseUrl(): String = diaryBaseUrlFlow.first()

    suspend fun setDiaryBaseUrl(url: String) {
        dataStore.edit { prefs -> prefs[DIARY_BASE_URL_KEY] = url }
    }

    val diaryApiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[DIARY_API_KEY_KEY] ?: DEFAULT_DIARY_API_KEY
    }

    suspend fun getDiaryApiKey(): String = diaryApiKeyFlow.first()

    suspend fun setDiaryApiKey(key: String) {
        dataStore.edit { prefs -> prefs[DIARY_API_KEY_KEY] = key }
    }

    val innerThoughtEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[INNER_THOUGHT_ENABLED_KEY] ?: DEFAULT_INNER_THOUGHT_ENABLED
    }

    suspend fun getInnerThoughtEnabled(): Boolean = innerThoughtEnabledFlow.first()

    suspend fun setInnerThoughtEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[INNER_THOUGHT_ENABLED_KEY] = enabled }
    }

    val yandereModeEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[YANDERE_MODE_ENABLED_KEY] ?: DEFAULT_YANDERE_MODE_ENABLED
    }

    suspend fun getYandereModeEnabled(): Boolean = yandereModeEnabledFlow.first()

    suspend fun setYandereModeEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[YANDERE_MODE_ENABLED_KEY] = enabled }
    }

    val yandereModeUsageStatsFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[YANDERE_MODE_USAGE_STATS_KEY] ?: DEFAULT_YANDERE_MODE_USAGE_STATS
    }

    suspend fun getYandereModeUsageStats(): Boolean = yandereModeUsageStatsFlow.first()

    suspend fun setYandereModeUsageStats(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[YANDERE_MODE_USAGE_STATS_KEY] = enabled }
    }

    val yandereModeInstalledAppsFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[YANDERE_MODE_INSTALLED_APPS_KEY] ?: DEFAULT_YANDERE_MODE_INSTALLED_APPS
    }

    suspend fun getYandereModeInstalledApps(): Boolean = yandereModeInstalledAppsFlow.first()

    suspend fun setYandereModeInstalledApps(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[YANDERE_MODE_INSTALLED_APPS_KEY] = enabled }
    }

    val showTypingSpinnerFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHOW_TYPING_SPINNER_KEY] ?: DEFAULT_SHOW_TYPING_SPINNER
    }

    suspend fun getShowTypingSpinner(): Boolean = showTypingSpinnerFlow.first()

    suspend fun setShowTypingSpinner(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[SHOW_TYPING_SPINNER_KEY] = enabled }
    }

    val searchApiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[SEARCH_API_KEY_KEY] ?: DEFAULT_SEARCH_API_KEY
    }

    suspend fun getSearchApiKey(): String = searchApiKeyFlow.first()

    suspend fun setSearchApiKey(key: String) {
        dataStore.edit { prefs -> prefs[SEARCH_API_KEY_KEY] = key }
    }

    // ==================== AI 生图配置 ====================

    val imageGenEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[IMAGE_GEN_ENABLED_KEY] ?: DEFAULT_IMAGE_GEN_ENABLED
    }

    suspend fun getImageGenEnabled(): Boolean = imageGenEnabledFlow.first()

    suspend fun setImageGenEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[IMAGE_GEN_ENABLED_KEY] = enabled }
    }

    val imageGenProviderFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[IMAGE_GEN_PROVIDER_KEY] ?: DEFAULT_IMAGE_GEN_PROVIDER
    }

    suspend fun getImageGenProvider(): String = imageGenProviderFlow.first()

    suspend fun setImageGenProvider(provider: String) {
        dataStore.edit { prefs -> prefs[IMAGE_GEN_PROVIDER_KEY] = provider }
    }

    val imageGenBaseUrlFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[IMAGE_GEN_BASE_URL_KEY] ?: DEFAULT_IMAGE_GEN_BASE_URL
    }

    suspend fun getImageGenBaseUrl(): String = imageGenBaseUrlFlow.first()

    suspend fun setImageGenBaseUrl(url: String) {
        dataStore.edit { prefs -> prefs[IMAGE_GEN_BASE_URL_KEY] = url }
    }

    val imageGenApiKeyFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[IMAGE_GEN_API_KEY_KEY] ?: DEFAULT_IMAGE_GEN_API_KEY
    }

    suspend fun getImageGenApiKey(): String = imageGenApiKeyFlow.first()

    suspend fun setImageGenApiKey(key: String) {
        dataStore.edit { prefs -> prefs[IMAGE_GEN_API_KEY_KEY] = key }
    }

    val imageGenModelFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[IMAGE_GEN_MODEL_KEY] ?: DEFAULT_IMAGE_GEN_MODEL
    }

    suspend fun getImageGenModel(): String = imageGenModelFlow.first()

    suspend fun setImageGenModel(model: String) {
        dataStore.edit { prefs -> prefs[IMAGE_GEN_MODEL_KEY] = model }
    }

    /** 拉取到的模型列表（全量，便于用户手动挑选），以换行分隔持久化。 */
    val imageGenModelListFlow: Flow<List<String>> = dataStore.data.map { prefs ->
        ImageGenDefaults.parseKeywords(prefs[IMAGE_GEN_MODEL_LIST_KEY] ?: DEFAULT_IMAGE_GEN_MODEL_LIST)
    }

    suspend fun getImageGenModelList(): List<String> = imageGenModelListFlow.first()

    suspend fun setImageGenModelList(models: List<String>) {
        dataStore.edit { prefs ->
            prefs[IMAGE_GEN_MODEL_LIST_KEY] = ImageGenDefaults.joinKeywords(models)
        }
    }

    val imageGenSizeFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[IMAGE_GEN_SIZE_KEY] ?: DEFAULT_IMAGE_GEN_SIZE
    }

    suspend fun getImageGenSize(): String = imageGenSizeFlow.first()

    suspend fun setImageGenSize(size: String) {
        dataStore.edit { prefs -> prefs[IMAGE_GEN_SIZE_KEY] = size.ifBlank { DEFAULT_IMAGE_GEN_SIZE } }
    }

    val imageGenCountFlow: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[IMAGE_GEN_COUNT_KEY] ?: DEFAULT_IMAGE_GEN_COUNT).coerceIn(1, 4)
    }

    suspend fun getImageGenCount(): Int = imageGenCountFlow.first()

    suspend fun setImageGenCount(count: Int) {
        dataStore.edit { prefs -> prefs[IMAGE_GEN_COUNT_KEY] = count.coerceIn(1, 4) }
    }

    /** 自动触发概率，0–100。 */
    val imageGenTriggerProbabilityFlow: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[IMAGE_GEN_TRIGGER_PROBABILITY_KEY] ?: DEFAULT_IMAGE_GEN_TRIGGER_PROBABILITY)
            .coerceIn(0, 100)
    }

    suspend fun getImageGenTriggerProbability(): Int = imageGenTriggerProbabilityFlow.first()

    suspend fun setImageGenTriggerProbability(probability: Int) {
        dataStore.edit { prefs ->
            prefs[IMAGE_GEN_TRIGGER_PROBABILITY_KEY] = probability.coerceIn(0, 100)
        }
    }

    val imageGenKeywordsFlow: Flow<List<String>> = dataStore.data.map { prefs ->
        ImageGenDefaults.parseKeywords(prefs[IMAGE_GEN_KEYWORDS_KEY] ?: DEFAULT_IMAGE_GEN_KEYWORDS)
    }

    suspend fun getImageGenKeywords(): List<String> = imageGenKeywordsFlow.first()

    suspend fun setImageGenKeywords(keywords: List<String>) {
        dataStore.edit { prefs ->
            prefs[IMAGE_GEN_KEYWORDS_KEY] = ImageGenDefaults.joinKeywords(keywords)
        }
    }

    val imageGenCooldownMinutesFlow: Flow<Int> = dataStore.data.map { prefs ->
        (prefs[IMAGE_GEN_COOLDOWN_MINUTES_KEY] ?: DEFAULT_IMAGE_GEN_COOLDOWN_MINUTES)
            .coerceIn(0, 120)
    }

    suspend fun getImageGenCooldownMinutes(): Int = imageGenCooldownMinutesFlow.first()

    suspend fun setImageGenCooldownMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[IMAGE_GEN_COOLDOWN_MINUTES_KEY] = minutes.coerceIn(0, 120)
        }
    }

    val imageGenPromptTemplateFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[IMAGE_GEN_PROMPT_TEMPLATE_KEY] ?: DEFAULT_IMAGE_GEN_PROMPT_TEMPLATE
    }

    suspend fun getImageGenPromptTemplate(): String = imageGenPromptTemplateFlow.first()

    suspend fun setImageGenPromptTemplate(template: String) {
        dataStore.edit { prefs ->
            prefs[IMAGE_GEN_PROMPT_TEMPLATE_KEY] = template.ifBlank { DEFAULT_IMAGE_GEN_PROMPT_TEMPLATE }
        }
    }

    val imageGenToastEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[IMAGE_GEN_TOAST_ENABLED_KEY] ?: DEFAULT_IMAGE_GEN_TOAST_ENABLED
    }

    suspend fun getImageGenToastEnabled(): Boolean = imageGenToastEnabledFlow.first()

    suspend fun setImageGenToastEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[IMAGE_GEN_TOAST_ENABLED_KEY] = enabled }
    }

    /** 上次生图时间（按会话），用于跨重启生效的冷却闸门。 */
    suspend fun getImageGenLastAt(companionId: Long): Long =
        dataStore.data.map { prefs -> prefs[imageGenLastAtKey(companionId)] ?: 0L }.first()

    suspend fun setImageGenLastAt(companionId: Long, timestamp: Long) {
        dataStore.edit { prefs -> prefs[imageGenLastAtKey(companionId)] = timestamp }
    }

    suspend fun getString(key: String): String {
        val prefKey = stringPreferencesKey(key)
        return dataStore.data.map { prefs -> prefs[prefKey] ?: "" }.first()
    }

    suspend fun setString(key: String, value: String) {
        val prefKey = stringPreferencesKey(key)
        dataStore.edit { prefs -> prefs[prefKey] = value }
    }

    suspend fun getString(key: String, defaultValue: String): String {
        val prefKey = stringPreferencesKey(key)
        return dataStore.data.map { prefs -> prefs[prefKey] ?: defaultValue }.first()
    }
}
