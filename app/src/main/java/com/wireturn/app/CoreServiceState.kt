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

sealed class CoreStatus {
    data object Idle : CoreStatus()
    data object Starting : CoreStatus()
    data object Connecting : CoreStatus()
    data object Connected : CoreStatus()
    data object Suppressed : CoreStatus()
    data object Stopping : CoreStatus()
    data object WaitingForNetwork : CoreStatus()
    data class CaptchaRequired(val session: CaptchaSession) : CoreStatus()
    data class Error(val message: String) : CoreStatus()
}

/**
 * Централизованное состояние прокси-сервиса.
 * Публичный API — только read-only Flow, мутация через явные методы.
 */
object CoreServiceState {

    data class RunningSession(
        val clientConfig: ClientConfig,
        val profileName: String
    )

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private val _status = MutableStateFlow<CoreStatus>(CoreStatus.Idle)
    val status: StateFlow<CoreStatus> = _status.asStateFlow()

    private val _coreFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val coreFailed: SharedFlow<Unit> = _coreFailed.asSharedFlow()

    private val _captchaSession = MutableStateFlow<CaptchaSession?>(null)
    val captchaSession: StateFlow<CaptchaSession?> = _captchaSession.asStateFlow()

    private val _session = MutableStateFlow<RunningSession?>(null)
    val session: StateFlow<RunningSession?> = _session.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    private val _isRestarting = MutableStateFlow(false)
    val isRestarting: StateFlow<Boolean> = _isRestarting.asStateFlow()

    val isRunning: StateFlow<Boolean> = _status.map { it !is CoreStatus.Idle }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    val isWorking: StateFlow<Boolean> = _status.map { it is CoreStatus.Connected || it is CoreStatus.Suppressed }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun setStatus(newStatus: CoreStatus) {
        if (newStatus is CoreStatus.Idle || newStatus is CoreStatus.Error || newStatus is CoreStatus.Connected) {
            _isRestarting.value = false
        }
        if (newStatus is CoreStatus.Idle) {
            _session.value = null
            _statusText.value = null
            _captchaSession.value = null
        }
        if (newStatus is CoreStatus.CaptchaRequired) {
            _captchaSession.value = newStatus.session
        }
        _status.value = newStatus
    }

    fun setRestarting(value: Boolean) {
        _isRestarting.value = value
    }

    fun setStatusText(text: String?) {
        _statusText.value = text
    }

    fun setSession(session: RunningSession?) {
        _session.value = session
    }

    fun emitFailed(message: String) {
        _coreFailed.tryEmit(Unit)
        setStatus(CoreStatus.Error(message))
    }

    fun setCaptchaSession(session: CaptchaSession?) {
        _captchaSession.value = session
        if (session != null) {
            setStatus(CoreStatus.CaptchaRequired(session))
        }
    }
}
