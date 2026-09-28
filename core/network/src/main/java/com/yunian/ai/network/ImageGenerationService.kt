package com.yunian.ai.network

import android.content.Context
import android.util.Base64
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.domain.GeneratedImage
import com.yunian.ai.domain.ImageGenerationProvider
import com.yunian.ai.domain.ImageModelCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容协议的 AI 生图接入实现。
 *
 * 协议：
 *  - `GET  {base}/models`                拉取模型列表
 *  - `POST {base}/images/generations`    生成图片（`data[].b64_json` 优先，`data[].url` 回退）
 *
 * 该实例持有一份**独立的 OkHttpClient**，与 AiService 的聊天/流式客户端完全隔离，
 * 不共享超时与连接互斥，避免生图长耗时影响聊天链路。
 */
class ImageGenerationService(context: Context) : ImageGenerationProvider {

    private val appContext: Context = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val httpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
        builder.addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))
        RequestSecurityInterceptor.enforceTls(builder)
        builder
            .connectionPool(okhttp3.ConnectionPool(3, 5, TimeUnit.MINUTES))
            .connectTimeout(TimeoutBudgets.HTTP_CONNECT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TimeoutBudgets.HTTP_WRITE_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ---------------------------------------------------------------- 模型列表

    override suspend fun fetchImageModels(baseUrl: String, apiKey: String): Result<ImageModelCatalog> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = getModelsBody(baseUrl, apiKey)
                val all = parseModelIds(body)
                if (all.isEmpty()) throw IllegalStateException("该接口未返回任何模型")
                val imageModels = all.filter { isLikelyImageModel(it) }
                SecureLog.i(
                    TAG,
                    "fetchImageModels ok: total=${all.size} imageModels=${imageModels.size}"
                )
                ImageModelCatalog(imageModels = imageModels, allModels = all)
            }
        }

    // ---------------------------------------------------------------- 连通性测试

    override suspend fun testConnection(baseUrl: String, apiKey: String, model: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val catalog = fetchImageModels(baseUrl, apiKey).getOrThrow()
                val hits = catalog.imageModels
                when {
                    hits.isEmpty() ->
                        "接口连通，但未识别到生图模型（返回 ${catalog.allModels.size} 个模型）。" +
                            "请确认该地址支持 /images/generations，或手动填写模型名。"
                    model.isNotBlank() && hits.none { it == model } ->
                        "接口连通，发现 ${hits.size} 个生图模型，但列表中不含「$model」，请确认模型名是否正确。"
                    else ->
                        "接口连通，发现 ${hits.size} 个生图模型${if (model.isNotBlank()) "，当前选择：$model" else ""}。"
                }
            }
        }

    // ---------------------------------------------------------------- 生图

    override suspend fun generateImage(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        size: String,
        count: Int
    ): Result<List<GeneratedImage>> = withContext(Dispatchers.IO) {
        try {
            val trimmedPrompt = prompt.trim()
            if (trimmedPrompt.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("生图描述为空"))
            }
            if (model.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("未配置生图模型"))
            }

            val startedAt = System.currentTimeMillis()
            val attempts = buildRequestAttempts(model, trimmedPrompt, size, count)

            var lastError: Throwable? = null
            for (attempt in attempts) {
                val outcome = postGeneration(baseUrl, apiKey, attempt)
                val items = outcome.getOrNull()
                if (items != null) {
                    val images = saveImages(items, model, startedAt)
                    if (images.isEmpty()) {
                        return@withContext Result.failure(IllegalStateException("接口未返回图片数据"))
                    }
                    return@withContext Result.success(images)
                }
                lastError = outcome.exceptionOrNull()
                // 仅在“参数不被支持”类错误上回退重试，其余直接失败
                if (!isRecoverableParamError(lastError)) break
                SecureLog.w(TAG, "generateImage retry with fallback params: ${lastError?.message}")
            }
            Result.failure(lastError ?: IllegalStateException("生图请求失败"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.e(TAG, "generateImage failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private suspend fun getModelsBody(baseUrl: String, apiKey: String): String {
        var last: Throwable? = null
        for (base in baseCandidates(baseUrl)) {
            val request = Request.Builder()
                .url("$base/models")
                .addHeader("Accept", "application/json")
                .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${apiKey.trim()}") }
                .get()
                .build()
            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: Exception) {
                last = e
                continue
            }
            response.use {
                val body = it.body?.string().orEmpty()
                if (it.code == 404) {
                    last = IllegalStateException("HTTP 404：$base/models 不存在")
                } else {
                    if (!it.isSuccessful) {
                        throw IllegalStateException(extractErrorMessage(body) ?: "HTTP ${it.code}")
                    }
                    ensureNotHtml(body)
                    return body
                }
            }
        }
        throw last ?: IllegalStateException("无法连接模型列表接口")
    }

    private suspend fun postGeneration(
        baseUrl: String,
        apiKey: String,
        attempt: GenerationAttempt
    ): Result<List<ImageGenItem>> {
        val payload = GenerationRequest(
            model = attempt.model,
            prompt = attempt.prompt,
            n = attempt.count,
            size = attempt.size,
            responseFormat = attempt.responseFormat
        )
        val bodyJson = json.encodeToString(GenerationRequest.serializer(), payload)
        var last: Throwable? = null

        for (base in baseCandidates(baseUrl)) {
            val request = Request.Builder()
                .url("$base/images/generations")
                .addHeader("Accept", "application/json")
                .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${apiKey.trim()}") }
                .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: Exception) {
                last = e
                continue
            }
            response.use {
                val body = it.body?.string().orEmpty()
                if (it.code == 404) {
                    last = IllegalStateException("HTTP 404：$base/images/generations 不存在")
                } else {
                    if (!it.isSuccessful) {
                        throw IllegalStateException(extractErrorMessage(body) ?: "HTTP ${it.code}")
                    }
                    return Result.success(parseImageItems(body))
                }
            }
        }
        return Result.failure(last ?: IllegalStateException("无法连接生图接口"))
    }

    /**
     * 构造请求参数回退序列。
     * 部分模型不支持 `n > 1` 或 `response_format`，遇到参数类错误时逐级降级。
     */
    private fun buildRequestAttempts(
        model: String,
        prompt: String,
        size: String,
        count: Int
    ): List<GenerationAttempt> {
        val safeCount = count.coerceIn(1, MAX_IMAGES_PER_REQUEST)
        val safeSize = size.trim().ifBlank { ImageGenerationProvider.DEFAULT_IMAGE_SIZE }
        val primary = GenerationAttempt(model, prompt, safeCount, safeSize, "b64_json")
        return listOf(
            primary,
            primary.copy(responseFormat = null),
            primary.copy(responseFormat = null, count = 1),
            primary.copy(responseFormat = null, count = 1, size = null)
        ).distinct()
    }

    private fun isRecoverableParamError(error: Throwable?): Boolean {
        val message = error?.message?.lowercase() ?: return false
        return message.contains("response_format") ||
            message.contains("unsupported") ||
            message.contains("invalid") ||
            message.contains("n must") ||
            message.contains("size") ||
            message.contains("参数")
    }

    private suspend fun saveImages(
        items: List<ImageGenItem>,
        model: String,
        startedAt: Long
    ): List<GeneratedImage> {
        val result = mutableListOf<GeneratedImage>()
        val elapsed = System.currentTimeMillis() - startedAt
        items.forEachIndexed { index, item ->
            val bytes = when {
                !item.b64Json.isNullOrBlank() -> decodeBase64(item.b64Json)
                !item.url.isNullOrBlank() -> downloadImageBytes(item.url)
                else -> null
            } ?: return@forEachIndexed
            val file = writeToDisk(bytes, index)
            result += GeneratedImage(
                filePath = file.absolutePath,
                revisedPrompt = item.revisedPrompt,
                latencyMs = elapsed,
                model = model
            )
        }
        return result
    }

    private fun decodeBase64(raw: String): ByteArray? = runCatching {
        val payload = raw.substringAfter("base64,", raw)
        Base64.decode(payload, Base64.DEFAULT)
    }.getOrNull()

    private fun downloadImageBytes(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).addHeader("Accept", "image/*").get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
        }
    }.getOrNull()

    /**
     * 写入持久目录。必须使用 getExternalFilesDir，不能落 cache——
     * 聊天记录会长期引用该路径。
     */
    private fun writeToDisk(bytes: ByteArray, index: Int): File {
        val dir = appContext.getExternalFilesDir(GENERATED_IMAGE_DIR)
            ?: File(appContext.filesDir, GENERATED_IMAGE_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "gen_${System.currentTimeMillis()}_$index.png")
        file.writeBytes(bytes)
        return file
    }

    private fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{")) return body.take(200)
        return runCatching {
            json.decodeFromString<ImageGenResponse>(body).error?.message
                ?: json.decodeFromString<ModelsListResponse>(body).error?.message
                ?: json.decodeFromString<ChatCompletionResponse>(body).error?.message
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Base URL 候选集：用户在配置页可能漏填 `/v1`，
     * 命中 404 时自动尝试补 `/v1`，避免“明明能用却报 404”。
     */
    private fun baseCandidates(raw: String): List<String> {
        val normalized = normalizeBaseUrl(raw)
        if (normalized.isEmpty()) throw IllegalArgumentException("未配置 API 地址")
        val candidates = mutableListOf(normalized)
        if (!normalized.contains("/v1")) candidates += "$normalized/v1"
        return candidates.distinct()
    }

    /**
     * 非流式调用要求响应是纯 JSON；部分中转站会返回 HTML 错误页，提前判定并给出可操作文案。
     */
    private fun ensureNotHtml(body: String) {
        if (body.trimStart().startsWith("<")) {
            throw IllegalStateException("该地址返回网页而非 API 响应，请检查 Base URL")
        }
    }

    /**
     * 解析生图响应，同时兼容流式（SSE）返回。
     *
     * 部分中转站会无视 `stream:false` 直接返回 `data: {...}` 分片；
     * 而 gpt-image 这类模型的流式响应还会把 base64 拆成多个分片（`partial_image_index`），
     * 因此需要按 index 累积后再拼成完整图片。
     */
    private fun parseImageItems(body: String): List<ImageGenItem> {
        ensureNotHtml(body)
        if (!body.trimStart().startsWith("data:")) {
            val response = json.decodeFromString<ImageGenResponse>(body)
            response.error?.message?.let { throw IllegalStateException(it) }
            return response.data.orEmpty()
                .filter { !it.b64Json.isNullOrBlank() || !it.url.isNullOrBlank() }
        }

        val complete = mutableListOf<ImageGenItem>()
        val partialBuffers = LinkedHashMap<Int, StringBuilder>()
        val partialMeta = LinkedHashMap<Int, ImageGenItem>()

        ssePayloads(body).forEach { payload ->
            val response = runCatching { json.decodeFromString<ImageGenResponse>(payload) }.getOrNull()
                ?: return@forEach
            response.error?.message?.let { throw IllegalStateException(it) }
            response.data.orEmpty().forEach { item ->
                val index = item.partialIndex
                if (index != null) {
                    partialBuffers.getOrPut(index) { StringBuilder() }.append(item.b64Json.orEmpty())
                    partialMeta[index] = item
                } else if (!item.b64Json.isNullOrBlank() || !item.url.isNullOrBlank()) {
                    complete += item
                }
            }
        }

        partialBuffers.forEach { (index, buffer) ->
            if (buffer.isNotEmpty()) {
                val meta = partialMeta[index]
                complete += ImageGenItem(
                    b64Json = buffer.toString(),
                    url = meta?.url,
                    revisedPrompt = meta?.revisedPrompt,
                )
            }
        }
        return complete
    }

    /** 解析模型列表响应，兼容 `data:` 流式返回。 */
    private fun parseModelIds(body: String): List<String> {
        ensureNotHtml(body)
        val payloads = if (body.trimStart().startsWith("data:")) ssePayloads(body) else listOf(body)
        val ids = mutableListOf<String>()
        payloads.forEach { payload ->
            val response = runCatching { json.decodeFromString<ModelsListResponse>(payload) }.getOrNull()
                ?: return@forEach
            response.error?.message?.let { throw IllegalStateException(it) }
            response.data.orEmpty()
                .mapNotNull { it.id?.trim() }
                .filter { it.isNotEmpty() }
                .let(ids::addAll)
        }
        return ids.distinct()
    }

    /** 拆出 SSE 的 data: 载荷（忽略注释/心跳行与 [DONE]）。 */
    private fun ssePayloads(body: String): List<String> =
        body.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> null
                    trimmed.startsWith(":") -> null
                    trimmed.startsWith("data:", ignoreCase = true) ->
                        trimmed.substring(5).trim()
                            .takeIf { it.isNotEmpty() && !it.equals("[DONE]", ignoreCase = true) }
                    else -> null
                }
            }
            .toList()

    private fun normalizeBaseUrl(raw: String): String {        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return url
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            url = "https://$url"
        }
        for (suffix in ENDPOINT_SUFFIXES) {
            if (url.endsWith(suffix, ignoreCase = true)) {
                url = url.dropLast(suffix.length).trimEnd('/')
                break
            }
        }
        return url
    }

    private fun isLikelyImageModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return IMAGE_MODEL_HINTS.any { id.contains(it) } &&
            CHAT_MODEL_EXCLUDES.none { id.contains(it) }
    }

    /** 仅对自家域名签名，与 AiService 的判定保持一致。 */
    private fun shouldSignRequest(request: Request): Boolean {
        val host = request.url.host.lowercase()
        return request.header("X-LianYu-Session")?.isNotBlank() == true ||
            host == "api.lianyu.ai" || host.endsWith(".lianyu.ai")
    }

    // ---------------------------------------------------------------- DTO

    @Serializable
    private data class GenerationRequest(
        val model: String,
        val prompt: String,
        val n: Int? = null,
        val size: String? = null,
        @SerialName("response_format") val responseFormat: String? = null
    )

    @Serializable
    private data class ImageGenResponse(
        val data: List<ImageGenItem>? = null,
        val error: ErrorDetail? = null,
        val created: Long? = null
    )

    @Serializable
    private data class ImageGenItem(
        @SerialName("b64_json") val b64Json: String? = null,
        val url: String? = null,
        @SerialName("revised_prompt") val revisedPrompt: String? = null,
        /** 流式返回时的分片下标；非 null 表示该 b64 只是一段，需要按 index 累积 */
        @SerialName("partial_image_index") val partialIndex: Int? = null
    )

    private data class GenerationAttempt(
        val model: String,
        val prompt: String,
        val count: Int,
        val size: String?,
        val responseFormat: String?
    )

    private companion object {
        const val TAG = "ImageGen"
        const val GENERATED_IMAGE_DIR = "generated_images"
        const val MAX_IMAGES_PER_REQUEST = 4
        const val READ_TIMEOUT_SECONDS = 180L
        const val CALL_TIMEOUT_SECONDS = 240L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val ENDPOINT_SUFFIXES = listOf("/images/generations", "/models", "/chat/completions")

        val IMAGE_MODEL_HINTS = listOf(
            "flux", "dall-e", "dalle", "sd-", "sd3", "sd2", "sdxl", "stable-diffusion",
            "wanx", "wan2", "cogview", "qwen-image", "imagen", "ideogram", "seedream",
            "kolors", "hidream", "hunyuan", "irag", "midjourney", "seededit", "image"
        )

        /** 明显是聊天/向量模型的 id，避免被 "image" 关键字误纳。 */
        val CHAT_MODEL_EXCLUDES = listOf(
            "vl-", "-vl", "vision", "embedding", "embed", "rerank", "tts", "asr",
            "whisper", "chat", "instruct", "coder", "reasoner"
        )
    }
}
