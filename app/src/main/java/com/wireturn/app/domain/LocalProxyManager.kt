package com.wireturn.app.domain

import android.content.Context
import com.wireturn.app.R
import com.wireturn.app.ProxyService
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.ProxyStatus
import com.wireturn.app.viewmodel.ProxyState
import com.wireturn.app.data.ClientConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocalProxyManager(private val context: Context) {

    private val _proxyState = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resetJob: kotlinx.coroutines.Job? = null

    suspend fun observeProxyLifecycle() {
        ProxyServiceState.proxyFailed.collect {
            setErrorWithAutoReset(context.getString(R.string.error_proxy_crashed, ProxyService.MAX_RESTARTS))
        }
    }

    suspend fun observeCaptchaEvents() {
        ProxyServiceState.status.collect { status ->
            if (status is ProxyStatus.CaptchaRequired) {
                _proxyState.value = ProxyState.CaptchaRequired(status.session.url, status.session.sessionId)
            } else if (_proxyState.value is ProxyState.CaptchaRequired) {
                syncStateWithService()
            }
        }
    }

    suspend fun observeProxyServiceStatus() {
        ProxyServiceState.status.collect { status ->
            when (status) {
                is ProxyStatus.Error -> {
                    setErrorWithAutoReset(status.message)
                }
                is ProxyStatus.Idle -> {
                    // Если сервис остановился, но у нас висит ошибка, не сбрасываем её сразу.
                    // Она сбросится сама через 4 секунды (resetJob) или при новом запуске.
                    if (_proxyState.value !is ProxyState.Error) {
                        syncStateWithService()
                    }
                }
                else -> {
                    // Для всех остальных состояний (Starting, Connecting, Connected и т.д.)
                    // синхронизируем состояние немедленно.
                    syncStateWithService()
                }
            }
        }
    }

    private fun syncStateWithService() {
        _proxyState.value = when (val status = ProxyServiceState.status.value) {
            is ProxyStatus.Idle -> ProxyState.Idle
            is ProxyStatus.Starting -> ProxyState.Starting
            is ProxyStatus.Connecting -> ProxyState.Connecting
            is ProxyStatus.Connected -> ProxyState.Connected
            is ProxyStatus.Suppressed -> ProxyState.Suppressed
            is ProxyStatus.WaitingForNetwork -> ProxyState.WaitingForNetwork
            is ProxyStatus.CaptchaRequired -> ProxyState.CaptchaRequired(status.session.url, status.session.sessionId)
            is ProxyStatus.Error -> ProxyState.Error(status.message)
        }
    }

    fun syncInitialState() {
        syncStateWithService()
    }

    suspend fun startProxy(cfg: ClientConfig, forceRestart: Boolean = false) {
        val currentStatus = ProxyServiceState.status.value
        // Разрешаем запуск, если сервис простаивает ИЛИ находится в состоянии ошибки/ожидания сети.
        // Это позволяет пользователю нажать "Retry" без ожидания.
        if (!forceRestart && currentStatus !is ProxyStatus.Idle && 
            currentStatus !is ProxyStatus.Error && currentStatus !is ProxyStatus.WaitingForNetwork) return
        
        // Сбрасываем локальное состояние ошибки и таймер авто-сброса перед новым запуском
        resetJob?.cancel()
        if (_proxyState.value is ProxyState.Error) {
            _proxyState.value = ProxyState.Idle
        }

        ProxyService.start(context, cfg)

        val result = withTimeoutOrNull(20_000L) {
            ProxyServiceState.status
                .dropWhile { it is ProxyStatus.Idle || it is ProxyStatus.Starting }
                .first()
        }

        if (_proxyState.value is ProxyState.Error) return

        when (result) {
            null -> {
                ProxyService.stop(context)
                setErrorWithAutoReset(context.getString(R.string.error_proxy_not_started))
            }
            is ProxyStatus.Error -> {
                // Если сервис вернул ошибку (например, Jitsi недоступен),
                // останавливаем его и показываем ошибку в UI.
                ProxyService.stop(context)
                setErrorWithAutoReset(result.message)
            }

            else -> {
                syncStateWithService()
            }
        }
    }

    fun stopProxy() {
        ProxyService.stop(context)
    }

    fun dismissCaptcha() {
        ProxyServiceState.setCaptchaSession(null)
        syncStateWithService()
    }

    fun setErrorWithAutoReset(message: String) {
        resetJob?.cancel()
        _proxyState.value = ProxyState.Error(message)
        resetJob = scope.launch {
            delay(4_000)
            if (_proxyState.value is ProxyState.Error) syncStateWithService()
        }
    }

    fun clearState() {
        _proxyState.value = ProxyState.Idle
    }

    fun destroy() {
        scope.cancel()
    }
}
