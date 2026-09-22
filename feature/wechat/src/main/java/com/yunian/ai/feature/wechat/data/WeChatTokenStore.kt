package com.yunian.ai.feature.wechat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yunian.ai.common.SecureLog
import com.yunian.ai.wechat.ilink.IlinkAccount
import com.yunian.ai.wechat.ilink.IlinkSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.wechatDataStore: DataStore<Preferences> by preferencesDataStore(name = "wechat_prefs")

@Serializable
@SerialName("A0")
data class A0(
    val botToken: String,
    val ilinkBotId: String,
    val ilinkUserId: String,
    val baseUrl: String = "https://ilinkai.weixin.qq.com",
    val accountId: String = "default"
)

/** iLink 会话的 context_token + 落库时间；iLink 协议 token 约 24h 过期 */
@Serializable
data class ContextTokenRecord(
    val token: String,
    val savedAtMs: Long = 0L,
)

class WeChatTokenStore(context: Context) : IlinkSessionStore {

    private val dataStore = context.applicationContext.wechatDataStore
    private val secureStore = WeChatSecureStore(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val contextTokensMutex = Mutex()
    private val accountState = MutableStateFlow(readAccount())

    companion object {
        private const val TAG = "WeChatTokenStore"
        private val CURSOR_KEY = stringPreferencesKey("wechat_cursor")
        private val AUTO_REPLY_KEY = booleanPreferencesKey("wechat_auto_reply")
        private val NOTIFY_ENABLED_KEY = booleanPreferencesKey("wechat_notify_enabled")
        private val FORWARD_ENABLED_KEY = booleanPreferencesKey("wechat_forward_enabled")
        private val DEFAULT_COMPANION_ID_KEY = longPreferencesKey("wechat_default_companion_id")
        private val USER_COMPANION_MAP_KEY = stringPreferencesKey("wechat_user_companion_map")
        private val CUSTOM_BOT_NAME_KEY = stringPreferencesKey("wechat_custom_bot_name")
    }

    val accountFlow: Flow<A0?> = accountState.asStateFlow()

    private fun readAccount(): A0? {
        val accountJson = secureStore.getAccountJson() ?: return null
        return runCatching { json.decodeFromString<A0>(accountJson) }
            .onFailure {
                secureStore.clearAccount()
                SecureLog.e(TAG, "Rejected invalid account record", it)
            }
            .getOrNull()
    }

    suspend fun getAccount(): A0? = accountState.value

    suspend fun saveAccount(account: A0) {
        secureStore.setAccountJson(json.encodeToString(account))
        accountState.value = account
    }

    suspend fun clearAccount() {
        secureStore.clearAccount()
        contextTokensMutex.withLock {
            secureStore.clearContextTokens()
        }
        accountState.value = null
        dataStore.edit { prefs ->
            prefs.remove(CURSOR_KEY)
        }
    }

    val autoReplyFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_REPLY_KEY] ?: true
    }

    suspend fun getAutoReply(): Boolean = autoReplyFlow.first()

    suspend fun setAutoReply(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_REPLY_KEY] = enabled
        }
    }

    val notifyEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[NOTIFY_ENABLED_KEY] ?: true
    }

    suspend fun getNotifyEnabled(): Boolean = notifyEnabledFlow.first()

    suspend fun setNotifyEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[NOTIFY_ENABLED_KEY] = enabled
        }
    }

    val forwardEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FORWARD_ENABLED_KEY] ?: true
    }

    suspend fun getForwardEnabled(): Boolean = forwardEnabledFlow.first()

    suspend fun setForwardEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[FORWARD_ENABLED_KEY] = enabled
        }
    }

    val defaultCompanionIdFlow: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[DEFAULT_COMPANION_ID_KEY]
    }

    suspend fun getDefaultCompanionId(): Long? = defaultCompanionIdFlow.first()

    suspend fun setDefaultCompanionId(companionId: Long?) {
        dataStore.edit { prefs ->
            if (companionId != null) {
                prefs[DEFAULT_COMPANION_ID_KEY] = companionId
            } else {
                prefs.remove(DEFAULT_COMPANION_ID_KEY)
            }
        }
    }

    val customBotNameFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[CUSTOM_BOT_NAME_KEY]
    }

    suspend fun getCustomBotName(): String? = customBotNameFlow.first()

    suspend fun setCustomBotName(name: String?) {
        dataStore.edit { prefs ->
            if (name != null) {
                prefs[CUSTOM_BOT_NAME_KEY] = name
            } else {
                prefs.remove(CUSTOM_BOT_NAME_KEY)
            }
        }
    }

    private val userCompanionMapFlow: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        prefs[USER_COMPANION_MAP_KEY]?.let {
            try {
                json.decodeFromString(it)
            } catch (_: Exception) {
                emptyMap()
            }
        } ?: emptyMap()
    }

    suspend fun getCompanionIdForWechatUser(wechatUserId: String): Long? {
        val map = userCompanionMapFlow.first()
        return map[wechatUserId]
    }

    suspend fun setCompanionIdForWechatUser(wechatUserId: String, companionId: Long) {
        val current = userCompanionMapFlow.first().toMutableMap()
        current[wechatUserId] = companionId
        dataStore.edit { prefs ->
            prefs[USER_COMPANION_MAP_KEY] = json.encodeToString(current)
        }
    }

    suspend fun removeWechatUserMapping(wechatUserId: String) {
        val current = userCompanionMapFlow.first().toMutableMap()
        current.remove(wechatUserId)
        dataStore.edit { prefs ->
            prefs[USER_COMPANION_MAP_KEY] = json.encodeToString(current)
        }
    }

    suspend fun getAllWechatUserMappings(): Map<String, Long> = userCompanionMapFlow.first()

    suspend fun getWechatUserIdsForCompanionId(companionId: Long): List<String> {
        val map = userCompanionMapFlow.first()
        return map.filter { it.value == companionId }.keys.toList()
    }

    private fun readTokenRecords(): Map<String, ContextTokenRecord> {
        val raw = secureStore.getContextTokensJson() ?: return emptyMap()
        // 新格式：Map<String, ContextTokenRecord>；旧格式：Map<String, String>（无时间戳，savedAtMs=0 视为未知）
        val newFormat = runCatching { json.decodeFromString<Map<String, ContextTokenRecord>>(raw) }
            .getOrNull()
        if (newFormat != null) return newFormat
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .getOrElse { emptyMap() }
            .mapValues { ContextTokenRecord(token = it.value, savedAtMs = 0L) }
    }

    private suspend fun saveTokenRecords(records: Map<String, ContextTokenRecord>) {
        secureStore.setContextTokensJson(json.encodeToString(records))
    }

    override suspend fun getContextToken(accountId: String, userId: String): String? {
        return readTokenRecords()["$accountId:$userId"]?.token
    }

    override suspend fun getContextTokenSavedAt(accountId: String, userId: String): Long? {
        val record = readTokenRecords()["$accountId:$userId"] ?: return null
        return record.savedAtMs.takeIf { it > 0L }
    }

    override suspend fun saveContextToken(accountId: String, userId: String, token: String) {
        contextTokensMutex.withLock {
            val current = readTokenRecords().toMutableMap()
            current["$accountId:$userId"] = ContextTokenRecord(token = token, savedAtMs = System.currentTimeMillis())
            saveTokenRecords(current)
        }
    }

    override suspend fun getContextTokens(accountId: String): Map<String, String> {
        val prefix = "$accountId:"
        return readTokenRecords()
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }
            .mapValues { it.value.token }
    }

    val cursorFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[CURSOR_KEY] ?: ""
    }

    override suspend fun getCursor(): String = cursorFlow.first()

    override suspend fun saveCursor(cursor: String) {
        dataStore.edit { prefs ->
            prefs[CURSOR_KEY] = cursor
        }
    }

    suspend fun isLoggedIn(): Boolean = getAccount() != null

    fun isLoggedInSync(): Boolean = accountState.value != null

    override suspend fun getSessionAccount(): IlinkAccount? = getAccount()?.toIlinkAccount()

    override suspend fun saveSessionAccount(account: IlinkAccount) {
        saveAccount(account.toA0())
    }

    override suspend fun clearSessionAccount() {
        clearAccount()
    }

    private fun A0.toIlinkAccount(): IlinkAccount = IlinkAccount(
        botToken = botToken,
        ilinkBotId = ilinkBotId,
        ilinkUserId = ilinkUserId,
        baseUrl = baseUrl,
        accountId = accountId,
    )

    private fun IlinkAccount.toA0(): A0 = A0(
        botToken = botToken,
        ilinkBotId = ilinkBotId,
        ilinkUserId = ilinkUserId,
        baseUrl = baseUrl,
        accountId = accountId,
    )
}
