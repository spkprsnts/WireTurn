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
 *
 * needsResultToken: true only for qWDTT (see CoreService.handleQwdttLog) - the solved token has
 * to be forwarded back to the core over stdin, so the dialog must reliably capture it even across
 * a same-webview navigation (VK's own completion redirect). FreeTurn doesn't need the token at
 * all - its own captcha proxy captures success server-side - so it stays on plain polling and
 * never gets the native JS bridge (see CaptchaWebViewDialog's useNativeBridge doc).
 */
data class CaptchaSession(val url: String, val sessionId: Long, val needsResultToken: Boolean = false)

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

    // Solved-captcha token, tagged by sessionId so a kernel that needs the token back (qWDTT,
    // over stdin) can match it to the request it actually made - other kernels (FreeTurn) just
    // detect success from their own log output and never collect this.
    private val _captchaResult = MutableSharedFlow<Pair<Long, String>>(extraBufferCapacity = 1)
    val captchaResult: SharedFlow<Pair<Long, String>> = _captchaResult.asSharedFlow()

    private val _session = MutableStateFlow<RunningSession?>(null)
    val session: StateFlow<RunningSession?> = _session.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    // Non-null only while the watchdog is backing off between crash retries - this is the ONLY
    // thing "Restarting" means. Hot-reload, dual-route wake, profile/kernel switch and
    // network-loss recovery are deliberate, not a failure being retried, so they just show
    // Connecting/Starting like any other connection attempt.
    data class RestartAttempt(val attempt: Int, val max: Int)

    private val _restartAttempt = MutableStateFlow<RestartAttempt?>(null)
    val restartAttempt: StateFlow<RestartAttempt?> = _restartAttempt.asStateFlow()

    val isRunning: StateFlow<Boolean> = _status.map { it !is CoreStatus.Idle }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    val isWorking: StateFlow<Boolean> = _status.map { it is CoreStatus.Connected || it is CoreStatus.Suppressed }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun setStatus(newStatus: CoreStatus) {
        if (newStatus is CoreStatus.Idle || newStatus is CoreStatus.Error ||
            newStatus is CoreStatus.Connected || newStatus is CoreStatus.Suppressed) {
            _restartAttempt.value = null
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

    fun setRestartAttempt(attempt: RestartAttempt?) {
        _restartAttempt.value = attempt
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

    fun submitCaptchaResult(sessionId: Long, token: String) {
        _captchaResult.tryEmit(sessionId to token)
    }

    fun setCaptchaSession(session: CaptchaSession?) {
        _captchaSession.value = session
        if (session != null) {
            setStatus(CoreStatus.CaptchaRequired(session))
        } else if (_status.value is CoreStatus.CaptchaRequired) {
            setStatus(CoreStatus.Connecting)
        }
    }
}
