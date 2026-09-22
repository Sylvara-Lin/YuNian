package com.yunian.ai.feature.settings.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.domain.ImageGenerationProvider
import com.yunian.ai.domain.ImageModelCatalog
import com.yunian.ai.domain.ServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 生图设置页的状态与持久化。
 *
 * 刻意独立于 SettingsViewModel（后者体量大且为并行开发热点），
 * 只依赖 AppSettingsStore + ImageGenerationProvider，保证互不干扰。
 */
class ImageGenSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appSettingsStore = AppSettingsStore(application)

    private val imageGenProvider: ImageGenerationProvider by lazy {
        ServiceRegistry.getOrThrow(ImageGenerationProvider::class.java)
    }

    private lateinit var apiConfigRepository: ApiConfigRepository

    // ---------------- 配置镜像 ----------------

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** "auto" = 跟随主 API；其他值 = 独立配置 */
    private val _connectionMode = MutableStateFlow("auto")
    val connectionMode: StateFlow<String> = _connectionMode.asStateFlow()

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow("")
    val model: StateFlow<String> = _model.asStateFlow()

    private val _savedModels = MutableStateFlow<List<String>>(emptyList())
    val savedModels: StateFlow<List<String>> = _savedModels.asStateFlow()

    private val _size = MutableStateFlow(ImageGenerationProvider.DEFAULT_IMAGE_SIZE)
    val size: StateFlow<String> = _size.asStateFlow()

    private val _count = MutableStateFlow(1)
    val count: StateFlow<Int> = _count.asStateFlow()

    private val _probability = MutableStateFlow(0)
    val probability: StateFlow<Int> = _probability.asStateFlow()

    private val _keywords = MutableStateFlow(AppSettingsStore.ImageGenDefaults.DEFAULT_KEYWORDS)
    val keywords: StateFlow<List<String>> = _keywords.asStateFlow()

    private val _cooldownMinutes = MutableStateFlow(3)
    val cooldownMinutes: StateFlow<Int> = _cooldownMinutes.asStateFlow()

    private val _promptTemplate = MutableStateFlow("{content}")
    val promptTemplate: StateFlow<String> = _promptTemplate.asStateFlow()

    private val _toastEnabled = MutableStateFlow(true)
    val toastEnabled: StateFlow<Boolean> = _toastEnabled.asStateFlow()

    // ---------------- 主 API 信息（「跟随主 API」用） ----------------

    private val _mainConfigName = MutableStateFlow("")
    val mainConfigName: StateFlow<String> = _mainConfigName.asStateFlow()

    private val _mainConfigReady = MutableStateFlow(false)
    val mainConfigReady: StateFlow<Boolean> = _mainConfigReady.asStateFlow()

    // ---------------- 异步动作状态 ----------------

    private val _isFetchingModels = MutableStateFlow(false)
    val isFetchingModels: StateFlow<Boolean> = _isFetchingModels.asStateFlow()

    private val _fetchedModels = MutableStateFlow<List<String>>(emptyList())
    val fetchedModels: StateFlow<List<String>> = _fetchedModels.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    data class TestResult(val success: Boolean, val message: String)

    init {
        val database = AppDatabase.getDatabase(application)
        apiConfigRepository = ApiConfigRepository(
            database.apiConfigDao(),
            database.apiProviderPresetDao()
        )

        viewModelScope.launch {
            appSettingsStore.imageGenEnabledFlow.collect { _enabled.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenProviderFlow.collect { _connectionMode.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenBaseUrlFlow.collect { _baseUrl.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenApiKeyFlow.collect { _apiKey.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenModelFlow.collect { _model.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenModelListFlow.collect { _savedModels.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenSizeFlow.collect { _size.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenCountFlow.collect { _count.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenTriggerProbabilityFlow.collect { _probability.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenKeywordsFlow.collect { _keywords.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenCooldownMinutesFlow.collect { _cooldownMinutes.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenPromptTemplateFlow.collect { _promptTemplate.value = it }
        }
        viewModelScope.launch {
            appSettingsStore.imageGenToastEnabledFlow.collect { _toastEnabled.value = it }
        }

        refreshMainConfig()
    }

    // ---------------- 写入 ----------------

    fun setEnabled(value: Boolean) = launchWrite { appSettingsStore.setImageGenEnabled(value) }

    fun setConnectionMode(mode: String) = launchWrite { appSettingsStore.setImageGenProvider(mode) }

    fun setBaseUrl(url: String) = launchWrite { appSettingsStore.setImageGenBaseUrl(url) }

    fun setApiKey(key: String) = launchWrite { appSettingsStore.setImageGenApiKey(key) }

    fun setModel(model: String) = launchWrite { appSettingsStore.setImageGenModel(model) }

    fun setSize(size: String) = launchWrite { appSettingsStore.setImageGenSize(size) }

    fun setCount(count: Int) = launchWrite { appSettingsStore.setImageGenCount(count) }

    fun setProbability(probability: Int) =
        launchWrite { appSettingsStore.setImageGenTriggerProbability(probability) }

    fun setCooldownMinutes(minutes: Int) =
        launchWrite { appSettingsStore.setImageGenCooldownMinutes(minutes) }

    fun setPromptTemplate(template: String) =
        launchWrite { appSettingsStore.setImageGenPromptTemplate(template) }

    fun setToastEnabled(value: Boolean) = launchWrite { appSettingsStore.setImageGenToastEnabled(value) }

    fun addKeyword(raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return
        if (_keywords.value.any { it.equals(keyword, ignoreCase = true) }) {
            _messages.tryEmit("关键词「$keyword」已存在")
            return
        }
        launchWrite { appSettingsStore.setImageGenKeywords(_keywords.value + keyword) }
    }

    fun removeKeyword(keyword: String) {
        launchWrite { appSettingsStore.setImageGenKeywords(_keywords.value.filterNot { it == keyword }) }
    }

    fun resetKeywords() =
        launchWrite { appSettingsStore.setImageGenKeywords(AppSettingsStore.ImageGenDefaults.DEFAULT_KEYWORDS) }

    // ---------------- 动作 ----------------

    fun refreshMainConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = runCatching { apiConfigRepository.getActiveEnabledConfig() }.getOrNull()
            withContext(Dispatchers.Main) {
                _mainConfigReady.value = config != null
                _mainConfigName.value = config?.name.orEmpty()
            }
        }
    }

    fun fetchModels() {
        if (_isFetchingModels.value) return
        viewModelScope.launch {
            val connection = resolveConnection()
            if (connection == null) {
                _messages.tryEmit("请先填写 API 地址与密钥（或先配置主 API）")
                return@launch
            }
            _isFetchingModels.value = true
            val result = withContext(Dispatchers.IO) {
                imageGenProvider.fetchImageModels(connection.baseUrl, connection.apiKey)
            }
            _isFetchingModels.value = false
            result.onSuccess { catalog: ImageModelCatalog ->
                _fetchedModels.value = if (catalog.imageModels.isNotEmpty()) {
                    catalog.imageModels
                } else {
                    catalog.allModels
                }
                // 持久化全量列表，供下次进页直接展示
                appSettingsStore.setImageGenModelList(catalog.allModels)
                if (catalog.imageModels.isEmpty()) {
                    _messages.tryEmit("未识别到生图模型，已展示全部 ${catalog.allModels.size} 个模型")
                } else {
                    _messages.tryEmit("发现 ${catalog.imageModels.size} 个生图模型")
                }
            }.onFailure { error ->
                SecureLog.w(TAG, "fetchModels failed: ${error.message}")
                _messages.tryEmit("拉取失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun testConnection() {
        if (_isTesting.value) return
        viewModelScope.launch {
            val connection = resolveConnection()
            if (connection == null) {
                _testResult.value = TestResult(false, "请先填写 API 地址与密钥（或先配置主 API）")
                return@launch
            }
            _isTesting.value = true
            _testResult.value = null
            val result = withContext(Dispatchers.IO) {
                imageGenProvider.testConnection(connection.baseUrl, connection.apiKey, connection.model)
            }
            _isTesting.value = false
            _testResult.value = result.fold(
                onSuccess = { TestResult(true, it) },
                onFailure = { TestResult(false, it.message ?: it.javaClass.simpleName) }
            )
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    /** 当前配置是否具备触发条件（供 UI 显示警示条）。 */
    fun configCheck(): String? {
        if (!_enabled.value) return null
        if (_connectionMode.value != "auto" &&
            (_baseUrl.value.isBlank() || _apiKey.value.isBlank())
        ) {
            return "独立配置未填写完整（API 地址 / 密钥）"
        }
        if (_connectionMode.value == "auto" && !_mainConfigReady.value) {
            return "尚未配置可用的主 API，请先在「API 设置」中添加密钥"
        }
        if (_model.value.isBlank()) return "尚未选择生图模型"
        if (_probability.value <= 0 && _keywords.value.isEmpty()) return "未设置触发概率也未设置关键词，不会自动出图"
        return null
    }

    // ---------------- 内部 ----------------

    private data class ResolvedConnection(
        val baseUrl: String,
        val apiKey: String,
        val model: String
    )

    private suspend fun resolveConnection(): ResolvedConnection? {
        val model = _model.value.trim()
        return if (_connectionMode.value == "auto") {
            val config = runCatching { apiConfigRepository.getActiveEnabledConfig() }.getOrNull()
                ?: return null
            if (config.baseUrl.isBlank() || config.apiKey.isBlank()) return null
            ResolvedConnection(config.baseUrl.trim(), config.apiKey.trim(), model)
        } else {
            val url = _baseUrl.value.trim()
            val key = _apiKey.value.trim()
            if (url.isBlank() || key.isBlank()) return null
            ResolvedConnection(url, key, model)
        }
    }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }
                .onFailure { SecureLog.w(TAG, "write image gen setting failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "ImageGenSettingsVM"
    }
}
