package com.yunian.ai.database.repository

interface EmbeddingProvider {

    suspend fun isEmbeddingSupported(): Boolean

    suspend fun getEmbeddingModelName(): String

    suspend fun embed(text: String): FloatArray?

    fun floatsToBytes(floats: FloatArray): ByteArray

    fun bytesToFloats(bytes: ByteArray): FloatArray

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float
}
