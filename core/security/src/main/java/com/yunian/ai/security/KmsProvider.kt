package com.yunian.ai.security


object KmsProvider {

    init {
        try {
            System.loadLibrary("lianyu_security")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("KmsProvider", "liblianyu_security.so not found — KMS features disabled", e)
        }
    }

    private external fun nativeInit(): Int
    private external fun nativeEncrypt(input: ByteArray): ByteArray?
    private external fun nativeDecrypt(input: ByteArray): ByteArray?
    private external fun nativeDestroyKeychain()
    private external fun nativeGetStatus(): Int

    private external fun nativeEncryptV2(input: ByteArray, metadata: ByteArray): ByteArray?
    private external fun nativeDecryptV2(input: ByteArray, metadata: ByteArray): ByteArray?

    const val STATE_UNINIT = 0
    const val STATE_READY = 1
    const val STATE_DESTROYED = -1

    fun initialize(): Boolean {

        val rc = nativeInit()
        if (rc == 0) return true

        return rc == 1
    }

    fun encryptWithSession(plaintext: ByteArray): ByteArray? {
        return nativeEncrypt(plaintext)
    }

    fun decryptWithSession(ciphertext: ByteArray): ByteArray? {
        return nativeDecrypt(ciphertext)
    }

    fun destroyKeychain() {
        nativeDestroyKeychain()
    }

    fun getStatus(): Int {
        return nativeGetStatus()
    }

    fun encryptWithMetadata(plaintext: ByteArray, metadata: ByteArray): ByteArray? {
        return nativeEncryptV2(plaintext, metadata)
    }

    fun decryptWithMetadata(ciphertext: ByteArray, metadata: ByteArray): ByteArray? {

        val nativeResult = nativeDecryptV2(ciphertext, metadata)
        if (nativeResult != null) return nativeResult

        if (isDebugBuild) {
            return devDecryptAesCbc(ciphertext, metadata)
        }
        return null
    }

    private val isDebugBuild: Boolean by lazy {
        com.yunian.ai.security.BuildConfig.DEBUG
    }

    private external fun nativeGetDevAesKey(): ByteArray?

    private fun devDecryptAesCbc(ciphertext: ByteArray, iv: ByteArray): ByteArray? {
        val aesKey = nativeGetDevAesKey() ?: return null
        return try {
            val key = javax.crypto.spec.SecretKeySpec(aesKey, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        } finally {

            java.util.Arrays.fill(aesKey, 0.toByte())
        }
    }

    val isReady: Boolean
        get() = nativeGetStatus() == STATE_READY
}
