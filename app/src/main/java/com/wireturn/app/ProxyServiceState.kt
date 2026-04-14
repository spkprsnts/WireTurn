package com.wireturn.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Сессия ручной капчи. sessionId позволяет диалогу различать соседние
 * капча-сессии с одинаковым URL и пересоздавать WebView через `key(sessionId)`.
 */
data class CaptchaSession(val url: String, val sessionId: Long)

/**
 * Централизованное состояние прокси-сервиса.
 * Публичный API — только read-only Flow, мутация через явные методы.
 */
object ProxyServiceState {

    private const val MAX_LOG_LINES = 200
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _proxyFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val proxyFailed: SharedFlow<Unit> = _proxyFailed.asSharedFlow()

    private val _startupResult = MutableStateFlow<StartupResult?>(null)
    val startupResult: StateFlow<StartupResult?> = _startupResult.asStateFlow()

    private val _captchaSession = MutableStateFlow<CaptchaSession?>(null)
    val captchaSession: StateFlow<CaptchaSession?> = _captchaSession.asStateFlow()

    private val _runningConfig = MutableStateFlow<com.wireturn.app.data.ClientConfig?>(null)
    val runningConfig: StateFlow<com.wireturn.app.data.ClientConfig?> = _runningConfig.asStateFlow()

    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    fun setRunning(value: Boolean) {
        _isRunning.value = value
        if (!value) {
            _runningConfig.value = null
            _statusText.value = null
        }
    }

    fun setStatusText(text: String?) {
        _statusText.value = text
    }

    fun setRunningConfig(config: com.wireturn.app.data.ClientConfig?) {
        _runningConfig.value = config
    }

    fun setStartupResult(result: StartupResult?) {
        _startupResult.value = result
    }

    fun setWorking(value: Boolean) {
        _isWorking.value = value
    }

    fun emitFailed() {
        _proxyFailed.tryEmit(Unit)
    }

    fun addLog(msg: String) {
        _logs.update { current ->
            val next = current + msg
            if (next.size > MAX_LOG_LINES) next.drop(next.size - MAX_LOG_LINES) else next
        }
    }

    fun setCaptchaSession(session: CaptchaSession?) {
        _captchaSession.value = session
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}

