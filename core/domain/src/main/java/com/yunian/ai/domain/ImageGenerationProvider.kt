package com.yunian.ai.domain

/**
 * AI 生图能力对外接口。
 *
 * 实现位于 `core:network`（ImageGenerationService），由 app 层注册进 ServiceRegistry，
 * 供 `feature:settings`（配置页）与 `feature:chat`（聊天自动触发）共同消费，
 * 避免 feature → feature 依赖。
 */
interface ImageGenerationProvider {

    /**
     * 拉取可用生图模型列表。
     *
     * @return [ImageModelCatalog]：
     *  - `imageModels` 按关键字识别出的生图模型（优先展示）
     *  - `allModels` 该接口返回的全部模型（识别为空时供用户手动挑选兜底）
     */
    suspend fun fetchImageModels(baseUrl: String, apiKey: String): Result<ImageModelCatalog>

    /** 校验地址 / 密钥 / 模型是否可用，成功时返回可展示的说明文案。 */
    suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String>

    /**
     * 生成图片。
     *
     * @param size 形如 `1024x1024`；服务端不支持时会回退到其默认尺寸。
     * @param count 期望张数（1–4），部分模型仅支持 1 张，由实现自动收敛。
     * @return 按请求顺序落盘成功的图片；`filePath` 为持久目录绝对路径。
     */
    suspend fun generateImage(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        size: String = DEFAULT_IMAGE_SIZE,
        count: Int = 1
    ): Result<List<GeneratedImage>>

    companion object {
        const val DEFAULT_IMAGE_SIZE = "1024x1024"
    }
}

/** 生图模型目录：识别结果 + 全量模型，供 UI 分层展示。 */
data class ImageModelCatalog(
    val imageModels: List<String>,
    val allModels: List<String>
)

/**
 * 单张已落盘的生成结果。
 *
 * [filePath] 必须指向持久目录（getExternalFilesDir），不可引用会被清理的 cache 目录，
 * 否则聊天记录重启后会丢失图片。
 */
data class GeneratedImage(
    val filePath: String,
    val revisedPrompt: String? = null,
    val latencyMs: Long = 0L,
    val model: String = ""
)
