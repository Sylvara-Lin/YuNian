package com.yunian.ai.domain

interface PlaceholderProvider {

    fun resolve(key: String): String?

    fun resolve(text: String, context: PlaceholderContext? = null): String
}

data class PlaceholderContext(

    val charName: String? = null,

    val userName: String? = null,

    val modelId: String? = null,

    val modelName: String? = null,

    val locale: String? = null,

    val timezone: String? = null,

    val currentTimeMillis: Long = System.currentTimeMillis(),

    val batteryLevel: Int? = null,

    val extras: Map<String, String> = emptyMap()
)
