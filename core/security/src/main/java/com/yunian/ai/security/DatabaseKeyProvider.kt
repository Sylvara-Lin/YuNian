package com.yunian.ai.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object DatabaseKeyProvider {

    private const val PREFS_NAME = "lianyu_db_secure_prefs"
    private const val KEY_DB_PASSPHRASE = "db_passphrase_b64"
    private const val KEY_ALIAS_DB = "lianyu_db_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Volatile
    private var cachedPassphrase: String? = null

    @Volatile
    private var integrityDigest: ByteArray? = null

    enum class TeeLevel {
        UNKNOWN,
        SOFTWARE,
        TRUSTED_EE,
        STRONG_BOX
    }

    fun setIntegrityDigest(digest: ByteArray) {
        integrityDigest = digest
        cachedPassphrase = null
    }

    fun getPassphrase(context: Context): String {
        cachedPassphrase?.let { return it }

        synchronized(this) {
            cachedPassphrase?.let { return it }

            val securePrefs = getSecurePrefs(context)
            val existing = securePrefs.getString(KEY_DB_PASSPHRASE, null)

            if (existing != null) {
                cachedPassphrase = existing
                return existing
            }

            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val basePassphrase = bytes.joinToString("") { "%02x".format(it) }

            val passphrase = integrityDigest?.let { digest ->
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(digest, "HmacSHA256"))
                mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
            } ?: basePassphrase

            securePrefs.edit().putString(KEY_DB_PASSPHRASE, passphrase).apply()
            cachedPassphrase = passphrase
            return passphrase
        }
    }

    fun verifyKeyIntegrity(context: Context): Boolean {
        return try {
            getPassphrase(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getTeeLevel(context: Context): TeeLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return TeeLevel.UNKNOWN
        }

        return try {

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val testAlias = "${KEY_ALIAS_DB}_tee_test"
            if (!keyStore.containsAlias(testAlias)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    testAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }

            val entry = keyStore.getEntry(testAlias, null)
            if (entry is KeyStore.SecretKeyEntry) {

                val providerName = keyStore.provider.name

                val isStrongBox = providerName == "AndroidKeyStoreStrongBox"
                val insideHardware = providerName == "AndroidKeyStore"

                when {
                    isStrongBox -> TeeLevel.STRONG_BOX
                    insideHardware -> TeeLevel.TRUSTED_EE
                    else -> TeeLevel.SOFTWARE
                }
            } else {
                TeeLevel.UNKNOWN
            }
        } catch (e: Exception) {
            TeeLevel.SOFTWARE
        }
    }

    fun isHardwareBacked(context: Context): Boolean {
        val level = getTeeLevel(context)
        return level == TeeLevel.TRUSTED_EE || level == TeeLevel.STRONG_BOX
    }

    private fun getSecurePrefs(context: Context): SharedPreferences {

        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)

            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
