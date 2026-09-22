package com.yunian.ai.common

object SuFlowApi {

    const val BASE_URL = "https://suflow.cloud"

    const val AUTH_BASE_URL = "https://suflow.cloud"

    const val CHAT_BASE_URL = "https://suflow.cloud/v1"

    const val CHAT_PATH = "/chat/completions"

    const val MODELS_PATH = "/models"

    const val USAGE_PATH = "/usage"

    const val HANDSHAKE_PATH = "/api/auth/handshake"

    const val KEYS_FETCH_PATH = "/api/keys/fetch"

    const val CLOVE_PROVISION = "/app/v1/provision"

    const val CLOVE_KEY_FETCH = "/api/keys/fetch"

    const val TIMEOUT_SECONDS = 30L

    const val TEST_MODEL = "gpt-4o-mini"

    fun deviceFingerprint(): String {
        val fp = android.os.Build.FINGERPRINT + "|" +
                 android.os.Build.MODEL + "|" +
                 android.os.Build.SERIAL
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(fp.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
