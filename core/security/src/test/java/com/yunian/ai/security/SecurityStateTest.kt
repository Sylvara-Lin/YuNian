package com.yunian.ai.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStateTest {
    @Test
    fun trustedSensitiveStateRequiresKmsReady() {
        SecurityState.resetForTest()
        SecurityState.markPreflightPassed(
            wbAesReady = true,
            signatureTrusted = true,
            dexTrusted = true,
            soTrusted = true,
            resourcesTrusted = true,
            payloadVerified = true,
            kmsReady = false
        )

        assertFalse(
            "Sensitive operations must not be trusted until KMS is ready.",
            SecurityState.snapshot().isTrustedForSensitiveOps
        )
        assertEquals(SecurityState.Admission.ALLOW_LOCAL, SecurityState.admission())
        assertTrue(SecurityState.canStartLocalBusiness())

        SecurityState.resetForTest()
    }

    @Test
    fun trustedSensitiveStateRequiresRuntimeReadinessAndPayloadVerification() {
        SecurityState.resetForTest()

        SecurityState.markPreflightPassed(
            wbAesReady = true,
            signatureTrusted = true,
            dexTrusted = true,
            soTrusted = true,
            resourcesTrusted = true,
            payloadVerified = false,
            kmsReady = true
        )
        assertFalse(
            "Sensitive operations must not be trusted before payload verification.",
            SecurityState.snapshot().isTrustedForSensitiveOps
        )
        assertEquals(SecurityState.Admission.ALLOW_LOCAL, SecurityState.admission())

        SecurityState.markPreflightPassed(
            wbAesReady = true,
            signatureTrusted = true,
            dexTrusted = true,
            soTrusted = true,
            resourcesTrusted = true,
            payloadVerified = true,
            kmsReady = true
        )
        assertTrue(
            "Sensitive operations should be trusted only when every gate is ready.",
            SecurityState.snapshot().isTrustedForSensitiveOps
        )
        assertEquals(SecurityState.Admission.ALLOW_FULL, SecurityState.admission())

        SecurityState.markTampered("unit-test")
        assertFalse(
            "Tamper state must revoke sensitive-operation trust immediately.",
            SecurityState.snapshot().isTrustedForSensitiveOps
        )
        assertEquals(SecurityState.Admission.ALLOW_LOCAL, SecurityState.admission())
        assertTrue(
            "Soft tamper must still allow offline-first local business.",
            SecurityState.canStartLocalBusiness()
        )

        SecurityState.resetForTest()
    }

    @Test
    fun hardAuthFailureBlocksLocalBusinessInit() {
        SecurityState.resetForTest()
        SecurityState.markHardAuthFailure("signature mismatch")

        assertEquals(SecurityState.Admission.BLOCK, SecurityState.admission())
        assertFalse(SecurityState.canStartLocalBusiness())
        assertFalse(SecurityState.snapshot().isTrustedForSensitiveOps)

        SecurityState.resetForTest()
    }
}
