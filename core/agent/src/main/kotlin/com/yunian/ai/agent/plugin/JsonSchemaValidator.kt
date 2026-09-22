package com.yunian.ai.agent.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简 JSON Schema 校验器（插件配置 fail-closed 用）。
 *
 * 支持子集：`type`（string/number/integer/boolean/object/array）、`required`、
 * `properties`（递归）、`items`、`enum`、`minLength/maxLength`、`minItems/maxItems`。
 * 未知关键字忽略（宽松）；类型不匹配 / required 缺失 / enum 越界即报错。
 */
internal object JsonSchemaValidator {

    /** 校验 instance 是否符合 schema，返回错误列表（空 = 通过）。 */
    fun validate(instance: Any?, schema: String): List<String> {
        val schemaJson = try {
            JSONObject(schema)
        } catch (e: Exception) {
            return listOf("配置 Schema 非法 JSON: ${e.message}")
        }
        return validateNode(instance, schemaJson, "$")
    }

    private fun validateNode(instance: Any?, schema: JSONObject, path: String): List<String> {
        val errors = mutableListOf<String>()

        // type 检查
        schema.optString("type").takeIf { it.isNotBlank() }?.let { expected ->
            if (!typeMatches(instance, expected)) {
                errors += "$path: 期望类型 $expected，实际 ${describe(instance)}"
                return errors // 类型不符，后续子规则无意义
            }
        }

        // enum 检查
        if (schema.has("enum")) {
            val allowed = schema.getJSONArray("enum")
            val hit = (0 until allowed.length()).any { i -> jsonEquals(allowed.get(i), instance) }
            if (!hit) errors += "$path: 值不在允许的 enum 范围内"
        }

        // 长度/数量约束
        when (instance) {
            is String -> {
                schema.optInt("minLength", -1).takeIf { it >= 0 }?.let { if (instance.length < it) errors += "$path: 长度不足 ${it}" }
                schema.optInt("maxLength", -1).takeIf { it >= 0 }?.let { if (instance.length > it) errors += "$path: 长度超过 ${it}" }
            }
            is JSONArray -> {
                schema.optInt("minItems", -1).takeIf { it >= 0 }?.let { if (instance.length() < it) errors += "$path: 数组少于 ${it} 项" }
                schema.optInt("maxItems", -1).takeIf { it >= 0 }?.let { if (instance.length() > it) errors += "$path: 数组超过 ${it} 项" }
                schema.optJSONObject("items")?.let { itemSchema ->
                    for (i in 0 until instance.length()) {
                        errors += validateNode(instance.get(i), itemSchema, "$path[$i]")
                    }
                }
            }
            is JSONObject -> {
                schema.optJSONArray("required")?.let { req ->
                    for (i in 0 until req.length()) {
                        val key = req.optString(i)
                        if (!instance.has(key) || instance.isNull(key)) errors += "$path: 缺少必填字段 $key"
                    }
                }
                schema.optJSONObject("properties")?.let { props ->
                    props.keys().forEach { key ->
                        if (instance.has(key) && !instance.isNull(key)) {
                            errors += validateNode(instance.get(key), props.getJSONObject(key), "$path.$key")
                        }
                    }
                }
            }
        }
        return errors
    }

    private fun typeMatches(instance: Any?, expected: String): Boolean = when (expected) {
        "object" -> instance is JSONObject
        "array" -> instance is JSONArray
        "string" -> instance is String
        "number" -> instance is Number
        "integer" -> instance is Int || instance is Long
        "boolean" -> instance is Boolean
        "null" -> instance == null || instance === JSONObject.NULL
        else -> true
    }

    private fun describe(instance: Any?): String = when (instance) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        else -> instance.javaClass.simpleName
    }

    private fun jsonEquals(a: Any?, b: Any?): Boolean {
        if (a == null || a === JSONObject.NULL) return b == null || b === JSONObject.NULL
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a == b
    }
}
