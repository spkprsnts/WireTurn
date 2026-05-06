package com.wireturn.app.domain

import android.content.Context
import android.net.Uri
import com.wireturn.app.R
import com.wireturn.app.AppLogsState
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
            is ProxyStatus.CaptchaRequired -> ProxyState.CaptchaRequired(status.session.url, status.session.sessionId)
            is ProxyStatus.Error -> ProxyState.Error(status.message)
        }
    }

    fun syncInitialState() {
        syncStateWithService()
    }

    suspend fun startProxy(cfg: ClientConfig) {
        if (ProxyServiceState.isRunning.value) return
        if (_proxyState.value is ProxyState.Error) _proxyState.value = ProxyState.Idle

        ProxyService.start(context, cfg)

        val result = withTimeoutOrNull(20_000L) {
            ProxyServiceState.status
                .dropWhile { it is ProxyStatus.Idle || it is ProxyStatus.Starting }
                .first()
        }

        if (_proxyState.value is ProxyState.Error) return

        if (result == null) {
            ProxyService.stop(context)
            setErrorWithAutoReset(context.getString(R.string.error_proxy_not_started))
        } else if (result is ProxyStatus.Error) {
            ProxyService.stop(context)
            setErrorWithAutoReset(result.message)
        } else {
            syncStateWithService()
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

    suspend fun setCustomKernel(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val maxSize = 100L * 1024 * 1024 // 100 MB
            val elfMagic = byteArrayOf(0x7F, 0x45, 0x4C, 0x46) // \x7FELF

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext context.getString(R.string.error_file_open)

            val header = ByteArray(4)
            val headerRead = inputStream.read(header)
            inputStream.close()

            if (headerRead < 4 || !header.contentEquals(elfMagic)) {
                return@withContext context.getString(R.string.error_not_elf)
            }

            val dest = File(context.filesDir, "custom_vkturn")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }

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
