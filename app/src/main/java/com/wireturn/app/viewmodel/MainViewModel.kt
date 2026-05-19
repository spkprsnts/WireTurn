package com.wireturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.wireturn.app.AppLogsState
import com.wireturn.app.ProxyService
import com.wireturn.app.ProxyServiceState
import com.wireturn.app.ProxyStatus
import com.wireturn.app.ProxyTileService
import com.wireturn.app.R
import com.wireturn.app.XrayService
import com.wireturn.app.XrayServiceState
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.AutoLaunchSettings
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.GlobalVpnSettings
import com.wireturn.app.data.KernelVariant
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.system.measureTimeMillis

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val proxyManager = LocalProxyManager(application)
    private val appUpdater = AppUpdater(application)
    private val profileManager = ProfileManager(
        prefs = prefs,
        scope = ProcessLifecycleOwner.get().lifecycleScope,
        defaultProfileName = application.getString(R.string.profile_default_name)
    )

    val proxyState: StateFlow<ProxyState> = proxyManager.proxyState
    val logs: StateFlow<List<AppLogsState.LogEntry>> = AppLogsState.logs
    val updateState: StateFlow<UpdateState> = appUpdater.state
    val updateProgress: StateFlow<Int> = appUpdater.downloadProgress

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _onboardingDone = MutableStateFlow(false)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
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

    private val _waitForNetwork = MutableStateFlow(true)
    val waitForNetwork: StateFlow<Boolean> = _waitForNetwork.asStateFlow()

    private val _restartOnNetworkChange = MutableStateFlow(false)
    val restartOnNetworkChange: StateFlow<Boolean> = _restartOnNetworkChange.asStateFlow()

    private val _captchaStyleMod = MutableStateFlow(true)
    val captchaStyleMod: StateFlow<Boolean> = _captchaStyleMod.asStateFlow()

    private val _captchaForceTint = MutableStateFlow(true)
    val captchaForceTint: StateFlow<Boolean> = _captchaForceTint.asStateFlow()

    private val _showFloatingActionButton = MutableStateFlow(true)
    val showFloatingActionButton: StateFlow<Boolean> = _showFloatingActionButton.asStateFlow()

    private val _appLanguage = MutableStateFlow("system")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _wgConfig = MutableStateFlow(WgConfig())
    val wgConfig: StateFlow<WgConfig> = _wgConfig.asStateFlow()

    private val _xrayConfig = MutableStateFlow(XrayConfig())
    val xrayConfig: StateFlow<XrayConfig> = _xrayConfig.asStateFlow()

    private val _globalVpnSettings = MutableStateFlow(GlobalVpnSettings())
    val globalVpnSettings: StateFlow<GlobalVpnSettings> = _globalVpnSettings.asStateFlow()

    private val _excludedApps = MutableStateFlow<Set<String>>(emptySet())
    val excludedApps: StateFlow<Set<String>> = _excludedApps.asStateFlow()

    private val _xraySettings = MutableStateFlow(XraySettings())
    val xraySettings: StateFlow<XraySettings> = _xraySettings.asStateFlow()

    private val _vlessConfig = MutableStateFlow(VlessConfig())
    val vlessConfig: StateFlow<VlessConfig> = _vlessConfig.asStateFlow()

    private val _vlessLinkHistory = MutableStateFlow<List<String>>(emptyList())
    val vlessLinkHistory: StateFlow<List<String>> = _vlessLinkHistory.asStateFlow()

    private val _autoLaunchSettings = MutableStateFlow(AutoLaunchSettings())
    val autoLaunchSettings: StateFlow<AutoLaunchSettings> = _autoLaunchSettings.asStateFlow()

    private var autoLaunchJob: Job? = null

    val profiles: StateFlow<List<Profile>> = profileManager.profiles
    val currentProfileId: StateFlow<String> = profileManager.currentProfileId

    val isArchitectureSupported: Boolean = Build.SUPPORTED_ABIS.any { 
        it == "arm64-v8a" || it == "x86_64" 
    }
    val deviceArchitecture: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private val _proxyPing = MutableStateFlow<PingResult?>(null)
    val proxyPing: StateFlow<PingResult?> = _proxyPing.asStateFlow()

    private val _proxyTransfer = MutableStateFlow<TransferResult?>(null)
    val proxyTransfer: StateFlow<TransferResult?> = _proxyTransfer.asStateFlow()

    private val _isHomeScreenActive = MutableStateFlow(false)

    private val _isBottomBarVisible = MutableStateFlow(true)
    val isBottomBarVisible: StateFlow<Boolean> = _isBottomBarVisible.asStateFlow()

    private val _bottomBarOffset = MutableStateFlow(0f)
    val bottomBarOffset: StateFlow<Float> = _bottomBarOffset.asStateFlow()

    private val _bottomBarHeight = MutableStateFlow(0f)
    val bottomBarHeight: StateFlow<Float> = _bottomBarHeight.asStateFlow()

    val isMainConfigChanged: StateFlow<Boolean> = combine(
        clientConfig, ProxyServiceState.clientConfigSnapshot
    ) { client, clientSnap ->
        clientSnap != null && client.fillDefaults() != clientSnap
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isXrayConfigChanged: StateFlow<Boolean> = combine(
        wgConfig, XrayServiceState.wgConfigSnapshot,
        vlessConfig, XrayServiceState.vlessConfigSnapshot,
        xrayConfig, XrayServiceState.xrayConfigSnapshot,
        xraySettings, XrayServiceState.xraySettingsSnapshot,
        clientConfig, ProxyServiceState.clientConfigSnapshot
    ) { args: Array<Any?> ->
        val wg = args[0] as WgConfig
        val wgSnap = args[1] as WgConfig?
        val vless = args[2] as VlessConfig
        val vlessSnap = args[3] as VlessConfig?
        val xray = args[4] as XrayConfig
        val xraySnap = args[5] as XrayConfig?
        val settings = args[6] as XraySettings
        val settingsSnap = args[7] as XraySettings?
        val client = args[8] as ClientConfig
        val clientSnap = args[9] as ClientConfig?

        val baseChanged = (wgSnap != null && wg.fillDefaults() != wgSnap) ||
                (vlessSnap != null && vless != vlessSnap) ||
                (xraySnap != null && xray != xraySnap) ||
                (settingsSnap != null && settings.fillDefaults() != settingsSnap)
        
        val connectionChanged = clientSnap != null && (
                client.kernelVariant != clientSnap.kernelVariant ||
                client.listenAddr != clientSnap.listenAddr ||
                client.olcrtcConfig.carrier != clientSnap.olcrtcConfig.carrier ||
                client.olcrtcConfig.transport != clientSnap.olcrtcConfig.transport ||
                client.socksAddr != clientSnap.socksAddr ||
                client.isSocksAuthEnabled != clientSnap.isSocksAuthEnabled ||
                client.socksUser != clientSnap.socksUser ||
                client.socksPass != clientSnap.socksPass
        )
        
        baseChanged || connectionChanged
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isConfigChanged: StateFlow<Boolean> = combine(
        isMainConfigChanged, isXrayConfigChanged, ProxyServiceState.isRestarting
    ) { main, xray, isRestarting -> 
        !isRestarting && (main || xray) 
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var settleJob: Job? = null

    fun setBottomBarHeight(height: Float) {
        if (height > 0 && _bottomBarHeight.value != height) {
            if (_bottomBarOffset.value > height) _bottomBarOffset.value = height
        }
        _bottomBarHeight.value = height
    }

    fun onBottomBarScroll(delta: Float) {
        val height = _bottomBarHeight.value
        if (height <= 0f) return
        settleJob?.cancel()
        val adjustedDelta = delta / 3f
        val currentOffset = _bottomBarOffset.value
        if (adjustedDelta > 0) {
            _bottomBarOffset.value = (currentOffset - adjustedDelta).coerceAtLeast(0f)
        } else {
            _bottomBarOffset.value = (currentOffset - adjustedDelta).coerceAtMost(height)
        }
    }

    fun settleBottomBar(velocity: Float) {
        val height = _bottomBarHeight.value
        if (height <= 0f) return
        settleJob?.cancel()
        val currentOffset = _bottomBarOffset.value
        val target = when {
            velocity > 300f -> 0f
            velocity < -1500f -> height
            currentOffset > height * 0.8f -> height
            else -> 0f
        }
        if (currentOffset == target) return
        settleJob = viewModelScope.launch {
            val duration = if (target == 0f) 150L else 250L
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= duration) break
                val progress = elapsed.toFloat() / duration
                val easedProgress = if (target == 0f) {
                    1f - (1f - progress) * (1f - progress)
                } else {
                    progress * progress
                }
                _bottomBarOffset.value = currentOffset + (target - currentOffset) * easedProgress
                delay(16)
            }
            _bottomBarOffset.value = target
        }
    }

    fun setBottomBarVisible(visible: Boolean) {
        settleJob?.cancel()
        _isBottomBarVisible.value = visible
        if (visible) _bottomBarOffset.value = 0f
    }

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

    private val _vpnEnabled = MutableStateFlow(false)
    val vpnEnabled: StateFlow<Boolean> = _vpnEnabled.asStateFlow()

    private val _olcrtcSocksAddr = MutableStateFlow("")
    val olcrtcSocksAddr: StateFlow<String> = _olcrtcSocksAddr.asStateFlow()
    private val _olcrtcSocksAuthEnabled = MutableStateFlow(true)
    val olcrtcSocksAuthEnabled: StateFlow<Boolean> = _olcrtcSocksAuthEnabled.asStateFlow()
    private val _olcrtcSocksUser = MutableStateFlow("")
    val olcrtcSocksUser: StateFlow<String> = _olcrtcSocksUser.asStateFlow()
    private val _olcrtcSocksPass = MutableStateFlow("")
    val olcrtcSocksPass: StateFlow<String> = _olcrtcSocksPass.asStateFlow()

    init {
        viewModelScope.launch {
            _onboardingDone.value = prefs.onboardingDoneFlow.first()
            _themeMode.value = prefs.themeModeFlow.first()
            _dynamicTheme.value = prefs.dynamicThemeFlow.first()
            _clientConfig.value = prefs.clientConfigFlow.first()
            _xrayConfig.value = prefs.xrayConfigFlow.first()
            _wgConfig.value = prefs.wgConfigFlow.first()
            _vlessConfig.value = prefs.vlessConfigFlow.first()
            _xraySettings.value = prefs.xraySettingsFlow.first()
            _vpnEnabled.value = prefs.vpnEnabledFlow.first()
            _batteryNotificationDismissed.value = prefs.batteryNotificationDismissedFlow.first()
            _appsExclusionHintShown.value = prefs.appsExclusionHintShownFlow.first()
            _allowUnstableUpdates.value = prefs.allowUnstableUpdatesFlow.first()
            _waitForNetwork.value = prefs.waitForNetworkFlow.first()
            _restartOnNetworkChange.value = prefs.restartOnNetworkChangeFlow.first()
            _captchaStyleMod.value = prefs.captchaStyleModFlow.first()
            _captchaForceTint.value = prefs.captchaForceTintFlow.first()
            _showFloatingActionButton.value = prefs.showFloatingActionButtonFlow.first()
            _appLanguage.value = prefs.appLanguageFlow.first()
            _globalVpnSettings.value = prefs.globalVpnSettingsFlow.first()
            _excludedApps.value = prefs.excludedAppsFlow.first()
            _vlessLinkHistory.value = prefs.vlessLinkHistoryFlow.first()
            _autoLaunchSettings.value = prefs.autoLaunchSettingsFlow.first()

            updateAutoLaunchJob(_autoLaunchSettings.value)
            applyLanguage(_appLanguage.value)

            _isInitialized.value = true

            // Migration: if ACTIVE keys are empty but we have profiles, activate current
            val currentProfiles = prefs.profilesFlow.first()
            if (currentProfiles.isNotEmpty()) {
                if (!prefs.hasActiveProfile()) {
                    val currentId = currentProfileId.value
                    val toActivate = currentProfiles.find { it.id == currentId } ?: currentProfiles.first()
                    prefs.saveFullProfile(toActivate.id, toActivate)
                }
            }

            launch { prefs.onboardingDoneFlow.collect { _onboardingDone.value = it } }
            launch { prefs.themeModeFlow.collect { _themeMode.value = it } }
            launch { prefs.vpnEnabledFlow.collect { _vpnEnabled.value = it } }
            launch { prefs.dynamicThemeFlow.collect { _dynamicTheme.value = it } }
            launch { prefs.clientConfigFlow.collect { _clientConfig.value = it } }
            launch { prefs.xrayConfigFlow.collect { _xrayConfig.value = it } }
            launch { prefs.wgConfigFlow.collect { _wgConfig.value = it } }
            launch { prefs.vlessConfigFlow.collect { _vlessConfig.value = it } }
            launch { prefs.xraySettingsFlow.collect { _xraySettings.value = it } }
            launch { prefs.batteryNotificationDismissedFlow.collect { _batteryNotificationDismissed.value = it } }
            launch { prefs.appsExclusionHintShownFlow.collect { _appsExclusionHintShown.value = it } }
            launch { prefs.allowUnstableUpdatesFlow.collect { _allowUnstableUpdates.value = it } }
            launch { prefs.waitForNetworkFlow.collect { _waitForNetwork.value = it } }
            launch { prefs.restartOnNetworkChangeFlow.collect { _restartOnNetworkChange.value = it } }
            launch { prefs.captchaStyleModFlow.collect { _captchaStyleMod.value = it } }
            launch { prefs.captchaForceTintFlow.collect { _captchaForceTint.value = it } }
            launch { prefs.showFloatingActionButtonFlow.collect { _showFloatingActionButton.value = it } }
            launch { prefs.appLanguageFlow.collect { _appLanguage.value = it } }
            launch { prefs.autoLaunchSettingsFlow.collect { _autoLaunchSettings.value = it; updateAutoLaunchJob(it) } }
            launch { prefs.globalVpnSettingsFlow.collect { _globalVpnSettings.value = it } }
            launch { prefs.excludedAppsFlow.collect { _excludedApps.value = it } }
            launch { prefs.vlessLinkHistoryFlow.collect { _vlessLinkHistory.value = it } }

            launch { prefs.olcrtcSocksAddrFlow.collect { _olcrtcSocksAddr.value = it } }
            launch { prefs.olcrtcSocksAuthEnabledFlow.collect { _olcrtcSocksAuthEnabled.value = it } }
            launch { prefs.olcrtcSocksUserFlow.collect { _olcrtcSocksUser.value = it } }
            launch { prefs.olcrtcSocksPassFlow.collect { _olcrtcSocksPass.value = it } }
        }

        viewModelScope.launch { proxyManager.observeProxyLifecycle() }
        viewModelScope.launch { proxyManager.observeProxyServiceStatus() }
        viewModelScope.launch { proxyManager.observeCaptchaEvents() }
        viewModelScope.launch {
            delay(1500)
            XrayServiceState.state.collect { state ->
                if (state == XrayState.Running || state == XrayState.DirectRoute) {
                    checkProxyPing(delayFirst = true)
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
                    val state = XrayServiceState.state.value
                    if (state == XrayState.Running || state == XrayState.DirectRoute) {
                        XrayServiceState.statsSocketName.value?.let { updateMetrics(it) }
                    } else {
                        _proxyTransfer.value = null
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

    private suspend fun updateMetrics(socketName: String) = withContext(Dispatchers.IO) {
        try {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            socket.outputStream.write("stats\n".toByteArray())
            val content = socket.inputStream.bufferedReader().readLine() ?: ""
            socket.close()
            if (content.isBlank()) return@withContext
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            val rx = json.get("rx_bytes")?.asLong ?: 0L
            val tx = json.get("tx_bytes")?.asLong ?: 0L
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
        } catch (_: Exception) {}
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

    fun updateAutoLaunchSettings(settings: AutoLaunchSettings) {
        viewModelScope.launch { 
            prefs.updateAutoLaunchSettings(settings)
            ProxyTileService.requestUpdate(getApplication()) 
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun isUrlReachable(url: String): Boolean {
        if (!isNetworkAvailable()) return true
        repeat(3) {
            try {
                val code = withContext(Dispatchers.IO) {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 2500
                    conn.readTimeout = 2500
                    conn.requestMethod = "HEAD"
                    conn.useCaches = false
                    conn.responseCode
                }
                if (code in 200..399) return true
            } catch (_: Exception) {}
            if (it < 2) delay(300)
        }
        return false
    }

    private fun updateAutoLaunchJob(settings: AutoLaunchSettings) {
        autoLaunchJob?.cancel()
        if (settings.enabled) {
            autoLaunchJob = viewModelScope.launch(Dispatchers.IO) {
                while (true) {
                    val isReachable = isUrlReachable(settings.checkUrl)
                    val isRunning = ProxyServiceState.isRunning.value
                    if (!isReachable && !isRunning) {
                        withContext(Dispatchers.Main) { startProxy() }
                    } else if (isReachable && isRunning) {
                        withContext(Dispatchers.Main) { 
                            ProxyService.stop(getApplication(), byUser = false) 
                        }
                    }
                    delay(settings.intervalMinutes * 60 * 1000L)
                }
            }
        }
    }

    fun startProxy() { 
        ProcessLifecycleOwner.get().lifecycleScope.launch { startProxyInternal() } 
    }
    
    private suspend fun startProxyInternal(forceRestart: Boolean = false) { 
        proxyManager.startProxy(clientConfig.value, forceRestart) 
    }
    
    private suspend fun restartProxyInternal() {
        proxyManager.stopProxy()
        withTimeoutOrNull(5000) { ProxyServiceState.isRunning.first { !it } }
        delay(600)
        startProxyInternal()
    }

    fun restartProxy() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            ProxyServiceState.setRestarting(true)
            try { restartProxyInternal() } finally { 
                delay(100)
                ProxyServiceState.setRestarting(false) 
            }
        }
    }

    fun restartXray() {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            ProxyServiceState.setRestarting(true)
            try {
                getApplication<Application>().stopService(Intent(getApplication(), XrayService::class.java))
                withTimeoutOrNull(5000) { XrayServiceState.state.first { it == XrayState.Idle } }
            } finally { 
                delay(100)
                ProxyServiceState.setRestarting(false) 
            }
        }
    }

    fun stopProxy() { proxyManager.stopProxy() }

    fun revertToSnapshotConfigs() {
        ProxyServiceState.clientConfigSnapshot.value?.let { saveClientConfig(it) }
        XrayServiceState.wgConfigSnapshot.value?.let { updateWgConfig(it) }
        XrayServiceState.vlessConfigSnapshot.value?.let { updateVlessConfig(it) }
        XrayServiceState.xrayConfigSnapshot.value?.let { updateXrayConfig(it) }
    }

    fun dismissCaptcha() { proxyManager.dismissCaptcha() }
    fun clearLogs() { AppLogsState.clearLogs() }
    
    fun saveClientConfig(config: ClientConfig) {
        viewModelScope.launch {
            prefs.saveClientListenAddr(config.listenAddr)
            prefs.saveOlcrtcSocks(
                config.socksAddr, 
                config.isSocksAuthEnabled, 
                config.socksUser, 
                config.socksPass
            )
            prefs.saveActiveProfilePart(profiles.value.find { it.id == currentProfileId.value }?.copy(
                kernelVariant = config.kernelVariant,
                turnableConfig = config.turnableConfig,
                olcrtcConfig = config.olcrtcConfig
            ) ?: return@launch)
            updateCurrentProfileInList()
        }
    }
    
    fun removeVlessLinkFromHistory(link: String) { 
        viewModelScope.launch { prefs.removeVlessLinkFromHistory(link) } 
    }
    
    fun setOnboardingDone() { 
        viewModelScope.launch { prefs.setOnboardingDone(true) } 
    }
    
    fun setBatteryNotificationDismissed(v: Boolean) { 
        viewModelScope.launch { prefs.setBatteryNotificationDismissed(v) } 
    }
    
    fun setAppsExclusionHintShown(v: Boolean) { 
        viewModelScope.launch { prefs.setAppsExclusionHintShown(v) } 
    }
    
    fun setAllowUnstableUpdates(v: Boolean) { 
        viewModelScope.launch { prefs.setAllowUnstableUpdates(v) } 
    }
    
    fun setWaitForNetwork(v: Boolean) { 
        viewModelScope.launch { prefs.setWaitForNetwork(v) } 
    }
    
    fun setRestartOnNetworkChange(v: Boolean) { 
        viewModelScope.launch { prefs.setRestartOnNetworkChange(v) } 
    }
    
    fun setVpnEnabled(v: Boolean) { 
        viewModelScope.launch { prefs.setVpnEnabled(v) } 
    }
    
    fun saveOlcrtcSocks(addr: String, auth: Boolean, user: String, pass: String) { 
        viewModelScope.launch { prefs.saveOlcrtcSocks(addr, auth, user, pass) } 
    }
    
    fun setCaptchaStyleMod(v: Boolean) { 
        viewModelScope.launch { prefs.setCaptchaStyleMod(v) } 
    }
    
    fun setCaptchaForceTint(v: Boolean) { 
        viewModelScope.launch { prefs.setCaptchaForceTint(v) } 
    }
    
    fun setShowFloatingActionButton(v: Boolean) { 
        viewModelScope.launch { prefs.setShowFloatingActionButton(v) } 
    }
    
    fun setAppLanguage(l: String) { 
        _appLanguage.value = l
        viewModelScope.launch { 
            prefs.setAppLanguage(l)
            applyLanguage(l) 
        } 
    }

    private fun applyLanguage(lang: String) {
        val loc = if (lang == "system") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang)
        AppCompatDelegate.setApplicationLocales(loc)
    }

    fun checkProxyPing(delayFirst: Boolean = false) {
        val addr = XrayServiceState.xraySettingsSnapshot.value?.connectableAddress ?: return
        if (addr.isBlank() || !com.wireturn.app.ui.ValidatorUtils.isValidHostPort(addr)) return
        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            _proxyPing.value = PingResult.Loading
            repeat(10) { attempt ->
                if (XrayServiceState.state.value !in listOf(XrayState.Running, XrayState.DirectRoute)) { 
                    _proxyPing.value = null
                    return@launch 
                }
                if (delayFirst && attempt == 0) delay(1000)
                val res = withContext(Dispatchers.IO) {
                    try {
                        val parts = addr.split(":")
                        val p = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(parts[0], parts[1].toInt()))
                        val conn = java.net.URL("https://1.1.1.1/").openConnection(p) as java.net.HttpURLConnection
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.instanceFollowRedirects = false
                        PingResult.Success(measureTimeMillis { conn.responseCode })
                    } catch (e: Exception) { 
                        AppLogsState.addLog("[Ping] Error: ${e.message}")
                        null 
                    }
                }
                if (res is PingResult.Success) { 
                    _proxyPing.value = res
                    return@launch 
                }
                delay(1000)
            }
            _proxyPing.value = PingResult.Error
        }
    }

    fun checkForUpdate() { 
        viewModelScope.launch { 
            appUpdater.checkForUpdate(silent = false, allowUnstable = _allowUnstableUpdates.value) 
        } 
    }
    
    fun downloadUpdate() { 
        viewModelScope.launch { appUpdater.downloadUpdate() } 
    }
    
    fun installUpdate() { appUpdater.installUpdate() }

    fun updateWgConfig(c: WgConfig) { 
        ProcessLifecycleOwner.get().lifecycleScope.launch { 
            prefs.saveWgConfig(c)
            updateCurrentProfileInList() 
        } 
    }
    
    fun updateXraySettings(s: XraySettings) {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveXraySettings(s)
            updateCurrentProfileInList()
        }
    }

    fun updateGlobalVpnSettings(s: GlobalVpnSettings) { 
        viewModelScope.launch { prefs.saveGlobalVpnSettings(s) } 
    }
    
    fun toggleAppExclusion(p: String) { 
        val cur = _excludedApps.value
        viewModelScope.launch { 
            prefs.saveExcludedApps(if (cur.contains(p)) cur - p else cur + p) 
        } 
    }
    
    fun saveExcludedApps(s: Set<String>) { 
        viewModelScope.launch { prefs.saveExcludedApps(s) } 
    }
    
    fun updateXrayConfig(c: XrayConfig) {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveXrayConfig(c)
            updateCurrentProfileInList()
        }
    }
    
    fun updateVlessConfig(c: VlessConfig) { 
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveVlessConfig(c)
            updateCurrentProfileInList() 
        } 
    }

    private fun updateCurrentProfileInList() {
        val curId = currentProfileId.value
        val profile = profiles.value.find { it.id == curId } ?: return
        val updated = profile.copy(
            kernelVariant = _clientConfig.value.kernelVariant,
            turnableConfig = _clientConfig.value.turnableConfig,
            olcrtcConfig = _clientConfig.value.olcrtcConfig,
            xrayProtocol = _xrayConfig.value.protocol,
            xrayEnabled = _xrayConfig.value.enabled,
            wgConfig = _wgConfig.value,
            vlessConfig = _vlessConfig.value
        )
        profileManager.updateCurrentProfile(updated)
    }

    fun selectProfile(id: String, profile: Profile? = null) {
        profileManager.selectProfile(id, profile) { target ->
            viewModelScope.launch {
                prefs.saveFullProfile(target.id, target)
            }
        }
    }

    fun selectProfileAndRestart(id: String, onCompletion: (() -> Unit)? = null) {
        val target = profiles.value.find { it.id == id } ?: return
        if (ProxyServiceState.isRunning.value) ProxyServiceState.setRestarting(true)
        profileManager.selectProfile(id, target) { p ->
            viewModelScope.launch {
                try {
                    prefs.saveFullProfile(p.id, p)
                    val isRunning = ProxyServiceState.isRunning.value
                    if (!isRunning) return@launch
                    val clientSnap = ProxyServiceState.clientConfigSnapshot.value
                    
                    val mainChanged = clientSnap != null && (
                        p.kernelVariant != clientSnap.kernelVariant ||
                        (p.kernelVariant == KernelVariant.TURNABLE && p.turnableConfig.sanitize() != clientSnap.turnableConfig) ||
                        (p.kernelVariant == KernelVariant.OLCRTC && p.olcrtcConfig.fillDefaults() != clientSnap.olcrtcConfig)
                    )

                    if (mainChanged) restartProxyInternal()
                    else getApplication<Application>().stopService(Intent(getApplication(), XrayService::class.java))
                } finally { 
                    delay(300)
                    ProxyServiceState.setRestarting(false) 
                }
            }
            onCompletion?.invoke()
        }
    }

    fun addFullProfile(
        name: String,
        clientConfig: ClientConfig,
        xrayConfig: XrayConfig,
        wgConfig: WgConfig,
        vlessConfig: VlessConfig
    ) {
        val id = java.util.UUID.randomUUID().toString()
        val newProfile = Profile(
            id = id,
            name = name,
            kernelVariant = clientConfig.kernelVariant,
            turnableConfig = clientConfig.turnableConfig,
            olcrtcConfig = clientConfig.olcrtcConfig,
            xrayProtocol = xrayConfig.protocol,
            xrayEnabled = xrayConfig.enabled,
            wgConfig = wgConfig,
            vlessConfig = vlessConfig
        ).sanitize()
        viewModelScope.launch {
            prefs.saveProfiles(profiles.value + newProfile)
            selectProfile(id, newProfile)
        }
    }

    fun cloneProfile(id: String, name: String) = profileManager.cloneProfile(id, name)
    fun deleteProfile(id: String) = deleteProfiles(listOf(id))
    fun deleteProfiles(ids: List<String>) = profileManager.deleteProfiles(ids) { nextId, p -> selectProfile(nextId, p) }
    fun renameProfile(id: String, name: String) = profileManager.renameProfile(id, name)
    fun reorderProfiles(list: List<Profile>) = profileManager.reorderProfiles(list)
    fun getProfileJson(id: String) = profileManager.getProfileJson(id)
    fun exportAllProfilesToZip() = profileManager.exportAllProfilesToZip()
    fun exportProfilesToZip(ids: List<String>) = profileManager.exportProfilesToZip(ids)
    fun importProfilesFromZip(s: java.io.InputStream) = profileManager.importProfilesFromZip(s) { selectProfileAndRestart(it) }
    fun importProfiles(data: List<Pair<String?, String>>) = profileManager.importProfiles(data) { selectProfileAndRestart(it) }
    
    fun resetAllSettings(c: Context) {
        viewModelScope.launch {
            c.stopService(Intent(c, ProxyService::class.java))
            c.stopService(Intent(c, XrayService::class.java))
            ProxyServiceState.setStatus(ProxyStatus.Idle)
            XrayServiceState.updateStatus(XrayState.Idle)
            prefs.resetAll()
            proxyManager.clearState()
            AppLogsState.clearLogs()
            val intent = (c as? android.app.Activity)?.intent ?: Intent(c, com.wireturn.app.ui.activities.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            c.startActivity(intent)
        }
    }
}
