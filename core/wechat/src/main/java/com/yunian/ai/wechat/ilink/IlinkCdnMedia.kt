package com.yunian.ai.wechat.ilink

data class IlinkCdnMedia(
    val encryptQueryParam: String?,
    val aesKey: String?,
    /** 服务端直接返回的完整下载 URL；非空时优先于 encryptQueryParam 拼接。 */
    val fullUrl: String? = null,
)