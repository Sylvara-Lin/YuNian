package com.yunian.ai.feature.wechat.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.wechat.WeChatChannelHealthSnapshot
import com.yunian.ai.domain.wechat.WeChatDeliveryStatus
import com.yunian.ai.domain.wechat.WeChatIdentityMapPort
import com.yunian.ai.domain.wechat.WeChatUserMapping
import com.yunian.ai.feature.wechat.data.A0
import com.yunian.ai.feature.wechat.data.WeChatMessageRepository
import com.yunian.ai.feature.wechat.data.WeChatTokenStore
import com.yunian.ai.feature.wechat.service.WeChatChannelKeeper
import com.yunian.ai.feature.wechat.service.WeChatChannelRuntime
import com.yunian.ai.feature.wechat.service.WeChatServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WeChatViewModel(
    application: Application,
    private val repository: WeChatMessageRepository,
    private val tokenStore: WeChatTokenStore
) : ViewModel() {
    private val appContext = application.applicationContext

    private val _uiState = MutableStateFlow(WeChatUiState())
    val uiState: StateFlow<WeChatUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WeChatEvent>()
    val events: SharedFlow<WeChatEvent> = _events.asSharedFlow()

    private val loginManager = WeChatLoginManager(repository)
    private val companionDao = AppDatabase.getDatabase(appContext).companionDao()

    private val identityMapPort: WeChatIdentityMapPort?
        get() = ServiceRegistry.get(WeChatIdentityMapPort::class.java)

    init {
        viewModelScope.launch {
            repository.accountFlow.collect { account ->
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = account != null,
                    account = account
                )
                if (account != null) {
                    WeChatChannelKeeper.ensureRunning(appContext)
                    loadUserMappings()
                    refreshChannelHealth()
                } else {
                    WeChatChannelKeeper.stop(appContext)
                    _uiState.value = _uiState.value.copy(
                        userMappings = emptyList(),
                        userCompanionMappings = emptyMap(),
                        channelHealth = WeChatChannelHealthSnapshot(),
                    )
                }
            }
        }

        viewModelScope.launch {
            tokenStore.autoReplyFlow.collect { autoReply ->
                _uiState.value = _uiState.value.copy(autoReply = autoReply)
            }
        }

        viewModelScope.launch {
            tokenStore.notifyEnabledFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(notifyEnabled = enabled)
            }
        }

        viewModelScope.launch {
            tokenStore.forwardEnabledFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(forwardEnabled = enabled)
            }
        }

        viewModelScope.launch {
            tokenStore.defaultCompanionIdFlow.collect { companionId ->
                _uiState.value = _uiState.value.copy(defaultCompanionId = companionId)
            }
        }

        viewModelScope.launch {
            tokenStore.customBotNameFlow.collect { name ->
                _uiState.value = _uiState.value.copy(customBotName = name)
            }
        }

        viewModelScope.launch {
            companionDao.getAllCompanions().collect { companions ->
                _uiState.value = _uiState.value.copy(availableCompanions = companions)
            }
        }

        viewModelScope.launch {
            repository.incomingMessages.collect { message ->
                val text = repository.extractText(message)
                _events.emit(WeChatEvent.MessageReceived(message.fromUserId ?: "", text))
            }
        }

        viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.isLoggedIn) {
                    refreshChannelHealth()
                }
                delay(HEALTH_REFRESH_MS)
            }
        }
    }

    private fun loadUserMappings() {
        viewModelScope.launch {
            val port = identityMapPort
            val list = if (port != null) {
                port.listMappings()
            } else {
                tokenStore.getAllWechatUserMappings()
                    .filter { it.value > 0 }
                    .map { (uid, cid) ->
                        WeChatUserMapping(wechatUserId = uid, companionId = cid)
                    }
            }
            val map = list.associate { it.wechatUserId to it.companionId }
            _uiState.value = _uiState.value.copy(
                userMappings = list,
                userCompanionMappings = map,
            )
        }
    }

    fun refreshChannelHealth() {
        viewModelScope.launch {
            val outbox = WeChatServiceLocator.outboxCoordinator(appContext)
            val open = runCatching { outbox.openCount() }.getOrDefault(0)
            val pending = runCatching { outbox.countByStatus(WeChatDeliveryStatus.PENDING) }.getOrDefault(0)
            val failed = runCatching { outbox.countByStatus(WeChatDeliveryStatus.FAILED) }.getOrDefault(0)
            val sending = runCatching { outbox.countByStatus(WeChatDeliveryStatus.SENDING) }.getOrDefault(0)
            val recent = runCatching { outbox.recentFailures(5) }.getOrDefault(emptyList())
            val snapshot = WeChatChannelRuntime.healthSnapshot(
                openOutboxCount = open,
                pendingOutboxCount = pending,
                failedOutboxCount = failed,
                sendingOutboxCount = sending,
                recentFailures = recent,
            )
            _uiState.value = _uiState.value.copy(channelHealth = snapshot)
        }
    }

    fun setUserCompanionMapping(wechatUserId: String, companionId: Long) {
        viewModelScope.launch {
            val uid = wechatUserId.trim()
            if (uid.isBlank() || companionId <= 0) return@launch
            val port = identityMapPort
            if (port != null) {
                runCatching { port.bind(uid, companionId) }
                    .onFailure {
                        _events.emit(WeChatEvent.SendFailed(it.message ?: "绑定映射失败"))
                    }
            } else {
                tokenStore.setCompanionIdForWechatUser(uid, companionId)
            }
            loadUserMappings()
        }
    }

    fun addUserCompanionMapping(wechatUserId: String, companionId: Long) {
        setUserCompanionMapping(wechatUserId, companionId)
    }

    fun removeUserCompanionMapping(wechatUserId: String) {
        viewModelScope.launch {
            val port = identityMapPort
            if (port != null) {
                port.unbind(wechatUserId)
            } else {
                tokenStore.removeWechatUserMapping(wechatUserId)
            }
            loadUserMappings()
        }
    }

    fun startQrCodeLogin() {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = loginManager.getQrCode()
            result.onSuccess { qrCode ->
                _uiState.value = _uiState.value.copy(
                    qrCodeKey = qrCode.statusToken,
                    qrCodeContent = qrCode.displayContent,
                    isLoading = false,
                    showQrCode = true
                )
                loginManager.startQrPolling(
                    qrCode = qrCode.statusToken,
                    scope = viewModelScope,
                    onSuccess = { account ->
                        _uiState.value = _uiState.value.copy(
                            isLoggedIn = true,
                            account = account,
                            showQrCode = false,
                            qrCodeKey = null,
                            qrCodeContent = null,
                            error = null
                        )
                        // 重新扫码登录成功：立即解除会话过期冷却，让收发即时恢复（不等 Worker 兜底）
                        WeChatChannelRuntime.clearSessionExpired()
                        viewModelScope.launch {
                            _events.emit(WeChatEvent.LoginSuccess)
                            WeChatChannelKeeper.ensureRunning(appContext)
                        }
                    },
                    onExpired = {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            showQrCode = false,
                            qrCodeKey = null,
                            qrCodeContent = null,
                            error = "二维码已过期，请重新获取"
                        )
                    },
                    onTimeout = {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            showQrCode = false,
                            qrCodeKey = null,
                            qrCodeContent = null,
                            error = "扫码超时，请重试"
                        )
                    }
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = error.message
                )
            }
        }
    }

    fun cancelQrLogin() {
        loginManager.cancelQrPolling()
        _uiState.value = _uiState.value.copy(showQrCode = false, qrCodeKey = null, qrCodeContent = null, isLoading = false)
    }

    fun toggleAutoReply(enabled: Boolean) {
        viewModelScope.launch {
            tokenStore.setAutoReply(enabled)
        }
    }

    fun toggleNotifyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            tokenStore.setNotifyEnabled(enabled)
        }
    }

    fun toggleForwardEnabled(enabled: Boolean) {
        viewModelScope.launch {
            tokenStore.setForwardEnabled(enabled)
        }
    }

    fun setDefaultCompanionId(companionId: Long?) {
        viewModelScope.launch {
            tokenStore.setDefaultCompanionId(companionId)
        }
    }

    fun setCustomBotName(name: String?) {
        viewModelScope.launch {
            tokenStore.setCustomBotName(name)
        }
    }

    fun sendMessage(toUserId: String, text: String) {
        viewModelScope.launch {
            val result = repository.sendTextMessage(toUserId, text)
            result.onFailure { error ->
                _events.emit(WeChatEvent.SendFailed(error.message ?: "发送失败"))
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            WeChatChannelKeeper.stop(appContext)
            _uiState.value = _uiState.value.copy(isLoggedIn = false, account = null)
            _events.emit(WeChatEvent.LoggedOut)
        }
    }

    override fun onCleared() {
        super.onCleared()
        loginManager.clear()
    }
}

data class WeChatUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val account: A0? = null,
    val showQrCode: Boolean = false,
    val qrCodeKey: String? = null,
    val qrCodeContent: String? = null,
    val error: String? = null,
    val autoReply: Boolean = false,
    val notifyEnabled: Boolean = true,
    val forwardEnabled: Boolean = true,
    val defaultCompanionId: Long? = null,
    val availableCompanions: List<CompanionEntity> = emptyList(),

    val userCompanionMappings: Map<String, Long> = emptyMap(),

    val userMappings: List<WeChatUserMapping> = emptyList(),

    val channelHealth: WeChatChannelHealthSnapshot = WeChatChannelHealthSnapshot(),
    val customBotName: String? = null
)

private const val HEALTH_REFRESH_MS = 5_000L

sealed class WeChatEvent {
    data class MessageReceived(val fromUserId: String, val text: String) : WeChatEvent()
    data class SendFailed(val error: String) : WeChatEvent()
    data object LoginSuccess : WeChatEvent()
    data object LoggedOut : WeChatEvent()
}

class WeChatViewModelFactory(private val application: Application) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val repository = WeChatServiceLocator.messageRepository(application)
        val tokenStore = WeChatServiceLocator.tokenStore(application)
        return WeChatViewModel(application, repository, tokenStore) as T
    }
}
