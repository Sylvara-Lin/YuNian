package com.yunian.ai.database.repository

import com.yunian.ai.security.CompositeVmpRuntime

object S0 : ApiConfigRepository.SecretCodec {
    override fun encrypt(plaintext: String): String {
        if (CompositeVmpRuntime.debugMode) return plaintext
        return CompositeVmpRuntime.execute(CompositeVmpRuntime.OP_API_SECRET_ENCRYPT, plaintext) as String
    }

    override fun decrypt(value: String): String? {
        if (CompositeVmpRuntime.debugMode) {
            if (value.startsWith("enc:")) {
                return CompositeVmpRuntime.execute(CompositeVmpRuntime.OP_API_SECRET_DECRYPT, value) as String?
            }
            return value
        }
        return CompositeVmpRuntime.execute(CompositeVmpRuntime.OP_API_SECRET_DECRYPT, value) as String?
    }

    override fun isEncrypted(value: String): Boolean {
        if (CompositeVmpRuntime.debugMode) return value.startsWith("enc:")
        return value.startsWith("enc:v4:tink-env:") || value.startsWith("enc:v2:kms:") || value.startsWith("enc:v3:tink:")
    }
}
