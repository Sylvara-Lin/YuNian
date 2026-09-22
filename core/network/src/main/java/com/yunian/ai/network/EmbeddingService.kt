package com.yunian.ai.network

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

class EmbeddingService(private val context: Context) : EmbeddingProvider {

    companion object {
        private const val TAG = "EmbeddingService"

        const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small"

        private val EMBEDDING_CAPABLE_PROVIDERS = setOf(
            ApiProvider.OPENAI,
            ApiProvider.OPENROUTER,
            ApiProvider.SILICONFLOW,
            ApiProvider.DASHSCOPE,
            ApiProvider.ZHIPU,
            ApiProvider.GEMINI,
            ApiProvider.CUSTOM
        )

        private val PROVIDER_EMBEDDING_MODELS = mapOf(
            ApiProvider.OPENAI to "text-embedding-3-small",
            ApiProvider.OPENROUTER to "openai/text-embedding-3-small",
            ApiProvider.SILICONFLOW to "BAAI/bge-m3",
            ApiProvider.DASHSCOPE to "text-embedding-v3",
            ApiProvider.ZHIPU to "embedding-3",
            ApiProvider.GEMINI to "text-embedding-004"
        )

        fun floatsToBytes(floats: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(floats.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(floats)
            return buffer.array()
        }

        fun bytesToFloats(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            buffer.asFloatBuffer().get(floats)
            return floats
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dotProduct = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dotProduct += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
            return if (denominator == 0.0) 0f else (dotProduct / denominator).toFloat()
        }
    }

    private val apiConfigRepository: ApiConfigRepository
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun floatsToBytes(floats: FloatArray): ByteArray = Companion.floatsToBytes(floats)

    override fun bytesToFloats(bytes: ByteArray): FloatArray = Companion.bytesToFloats(bytes)

    override fun cosineSimilarity(a: FloatArray, b: FloatArray): Float = Companion.cosineSimilarity(a, b)

    private val embeddingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    init {
        val database = AppDatabase.getDatabase(context.applicationContext)
        apiConfigRepository = ApiConfigRepository(database.apiConfigDao())
    }

    override suspend fun isEmbeddingSupported(): Boolean {
        val config = getConfig() ?: return false
        return EMBEDDING_CAPABLE_PROVIDERS.contains(config.provider)
    }

    override suspend fun getEmbeddingModelName(): String {
        val config = getConfig() ?: return DEFAULT_EMBEDDING_MODEL
        val recommended = PROVIDER_EMBEDDING_MODELS[config.provider]
        return recommended ?: DEFAULT_EMBEDDING_MODEL
    }

    override suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null

        val config = getConfig() ?: return@withContext null
        if (!EMBEDDING_CAPABLE_PROVIDERS.contains(config.provider)) return@withContext null

        val model = PROVIDER_EMBEDDING_MODELS[config.provider] ?: DEFAULT_EMBEDDING_MODEL
        val keys = config.getAllApiKeys()
        if (keys.isEmpty()) return@withContext null

        val baseUrl = config.baseUrl.trimEnd('/')
        val embeddingUrl = when (config.provider) {
            ApiProvider.GEMINI -> "$baseUrl/openai/embeddings"
            else -> "$baseUrl/embeddings"
        }

        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", JsonPrimitive(text))
        }.toString()

        val request = Request.Builder()
            .url(embeddingUrl)
            .header("Authorization", "Bearer ${keys.first()}")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            embeddingClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SecureLog.w(TAG, "Embedding API failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val jsonBody = json.parseToJsonElement(body).jsonObject
                val data = jsonBody["data"]?.jsonArray?.firstOrNull()
                    ?: return@withContext null
                val embedding = data.jsonObject["embedding"]?.jsonArray
                    ?: return@withContext null

                val floats = FloatArray(embedding.size)
                for (i in embedding.indices) {
                    floats[i] = embedding[i].jsonPrimitive.content.toFloat()
                }
                return@withContext floats
            }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Embedding generation failed: ${e.message}")
            return@withContext null
        }
    }

    suspend fun embedBatch(texts: List<String>): List<FloatArray?> = withContext(Dispatchers.IO) {
        texts.map { embed(it) }
    }

    private suspend fun getConfig(): ApiConfig? {
        return apiConfigRepository.getActiveEnabledConfig()
    }
}
