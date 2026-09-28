package com.yunian.ai.security

object SecureStrings {

    fun getByStringId(id: Int): String = when (id) {
        0 -> apiHost
        1 -> apiSyncPath
        2 -> gitHubApiUrl
        3 -> gitHubRepoOwner
        4 -> gitHubRepoName
        5 -> oauthRedirect
        6 -> openaiBaseUrl
        7 -> deepseekBaseUrl
        8 -> dashscopeBaseUrl
        9 -> kimiBaseUrl
        10 -> anthropicBaseUrl
        11 -> geminiBaseUrl
        12 -> securityProviderClass
        13 -> chatCompletionsPath
        14 -> messagesPath
        15 -> chatCompletionsNoSlash
        16 -> messagesNoSlash
        17 -> xiaomiBaseUrl
        18 -> zhipuBaseUrl
        19 -> siliconflowBaseUrl
        20 -> openrouterBaseUrl
        21 -> groqBaseUrl
        22 -> partnerBaseUrl
        else -> ""
    }

    val apiHost: String by lazy { NativeBridge.getSecureString(0) }

    val apiSyncPath: String by lazy { NativeBridge.getSecureString(1) }

    val gitHubApiUrl: String by lazy { NativeBridge.getSecureString(2) }

    val gitHubRepoOwner: String by lazy { NativeBridge.getSecureString(3) }

    val gitHubRepoName: String by lazy { NativeBridge.getSecureString(4) }

    val oauthRedirect: String by lazy { NativeBridge.getSecureString(5) }

    val openaiBaseUrl: String by lazy { NativeBridge.getSecureString(6) }

    val deepseekBaseUrl: String by lazy { NativeBridge.getSecureString(7) }

    val dashscopeBaseUrl: String by lazy { NativeBridge.getSecureString(8) }

    val kimiBaseUrl: String by lazy { NativeBridge.getSecureString(9) }

    val anthropicBaseUrl: String by lazy { NativeBridge.getSecureString(10) }

    val geminiBaseUrl: String by lazy { NativeBridge.getSecureString(11) }

    val securityProviderClass: String by lazy { NativeBridge.getSecureString(12) }

    val chatCompletionsPath: String by lazy { NativeBridge.getSecureString(13) }

    val messagesPath: String by lazy { NativeBridge.getSecureString(14) }

    val chatCompletionsNoSlash: String by lazy { NativeBridge.getSecureString(15) }

    val messagesNoSlash: String by lazy { NativeBridge.getSecureString(16) }

    val xiaomiBaseUrl: String by lazy { NativeBridge.getSecureString(17) }

    val zhipuBaseUrl: String by lazy { NativeBridge.getSecureString(18) }

    val siliconflowBaseUrl: String by lazy { NativeBridge.getSecureString(19) }

    val openrouterBaseUrl: String by lazy { NativeBridge.getSecureString(20) }

    val groqBaseUrl: String by lazy { NativeBridge.getSecureString(21) }

    val partnerBaseUrl: String by lazy { NativeBridge.getSecureString(22) }
}
