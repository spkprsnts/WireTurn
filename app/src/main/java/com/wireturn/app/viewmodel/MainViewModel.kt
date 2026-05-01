package com.wireturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.wireturn.app.AppLogsState
import com.wireturn.app.R
import com.wireturn.app.ProxyService
import com.wireturn.app.XrayService
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.XrayServiceState
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.DCType
import com.wireturn.app.data.GlobalVpnSettings
import com.wireturn.app.data.Profile
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.XraySettings
import com.wireturn.app.domain.AppUpdater
import com.wireturn.app.domain.LocalProxyManager
import com.wireturn.app.domain.ProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.FlowPreview
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.system.measureTimeMillis

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val proxyManager = LocalProxyManager(application)
    private val appUpdater = AppUpdater(application)
    private val profileManager = ProfileManager(prefs, ProcessLifecycleOwner.get().lifecycleScope)

    val proxyState: StateFlow<ProxyState> = proxyManager.proxyState
    val logs: StateFlow<List<String>> = AppLogsState.logs
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

    private val _appsExclusionHintShown = MutableStateFlow(false)
    val appsExclusionHintShown: StateFlow<Boolean> = _appsExclusionHintShown.asStateFlow()

    private val _allowUnstableUpdates = MutableStateFlow(false)
    val allowUnstableUpdates: StateFlow<Boolean> = _allowUnstableUpdates.asStateFlow()

    private val _restartOnNetworkChange = MutableStateFlow(true)
    val restartOnNetworkChange: StateFlow<Boolean> = _restartOnNetworkChange.asStateFlow()

    private val _captchaStyleMod = MutableStateFlow(true)
    val captchaStyleMod: StateFlow<Boolean> = _captchaStyleMod.asStateFlow()

    private val _captchaForceTint = MutableStateFlow(true)
    val captchaForceTint: StateFlow<Boolean> = _captchaForceTint.asStateFlow()

    private val _wgConfig = MutableStateFlow(WgConfig())
    val wgConfig: StateFlow<WgConfig> = _wgConfig.asStateFlow()

    private val _xraySettings = MutableStateFlow(XraySettings())
    val xraySettings: StateFlow<XraySettings> = _xraySettings.asStateFlow()

    private val _globalVpnSettings = MutableStateFlow(GlobalVpnSettings())
    val globalVpnSettings: StateFlow<GlobalVpnSettings> = _globalVpnSettings.asStateFlow()

    private val _excludedApps = MutableStateFlow<Set<String>>(emptySet())
    val excludedApps: StateFlow<Set<String>> = _excludedApps.asStateFlow()

    private val _xrayConfig = MutableStateFlow(XrayConfig())
    val xrayConfig: StateFlow<XrayConfig> = _xrayConfig.asStateFlow()

    private val _vlessConfig = MutableStateFlow(VlessConfig())
    val vlessConfig: StateFlow<VlessConfig> = _vlessConfig.asStateFlow()

    private val _vkLinkHistory = MutableStateFlow<List<String>>(emptyList())
    val vkLinkHistory: StateFlow<List<String>> = _vkLinkHistory.asStateFlow()

    private val _wbstreamUuidHistory = MutableStateFlow<List<String>>(emptyList())
    val wbstreamUuidHistory: StateFlow<List<String>> = _wbstreamUuidHistory.asStateFlow()

    private val _serverAddressHistory = MutableStateFlow<List<String>>(emptyList())
    val serverAddressHistory: StateFlow<List<String>> = _serverAddressHistory.asStateFlow()

    private val _jazzCredsHistory = MutableStateFlow<List<String>>(emptyList())
    val jazzCredsHistory: StateFlow<List<String>> = _jazzCredsHistory.asStateFlow()

    private val _turnableUrlHistory = MutableStateFlow<List<String>>(emptyList())
    val turnableUrlHistory: StateFlow<List<String>> = _turnableUrlHistory.asStateFlow()

    private val _vlessLinkHistory = MutableStateFlow<List<String>>(emptyList())
    val vlessLinkHistory: StateFlow<List<String>> = _vlessLinkHistory.asStateFlow()

    val profiles: StateFlow<List<Profile>> = profileManager.profiles
    val currentProfileId: StateFlow<String> = profileManager.currentProfileId

    // Architecture support
    val isArchitectureSupported: Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
    val deviceArchitecture: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

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

    data class TransferResult(
        val rx: Long,
        val tx: Long,
        val rxSpeed: Long = 0,
        val txSpeed: Long = 0
    )

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastMetricsTime = 0L

    init {
        viewModelScope.launch {
            val done = prefs.onboardingDoneFlow.first()
            val theme = prefs.themeModeFlow.first()
            val dynamic = prefs.dynamicThemeFlow.first()
            val config = prefs.clientConfigFlow.first()
            val batteryDismissed = prefs.batteryNotificationDismissedFlow.first()
            val appsExclusionHintShown = prefs.appsExclusionHintShownFlow.first()
            val allowUnstableUpdates = prefs.allowUnstableUpdatesFlow.first()
            val restartOnNetworkChange = prefs.restartOnNetworkChangeFlow.first()
            val captchaStyleMod = prefs.captchaStyleModFlow.first()
            val captchaForceTint = prefs.captchaForceTintFlow.first()

            val wgConfig = prefs.wgConfigFlow.first()
            val xraySettings = prefs.xraySettingsFlow.first()
            val globalVpnSettings = prefs.globalVpnSettingsFlow.first()
            val xrayConfig = prefs.xrayConfigFlow.first()
            val vlessConfig = prefs.vlessConfigFlow.first()
            val excludedApps = prefs.excludedAppsFlow.first()

            val vkLinkHistory = prefs.vkLinkHistoryFlow.first()
            val wbstreamUuidHistory = prefs.wbstreamUuidHistoryFlow.first()
            val serverAddressHistory = prefs.serverAddressHistoryFlow.first()
            val jazzCredsHistory = prefs.jazzCredsHistoryFlow.first()
            val turnableUrlHistory = prefs.turnableUrlHistoryFlow.first()
            val vlessLinkHistory = prefs.vlessLinkHistoryFlow.first()

            _onboardingDone.value = done
            _themeMode.value = theme
            _dynamicTheme.value = dynamic
            _clientConfig.value = config
            _batteryNotificationDismissed.value = batteryDismissed
            _appsExclusionHintShown.value = appsExclusionHintShown
            _allowUnstableUpdates.value = allowUnstableUpdates
            _restartOnNetworkChange.value = restartOnNetworkChange
            _captchaStyleMod.value = captchaStyleMod
            _captchaForceTint.value = captchaForceTint
            _wgConfig.value = wgConfig
            _xraySettings.value = xraySettings
            _globalVpnSettings.value = globalVpnSettings
            _xrayConfig.value = xrayConfig
            _vlessConfig.value = vlessConfig
            _excludedApps.value = excludedApps

            _vkLinkHistory.value = vkLinkHistory
            _wbstreamUuidHistory.value = wbstreamUuidHistory
            _serverAddressHistory.value = serverAddressHistory
            _jazzCredsHistory.value = jazzCredsHistory
            _turnableUrlHistory.value = turnableUrlHistory
            _vlessLinkHistory.value = vlessLinkHistory

            val currentProfiles = prefs.profilesFlow.first()
            if (currentProfiles.isEmpty()) {
                val defaultProfile = Profile(
                    id = "default",
                    name = getApplication<Application>().getString(R.string.profile_default_name),
                    clientConfig = config,
                    xraySettings = xraySettings,
                    xrayConfig = xrayConfig,
                    wgConfig = wgConfig,
                    vlessConfig = vlessConfig
                )
                prefs.saveProfiles(listOf(defaultProfile))
                prefs.setCurrentProfileId("default")
            }

            _isInitialized.value = true

            viewModelScope.launch {
                delay(2000)
                appUpdater.checkForUpdate(silent = true, allowUnstable = _allowUnstableUpdates.value)
            }

            launch { prefs.onboardingDoneFlow.collect { _onboardingDone.value = it } }
            launch { prefs.themeModeFlow.collect { _themeMode.value = it } }
            launch { prefs.dynamicThemeFlow.collect { _dynamicTheme.value = it } }
            launch { prefs.clientConfigFlow.collect { _clientConfig.value = it } }
            launch { prefs.batteryNotificationDismissedFlow.collect { _batteryNotificationDismissed.value = it } }
            launch { prefs.appsExclusionHintShownFlow.collect { _appsExclusionHintShown.value = it } }
            launch { prefs.allowUnstableUpdatesFlow.collect { _allowUnstableUpdates.value = it } }
            launch { prefs.restartOnNetworkChangeFlow.collect { _restartOnNetworkChange.value = it } }
            launch { prefs.captchaStyleModFlow.collect { _captchaStyleMod.value = it } }
            launch { prefs.captchaForceTintFlow.collect { _captchaForceTint.value = it } }
            launch {
                prefs.allowUnstableUpdatesFlow
                    .drop(1)
                    .debounce(1000)
                    .collect { allow ->
                        appUpdater.checkForUpdate(silent = true, allowUnstable = allow)
                    }
            }
            launch { prefs.wgConfigFlow.collect { _wgConfig.value = it } }
            launch { prefs.xraySettingsFlow.collect { _xraySettings.value = it } }
            launch { prefs.xrayConfigFlow.collect { _xrayConfig.value = it } }
            launch { prefs.vlessConfigFlow.collect { _vlessConfig.value = it } }
            launch { prefs.globalVpnSettingsFlow.collect { _globalVpnSettings.value = it } }
            launch { prefs.excludedAppsFlow.collect { _excludedApps.value = it } }

            launch { prefs.vkLinkHistoryFlow.collect { _vkLinkHistory.value = it } }
            launch { prefs.wbstreamUuidHistoryFlow.collect { _wbstreamUuidHistory.value = it } }
            launch { prefs.serverAddressHistoryFlow.collect { _serverAddressHistory.value = it } }
            launch { prefs.jazzCredsHistoryFlow.collect { _jazzCredsHistory.value = it } }
            launch { prefs.turnableUrlHistoryFlow.collect { _turnableUrlHistory.value = it } }
            launch { prefs.vlessLinkHistoryFlow.collect { _vlessLinkHistory.value = it } }
        }

        viewModelScope.launch { proxyManager.observeProxyLifecycle() }
        viewModelScope.launch { proxyManager.observeStartupResult() }
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
                delay(1000)
            }
        }
    }

    private fun stopMetricsPoller() {
        metricsJob?.cancel()
        metricsJob = null
        _proxyTransfer.value = null
        lastRx = 0L
        lastTx = 0L
        lastMetricsTime = 0L
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

                val now = System.currentTimeMillis()
                var rxSpeed = 0L
                var txSpeed = 0L

                if (lastMetricsTime in 1..<now) {
                    val dt = (now - lastMetricsTime) / 1000.0
                    if (dt > 0) {
                        rxSpeed = ((rx - lastRx).coerceAtLeast(0) / dt).toLong()
                        txSpeed = ((tx - lastTx).coerceAtLeast(0) / dt).toLong()
                    }
                }

                lastRx = rx
                lastTx = tx
                lastMetricsTime = now

                _proxyTransfer.value = TransferResult(rx, tx, rxSpeed, txSpeed)
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

    private val _privacyMode = MutableStateFlow(false)
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()

    fun setPrivacyMode(enabled: Boolean) { _privacyMode.value = enabled }

    fun startProxy() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            val cfg = clientConfig.value
            if (cfg.dcMode) {
                when (cfg.dcType) {
                    DCType.SALUTE_JAZZ -> prefs.addJazzCredsToHistory(cfg.jazzCreds)
                    DCType.WB_STREAM -> prefs.addWbstreamUuidToHistory(cfg.wbstreamUuid)
                }
            } else {
                prefs.addVkLinkToHistory(cfg.vkLink)
                prefs.addTurnableUrlToHistory(cfg.turnableUrl)
            }
            prefs.addServerAddressToHistory(cfg.serverAddress)
            proxyManager.startProxy(cfg)
        }
    }

    fun restartProxy() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            proxyManager.stopProxy()
            // Ждем реальной остановки сервиса через его состояние
            withTimeoutOrNull(5000) {
                ProxyServiceState.isRunning.first { !it }
            }
            delay(500) // Дополнительная пауза для стабильности
            startProxy()
        }
    }

    fun restartXray() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            getApplication<Application>().stopService(Intent(getApplication(), XrayService::class.java))
            withTimeoutOrNull(5000) {
                XrayServiceState.state.first { it == XrayState.Idle }
            }
            delay(300)
            // XraySupervisor в ProxyService сам его перезапустит, 
            // так как он следит за xrayEnabled и ProxyServiceState.isRunning
            // Но мы можем явно дернуть старт, если хотим быстрее
            if (ProxyServiceState.isRunning.value && xraySettings.value.xrayEnabled) {
                getApplication<Application>().startForegroundService(Intent(getApplication(), XrayService::class.java))
            }
        }
    }

    fun stopProxy() {
        proxyManager.stopProxy()
    }

    fun revertToRunningConfigs() {
        ProxyServiceState.runningConfig.value?.let { saveClientConfig(it) }
        XrayServiceState.runningWgConfig.value?.let { updateWgConfig(it) }
        XrayServiceState.runningVlessConfig.value?.let { updateVlessConfig(it) }
        XrayServiceState.runningXrayConfig.value?.let { updateXrayConfig(it) }
    }

    fun dismissCaptcha() { proxyManager.dismissCaptcha() }
    fun clearLogs() { AppLogsState.clearLogs() }
    fun saveClientConfig(config: ClientConfig) {
        _clientConfig.value = config
        viewModelScope.launch {
            prefs.saveClientConfig(config)
            updateCurrentProfileInList()
        }
    }
    fun removeVkLinkFromHistory(link: String) { viewModelScope.launch { prefs.removeVkLinkFromHistory(link) } }
    fun removeWbstreamUuidFromHistory(uuid: String) { viewModelScope.launch { prefs.removeWbstreamUuidFromHistory(uuid) } }
    fun removeServerAddressFromHistory(address: String) { viewModelScope.launch { prefs.removeServerAddressFromHistory(address) } }
    fun removeJazzCredsFromHistory(creds: String) { viewModelScope.launch { prefs.removeJazzCredsFromHistory(creds) } }
    fun removeTurnableUrlFromHistory(url: String) { viewModelScope.launch { prefs.removeTurnableUrlFromHistory(url) } }
    fun removeVlessLinkFromHistory(link: String) { viewModelScope.launch { prefs.removeVlessLinkFromHistory(link) } }
    fun setOnboardingDone() { viewModelScope.launch { prefs.setOnboardingDone(true) } }
    fun setBatteryNotificationDismissed(dismissed: Boolean) { viewModelScope.launch { prefs.setBatteryNotificationDismissed(dismissed) } }
    fun setAppsExclusionHintShown(shown: Boolean) { viewModelScope.launch { prefs.setAppsExclusionHintShown(shown) } }
    fun setAllowUnstableUpdates(allow: Boolean) { viewModelScope.launch { prefs.setAllowUnstableUpdates(allow) } }
    fun setRestartOnNetworkChange(enabled: Boolean) { viewModelScope.launch { prefs.setRestartOnNetworkChange(enabled) } }

    fun setCaptchaStyleMod(enabled: Boolean) { viewModelScope.launch { prefs.setCaptchaStyleMod(enabled) } }
    fun setCaptchaForceTint(enabled: Boolean) { viewModelScope.launch { prefs.setCaptchaForceTint(enabled) } }

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
                        AppLogsState.addLog("[Ping] Error: ${e.message}")
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
    fun checkForUpdate() { viewModelScope.launch { appUpdater.checkForUpdate(silent = false, allowUnstable = _allowUnstableUpdates.value) } }
    fun downloadUpdate() { viewModelScope.launch { appUpdater.downloadUpdate() } }
    fun installUpdate() { appUpdater.installUpdate() }

    fun updateWgConfig(config: WgConfig) {
        _wgConfig.value = config
        viewModelScope.launch {
            prefs.saveWgConfig(config)
            updateCurrentProfileInList()
        }
    }

    fun updateXraySettings(settings: XraySettings) {
        _xraySettings.value = settings
        viewModelScope.launch {
            prefs.saveXraySettings(settings)
            updateCurrentProfileInList()
        }
    }

    fun updateGlobalVpnSettings(settings: GlobalVpnSettings) {
        _globalVpnSettings.value = settings
        viewModelScope.launch {
            prefs.saveGlobalVpnSettings(settings)
        }
    }

    fun toggleAppExclusion(packageName: String) {
        val currentExcluded = _excludedApps.value
        val newExcludedApps = if (currentExcluded.contains(packageName)) {
            currentExcluded - packageName
        } else {
            currentExcluded + packageName
        }
        viewModelScope.launch {
            prefs.saveExcludedApps(newExcludedApps)
        }
    }

    fun saveExcludedApps(excludedApps: Set<String>) {
        _excludedApps.value = excludedApps
        viewModelScope.launch {
            prefs.saveExcludedApps(excludedApps)
        }
    }

    fun updateXrayConfig(config: XrayConfig) {
        _xrayConfig.value = config
        viewModelScope.launch {
            prefs.saveXrayConfig(config)
            updateCurrentProfileInList()
        }
    }

    fun updateVlessConfig(config: VlessConfig) {
        _vlessConfig.value = config
        viewModelScope.launch {
            prefs.saveVlessConfig(config)
            updateCurrentProfileInList()
        }
    }

    fun updateWgConfigText(text: String) {
        val parsed = WgConfig.parse(text)
        if (_wgConfig.value != parsed) {
            _wgConfig.value = parsed
            viewModelScope.launch {
                prefs.saveWgConfig(parsed)
                updateCurrentProfileInList()
            }
        }
    }

    private fun updateCurrentProfileInList() {
        profileManager.updateCurrentProfile(
            _clientConfig.value,
            _xraySettings.value,
            _xrayConfig.value,
            _wgConfig.value,
            _vlessConfig.value
        )
    }

    fun selectProfile(id: String, profile: Profile? = null) {
        profileManager.selectProfile(id, profile) { client, settings, xray, wg, vless ->
            _clientConfig.value = client
            _wgConfig.value = wg
            _vlessConfig.value = vless
            _xraySettings.value = settings
            _xrayConfig.value = xray
        }
    }

    fun selectProfileAndRestart(id: String, onCompletion: (() -> Unit)? = null) {
        val profileName = profiles.value.find { it.id == id }?.name
        profileManager.selectProfile(id) { client, settings, xray, wg, vless ->
            _clientConfig.value = client
            _wgConfig.value = wg
            _vlessConfig.value = vless
            _xraySettings.value = settings
            _xrayConfig.value = xray

            ProcessLifecycleOwner.get().lifecycleScope.launch {
                // Wait for prefs to be saved
                delay(200)

                val runningProfileName = ProxyServiceState.runningProfileName.value
                val profileChanged = runningProfileName != null && profileName != runningProfileName

                val runningConfig = ProxyServiceState.runningConfig.value
                val mainConfigChanged = profileChanged || (runningConfig != null && client != runningConfig)

                if (mainConfigChanged) {
                    restartProxy()
                } else if (ProxyServiceState.isRunning.value) {
                    ProxyServiceState.setRunningProfileName(profileName)
                }

                val runningWg = XrayServiceState.runningWgConfig.value
                val runningVless = XrayServiceState.runningVlessConfig.value
                val runningXray = XrayServiceState.runningXrayConfig.value

                if ((runningWg != null && wg != runningWg) ||
                    (runningVless != null && vless != runningVless) ||
                    (runningXray != null && xray != runningXray)) {
                    restartXray()
                }
            }
            onCompletion?.invoke()
        }
    }

    fun createProfile(name: String) {
        profileManager.createProfile(name) { _, _ -> }
    }
    fun cloneProfile(id: String, newName: String) = profileManager.cloneProfile(id, newName)
    fun deleteProfile(id: String) = profileManager.deleteProfile(id) { nextId -> selectProfile(nextId) }
    fun renameProfile(id: String, newName: String) = profileManager.renameProfile(id, newName)
    fun reorderProfiles(newList: List<Profile>) = profileManager.reorderProfiles(newList)
    fun getProfileJson(id: String): String? = profileManager.getProfileJson(id)
    fun exportAllProfilesToZip(): ByteArray = profileManager.exportAllProfilesToZip()
    fun importProfilesFromZip(inputStream: java.io.InputStream) = profileManager.importProfilesFromZip(inputStream) { id ->
        selectProfileAndRestart(id)
    }
    fun importProfiles(data: List<Pair<String?, String>>) = profileManager.importProfiles(data) { id ->
        selectProfileAndRestart(id)
    }

    private fun isValidHostPort(address: String): Boolean {
        return com.wireturn.app.ui.ValidatorUtils.isValidHostPort(address)
    }

    fun resetAllSettings(context: Context) {
        viewModelScope.launch {
            context.stopService(Intent(context, ProxyService::class.java))
            context.stopService(Intent(context, XrayService::class.java))
            ProxyServiceState.setRunning(false)
            XrayServiceState.updateStatus(XrayState.Idle)
            prefs.resetAll()
            proxyManager.clearState()
            AppLogsState.clearLogs()
            _wgConfig.value = WgConfig()
            _xrayConfig.value = XrayConfig()
            val intent = (context as? android.app.Activity)?.intent
                ?: Intent(context, com.wireturn.app.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}

