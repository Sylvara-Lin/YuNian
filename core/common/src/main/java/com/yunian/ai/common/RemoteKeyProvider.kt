package com.yunian.ai.common

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yunian.ai.common.security.DeviceRequestSigner
import com.yunian.ai.domain.BuiltinCloudAccessPolicy
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object RemoteKeyProvider {

    private const val CLOUD_ACCESS_DENIED = "builtin_cloud_access_denied"

    private data class HandshakeBuildResult(
        val body: JSONObject? = null,
        val error: JSONObject? = null
    )

    data class PartnerSession(
        val clientId: String,
        val token: String,
        val sessionKey: String?
    )

    private const val META_PREFS_NAME = "suflow_session_meta"

    private const val SESSION_PREFS_NAME = "suflow_session_store_encrypted"
    private const val LEGACY_PREFS_NAME = "remote_key_provider"
    private const val KEY_CACHE_FILE = "partner_keys.dat"
    private const val KEY_LAST_FETCH = "last_fetch_ms"
    private const val KEY_RANDOM_MODEL = "random_model"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_SESSION_KEY = "session_key"
    private const val KEY_LEGACY_SECRET = "secret"

    @Volatile
    var serverUrl: String = SuFlowApi.BASE_URL

    private fun resolveServerUrl(): String = serverUrl
    private const val HANDSHAKE_PATH = "/api/auth/handshake"
    private const val CHALLENGE_PATH = "/api/auth/challenge"

    private const val APP_ROUTE_ID = "suflow-app-provision-key-2024-secure-32byte!!"

    private const val KEYS_FETCH_PATH = "/api/keys/fetch"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"

    private const val KEYSTORE_KEY_ALIAS = "lianyu_partner_key_v4_gcm"
    private const val LEGACY_KEYSTORE_KEY_ALIAS = "lianyu_partner_key_v3"

    @Volatile
    private var cachedKeys: List<String> = emptyList()

    @Volatile
    private var cachedRandomModel: String? = null

    @Volatile
    private var lastFetchMs: Long = 0

    @Volatile
    var lastCloudError: CloudError? = null
        private set

    @Volatile
    private var sessionPrefsCache: SharedPreferences? = null

    private val random = SecureRandom()

    fun isBuiltinCloudAccessAllowed(): Boolean {
        val policy = ServiceRegistry.get(BuiltinCloudAccessPolicy::class.java)
        if (policy == null) {
            SecureLog.w("RemoteKeyProvider", "Built-in cloud denied: security policy unavailable")
            return false
        }
        if (!policy.isBuiltinCloudAccessAllowed()) {
            SecureLog.w(
                "RemoteKeyProvider",
                "Built-in cloud denied: ${policy.denialReason() ?: "security policy rejected request"}"
            )
            return false
        }
        return true
    }

    private fun sessionPrefs(context: Context): SharedPreferences {
        sessionPrefsCache?.let { return it }
        val appCtx = context.applicationContext
        val prefs = try {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appCtx,
                SESSION_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            SecureLog.security("RemoteKeyProvider: EncryptedSharedPreferences failed, using private prefs fallback")
            appCtx.getSharedPreferences(SESSION_PREFS_NAME, Context.MODE_PRIVATE)
        }
        migrateLegacySessionPrefs(appCtx, prefs)
        sessionPrefsCache = prefs
        return prefs
    }

    private fun metaPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(META_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun migrateLegacySessionPrefs(context: Context, target: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val hasLegacySecrets = !legacy.getString(KEY_AUTH_TOKEN, null).isNullOrBlank() ||
            !legacy.getString(KEY_LEGACY_SECRET, null).isNullOrBlank() ||
            !legacy.getString(KEY_CLIENT_ID, null).isNullOrBlank() ||
            !legacy.getString("client_id", null).isNullOrBlank()
        if (!hasLegacySecrets && target.contains(KEY_CLIENT_ID)) return

        val editor = target.edit()
        val clientId = legacy.getString(KEY_CLIENT_ID, null)
            ?: legacy.getString("client_id", null)
        val token = legacy.getString(KEY_AUTH_TOKEN, null)
        val sessionKey = legacy.getString(KEY_SESSION_KEY, null)
        val secret = legacy.getString(KEY_LEGACY_SECRET, null)
        if (!clientId.isNullOrBlank()) editor.putString(KEY_CLIENT_ID, clientId)
        if (!token.isNullOrBlank()) editor.putString(KEY_AUTH_TOKEN, token)
        if (!sessionKey.isNullOrBlank()) editor.putString(KEY_SESSION_KEY, sessionKey)
        if (!secret.isNullOrBlank()) editor.putString(KEY_LEGACY_SECRET, secret)
        editor.apply()

        val metaEditor = metaPrefs(context).edit()
        if (legacy.contains(KEY_LAST_FETCH)) {
            metaEditor.putLong(KEY_LAST_FETCH, legacy.getLong(KEY_LAST_FETCH, 0L))
        }
        legacy.getString(KEY_RANDOM_MODEL, null)?.let { metaEditor.putString(KEY_RANDOM_MODEL, it) }
        metaEditor.apply()

        legacy.edit().clear().apply()
    }

    fun cloveHandshake(ctx: Context): JSONObject {
        if (!isBuiltinCloudAccessAllowed()) {
            lastCloudError = CloudError(
                code = CloudError.BUILTIN_ACCESS_DENIED,
                message = "内置云端访问被安全策略拒绝"
            )
            return JSONObject().apply {
                put("ok", false)
                put("error", CLOUD_ACCESS_DENIED)
            }
        }
        val url = URL("${resolveServerUrl()}$HANDSHAKE_PATH")
        val handshake = buildHandshakeBody()
        handshake.error?.let {
            lastCloudError = CloudError.parse(it.toString()) ?: CloudError(
                code = it.optString("error", CloudError.NETWORK_ERROR),
                message = it.optString("message").ifEmpty { null }
            )
            return it
        }
        val body = handshake.body ?: return JSONObject().apply {
            lastCloudError = CloudError(code = "device_sign_unavailable", message = "设备签名不可用")
            put("ok", false)
            put("error", "device_sign_unavailable")
        }
        val result = httpPost(url, body.toString())
        result?.let { storeHandshakeResult(ctx, it) }
        return result ?: JSONObject().apply {
            if (lastCloudError == null) {
                lastCloudError = CloudError(code = CloudError.NETWORK_ERROR, message = "网络连接失败")
            }
            put("ok", false)
            put("error", lastCloudError?.code ?: CloudError.NETWORK_ERROR)
            lastCloudError?.message?.let { put("message", it) }
        }
    }

    fun storeHandshakeResult(ctx: Context, clientId: String, secret: String) {
        val appCtx = ctx.applicationContext
        sessionPrefs(appCtx).edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_LEGACY_SECRET, secret)
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_SESSION_KEY)
            .apply()
        updateFetchTime(appCtx)

        val newKeys = listOf("$clientId:$secret")
        cachedKeys = newKeys
        saveLocalKeys(appCtx, newKeys)
    }

    fun storeHandshakeResult(ctx: Context, handshake: JSONObject): Boolean {
        val clientId = handshake.optString("client_id").ifEmpty { null } ?: return false
        val sessionToken = handshake.optString("session_token").ifEmpty {
            handshake.optString("token").ifEmpty { null }
        }
        val sessionKey = handshake.optString("session_key").ifEmpty {
            handshake.optString("sessionKey").ifEmpty { null }
        }
        if (sessionToken != null) {
            storeSessionResult(ctx, clientId, sessionToken, sessionKey)
            return true
        }

        val secret = handshake.optString("secret").ifEmpty { null } ?: return false
        storeHandshakeResult(ctx, clientId, secret)
        return true
    }

    fun storeSessionResult(ctx: Context, clientId: String, sessionToken: String, sessionKey: String?) {
        val appCtx = ctx.applicationContext
        sessionPrefs(appCtx).edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_AUTH_TOKEN, sessionToken)
            .putString(KEY_SESSION_KEY, sessionKey)
            .remove(KEY_LEGACY_SECRET)
            .apply()
        updateFetchTime(appCtx)

        val sessionKeys = listOf(sessionToken)
        cachedKeys = sessionKeys
        saveLocalKeys(appCtx, sessionKeys)
    }

    private fun getOrCreateKeystoreKey(alias: String = KEYSTORE_KEY_ALIAS): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (keyStore.containsAlias(alias)) {
            return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptData(data: String): String {
        val secretKey = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        val combined = byteArrayOf(1) + iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptData(context: Context, encryptedBase64: String): String? {
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            val isGcm = combined.isNotEmpty() && combined[0].toInt() == 1
            val secretKey = if (isGcm) getOrCreateKeystoreKey() else getOrCreateKeystoreKey(LEGACY_KEYSTORE_KEY_ALIAS)
            val ivLength = if (isGcm) 12 else 16
            val offset = if (isGcm) 1 else 0
            val iv = combined.copyOfRange(offset, offset + ivLength)
            val encrypted = combined.copyOfRange(offset + ivLength, combined.size)
            val cipher = Cipher.getInstance(if (isGcm) AES_GCM_ALGORITHM else "AES/CBC/PKCS7Padding")
            if (isGcm) {
                cipher.init(Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
            } else {
                cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            }
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "Decrypt failed: ${e.message}")
            null
        }
    }

    fun getPartnerKeys(context: Context): List<String> {
        val appContext = context.applicationContext

        getPartnerSession(appContext)?.takeIf { isCacheValid(appContext) }?.let { session ->
            cachedKeys = listOf(session.token)
            return cachedKeys
        }

        if (cachedKeys.isNotEmpty() && isCacheValid(appContext)) {
            return cachedKeys
        }

        val localKeys = loadLocalKeys(appContext)
        if (localKeys.isNotEmpty() && !isCacheExpired(appContext)) {
            cachedKeys = localKeys
            return localKeys
        }

        return emptyList()
    }

    suspend fun fetchKeysAsync(context: Context, forceRefresh: Boolean = false): List<String> {
        val appContext = context.applicationContext

        return withContext(Dispatchers.IO) {
            if (!isBuiltinCloudAccessAllowed()) return@withContext emptyList()
            if (!forceRefresh) {
                getPartnerSession(appContext)?.takeIf { isCacheValid(appContext) }?.let { session ->
                    cachedKeys = listOf(session.token)
                    SecureLog.d("RemoteKeyProvider", "Using cached YuNian session")
                    return@withContext cachedKeys
                }
            }

            SecureLog.d("RemoteKeyProvider", "=== FETCH KEYS START ===")
            SecureLog.d("RemoteKeyProvider", "forceRefresh=$forceRefresh, cachedKeys=${cachedKeys.size}")

            try {
                val encryptedKeys = fetchEncryptedKeys(appContext)
                if (!encryptedKeys.isNullOrEmpty()) {
                    saveLocalKeys(appContext, encryptedKeys)
                    cachedKeys = encryptedKeys
                    SecureLog.api("RemoteKeyProvider", "Fetched YuNian session via handshake")
                    updateFetchTime(appContext)
                    return@withContext encryptedKeys
                }
            } catch (e: Exception) {
                SecureLog.w("RemoteKeyProvider", "Encrypted API failed: ${e.message}")
            }

            val localKeys = loadLocalKeys(appContext)
            if (localKeys.isNotEmpty()) {
                cachedKeys = localKeys
                SecureLog.w("RemoteKeyProvider", "Encrypted fetch failed, using cached keys (${localKeys.size})")
                return@withContext localKeys
            }

            SecureLog.w("RemoteKeyProvider", "No keys available - encrypted failed and no cache")
            emptyList()
        }
    }

    suspend fun ensureSession(context: Context, forceRefresh: Boolean = false): PartnerSession? {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            if (!isBuiltinCloudAccessAllowed()) return@withContext null
            if (!forceRefresh) {
                getPartnerSession(appContext)?.takeIf { isCacheValid(appContext) }?.let { return@withContext it }
            }
            fetchEncryptedKeys(appContext)
            getPartnerSession(appContext)
        }
    }

    fun getPartnerSession(context: Context): PartnerSession? {
        val prefs = sessionPrefs(context)
        val clientId = prefs.getString(KEY_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val token = prefs.getString(KEY_AUTH_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val sessionKey = prefs.getString(KEY_SESSION_KEY, null)?.takeIf { it.isNotBlank() }
        return PartnerSession(clientId = clientId, token = token, sessionKey = sessionKey)
    }

    private fun fetchEncryptedKeys(ctx: Context): List<String>? {
        val handshakeBuild = buildHandshakeBody()
        handshakeBuild.error?.let {
            lastCloudError = CloudError.parse(it.toString()) ?: CloudError(
                code = it.optString("error", CloudError.NETWORK_ERROR),
                message = it.optString("message").ifEmpty { null }
            )
            SecureLog.w("RemoteKeyProvider", "Handshake preflight failed: ${it.optString("error")}")
            return null
        }
        val handshakeJson = handshakeBuild.body ?: return null
        val handshakeUrl = URL("${resolveServerUrl()}$HANDSHAKE_PATH")

        SecureLog.d("RemoteKeyProvider", "Handshake POST $HANDSHAKE_PATH (session mode)")
        val handshakeResp = httpPost(handshakeUrl, handshakeJson.toString())
        if (handshakeResp == null) {
            SecureLog.w("RemoteKeyProvider", "Handshake returned null")
            return null
        }

        val ok = handshakeResp.optBoolean("ok", false)
        if (!ok) {
            val error = handshakeResp.optString("error", "unknown")
            val message = handshakeResp.optString("message").ifEmpty { null }
            lastCloudError = CloudError(code = error, message = message)
            SecureLog.w("RemoteKeyProvider", "Handshake failed: $error${message?.let { " ($it)" } ?: ""}")
            return null
        }

        val clientId = handshakeResp.optString("client_id").ifEmpty { null }
        val sessionToken = handshakeResp.optString("session_token").ifEmpty {
            handshakeResp.optString("token").ifEmpty { null }
        }
        val sessionKey = handshakeResp.optString("session_key").ifEmpty {
            handshakeResp.optString("sessionKey").ifEmpty { null }
        }
        if (clientId == null || sessionToken == null) {
            SecureLog.w("RemoteKeyProvider", "Handshake missing client_id or session_token")
            return null
        }

        storeSessionResult(ctx, clientId, sessionToken, sessionKey)

        if (handshakeResp.has("randomModel") && !handshakeResp.isNull("randomModel")) {
            cachedRandomModel = handshakeResp.getString("randomModel")
        } else if (cachedRandomModel == null) {

            cachedRandomModel = "claude-sonnet-4-20250514"
        }

        try {
            metaPrefs(ctx)
                .edit()
                .putString(KEY_RANDOM_MODEL, cachedRandomModel)
                .apply()
        } catch (_: Exception) {}

        return listOf(sessionToken)
    }

    private fun buildHandshakeBody(): HandshakeBuildResult {
        val deviceId = android.os.Build.FINGERPRINT.take(40) + "_" + android.os.Build.MODEL.replace(" ", "_")
        val encodedDeviceId = URLEncoder.encode(deviceId, Charsets.UTF_8.name())
        val challengeUrl = URL("${resolveServerUrl()}$CHALLENGE_PATH?device_id=$encodedDeviceId")
        val challengeResp = httpGet(challengeUrl) ?: return HandshakeBuildResult()
        if (!challengeResp.optBoolean("ok", false)) return HandshakeBuildResult(error = challengeResp)

        val challenge = challengeResp.optString("challenge").takeIf { it.isNotBlank() } ?: return HandshakeBuildResult()
        val payload = "v1\nhandshake\n$challenge\n$deviceId"
        val challengeSig = DeviceRequestSigner.sign(payload.toByteArray(Charsets.UTF_8))?.signature ?: return HandshakeBuildResult()

        return HandshakeBuildResult(body = JSONObject().apply {
            put("device_id", deviceId)
            put("user_id", deviceId.take(20))
            put("device_public_key", DeviceRequestSigner.publicKeyBase64())
            put("device_key_id", DeviceRequestSigner.keyId())
            put("sig_alg", DeviceRequestSigner.SIGNATURE_ALGORITHM)
            put("challenge", challenge)
            put("challenge_sig", challengeSig)
        })
    }

    private fun decryptAesGcm(data: String, sessionKeyHex: String, ivHex: String, tagHex: String): String? {
        return try {

            val rawKey = sessionKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val salt = "lianyu_suflow_v3".toByteArray()
            val spec = javax.crypto.spec.PBEKeySpec(
                String(rawKey).toCharArray(), salt, 100000, 256
            )
            val keyBytes = factory.generateSecret(spec).encoded.copyOf(32)

            val ivBytes = ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val tagBytes = tagHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipherText = android.util.Base64.decode(data, android.util.Base64.DEFAULT)

            val cipher = javax.crypto.Cipher.getInstance(AES_GCM_ALGORITHM)
            val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decrypted = cipher.doFinal(cipherText + tagBytes)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "AES-GCM decrypt error: ${e.message}")
            null
        }
    }

    private fun getClientId(ctx: Context): String {
        val prefs = sessionPrefs(ctx)
        var clientId = prefs.getString(KEY_CLIENT_ID, null)
        if (clientId == null) {
            val androidId = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
            clientId = androidId + "_" + android.os.Build.MODEL.replace(" ", "_")
            prefs.edit().putString(KEY_CLIENT_ID, clientId).apply()
        }
        return clientId
    }

    private fun httpPost(url: URL, body: String): JSONObject? {
        var connection: HttpURLConnection? = null
        try {
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")

                setRequestProperty("x-app-key", APP_ROUTE_ID)
                doOutput = true
                doInput = true
            }

            connection.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {

                val cloudError = CloudError.parseErrorStream(connection, responseCode)
                if (cloudError != null) {
                    lastCloudError = cloudError
                    SecureLog.w(
                        "RemoteKeyProvider",
                        "HTTP POST $responseCode error=${cloudError.code} message=${cloudError.message}"
                    )
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", cloudError.code)
                        cloudError.message?.let { put("message", it) }
                    }
                }

                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", "app_key_mismatch")
                    }
                }
                return null
            }

            val responseBody = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            return JSONObject(responseBody)
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "HTTP POST failed: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun httpGet(url: URL): JSONObject? {
        var connection: HttpURLConnection? = null
        try {
            connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")

                setRequestProperty("x-app-key", APP_ROUTE_ID)
                doInput = true
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {

                val cloudError = CloudError.parseErrorStream(connection, responseCode)
                if (cloudError != null) {
                    lastCloudError = cloudError
                    SecureLog.w(
                        "RemoteKeyProvider",
                        "HTTP GET $responseCode error=${cloudError.code} message=${cloudError.message}"
                    )
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", cloudError.code)
                        cloudError.message?.let { put("message", it) }
                    }
                }

                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    return JSONObject().apply {
                        put("ok", false)
                        put("error", "app_key_mismatch")
                    }
                }
                return null
            }

            val responseBody = connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            return JSONObject(responseBody)
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "HTTP GET failed: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun fetchFromServer(urlString: String, ctx: Context): List<String>? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                SecureLog.d("RemoteKeyProvider", "Opening connection to: $urlString")
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Client-Version", getAppVersion(ctx))
                    doInput = true
                    doOutput = false
                }

                SecureLog.d("RemoteKeyProvider", "Connecting to server...")
                val responseCode = connection.responseCode
                SecureLog.d("RemoteKeyProvider", "Server response code: $responseCode")
                if (responseCode != HttpURLConnection.HTTP_OK) return@withContext null

                val body = connection.inputStream?.bufferedReader()?.use { it.readText() }
                    ?: return@withContext null

                val json = JSONObject(body)
                if (!json.has("keys")) return@withContext null

                val keysArray = json.getJSONArray("keys")
                val keys = mutableListOf<String>()
                for (i in 0 until keysArray.length()) {
                    keys.add(keysArray.getString(i))
                }

                if (json.has("randomModel") && !json.isNull("randomModel")) {
                    val randomModel = json.getString("randomModel")
                    cachedRandomModel = randomModel
                    SecureLog.d("RemoteKeyProvider", "Server recommended random model: $randomModel")

                    try {
                        metaPrefs(ctx)
                            .edit()
                            .putString(KEY_RANDOM_MODEL, randomModel)
                            .apply()
                    } catch (_: Exception) {}
                }

                if (cachedRandomModel == null && json.has("models")) {
                    val modelsArray = json.getJSONArray("models")
                    if (modelsArray.length() > 0) {
                        val randomIndex = random.nextInt(modelsArray.length())
                        cachedRandomModel = modelsArray.getString(randomIndex)
                        SecureLog.d("RemoteKeyProvider", "Locally selected random model: $cachedRandomModel")
                    }
                }

                SecureLog.api("RemoteKeyProvider",
                    "Fetched ${keys.size}/${json.optInt("totalAvailable", keys.size)} random keys, " +
                    "model: ${cachedRandomModel ?: "default"}"
                )

                keys
            } catch (e: Exception) {
                SecureLog.w("RemoteKeyProvider", "Fetch from $urlString failed: ${e.message}")
                null
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun saveLocalKeys(context: Context, keys: List<String>) {
        try {
            val json = JSONObject()
            json.put("keys", JSONArray(keys))
            json.put("version", 2)
            val encrypted = encryptData(json.toString())
            val file = File(context.filesDir, KEY_CACHE_FILE)
            file.writeText(encrypted)
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "Save local keys failed: ${e.message}")
        }
    }

    private fun loadLocalKeys(context: Context): List<String> {
        return try {
            val file = File(context.filesDir, KEY_CACHE_FILE)
            if (!file.exists()) return emptyList()
            val encrypted = file.readText()
            val decrypted = decryptData(context, encrypted) ?: return emptyList()
            val json = JSONObject(decrypted)
            if (!json.has("keys")) return emptyList()

            if (cachedRandomModel == null) {
                cachedRandomModel = metaPrefs(context).getString(KEY_RANDOM_MODEL, null)
                if (cachedRandomModel != null) {
                    SecureLog.d("RemoteKeyProvider", "Restored random model from cache: $cachedRandomModel")
                }
            }

            val keysArray = json.getJSONArray("keys")
            val keys = mutableListOf<String>()
            for (i in 0 until keysArray.length()) {
                keys.add(keysArray.getString(i))
            }
            keys
        } catch (e: Exception) {
            SecureLog.w("RemoteKeyProvider", "Load local keys failed: ${e.message}")
            emptyList()
        }
    }

    fun getRandomModel(context: Context? = null): String? {
        if (cachedRandomModel != null) return cachedRandomModel

        if (context != null) {
            cachedRandomModel = metaPrefs(context).getString(KEY_RANDOM_MODEL, null)
        }
        return cachedRandomModel
    }

    private fun isCacheValid(context: Context): Boolean {
        val lastFetch = metaPrefs(context).getLong(KEY_LAST_FETCH, 0)
        return System.currentTimeMillis() - lastFetch < CACHE_TTL_MS
    }

    private fun isCacheExpired(context: Context): Boolean {
        val lastFetch = metaPrefs(context).getLong(KEY_LAST_FETCH, 0)
        return System.currentTimeMillis() - lastFetch >= CACHE_TTL_MS * 24
    }

    private fun updateFetchTime(context: Context) {
        metaPrefs(context).edit().putLong(KEY_LAST_FETCH, System.currentTimeMillis()).apply()
    }

    fun clearCache(context: Context) {
        cachedKeys = emptyList()
        cachedRandomModel = null
        sessionPrefsCache = null
        val appCtx = context.applicationContext
        val file = File(appCtx.filesDir, KEY_CACHE_FILE)
        file.delete()

        runCatching { sessionPrefs(appCtx).edit().clear().apply() }
        metaPrefs(appCtx).edit().clear().apply()
        appCtx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
