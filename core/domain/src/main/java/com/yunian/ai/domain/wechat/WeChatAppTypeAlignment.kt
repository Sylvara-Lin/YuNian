package com.yunian.ai.domain.wechat

object WeChatAppTypeAlignment {

    fun toAppContentType(kind: WeChatContentKind): AppContentType? = when (kind) {
        WeChatContentKind.TEXT -> AppContentType.TEXT
        WeChatContentKind.IMAGE -> AppContentType.IMAGE
        WeChatContentKind.VOICE -> AppContentType.VOICE
        WeChatContentKind.FILE -> AppContentType.FILE
        WeChatContentKind.VIDEO -> AppContentType.VIDEO
        WeChatContentKind.UNKNOWN -> null
    }

    fun toWeChatContentKind(app: AppContentType): WeChatContentKind? = when (app) {
        AppContentType.TEXT -> WeChatContentKind.TEXT
        AppContentType.IMAGE -> WeChatContentKind.IMAGE
        AppContentType.VOICE, AppContentType.AUDIO -> WeChatContentKind.VOICE
        AppContentType.FILE -> WeChatContentKind.FILE
        AppContentType.VIDEO -> WeChatContentKind.VIDEO
        AppContentType.REASONING -> null
    }

    fun toWeChatContentKindFromAppName(appTypeName: String?): WeChatContentKind? {
        val app = AppContentType.fromWireName(appTypeName) ?: return null
        return toWeChatContentKind(app)
    }

    fun isSyncableToWeChat(app: AppContentType): Boolean =
        toWeChatContentKind(app) != null

    fun isSyncableToWeChat(appTypeName: String?): Boolean {
        val app = AppContentType.fromWireName(appTypeName) ?: return false
        return isSyncableToWeChat(app)
    }

    fun isSupportedByAiDialogue(kind: WeChatContentKind): Boolean = when (kind) {
        WeChatContentKind.TEXT, WeChatContentKind.IMAGE -> true
        else -> false
    }

    fun toAiMessageTypeName(kind: WeChatContentKind): String? = when (kind) {
        WeChatContentKind.TEXT -> "text"
        WeChatContentKind.IMAGE -> "image"
        else -> null
    }

    fun toAiMessageTypeName(app: AppContentType): String? {
        val kind = toWeChatContentKind(app) ?: return null
        return toAiMessageTypeName(kind)
    }
}
