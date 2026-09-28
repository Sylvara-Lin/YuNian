package com.yunian.ai.security

object SecurityState {
    enum class Admission {

        ALLOW_FULL,

        ALLOW_LOCAL,

        BLOCK
    }

    enum class RiskLevel(val tier: Int) {
        SAFE(0), LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

        companion object {
            fun fromTier(tier: Int): RiskLevel = entries.firstOrNull { it.tier == tier } ?: CRITICAL
        }
    }

    const val SENSITIVE_OPS_MAX_RISK: Int = 1

    data class Snapshot(
        val preflightPassed: Boolean = false,
        val wbAesReady: Boolean = false,
        val signatureTrusted: Boolean = false,
        val dexTrusted: Boolean = false,
        val soTrusted: Boolean = false,
        val resourcesTrusted: Boolean = false,
        val payloadVerified: Boolean = false,
        val kmsReady: Boolean = false,
        val tampered: Boolean = false,

        val hardAuthFailed: Boolean = false,

        val riskLevel: Int = RiskLevel.SAFE.tier,
        val reason: String? = null
    ) {
        val isTrustedForSensitiveOps: Boolean
            get() = preflightPassed &&
                wbAesReady &&
                signatureTrusted &&
                dexTrusted &&
                soTrusted &&
                resourcesTrusted &&
                payloadVerified &&
                kmsReady &&
                riskLevel <= SENSITIVE_OPS_MAX_RISK &&
                !tampered &&
                !hardAuthFailed

        val admission: Admission
            get() = when {
                hardAuthFailed -> Admission.BLOCK
                isTrustedForSensitiveOps -> Admission.ALLOW_FULL

                riskLevel > SENSITIVE_OPS_MAX_RISK -> Admission.ALLOW_LOCAL
                else -> Admission.ALLOW_LOCAL
            }
    }

    @Volatile
    private var current = Snapshot()

    fun snapshot(): Snapshot = current

    fun admission(): Admission = current.admission

    fun canStartLocalBusiness(): Boolean = current.admission != Admission.BLOCK

    fun markPreflightPassed(
        wbAesReady: Boolean,
        signatureTrusted: Boolean,
        dexTrusted: Boolean,
        soTrusted: Boolean,
        resourcesTrusted: Boolean,
        payloadVerified: Boolean,
        kmsReady: Boolean
    ) {
        current = Snapshot(
            preflightPassed = true,
            wbAesReady = wbAesReady,
            signatureTrusted = signatureTrusted,
            dexTrusted = dexTrusted,
            soTrusted = soTrusted,
            resourcesTrusted = resourcesTrusted,
            payloadVerified = payloadVerified,
            kmsReady = kmsReady,
            tampered = false,
            hardAuthFailed = false,
            reason = null
        )
    }

    fun markRuntimeReady(wbAesReady: Boolean, kmsReady: Boolean) {
        val previous = current
        current = previous.copy(
            wbAesReady = previous.wbAesReady || wbAesReady,
            kmsReady = previous.kmsReady || kmsReady
        )
    }

    fun updateRiskLevel() {
        val tier = try {
            NativeBridge.zeroTrustGetRiskLevel()
        } catch (t: Throwable) {
            RiskLevel.CRITICAL.tier
        }
        val newLevel = RiskLevel.fromTier(tier)
        val previous = current
        if (previous.riskLevel != newLevel.tier) {
            current = previous.copy(riskLevel = newLevel.tier)
        }
    }

    fun markTampered(reason: String) {
        current = current.copy(tampered = true, reason = reason)
    }

    fun markHardAuthFailure(reason: String) {
        current = current.copy(
            tampered = true,
            hardAuthFailed = true,
            reason = reason
        )
    }

    fun resetForTest() {
        current = Snapshot()
    }
}
