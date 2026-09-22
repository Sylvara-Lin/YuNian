package com.yunian.ai.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class LoadTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val apiKey = "sk-" + "a".repeat(20)
    private val baseUrl = "https://suflow.cloud/v1"

    @org.junit.Ignore("外部网络压测（suflow.cloud 38 并发），需真机/网络演练时手动去掉本注解单跑，不作为 unitTest 常规断言")
    @org.junit.Test
    fun testFetchModelsWith38Users() = runBlocking {
        val userCount = 38
        val results = mutableListOf<Result<String>>()
        val lock = Any()

        println("========================================")
        println("开始负载测试: $userCount 个并发用户")
        println("目标: $baseUrl/models")
        println("时间: ${java.time.LocalDateTime.now()}")
        println("========================================")

        val totalTime = measureTimeMillis {
            val jobs = (1..userCount).map { userId ->
                async(Dispatchers.IO) {
                    val userTime = measureTimeMillis {
                        val result = runCatching {
                            fetchModels()
                        }
                        synchronized(lock) {
                            results.add(result)
                        }
                        val status = if (result.isSuccess) "✓ 成功" else "✗ 失败"
                        val modelCount = result.getOrNull()?.lines()?.size ?: 0
                        println("用户 #$userId: $status (${result.getOrNull()?.length ?: 0} chars, $modelCount models)")
                    }
                }
            }
            jobs.awaitAll()
        }

        val successCount = results.count { it.isSuccess }
        val failCount = results.count { it.isFailure }
        val avgTime = totalTime / userCount

        println("========================================")
        println("测试完成!")
        println("总耗时: ${totalTime}ms")
        println("成功: $successCount / $userCount")
        println("失败: $failCount / $userCount")
        println("平均每用户: ${avgTime}ms")
        println("========================================")

        results.filter { it.isFailure }.forEachIndexed { index, result ->
            println("失败 #${index + 1}: ${result.exceptionOrNull()?.message}")
        }

        assert(successCount >= userCount * 0.8) {
            "成功率过低: $successCount/$userCount (${successCount * 100 / userCount}%)"
        }
    }

    private fun fetchModels(): String {
        val request = Request.Builder()
            .url("$baseUrl/models")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            return response.body?.string() ?: throw Exception("Empty response")
        }
    }
}
