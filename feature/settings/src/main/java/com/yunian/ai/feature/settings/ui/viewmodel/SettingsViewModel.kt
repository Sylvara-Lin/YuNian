package com.yunian.ai.feature.settings.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.CloudError
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ApiProviderPreset
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.network.AiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val aiService: com.yunian.ai.network.AiService by lazy {
        ServiceRegistry.getOrThrow(com.yunian.ai.domain.AiServiceProvider::class.java)
            as com.yunian.ai.network.AiService
    }

    private val appSettingsStore = AppSettingsStore(application)
    private lateinit var repository: ApiConfigRepository
    val configs: Flow<List<ApiConfig>>
    val providerPresets: Flow<List<ApiProviderPreset>>

    private val _connectionStatus = MutableStateFlow<Map<String, ConnectionResult>>(emptyMap())
    val connectionStatus: StateFlow<Map<String, ConnectionResult>> = _connectionStatus.asStateFlow()
    private val connectionStatusMap = java.util.concurrent.ConcurrentHashMap<String, ConnectionResult>()

    private val _testedConfigs = MutableStateFlow<Map<String, ApiConfig>>(emptyMap())
    val testedConfigs: StateFlow<Map<String, ApiConfig>> = _testedConfigs.asStateFlow()
    private val testedConfigsMap = java.util.concurrent.ConcurrentHashMap<String, ApiConfig>()

    private fun updateConnectionStatus(key: String, value: ConnectionResult) {
        connectionStatusMap[key] = value
        _connectionStatus.value = connectionStatusMap.toMap()
    }
    private fun removeConnectionStatus(key: String) {
        connectionStatusMap.remove(key)
        _connectionStatus.value = connectionStatusMap.toMap()
    }
    private fun updateTestedConfig(key: String, value: ApiConfig) {
        testedConfigsMap[key] = value
        _testedConfigs.value = testedConfigsMap.toMap()
    }

    private val _saveResult = MutableSharedFlow<SaveResult>(extraBufferCapacity = 8)
    val saveResult: SharedFlow<SaveResult> = _saveResult

    data class TestCompletionEvent(
        val isSuccess: Boolean,
        val providerName: String,
        val latencyMs: Long = 0L,
        val errorMessage: String? = null
    )
    private val _testCompletionEvent = MutableSharedFlow<TestCompletionEvent>(extraBufferCapacity = 1)
    val testCompletionEvent: SharedFlow<TestCompletionEvent> = _testCompletionEvent

    private val _visionEnabled = MutableStateFlow(true)
    val visionEnabled: StateFlow<Boolean> = _visionEnabled.asStateFlow()
    private val _visionModel = MutableStateFlow(AppSettingsStore.VisionModels.VISION_AUTO)
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()
    private val _visionProvider = MutableStateFlow("auto")
    val visionProvider: StateFlow<String> = _visionProvider.asStateFlow()
    private val _visionApiUrl = MutableStateFlow("")
    val visionApiUrl: StateFlow<String> = _visionApiUrl.asStateFlow()
    private val _visionApiKey = MutableStateFlow("")
    val visionApiKey: StateFlow<String> = _visionApiKey.asStateFlow()

    private val _diaryEnabled = MutableStateFlow(false)
    val diaryEnabled: StateFlow<Boolean> = _diaryEnabled.asStateFlow()
    private val _diaryModel = MutableStateFlow("")
    val diaryModel: StateFlow<String> = _diaryModel.asStateFlow()
    private val _diaryBaseUrl = MutableStateFlow("")
    val diaryBaseUrl: StateFlow<String> = _diaryBaseUrl.asStateFlow()
    private val _diaryApiKey = MutableStateFlow("")
    val diaryApiKey: StateFlow<String> = _diaryApiKey.asStateFlow()

    private val _innerThoughtEnabled = MutableStateFlow(false)
    val innerThoughtEnabled: StateFlow<Boolean> = _innerThoughtEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            appSettingsStore.visionEnabledFlow.collect { enabled ->
                _visionEnabled.value = enabled
            }
        }

        viewModelScope.launch {
            appSettingsStore.visionModelFlow.collect { model ->
                _visionModel.value = model
            }
        }

        viewModelScope.launch {
            appSettingsStore.visionProviderFlow.collect { provider ->
                _visionProvider.value = provider
            }
        }

        viewModelScope.launch {
            appSettingsStore.visionApiUrlFlow.collect { url ->
                _visionApiUrl.value = url
            }
        }

        viewModelScope.launch {
            appSettingsStore.visionApiKeyFlow.collect { key ->
                _visionApiKey.value = key
            }
        }

        viewModelScope.launch {
            appSettingsStore.diaryEnabledFlow.collect { enabled ->
                _diaryEnabled.value = enabled
            }
        }

        viewModelScope.launch {
            appSettingsStore.diaryModelFlow.collect { model ->
                _diaryModel.value = model
            }
        }

        viewModelScope.launch {
            appSettingsStore.diaryBaseUrlFlow.collect { url ->
                _diaryBaseUrl.value = url
            }
        }

        viewModelScope.launch {
            appSettingsStore.diaryApiKeyFlow.collect { key ->
                _diaryApiKey.value = key
            }
        }

        viewModelScope.launch {
            appSettingsStore.innerThoughtEnabledFlow.collect { enabled ->
                _innerThoughtEnabled.value = enabled
            }
        }
    }

    fun setVisionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsStore.setVisionEnabled(enabled)
        }
    }

    fun setVisionModel(model: String) {
        viewModelScope.launch {
            appSettingsStore.setVisionModel(model)
        }
    }

    fun setVisionProvider(provider: String) {
        viewModelScope.launch {
            appSettingsStore.setVisionProvider(provider)
        }
    }

    fun setVisionApiUrl(url: String) {
        viewModelScope.launch {
            appSettingsStore.setVisionApiUrl(url)
        }
    }

    fun setVisionApiKey(key: String) {
        viewModelScope.launch {
            appSettingsStore.setVisionApiKey(key)
        }
    }

    fun setDiaryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsStore.setDiaryEnabled(enabled)
        }
    }

    fun setDiaryModel(model: String) {
        viewModelScope.launch {
            appSettingsStore.setDiaryModel(model)
        }
    }

    fun setDiaryBaseUrl(url: String) {
        viewModelScope.launch {
            appSettingsStore.setDiaryBaseUrl(url)
        }
    }

    fun setDiaryApiKey(key: String) {
        viewModelScope.launch {
            appSettingsStore.setDiaryApiKey(key)
        }
    }

    suspend fun testDiaryConnection(baseUrl: String, apiKey: String, model: String): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
                    throw IllegalArgumentException("API 地址、密钥和模型名不能为空")
                }
                val config = ApiConfig(
                    provider = ApiProvider.CUSTOM,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model
                )
                val testMessages = listOf(
                    com.yunian.ai.network.Message("system", "You are a helpful assistant."),
                    com.yunian.ai.network.Message("user", "Hi")
                )
                val response = aiService.callOpenAiCompatibleForTest(config, testMessages)
                "连接成功！响应: ${response.take(50)}"
            }
        }
    }

    fun setInnerThoughtEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsStore.setInnerThoughtEnabled(enabled)
        }
    }

    suspend fun testVisionConnection(provider: String, baseUrl: String, apiKey: String, model: String): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (baseUrl.isBlank() || apiKey.isBlank()) {
                    throw IllegalArgumentException("API 地址和密钥不能为空")
                }

                val resolvedProvider = when (provider.uppercase()) {
                    "OPENAI" -> ApiProvider.OPENAI
                    "ANTHROPIC" -> ApiProvider.ANTHROPIC
                    "GEMINI" -> ApiProvider.GEMINI
                    "KIMI" -> ApiProvider.KIMI
                    "DEEPSEEK" -> ApiProvider.DEEPSEEK
                    "DASHSCOPE" -> ApiProvider.DASHSCOPE
                    "ZHIPU" -> ApiProvider.ZHIPU
                    "CUSTOM" -> ApiProvider.CUSTOM
                    "IFLYTEK" -> ApiProvider.IFLYTEK
                    else -> ApiProvider.OPENAI
                }

                val config = ApiConfig(
                    provider = resolvedProvider,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model
                )

                val aiService = aiService
                val testMessages = listOf(
                    com.yunian.ai.network.Message("system", "You are a helpful assistant."),
                    com.yunian.ai.network.Message("user", "Hi")
                )

                val response = when (resolvedProvider) {
                    ApiProvider.ANTHROPIC -> aiService.callAnthropicForTest(config, testMessages, "Be helpful.")
                    else -> aiService.callOpenAiCompatibleForTest(config, testMessages)
                }

                "连接成功！响应: ${response.take(50)}"
            }
        }
    }

    enum class ConnectionStatus {
        UNKNOWN, TESTING, CONNECTED, FAILED
    }
    data class ConnectionResult(
        val status: ConnectionStatus,
        val latencyMs: Long = 0L,
        val errorMessage: String? = null,
        val errorCode: String? = null,

        val groupName: String? = null,
        val remainingQuota: Double = 0.0,
        val dailyLimit: Double? = null,
        val rpmLimit: Int = 0,
        val discount: Double = 1.0,

        val clientId: String? = null,
    )

    fun connectionKey(config: ApiConfig): String =
        if (config.id > 0) "id:${config.id}" else "draft:${config.provider.name}:${config.name}:${config.baseUrl}"

    sealed class SaveResult {
        data class Success(val message: String) : SaveResult()
        data class Error(val message: String) : SaveResult()
    }

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ApiConfigRepository(database.apiConfigDao(), database.apiProviderPresetDao())
        configs = repository.getAllConfigs()
        providerPresets = repository.getVisibleProviderPresets()

        viewModelScope.launch(Dispatchers.IO) {
            val saved = repository.getAllConfigs().first()
            saved.forEach { config ->
                val key = connectionKey(config)
                if (config.connectionTested && _connectionStatus.value[key] == null) {
                    updateConnectionStatus(key, ConnectionResult(ConnectionStatus.CONNECTED, config.latencyMs))
                }
            }
        }

        refreshConnectionStatus()
    }

    fun saveConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val key = connectionKey(config)
                val draftStatus = _connectionStatus.value[key]

                val testedConfig = _testedConfigs.value[key]

                val configToSave = if (testedConfig != null && draftStatus?.status == ConnectionStatus.CONNECTED) {

                    testedConfig.copy(
                        id = config.id,

                        apiKey = config.apiKey.takeIf { it.isNotBlank() } ?: testedConfig.apiKey,
                        // 备用 key 已废弃：无论测试前后，统一清空，保证与「直接保存」路径行为一致
                        extraApiKeys = "",
                        baseUrl = config.baseUrl.takeIf { it.isNotBlank() } ?: testedConfig.baseUrl,
                        model = config.model.takeIf { it.isNotBlank() } ?: testedConfig.model,
                        name = config.name,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        connectionTested = true,
                        connectionTestedAt = System.currentTimeMillis(),
                        latencyMs = draftStatus.latencyMs
                    )
                } else if (draftStatus?.status == ConnectionStatus.CONNECTED) {
                    config.copy(
                        connectionTested = true,
                        connectionTestedAt = System.currentTimeMillis(),
                        latencyMs = draftStatus.latencyMs
                    )
                } else {
                    config
                }

                SecureLog.d("SettingsViewModel", "Saving config: provider=${configToSave.provider}, model=${configToSave.model}, keys=${configToSave.getAllApiKeys().size}")

                val savedId = repository.saveConfig(configToSave)

                repository.disableOtherConfigs(savedId)
                repository.enableConfig(savedId)
                val savedKey = connectionKey(configToSave.copy(id = savedId))
                updateConnectionStatus(savedKey, draftStatus ?: ConnectionResult(ConnectionStatus.UNKNOWN))

                updateTestedConfig(savedKey, configToSave)
                _saveResult.emit(SaveResult.Success("配置已保存并已启用"))
            } catch (e: Exception) {
                SecureLog.e("SettingsViewModel", "Save config failed: ${e.message}")
                _saveResult.emit(SaveResult.Error("保存失败，请检查配置"))
            }
        }
    }

    fun toggleConfigEnabled(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val actualConfig = resolveConfig(config)
                if (actualConfig.isEnabled) {
                    val updated = actualConfig.copy(isEnabled = false)
                    repository.updateConfig(updated)
                    _saveResult.emit(SaveResult.Success("${actualConfig.provider.displayName} 已禁用"))
                } else {
                    repository.disableOtherConfigs(actualConfig.id)
                    val updated = actualConfig.copy(isEnabled = true)
                    repository.updateConfig(updated)
                    _saveResult.emit(SaveResult.Success("${actualConfig.provider.displayName} 已启用"))
                }
            } catch (e: Exception) {
                _saveResult.emit(SaveResult.Error("操作失败"))
            }
        }
    }

    fun selectActiveConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val oldKey = connectionKey(config)
                val actualConfig = resolveConfig(config)
                val newKey = connectionKey(actualConfig)

                if (oldKey != newKey) {
                    _connectionStatus.value[oldKey]?.let { updateConnectionStatus(newKey, it) }
                }
                repository.disableOtherConfigs(actualConfig.id)
                val updated = actualConfig.copy(isEnabled = true)
                repository.updateConfig(updated)
                _saveResult.emit(SaveResult.Success("已切换到 ${actualConfig.provider.displayName}"))
            } catch (e: Exception) {
                _saveResult.emit(SaveResult.Error("切换失败"))
            }
        }
    }

    private suspend fun resolveConfig(config: ApiConfig): ApiConfig {
        if (config.id > 0) return config
        val existing = repository.getConfigByProvider(config.provider)
        if (existing != null) return existing
        val newId = repository.saveConfig(config)
        return config.copy(id = newId)
    }

    fun deleteConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            if (config.id > 0) {
                repository.deleteConfigById(config.id)
            } else {
                repository.deleteConfig(config.provider)
            }
            removeConnectionStatus(connectionKey(config))
        }
    }

    fun deleteConfigByProvider(provider: ApiProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteConfig(provider)
            connectionStatusMap.entries.removeIf {  it.key.startsWith("id:")  }; _connectionStatus.value = connectionStatusMap.toMap()
        }
    }

    fun refreshConnectionStatus() {
        viewModelScope.launch {
            val allConfigs = repository.getAllConfigs()
            allConfigs.collect { list ->
                list.forEach { config ->
                    val key = connectionKey(config)
                    val current = _connectionStatus.value[key]
                    val status = when {

                        current?.status == ConnectionStatus.TESTING -> ConnectionResult(ConnectionStatus.TESTING, current.latencyMs)
                        current?.status == ConnectionStatus.CONNECTED -> ConnectionResult(ConnectionStatus.CONNECTED, current.latencyMs)
                        current?.status == ConnectionStatus.FAILED -> ConnectionResult(ConnectionStatus.FAILED, current.latencyMs, current.errorMessage)

                        config.connectionTested && current == null -> ConnectionResult(ConnectionStatus.CONNECTED, config.latencyMs)

                        current != null -> current
                        else -> ConnectionResult(ConnectionStatus.UNKNOWN)
                    }
                    updateConnectionStatus(key, status)
                }
            }
        }
    }

    fun refreshPartnerQuota() {
        viewModelScope.launch(Dispatchers.IO) {
            val allConfigs = repository.getAllConfigs().first()
            val partnerConfig = allConfigs.firstOrNull { it.provider == ApiProvider.PARTNER } ?: return@launch
            val key = connectionKey(partnerConfig)
            try {
                val handshakeJson = com.yunian.ai.common.RemoteKeyProvider.cloveHandshake(getApplication())
                val ok = handshakeJson.optBoolean("ok", false)
                val latency = handshakeJson.optLong("latency_ms", 0)
                if (ok) {
                    val clientId = handshakeJson.optString("client_id").ifEmpty { null }
                    val sessionToken = handshakeJson.optString("session_token").ifEmpty { null }
                    if (clientId != null && sessionToken != null) {
                        com.yunian.ai.common.RemoteKeyProvider.storeHandshakeResult(getApplication(), handshakeJson)
                    }
                    val groupName = handshakeJson.optString("group_name").ifEmpty { null }
                    val remaining = handshakeJson.optDouble("remaining", 0.0)
                    val daily = if (handshakeJson.has("daily_quota_limit") && !handshakeJson.isNull("daily_quota_limit"))
                        handshakeJson.optDouble("daily_quota_limit") else null
                    val rpm = handshakeJson.optInt("rpm_limit", 0)
                    val disc = handshakeJson.optDouble("discount", 1.0)
                    updateConnectionStatus(key, ConnectionResult(
                        ConnectionStatus.CONNECTED, latency, null, null,
                        groupName, remaining, daily, rpm, disc, clientId
                    ))
                }
            } catch (_: Exception) {  }
        }
    }

    fun testConnection(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = connectionKey(config)
            updateConnectionStatus(key, ConnectionResult(ConnectionStatus.TESTING))

            try {
            var allKeys = config.getAllApiKeys()

            SecureLog.d("SettingsViewModel", "=== TEST CONNECTION START ===")
            SecureLog.d("SettingsViewModel", "Testing config: provider=${config.provider}, baseUrl=${config.baseUrl}, model=${config.model}")
            SecureLog.d("SettingsViewModel", "Available keys: ${allKeys.size} (userKey=${config.apiKey.takeIf { it.isNotBlank() }?.take(8)}..., remote=${allKeys.size > 1})")
            SecureLog.d("SettingsViewModel", "isEnabled=${config.isEnabled}, connectionTested=${config.connectionTested}")
            SecureLog.d("SettingsViewModel", "isPARTNER=${config.provider == ApiProvider.PARTNER}, providerName=${config.provider.name}")

            if (config.provider == ApiProvider.PARTNER) {
                SecureLog.d("SettingsViewModel", "PARTNER: calling handshake endpoint...")
                try {
                    val handshakeJson = com.yunian.ai.common.RemoteKeyProvider.cloveHandshake(getApplication())
                    val ok = handshakeJson.optBoolean("ok", false)
                    val latency = handshakeJson.optLong("latency_ms", 0)
                    val errorCode = handshakeJson.optString("error").ifEmpty { null }
                    val clientId = handshakeJson.optString("client_id").ifEmpty { null }
                    val sessionToken = handshakeJson.optString("session_token").ifEmpty { null }

                    if (ok && clientId != null && sessionToken != null) {
                        val groupName = handshakeJson.optString("group_name").ifEmpty { null }
                        val remaining = handshakeJson.optDouble("remaining", 0.0)
                        val daily = if (handshakeJson.has("daily_quota_limit") && !handshakeJson.isNull("daily_quota_limit"))
                            handshakeJson.optDouble("daily_quota_limit") else null
                        val rpm = handshakeJson.optInt("rpm_limit", 0)
                        val disc = handshakeJson.optDouble("discount", 1.0)

                        com.yunian.ai.common.RemoteKeyProvider.storeHandshakeResult(getApplication(), handshakeJson)
                        updateConnectionStatus(key, ConnectionResult(
                            ConnectionStatus.CONNECTED, latency, null, null,
                            groupName, remaining, daily, rpm, disc, clientId
                        ))
                        SecureLog.d("SettingsViewModel", "PARTNER handshake OK clientId=$clientId latency=${latency}ms")
                        return@launch
                    } else {
                        val err = errorCode ?: "unknown"
                        val serverMessage = handshakeJson.optString("message").ifEmpty { null }

                        if (err == "key_disabled" && clientId != null) {
                            updateConnectionStatus(key, ConnectionResult(
                                ConnectionStatus.FAILED, latency,
                                "密钥已被管理员禁用", err, clientId = clientId
                            ))
                        } else {

                            val friendly = CloudError(code = err, message = null).friendlyMessage()
                            val displayMessage = serverMessage
                                ?: if (friendly != "请求失败（$err）") friendly else "连接失败"
                            updateConnectionStatus(key, ConnectionResult(
                                ConnectionStatus.FAILED, latency,
                                displayMessage, err
                            ))
                        }
                        SecureLog.e("SettingsViewModel", "PARTNER handshake FAILED error=$err${serverMessage?.let { " ($it)" } ?: ""}")
                        return@launch
                    }
                } catch (e: Exception) {
                    updateConnectionStatus(key, ConnectionResult(
                        ConnectionStatus.FAILED, 0L,
                        "无法连接服务器: ${e.message}", "network_error"
                    ))
                    SecureLog.e("SettingsViewModel", "PARTNER handshake exception: ${e.message}")
                    return@launch
                }
            }

            var currentConfig = config

            if (allKeys.isEmpty()) {
                updateConnectionStatus(key, ConnectionResult(ConnectionStatus.FAILED, 0L, "API Key 为空，请填写主密钥或检查远程Key服务"))

                return@launch
            }

            var testConfig = currentConfig
            val userModel = currentConfig.model.trim()

            if (userModel.isBlank() ||
                currentConfig.provider == ApiProvider.PARTNER ||
                currentConfig.provider == ApiProvider.CUSTOM) {

                val aiService = aiService
                val keyToUse = allKeys.firstOrNull() ?: currentConfig.apiKey
                SecureLog.d("SettingsViewModel", "Fetching models with key: ${keyToUse.take(8)}...")

                val modelsResult = aiService.fetchModels(currentConfig.baseUrl, keyToUse, currentConfig.provider)
                val models = modelsResult.getOrNull()

                if (models != null && models.isNotEmpty()) {
                    SecureLog.d("SettingsViewModel", "Found ${models.size} models: ${models.take(5).joinToString(", ")}")
                    _fetchedModels.value = _fetchedModels.value.toMutableMap().apply {
                        put(currentConfig.provider.name, models)
                    }

                    val chatKeywords = listOf("chat", "completion", "instruct", "gpt", "claude", "gemini",
                        "deepseek", "qwen", "glm", "moonshot", "kimi", "yi-", "ernie", "hunyuan", "doubao")
                    val chatModels = models.filter { m ->
                        chatKeywords.any { m.contains(it, ignoreCase = true) }
                    }

                    val testModel = when {
                        currentConfig.provider == ApiProvider.PARTNER -> {
                            val serverModel = com.yunian.ai.common.RemoteKeyProvider.getRandomModel(getApplication())?.takeIf { it.isNotBlank() }

                            val candidatePool = if (chatModels.size > 1) chatModels
                            else models.filter { !it.contains("embed", ignoreCase = true) && !it.contains("moderation", ignoreCase = true) }
                            val chosenModel = if (candidatePool.size > 1) {
                                com.yunian.ai.network.AiService.familyBalancedRandom(candidatePool)
                            } else {
                                serverModel ?: chatModels.firstOrNull()
                                ?: models.firstOrNull { !it.contains("embed", ignoreCase = true) && !it.contains("moderation", ignoreCase = true) }
                                ?: models.first()
                            }
                            SecureLog.d("SettingsViewModel", "PARTNER test model: chosen=$chosenModel, server=$serverModel, chatModels=${chatModels.size}, candidatePool=${candidatePool.size}")
                            chosenModel
                        }

                        userModel.isNotBlank() -> userModel
                        chatModels.isNotEmpty() -> chatModels.first()
                        else -> {

                            models.firstOrNull { !it.contains("embed", ignoreCase = true) && !it.contains("moderation", ignoreCase = true) }
                                ?: models.first()
                        }
                    }

                    testConfig = currentConfig.copy(model = testModel)
                    SecureLog.d("SettingsViewModel", "Selected test model: $testModel (from ${models.size} models, ${chatModels.size} chat-capable)")
                } else {
                    SecureLog.w("SettingsViewModel", "No models fetched, using existing config: ${currentConfig.model}")
                }
            }

            if (testConfig.model.isBlank()) {
                updateConnectionStatus(key, ConnectionResult(ConnectionStatus.FAILED, 0L, "无法获取模型列表，请手动填写模型名称"))

                return@launch
            }

            val probeMessages = listOf(
                com.yunian.ai.network.Message("user", "ping")
            )
            SecureLog.d("SettingsViewModel", "Probing API RTT: url=${testConfig.baseUrl}, model=${testConfig.model}")
            val startTime = System.currentTimeMillis()
            val result = runCatching {
                val aiService = aiService
                if (AiService.usesAnthropicProtocol(testConfig)) {
                    aiService.callAnthropicForTest(testConfig, probeMessages, "Reply with ok.")
                } else {
                    aiService.callOpenAiCompatibleForTest(testConfig, probeMessages)
                }
            }.also {

                val ex = it.exceptionOrNull()
                if (ex is kotlinx.coroutines.CancellationException) throw ex
            }
            val latencyMs = System.currentTimeMillis() - startTime

            val isSuccess = result.isSuccess

            if (!isSuccess) {
                val errorMsg = result.exceptionOrNull()?.message ?: "未知错误"
                SecureLog.e("SettingsViewModel", "Connection test failed: $errorMsg")

                val friendlyError = when {
                    errorMsg.contains("401", ignoreCase = true) || errorMsg.contains("Unauthorized", ignoreCase = true) ->
                        "API Key 无效或已过期"
                    errorMsg.contains("403", ignoreCase = true) || errorMsg.contains("Forbidden", ignoreCase = true) ->
                        "API Key 没有权限访问此接口"
                    errorMsg.contains("404", ignoreCase = true) ->
                        "API 地址或模型名不正确"
                    errorMsg.contains("429", ignoreCase = true) ->
                        "请求过于频繁，请稍后再试"
                    errorMsg.contains("500", ignoreCase = true) || errorMsg.contains("502", ignoreCase = true) || errorMsg.contains("503", ignoreCase = true) ->
                        "服务器暂时不可用，请稍后再试"
                    errorMsg.contains("timeout", ignoreCase = true) || errorMsg.contains("Timeout", ignoreCase = true) ->
                        "连接超时，请检查网络或API地址"
                    errorMsg.contains("Unable to resolve host", ignoreCase = true) ->
                        "无法解析主机名，请检查API地址是否正确"
                    else -> errorMsg
                }

                updateConnectionStatus(key, ConnectionResult(ConnectionStatus.FAILED, latencyMs, friendlyError,
                    errorCode = when {
                        errorMsg.contains("upstream_unreachable") -> "upstream_unreachable"
                        errorMsg.contains("account_blocked") -> "account_blocked"
                        errorMsg.contains("timeout") -> "timeout"
                        errorMsg.contains("resolve host") || errorMsg.contains("refused") -> "network_error"
                        else -> null
                    }
                ))
            } else {
                updateConnectionStatus(key, ConnectionResult(ConnectionStatus.CONNECTED, latencyMs))
            }

            _testCompletionEvent.emit(TestCompletionEvent(
                isSuccess = isSuccess,
                providerName = config.provider.displayName,
                latencyMs = latencyMs,
                errorMessage = if (!isSuccess) result.exceptionOrNull()?.message else null
            ))

            if (isSuccess) {

                val finalConfig = testConfig.copy(
                    connectionTested = true,
                    connectionTestedAt = System.currentTimeMillis(),
                    latencyMs = latencyMs
                )

                updateTestedConfig(key, finalConfig)

                if (config.id > 0) {
                    repository.updateConfig(finalConfig)
                    SecureLog.d("SettingsViewModel", "Saved tested config with remote keys and random model: ${finalConfig.model}")
                }
            } else if (config.id > 0) {

                val updatedConfig = config.copy(
                    connectionTested = false,
                    connectionTestedAt = 0L,
                    latencyMs = 0L
                )
                repository.updateConfig(updatedConfig)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {

            throw e
        } catch (e: Exception) {
            SecureLog.e("SettingsViewModel", "testConnection unexpected error", e)
            updateConnectionStatus(key, ConnectionResult(ConnectionStatus.FAILED, 0L, e.message ?: "未知错误"))
        } finally {

            if (_connectionStatus.value[key]?.status == ConnectionStatus.TESTING) {
                updateConnectionStatus(key, ConnectionResult(ConnectionStatus.FAILED, 0L, "测试中断"))
            }
        }
        }
    }

    private val _fetchedModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val fetchedModels: StateFlow<Map<String, List<String>>> = _fetchedModels.asStateFlow()

    private val _balanceInfo = MutableStateFlow<AiService.BalanceInfo?>(null)
    val balanceInfo: StateFlow<AiService.BalanceInfo?> = _balanceInfo.asStateFlow()

    private val _balanceQueryFailed = MutableStateFlow(false)
    val balanceQueryFailed: StateFlow<Boolean> = _balanceQueryFailed.asStateFlow()

    data class ModelFetchState(
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )

    private val _modelFetchStates = MutableStateFlow<Map<String, ModelFetchState>>(emptyMap())
    val modelFetchStates: StateFlow<Map<String, ModelFetchState>> = _modelFetchStates.asStateFlow()

    fun fetchModels(baseUrl: String, apiKey: String, provider: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _fetchedModels.value = _fetchedModels.value.toMutableMap().apply {
                remove(provider)
            }
            _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                put(provider, ModelFetchState(isLoading = true))
            }
            var earlyReturn = false
            try {
                val aiService = aiService
                val resolvedProvider = try {
                    ApiProvider.valueOf(provider)
                } catch (e: Exception) {
                    null
                }

                val modelListConfig = ApiConfig(
                    provider = resolvedProvider ?: ApiProvider.CUSTOM,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = "",
                    formatHint = if (provider == "CUSTOM_ANTHROPIC") "anthropic" else "openai"
                )
                if (!com.yunian.ai.network.AiService.supportsOpenAiModelList(modelListConfig)) {
                    _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                        put(provider, ModelFetchState(errorMessage = "Anthropic 兼容模式通常不支持 /models，请手动填写模型名"))
                    }
                    return@launch
                }

                var keyToUse = apiKey
                if (resolvedProvider == ApiProvider.PARTNER && keyToUse.isBlank()) {
                    SecureLog.d("SettingsViewModel", "PARTNER fetchModels: fetching keys from remote server...")
                    val remoteKeys = com.yunian.ai.common.RemoteKeyProvider.fetchKeysAsync(getApplication(), forceRefresh = true)
                    if (remoteKeys.isNotEmpty()) {
                        keyToUse = remoteKeys.first()
                        SecureLog.d("SettingsViewModel", "Using remote key for fetchModels: ${keyToUse.take(8)}...")
                    } else {
                        _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                            put(provider, ModelFetchState(errorMessage = "无法从服务器获取密钥"))
                        }
                        earlyReturn = true
                    }
                }
                if (!earlyReturn) {
                val result = aiService.fetchModels(baseUrl, keyToUse, resolvedProvider)
                result.onSuccess { models ->
                    _fetchedModels.value = _fetchedModels.value.toMutableMap().apply {
                        put(provider, models)
                    }
                    _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                        put(provider, ModelFetchState())
                    }
                }.onFailure { error ->
                    _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                        put(provider, ModelFetchState(errorMessage = error.message ?: "模型列表获取失败"))
                    }
                }
                }
            } catch (e: Exception) {
                _modelFetchStates.value = _modelFetchStates.value.toMutableMap().apply {
                    put(provider, ModelFetchState(errorMessage = e.message ?: "模型列表获取失败"))
                }
            }
        }
    }

    fun queryBalance(configId: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _balanceQueryFailed.value = false
            val aiService = aiService
            val result = if (configId != null) {
                aiService.queryBalance(configId)
            } else {
                val activeConfig = aiService.getActiveConfig()
                if (activeConfig != null) {
                    aiService.queryBalance(activeConfig.id.takeIf { it > 0 })
                } else {
                    val partnerPreset = repository.getProviderPreset(ApiProvider.PARTNER)
                    val builtinConfig = ApiConfig(
                        provider = ApiProvider.PARTNER,
                        apiKey = "",
                        baseUrl = partnerPreset?.baseUrl ?: ApiProvider.PARTNER.defaultBaseUrl,
                        model = partnerPreset?.model ?: ApiProvider.PARTNER.defaultModel
                    )
                    aiService.queryBalanceWithConfig(builtinConfig)
                }
            }
            result.onSuccess { balance ->
                _balanceInfo.value = balance
                _balanceQueryFailed.value = false
            }.onFailure {
                _balanceInfo.value = null
                _balanceQueryFailed.value = true
            }
        }
    }

    fun queryBalanceForConfig(config: ApiConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            _balanceQueryFailed.value = false
            val aiService = aiService
            aiService.queryBalanceWithConfig(config)
                .onSuccess { balance ->
                    _balanceInfo.value = balance
                    _balanceQueryFailed.value = false
                }.onFailure {
                    _balanceInfo.value = null
                    _balanceQueryFailed.value = true
                }
        }
    }

    override fun onCleared() {

        connectionStatusMap.clear()
        testedConfigsMap.clear()
        _connectionStatus.value = emptyMap()
        _testedConfigs.value = emptyMap()
        super.onCleared()
    }
}
