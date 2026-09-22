package com.yunian.ai.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.*
import java.security.spec.ECGenParameterSpec
import android.security.keystore.KeyInfo

object HardwareKeyAttestation {

    private const val TAG = "YuNian-HKA"
    private const val KEY_ALIAS = "lianyu_hka_ecdsa_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Volatile
    private var initialized = false

    @Volatile
    private var keyPair: KeyPair? = null

    fun ensureKeyPair(): Boolean {
        if (initialized && keyPair != null) return true

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS)) {

                val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
                keyPair = KeyPair(entry.certificate.publicKey, entry.privateKey)
                Log.d(TAG, "Hardware key pair loaded (existing)")
            } else {

                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .setAttestationChallenge(generateChallenge())
                    .apply {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            setIsStrongBoxBacked(true)
                        }
                    }
                    .build()

                val keyGenerator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC,
                    ANDROID_KEYSTORE
                )
                keyGenerator.initialize(spec)
                keyPair = keyGenerator.generateKeyPair()
                Log.d(TAG, "Hardware key pair generated in secure hardware")
            }

            initialized = true
            true
        } catch (e: Exception) {

            Log.w(TAG, "StrongBox unavailable — falling back to TEE", e)
            generateTeeKey()
        } catch (e: Exception) {
            Log.e(TAG, "Hardware key attestation failed", e)
            false
        }
    }

    private fun generateTeeKey(): Boolean {
        return try {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .setAttestationChallenge(generateChallenge())
                .build()

            val keyGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            keyGenerator.initialize(spec)
            keyPair = keyGenerator.generateKeyPair()
            initialized = true
            Log.d(TAG, "Hardware key pair generated in TEE")
            true
        } catch (e: Exception) {
            Log.e(TAG, "TEE key generation failed", e)
            false
        }
    }

    private fun generateChallenge(): ByteArray {
        val challenge = ByteArray(16)
        SecureRandom().nextBytes(challenge)
        return challenge
    }

    fun getCertificateChain(): Array<ByteArray>? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val chain = keyStore.getCertificateChain(KEY_ALIAS) ?: return null
            chain.map { it.encoded }.toTypedArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get certificate chain", e)
            null
        }
    }

    fun sign(data: ByteArray): ByteArray? {
        return try {
            val privateKey = keyPair?.private ?: return null
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(data)
            signature.sign()
        } catch (e: Exception) {
            Log.e(TAG, "Hardware signing failed", e)
            null
        }
    }

    fun getSecurityLevel(): Int {
        return try {
            val factory = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return -1
            val keyInfo = factory.getKeySpec(entry.privateKey, KeyInfo::class.java)
            when {
                keyInfo.isInsideSecureHardware -> 1
                else -> 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Security level check failed", e)
            -1
        }
    }
}
