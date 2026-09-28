package com.yunian.ai.agent.plugin

import com.yunian.ai.domain.plugin.BlueprintPluginRef
import com.yunian.ai.domain.plugin.PluginBlueprint
import org.json.JSONArray
import org.json.JSONObject

/**
 * 蓝图 JSON 解析器（对应 cordis.yml 语义，JSON 形态）。
 *
 * 输入格式：
 * ```json
 * {
 *   "id": "default",
 *   "name": "默认蓝图",
 *   "plugins": [ { "id": "coffee.luckin", "enabled": true, "config": {...} } ],
 *   "patches": [ { "id": "coffee.luckin", "enabled": true, "config": {...} } ],
 *   "inserts": [ { "id": "skill.xxx", "config": {...} } ]
 * }
 * ```
 *
 * 语义：基准 plugins → patches 按 id 覆盖（enabled 覆盖 + config **深合并**，cordis patch）
 * → inserts 追加到尾部（cordis insert）。解析失败抛 [IllegalArgumentException]。
 */
object PluginBlueprintParser {

    fun parse(json: String): PluginBlueprint {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("蓝图 JSON 非法: ${e.message}")
        }

        val id = root.optString("id").takeIf { it.isNotBlank() } ?: "unnamed"
        val name = root.optString("name").takeIf { it.isNotBlank() } ?: id

        // 1. 基准 plugins
        val base = parseRefs(root.optJSONArray("plugins"))
        val byId = LinkedHashMap<String, BlueprintPluginRef>()
        base.forEach { byId[it.id] = it }

        // 2. patches：按 id 覆盖 enabled + 深合并 config
        parseRefs(root.optJSONArray("patches")).forEach { patch ->
            val existing = byId[patch.id]
            byId[patch.id] = existing?.let { old ->
                BlueprintPluginRef(
                    id = old.id,
                    enabled = patch.enabled, // patch 显式携带 enabled（默认 true 视为覆盖）
                    configJson = deepMergeJson(old.configJson, patch.configJson),
                )
            } ?: patch // patch 引用不存在的插件：按 insert 语义追加
        }

        // 3. inserts：追加到尾部
        parseRefs(root.optJSONArray("inserts")).forEach { ins ->
            val existing = byId[ins.id]
            byId[ins.id] = existing?.let { old ->
                BlueprintPluginRef(old.id, ins.enabled, deepMergeJson(old.configJson, ins.configJson))
            } ?: ins
        }

        return PluginBlueprint(id, name, byId.values.toList())
    }

    private fun parseRefs(arr: JSONArray?): List<BlueprintPluginRef> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("plugins[$i]: 必须是对象")
            val pid = o.optString("id").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("plugins[$i]: 缺少 id")
            val config = o.optJSONObject("config")
            BlueprintPluginRef(
                id = pid,
                enabled = o.optBoolean("enabled", true),
                configJson = config?.toString(),
            )
        }
    }

    /** a 与 b 的深合并（均为 JSON 对象时递归；b 覆盖 a；null 一侧返回另一侧）。 */
    internal fun deepMergeJson(a: String?, b: String?): String? {
        if (a == null) return b
        if (b == null) return a
        val ao = runCatching { JSONObject(a) }.getOrNull()
        val bo = runCatching { JSONObject(b) }.getOrNull()
        if (ao == null || bo == null) return b
        val merged = JSONObject(ao.toString())
        bo.keys().forEach { key ->
            val bv = bo.get(key)
            val av = merged.opt(key)
            merged.put(key, if (av is JSONObject && bv is JSONObject) {
                JSONObject(deepMergeJson(av.toString(), bv.toString()) ?: "{}")
            } else {
                bv
            })
        }
        return merged.toString()
    }
}
