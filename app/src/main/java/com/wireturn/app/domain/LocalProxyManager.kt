package com.wireturn.app.domain

import android.content.Context
import android.net.Uri
import com.wireturn.app.R
import com.wireturn.app.AppLogsState
import com.wireturn.app.ProxyService
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.StartupResult
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.viewmodel.ProxyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class LocalProxyManager(private val context: Context) {

    private val _proxyState = MutableStateFlow<ProxyState>(ProxyState.Idle)
    val proxyState: StateFlow<ProxyState> = _proxyState.asStateFlow()

    private val _customKernelExists = MutableStateFlow(false)
    val customKernelExists: StateFlow<Boolean> = _customKernelExists.asStateFlow()

    private val _customKernelLastModified = MutableStateFlow<Long?>(null)
    val customKernelLastModified: StateFlow<Long?> = _customKernelLastModified.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resetJob: kotlinx.coroutines.Job? = null

    init {
        val file = File(context.filesDir, "custom_vkturn")
        _customKernelExists.value = file.exists()
        if (file.exists()) {
            _customKernelLastModified.value = file.lastModified()
        }
    }

    suspend fun observeProxyLifecycle() {
        ProxyServiceState.proxyFailed.collect {
            setErrorWithAutoReset(context.getString(R.string.error_proxy_crashed, ProxyService.MAX_RESTARTS))
        }
    }

    suspend fun observeStartupResult() {
        ProxyServiceState.startupResult.collect { result ->
            if (result is StartupResult.Failed) {
                setErrorWithAutoReset(result.message)
            }
        }
    }

    suspend fun observeCaptchaEvents() {
        ProxyServiceState.captchaSession.collect { session ->
            if (session != null) {
                _proxyState.value = ProxyState.CaptchaRequired(session.url, session.sessionId)
            } else if (_proxyState.value is ProxyState.CaptchaRequired) {
                // Капча-сессия закрыта — возвращаемся в рабочее состояние,
                // но только если сервис всё ещё запущен.
                if (ProxyServiceState.isRunning.value) {
                    updateStateAfterSuccess()
                } else {
                    _proxyState.value = ProxyState.Idle
                }
            }
        }
    }

    suspend fun observeProxyServiceStatus() {
        ProxyServiceState.isRunning.collect { running ->
            if (running) {
                // Если сервис запущен, но мы еще в Idle, переходим в Starting.
                if (_proxyState.value is ProxyState.Idle) {
                    _proxyState.value = ProxyState.Starting
                }
            } else {
                // Если сервис НЕ запущен, переводим UI в Idle из любых активных состояний
                val current = _proxyState.value
                if (current !is ProxyState.Idle && current !is ProxyState.Error) {
                    _proxyState.value = ProxyState.Idle
                }
            }
        }
    }

    suspend fun observeProxyServiceWorking() {
        ProxyServiceState.isWorking.collect { working ->
            if (working) {
                // Если сейчас висит ошибка, не перебиваем её сразу (пусть пользователь увидит)
                // Но если это состояние запуска, то переходим в Working
                if (_proxyState.value !is ProxyState.Error && _proxyState.value !is ProxyState.CaptchaRequired) {
                    _proxyState.value = ProxyState.Working
                } else if (_proxyState.value is ProxyState.CaptchaRequired) {
                    // Если капча была решена и пошли данные — переходим в Working
                    _proxyState.value = ProxyState.Working
                }
            } else if (_proxyState.value is ProxyState.Working) {
                // Больше не "Working" — если сервис всё еще запущен, возвращаемся в Running
                if (ProxyServiceState.isRunning.value) {
                    if (!_customKernelExists.value) {
                        _proxyState.value = ProxyState.Running
                    }
                } else {
                    _proxyState.value = ProxyState.Idle
                }
            }
        }
    }

    private fun updateStateAfterSuccess() {
        if (_customKernelExists.value || ProxyServiceState.isWorking.value) {
            _proxyState.value = ProxyState.Working
        } else {
            _proxyState.value = ProxyState.Running
        }
    }

    fun syncInitialState() {
        val captcha = ProxyServiceState.captchaSession.value
        if (captcha != null) {
            _proxyState.value = ProxyState.CaptchaRequired(captcha.url, captcha.sessionId)
        } else if (ProxyServiceState.isWorking.value) {
            _proxyState.value = ProxyState.Working
        } else if (ProxyServiceState.isRunning.value) {
            updateStateAfterSuccess()
        }
    }

    suspend fun startProxy(cfg: ClientConfig) {
        if (ProxyServiceState.isRunning.value) return
        if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle

        _proxyState.value = ProxyState.Starting

        // Сбрасываем старые результаты перед запуском, чтобы не подхватить их из Flow
        ProxyServiceState.setStartupResult(null)
        ProxyServiceState.setWorking(false)

        ProxyService.start(context, cfg)

        val result = withTimeoutOrNull(20_000L) {
            merge(
                ProxyServiceState.startupResult.filterNotNull(),
                ProxyServiceState.isRunning.dropWhile { !it }.filter { !it }.map { null }
            ).first()
        }

        if (_proxyState.value is ProxyState.Error) return

        when (result) {
            null -> {
                ProxyService.stop(context)
                setErrorWithAutoReset(context.getString(R.string.error_proxy_not_started))
            }
            is StartupResult.Failed -> {
                ProxyService.stop(context)
                setErrorWithAutoReset(result.message)
            }
            is StartupResult.Success -> {
                updateStateAfterSuccess()
            }
        }
    }

    fun stopProxy() {
        ProxyService.stop(context)
        _proxyState.value = ProxyState.Idle
    }

    fun dismissCaptcha() {
        ProxyServiceState.setCaptchaSession(null)
        if (_proxyState.value is ProxyState.CaptchaRequired) {
            updateStateAfterSuccess()
        }
    }

    fun setErrorWithAutoReset(message: String) {
        resetJob?.cancel()
        _proxyState.value = ProxyState.Error(message)
        resetJob = scope.launch {
            delay(4_000)
            if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle
        }
    }

    suspend fun setCustomKernel(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val maxSize = 100L * 1024 * 1024 // 100 MB
            val elfMagic = byteArrayOf(0x7F, 0x45, 0x4C, 0x46) // \x7FELF

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext context.getString(R.string.error_file_open)

            // Читаем первые 4 байта для проверки ELF-магии
            val header = ByteArray(4)
            val headerRead = inputStream.read(header)
            inputStream.close()

            if (headerRead < 4 || !header.contentEquals(elfMagic)) {
                return@withContext context.getString(R.string.error_not_elf)
            }

            // Копируем файл
            val dest = File(context.filesDir, "custom_vkturn")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }

            // Пытаемся получить дату изменения оригинала
            val originalLastModified = try {
                context.contentResolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (index != -1) cursor.getLong(index) else null
                    } else null
                }
            } catch (_: Exception) { null }

            if (originalLastModified != null && originalLastModified > 0) {
                dest.setLastModified(originalLastModified)
            }

            if (dest.length() == 0L) {
                dest.delete()
                return@withContext context.getString(R.string.error_file_empty)
            }
            if (dest.length() > maxSize) {
                dest.delete()
                return@withContext context.getString(R.string.error_file_too_large, maxSize / 1024 / 1024)
            }

            dest.setExecutable(true, false)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", dest.absolutePath)).waitFor()
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { 
                _customKernelExists.value = true
                _customKernelLastModified.value = dest.lastModified()
            }
            AppLogsState.addLog(context.getString(R.string.log_custom_kernel_installed, dest.length() / 1024))
            null
        } catch (e: Exception) {
            AppLogsState.addLog(context.getString(R.string.error_kernel_install_failed, e.message ?: ""))
            context.getString(R.string.error_format_short, e.message ?: "")
        }
    }

    fun clearCustomKernel() {
        File(context.filesDir, "custom_vkturn").delete()
        _customKernelExists.value = false
        _customKernelLastModified.value = null
        AppLogsState.addLog(context.getString(R.string.log_custom_kernel_deleted))
    }

    fun clearState() {
        _proxyState.value = ProxyState.Idle
        File(context.filesDir, "custom_vkturn").delete()
        _customKernelExists.value = false
        _customKernelLastModified.value = null
    }

    fun destroy() {
        scope.cancel()
    }
}

