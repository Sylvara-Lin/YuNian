package com.yunian.ai.feature.skills.repository

import android.content.Context
import android.content.res.AssetManager
import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.Skill
import com.yunian.ai.domain.SkillManager
import com.yunian.ai.domain.SkillMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStreamReader

class SkillManagerImpl(
    private val context: Context
) : SkillManager {

    private val json = Json { ignoreUnknownKeys = true }
    private val loadedSkills = mutableMapOf<String, Skill>()
    private var discovered: List<SkillMetadata>? = null

    /** 外部技能目录：AI 自主安装的技能存放于此，与内置 assets 技能合并暴露 */
    private val externalDir: File
        get() = File(context.filesDir, "external_skills")

    override suspend fun discoverSkills(): List<SkillMetadata> {
        if (discovered != null) return discovered!!

        return withContext(Dispatchers.IO) {
            val metadataList = mutableListOf<SkillMetadata>()

            // 内置技能（assets/skills）
            val assets = context.assets
            val skillFiles = try {
                assets.list("skills") ?: emptyArray()
            } catch (e: Exception) {
                SecureLog.w("SkillManager", "No skills directory in assets: ${e.message}")
                emptyArray<String>()
            }

            for (fileName in skillFiles) {
                if (fileName.endsWith(".md") || fileName.endsWith(".skill")) {
                    val metadata = parseSkillFile(assets, "skills/$fileName")
                    metadata?.let { metadataList.add(it) }
                }
            }

            // 外部技能（用户私有目录）：覆盖同名内置技能
            externalDir.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".md") || it.name.endsWith(".skill")) }
                ?.forEach { file ->
                    runCatching {
                        val (metadata, _) = parseSkillContent(file.readText())
                        metadata
                    }.getOrNull()?.takeIf { it.name != "unknown" }?.let { metadata ->
                        metadataList.removeAll { it.name == metadata.name }
                        metadataList.add(metadata)
                    }
                }

            discovered = metadataList
            metadataList
        }
    }

    override suspend fun loadSkill(name: String): Skill? {

        loadedSkills[name]?.let { return it }

        return withContext(Dispatchers.IO) {
            // 外部技能优先（覆盖内置同名技能）。外部技能名经规范化落盘，
            // 故这里也规范化后再拼路径，兼容模型用原始（含中文/空格）名字调用 use_skill。
            val normalized = SkillNames.normalize(name)
            if (normalized != null) {
                val external = File(externalDir, "$normalized.md").takeIf { it.isFile }
                    ?: File(externalDir, "$normalized.skill").takeIf { it.isFile }
                if (external != null) {
                    val content = external.readText()
                    val (metadata, markdown) = parseSkillContent(content)
                    val skill = Skill(
                        metadata = metadata,
                        content = markdown,
                        sourcePath = "external/$normalized",
                    )
                    loadedSkills[name] = skill
                    return@withContext skill
                }
            }

            val assets = context.assets
            var inputStream: java.io.InputStream? = null

            val possiblePaths = listOf(
                "skills/$name.md",
                "skills/$name.skill",
                "skills/$name/index.md"
            )

            for (path in possiblePaths) {
                try {
                    inputStream = assets.open(path)
                    break
                } catch (e: Exception) {

                }
            }

            inputStream ?: return@withContext null

            val content = InputStreamReader(inputStream).readText()
            val (metadata, markdown) = parseSkillContent(content)

            if (metadata.name != name) {
                SecureLog.w("SkillManager", "Skill name mismatch: expected $name, got ${metadata.name}")
            }

            val skill = Skill(
                metadata = metadata,
                content = markdown,
                sourcePath = "assets/skills/$name"
            )
            loadedSkills[name] = skill
            skill
        }
    }

    override suspend fun searchSkills(query: String): List<SkillMetadata> {
        val allSkills = discoverSkills()
        val lowerQuery = query.lowercase()
        return allSkills.filter { skill ->
            skill.name.lowercase().contains(lowerQuery) ||
            skill.description.lowercase().contains(lowerQuery) ||
            skill.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    override fun getLoadedSkills(): List<Skill> = loadedSkills.values.toList()

    override suspend fun unloadSkill(name: String): Boolean {
        return loadedSkills.remove(name) != null
    }

    override suspend fun installSkill(name: String, markdown: String): Boolean {
        // 规范化而非拒绝：真实 SKILL.md 的 name 常含中文/空格/点号/冒号，
        // 旧的 [A-Za-z0-9_-] 硬校验会把它们直接拒绝（技能永远装不上的根因之一）。
        val safeName = SkillNames.normalize(name)
        if (safeName == null) {
            SecureLog.w("SkillManager", "Rejected skill install: unsafe name '$name'")
            return false
        }
        if (markdown.isBlank()) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                externalDir.mkdirs()
                // 关键闭环：文件名的 name 与写入内容 frontmatter 的 name 必须一致，
                // 否则 loadSkill(name) 按 externalDir/<name>.md 拼路径会找不到文件。
                val normalizedContent = SkillNames.rewriteFrontmatterName(markdown, safeName)
                File(externalDir, "$safeName.md").writeText(normalizedContent)
                discovered = null
                loadedSkills.remove(safeName)
                loadedSkills.remove(name.trim())
                SecureLog.i("SkillManager", "Skill installed: $safeName (${normalizedContent.length} chars)")
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun uninstallSkill(name: String): Boolean {
        val safeName = SkillNames.normalize(name) ?: return false
        return withContext(Dispatchers.IO) {
            val file = File(externalDir, "$safeName.md")
            val removed = file.isFile && file.delete()
            if (removed) {
                discovered = null
                loadedSkills.remove(safeName)
                loadedSkills.remove(name.trim())
                SecureLog.i("SkillManager", "Skill uninstalled: $safeName")
            }
            removed
        }
    }

    override suspend fun isExternalSkill(name: String): Boolean {
        val safeName = SkillNames.normalize(name) ?: return false
        return withContext(Dispatchers.IO) {
            File(externalDir, "$safeName.md").isFile
        }
    }

    private fun parseSkillFile(assets: AssetManager, path: String): SkillMetadata? {
        try {
            val inputStream = assets.open(path)
            val content = InputStreamReader(inputStream).readText()
            val (metadata, _) = parseSkillContent(content)
            return metadata
        } catch (e: Exception) {
            SecureLog.w("SkillManager", "Failed to parse skill file $path: ${e.message}")
            return null
        }
    }

    private fun parseSkillContent(content: String): Pair<SkillMetadata, String> {
        val lines = content.split("\n")

        var frontmatterEnd = -1
        if (lines.firstOrNull()?.trim() == "---") {
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") {
                    frontmatterEnd = i
                    break
                }
            }
        }

        val metadata: SkillMetadata
        val markdown: String

        if (frontmatterEnd > 0) {
            val frontmatterText = lines.subList(1, frontmatterEnd).joinToString("\n")
            metadata = parseYamlFrontmatter(frontmatterText)
            markdown = lines.subList(frontmatterEnd + 1, lines.size).joinToString("\n").trim()
        } else {

            metadata = SkillMetadata(
                name = "unknown",
                description = "No frontmatter found"
            )
            markdown = content
        }

        return metadata to markdown
    }

    private fun parseYamlFrontmatter(text: String): SkillMetadata {

        var name = "unknown"
        var description = ""
        var version = "1.0"
        var author = ""
        val tags = mutableListOf<String>()
        var requiresConfirmation = false
        val parameters = mutableMapOf<String, Any>()

        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val colonIndex = trimmed.indexOf(':')
            if (colonIndex <= 0) continue

            val key = trimmed.substring(0, colonIndex).trim().lowercase()
            val value = trimmed.substring(colonIndex + 1).trim()

            when (key) {
                "name" -> name = value.trim('"', '\'')
                "description" -> description = value.trim('"', '\'')
                "version" -> version = value.trim('"', '\'')
                "author" -> author = value.trim('"', '\'')
                "tags" -> {

                    val tagsText = value.trim('[', ']', ' ')
                    if (tagsText.isNotBlank()) {
                        tags.addAll(tagsText.split(',').map { it.trim().trim('"', '\'') })
                    }
                }
                "requires_confirmation" -> requiresConfirmation = value.lowercase() == "true"
                else -> parameters[key] = value.trim('"', '\'')
            }
        }

        return SkillMetadata(
            name = name,
            description = description,
            version = version,
            author = author,
            tags = tags,
            requiresConfirmation = requiresConfirmation,
            parameters = parameters
        )
    }
}
