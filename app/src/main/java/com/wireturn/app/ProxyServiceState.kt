package com.wireturn.app

import com.wireturn.app.data.ClientConfig
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

    data class RunningSession(
        val clientConfig: ClientConfig,
        val profileName: String
    )

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private val _status = MutableStateFlow<ProxyStatus>(ProxyStatus.Idle)
    val status: StateFlow<ProxyStatus> = _status.asStateFlow()

    private val _proxyFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val proxyFailed: SharedFlow<Unit> = _proxyFailed.asSharedFlow()

    private val _captchaSession = MutableStateFlow<CaptchaSession?>(null)
    val captchaSession: StateFlow<CaptchaSession?> = _captchaSession.asStateFlow()

    private val _session = MutableStateFlow<RunningSession?>(null)
    val session: StateFlow<RunningSession?> = _session.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _isChangingProfile = MutableStateFlow(false)
    val isChangingProfile: StateFlow<Boolean> = _isChangingProfile.asStateFlow()

    val isRunning: StateFlow<Boolean> = _status.map { it !is ProxyStatus.Idle }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    val isWorking: StateFlow<Boolean> = _status.map { it is ProxyStatus.Connected || it is ProxyStatus.Suppressed }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun setStatus(newStatus: ProxyStatus) {
        if (newStatus is ProxyStatus.Idle) {
            _session.value = null
            _statusText.value = null
            _captchaSession.value = null
        }
        if (newStatus is ProxyStatus.CaptchaRequired) {
            _captchaSession.value = newStatus.session
        }
        _status.value = newStatus
    }

    fun setChangingProfile(value: Boolean) {
        _isChangingProfile.value = value
    }

    fun setStatusText(text: String?) {
        _statusText.value = text
    }

    fun setSession(session: RunningSession?) {
        _session.value = session
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
