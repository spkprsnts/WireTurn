package com.wireturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wireturn.app.ProxyService
import com.wireturn.app.WireproxyService
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.WireproxyServiceState
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.data.WgConfig
import com.wireturn.app.domain.AppUpdater
import com.wireturn.app.domain.LocalProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.system.measureTimeMillis

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val proxyManager = LocalProxyManager(application)
    private val appUpdater = AppUpdater(application)

    val proxyState: StateFlow<ProxyState> = proxyManager.proxyState
    val logs: StateFlow<List<String>> = ProxyServiceState.logs
    val customKernelExists: StateFlow<Boolean> = proxyManager.customKernelExists
    val updateState: StateFlow<UpdateState> = appUpdater.state

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _onboardingDone = MutableStateFlow(false)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicTheme = MutableStateFlow(true)
    val dynamicTheme: StateFlow<Boolean> = _dynamicTheme.asStateFlow()

    private val _clientConfig = MutableStateFlow(ClientConfig())
    val clientConfig: StateFlow<ClientConfig> = _clientConfig.asStateFlow()

    private val _wgConfig = MutableStateFlow(WgConfig())
    val wgConfig: StateFlow<WgConfig> = _wgConfig.asStateFlow()

    // Custom kernel
    private val _kernelError = MutableStateFlow<String?>(null)
    val kernelError: StateFlow<String?> = _kernelError.asStateFlow()

    private val _wireproxyPing = MutableStateFlow<PingResult?>(null)
    val wireproxyPing: StateFlow<PingResult?> = _wireproxyPing.asStateFlow()

    private val _wireproxyTransfer = MutableStateFlow<TransferResult?>(null)
    val wireproxyTransfer: StateFlow<TransferResult?> = _wireproxyTransfer.asStateFlow()

    private val _isHomeScreenActive = MutableStateFlow(false)

    private var pingJob: Job? = null
    private var metricsJob: Job? = null

    sealed class PingResult {
        object Loading : PingResult()
        data class Success(val ms: Long) : PingResult()
        object Error : PingResult()
    }

    data class TransferResult(val rx: Long, val tx: Long)

    init {
        viewModelScope.launch {
            val done = prefs.onboardingDoneFlow.first()
            val theme = prefs.themeModeFlow.first()
            val dynamic = prefs.dynamicThemeFlow.first()
            val config = prefs.clientConfigFlow.first()

            _onboardingDone.value = done
            _themeMode.value = theme
            _dynamicTheme.value = dynamic
            _clientConfig.value = config

            _isInitialized.value = true

            viewModelScope.launch {
                delay(2000)
                appUpdater.checkForUpdate(silent = true)
            }

            launch { prefs.onboardingDoneFlow.collect { _onboardingDone.value = it } }
            launch { prefs.themeModeFlow.collect { _themeMode.value = it } }
            launch { prefs.dynamicThemeFlow.collect { _dynamicTheme.value = it } }
            launch { prefs.clientConfigFlow.collect { _clientConfig.value = it } }
            launch { prefs.wgConfigFlow.collect { _wgConfig.value = it } }
        }
        viewModelScope.launch { proxyManager.observeProxyLifecycle() }
        viewModelScope.launch { proxyManager.observeProxyServiceStatus() }
        viewModelScope.launch { proxyManager.observeCaptchaEvents() }
        viewModelScope.launch { proxyManager.observeProxyServiceWorking() }
        viewModelScope.launch {
            WireproxyServiceState.state.collect { state ->
                val isRunning = state is WireproxyState.Running
                if (isRunning) {
                    checkWireproxyPing()
                    startMetricsPoller()
                } else {
                    stopMetricsPoller()
                }
            }
        }
        proxyManager.syncInitialState()
    }

    fun setHomeScreenActive(active: Boolean) {
        _isHomeScreenActive.value = active
    }

    private fun startMetricsPoller() {
        metricsJob?.cancel()
        metricsJob = viewModelScope.launch {
            while (true) {
                if (_isHomeScreenActive.value) {
                    val port = WireproxyServiceState.metricsPort.value
                    if (port != null) {
                        updateMetrics(port)
                    }
                }
                delay(3000)
            }
        }
    }

    private fun stopMetricsPoller() {
        metricsJob?.cancel()
        metricsJob = null
        _wireproxyTransfer.value = null
    }

    private suspend fun updateMetrics(port: Int) {
        withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("http://127.0.0.1:$port/metrics")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                val content = connection.inputStream.bufferedReader().use { it.readText() }

                var rx = 0L
                var tx = 0L

                content.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#")) return@forEach

                    if (trimmed.startsWith("rx_bytes")) {
                        rx += trimmed.split("=").lastOrNull()?.toLongOrNull() ?: 0L
                    } else if (trimmed.startsWith("tx_bytes")) {
                        tx += trimmed.split("=").lastOrNull()?.toLongOrNull() ?: 0L
                    }
                }
                _wireproxyTransfer.value = TransferResult(rx, tx)
            } catch (_: Exception) {
                // pass
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        proxyManager.destroy()
    }

    fun setDynamicTheme(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicTheme(enabled) }
    }
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    val vkLinkHistory: StateFlow<List<String>> = prefs.vkLinkHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val serverAddressHistory: StateFlow<List<String>> = prefs.serverAddressHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _privacyMode = MutableStateFlow(false)
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()

    fun setPrivacyMode(enabled: Boolean) { _privacyMode.value = enabled }

    fun startProxy() {
        viewModelScope.launch {
            val cfg = clientConfig.value
            prefs.addVkLinkToHistory(cfg.vkLink)
            prefs.addServerAddressToHistory(cfg.serverAddress)
            proxyManager.startProxy(cfg)
        }
    }

    fun stopProxy() {
        proxyManager.stopProxy()
    }

    fun dismissCaptcha() { proxyManager.dismissCaptcha() }
    fun clearLogs() { ProxyServiceState.clearLogs() }
    fun saveClientConfig(config: ClientConfig) { viewModelScope.launch { prefs.saveClientConfig(config) } }
    fun removeVkLinkFromHistory(link: String) { viewModelScope.launch { prefs.removeVkLinkFromHistory(link) } }
    fun removeServerAddressFromHistory(address: String) { viewModelScope.launch { prefs.removeServerAddressFromHistory(address) } }
    fun setOnboardingDone() { viewModelScope.launch { prefs.setOnboardingDone(true) } }

    fun checkWireproxyPing() {
        val socksAddr = _wgConfig.value.socks5BindAddress
        if (!isValidHostPort(socksAddr) || socksAddr.isBlank()) return

        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            _wireproxyPing.value = PingResult.Loading
            repeat(5) { attempt ->
                if (WireproxyServiceState.state.value != WireproxyState.Running) {
                    _wireproxyPing.value = null
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    try {
                        val parts = socksAddr.split(":")
                        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(parts[0], parts[1].toInt()))
                        val time = measureTimeMillis {
                            Socket(proxy).use { it.connect(InetSocketAddress("1.1.1.1", 53), 2000) }
                        }
                        PingResult.Success(time)
                    } catch (_: Exception) { null }
                }
                if (result is PingResult.Success) {
                    _wireproxyPing.value = result
                    return@launch
                }
                if (attempt < 2) delay(500)
            }
            _wireproxyPing.value = PingResult.Error
        }
    }

    fun setCustomKernel(uri: Uri) { viewModelScope.launch { _kernelError.value = proxyManager.setCustomKernel(uri) } }
    fun clearCustomKernel() { proxyManager.clearCustomKernel() }
    fun clearKernelError() { _kernelError.value = null }
    fun checkForUpdate() { viewModelScope.launch { appUpdater.checkForUpdate(silent = false) } }
    fun downloadUpdate() { viewModelScope.launch { appUpdater.downloadUpdate() } }
    fun installUpdate() { appUpdater.installUpdate() }
    fun resetUpdateState() { appUpdater.resetState() }

    fun updateWgConfig(config: WgConfig) {
        _wgConfig.value = config
        viewModelScope.launch {
            prefs.saveWgConfig(config)
        }
    }

    fun updateWgConfigText(text: String) {
        val parsed = WgConfig.parse(text)
        if (_wgConfig.value != parsed) {
            _wgConfig.value = parsed
            viewModelScope.launch {
                prefs.saveWgConfig(parsed)
            }
        }
    }

    private fun isValidHostPort(address: String): Boolean {
        return com.wireturn.app.ui.ValidatorUtils.isValidHostPort(address)
    }

    fun resetAllSettings(context: Context) {
        viewModelScope.launch {
            if (ProxyServiceState.isRunning.value) {
                context.stopService(Intent(context, ProxyService::class.java))
                context.stopService(Intent(context, WireproxyService::class.java))
            }
            prefs.resetAll()
            proxyManager.clearState()
            ProxyServiceState.clearLogs()
            _wgConfig.value = WgConfig()
            val intent = (context as? android.app.Activity)?.intent
                ?: Intent(context, com.wireturn.app.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}

