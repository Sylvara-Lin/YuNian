package com.yunian.ai.wechat.ilink

data class IlinkAccount(
    val botToken: String,
    val ilinkBotId: String,
    val ilinkUserId: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val accountId: String = DEFAULT_ACCOUNT_ID,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}