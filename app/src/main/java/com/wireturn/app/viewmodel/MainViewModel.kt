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
import com.wireturn.app.CoreService
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.CoreTileService
import com.wireturn.app.XrayService
import com.wireturn.app.R
import com.wireturn.app.XrayServiceState
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.AutoLaunchSettings
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.OlcrtcConfig
import com.wireturn.app.data.TurnableConfig
import com.wireturn.app.data.VpnSettings
import com.wireturn.app.data.Profile
import com.wireturn.app.data.ThemeMode
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.XraySettings
import com.wireturn.app.domain.AppUpdater
import com.wireturn.app.domain.CoreManager
import com.wireturn.app.domain.ProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.system.measureTimeMillis

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val coreManager = CoreManager(application)
    private val appUpdater = AppUpdater(application)
    private val profileManager = ProfileManager(
        prefs = prefs,
        scope = ProcessLifecycleOwner.get().lifecycleScope
    )

    val coreState: StateFlow<CoreState> = coreManager.coreState
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

    val privacyMode: StateFlow<Boolean> = prefs.privacyModeFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    private val _appLanguage = MutableStateFlow("system")
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _wgConfig = MutableStateFlow(WgConfig())
    val wgConfig: StateFlow<WgConfig> = _wgConfig.asStateFlow()

    private val _xrayConfig = MutableStateFlow(XrayConfig())
    val xrayConfig: StateFlow<XrayConfig> = _xrayConfig.asStateFlow()

    private val _vpnSettings = MutableStateFlow(VpnSettings())
    val vpnSettings: StateFlow<VpnSettings> = _vpnSettings.asStateFlow()

    private val _xraySettings = MutableStateFlow(XraySettings())
    val xraySettings: StateFlow<XraySettings> = _xraySettings.asStateFlow()

    private val _vlessConfig = MutableStateFlow(VlessConfig())
    val vlessConfig: StateFlow<VlessConfig> = _vlessConfig.asStateFlow()

    private val _vlessLinkHistory = MutableStateFlow<List<String>>(emptyList())
    val vlessLinkHistory: StateFlow<List<String>> = _vlessLinkHistory.asStateFlow()

    private val _autoLaunchSettings = MutableStateFlow(AutoLaunchSettings())
    val autoLaunchSettings: StateFlow<AutoLaunchSettings> = _autoLaunchSettings.asStateFlow()

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

    private val _olcrtcSocksAddr = MutableStateFlow("")
    private val _olcrtcSocksAuthEnabled = MutableStateFlow(true)
    private val _olcrtcSocksUser = MutableStateFlow("")
    private val _olcrtcSocksPass = MutableStateFlow("")

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
            _vpnSettings.value = prefs.vpnSettingsFlow.first()
            _batteryNotificationDismissed.value = prefs.batteryNotificationDismissedFlow.first()
            _appsExclusionHintShown.value = prefs.appsExclusionHintShownFlow.first()
            _allowUnstableUpdates.value = prefs.allowUnstableUpdatesFlow.first()
            _waitForNetwork.value = prefs.waitForNetworkFlow.first()
            _restartOnNetworkChange.value = prefs.restartOnNetworkChangeFlow.first()
            _captchaStyleMod.value = prefs.captchaStyleModFlow.first()
            _captchaForceTint.value = prefs.captchaForceTintFlow.first()
            _appLanguage.value = prefs.appLanguageFlow.first()
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
            launch { prefs.vpnSettingsFlow.collect { _vpnSettings.value = it } }
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
            launch { prefs.appLanguageFlow.collect { _appLanguage.value = it } }
            launch { prefs.autoLaunchSettingsFlow.collect { _autoLaunchSettings.value = it; updateAutoLaunchJob(it) } }
            launch { prefs.vlessLinkHistoryFlow.collect { _vlessLinkHistory.value = it } }

            launch {
                var isFirstEmission = true
                allowUnstableUpdates
                    .debounce(2000)
                    .collect { 
                        appUpdater.checkForUpdate(silent = true, allowUnstable = it, force = !isFirstEmission)
                        isFirstEmission = false
                    }
            }

            launch { prefs.olcrtcSocksAddrFlow.collect { _olcrtcSocksAddr.value = it } }
            launch { prefs.olcrtcSocksAuthEnabledFlow.collect { _olcrtcSocksAuthEnabled.value = it } }
            launch { prefs.olcrtcSocksUserFlow.collect { _olcrtcSocksUser.value = it } }
            launch { prefs.olcrtcSocksPassFlow.collect { _olcrtcSocksPass.value = it } }
        }

        viewModelScope.launch { coreManager.observeCoreLifecycle() }
        viewModelScope.launch { coreManager.observeCoreServiceStatus() }
        viewModelScope.launch { coreManager.observeCaptchaEvents() }
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
        coreManager.syncInitialState()
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
        coreManager.destroy()
    }

    fun setDynamicTheme(enabled: Boolean) { 
        viewModelScope.launch { prefs.setDynamicTheme(enabled) } 
    }
    
    fun setThemeMode(mode: ThemeMode) { 
        viewModelScope.launch { prefs.setThemeMode(mode) } 
    }

    fun setPrivacyMode(enabled: Boolean) { 
        viewModelScope.launch { prefs.setPrivacyMode(enabled) } 
    }

    fun updateAutoLaunchSettings(settings: AutoLaunchSettings) {
        viewModelScope.launch { 
            prefs.updateAutoLaunchSettings(settings)
            CoreTileService.requestUpdate(getApplication())
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
            autoLaunchJob = ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
                while (true) {
                    val isReachable = isUrlReachable(settings.checkUrl)
                    val isRunning = CoreServiceState.isRunning.value
                    if (!isReachable && !isRunning) {
                        withContext(Dispatchers.Main) { startCore() }
                    } else if (isReachable && isRunning) {
                        withContext(Dispatchers.Main) { 
                            CoreService.stop(getApplication(), byUser = false)
                        }
                    }
                    delay(settings.intervalMinutes * 60 * 1000L)
                }
            }
        }
    }

    fun startCore() {
        ProcessLifecycleOwner.get().lifecycleScope.launch { startCoreInternal() }
    }
    
    private suspend fun startCoreInternal(forceRestart: Boolean = false) {
        coreManager.startCore(clientConfig.value, forceRestart)
    }

    fun stopCore() { coreManager.stopCore() }

    fun dismissCaptcha() { coreManager.dismissCaptcha() }
    fun clearLogs() { AppLogsState.clearLogs() }
    
    fun saveClientConfig(config: ClientConfig) {
        viewModelScope.launch {
            prefs.saveClientConfig(config)
            val profile = profiles.value.find { it.id == currentProfileId.value } ?: return@launch
            val updatedProfile = when (val k = config.kernelConfig) {
                is KernelConfig.Turnable -> profile.copy(kernelVariant = KernelVariant.TURNABLE, turnableConfig = k.config)
                is KernelConfig.Olcrtc -> profile.copy(kernelVariant = KernelVariant.OLCRTC, olcrtcConfig = k.config)
            }
            prefs.saveActiveProfilePart(updatedProfile)
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

    fun setCaptchaStyleMod(v: Boolean) {
        viewModelScope.launch { prefs.setCaptchaStyleMod(v) } 
    }
    
    fun setCaptchaForceTint(v: Boolean) { 
        viewModelScope.launch { prefs.setCaptchaForceTint(v) } 
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
        val addr = XrayServiceState.session.value?.settings?.connectableAddress ?: return
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
                        AppLogsState.addLog("* [Ping] Error: ${e.message}")
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
        _wgConfig.value = c
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveWgConfig(c)
            updateCurrentProfileInList()
        }
    }

    fun updateXraySettings(s: XraySettings) {
        _xraySettings.value = s
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveXraySettings(s)
            updateCurrentProfileInList()
        }
    }

    fun saveVpnSettings(s: VpnSettings) {
        viewModelScope.launch { prefs.saveVpnSettings(s) }
    }

    fun toggleAppExclusion(p: String) {
        val cur = _vpnSettings.value.excludedApps
        viewModelScope.launch {
            prefs.saveExcludedApps(if (cur.contains(p)) cur - p else cur + p)
        }
    }

    fun saveExcludedApps(s: Set<String>) {
        viewModelScope.launch { prefs.saveExcludedApps(s) }
    }

    fun updateXrayConfig(c: XrayConfig) {
        _xrayConfig.value = c
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveXrayConfig(c)
            updateCurrentProfileInList()
        }
    }

    fun updateVlessConfig(c: VlessConfig) {
        _vlessConfig.value = c
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            prefs.saveVlessConfig(c)
            updateCurrentProfileInList()
        }
    }

    private fun updateCurrentProfileInList() {
        val curId = currentProfileId.value
        val profile = profiles.value.find { it.id == curId } ?: return
        val withKernel = when (val k = _clientConfig.value.kernelConfig) {
            is KernelConfig.Turnable -> profile.copy(kernelVariant = KernelVariant.TURNABLE, turnableConfig = k.config)
            is KernelConfig.Olcrtc -> profile.copy(kernelVariant = KernelVariant.OLCRTC, olcrtcConfig = k.config)
        }
        profileManager.updateCurrentProfile(withKernel.copy(
            xrayProtocol = _xrayConfig.value.protocol,
            xrayEnabled = _xrayConfig.value.enabled,
            wgConfig = _wgConfig.value,
            vlessConfig = _vlessConfig.value
        ))
    }

    fun selectProfileAndRestart(id: String, profile: Profile? = null, onCompletion: (() -> Unit)? = null) {
        val target = profile ?: profiles.value.find { it.id == id } ?: return
        val wasRunning = CoreServiceState.isRunning.value || XrayServiceState.state.value != XrayState.Idle
        if (wasRunning) {
            CoreServiceState.setStatusText(null)
            CoreServiceState.setRestarting(true)
        }
        
        profileManager.selectProfile(id, target) { p ->
            viewModelScope.launch {
                try {
                    prefs.saveFullProfile(p.id, p)
                } finally {
                    if (!wasRunning) {
                        CoreServiceState.setRestarting(false)
                    } else {
                        // Safety timeout reset for isRestarting
                        delay(2000)
                        if (CoreServiceState.isRestarting.value) {
                            CoreServiceState.setRestarting(false)
                        }
                    }
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
        val defaultName = getApplication<Application>().getString(R.string.profile_default_name)
        val kernelVariant = clientConfig.kernelVariant
        val turnableConfig = (clientConfig.kernelConfig as? KernelConfig.Turnable)?.config ?: TurnableConfig()
        val olcrtcConfig = (clientConfig.kernelConfig as? KernelConfig.Olcrtc)?.config ?: OlcrtcConfig()
        val newProfile = Profile(
            id = id,
            name = name,
            kernelVariant = kernelVariant,
            turnableConfig = turnableConfig,
            olcrtcConfig = olcrtcConfig,
            xrayProtocol = xrayConfig.protocol,
            xrayEnabled = xrayConfig.enabled,
            wgConfig = wgConfig,
            vlessConfig = vlessConfig
        ).sanitize(defaultName)
        val wasEmpty = profiles.value.isEmpty()
        viewModelScope.launch {
            prefs.saveProfiles(profiles.value + newProfile)
            if (wasEmpty) {
                selectProfileAndRestart(id, newProfile)
            }
        }
    }

    fun cloneProfile(id: String, name: String) = profileManager.cloneProfile(id, name)
    fun deleteProfile(id: String) = deleteProfiles(listOf(id))
    fun deleteProfiles(ids: List<String>) {
        val willBeEmpty = (profiles.value.size - ids.size) <= 0
        if (willBeEmpty) {
            if (CoreServiceState.isRunning.value) stopCore()
            viewModelScope.launch { prefs.clearActiveProfile() }
        }
        profileManager.deleteProfiles(ids) { nextId, p -> selectProfileAndRestart(nextId, p) }
    }
    fun renameProfile(id: String, name: String) = profileManager.renameProfile(id, name)
    fun reorderProfiles(list: List<Profile>) = profileManager.reorderProfiles(list)
    fun getProfileJson(id: String) = profileManager.getProfileJson(id)
    fun exportAllProfilesToZip() = profileManager.exportAllProfilesToZip()
    fun exportProfilesToZip(ids: List<String>) = profileManager.exportProfilesToZip(ids)
    fun importProfilesFromZip(s: java.io.InputStream) = profileManager.importProfilesFromZip(s) { selectProfileAndRestart(it.id, it) }
    fun importProfiles(data: List<Pair<String?, String>>) = profileManager.importProfiles(data) { selectProfileAndRestart(it.id, it) }
    
    fun resetAllSettings(c: Context) {
        viewModelScope.launch {
            c.stopService(Intent(c, CoreService::class.java))
            c.stopService(Intent(c, XrayService::class.java))
            CoreServiceState.setStatus(CoreStatus.Idle)
            XrayServiceState.updateStatus(XrayState.Idle)
            prefs.resetAll()
            coreManager.clearState()
            AppLogsState.clearLogs()
            val intent = Intent(c, com.wireturn.app.ui.activities.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            c.startActivity(intent)
        }
    }

    companion object {
        private var autoLaunchJob: Job? = null
    }
}
