package com.yunian.ai.feature.skills.repository

import com.yunian.ai.agent.uniffi.SkillStore
import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.SkillManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rust `SkillStore` 回调适配器 —— 把本地技能体系（`SkillManager`：assets/skills +
 * filesDir/external_skills + 技能市场）桥接到 Rust `SkillSelector`。
 *
 * ## 为什么需要它（Q6 双体系收敛）
 *
 * 迁移前本地与 master 各有一套技能体系：
 *
 * | | 本地（迁移前） | master / Rust |
 * |---|---|---|
 * | 存储 | `assets/skills` + `external_skills`（纯文件） | Room `agent_skills` 索引 + 文件正文 |
 * | 工具 | `use_skill`（走 `ToolRegistry`，受 `useTools` 门控） | `load_skill`（`AgentToolHost` 特判，无条件可用） |
 *
 * 两者并存时模型可能**同时调用** `use_skill` 与 `load_skill` → 重复加载同一技能正文 +
 * 提示词污染 + token 浪费（见计划文档 R22）。本适配器让 Rust `SkillSelector` 直接
 * 在本地资产上做渐进式披露（L1 目录 / L2 按需），从而**退役 `use_skill`**、统一为 `load_skill`。
 *
 * ## 语义映射
 *
 * - `skill_id` = 规范化后的技能名（[SkillNames.normalize]）——因为本地是按名字落盘/查表的，
 *   而 Rust 只会把 meta 里的 `skill_id` 原样回传给 `getSkillContent`，两者必须一致。
 * - `category` = `CUSTOM`（本地技能体系无分类概念；不发明额外分类，避免 UI/Rust 语义分叉）。
 * - `tools` = 空数组（本地技能无工具依赖声明 → 恒显示，不参与汇合点过滤）。
 * - `enabled` = 恒 `true`（本地技能没有禁用位；卸载即删除）。
 * - `companion_id` = `null`（本地技能是全局的）。
 *
 * ## 与 [com.yunian.ai.agent.skill.SkillStoreImpl] 的关系
 *
 * 后者是 master 的「Room 索引 + 文件正文」实现，二者都实现同一个 UniFFI 回调接口。
 * 迁移期保留两者：`SkillStoreAdapter` 负责本地资产/市场技能，`SkillStoreImpl` 负责
 * 内置种（`builtin_chat_tool_protocol`）等 Agent 原生技能。由 [com.yunian.ai.agent.AgentFacade]
 * 决定注入哪一个。
 */
class SkillStoreAdapter(
    private val skillManager: SkillManager,
) : SkillStore {

    // ── 读 ──

    /** 返回索引 JSON 数组 `[SkillMeta, ...]`（仅启用项；Rust 侧再做召回/评分）。 */
    override fun listSkills(companionId: Long?): String = runBlockingOnIo {
        metaJsonArray(
            skillManager.discoverSkills().map { meta ->
                JSONObject().apply {
                    put("skill_id", SkillNames.normalize(meta.name) ?: meta.name)
                    put("name", meta.name)
                    put("description", meta.description)
                    put("category", DEFAULT_CATEGORY)
                    put("tags", meta.tags.joinToString(","))
                    put("tools", JSONArray())
                    put("enabled", true)
                    put("companion_id", JSONObject.NULL)
                    put("version", 1)
                    put("updated_at", 0L)
                }
            }
        )
    }

    /** 读正文：委托本地 [SkillManager.loadSkill]（含 frontmatter 剥离），不存在返回 null。 */
    override fun getSkillContent(skillId: String): String? = runBlockingOnIo {
        val skill = skillManager.loadSkill(skillId) ?: return@runBlockingOnIo null
        skill.content.takeIf { it.isNotBlank() }
    }

    /** 关键字搜索本地技能索引。 */
    override fun searchSkills(query: String, limit: UInt): String = runBlockingOnIo {
        // 先把 UInt 夹进 Int 区间再收敛下界：直接 `limit.toInt()` 在 limit > Int.MAX_VALUE
        // 时会溢出成负数，再 `coerceAtLeast(1)` 就把「上限很大」误判成「只要 1 条」。
        val capped = limit.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt().coerceAtLeast(1)
        metaJsonArray(
            skillManager.searchSkills(query).take(capped).map { meta ->
                JSONObject().apply {
                    put("skill_id", SkillNames.normalize(meta.name) ?: meta.name)
                    put("name", meta.name)
                    put("description", meta.description)
                    put("category", DEFAULT_CATEGORY)
                    put("tags", meta.tags.joinToString(","))
                    put("tools", JSONArray())
                    put("enabled", true)
                    put("companion_id", JSONObject.NULL)
                    put("version", 1)
                    put("updated_at", 0L)
                }
            }
        )
    }

    // ── 写 ──

    /**
     * 保存技能：写 `filesDir/external_skills/<name>.md`。
     *
     * 注意：本次迁移中 Rust 侧**不会调用**该方法（技能安装仍走本地 `SkillManagerImpl.installSkill`
     * 与技能市场），此处仅为接口完备性实现。成功返回 1（本地无版本号概念），失败返回 -1。
     */
    override fun saveSkill(metaJson: String, content: String): Int {
        val name = runCatching { JSONObject(metaJson).optString("name") }.getOrNull()
        val safeName = SkillNames.normalize(name)
        if (safeName == null || content.isBlank()) return -1
        return runBlockingOnIo {
            runCatching { skillManager.installSkill(safeName, content) }
                .onFailure { SecureLog.w(TAG, "saveSkill failed: ${it.message}") }
                .getOrDefault(false)
        }.let { if (it) 1 else -1 }
    }

    /** 删除技能（仅外部安装的技能可删；内置技能返回 false）。 */
    override fun deleteSkill(skillId: String): Boolean = runBlockingOnIo {
        runCatching { skillManager.uninstallSkill(skillId) }
            .onFailure { SecureLog.w(TAG, "deleteSkill failed: ${it.message}") }
            .getOrDefault(false)
    }

    // ── 内部 ──

    private fun metaJsonArray(items: List<JSONObject>): String =
        JSONArray().apply { items.forEach { put(it) } }.toString()

    /** 回调线程（Rust 侧）非主线程，IO 用 Dispatchers.IO 兜底。 */
    private inline fun <T> runBlockingOnIo(crossinline block: suspend () -> T): T =
        runBlocking { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val TAG = "SkillStoreAdapter"

        /** 本地技能体系无分类概念 → 统一 CUSTOM（与 `SkillCategory.CUSTOM` 同名）。 */
        const val DEFAULT_CATEGORY = "CUSTOM"
    }
}
