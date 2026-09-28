package com.yunian.ai.feature.qqbot.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface QQBotLiteBindApi {

    @POST("lite/create_bind_task")
    @Headers("Accept: application/json")
    suspend fun createBindTask(@Body request: CreateBindTaskRequest): Response<CreateBindTaskResponse>

    @POST("lite/poll_bind_result")
    @Headers("Accept: application/json")
    suspend fun pollBindResult(@Body request: PollBindResultRequest): Response<PollBindResultResponse>
}

@Serializable
data class CreateBindTaskRequest(
    val key: String
)

@Serializable
data class CreateBindTaskResponse(
    val retcode: Int = 0,
    val msg: String? = null,
    val data: CreateBindTaskData? = null
)

@Serializable
data class CreateBindTaskData(
    @SerialName("task_id") val taskId: String
)

@Serializable
data class PollBindResultRequest(
    @SerialName("task_id") val taskId: String
)

@Serializable
data class PollBindResultResponse(
    val retcode: Int = 0,
    val msg: String? = null,
    val data: PollBindResultData? = null
)

@Serializable
data class PollBindResultData(
    val status: Int = 0,
    @SerialName("bot_appid") val botAppId: String? = null,
    @SerialName("bot_encrypt_secret") val botEncryptSecret: String? = null,
    @SerialName("user_openid") val userOpenid: String? = null
)

enum class BindStatus(val value: Int) {
    NONE(0),
    PENDING(1),
    COMPLETED(2),
    EXPIRED(3)
}
