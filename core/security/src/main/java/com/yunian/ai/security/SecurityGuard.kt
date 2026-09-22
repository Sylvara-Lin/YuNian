package com.yunian.ai.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import com.yunian.ai.common.PerformanceTrace

object SecurityGuard {

    private const val TAG = "YuNian-Security"

    @Volatile
    private var tampered = false

    @Volatile
    private var inited = false

    @Volatile
    private var lastCheckMs = 0L

    private const val CHECK_INTERVAL_MS = 30_000L

    fun productionPreflight(context: Context) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return
        val appCtx = context.applicationContext

        fun recordSoftFailure(reason: String) {
            tampered = true
            SecurityState.markTampered(reason)
            AuditLogger.log(appCtx, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, reason)
        }

        fun recordHardFailure(reason: String) {
            tampered = true
            SecurityState.markHardAuthFailure(reason)
            AuditLogger.log(appCtx, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, reason)
        }

        val wbAesReady = runCatching { NativeBridge.wbAesInit() }.isSuccess
        if (!wbAesReady) recordHardFailure("white-box AES init failed")

        val signatureOk = runCatching { NativeBridge.verifySignature(context) }.getOrDefault(false)
                || verifySignatureViaPackageManager(context)
        if (!signatureOk) recordHardFailure("APK signature verification failed")

        if (wbAesReady && signatureOk) {
            SecurityState.markPreflightPassed(
                wbAesReady = true,
                signatureTrusted = true,
                dexTrusted = false,
                soTrusted = false,
                resourcesTrusted = false,
                payloadVerified = false,
                kmsReady = KmsProvider.isReady
            )
        }

        Thread({
            try {
                val sdkInt = android.os.Build.VERSION.SDK_INT
                if (sdkInt <= 35) {
                    val antiDebugOk = runCatching { NativeBridge.antiDebugInit() }.getOrDefault(false)
                    if (!antiDebugOk && sdkInt <= 33) {
                        recordSoftFailure("anti-debug initialization failed")
                    }
                }

                val vmpAnchorsOk = runCatching {
                    CompositeVmpRuntime.verifyTrustAnchors()
                }.getOrDefault(false)
                if (!vmpAnchorsOk) recordSoftFailure("VMP trust anchors verification failed")

                val dexOk = runCatching { NativeBridge.checkDexIntegrity() }.getOrDefault(false)
                if (!dexOk) recordSoftFailure("DEX integrity verification failed")

                val soOk = runCatching { NativeBridge.checkSoIntegrity() }.getOrDefault(false)
                if (!soOk) recordSoftFailure("native library integrity verification failed")

                val resourcesOk = runCatching {
                    NativeBridge.checkResourcesIntegrity()
                }.getOrDefault(false)
                if (!resourcesOk) recordSoftFailure("resource integrity verification failed")

                val digest = runCatching { NativeBridge.computeIntegrityDigest() }.getOrNull()
                val digestOk = digest != null && digest.size == 32
                if (digestOk) {
                    DatabaseKeyProvider.setIntegrityDigest(digest!!)
                } else {
                    recordSoftFailure("APK integrity digest unavailable")
                }

                if (wbAesReady && signatureOk) {
                    SecurityState.markPreflightPassed(
                        wbAesReady = true,
                        signatureTrusted = true,
                        dexTrusted = dexOk,
                        soTrusted = soOk,
                        resourcesTrusted = resourcesOk,
                        payloadVerified = digestOk,
                        kmsReady = KmsProvider.isReady
                    )

                    if (!dexOk) recordSoftFailure("DEX integrity verification failed")
                    if (!soOk) recordSoftFailure("native library integrity verification failed")
                    if (!resourcesOk) recordSoftFailure("resource integrity verification failed")
                    if (!digestOk) recordSoftFailure("APK integrity digest unavailable")
                    if (!vmpAnchorsOk) recordSoftFailure("VMP trust anchors verification failed")
                }
            } catch (t: Throwable) {
                recordSoftFailure("background preflight failed: ${t.javaClass.simpleName}")
            }
        }, "ly-preflight-soft").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun init(context: Context) {
        if (inited) return
        inited = true
        val appCtx = context.applicationContext

        try {
            TinkAeadProvider.initialize()
        } catch (e: Exception) {
            AuditLogger.log(appCtx, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, "Tink AEAD init failed")
            tampered = true
            SecurityState.markTampered("Tink AEAD init failed")
        }
        PerformanceTrace.markSecurityTinkDone()

        var wbAesReady = false
        try {
            NativeBridge.wbAesInit()
            wbAesReady = true
        } catch (e: Exception) {
            AuditLogger.log(appCtx, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, "white-box AES init failed")
            tampered = true
            SecurityState.markTampered("white-box AES init failed")
        }
        PerformanceTrace.markSecurityWhiteBoxDone()

        runCatching { NativeBridge.verifySignature(context) }.getOrDefault(false)
        PerformanceTrace.markSecuritySignatureDone()

        var kmsOk = false
        try {
            kmsOk = KmsProvider.initialize()
        } catch (e: Exception) {
            AuditLogger.log(appCtx, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.KEYSTORE_ERROR, "KMS initialization failed")
            tampered = true
            SecurityState.markTampered("KMS initialization failed")
        }
        PerformanceTrace.markSecurityKmsDone()

        try {
            NativeBridge.wbAesInit()
            wbAesReady = true
        } catch (_: Exception) {  }

        SecurityState.markRuntimeReady(
            wbAesReady = wbAesReady,
            kmsReady = KmsProvider.isReady
        )
        PerformanceTrace.markSecurityAttestationDone()
        PerformanceTrace.markSecurityIntegrityDone()

        Thread({
            try {
                try {
                    HardwareKeyAttestation.ensureKeyPair()
                    val securityLevel = HardwareKeyAttestation.getSecurityLevel()
                    android.util.Log.i(
                        TAG,
                        "Hardware key attestation: level=$securityLevel (2=StrongBox 1=TEE 0=SW)"
                    )
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Hardware key attestation unavailable", e)
                }

                if (kmsOk) {
                    try {
                        val seed = java.security.SecureRandom().generateSeed(32)
                        NativeBridge.wbAesObfuscateTables(seed)
                        NativeBridge.wbAesSideChannelDefense()
                    } catch (e: Exception) {
                        AuditLogger.log(
                            appCtx,
                            AuditLogger.Level.WARNING,
                            AuditLogger.Event.TAMPER_DETECTED,
                            "L2 white-box hardening initialization failed"
                        )
                    }
                }

                try {
                    val digest = NativeBridge.computeIntegrityDigest()
                    if (digest != null && digest.size == 32) {
                        DatabaseKeyProvider.setIntegrityDigest(digest)
                    } else {
                        AuditLogger.log(
                            appCtx,
                            AuditLogger.Level.ERROR,
                            AuditLogger.Event.TAMPER_DETECTED,
                            "Integrity digest unavailable"
                        )
                        SecurityState.markTampered("Integrity digest unavailable")
                    }
                } catch (e: Exception) {
                    AuditLogger.log(
                        appCtx,
                        AuditLogger.Level.ERROR,
                        AuditLogger.Event.TAMPER_DETECTED,
                        "Integrity digest binding failed"
                    )
                    SecurityState.markTampered("Integrity digest binding failed")
                }

                val isEmulator = runCatching { NativeBridge.isEmulator() }.getOrDefault(false)
                        || android.os.Build.FINGERPRINT.contains("generic")
                        || android.os.Build.FINGERPRINT.contains("sdk_gphone")
                        || android.os.Build.MODEL.contains("sdk_gphone")
                if (isEmulator &&
                    (appCtx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
                ) {

                    android.util.Log.e(TAG, "emulator heuristic matched on release build")
                    AuditLogger.log(
                        appCtx,
                        AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.EMULATOR_DETECTED,
                        "emulator_detected_soft"
                    )
                    tampered = true
                    SecurityState.markTampered("emulator heuristic matched")
                }

                try {
                    NativeBridge.startHeartbeat()
                } catch (e: Exception) {
                    AuditLogger.log(
                        appCtx,
                        AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.TAMPER_DETECTED,
                        "heartbeat start failed"
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "background security init failed", t)
            }
        }, "ly-security-deferred").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun isSafe(context: Context): Boolean {
        if (tampered) {
            SecurityState.markTampered("SecurityGuard tampered")
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastCheckMs > CHECK_INTERVAL_MS) {
            performFullCheck(context)
        }

        return !tampered
    }

    fun getThreatAssessment(context: Context): ThreatAssessment {
        val score = NativeBridge.getThreatScore()
        return ThreatAssessment(
            isRooted = NativeBridge.isDeviceRooted(),
            isHooked = NativeBridge.isHookDetected(),
            isEmulator = NativeBridge.isEmulator(),
            isDebugged = NativeBridge.isDebugged(),
            isMitm = NativeBridge.isMitmDetected(),
            sigValid = NativeBridge.verifySignature(context),
            threatScore = score,
            dbKeyIntegrity = DatabaseKeyProvider.verifyKeyIntegrity(context)
        )
    }

    private

    fun isEmulatorBySensors(): Boolean {
        return try {

            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val ctx = app.javaClass.getMethod("getApplication").invoke(app) as? android.content.Context
            val sm = ctx?.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
                ?: return false
            val sensors = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)

            if (sensors.isEmpty()) return true

            val hasAccelerometer = sensors.any { it.type == android.hardware.Sensor.TYPE_ACCELEROMETER }
            val hasGyroscope = sensors.any { it.type == android.hardware.Sensor.TYPE_GYROSCOPE }
            val hasMagneticField = sensors.any { it.type == android.hardware.Sensor.TYPE_MAGNETIC_FIELD }

            if (!hasAccelerometer && !hasMagneticField) return true

            if (sensors.size < 5) return true
            false
        } catch (_: Exception) {

            return true
        }
    }

    fun isXposedDetected(): Boolean {
        val xposedClasses = arrayOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "de.robv.android.xposed.XposedInit",
            "de.robv.android.xposed.XposedInstaller",
            "de.robv.android.xposed.callbacks.XC_LoadPackage",
            "io.github.lsposed.LSPosedBridge",
            "org.lsposed.lspd.LSPosedBridge",
            "com.android.internal.util.XposedHelpers",
        )
        for (clsName in xposedClasses) {
            try {
                Class.forName(clsName)
                return true
            } catch (_: ClassNotFoundException) { }
        }
        return false
    }

    fun performFullCheck(context: Context) {
        lastCheckMs = System.currentTimeMillis()
        var detected = false

        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.DEBUG_DETECTED, "Debugger connected")
            detected = true
        }
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.DEBUG_DETECTED, "Debuggable flag set")
            detected = true
        }
        if (NativeBridge.isDebugged()) {
            AuditLogger.log(context, AuditLogger.Level.WARNING,
                AuditLogger.Event.DEBUG_DETECTED, "Native debug detection triggered")
            detected = true
        }

        if (NativeBridge.isHookDetected()) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.HOOK_DETECTED, "Xposed/Frida/LSPosed detected")
            detected = true
        }
        if (NativeBridge.isDeviceRooted()) {
            AuditLogger.log(context, AuditLogger.Level.ERROR,
                AuditLogger.Event.ROOT_DETECTED, "Root/Magisk/KernelSU detected")
            detected = true
        }
        if (NativeBridge.isEmulator()) {
            AuditLogger.log(context, AuditLogger.Level.WARNING,
                AuditLogger.Event.EMULATOR_DETECTED, "Emulator/virtual environment detected")
            detected = true
        }

        val heavyContext = context.applicationContext
        android.os.Looper.myQueue().addIdleHandler(object : android.os.MessageQueue.IdleHandler {
            override fun queueIdle(): Boolean {
                var heavyDetected = false

                if (!NativeBridge.verifySignature(heavyContext)) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.SIGNATURE_FAIL, "APK signature verification failed")
                    heavyDetected = true
                }

                if (NativeBridge.isMitmDetected()) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.ERROR,
                        AuditLogger.Event.MITM_DETECTED, "MITM/proxy detected")
                    heavyDetected = true
                }

                val score = NativeBridge.getThreatScore()
                if (score >= 3) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.THREAT_HIGH, "Threat score: $score (threshold: 3)")
                    heavyDetected = true
                }

                if (!NativeBridge.isHeartbeatOk()) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.TAMPER_DETECTED, "SO CRC32 heartbeat detected tampering")
                    heavyDetected = true
                }

                if (NativeBridge.isFridaDetected()) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.TAMPER_DETECTED, "Frida heartbeat detected instrumentation")
                    heavyDetected = true
                }

                if (!DatabaseKeyProvider.verifyKeyIntegrity(heavyContext)) {
                    AuditLogger.log(heavyContext, AuditLogger.Level.ERROR,
                        AuditLogger.Event.KEYSTORE_ERROR, "Database key integrity check failed")
                    heavyDetected = true
                }
                if (heavyDetected) {
                    tampered = true
                    SecurityState.markTampered("Security tampering confirmed (L2)")
                    AuditLogger.log(heavyContext, AuditLogger.Level.CRITICAL,
                        AuditLogger.Event.TAMPER_DETECTED, "L2 heavy check: tampering confirmed")
                }
                return false
            }
        })

        if (detected) {
            tampered = true
            SecurityState.markTampered("Security tampering confirmed (L1)")
            AuditLogger.log(context, AuditLogger.Level.CRITICAL,
                AuditLogger.Event.TAMPER_DETECTED, "L1 lightweight check: tampering confirmed")
        }
    }

    fun reset() {
        NativeBridge.resetGuard()
        tampered = false
        SecurityState.resetForTest()
        lastCheckMs = 0L
        AuditLogger.log(null, AuditLogger.Level.INFO,
            AuditLogger.Event.GUARD_RESET, "Security guard reset")
    }

    data class ThreatAssessment(
        val isRooted: Boolean,
        val isHooked: Boolean,
        val isEmulator: Boolean,
        val isDebugged: Boolean,
        val isMitm: Boolean,
        val sigValid: Boolean,
        val threatScore: Int,
        val dbKeyIntegrity: Boolean
    )

    enum class CryptoLevel(val level: Int, val description: String) {
        C1(1, "对称加密 (SM4 + 白盒AES)"),
        C2(2, "哈希与签名 (SM3 + SM2)"),
        C3(3, "密钥管理 (KMS + TEE)"),
        C4(4, "硬件信任根 (HSM + 远程证明 + PKI)")
    }

    fun getCryptoLevel(context: Context): CryptoLevel {

        val teeOk = DatabaseKeyProvider.isHardwareBacked(context)
        val kmsOk = KmsProvider.isReady

        if (teeOk && kmsOk) return CryptoLevel.C4

        if (kmsOk) return CryptoLevel.C3

        if (NativeBridge.verifySignature(context)) return CryptoLevel.C2

        return CryptoLevel.C1
    }

    internal fun verifySignatureViaPackageManager(context: Context): Boolean {
        return runCatching {
            val pm = context.packageManager
            val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            val pi = pm.getPackageInfo(context.packageName, flags)
            val signingInfo = pi.signingInfo ?: return@runCatching false
            val certs = signingInfo.apkContentsSigners ?: return@runCatching false
            if (certs.isEmpty()) return@runCatching false

            val md = java.security.MessageDigest.getInstance("SHA-256")
            val actual = md.digest(certs[0].toByteArray())

            val expected = NativeBridge.getExpectedCertSha256() ?: return@runCatching false

            actual.contentEquals(expected)
        }.getOrDefault(false)
    }

    @JvmStatic
    fun enableScreenProtection(activity: android.app.Activity) {
        try {
            activity.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Exception) {}
    }
}
