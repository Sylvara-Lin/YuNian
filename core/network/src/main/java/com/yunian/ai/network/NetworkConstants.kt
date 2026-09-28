package com.yunian.ai.network

import com.yunian.ai.database.model.ApiProvider

object NetworkConstants {

    const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 10
    const val DEFAULT_READ_TIMEOUT_SECONDS = 30
    const val DEFAULT_WRITE_TIMEOUT_SECONDS = 10
    const val DEFAULT_CALL_TIMEOUT_SECONDS = 30
    const val DEFAULT_PING_INTERVAL_SECONDS = 30

    const val SHORT_READ_TIMEOUT_SECONDS = 20
    const val STREAMING_READ_TIMEOUT_SECONDS = 60
    const val VISION_READ_TIMEOUT_SECONDS = 25
    const val DEBUG_LOG_TIMEOUT_SECONDS = 3
    const val DOWNLOAD_WRITE_TIMEOUT_SECONDS = 15
    const val MODEL_FETCH_TIMEOUT_SECONDS = 25

    const val CONNECTION_POOL_MAX_IDLE = 5
    const val CONNECTION_POOL_KEEP_ALIVE_MINUTES = 5L
    const val PARTNER_CONNECTION_POOL_MAX_IDLE = 3

    const val OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/"
    const val OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions"
    const val OPENAI_DEFAULT_API_VERSION = "/v1"
    val OPENAI_BILLING_SUBSCRIPTION_ENDPOINTS = listOf(
        "/dashboard/billing/subscription",
        "/v1/dashboard/billing/subscription"
    )
    val OPENAI_BILLING_USAGE_ENDPOINTS = listOf(
        "/dashboard/billing/usage",
        "/v1/dashboard/billing/usage"
    )

    const val PARTNER_CONNECT_TIMEOUT_SECONDS = 15
    const val PARTNER_READ_TIMEOUT_SECONDS = 25

    const val BAIDU_TTS_URL = "https://tsn.baidu.com/text2audio"

    val SILICONFLOW_STT_URL = ApiProvider.SILICONFLOW.defaultBaseUrl.trimEnd('/') + "/audio/transcriptions"
    val SILICONFLOW_TTS_URL = ApiProvider.SILICONFLOW.defaultBaseUrl.trimEnd('/') + "/audio/speech"

    val SILICONFLOW_VOICE_LIST_URL = ApiProvider.SILICONFLOW.defaultBaseUrl.trimEnd('/') + "/audio/voice/list"

    const val QQ_BOT_AUTH_BASE_URL = "https://bots.qq.com/"
    const val QQ_BOT_API_BASE_URL = "https://api.sgroup.qq.com/"

    const val QQ_BOT_LITE_CREATE_TASK_URL = "https://q.qq.com/lite/create_bind_task"
    const val QQ_BOT_LITE_POLL_RESULT_URL = "https://q.qq.com/lite/poll_bind_result"
    const val QQ_BOT_LITE_QR_CONNECT_URL = "https://q.qq.com/qqbot/openclaw/connect.html"

    const val QQ_BOT_OPEN_PLATFORM_URL = "https://q.qq.com/qqbot/"

    const val QQ_BOT_API_CONNECT_TIMEOUT_SECONDS = 15
    const val QQ_BOT_API_READ_TIMEOUT_SECONDS = 15
    const val QQ_BOT_API_WRITE_TIMEOUT_SECONDS = 15
    const val QQ_BOT_TOKEN_REFRESH_MARGIN_MS = 60_000L

    const val QQ_BOT_WS_CONNECT_TIMEOUT_SECONDS = 15
    const val QQ_BOT_WS_READ_TIMEOUT_SECONDS = 0
    const val QQ_BOT_WS_WRITE_TIMEOUT_SECONDS = 15
    const val QQ_BOT_WS_PING_INTERVAL_SECONDS = 30

    const val WECHAT_BASE_URL = "https://ilinkai.weixin.qq.com"
    const val WECHAT_POLL_INTERVAL_MINUTES = 15L
    const val WECHAT_POLL_FLEX_MINUTES = 5L
    const val WECHAT_POLL_TIMEOUT_MS = 15_000L
    const val WECHAT_POLL_RETRY_DELAY_MS = 5_000L
    const val WECHAT_SDK_CONNECT_TIMEOUT_MS = 10_000L
    const val WECHAT_SDK_READ_TIMEOUT_MS = 15_000L
    const val WECHAT_SDK_WRITE_TIMEOUT_MS = 10_000L
    const val WECHAT_SDK_LOGIN_TIMEOUT_MS = 5 * 60 * 1000L
    const val WECHAT_SDK_CHANNEL_VERSION = "1.0.3"

    const val TTS_CONNECT_TIMEOUT_SECONDS = 15
    const val TTS_READ_TIMEOUT_SECONDS = 30

    const val XUNFEI_TTS_WEBSOCKET_URL = "wss://tts-api.xfyun.cn/v2/tts"
    const val XUNFEI_TTS_HOST = "tts-api.xfyun.cn"
    const val VOLCENGINE_TTS_URL = "https://openspeech.bytedance.com/api/v1/tts"
    const val MICROSOFT_TTS_SPEECH_HOST = "tts.speech.microsoft.com"
    const val MICROSOFT_TTS_SPEECH_PATH = "/cognitiveservices/v1"
    const val MICROSOFT_TTS_TOKEN_HOST = "api.cognitive.microsoft.com"
    const val MICROSOFT_TTS_TOKEN_PATH = "/sts/v1.0/issueToken"
    const val BAIDU_TTS_TOKEN_URL_TEMPLATE =
        "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials&client_id=%s&client_secret=%s"
    const val ALIYUN_TTS_ENDPOINT = "nls-gateway-cn-shanghai.aliyuncs.com"
    const val ALIYUN_TTS_PATH = "/stream/v1/tts"
    const val ALIYUN_TTS_TOKEN_ENDPOINT = "nls-meta.cn-shanghai.aliyuncs.com"
    const val ALIYUN_TTS_TOKEN_PATH = "/pop/v2018-05-18/tokens"

    const val OPENAI_LIGHT_CONNECT_TIMEOUT_SECONDS = 15
    const val OPENAI_LIGHT_READ_TIMEOUT_SECONDS = 45
    const val OPENAI_LIGHT_WRITE_TIMEOUT_SECONDS = 15

    const val API_CALL_TIMEOUT_MS = 30_000L
    const val VISION_API_CALL_TIMEOUT_MS = 60_000L
    const val SAFETY_CLASSIFY_TIMEOUT_MS = 30_000L
    const val MEMORY_EXTRACT_TIMEOUT_MS = 5_000L
    const val TTS_SYNTH_TIMEOUT_MS = 10_000L

    const val API_CALL_TIMEOUT_MS_IQOO = 45_000L
    const val VISION_API_CALL_TIMEOUT_MS_IQOO = 90_000L

    const val API_AVAILABILITY_TIMEOUT_MS = 1500L
    const val PIPELINE_EXECUTION_TIMEOUT_MS = 8000L
    const val CONTENT_FILTER_FULL_TIMEOUT_MS = 3000L
    const val CONTENT_FILTER_VECTOR_TIMEOUT_MS = 3000L
    const val BAYESIAN_VERIFICATION_TIMEOUT_MS = 5000L

    const val DEFAULT_RETRY_MAX_RETRIES = 2
    const val DEFAULT_RETRY_INITIAL_DELAY_MS = 300L
    const val KEY_FAILURE_COOLDOWN_MS = 5_000L

    const val STREAM_BUFFER_INTERVAL_MS = 50L
    const val STREAM_BUFFER_TEXT_LENGTH_THRESHOLD = 20
    const val STREAM_TYPING_BASE_DELAY_MS = 30L
    const val STREAM_TYPING_PER_CHAR_DELAY_MS = 15L

    const val DNS_RESOLUTION_TIMEOUT_SECONDS = 5

    const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/linruoxi666/LianYu/releases/latest"

    const val DEBUG_LOG_SERVER_URL = "http://10.188.248.127:8765/log"

    const val DEEPSEEK_API_KEYS_URL = "https://platform.deepseek.com/api_keys"

    const val DEFAULT_DNS_SERVER = "8.8.8.8"
}
