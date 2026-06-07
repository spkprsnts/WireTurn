package com.wireturn.app.domain

import android.content.Context
import com.wireturn.app.CoreService
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.viewmodel.CoreState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class CoreManager(private val context: Context) {

    private val _coreState = MutableStateFlow<CoreState>(CoreState.Idle)
    val coreState: StateFlow<CoreState> = _coreState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resetJob: kotlinx.coroutines.Job? = null

    suspend fun observeCoreLifecycle() {
        CoreServiceState.coreFailed.collect {
            setErrorWithAutoReset(context.getString(R.string.error_core_crashed, CoreService.MAX_RESTARTS))
        }
    }

    suspend fun observeCaptchaEvents() {
        CoreServiceState.status.collect { status ->
            if (status is CoreStatus.CaptchaRequired) {
                _coreState.value = CoreState.CaptchaRequired(status.session.url, status.session.sessionId)
            } else if (_coreState.value is CoreState.CaptchaRequired) {
                syncStateWithService()
            }
        }
    }

    suspend fun observeCoreServiceStatus() {
        CoreServiceState.status.collect { status ->
            when (status) {
                is CoreStatus.Error -> {
                    setErrorWithAutoReset(status.message)
                }
                is CoreStatus.Starting -> {
                    // New attempt always resets error
                    resetJob?.cancel()
                    syncStateWithService()
                }
                else -> {
                    // Ignore transient/idle statuses if we are currently showing an error.
                    // The error will be cleared by resetJob or a new start attempt.
                    if (_coreState.value !is CoreState.Error) {
                        syncStateWithService()
                    }
                }
            }
        }
    }

    private fun syncStateWithService() {
        _coreState.value = when (val status = CoreServiceState.status.value) {
            is CoreStatus.Idle -> CoreState.Idle
            is CoreStatus.Starting -> CoreState.Starting
            is CoreStatus.Connecting -> CoreState.Connecting
            is CoreStatus.Connected -> CoreState.Connected
            is CoreStatus.Suppressed -> CoreState.Suppressed
            is CoreStatus.Stopping -> CoreState.Stopping
            is CoreStatus.WaitingForNetwork -> CoreState.WaitingForNetwork
            is CoreStatus.CaptchaRequired -> CoreState.CaptchaRequired(status.session.url, status.session.sessionId)
            is CoreStatus.Error -> CoreState.Error(status.message)
        }
    }

    fun syncInitialState() {
        syncStateWithService()
    }

    suspend fun startCore(cfg: ClientConfig, forceRestart: Boolean = false) {
        val currentStatus = CoreServiceState.status.value
        // Разрешаем запуск, если сервис простаивает ИЛИ находится в состоянии ошибки/ожидания сети.
        // Это позволяет пользователю нажать "Retry" без ожидания.
        if (!forceRestart && currentStatus !is CoreStatus.Idle &&
            currentStatus !is CoreStatus.Error && currentStatus !is CoreStatus.WaitingForNetwork) return
        
        // Сбрасываем локальное состояние ошибки и таймер авто-сброса перед новым запуском
        resetJob?.cancel()
        if (_coreState.value is CoreState.Error) {
            _coreState.value = CoreState.Idle
        }

        CoreService.start(context, cfg)

        val result = withTimeoutOrNull(20_000L.milliseconds) {
            CoreServiceState.status
                .dropWhile { it is CoreStatus.Idle || it is CoreStatus.Starting || it is CoreStatus.Stopping }
                .first()
        }

        if (_coreState.value is CoreState.Error) return

        when (result) {
            null -> {
                CoreService.stop(context)
                setErrorWithAutoReset(context.getString(R.string.error_core_not_started))
            }
            is CoreStatus.Error -> {
                // Если сервис вернул ошибку (например, Jitsi недоступен),
                // останавливаем его и показываем ошибку в UI.
                CoreService.stop(context)
                setErrorWithAutoReset(result.message)
            }

            else -> {
                syncStateWithService()
            }
        }
    }

    fun stopCore() {
        CoreService.stop(context)
    }

    fun dismissCaptcha() {
        CoreServiceState.setCaptchaSession(null)
        syncStateWithService()
    }

    fun setErrorWithAutoReset(message: String) {
        resetJob?.cancel()
        _coreState.value = CoreState.Error(message)
        resetJob = scope.launch {
            delay(4_000.milliseconds)
            if (_coreState.value is CoreState.Error) {
                // Force reset local state to Idle
                _coreState.value = CoreState.Idle
                // If the global status is still an Error, clear it too
                if (CoreServiceState.status.value is CoreStatus.Error) {
                    CoreServiceState.setStatus(CoreStatus.Idle)
                }
            }
        }
    }

    fun clearState() {
        _coreState.value = CoreState.Idle
    }

    fun destroy() {
        scope.cancel()
    }
}
