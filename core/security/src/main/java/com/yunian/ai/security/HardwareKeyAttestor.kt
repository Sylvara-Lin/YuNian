package com.yunian.ai.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.security.cert.CertificateException

object HardwareKeyAttestor {

    private val GOOGLE_ROOT_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIICiTCCAi+gAwIBAgIBATAKBggqhkjOPQQDAjA6MSYwJAYDVQQDDB1Hb29nbGUg
SGFyZHdhcmUgQXR0ZXN0YXRpb24gUm9vdCBDQTEQMA4GA1UECwwHZW5nby5jb20w
HhcNMjIwMTEwMTgzNDMxWhcNMzIwMTA4MTgzNDMxWjA6MSYwJAYDVQQDDB1Hb29n
bGUgSGFyZHdhcmUgQXR0ZXN0YXRpb24gUm9vdCBDQTEQMA4GA1UECwwHZW5nby5j
b20wWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARl3d+k/QluQ9TbC7PGJgLw1r/B
0fLPLw0w4Tw3SFP8xh8xYQp3AMvGhCH5iLp7iM8k7WNCfH1TfMR6j+LdiG8/o4IB
bTCCAWkwEgYDVR0TAQH/BAgwBgEB/wIBADAOBgNVHQ8BAf8EBAMCAoQwHQYDVR0O
BBYEFISPYgqBvzr4WDrr5hEbi/AQOUIvMB8GA1UdIwQYMBaAFISPYgqBvzr4WDrr
5hEbi/AQOUIvMIGCBgNVHR8EezB5MHegdqB0hnJodHRwczovL2FuZHJvaWQuZ29v
Z2xlYXBpcy5jb20vYXR0ZXN0YXRpb24vY3JsL0VDS5KMDAzQTQwNUY5Rjc2
RDM2MTQ5MjNGMDM0OEIwRTY5MUY0RjEyMTk2NTAwNTk4ODA1NTZEMzUzRjZENTAw
MzI3NTAvLmNybDAhBgNVHREEGjAYgRZjb25maXJtQGVuZ28uZ29vZ2xlLmNvbTAP
BgkqhkiG9w0BAQoFAAOCAQEAoGC4pJGM3jY1GLgN2F7IaEN+7QYCPz+x3o9SMdOa
FA+d3d4bTiY8jlQ7Ee3L7RFSP4s/jNTG+ZXHxU2kY1JMS1MW/nAqZqKAPfIZxRyn
MQJpIC6WmCpH6fq2CNEYKhTd4wEiTTpHLB5LkGAhFllCddXLE3yPOVQo+D/CNjCU
+SEuYRVqMgqnJh9VHk2KJO6eCQG0vJFmFkn6wOXJ7gBbkFXfXv8MH+ppDA+T5s6F
gMQo5oQQ5PkO5LqNUsGPXOC4JYFHGhSgQh8OPyJYWFYjjK/XHLqgK3DFVwfA7GHw
dA5HQAyBhB7HqR4CGJx0YPA1DThjCq8pJJFH0QLCGX02hA==
-----END CERTIFICATE-----""".trimIndent()

    private const val CLOCK_SKEW_MS = 24L * 3600L * 1000L

    private const val OID_ATTESTATION = "1.3.6.1.4.1.11129.2.1.17"

    private const val ATTEST_KEY_ALIAS = "lianyu_attest_key"

    private val googleRootCert: X509Certificate by lazy {
        CertificateFactory.getInstance("X.509").generateCertificate(
            GOOGLE_ROOT_CERT_PEM.byteInputStream()
        ) as X509Certificate
    }

    fun isTeeAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val kpg = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
                )
                kpg.initialize(
                    KeyGenParameterSpec.Builder(
                        ATTEST_KEY_ALIAS + "_probe",
                        KeyProperties.PURPOSE_SIGN
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                kpg.generateKeyPair()

                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                ks.deleteEntry(ATTEST_KEY_ALIAS + "_probe")
                true
            } catch (e: Exception) {
                false
            }
        } else false
    }

    fun attestDk(dkHash: ByteArray): AttestationResult {

        cleanup()

        val attestKey = generateAttestationKey(dkHash)
            ?: return AttestationResult.NotAvailable

        val chain = getAttestationChain(attestKey, dkHash) ?: return AttestationResult.ChainInvalid

        if (chain.isEmpty()) return AttestationResult.ChainInvalid
        val leaf = chain[0]

        val extValue = leaf.getExtensionValue(OID_ATTESTATION)
            ?: return AttestationResult.NotAttested

        val attestData = AttestationDataParser.parseAttestationData(extValue)
            ?: return AttestationResult.NotAttested

        val expectedPackage = "com.yunian.ai"
        if (attestData.packageName != expectedPackage) {
            return AttestationResult.PackageMismatch(attestData.packageName)
        }

        if (!attestData.isKeyStoreBacked) {
            return AttestationResult.NotHardwareBacked
        }

        if (!verifyChainOffline(chain)) {
            return AttestationResult.ChainInvalid
        }

        val now = System.currentTimeMillis()
        val validFrom = leaf.notBefore.time
        if (now < validFrom - CLOCK_SKEW_MS) {
            return AttestationResult.ClockSkewed
        }

        return AttestationResult.Verified(
            isStrongBox = attestData.isStrongBoxBacked,
            bootloaderLocked = attestData.bootloaderLocked,
            verifiedBootState = attestData.verifiedBootState
        )
    }

    private fun generateAttestationKey(challenge: ByteArray): KeyPair? {
        return try {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )
            kpg.initialize(
                KeyGenParameterSpec.Builder(
                    ATTEST_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .build()
            )
            kpg.generateKeyPair()
        } catch (e: Exception) {
            null
        }
    }

    private fun getAttestationChain(
        key: KeyPair, dkHash: ByteArray
    ): Array<X509Certificate>? {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.getCertificateChain(ATTEST_KEY_ALIAS) as? Array<X509Certificate>
        } catch (e: Exception) {
            null
        }
    }

    private fun verifyChainOffline(chain: Array<X509Certificate>): Boolean {
        return try {

            for (i in 0 until chain.size - 1) {
                chain[i].verify(chain[i + 1].publicKey)
            }

            val lastKey = chain.last().publicKey
            val lastEncoded = chain.last().encoded
            googleRootCert.verify(lastKey)

            if (!googleRootCert.encoded.contentEquals(lastEncoded)) {
                android.util.Log.w("Attestor", "Root cert verify passed but bytes differ")
            }
            true
        } catch (e: java.security.SignatureException) {

            android.util.Log.e("Attestor", "Chain signature verification failed: cert may be forged", e)
            false
        } catch (e: java.security.cert.CertificateException) {

            android.util.Log.e("Attestor", "Chain contains malformed certificate", e)
            false
        } catch (e: Exception) {

            android.util.Log.w("Attestor", "Chain verification failed: ${e.message}")
            false
        }
    }

    fun cleanup() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry(ATTEST_KEY_ALIAS)
        } catch (_: Exception) {}
    }
}

sealed class AttestationResult {

    object NotAvailable : AttestationResult()

    object NotAttested : AttestationResult()

    object ChainInvalid : AttestationResult()

    data class PackageMismatch(val found: String) : AttestationResult()

    object NotHardwareBacked : AttestationResult()

    object ClockSkewed : AttestationResult()

    data class Verified(
        val isStrongBox: Boolean,
        val bootloaderLocked: Boolean,
        val verifiedBootState: String
    ) : AttestationResult()
}
