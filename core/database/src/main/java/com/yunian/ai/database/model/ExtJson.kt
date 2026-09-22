package com.yunian.ai.database.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

@PublishedApi
internal val extJsonParser = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

inline fun <reified T> String.readExt(key: String): T? {
    if (isBlank() || this == "{}") return null
    return runCatching {
        val obj = extJsonParser.decodeFromString<JsonObject>(this)
        val element = obj[key] ?: return null
        extJsonParser.decodeFromJsonElement(serializer<T>(), element)
    }.getOrNull()
}

inline fun <reified T> String.writeExt(key: String, value: T): String {
    val obj = if (isBlank() || this == "{}") {
        JsonObject(emptyMap())
    } else {
        runCatching { extJsonParser.decodeFromString<JsonObject>(this) }
            .getOrDefault(JsonObject(emptyMap()))
    }
    val newElement = extJsonParser.encodeToJsonElement(serializer(), value)
    val mutable = obj.toMutableMap()
    mutable[key] = newElement
    return extJsonParser.encodeToString(JsonObject(mutable))
}

fun String.removeExt(key: String): String {
    if (isBlank() || this == "{}") return this
    return runCatching {
        val obj = extJsonParser.decodeFromString<JsonObject>(this)
        val mutable = obj.toMutableMap()
        mutable.remove(key)
        extJsonParser.encodeToString(JsonObject(mutable))
    }.getOrDefault(this)
}

fun String.hasExt(key: String): Boolean {
    if (isBlank() || this == "{}") return false
    return runCatching {
        val obj = extJsonParser.decodeFromString<JsonObject>(this)
        obj.containsKey(key)
    }.getOrDefault(false)
}
