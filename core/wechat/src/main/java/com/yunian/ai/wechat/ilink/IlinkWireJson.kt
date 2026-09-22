package com.yunian.ai.wechat.ilink

import com.yunian.ai.wechat.wire.WireCdnMedia
import com.yunian.ai.wechat.wire.WireFileItem
import com.yunian.ai.wechat.wire.WireImageItem
import com.yunian.ai.wechat.wire.WireMessageItem
import com.yunian.ai.wechat.wire.WireTextItem
import com.yunian.ai.wechat.wire.WireVideoItem
import com.yunian.ai.wechat.wire.WireVoiceItem
import com.yunian.ai.wechat.wire.WireWeChatMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

// ---------------------------------------------------------------------------
// 官方协议 DTO（@tencent-weixin/openclaw-weixin api/types.ts 的 Kotlin 镜像）。
// JSON 字段一律 snake_case；解析容忍未知字段（ignoreUnknownKeys）。
// ---------------------------------------------------------------------------

/** getupdates 响应。 */
@Serializable
data class IlinkUpdatesRespDto(
    val ret: Int? = null,
    val errcode: Int? = null,
    val errmsg: String? = null,
    val msgs: List<IlinkMessageDto> = emptyList(),
    @SerialName("get_updates_buf") val getUpdatesBuf: String? = null,
    @SerialName("longpolling_timeout_ms") val longpollingTimeoutMs: Long? = null,
)

/** 统一消息（proto: WeixinMessage）。 */
@Serializable
data class IlinkMessageDto(
    val seq: Long? = null,
    @SerialName("message_id") val messageId: Long? = null,
    @SerialName("from_user_id") val fromUserId: String? = null,
    @SerialName("to_user_id") val toUserId: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("create_time_ms") val createTimeMs: Long? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("message_type") val messageType: Int? = null,
    @SerialName("message_state") val messageState: Int? = null,
    @SerialName("item_list") val itemList: List<IlinkItemDto> = emptyList(),
    @SerialName("context_token") val contextToken: String? = null,
)

/** 消息 item。 */
@Serializable
data class IlinkItemDto(
    val type: Int = 0,
    @SerialName("text_item") val textItem: IlinkTextItemDto? = null,
    @SerialName("image_item") val imageItem: IlinkImageItemDto? = null,
    @SerialName("voice_item") val voiceItem: IlinkVoiceItemDto? = null,
    @SerialName("file_item") val fileItem: IlinkFileItemDto? = null,
    @SerialName("video_item") val videoItem: IlinkVideoItemDto? = null,
)

@Serializable
data class IlinkTextItemDto(val text: String? = null)

/** CDN 媒体引用；aes_key 为 base64 编码字节。 */
@Serializable
data class IlinkCdnMediaDto(
    @SerialName("encrypt_query_param") val encryptQueryParam: String? = null,
    @SerialName("aes_key") val aesKey: String? = null,
    @SerialName("encrypt_type") val encryptType: Int? = null,
    @SerialName("full_url") val fullUrl: String? = null,
)

@Serializable
data class IlinkImageItemDto(
    val media: IlinkCdnMediaDto? = null,
    @SerialName("thumb_media") val thumbMedia: IlinkCdnMediaDto? = null,
    /** 16 字节 AES key 的 hex 明文；解析入站图片时优先于 media.aes_key。 */
    val aeskey: String? = null,
    val url: String? = null,
    @SerialName("mid_size") val midSize: Long? = null,
)

@Serializable
data class IlinkVoiceItemDto(
    val media: IlinkCdnMediaDto? = null,
    @SerialName("encode_type") val encodeType: Int? = null,
    /** 语音转文字内容。 */
    val text: String? = null,
)

@Serializable
data class IlinkFileItemDto(
    val media: IlinkCdnMediaDto? = null,
    @SerialName("file_name") val fileName: String? = null,
    val md5: String? = null,
    val len: String? = null,
)

@Serializable
data class IlinkVideoItemDto(
    val media: IlinkCdnMediaDto? = null,
    @SerialName("thumb_media") val thumbMedia: IlinkCdnMediaDto? = null,
    @SerialName("video_size") val videoSize: Long? = null,
)

/** getuploadurl 响应。 */
@Serializable
data class IlinkUploadUrlRespDto(
    @SerialName("upload_param") val uploadParam: String? = null,
    @SerialName("upload_full_url") val uploadFullUrl: String? = null,
)

/** get_bot_qrcode 响应。 */
@Serializable
data class IlinkQrCodeRespDto(
    val qrcode: String? = null,
    @SerialName("qrcode_img_content") val qrcodeImgContent: String? = null,
)

/** get_qrcode_status 响应。 */
@Serializable
data class IlinkQrStatusRespDto(
    val status: String? = null,
    @SerialName("bot_token") val botToken: String? = null,
    @SerialName("ilink_bot_id") val ilinkBotId: String? = null,
    val baseurl: String? = null,
    @SerialName("ilink_user_id") val ilinkUserId: String? = null,
    @SerialName("redirect_host") val redirectHost: String? = null,
)

// ---------------------------------------------------------------------------
// JSON ↔ Wire 映射 + 请求体组装
// ---------------------------------------------------------------------------

/**
 * 官方 snake_case JSON 与 [WireWeChatMessage] 之间的转换，以及各端点请求体组装。
 * 请求体结构严格对齐官方实现，不自创字段。
 */
object IlinkWireJson {

    /** 消息大类：2 = BOT 发送。 */
    const val MESSAGE_TYPE_BOT = 2
    /** 消息状态：2 = FINISH。 */
    const val MESSAGE_STATE_FINISH = 2
    /** item 类型：1 = 文本。 */
    const val ITEM_TYPE_TEXT = 1
    /** item 类型：2 = 图片。 */
    const val ITEM_TYPE_IMAGE = 2
    /** getuploadurl 的 media_type：1 = 图片。 */
    const val MEDIA_TYPE_IMAGE = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** 解析 getupdates 原始响应文本。 */
    fun parseUpdatesResponse(raw: String): IlinkUpdatesRespDto = json.decodeFromString(raw)

    /** 官方消息 DTO → Wire 模型（context_token 必须透传，收发端依赖它维持会话关联）。 */
    fun toWireMessage(dto: IlinkMessageDto): WireWeChatMessage = WireWeChatMessage(
        seq = dto.seq ?: dto.messageId,
        messageId = dto.messageId,
        fromUserId = dto.fromUserId,
        toUserId = dto.toUserId,
        createTimeMs = dto.createTimeMs,
        sessionId = dto.sessionId,
        messageType = dto.messageType,
        messageState = dto.messageState,
        itemList = dto.itemList.map { toWireItem(it) }.takeIf { dto.itemList.isNotEmpty() },
        contextToken = dto.contextToken,
    )

    private fun toWireItem(dto: IlinkItemDto): WireMessageItem = WireMessageItem(
        type = dto.type,
        textItem = dto.textItem?.let { WireTextItem(text = it.text) },
        imageItem = dto.imageItem?.let { img ->
            WireImageItem(
                cdnImg = WireCdnMedia(
                    encryptQueryParam = img.media?.encryptQueryParam,
                    aesKey = unifyImageAesKeyToBase64(img.aeskey, img.media?.aesKey),
                    fullUrl = img.media?.fullUrl,
                ),
            )
        },
        voiceItem = dto.voiceItem?.let { voice ->
            WireVoiceItem(
                cdnVoice = toWireMedia(voice.media),
            )
        },
        fileItem = dto.fileItem?.let { file ->
            WireFileItem(
                cdnFile = toWireMedia(file.media),
                fileName = file.fileName,
            )
        },
        videoItem = dto.videoItem?.let { video ->
            WireVideoItem(
                cdnVideo = toWireMedia(video.media),
                cdnThumb = toWireMedia(video.thumbMedia),
            )
        },
    )

    private fun toWireMedia(dto: IlinkCdnMediaDto?): WireCdnMedia? = dto?.let {
        WireCdnMedia(
            encryptQueryParam = it.encryptQueryParam,
            aesKey = it.aesKey,
            fullUrl = it.fullUrl,
        )
    }

    /**
     * 入站图片 key 统一为 base64(16 字节原始 key)：
     * - image_item.aeskey 是 hex 明文（32 个 hex 字符）→ hex 解码后转 base64；
     * - media.aes_key 已是 base64 → 原样保留（下载端 parseAesKeyBytes 兼容两种形态）。
     */
    fun unifyImageAesKeyToBase64(aeskeyHex: String?, mediaAesKey: String?): String? {
        val hex = aeskeyHex?.trim().orEmpty()
        if (hex.length == 32 && hex.all { it.isHexChar() }) {
            return Base64.getEncoder().encodeToString(IlinkCdnCodec.hexToBytes(hex))
        }
        return mediaAesKey?.takeIf { it.isNotBlank() }
    }

    // -----------------------------------------------------------------------
    // 请求体组装
    // -----------------------------------------------------------------------

    /** getupdates 请求体：`{get_updates_buf, base_info}`。 */
    fun buildGetUpdatesBody(getUpdatesBuf: String, baseInfo: JsonObject): JsonObject =
        buildJsonObject {
            put("get_updates_buf", getUpdatesBuf)
            put("base_info", baseInfo)
        }

    /** sendmessage 文本消息请求体（官方 send.ts buildTextMessageReq + api.sendMessage）。 */
    fun buildTextMessageBody(
        toUserId: String,
        text: String,
        contextToken: String,
        clientId: String,
        baseInfo: JsonObject,
    ): JsonObject = buildJsonObject {
        put("msg", buildJsonObject {
            put("from_user_id", "")
            put("to_user_id", toUserId)
            put("client_id", clientId)
            put("message_type", MESSAGE_TYPE_BOT)
            put("message_state", MESSAGE_STATE_FINISH)
            put("item_list", buildJsonArray {
                add(buildJsonObject {
                    put("type", ITEM_TYPE_TEXT)
                    put("text_item", buildJsonObject { put("text", text) })
                })
            })
            put("context_token", contextToken)
        })
        put("base_info", baseInfo)
    }

    /** sendmessage 图片消息请求体（官方 send.ts sendImageMessageWeixin）。 */
    fun buildImageMessageBody(
        toUserId: String,
        contextToken: String,
        clientId: String,
        downloadEncryptedQueryParam: String,
        aesKeyBase64: String,
        cipherSize: Int,
        baseInfo: JsonObject,
    ): JsonObject = buildJsonObject {
        put("msg", buildJsonObject {
            put("from_user_id", "")
            put("to_user_id", toUserId)
            put("client_id", clientId)
            put("message_type", MESSAGE_TYPE_BOT)
            put("message_state", MESSAGE_STATE_FINISH)
            put("item_list", buildJsonArray {
                add(buildJsonObject {
                    put("type", ITEM_TYPE_IMAGE)
                    put("image_item", buildJsonObject {
                        put("media", buildJsonObject {
                            put("encrypt_query_param", downloadEncryptedQueryParam)
                            put("aes_key", aesKeyBase64)
                            put("encrypt_type", 1)
                        })
                        put("mid_size", cipherSize)
                    })
                })
            })
            put("context_token", contextToken)
        })
        put("base_info", baseInfo)
    }

    /** getuploadurl 请求体（官方 cdn/upload.ts uploadMediaToCdn）。 */
    fun buildGetUploadUrlBody(
        filekey: String,
        toUserId: String,
        rawsize: Int,
        rawfilemd5: String,
        filesize: Int,
        aeskeyHex: String,
        baseInfo: JsonObject,
    ): JsonObject = buildJsonObject {
        put("filekey", filekey)
        put("media_type", MEDIA_TYPE_IMAGE)
        put("to_user_id", toUserId)
        put("rawsize", rawsize)
        put("rawfilemd5", rawfilemd5)
        put("filesize", filesize)
        put("no_need_thumb", true)
        put("aeskey", aeskeyHex)
        put("base_info", baseInfo)
    }

    private fun Char.isHexChar(): Boolean =
        isDigit() || this in 'a'..'f' || this in 'A'..'F'
}
