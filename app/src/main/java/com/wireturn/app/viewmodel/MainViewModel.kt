package com.wireturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wireturn.app.ProxyService
import com.wireturn.app.XrayService
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.XrayServiceState
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.DCType
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
import kotlin.system.measureTimeMillis

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val proxyManager = LocalProxyManager(application)
    private val appUpdater = AppUpdater(application)

    val proxyState: StateFlow<ProxyState> = proxyManager.proxyState
    val logs: StateFlow<List<String>> = ProxyServiceState.logs
    val customKernelExists: StateFlow<Boolean> = proxyManager.customKernelExists
    val customKernelLastModified: StateFlow<Long?> = proxyManager.customKernelLastModified
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

    private val _batteryNotificationDismissed = MutableStateFlow(false)
    val batteryNotificationDismissed: StateFlow<Boolean> = _batteryNotificationDismissed.asStateFlow()

    private val _wgConfig = MutableStateFlow(WgConfig())
    val wgConfig: StateFlow<WgConfig> = _wgConfig.asStateFlow()

    private val _xrayConfig = MutableStateFlow(com.wireturn.app.data.XrayConfig())
    val xrayConfig: StateFlow<com.wireturn.app.data.XrayConfig> = _xrayConfig.asStateFlow()

    private val _vlessConfig = MutableStateFlow(com.wireturn.app.data.VlessConfig())
    val vlessConfig: StateFlow<com.wireturn.app.data.VlessConfig> = _vlessConfig.asStateFlow()

    // Custom kernel
    private val _kernelError = MutableStateFlow<String?>(null)
    val kernelError: StateFlow<String?> = _kernelError.asStateFlow()

    private val _proxyPing = MutableStateFlow<PingResult?>(null)
    val proxyPing: StateFlow<PingResult?> = _proxyPing.asStateFlow()

    private val _proxyTransfer = MutableStateFlow<TransferResult?>(null)
    val proxyTransfer: StateFlow<TransferResult?> = _proxyTransfer.asStateFlow()

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
            val batteryDismissed = prefs.batteryNotificationDismissedFlow.first()

            val wgConfig = prefs.wgConfigFlow.first()
            val xrayConfig = prefs.xrayConfigFlow.first()
            val vlessConfig = prefs.vlessConfigFlow.first()

            _onboardingDone.value = done
            _themeMode.value = theme
            _dynamicTheme.value = dynamic
            _clientConfig.value = config
            _batteryNotificationDismissed.value = batteryDismissed
            _wgConfig.value = wgConfig
            _xrayConfig.value = xrayConfig
            _vlessConfig.value = vlessConfig

            _isInitialized.value = true

            viewModelScope.launch {
                delay(2000)
                appUpdater.checkForUpdate(silent = true)
            }

            launch { prefs.onboardingDoneFlow.collect { _onboardingDone.value = it } }
            launch { prefs.themeModeFlow.collect { _themeMode.value = it } }
            launch { prefs.dynamicThemeFlow.collect { _dynamicTheme.value = it } }
            launch { prefs.clientConfigFlow.collect { _clientConfig.value = it } }
            launch { prefs.batteryNotificationDismissedFlow.collect { _batteryNotificationDismissed.value = it } }
            launch { prefs.wgConfigFlow.collect { _wgConfig.value = it } }
            launch { prefs.xrayConfigFlow.collect { _xrayConfig.value = it } }
            launch { prefs.vlessConfigFlow.collect { _vlessConfig.value = it } }
        }

        viewModelScope.launch { proxyManager.observeProxyLifecycle() }
        viewModelScope.launch { proxyManager.observeProxyServiceStatus() }
        viewModelScope.launch { proxyManager.observeCaptchaEvents() }
        viewModelScope.launch { proxyManager.observeProxyServiceWorking() }
        viewModelScope.launch {
            delay(1500)
            XrayServiceState.state.collect { state ->
                val isRunning = state is XrayState.Running
                if (isRunning) {
                    checkProxyPing()
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
                    val port = XrayServiceState.metricsPort.value
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
        _proxyTransfer.value = null
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
                _proxyTransfer.value = TransferResult(rx, tx)
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

    val wbstreamUuidHistory: StateFlow<List<String>> = prefs.wbstreamUuidHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val serverAddressHistory: StateFlow<List<String>> = prefs.serverAddressHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val jazzCredsHistory: StateFlow<List<String>> = prefs.jazzCredsHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val vlessLinkHistory: StateFlow<List<String>> = prefs.vlessLinkHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _privacyMode = MutableStateFlow(false)
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()

    fun setPrivacyMode(enabled: Boolean) { _privacyMode.value = enabled }

    fun startProxy() {
        viewModelScope.launch {
            val cfg = clientConfig.value
            if (cfg.dcMode) {
                when (cfg.dcType) {
                    DCType.SALUTE_JAZZ -> prefs.addJazzCredsToHistory(cfg.jazzCreds)
                    DCType.WB_STREAM -> prefs.addWbstreamUuidToHistory(cfg.wbstreamUuid)
                }
            } else {
                prefs.addVkLinkToHistory(cfg.vkLink)
            }
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
    fun removeWbstreamUuidFromHistory(uuid: String) { viewModelScope.launch { prefs.removeWbstreamUuidFromHistory(uuid) } }
    fun removeServerAddressFromHistory(address: String) { viewModelScope.launch { prefs.removeServerAddressFromHistory(address) } }
    fun removeJazzCredsFromHistory(creds: String) { viewModelScope.launch { prefs.removeJazzCredsFromHistory(creds) } }
    fun removeVlessLinkFromHistory(link: String) { viewModelScope.launch { prefs.removeVlessLinkFromHistory(link) } }
    fun setOnboardingDone() { viewModelScope.launch { prefs.setOnboardingDone(true) } }
    fun setBatteryNotificationDismissed(dismissed: Boolean) { viewModelScope.launch { prefs.setBatteryNotificationDismissed(dismissed) } }

    fun checkProxyPing() {
        val socksAddr = XrayServiceState.runningXrayConfig.value?.connectableAddress ?: return
        if (socksAddr.isBlank() || !isValidHostPort(socksAddr)) return

        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            _proxyPing.value = PingResult.Loading
            repeat(5) { attempt ->
                if (XrayServiceState.state.value != XrayState.Running) {
                    _proxyPing.value = null
                    return@launch
                }
                val result = withContext(Dispatchers.IO) {
                    try {
                        val parts = socksAddr.split(":")
                        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(parts[0], parts[1].toInt()))
                        var time = 0L
                        val isSuccess = try {
                            time = measureTimeMillis {
                                val url = java.net.URL("https://1.1.1.1/")
                                val conn = url.openConnection(proxy) as java.net.HttpURLConnection
                                conn.connectTimeout = 2000
                                conn.readTimeout = 2000
                                conn.instanceFollowRedirects = false
                                conn.responseCode
                            }
                            true
                        } catch (_: Exception) {
                            false
                        }
                        if (isSuccess) PingResult.Success(time) else null
                    } catch (e: Exception) {
                        ProxyServiceState.addLog("[Ping] Error: ${e.message}")
                        null
                    }
                }
                if (result is PingResult.Success) {
                    _proxyPing.value = result
                    return@launch
                }
                if (attempt < 2) delay(500)
            }
            _proxyPing.value = PingResult.Error
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

    fun updateXrayConfig(config: com.wireturn.app.data.XrayConfig) {
        _xrayConfig.value = config
        viewModelScope.launch {
            prefs.saveXrayConfig(config)
        }
    }

    fun updateVlessConfig(config: com.wireturn.app.data.VlessConfig) {
        _vlessConfig.value = config
        viewModelScope.launch {
            prefs.saveVlessConfig(config)
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
                context.stopService(Intent(context, XrayService::class.java))
            }
            prefs.resetAll()
            proxyManager.clearState()
            ProxyServiceState.clearLogs()
            _wgConfig.value = WgConfig()
            _xrayConfig.value = com.wireturn.app.data.XrayConfig()
            val intent = (context as? android.app.Activity)?.intent
                ?: Intent(context, com.wireturn.app.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}

