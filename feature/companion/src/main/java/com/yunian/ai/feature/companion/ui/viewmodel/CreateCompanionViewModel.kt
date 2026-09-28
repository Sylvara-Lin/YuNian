package com.yunian.ai.feature.companion.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.common.CompanionRole
import com.yunian.ai.database.DefaultCompanionSeeder
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.UserRepository
import com.yunian.ai.common.ImageUtils
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.ServiceRegistry
import androidx.core.content.edit
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.text.RegexOption

class CreateCompanionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository by lazy { ServiceRegistry.getOrThrow(CompanionRepository::class.java) }
    private val userRepository by lazy { ServiceRegistry.getOrThrow(UserRepository::class.java) }

    val selectedRole: StateFlow<CompanionRole> by lazy { userRepository.selectedRole }

    private val _existingCompanion = MutableStateFlow<CompanionEntity?>(null)
    val existingCompanion: StateFlow<CompanionEntity?> = _existingCompanion

    /** 可供绑定的 API 配置（有 Key 或 PARTNER 远程 Key） */
    private val _apiConfigs = MutableStateFlow<List<com.yunian.ai.database.model.ApiConfig>>(emptyList())
    val apiConfigs: StateFlow<List<com.yunian.ai.database.model.ApiConfig>> = _apiConfigs.asStateFlow()

    /** 当前选中的专属 API 配置 ID；null = 跟随全局 */
    private val _selectedApiConfigId = MutableStateFlow<Long?>(null)
    val selectedApiConfigId: StateFlow<Long?> = _selectedApiConfigId.asStateFlow()

    fun selectApiConfig(id: Long?) {
        _selectedApiConfigId.value = id
    }

    fun loadApiConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repo = ServiceRegistry.getOrThrow(com.yunian.ai.database.repository.ApiConfigRepository::class.java)
                val configs = repo.getAllConfigs().first()
                    .filter { it.apiKey.isNotBlank() || it.provider == com.yunian.ai.database.model.ApiProvider.PARTNER }
                withContext(Dispatchers.Main) { _apiConfigs.value = configs }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.w("CreateCompanionVM", "loadApiConfigs failed: ${e.message}")
            }
        }
    }

    private val _saveCompleted = MutableStateFlow(false)
    val saveCompleted: StateFlow<Boolean> = _saveCompleted

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val aiService by lazy { ServiceRegistry.getOrThrow(AiServiceProvider::class.java) }

    fun loadCompanion(id: Long) {
        viewModelScope.launch {
            _existingCompanion.value = repository.getCompanionById(id)
        }
    }

    fun saveCompanion(companion: CompanionEntity, isEditMode: Boolean) {
        viewModelScope.launch {
            _isSaving.value = true
            _saveError.value = null
            try {
                val avatarUrl = companion.avatarUrl
                val savedAvatar = if (avatarUrl != null && avatarUrl.startsWith("content://")) {
                    ImageUtils.saveUriToInternalStorage(getApplication(), avatarUrl)
                } else avatarUrl

                val companionToSave = companion.copy(avatarUrl = savedAvatar)
                withContext(Dispatchers.IO) {
                    if (isEditMode) {
                        repository.updateCompanion(companionToSave)
                    } else {
                        repository.insertCompanion(companionToSave)
                    }
                }
                _saveCompleted.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("CreateCompanionVM", "保存人设失败", e)
                _saveError.value = "保存失败：${e.message ?: "未知错误"}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun consumeSaveError() {
        _saveError.value = null
    }

    fun resetSaveCompleted() {
        _saveCompleted.value = false
    }

    fun deleteCompanion(companion: CompanionEntity) {
        viewModelScope.launch {

            if (companion.tags.orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .any {
                        it == DefaultCompanionSeeder.LEGACY_TAG ||
                            it == DefaultCompanionSeeder.defaultExperienceCompanionTag
                    }
            ) {
                getApplication<Application>()
                    .getSharedPreferences("default_companion", android.content.Context.MODE_PRIVATE)
                    .edit { putBoolean("deleted_by_user", true) }
            }
            repository.deleteCompanion(companion)
        }
    }

    fun bodyTypeSuggestions(role: CompanionRole): List<String> = when (role) {
        CompanionRole.GIRLFRIEND -> listOf("纤细", "匀称", "娇小", "高挑", "微胖", "元气")
        CompanionRole.BOYFRIEND -> listOf("偏瘦", "健壮", "高挑", "结实", "清瘦", "运动型")
    }

    fun professionSuggestions(role: CompanionRole): List<String> = when (role) {
        CompanionRole.GIRLFRIEND -> listOf("学生", "插画师", "幼师", "护士", "文员", "自由职业")
        CompanionRole.BOYFRIEND -> listOf("程序员", "设计师", "医生", "工程师", "教师", "自由职业")
    }

    fun personalityTags(role: CompanionRole): List<String> = when (role) {
        CompanionRole.GIRLFRIEND -> listOf(
            "温柔", "粘人", "体贴", "爱撒娇", "活泼", "害羞", "傲娇",
            "细心", "爱吃醋", "浪漫", "懂事", "软萌", "治愈", "俏皮"
        )
        CompanionRole.BOYFRIEND -> listOf(
            "可靠", "温柔", "有担当", "护短", "沉稳", "直率", "笨拙温柔",
            "细心", "爱吃醋", "爽朗", "理性", "宠溺", "安全感", "闷骚"
        )
    }

    fun generatePersonaByAi(
        name: String,
        role: CompanionRole,
        referenceCharacter: String? = null,
        bodyType: String? = null,
        profession: String? = null,
        personalityTags: List<String> = emptyList(),
        onResult: (AiPersonaDraft) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (name.isBlank()) return
        _isGenerating.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val roleLabel = when (role) {
                    CompanionRole.GIRLFRIEND -> "AI女友"
                    CompanionRole.BOYFRIEND -> "AI男友"
                }
                val pronoun = when (role) {
                    CompanionRole.GIRLFRIEND -> "她"
                    CompanionRole.BOYFRIEND -> "他"
                }
                val refPart = if (!referenceCharacter.isNullOrBlank()) {
                    "\n参考角色风格：$referenceCharacter"
                } else ""
                val bodyPart = if (!bodyType.isNullOrBlank()) {
                    "\n身材特征：$bodyType"
                } else ""
                val profPart = if (!profession.isNullOrBlank()) {
                    "\n职业背景：$profession"
                } else ""
                val tagPart = if (personalityTags.isNotEmpty()) {
                    "\n必须体现的性格标签：${personalityTags.joinToString("、")}"
                } else ""
                val prompt = """你是专业的人设/角色设定生成器。请为「${name}」生成一个${roleLabel}的完整人设，一次性给出所有基础信息与详细设定。

用户已提供的参考（非空项必须沿用，不得更改）：$bodyPart$profPart$tagPart$refPart

严格输出以下 JSON（不要 markdown 代码块标记、不要任何解释文字，JSON 必须合法可解析）：
{
  "name": "角色名，中文 2-4 字，好听有记忆点（用户已提供名字则必须沿用「${name}」）",
  "age": "年龄，纯数字字符串，如 22",
  "bodyType": "身材特征，2-4 个字",
  "profession": "职业，2-6 个字",
  "personalityTags": ["性格标签 5 个，每个 2-4 字，必须与 persona 的性格板块一致"],
  "persona": "完整人设正文，800~1500 字，必须包含六大板块且每个板块内容充实：一、性格特点（5-8 条，立体有层次，有优点也有小缺点）；二、说话风格（语气、用词习惯、口头禅、特殊表达方式，符合${roleLabel}性别特征，像真人不像 AI）；三、背景故事（成长经历、家庭环境、重要转折点，有记忆点）；四、行为习惯（日常爱好、小动作、饮食偏好、作息等细节）；五、情感模式（对待感情的态度、表达方式、敏感点）；六、互动特点（如何回应他人、生气时的表现、开心时的表现）。全程用${pronoun}指代角色，避免性别混淆。"
}

要求：所有字段认真填写；persona 内容要充实，不要一句话带过；必须完整输出，不要截断。"""

                SecureLog.d("CreateCompanionVM", "开始AI生成人设，name=$name, role=$roleLabel")
                val result = aiService.callGeneration(prompt)
                SecureLog.d("CreateCompanionVM", "AI生成结果长度=${result.length}")

                val cleaned = result
                    .removePrefix("```")
                    .removeSuffix("```")
                    .replace(Regex("^json\\s*", RegexOption.MULTILINE), "")
                    .trim()

                withContext(Dispatchers.Main) {
                    when {
                        // AiService 约定：无可用配置时返回 [TOAST] 前缀文案
                        result.startsWith("[TOAST]") ->
                            onError(result.removePrefix("[TOAST]").trim().ifBlank { "请先在「API 设置」中配置并启用可用的 API" })
                        // 空结果（调用失败被服务层吞掉）：明确报错，不能静默
                        cleaned.isBlank() ->
                            onError("生成失败：AI 未返回内容，请检查网络后重试；若持续失败请在「API 设置」检查 Key/模型")
                        else -> {
                            // 结构化 JSON 优先；解析失败降级为整段 persona（不丢内容）
                            val draft = parsePersonaDraft(cleaned)
                                ?: AiPersonaDraft(persona = cleaned.replace(Regex("^json\\s*", RegexOption.IGNORE_CASE), "").trim())
                            if (draft.persona.isBlank()) {
                                onError("生成失败：AI 返回的内容为空，请重试")
                            } else {
                                onResult(draft)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("CreateCompanionVM", "AI生成人设失败", e)
                withContext(Dispatchers.Main) {
                    onError("生成失败：${e.message ?: "未知错误"}")
                }
            } finally {
                _isGenerating.value = false
                SecureLog.d("CreateCompanionVM", "AI生成结束")
            }
        }
    }
}

/** AI 一次生成的人设草稿：基础字段 + 正文（字段可能为 null，UI 端只填空位） */
data class AiPersonaDraft(
    val name: String? = null,
    val age: String? = null,
    val bodyType: String? = null,
    val profession: String? = null,
    val personalityTags: List<String> = emptyList(),
    val persona: String = ""
)

/** 从模型返回文本中提取并解析 JSON 草稿；解析失败返回 null（调用方降级为纯文本 persona） */
private fun parsePersonaDraft(raw: String): AiPersonaDraft? {
    return try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = org.json.JSONObject(raw.substring(start, end + 1))
        val tags = mutableListOf<String>()
        obj.optJSONArray("personalityTags")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { tags.add(it) }
            }
        }
        AiPersonaDraft(
            name = obj.optString("name").takeIf { it.isNotBlank() },
            age = obj.optString("age").takeIf { it.isNotBlank() },
            bodyType = obj.optString("bodyType").takeIf { it.isNotBlank() },
            profession = obj.optString("profession").takeIf { it.isNotBlank() },
            personalityTags = tags.distinct(),
            persona = obj.optString("persona").trim()
        )
    } catch (e: Exception) {
        SecureLog.w("CreateCompanionVM", "parsePersonaDraft failed: ${e.message}")
        null
    }
}
