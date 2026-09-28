package com.yunian.ai.security

import android.content.Context

object VmpDex2cDispatcher {

    enum class DispatchTarget {

        NATIVE_DEX2C,

        VMP_BYTECODE,

        ART_FALLBACK
    }

    @Volatile
    private var dex2cLoaded: Boolean = false

    private val resolutionCache = HashMap<String, DispatchTarget>()

    private val dex2cWhitelist: Set<String> = setOf(

        "NativeBridge.verifySignature",
        "NativeBridge.isSafe",

        "NativeBridge.vmpRootDetect",
        "NativeBridge.vmpCodeIntegrity",
        "NativeBridge.vmpFridaHeartbeat",

        "NativeBridge.isDeviceRooted",
        "NativeBridge.isHookDetected",
        "NativeBridge.isEmulator",
        "NativeBridge.isDebugged",

        "KmsProvider.decryptWithMetadata",

        "SecurityOrchestrator.encrypt",
        "SecurityOrchestrator.decrypt"
    )

    private val vmpMethods: Set<String> = setOf(
        "NativeBridge.verifySignature",
        "NativeBridge.isSafe",
        "KmsProvider.decryptWithMetadata",
        "NativeBridge.vmpRootDetect",
        "NativeBridge.vmpCodeIntegrity",
        "NativeBridge.vmpSm3Hash",
        "NativeBridge.vmpFridaHeartbeat",
        "NativeBridge.vmpWbAesKeycheck",
        "NativeBridge.vmpKmsDeriveSk",
        "NativeBridge.vmpTeeAttest",
        "NativeBridge.vmpApkSigVerify",
        "NativeBridge.vmpSecureWipe"
    )

    fun init(): Boolean {
        return try {
            System.loadLibrary("lianyu_dex2c")
            dex2cLoaded = true
            if (com.yunian.ai.security.BuildConfig.DEBUG) {
                android.util.Log.i("VmpDex2c", "Dex2C native library loaded — using native dispatch")
            }
            true
        } catch (e: UnsatisfiedLinkError) {
            dex2cLoaded = false
            if (com.yunian.ai.security.BuildConfig.DEBUG) {
                android.util.Log.w("VmpDex2c", "Dex2C native library not available — falling back to VMP/ART")
            }
            false
        }
    }

    val isDex2cAvailable: Boolean
        get() = dex2cLoaded

    fun resolve(methodKey: String): DispatchTarget {

        resolutionCache[methodKey]?.let { return it }

        val target = when {
            dex2cLoaded && methodKey in dex2cWhitelist -> DispatchTarget.NATIVE_DEX2C
            methodKey in vmpMethods -> DispatchTarget.VMP_BYTECODE
            else -> DispatchTarget.ART_FALLBACK
        }

        resolutionCache[methodKey] = target
        return target
    }

    fun overrideTarget(methodKey: String, target: DispatchTarget) {
        resolutionCache[methodKey] = target
    }

    fun clearCache() {
        resolutionCache.clear()
    }

    fun dispatch(methodKey: String, context: Context? = null, vararg args: Any?): Any? {
        val target = resolve(methodKey)

        return when (target) {
            DispatchTarget.NATIVE_DEX2C -> dispatchNative(methodKey, context, *args)
            DispatchTarget.VMP_BYTECODE -> dispatchVmp(methodKey, context, *args)
            DispatchTarget.ART_FALLBACK -> dispatchArt(methodKey, context, *args)
        }
    }

    private fun dispatchNative(methodKey: String, context: Context?, vararg args: Any?): Any? {
        return try {
            when (methodKey) {
                "NativeBridge.verifySignature" ->
                    nativeVerifySignature(context!!)
                "NativeBridge.isSafe" ->
                    nativeIsSafe()
                "KmsProvider.decryptWithMetadata" -> {
                    val ciphertext = args.getOrNull(0) as? ByteArray ?: return null
                    val metadata = args.getOrNull(1) as? ByteArray ?: return null
                    nativeDecryptWithMetadata(ciphertext, metadata)
                }
                else -> {
                    if (com.yunian.ai.security.BuildConfig.DEBUG) {
                        android.util.Log.w("VmpDex2c", "Unregistered native method: $methodKey")
                    }
                    null
                }
            }
        } catch (e: Exception) {
            if (com.yunian.ai.security.BuildConfig.DEBUG) {
                android.util.Log.e("VmpDex2c", "Native dispatch failed for $methodKey: ${e.message}")
            }

            dispatchVmp(methodKey, context, *args)
        }
    }

    private fun dispatchVmp(methodKey: String, context: Context?, vararg args: Any?): Any? {
        return when (methodKey) {
            "NativeBridge.verifySignature" -> {

                CompositeVmpRuntime.verifyApkSignature()
            }
            "NativeBridge.isSafe" -> {

                NativeBridge.isSafe()
            }
            "KmsProvider.decryptWithMetadata" -> {
                val ciphertext = args.getOrNull(0) as? ByteArray ?: return null
                val metadata = args.getOrNull(1) as? ByteArray ?: return null
                KmsProvider.decryptWithMetadata(ciphertext, metadata)
            }
            else -> {
                if (com.yunian.ai.security.BuildConfig.DEBUG) {
                    android.util.Log.w("VmpDex2c", "Unregistered VMP method: $methodKey")
                }
                dispatchArt(methodKey, context, *args)
            }
        }
    }

    private fun dispatchArt(methodKey: String, context: Context?, vararg args: Any?): Any? {
        if (com.yunian.ai.security.BuildConfig.DEBUG) {
            android.util.Log.w("VmpDex2c", "ART fallback for: $methodKey")
        }
        return when (methodKey) {
            "NativeBridge.verifySignature" ->
                context?.let { NativeBridge.verifySignature(it) }
            "NativeBridge.isSafe" ->
                NativeBridge.isSafe()
            "KmsProvider.decryptWithMetadata" -> {
                val ciphertext = args.getOrNull(0) as? ByteArray ?: return null
                val metadata = args.getOrNull(1) as? ByteArray ?: return null
                KmsProvider.decryptWithMetadata(ciphertext, metadata)
            }
            else -> null
        }
    }

    @JvmStatic
    private external fun nativeVerifySignature(context: Context): Boolean

    @JvmStatic
    private external fun nativeIsSafe(): Boolean

    @JvmStatic
    private external fun nativeDecryptWithMetadata(ciphertext: ByteArray, metadata: ByteArray): ByteArray?

    fun shouldUseDex2C(methodKey: String): Boolean {
        return resolve(methodKey) == DispatchTarget.NATIVE_DEX2C
    }

    fun getStats(): Map<DispatchTarget, Int> {
        val stats = mutableMapOf<DispatchTarget, Int>()
        stats[DispatchTarget.NATIVE_DEX2C] = 0
        stats[DispatchTarget.VMP_BYTECODE] = 0
        stats[DispatchTarget.ART_FALLBACK] = 0
        for ((_, target) in resolutionCache) {
            stats[target] = (stats[target] ?: 0) + 1
        }
        return stats
    }
}
