package com.yunian.ai.common

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

object SaltStore {

    private const val PREFS_NAME = "lianyu_salt_store"
    private const val KEY_PREFIX = "runtimesalt_"

    @Volatile
    private var context: Context? = null

    private val saltCache = ConcurrentHashMap<String, String>()

    @JvmStatic
    fun init(appContext: Context) {
        context = appContext.applicationContext
    }

    @JvmStatic
    fun getSalt(namespace: String): String {
        val ctx = context
            ?: throw IllegalStateException("SaltStore.init(context) must be called before getSalt()")

        return saltCache.getOrPut(namespace) {
            val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_PREFIX + namespace, null) ?: run {
                val random = SecureRandom()
                val randomBytes = ByteArray(16)
                random.nextBytes(randomBytes)
                val seed = "${ctx.packageName}:${namespace}:${randomBytes.joinToString("") { "%02x".format(it) }}"
                val digest = MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(seed.toByteArray(Charsets.UTF_8))
                val hash = hashBytes.joinToString("") { "%02x".format(it) }
                prefs.edit().putString(KEY_PREFIX + namespace, hash).apply()
                hash
            }
        }
    }

    fun shutdown() {
        saltCache.clear()
        context = null
    }
}
