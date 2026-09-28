package com.yunian.ai.security

import android.content.Context

object NativeBridge {
    @Volatile var tampered: Boolean = false
        private set

    init {
        try { System.loadLibrary("lianyu_security") }
        catch (e: UnsatisfiedLinkError) {
            tampered = true
            android.util.Log.e("NativeBridge", "liblianyu_security.so not found — security features disabled", e)
        }
    }

    @JvmStatic
    var sDexClassLoader: ClassLoader? = null
    @JvmStatic
    var sRealAppClass: Class<*>? = null

    @JvmStatic
    external fun nativeLoadPayload(context: Context, appClassName: String): Int

    @JvmStatic
    external fun verifySignature(context: Context): Boolean
    @JvmStatic
    external fun injectAuthHeader(builder: Any)
    @JvmStatic
    external fun getRepoOwner(): String
    @JvmStatic
    external fun getRepoName(): String
    @JvmStatic
    external fun getGitHubApiUrl(): String
    @JvmStatic
    external fun isSafe(): Boolean
    @JvmStatic
    external fun isMitmDetected(): Boolean
    @JvmStatic
    external fun verifyRequestIntegrity(url: String): Boolean

    @JvmStatic
    external fun isDeviceRooted(): Boolean
    @JvmStatic
    external fun isHookDetected(): Boolean
    @JvmStatic
    external fun enterDeadLoop()
    @JvmStatic
    external fun nativeGetVmpFingerprint(): Int
    @JvmStatic
    external fun isEmulator(): Boolean
    @JvmStatic
    external fun isDebugged(): Boolean
    @JvmStatic
    external fun getThreatScore(): Int
    @JvmStatic
    external fun resetGuard()

    @JvmStatic
    external fun checkFridaFiles(): Boolean
    @JvmStatic
    external fun checkSelinuxPermissive(): Boolean
    @JvmStatic
    external fun checkBootloader(): Boolean
    @JvmStatic
    external fun checkZygiskModules(): Boolean
    @JvmStatic
    external fun checkLibraryInjection(): Boolean
    @JvmStatic
    external fun checkVirtualEnv(): Boolean
    @JvmStatic
    external fun checkFridaThreads(): Boolean
    @JvmStatic
    external fun getSecureString(id: Int): String
    @JvmStatic
    external fun getFullThreatScore(): Int

    @JvmStatic
    external fun vmRunCheckTracer(): Int
    @JvmStatic
    external fun vmSelftest(): Int

    @JvmStatic
    external fun vmpTrustAnchorsVerify(): Int
    @JvmStatic
    external fun vmpWbAesKeycheck(): Int
    @JvmStatic
    external fun vmpKmsDeriveSk(ctxPtr: Long, ctxLen: Int): Long
    @JvmStatic
    external fun vmpTeeAttest(): Int
    @JvmStatic
    external fun vmpApkSigVerify(): Int

    @JvmStatic
    external fun vmpRootDetect(): Int
    @JvmStatic
    external fun vmpCodeIntegrity(expectedCrc: Int): Int
    @JvmStatic
    external fun vmpSm3Hash(dataPtr: Long, dataLen: Int): Long
    @JvmStatic
    external fun vmpFridaHeartbeat(): Int

    @JvmStatic
    external fun ptraceSelfAttach(): Boolean
    @JvmStatic
    external fun antiDebugInit(): Boolean

    @JvmStatic
    external fun zeroTrustInit()
    @JvmStatic
    external fun zeroTrustEvaluate(): Int
    @JvmStatic
    external fun zeroTrustGetState(): Int
    @JvmStatic
    external fun zeroTrustGetScore(): Int
    @JvmStatic
    external fun zeroTrustGetScoreBreakdown(): String
    @JvmStatic
    external fun zeroTrustGetRiskLevel(): Int
    @JvmStatic
    external fun zeroTrustIsDegraded(): Int
    @JvmStatic
    external fun zeroTrustIsLocked(): Int
    @JvmStatic
    external fun zeroTrustIsContinuousEvaluationRunning(): Int

    @JvmStatic
    external fun wbAesInit()
    @JvmStatic
    external fun wbAesEncrypt(data: ByteArray): ByteArray?
    @JvmStatic
    external fun wbAesDecrypt(data: ByteArray): ByteArray?
    @JvmStatic
    external fun wbAesSelftest(): Int

    @JvmStatic
    external fun wbAesObfuscateTables(seed: ByteArray)
    @JvmStatic
    external fun wbAesSideChannelDefense()

    @JvmStatic
    external fun checkDexIntegrity(): Boolean
    @JvmStatic
    external fun checkSoIntegrity(): Boolean
    @JvmStatic
    external fun checkResourcesIntegrity(): Boolean
    @JvmStatic
    external fun computeIntegrityDigest(): ByteArray?

    @JvmStatic
    external fun startHeartbeat()
    @JvmStatic
    external fun isHeartbeatOk(): Boolean
    @JvmStatic
    external fun isFridaDetected(): Boolean
    @JvmStatic
    external fun isTracerDetected(): Boolean

    @JvmStatic
    external fun getPinnedCert(index: Int): String?
    @JvmStatic
    external fun getPinnedCertCount(): Int

    @JvmStatic
    external fun getExpectedCertSha256(): ByteArray?

    @JvmStatic
    external fun encryptBody(plaintext: ByteArray): ByteArray?
    @JvmStatic
    external fun decryptBody(ciphertext: ByteArray): ByteArray?

    @JvmStatic
    external fun sealCredential(plaintext: ByteArray, aad: ByteArray): ByteArray?
    @JvmStatic
    external fun unsealCredential(ciphertext: ByteArray, aad: ByteArray): ByteArray?

    @JvmStatic
    external fun getPreflightToken(): String

    fun initialize(context: Context): Boolean {
        wbAesInit()
        val sigOk = verifySignature(context)

        KmsProvider.initialize()

        val seed = java.security.SecureRandom().generateSeed(32)
        wbAesObfuscateTables(seed)

        wbAesSideChannelDefense()
        return sigOk
    }

    fun runFullCheck(context: Context): Boolean {
        val sigOk = verifySignature(context)
        if (!sigOk) return false
        val rootOk = !isDeviceRooted()
        val hookOk = !isHookDetected()
        val emuOk = !isEmulator()
        val debugOk = !isDebugged()
        val mitmOk = !isMitmDetected()
        val score = getThreatScore()
        return sigOk && rootOk && hookOk && emuOk && debugOk && mitmOk && score < 3
    }

    fun encryptData(plaintext: ByteArray): ByteArray? {
        return wbAesEncrypt(plaintext)
    }

    fun decryptData(ciphertext: ByteArray): ByteArray? {
        return wbAesDecrypt(ciphertext)
    }
}
