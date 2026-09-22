package com.yunian.ai.feature.wechat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yunian.ai.common.SecureLog
import com.yunian.ai.security.NativeCredentialSealer

internal class WeChatSecureStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getAccountJson(): String? = readSealed(KEY_ACCOUNT_JSON)
    fun setAccountJson(json: String) = writeSealed(KEY_ACCOUNT_JSON, json)
    fun clearAccount() = prefs.edit().remove(KEY_ACCOUNT_JSON).apply()

    fun getContextTokensJson(): String? = readSealed(KEY_CONTEXT_TOKENS_JSON)
    fun setContextTokensJson(json: String) = writeSealed(KEY_CONTEXT_TOKENS_JSON, json)
    fun clearContextTokens() = prefs.edit().remove(KEY_CONTEXT_TOKENS_JSON).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun readSealed(key: String): String? {
        val storedValue = prefs.getString(key, null) ?: return null
        return runCatching {
            val plaintext = NativeCredentialSealer.unseal(storedValue, key)
            if (!NativeCredentialSealer.isSealed(storedValue)) {
                writeSealed(key, plaintext)
            }
            plaintext
        }.getOrElse { error ->
            prefs.edit().remove(key).apply()
            SecureLog.e(TAG, "Rejected invalid credential record: $key", error)
            null
        }
    }

    private fun writeSealed(key: String, plaintext: String) {
        prefs.edit().putString(key, NativeCredentialSealer.seal(plaintext, key)).apply()
    }

    companion object {
        private const val TAG = "WeChatSecureStore"
        private const val PREFS_NAME = "wechat_secure_store"
        private const val KEY_ACCOUNT_JSON = "account_json"
        private const val KEY_CONTEXT_TOKENS_JSON = "context_tokens_json"
    }
}
