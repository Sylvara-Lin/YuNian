package com.yunian.ai.feature.chat.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yunian.ai.common.ChatDetailSettingsDataStoreProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CompanionChatDetailSettings(
    val backgroundKey: String? = null,
    val useGlobalBackground: Boolean = true,
    val proactiveEnabled: Boolean = true,
    val proactiveIntervalMinutes: Int = 180,
    val proactiveMinIntervalMinutes: Int = 60,
    val proactiveMaxIntervalMinutes: Int = 720,
    val proactiveDailyLimit: Int = 6,
    val allowNewTopic: Boolean = true,
    val allowLateNightMessage: Boolean = false,
    val allowFollowUpMessage: Boolean = true,
    val doNotDisturbEnabled: Boolean = false,
    val dndStartMinutes: Int = 23 * 60,
    val dndEndMinutes: Int = 8 * 60,
    val allowPriorityMessageInDnd: Boolean = false,
    val blocked: Boolean = false,
    val wechatSyncEnabled: Boolean = true,
    val stickerProbability: Int = 30,
    val ttsEnabled: Boolean = false,
    val ttsProbability: Int = 50,
    val ntpTimeEnabled: Boolean = false,
    val followUpReminderEnabled: Boolean = true,
    val followUpReminderIntervalMinutes: Int = 5,
    val followUpReminderMaxTimes: Int = 3,
    // AI 生图单聊覆盖：默认不覆盖，沿用「API 设置 → AI 生图」的全局配置。
    // 本 Store 使用 JSON + ignoreUnknownKeys，新增字段对旧数据天然向后兼容，无需数据库迁移。
    val imageGenOverrideEnabled: Boolean = false,
    val imageGenTriggerProbability: Int = 0,
    val imageGenKeywords: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

class ChatDetailSettingsStore(context: Context) {

    private val dataStore = ChatDetailSettingsDataStoreProvider.get(context)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val SETTINGS_MAP_KEY = stringPreferencesKey("companion_chat_detail_settings_map")

        /**
         * 进程级同步内存快照（T03/B5）：`companionId -> 最近一次从 DataStore 解析出的（已 sanitize 的）设置`。
         *
         * 目的：让 ChatScreen **首帧**即可同步解析出正确的背景 key，消除
         * 「首帧先用默认设置（全局背景）→ DataStore 到达后才翻转成角色独立背景」的窗口内翻转（R4）。
         *
         * 写入时机：任意 [ChatDetailSettingsStore] 实例的 [settingsMapFlow] 每次发射。
         * 读取时机：[getCachedSettings]（由 ChatScreen 组合期同步调用、以及 `MainScreen` 进入前预热读取）。
         * 由于是进程级（跨实例）共享，`MainScreen` 预填充的快照可被 ChatScreen 的新实例读到。
         */
        private val snapshot = ConcurrentHashMap<Long, CompanionChatDetailSettings>()

        /** 同步读取快照；从未观察过该角色时返回 `null`（调用方回退默认设置）。 */
        fun getCachedSettings(companionId: Long): CompanionChatDetailSettings? = snapshot[companionId]

        /**
         * 用最新一次 DataStore 解析结果**收敛**快照：先移除已不存在的 key，再写入当前值。
         *
         * D1 修复：原实现只 upsert、从不移除 —— `resetSettings()` 删除某角色后再次进入该聊天时，
         * [getCachedSettings] 会返回**陈旧快照**，导致首帧用旧背景、DataStore 到达后再翻转（R4 复现）。
         * `snapshot` 为 [ConcurrentHashMap]，`keys.retainAll` / `putAll` 均线程安全。
         */
        private fun updateSnapshot(map: Map<Long, CompanionChatDetailSettings>) {
            snapshot.keys.retainAll(map.keys)
            snapshot.putAll(map)
        }
    }

    /**
     * 设置集合流：每次 DataStore 发射时**同步刷新进程级快照**（T03/B5），
     * 并统一完成 sanitize，使下游 [settingsFlow] 无需重复处理。
     */
    val settingsMapFlow: Flow<Map<Long, CompanionChatDetailSettings>> = dataStore.data.map { prefs ->
        val raw = prefs[SETTINGS_MAP_KEY]?.let(::decodeSettingsMap) ?: emptyMap()
        val sanitized = raw.mapValues { (_, settings) -> sanitizeSettings(settings) }
        updateSnapshot(sanitized)
        sanitized
    }

    fun settingsFlow(companionId: Long): Flow<CompanionChatDetailSettings> =
        settingsMapFlow.map { map ->
            map[companionId] ?: CompanionChatDetailSettings()
        }

    suspend fun getSettings(companionId: Long): CompanionChatDetailSettings =
        settingsFlow(companionId).first()

    suspend fun updateSettings(companionId: Long, transform: (CompanionChatDetailSettings) -> CompanionChatDetailSettings) {
        dataStore.edit { prefs ->
            val currentMap = prefs[SETTINGS_MAP_KEY]?.let(::decodeSettingsMap)?.toMutableMap() ?: mutableMapOf()
            val currentSettings = sanitizeSettings(currentMap[companionId] ?: CompanionChatDetailSettings())
            currentMap[companionId] = sanitizeSettings(
                transform(currentSettings).copy(updatedAt = System.currentTimeMillis())
            )
            prefs[SETTINGS_MAP_KEY] = json.encodeToString(currentMap)
        }
    }

    suspend fun replaceSettings(companionId: Long, settings: CompanionChatDetailSettings) {
        updateSettings(companionId) { settings }
    }

    suspend fun resetSettings(companionId: Long) {
        dataStore.edit { prefs ->
            val currentMap = prefs[SETTINGS_MAP_KEY]?.let(::decodeSettingsMap)?.toMutableMap() ?: mutableMapOf()
            currentMap.remove(companionId)
            prefs[SETTINGS_MAP_KEY] = json.encodeToString(currentMap)
        }
    }

    private fun decodeSettingsMap(raw: String): Map<Long, CompanionChatDetailSettings> =
        runCatching { json.decodeFromString<Map<Long, CompanionChatDetailSettings>>(raw) }.getOrElse { emptyMap() }

    private fun sanitizeSettings(settings: CompanionChatDetailSettings): CompanionChatDetailSettings {
        val key = settings.backgroundKey?.trim().orEmpty()
        return if (!settings.useGlobalBackground && (key.isEmpty() || key == "default")) {
            settings.copy(useGlobalBackground = true, backgroundKey = null)
        } else {
            settings
        }
    }
}
