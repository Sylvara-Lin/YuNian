package com.yunian.ai.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCredentialSealerTest {
    @Before
    fun verifyApplicationIdentity() {
        assertTrue(
            "Application signature must be verified before credential sealing",
            NativeBridge.verifySignature(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
        )
    }

    @Test
    fun credentialEnvelope_roundTripsOnlyForItsRecord() {
        val sealed = NativeCredentialSealer.seal("bot-token", "account_json")

        assertEquals("bot-token", NativeCredentialSealer.unseal(sealed, "account_json"))
        assertThrows(IllegalStateException::class.java) {
            NativeCredentialSealer.unseal(sealed, "context_tokens_json")
        }
    }

    @Test
    fun credentialEnvelope_rejectsTampering() {
        val sealed = NativeCredentialSealer.seal("bot-token", "account_json")
        val firstCiphertextCharacter = sealed[5]
        val tampered = sealed.replaceRange(
            5,
            6,
            if (firstCiphertextCharacter == 'A') "B" else "A",
        )

        assertThrows(IllegalStateException::class.java) {
            NativeCredentialSealer.unseal(tampered, "account_json")
        }
    }

    @Test
    fun credentialEnvelope_readsLegacyUnprefixedValues() {
        assertEquals("legacy-token", NativeCredentialSealer.unseal("legacy-token", "account_json"))
    }
}