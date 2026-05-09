package com.wireturn.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Сессия ручной капчи. sessionId позволяет диалогу различать соседние
 * капча-сессии с одинаковым URL и пересоздавать WebView через `key(sessionId)`.
 */
data class CaptchaSession(val url: String, val sessionId: Long)

sealed class ProxyStatus {
    data object Idle : ProxyStatus()
    data object Starting : ProxyStatus()
    data object Connecting : ProxyStatus()
    data object Connected : ProxyStatus()
    data object Suppressed : ProxyStatus()
    data object WaitingForNetwork : ProxyStatus()
    data class CaptchaRequired(val session: CaptchaSession) : ProxyStatus()
    data class Error(val message: String) : ProxyStatus()
}

/**
 * Централизованное состояние прокси-сервиса.
 * Публичный API — только read-only Flow, мутация через явные методы.
 */
object ProxyServiceState {

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private val _status = MutableStateFlow<ProxyStatus>(ProxyStatus.Idle)
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    private val _proxyFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val proxyFailed: SharedFlow<Unit> = _proxyFailed.asSharedFlow()

    private val _captchaSession = MutableStateFlow<CaptchaSession?>(null)
    val captchaSession: StateFlow<CaptchaSession?> = _captchaSession.asStateFlow()

    private val _clientConfigSnapshot = MutableStateFlow<com.wireturn.app.data.ClientConfig?>(null)
    val clientConfigSnapshot: StateFlow<com.wireturn.app.data.ClientConfig?> = _clientConfigSnapshot.asStateFlow()

    private val _profileNameSnapshot = MutableStateFlow<String?>(null)
    val profileNameSnapshot: StateFlow<String?> = _profileNameSnapshot.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    private val _isChangingProfile = MutableStateFlow(false)
    val isChangingProfile: StateFlow<Boolean> = _isChangingProfile.asStateFlow()

    // Derived flows for backward compatibility and specialized logic
    val isRunning: StateFlow<Boolean> = _status.map { it !is ProxyStatus.Idle }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    
    val isWorking: StateFlow<Boolean> = _status.map { it is ProxyStatus.Connected || it is ProxyStatus.Suppressed }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun setStatus(newStatus: ProxyStatus) {
        if (newStatus is ProxyStatus.Idle) {
            _clientConfigSnapshot.value = null
            _profileNameSnapshot.value = null
            _statusText.value = null
            _captchaSession.value = null
        }
        if (newStatus is ProxyStatus.CaptchaRequired) {
            _captchaSession.value = newStatus.session
        }
        _status.value = newStatus
    }

    fun setRestarting(value: Boolean) {
        _isRestarting.value = value
    }

    fun setChangingProfile(value: Boolean) {
        _isChangingProfile.value = value
    }

    fun setStatusText(text: String?) {
        _statusText.value = text
    }

    fun setClientConfigSnapshot(config: com.wireturn.app.data.ClientConfig?) {
        _clientConfigSnapshot.value = config
    }

    fun setProfileNameSnapshot(name: String?) {
        _profileNameSnapshot.value = name
    }

    fun emitFailed() {
        _proxyFailed.tryEmit(Unit)
        setStatus(ProxyStatus.Error("Proxy failed"))
    }

    fun setCaptchaSession(session: CaptchaSession?) {
        _captchaSession.value = session
        if (session != null) {
            setStatus(ProxyStatus.CaptchaRequired(session))
        }
    }
}
