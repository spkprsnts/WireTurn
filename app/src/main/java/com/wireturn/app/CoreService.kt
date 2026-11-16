package com.wireturn.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.VpnSettings
import com.wireturn.app.data.WatchdogSettings
import com.wireturn.app.kernel.BinaryOutputState
import com.wireturn.app.kernel.KernelCommandContext
import com.wireturn.app.kernel.KernelLogContext
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.kernel.NetworkQuality
import com.wireturn.app.kernel.canUpdateConnectingStatus
import com.wireturn.app.kernel.markConnecting
import com.wireturn.app.kernel.usesNativeTun
import com.wireturn.app.viewmodel.AppLifecycleState
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds


class CoreService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val process = AtomicReference<Process?>()
    // Non-null (i.e. != -1) only between a qWDTT "CAPTCHA_SOLVE|manual|..." request and its
    // resolution - see observeQwdttCaptchaResult(). Read/written from multiple serviceScope
    // coroutines (Dispatchers.IO is a thread pool, not single-threaded), hence Atomic rather than
    // a plain var - same reasoning as `process` above.
    private val pendingQwdttCaptchaSessionId = AtomicLong(-1L)
    private val userStopped = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)
    private val currentRunningCfg = AtomicReference<ClientConfig?>(null)
    // Serializes stopBinaryProcessGracefully() across all its callers - rapid profile switches can
    // otherwise start a new binary before the old one's process is confirmed dead.
    private val stopMutex = Mutex()

    private val handler = Handler(Looper.getMainLooper())
    // The physical networks and which one traffic goes out through; what a change means for the
    // tunnel is decided here, in onNetworkChanged.
    private val networkMonitor by lazy {
        NetworkMonitor(this, object : NetworkMonitor.Listener {
            override fun onPrimaryNetworkChanged(previousLabel: String) = onNetworkChanged(previousLabel)

            override fun onNetworkCapabilitiesChanged(capabilities: NetworkCapabilities) {
                if (CoreServiceState.status.value is CoreStatus.WaitingForNetwork &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    // Сеть появилась — переходим в Starting, чтобы не сработать повторно, и запускаемся
                    CoreServiceState.setStatus(CoreStatus.Starting)
                    startService(Intent(this@CoreService, CoreService::class.java))
                }
            }
        })
    }
    // The primary network's label before the current debounce window started.
    @Volatile private var networkChangeFrom: String? = null
    @Volatile private var caBundlePath: String? = null
    private var restartCount = 0
    // Specific reason for the current failure, if a handler knows one - shown instead of the
    // generic core_failed message if the watchdog exhausts MAX_RESTARTS.
    private var lastKnownFailureReason: String? = null
    private var minRestartDelayMs = 0L
    // A failure no retry fixes (the kernel isn't built for this ABI, the config turned invalid) -
    // ends the session even with the eternal watchdog. Cleared by a valid config change.
    @Volatile private var nonRetryableFailure = false
    // Cuts the watchdog's wait between attempts short - a network change: with the eternal
    // watchdog's waits of up to WatchdogSettings.maxIntervalMinutes, a working network shouldn't
    // sit out the rest of one.
    private val watchdogWake = Channel<Unit>(Channel.CONFLATED)
    // Set right before a deliberate stop of the running binary (hot-reload, network change) so the
    // supervisor loop restarts it straight away instead of treating the exit as a crash and
    // showing a watchdog "attempt N/M" for it.
    private val plannedRestart = AtomicBoolean(false)

    private lateinit var serviceScope: CoroutineScope
    private var coreJob: Job? = null
    // XrayService and HevVpnService following the session - see their classes.
    private lateinit var xraySupervisor: XraySupervisor
    private lateinit var vpnSupervisor: VpnSupervisor
    private var networkDebounceJob: Job? = null

    // Adapters handing kernel/*/*.kt implementations only what they need from this Service,
    // instead of each one needing direct access to CoreService itself.
    // Set per run when the kernel takes the VPN's TUN itself (usesNativeTun), null otherwise.
    @Volatile private var nativeTunSocket: String? = null
    // Fresh per run, see KernelCommandContext.runSocket.
    @Volatile private var runSocket: String = ""
    // This run's sidecar binary, if its kernel has one (Kernel.buildSidecarCommand).
    private val sidecar = AtomicReference<Process?>()
    // Its output said its listen port was taken - see runBinary's early-death check.
    @Volatile private var sidecarPortInUse = false

    private val commandContext = object : KernelCommandContext {
        override val filesDir get() = this@CoreService.filesDir
        override val nativeLibraryDir get() = applicationInfo.nativeLibraryDir
        override val nativeTunSocket get() = this@CoreService.nativeTunSocket
        override val runSocket get() = this@CoreService.runSocket
        override val networkDnsServers get() = networkMonitor.dnsServers()
        override val androidId: String
            @android.annotation.SuppressLint("HardwareIds")
            get() = runCatching {
            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
    }
    private val logContext = object : KernelLogContext {
        override fun getString(resId: Int, vararg args: Any) = this@CoreService.getString(resId, *args)
        override fun updateNotification(text: String) = this@CoreService.updateNotification(text)
        override fun isNetworkAvailable() = networkMonitor.isAvailable
        override suspend fun getNetworkQuality() = this@CoreService.getNetworkQuality()
        override suspend fun isNetworkMissingAndHandled() = this@CoreService.isNetworkMissingAndHandled()
        override fun launchCaptchaActivityIfForeground(url: String) = this@CoreService.launchCaptchaActivityIfForeground(url)
        override fun setPendingCaptchaSessionId(id: Long) { pendingQwdttCaptchaSessionId.set(id) }
        override fun setLastFailureReason(reason: String) { lastKnownFailureReason = reason }
        override fun setMinRestartDelay(delayMs: Long) { minRestartDelayMs = delayMs }
        override fun onNativeTunConfig(address: String, dns: List<String>, mtu: Int) {
            // Only a kernel started in TUN mode gets here with a socket to hand the tun to.
            val socket = nativeTunSocket ?: return
            if (userStopped.get()) return
            handler.post {
                // Checked again once on the main thread: a stop (or this run giving way to the next
                // one, with its own socket) may have come in between - the tun must not come back up.
                if (userStopped.get() || nativeTunSocket != socket) return@post
                startService(Intent(this@CoreService, HevVpnService::class.java).apply {
                    action = HevVpnService.ACTION_START_NATIVE
                    putExtra(HevVpnService.EXTRA_NATIVE_SOCKET, socket)
                    putExtra(HevVpnService.EXTRA_NATIVE_ADDRESS, address)
                    putExtra(HevVpnService.EXTRA_NATIVE_DNS, dns.toTypedArray())
                    putExtra(HevVpnService.EXTRA_NATIVE_MTU, mtu)
                })
            }
        }
        override fun writeToSidecar(line: String) {
            try {
                sidecar.get()?.outputStream?.let {
                    it.write((line + "\n").toByteArray(Charsets.UTF_8))
                    it.flush()
                }
            } catch (_: Exception) {
            }
        }
        override fun onNativeTunTraffic(rxBytes: Long, txBytes: Long) {
            if (nativeTunSocket == null) return
            CoreServiceState.setNativeTunTraffic(
                CoreServiceState.NativeTunTraffic(rxBytes, txBytes, android.os.SystemClock.elapsedRealtime())
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        NotificationHelper.createChannel(this)
        NotificationHelper.observeStates(this, serviceScope)
        observeCaptchaForNotification()
        observeQwdttCaptchaResult()
        observeErrorForNotification()
        serviceScope.launch {
            AppPreferences(applicationContext).watchdogSettingsFlow.collect {
                CoreServiceState.setErrorsRetried(it.eternal)
            }
        }
        xraySupervisor = XraySupervisor(this, serviceScope).also { it.start() }
        vpnSupervisor = VpnSupervisor(this, serviceScope, ::restartBinaryPlanned).also { it.start() }
        caBundlePath = CaBundleUtil.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP || action == ACTION_STOP_BY_USER) {
            handleStopAction(disableAutoLaunch = action == ACTION_STOP_BY_USER)
            return START_NOT_STICKY
        }

        moveToForeground()

        // При каждом явном вызове Start — перезапускаем цикл, чтобы подхватить возможные изменения в конфиге
        userStopped.set(false)
        CoreServiceState.setStatus(CoreStatus.Starting)
        val previousJob = coreJob
        coreJob = serviceScope.launch {
            // Ждём завершения предыдущего цикла, иначе он может конкурентно тронуть process
            // и потерять ссылку на новый, из-за чего старый бинарник не освободит порт.
            // Kill its process between cancel() and join(), as handleStopAction does: its
            // runBinary() blocks on a readLine() that cancellation can't interrupt, so a bare
            // cancelAndJoin() waits for the old kernel's next output line - with a quiet
            // connected kernel, until the user stops it, while the old profile keeps running.
            // Cancelled first, so its supervisor can't spawn a replacement in between.
            previousJob?.cancel()
            stopBinaryProcessGracefully()
            previousJob?.join()
            stopBinaryProcessGracefully()

            val prefs = AppPreferences(applicationContext)
            val cfg = prefs.clientConfigFlow.first()
            val profileName = prefs.currentProfileNameFlow.first().orEmpty()
            val vlessConfig = prefs.vlessConfigFlow.first()
            val xrayConfig = prefs.xrayConfigFlow.first()

            // Сразу фиксируем работающий конфиг для UI (с заполненными дефолтами)
            val filledCfg = cfg.fillDefaults()
            
            if (!filledCfg.isValid) {
                val errorRes = filledCfg.getValidationErrorResId() ?: R.string.error_settings_empty
                CoreServiceState.setStatus(CoreStatus.Error(getString(errorRes)))
                delay(3_000.milliseconds)
                withContext(Dispatchers.Main) { stopSelf() }
                return@launch
            }

            prefs.saveClientConfig(filledCfg)
            // What runs has the default VK calls filled in (withDefaultVkCalls); the session -
            // which XrayService saves back to the preferences - stays as the profile has it, so the
            // setting is never baked into a profile.
            CoreServiceState.setSession(CoreServiceState.RunningSession(filledCfg, profileName))
            currentRunningCfg.set(filledCfg.withDefaultVkCalls())

            try {
                initStartup(vlessConfig, xrayConfig, profileName)
                mainSupervisor()
            } finally {
                currentRunningCfg.set(null)
                if (!userStopped.get() && isActive) {
                    withContext(Dispatchers.Main) { stopSelf() }
                }
            }
        }

        return START_STICKY
    }

    private fun initStartup(
        vlessConfig: com.wireturn.app.data.VlessConfig,
        xrayConfig: com.wireturn.app.data.XrayConfig,
        profileName: String?
    ) {
        if (!AppLogsState.isEmpty()) {
            AppLogsState.addLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
        val safeProfileName = profileName?.take(50) ?: "-"
        AppLogsState.addLog(getString(R.string.log_core_start, safeProfileName))

        val kernelInfo = currentRunningCfg.get()
            ?.let { KernelRegistry.get(it.kernelVariant).logLabel(it.kernelConfig) } ?: "-"
        val xrayLink = (currentRunningCfg.get()?.kernelConfig as? KernelConfig.Xray)?.config?.link
        val xrayInfo = if (xrayLink != null) {
            // The profile is Xray itself - say which protocol its link speaks.
            getString(com.wireturn.app.ui.ValidatorUtils.uriProtocolStringRes(xrayLink))
        } else if (xrayConfig.enabled) {
            val isXrayVless = xrayConfig.protocol == com.wireturn.app.data.XrayConfiguration.VLESS
            "${xrayConfig.protocol.name}${if (isXrayVless && vlessConfig.isDualRoute) " (Dual-route)" else ""}"
        } else "Disabled"
        AppLogsState.addLog(getString(R.string.log_core_profile_summary, kernelInfo, xrayInfo))

        val isXrayVless = xrayConfig.protocol == com.wireturn.app.data.XrayConfiguration.VLESS
        val isDualRouteStart = xrayConfig.enabled && isXrayVless && vlessConfig.isDualRoute

        NotificationHelper.cancelErrorNotification(this)
        
        if (isDualRouteStart) {
            // В режиме Dual-route стартуем в паузе, чтобы не запускать бинарник зря
            AppLogsState.addLog(getString(R.string.log_core_suppressed))
            CoreServiceState.setStatus(CoreStatus.Suppressed)
            // null, not the literal text - the Suppressed branch in NotificationHelper already
            // derives the right text (connecting/direct route active) from current xrayState.
            CoreServiceState.setStatusText(null)
        } else {
            CoreServiceState.setStatus(CoreStatus.Starting)
        }

        userStopped.set(false)
        restartCount = 0
        lastKnownFailureReason = null
        CoreTileService.requestUpdate(this)

        acquireWakeLock()

        networkChangeFrom = null
        networkMonitor.register()
        registerScreenReceiver()
    }

    /**
     * One lock for the service's whole life, held while it runs and released in onDestroy - the
     * system drops it with the process anyway. Not reference counted, so a start on a running
     * service (a retry after an error) holds the same lock instead of leaking another one. No
     * timeout: a session is as long as the user keeps it up, and a lock that ran out after 24h
     * would let the CPU sleep under a tunnel that's still on.
     */
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WireTurn::BgLock")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (!lock.isHeld) lock.acquire()
    }

    private fun moveToForeground() {
        try {
            val notification = NotificationHelper.buildNotification(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            AppLogsState.addLog(getString(R.string.log_core_foreground_failed, e.message ?: "Unknown"))
        }
    }

    private suspend fun mainSupervisor() = coroutineScope {
        val prefs = AppPreferences(applicationContext)

        launch {
            combine(
                XrayServiceState.state,
                prefs.xrayConfigFlow,
                prefs.vlessConfigFlow,
                XrayServiceState.session
            ) { state, xray, vless, xraySession ->
                val isXrayVless = xray.protocol == com.wireturn.app.data.XrayConfiguration.VLESS

                // Используем снапшот работающего конфига Xray для определения режима Dual-route.
                // Это предотвращает преждевременный запуск бинарника при отключении Dual-route,
                // пока Xray еще не перезагружен с новыми настройками.
                val effectiveVless = if (state != XrayState.Idle) (xraySession?.vless ?: vless) else vless
                val isDualRoute = xray.enabled && isXrayVless && effectiveVless.isDualRoute

                if (isDualRoute) {
                    when (state) {
                        XrayState.DirectRoute -> {
                            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                                AppLogsState.addLog(getString(R.string.log_core_suppressed))
                                CoreServiceState.setStatus(CoreStatus.Suppressed)
                            }
                            updateNotification(getString(R.string.direct_route_active))
                        }
                        // XrayState.Running в режиме Dual-route означает использование туннеля (local route).
                        // Если мы были в паузе (Suppressed), пробуждаем бинарник.
                        XrayState.Running -> {
                            if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                                CoreServiceState.setStatus(CoreStatus.Connecting)
                            }
                        }
                        // Starting, Connecting — пробуждение туннеля при потере прямого
                        // маршрута обрабатывается в XrayService.handleDualRouteLog
                        else -> {}
                    }
                } else if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                    // Режим Dual-route был выключен — пробуждаем туннель немедленно
                    CoreServiceState.setStatus(CoreStatus.Connecting)
                }
            }.collect {}
        }

        launch {
            CoreServiceState.status.collect { status ->
                if (status is CoreStatus.Suppressed) {
                    stopBinaryProcessGracefully()
                }
            }
        }

        // Hot-reload: следим за изменениями конфига туннельного бинарника
        launch {
            prefs.clientConfigFlow
                .drop(1) // пропускаем начальное значение — уже обработано в onStartCommand
                .collect { newCfgRaw ->
                    // A deliberate stop clears the active config right around when
                    // handleStopAction() runs - without this guard that races the real stop.
                    if (userStopped.get()) return@collect
                    val runningCfg = currentRunningCfg.get() ?: return@collect
                    // As at the start: the default VK calls go into what runs - a change to them
                    // restarts the binary too - and not into the session.
                    val sessionCfg = newCfgRaw.fillDefaults()
                    val newCfg = sessionCfg.withDefaultVkCalls()

                    if (!newCfg.isValid) {
                        val errorRes = newCfg.getValidationErrorResId() ?: R.string.error_settings_empty
                        nonRetryableFailure = true
                        CoreServiceState.setStatus(CoreStatus.Error(getString(errorRes)))
                        stopBinaryProcessGracefully()

                        delay(3_000.milliseconds)
                        if (this@launch.isActive && !userStopped.get()) {
                            withContext(Dispatchers.Main) { stopSelf() }
                        }
                        return@collect
                    }

                    val binaryChanged = requiresBinaryRestart(runningCfg, newCfg)
                    currentRunningCfg.set(newCfg)
                    CoreServiceState.setSession(CoreServiceState.RunningSession(sessionCfg, prefs.currentProfileNameFlow.first().orEmpty()))
                    if (binaryChanged) {
                        // A config/profile switch is a deliberate new attempt, not the watchdog
                        // retrying a failure - drop any restart counter left over from before.
                        restartCount = 0
                        nonRetryableFailure = false
                        lastKnownFailureReason = null
                        CoreServiceState.setRestartAttempt(null)

                        val xrayConfig = prefs.xrayConfigFlow.first()
                        val vlessConfig = prefs.vlessConfigFlow.first()
                        val isDualRoute = xrayConfig.enabled &&
                            xrayConfig.protocol == com.wireturn.app.data.XrayConfiguration.VLESS &&
                            vlessConfig.isDualRoute
                        if (isDualRoute && CoreServiceState.status.value !is CoreStatus.Idle) {
                            AppLogsState.addLog(getString(R.string.log_core_dual_route_config_changed))
                            CoreServiceState.setStatus(CoreStatus.Suppressed)
                        } else {
                            AppLogsState.addLog(getString(R.string.log_core_config_changed))
                            CoreServiceState.setStatusText(null)
                            CoreServiceState.setStatus(CoreStatus.Stopping)
                            if (process.get() != null) plannedRestart.set(true)
                            stopBinaryProcessGracefully()
                        }
                    }
                }
        }

        // VPN mode / Xray toggled while running: whether the kernel takes the tun itself
        // (usesNativeTun) is a launch flag, so it's restarted into the other mode. In kernel TUN
        // mode the VPN settings are baked into the tun it already holds, so a change there drops
        // that tun and restarts the kernel, which asks for a new one.
        launch {
            var lastVpnSettings: VpnSettings? = null
            combine(prefs.vpnSettingsFlow, prefs.xrayConfigFlow.map { it.enabled }) { vpn, xrayOn -> vpn to xrayOn }
                .distinctUntilChanged()
                .collect { (vpnSettings, xrayOn) ->
                    val previous = lastVpnSettings
                    lastVpnSettings = vpnSettings
                    if (userStopped.get()) return@collect
                    val cfg = currentRunningCfg.get() ?: return@collect
                    // Process first: the socket is set before it starts, so the two read in this
                    // order describe the same run. With none running, the next run decides anyway.
                    val running = process.get() != null
                    val runningNative = nativeTunSocket != null
                    val wantNative = usesNativeTun(cfg, vpnSettings.enabled, xrayOn)
                    val settingsChanged = previous != null && previous != vpnSettings
                    if (runningNative && wantNative && settingsChanged) {
                        // Even mid-restart: a tun left up would be reused as is by the next run.
                        withContext(Dispatchers.Main) {
                            startService(Intent(this@CoreService, HevVpnService::class.java).apply {
                                action = HevVpnService.ACTION_STOP
                            })
                        }
                        if (running) restartBinaryPlanned(getString(R.string.log_core_tun_settings_changed))
                    } else if (running && wantNative != runningNative) {
                        restartBinaryPlanned(getString(R.string.log_core_tun_mode_changed))
                    }
                }
        }

        while (isActive && !userStopped.get()) {
            // Idle here is a stop on its way, never a run: the tile sets it optimistically before
            // its STOP broadcast arrives, and starting a binary in that gap only has the stop cut
            // it off half-started.
            if (CoreServiceState.status.value is CoreStatus.Suppressed ||
                CoreServiceState.status.value is CoreStatus.Idle
            ) {
                delay(1_000.milliseconds)
                continue
            }

            if (CoreServiceState.status.value is CoreStatus.WaitingForNetwork) {
                // В режиме ожидания сети мы ничего не делаем, пока NetworkCallback не перезапустит нас
                delay(1_000.milliseconds)
                continue
            }

            if (CoreServiceState.status.value is CoreStatus.CaptchaRequired) {
                delay(1_000.milliseconds)
                continue
            }

            val cfg = currentRunningCfg.get() ?: break
            val startTime = android.os.SystemClock.elapsedRealtime()
            // Reset so a stale reason from an earlier, unrelated cycle can't outlive it - only
            // this run's own handler (if any) should set it before the failure check below reads it.
            lastKnownFailureReason = null
            minRestartDelayMs = 0L
            // Decided per run: VPN mode without Xray lets the kernel take the tun itself (no hev).
            // A fresh name each run - the kernel accepts the tun only once per process.
            nativeTunSocket = if (usesNativeTun(cfg, prefs.vpnSettingsFlow.first().enabled, prefs.xrayConfigFlow.first().enabled)) {
                "wireturn_tun_${android.os.Process.myPid()}_${System.nanoTime()}"
            } else null
            runSocket = "wireturn_run_${android.os.Process.myPid()}_${System.nanoTime()}"
            // The kernel's counters start over with the process; a placeholder until its first
            // sample still marks this run as the one the traffic stats come from.
            CoreServiceState.setNativeTunTraffic(
                if (nativeTunSocket != null) CoreServiceState.NativeTunTraffic(0, 0, 0L) else null
            )
            val startupSuccessful = if (KernelRegistry.get(cfg.kernelVariant).runsBinary) runBinary(cfg) else followXray()
            val duration = android.os.SystemClock.elapsedRealtime() - startTime
            
            if (userStopped.get()) break

            // ПРОВЕРКА ПАУЗЫ: Если бинарник был убит супервизором для перехода в DirectRoute,
            // мы НЕ должны запускать логику вотчдога.
            if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                restartCount = 0
                continue
            }

            if (plannedRestart.getAndSet(false)) {
                restartCount = 0
                CoreServiceState.setRestartAttempt(null)
                CoreServiceState.setStatusText(null)
                CoreServiceState.setStatus(CoreStatus.Connecting)
                continue
            }

            if (isNetworkMissingAndHandled()) {
                continue
            }

            val watchdog = prefs.watchdogSettingsFlow.first()
            val currentStatus = CoreServiceState.status.value
            if (!startupSuccessful || currentStatus is CoreStatus.Error) {
                if (currentStatus !is CoreStatus.Error) {
                    AppLogsState.addLog(getString(R.string.log_core_quick_exit, duration))
                    AppLogsState.addLog(getString(R.string.log_core_startup_failed))
                    CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_kernel_or_settings)))
                }
                // The eternal watchdog retries a failed start like any other exit - what this run
                // failed on (a server down, a call gone) may well pass. Only what can't is final.
                if (!watchdog.eternal || nonRetryableFailure) {
                    delay(3_000.milliseconds)
                    if (isActive && !userStopped.get()) {
                        withContext(Dispatchers.Main) { stopSelf() }
                    }
                    break
                }
            }

            if (duration > 300_000) {
                restartCount = 0
            }
            restartCount++
            if (restartCount > MAX_RESTARTS && !watchdog.eternal) {
                AppLogsState.addLog(getString(R.string.log_core_watchdog_limit, MAX_RESTARTS))
                val errorMsg = lastKnownFailureReason ?: getString(R.string.core_failed)
                CoreServiceState.emitFailed(errorMsg)
                if (!AppLifecycleState.isAppInForeground.value) {
                    NotificationHelper.notifyError(this@CoreService, errorMsg)
                }
                withContext(Dispatchers.Main) { stopSelf() }
                break
            }

            CoreServiceState.setStatus(CoreStatus.Connecting)

            var baseDelay = when {
                // Eternal, past the regular limit: backing off however long each run lasted, so a
                // server that's down for hours isn't hammered every connecting timeout.
                restartCount > MAX_RESTARTS ->
                    WatchdogSettings.eternalDelayMs(restartCount - MAX_RESTARTS, watchdog.maxIntervalMs)
                duration > 30_000 -> 1000L
                else -> minOf(1000L * restartCount, 30_000L)
            }
            if (isSlowConnection()) {
                AppLogsState.addLog(getString(R.string.log_core_slow_network_watchdog))
                baseDelay = maxOf(baseDelay, 5000L)
            }
            baseDelay = maxOf(baseDelay, minRestartDelayMs)
            val delayMs = baseDelay + Random.nextLong(0, 500)

            AppLogsState.addLog(
                if (watchdog.eternal) getString(R.string.log_core_watchdog_restart_eternal, delayMs, restartCount)
                else getString(R.string.log_core_watchdog_restart, delayMs, restartCount, MAX_RESTARTS)
            )
            // Clears any stale text left over from the previous run (e.g. "Active") so the
            // restart-attempt text below isn't shadowed by it in the notification/tile.
            CoreServiceState.setStatusText(null)
            CoreServiceState.setRestartAttempt(
                CoreServiceState.RestartAttempt(restartCount, MAX_RESTARTS.takeUnless { watchdog.eternal })
            )

            // A network change during the wait starts the next attempt right away (onNetworkChanged).
            watchdogWake.tryReceive()
            if (withTimeoutOrNull(delayMs.milliseconds) { watchdogWake.receive() } != null) {
                AppLogsState.addLog(getString(R.string.log_core_watchdog_wake))
            }
        }
    }

    // A profile with no binary of its own (Kernel.runsBinary, XrayKernel): the tunnel is Xray
    // itself, which the Xray supervisor starts - this run only mirrors Xray's state into the core
    // status until something ends it. A config change (Stopping, see the hot-reload collector)
    // comes straight back as a planned restart, not as a watchdog attempt.
    private suspend fun followXray(): Boolean {
        markConnecting()
        combine(XrayServiceState.state, CoreServiceState.status) { xray, status -> xray to status }
            .takeWhile { (_, status) ->
                !userStopped.get() && status !is CoreStatus.Stopping &&
                    status !is CoreStatus.Idle && status !is CoreStatus.Error
            }
            .collect { (xray, status) ->
                if (xray == XrayState.Running && status !is CoreStatus.Connected) {
                    CoreServiceState.setStatus(CoreStatus.Connected)
                    updateNotification(getString(R.string.core_active))
                } else if (xray != XrayState.Running && status is CoreStatus.Connected) {
                    markConnecting()
                }
            }
        if (!userStopped.get() && CoreServiceState.status.value is CoreStatus.Stopping) plannedRestart.set(true)
        return true
    }

    private suspend fun runBinary(cfg: ClientConfig): Boolean = coroutineScope {
        val cmdArgs = buildCommandArgs(cfg)

        if (CoreServiceState.status.value is CoreStatus.Error) {
            return@coroutineScope false
        }

        // Not every kernel is built for every ABI (CSQTT has no 32-bit x86 build) - say so
        // instead of a bare "cannot run program".
        if (!java.io.File(cmdArgs[0]).exists()) {
            nonRetryableFailure = true
            CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_kernel_unsupported_device)))
            return@coroutineScope false
        }

        val kernel = KernelRegistry.get(cfg.kernelVariant)
        val state = BinaryOutputState()
        var startedProc: Process? = null

        try {
            val sidecarArgs = kernel.buildSidecarCommand(commandContext, cfg)
            sidecarArgs?.let { startSidecar(it, cfg) }
            AppLogsState.addLog(getString(R.string.log_core_command, redactedCommandLog(cmdArgs, cfg)))

            val proc = withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(cmdArgs)
                    .directory(filesDir)
                    .redirectErrorStream(true)
                val env = builder.environment()
                val nativeLibDir = applicationInfo.nativeLibraryDir
                
                // Lets the spawned binary find shared libraries bundled as jniLibs.
                env["LD_LIBRARY_PATH"] = nativeLibDir
                
                // Add native libs to PATH just in case
                val currentPath = env["PATH"] ?: ""
                if (!currentPath.contains(nativeLibDir)) {
                    env["PATH"] = "$nativeLibDir:$currentPath"
                }

                // Force Go-based binaries to use internal resolver (often avoids IPv6 issues)
                if (cfg.goDnsGo) {
                    env["GODEBUG"] = "netdns=go"
                }

                // Bundled CA store so TLS verification doesn't depend on the device's
                // (possibly stale) system trust store — see ensureCaBundle().
                if (cfg.useCustomCerts) {
                    caBundlePath?.let { env["SSL_CERT_FILE"] = it }
                }
                env.putAll(kernel.environment(commandContext, cfg))

                // Same non-suspending stretch as start() - a suspension point in between risks
                // cancellation orphaning the freshly-spawned process with no reference to clean it up.
                builder.start().also {
                    startedProc = it
                    process.set(it)
                }
            }
            // The receiver only reports changes - a run started (or restarted) with the screen
            // already off is told so here.
            if (!(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) {
                kernel.screenStateCommand(false)?.let(::writeQwdttStdin)
            }

            // The sidecar can die before there's a kernel for its watcher (startSidecar) to stop,
            // which would leave this kernel up with nothing serving it. Checked after
            // process.set(): a death from here on is the watcher's to handle. Dying this early is
            // a setup problem (its port already taken, say) that a restart won't fix - stop here,
            // with its own output in the log saying why.
            if (sidecarArgs != null && sidecar.get() == null) {
                val reason = if (sidecarPortInUse) {
                    getString(R.string.error_sidecar_port_in_use, cfg.socksAddr)
                } else {
                    getString(R.string.error_sidecar_failed)
                }
                CoreServiceState.setStatus(CoreStatus.Error(reason))
                state.startupFailed = true
                sendSigTerm(proc)
            }

            if (kernel.connectingOnStart) {
                state.startupEmitted = true
                if (canUpdateConnectingStatus()) {
                    markConnecting()
                }
            }

            // Watchdog for connection timeout
            val connectingTimeoutMs = KernelRegistry.get(cfg.kernelVariant).connectingTimeoutMs
            val connectionWatchdog = launch {
                while (isActive) {
                    val status = CoreServiceState.status.value
                    if (status is CoreStatus.Connecting) {
                        if (state.connectingSince == 0L) {
                            state.connectingSince = android.os.SystemClock.elapsedRealtime()
                        } else if (android.os.SystemClock.elapsedRealtime() - state.connectingSince > connectingTimeoutMs) {
                            AppLogsState.addLog(getString(R.string.log_core_connection_timeout))
                            state.startupEmitted = true
                            proc.destroy()
                            break
                        }
                    } else {
                        state.connectingSince = 0L
                    }
                    delay(1_000.milliseconds)
                }
            }

            var forceKillJob: Job? = null
            try {
                withContext(Dispatchers.IO) {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var stopping = false
                        for (rawLine in reader.lineSequence()) {
                            if (!isActive) break
                            // Per-packet trace lines: thousands a second under load, so they go
                            // before any other work (ANSI stripping, level detection, parsing).
                            if (kernel.isDiscarded(rawLine)) continue
                            val line = AppLogsState.stripAnsi(rawLine)
                            val level = kernel.logLevel(line) ?: LogLevels.detect(line)
                            AppLogsState.addLog(line, if (kernel.isNoise(line)) LogLevel.DEBUG else level)
                            // Process was intentionally killed (hot-reload/stop) — log but don't update status
                            if (process.get() == null) continue
                            if (stopping) continue
                            if (level == LogLevel.DEBUG && !kernel.parsesDebugLine(line)) continue
                            if (processOutputLine(line, state, cfg)) {
                                // Closing the pipe under a live process kills it with SIGPIPE (exit
                                // 141) before it can clean up - e.g. Go cores never get to release
                                // their TURN allocations, which keeps VK's quota held and makes the
                                // next start hit "Allocation Quota Reached". Ask it to stop with
                                // SIGTERM and keep draining its output until it actually exits.
                                stopping = true
                                sendSigTerm(proc)
                                forceKillJob = this@coroutineScope.launch {
                                    delay(5_000.milliseconds)
                                    proc.destroyForcibly()
                                }
                            }
                        }
                    }
                }
            } finally {
                forceKillJob?.cancel()
                connectionWatchdog.cancel()
            }

            val exitCode = withContext(Dispatchers.IO) {
                if (proc.waitFor(5, TimeUnit.SECONDS)) proc.exitValue() else -1
            }
            AppLogsState.addLog(getString(R.string.log_core_stopped, exitCode))

            val wasKilledIntentionally = process.get() == null
            if (!state.startupEmitted && !state.startupFailed && !wasKilledIntentionally && isActive) {
                CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_process_no_output, exitCode)))
            }

            !state.startupFailed
        } catch (_: InterruptedIOException) {
            true
        } catch (_: CancellationException) {
            true
        } catch (e: Exception) {
            handleProcessException(e)
            false
        } finally {
            CoreServiceState.setCaptchaSession(null)
            // NonCancellable: this finally often runs on an already-cancelled coroutine; without
            // it the IO hop below would throw immediately and leak the old binary process.
            withContext(NonCancellable) {
                stopBinaryProcessGracefully()
                // compareAndSet: if process already points to a NEWER run's process by now, an
                // unconditional set(null) would clear that instead and leak it.
                process.compareAndSet(startedProc, null)
            }
        }
    }

    private suspend fun processOutputLine(line: String, state: BinaryOutputState, cfg: ClientConfig): Boolean {
        val lower = line.lowercase()
        return KernelRegistry.get(cfg.kernelVariant).parseLogLine(line, lower, state, logContext, cfg)
    }

    // MainActivity's own LaunchedEffect(captchaSession) would eventually pick a new session up
    // too, but don't rely solely on that recomposing in time - open the window directly while
    // the app is foreground. Shared by every kernel's captcha flow (see kernel/*/*.kt).
    private fun launchCaptchaActivityIfForeground(url: String) {
        if (!AppLifecycleState.isAppInForeground.value) return
        val intent = Intent(this, com.wireturn.app.ui.activities.CaptchaActivity::class.java).apply {
            putExtra("CAPTCHA_URL", url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    // FreeTurn/Qwdtt/OpenFlux have no file-based config option upstream (unlike the other
    // kernels), so their sensitive values ride the process command line directly - mask those
    // specific flags for the app's own log, which the user may end up sharing for support.
    private fun redactedCommandLog(cmdArgs: List<String>, cfg: ClientConfig): String {
        val sensitiveFlags = KernelRegistry.get(cfg.kernelVariant).sensitiveCommandFlags
        if (sensitiveFlags.isEmpty()) return cmdArgs.joinToString(" ")
        return CommandLogRedactor.redact(cmdArgs, sensitiveFlags)
    }

    private fun buildCommandArgs(cfg: ClientConfig): List<String> =
        KernelRegistry.get(cfg.kernelVariant).buildCommand(commandContext, cfg)


    private fun requiresBinaryRestart(old: ClientConfig, new: ClientConfig): Boolean {
        if (old.kernelConfig != new.kernelConfig) return true
        if (old.goDnsGo != new.goDnsGo) return true
        if (old.useCustomCerts != new.useCustomCerts) return true
        // Same kernel from here (its KernelConfig is equal) - what else its command reads.
        return KernelRegistry.get(new.kernelVariant).localSettingsChanged(old, new)
    }

    // Stops the running binary so the supervisor loop brings it straight back (no watchdog
    // attempt) - picking up anything decided per run, like the TUN mode. No-op if none is running.
    private suspend fun restartBinaryPlanned(logLine: String) {
        if (userStopped.get() || process.get() == null) return
        AppLogsState.addLog(logLine)
        restartCount = 0
        CoreServiceState.setStatusText(null)
        CoreServiceState.setStatus(CoreStatus.Stopping)
        plannedRestart.set(true)
        stopBinaryProcessGracefully()
    }

    private fun handleProcessException(e: Exception) {
        AppLogsState.addLog(getString(R.string.error_critical_format, e.message))
    }

    private suspend fun stopBinaryProcessGracefully() = stopMutex.withLock {
        process.getAndSet(null)?.let { proc ->
            withContext(Dispatchers.IO) {
                sendSigTerm(proc)
                try {
                    if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                    }
                } catch (_: Exception) {
                    proc.destroyForcibly()
                }
            }
        }
        // After the kernel, which may still be using what the sidecar serves it until it exits.
        stopSidecar()
    }

    // See Kernel.buildSidecarCommand. Its output goes to the log as is, levelled by its kernel.
    private suspend fun startSidecar(args: List<String>, cfg: ClientConfig) {
        stopSidecar()
        sidecarPortInUse = false
        val kernel = KernelRegistry.get(cfg.kernelVariant)
        AppLogsState.addLog(getString(R.string.log_core_sidecar_command, redactedCommandLog(args, cfg)))
        // Set inside the IO hop, like runBinary's process: a stop landing during it cancels the
        // coroutine, withContext then throws on return, and a sidecar set only afterwards would be
        // orphaned - still holding its port, with nothing left to stop it.
        val proc = withContext(Dispatchers.IO) {
            ProcessBuilder(args)
                .directory(filesDir)
                .redirectErrorStream(true)
                .apply { environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir }
                .start()
                .also { sidecar.set(it) }
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = AppLogsState.stripAnsi(rawLine)
                        AppLogsState.addLog(line, kernel.logLevel(line) ?: LogLevels.detect(line))
                        // Go's bind error text, e.g. socks2tun's "socks5 listen: ... address already in use".
                        if (line.contains("address already in use", ignoreCase = true)) sidecarPortInUse = true
                    }
                }
            } catch (_: Exception) {
            }
            // Still the current one = nobody stopped it: it died under a running kernel, which
            // is left without what it serves - end the run, the supervisor restarts both.
            if (sidecar.compareAndSet(proc, null)) {
                val exitCode = try { proc.waitFor(); proc.exitValue() } catch (_: Exception) { -1 }
                AppLogsState.addLog(getString(R.string.log_core_sidecar_exited, exitCode))
                process.get()?.let { sendSigTerm(it) }
            }
        }
    }

    private suspend fun stopSidecar() {
        val proc = sidecar.getAndSet(null) ?: return
        withContext(Dispatchers.IO) {
            sendSigTerm(proc)
            try {
                if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
        }
    }

    private fun sendSigTerm(proc: Process) {
        try {
            val field = proc.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            val pid = field.getInt(proc)
            android.os.Process.sendSignal(pid, 15) // SIGTERM
        } catch (_: Exception) {
            proc.destroy()
        }
    }

    private fun observeErrorForNotification() {
        serviceScope.launch {
            combine(
                CoreServiceState.status,
                AppLifecycleState.isAppInForeground
            ) { status, isForeground ->
                status to isForeground
            }.collect { (status, isForeground) ->
                // Not for an Error the eternal watchdog retries: one alert per failed attempt, for
                // hours, would bury everything else - its notification shows the attempts anyway.
                if (status is CoreStatus.Error && !isForeground &&
                    (!CoreServiceState.errorsRetried.value || nonRetryableFailure)
                ) {
                    NotificationHelper.notifyError(this@CoreService, status.message)
                } else if (status is CoreStatus.Connected || status is CoreStatus.Suppressed || status is CoreStatus.WaitingForNetwork || isForeground) {
                    NotificationHelper.cancelErrorNotification(this@CoreService)
                }
            }
        }
    }

    private fun observeCaptchaForNotification() {
        serviceScope.launch {
            combine(
                CoreServiceState.captchaSession,
                AppLifecycleState.isAppInForeground,
                AppPreferences(applicationContext).partialCaptchaAlertsFlow
            ) { session, isForeground, partialAlerts ->
                // A partial one the user opted out of counts as none - nothing to notify about.
                session?.takeIf { !it.partial || partialAlerts } to isForeground
            }.collect { (session, isForeground) ->
                if (session != null && !isForeground) {
                    delay(1_000.milliseconds)
                    if (CoreServiceState.captchaSession.value != null && !AppLifecycleState.isAppInForeground.value) {
                        NotificationHelper.notifyCaptcha(this@CoreService, session.url, session.partial)
                    }
                } else {
                    NotificationHelper.cancelCaptchaNotification(this@CoreService)
                }
            }
        }
    }

    // Bridges the UI-solved captcha token back into the qWDTT process's stdin. Only QwdttKernel
    // calls ctx.setPendingCaptchaSessionId() (see kernel/QwdttKernel.kt), so this is a no-op for
    // every other kernel's captcha sessions (FreeTurn detects its own success from its log output,
    // never emits here).
    private fun observeQwdttCaptchaResult() {
        serviceScope.launch {
            CoreServiceState.captchaResult.collect { (sessionId, token) ->
                // compareAndSet: only the matching, still-pending session acts, and it atomically
                // clears itself first so the dismiss-watcher below can't also treat the
                // setCaptchaSession(null) this causes as a cancellation.
                if (pendingQwdttCaptchaSessionId.compareAndSet(sessionId, -1L)) {
                    writeQwdttStdin("CAPTCHA_RESULT|$token")
                    CoreServiceState.setCaptchaSession(null)
                }
            }
        }
        serviceScope.launch {
            CoreServiceState.captchaSession.collect { session ->
                if (session == null) {
                    val previous = pendingQwdttCaptchaSessionId.getAndSet(-1L)
                    if (previous != -1L) {
                        writeQwdttStdin("CAPTCHA_RESULT|error:cancelled")
                    }
                }
            }
        }
    }

    private fun writeQwdttStdin(line: String) {
        try {
            process.get()?.outputStream?.let {
                it.write((line + "\n").toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (_: Exception) {
        }
    }


    // Screen on/off for kernels that take it (Kernel.screenStateCommand), registered while running.
    private var screenReceiver: android.content.BroadcastReceiver? = null

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> true
                    Intent.ACTION_SCREEN_OFF -> false
                    else -> return
                }
                sendScreenState(on)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // System broadcasts: no export flag needed, but API 33+ wants one stated for a runtime receiver.
        androidx.core.content.ContextCompat.registerReceiver(
            this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
    }

    private fun sendScreenState(screenOn: Boolean) {
        val cfg = currentRunningCfg.get() ?: return
        KernelRegistry.get(cfg.kernelVariant).screenStateCommand(screenOn)?.let(::writeQwdttStdin)
    }

    private fun onNetworkChanged(previousLabel: String) {
        // Several switches within the debounce window are reported as one, from where it began.
        if (networkDebounceJob?.isActive != true) networkChangeFrom = previousLabel
        networkDebounceJob?.cancel()
        networkDebounceJob = serviceScope.launch {
            delay(2_000.milliseconds)
            val from = networkChangeFrom ?: previousLabel
            networkChangeFrom = null
            val to = networkMonitor.primaryLabel
            if (userStopped.get() || currentRunningCfg.get() == null) return@launch
            // Xray is reloaded whatever happens to the kernel: its connections to the server (an
            // Xray-only profile, the direct route) stay on the old network otherwise - see
            // XrayService.reload. Its own setting (on by default): it costs a second and no captcha.
            if (XrayServiceState.state.value != XrayState.Idle &&
                AppPreferences(applicationContext).xraySettingsFlow.first().reloadOnNetworkChange
            ) {
                startService(Intent(this@CoreService, XrayService::class.java).setAction(XrayService.ACTION_RELOAD))
            }
            // Waiting out the watchdog's delay between attempts: the next one goes now, on the new
            // network, its backoff started over.
            if (process.get() == null && CoreServiceState.restartAttempt.value != null) {
                restartCount = 0
                watchdogWake.trySend(Unit)
            }
            // Always logged while the tunnel is up; only a running kernel gets restarted - not one
            // paused for the direct route, nor one already down with the old network (the
            // watchdog's next start is on the new one anyway).
            val restart = CoreServiceState.status.value !is CoreStatus.Suppressed &&
                process.get() != null &&
                AppPreferences(applicationContext).restartOnNetworkChangeFlow.first()
            // Same label both ends: Android brought the same network (type, SIM) up anew - say
            // so rather than "X → X".
            val reconnect = from == to
            if (!restart) {
                AppLogsState.addLog(
                    if (reconnect) getString(R.string.log_core_network_reconnected, to)
                    else getString(R.string.log_core_network_changed, from, to)
                )
                return@launch
            }
            AppLogsState.addLog(
                if (reconnect) getString(R.string.log_core_network_reconnect, to)
                else getString(R.string.log_core_network_change, from, to)
            )
            updateNotification(getString(R.string.notification_network_change))
            restartCount = 0
            plannedRestart.set(true)
            stopBinaryProcessGracefully()
        }
    }

    private fun handleStopAction(disableAutoLaunch: Boolean) {
        if (userStopped.getAndSet(true)) return
        xraySupervisor.stop()
        vpnSupervisor.stop()
        NotificationHelper.cancelErrorNotification(this)
        CoreServiceState.setStatus(CoreStatus.Stopping)
        NotificationHelper.updateNotification(this)
        serviceScope.launch {
            // Kill the process before cancelAndJoin(), not after: coreJob's runBinary() blocks on
            // a synchronous readLine() that cancellation can't interrupt, so cancelAndJoin() alone
            // would hang forever - killing the process first closes stdout and unblocks it.
            stopBinaryProcessGracefully()
            coreJob?.cancelAndJoin()
            nativeTunSocket = null
            CoreServiceState.setNativeTunTraffic(null)

            if (disableAutoLaunch) {
                val prefs = AppPreferences(applicationContext)
                val autoLaunch = prefs.autoLaunchSettingsFlow.first()
                if (autoLaunch.enabled) {
                    prefs.updateAutoLaunchSettings(autoLaunch.copy(enabled = false))
                }
            }
            
            // Explicitly stop Xray and VPN mode when tunnel stops
            withContext(Dispatchers.Main) {
                stopService(Intent(this@CoreService, XrayService::class.java))
                startService(Intent(this@CoreService, HevVpnService::class.java).apply {
                    action = HevVpnService.ACTION_STOP
                })
            }

            stopBinaryProcessGracefully()
            withContext(Dispatchers.Main) {
                CoreServiceState.setStatus(CoreStatus.Idle)
                NotificationHelper.updateNotification(this@CoreService)
                stopSelf()
            }
        }
    }

    private suspend fun isNetworkMissingAndHandled(): Boolean {
        if (!networkMonitor.isAvailable) {
            val prefs = AppPreferences(applicationContext)
            if (prefs.waitForNetworkFlow.first()) {
                if (CoreServiceState.status.value !is CoreStatus.WaitingForNetwork) {
                    AppLogsState.addLog(getString(R.string.log_core_no_network_waiting))
                }
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.WaitingForNetwork)
                    updateNotification(getString(R.string.status_waiting_for_network))
                }
                return true
            }
        }
        return false
    }

    private suspend fun getNetworkQuality(): NetworkQuality = withContext(Dispatchers.IO) {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return@withContext NetworkQuality.OFFLINE
        val caps = cm.getNetworkCapabilities(network) ?: return@withContext NetworkQuality.OFFLINE
        
        // 1. Порог скорости для сотовых сетей снижаем до минимума, так как система часто ошибается
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val speed = caps.linkDownstreamBandwidthKbps
            if (speed in 1..150) return@withContext NetworkQuality.SLOW
        }
        
        // 2. Проверка реальной задержки через TCP-соединение с max.ru (гарантированно доступен в РФ)
        // The name lookup has no timeout of its own (connect's covers only the connection), so a
        // DNS that doesn't answer held every watchdog restart for as long as the system resolver
        // kept trying. The probe runs outside this call - a blocking lookup can't be cancelled -
        // and is waited on only so long: one that takes longer is a slow network anyway.
        val probe = serviceScope.async(Dispatchers.IO) {
            try {
                val start = android.os.SystemClock.elapsedRealtime()
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("max.ru", 80), 1500)
                }
                val rtt = android.os.SystemClock.elapsedRealtime() - start
                // Если ответ шел дольше 800мс — считаем сеть медленной для watchdog
                if (rtt > 800) NetworkQuality.SLOW else NetworkQuality.FAST
            } catch (_: Exception) {
                // Если max.ru недоступен совсем, это не "медленная сеть", а отсутствие интернета
                NetworkQuality.OFFLINE
            }
        }
        withTimeoutOrNull(NETWORK_PROBE_TIMEOUT_MS.milliseconds) { probe.await() }
            ?: NetworkQuality.SLOW.also { probe.cancel() }
    }

    private suspend fun isSlowConnection(): Boolean = getNetworkQuality() == NetworkQuality.SLOW

    private fun updateNotification(text: String) {
        CoreServiceState.setStatusText(text)
    }

    override fun onDestroy() {
        super.onDestroy()
        isStarted.set(false)
        userStopped.set(true)
        currentRunningCfg.set(null)

        CoreServiceState.setErrorsRetried(false)
        CoreServiceState.setStatus(CoreStatus.Idle)

        handler.removeCallbacksAndMessages(null)
        networkMonitor.unregister()
        unregisterScreenReceiver()
        AppLogsState.addLog(getString(R.string.log_core_stop_ui))

        // Safety net: xraySupervisor/vpnSupervisor normally tear these down reactively, but
        // they live on serviceScope, which is cancelled below - if the service dies before they
        // get to react (e.g. watchdog-exhausted path setting Idle right as it fires), HevVpnService
        // can be left orphaned with the system VPN/TUN still established. Stop both unconditionally
        // here so that never depends on supervisor timing.
        stopService(Intent(this, XrayService::class.java))
        if (VpnServiceState.state.value != VpnState.Idle) {
            startService(Intent(this, HevVpnService::class.java).apply {
                action = HevVpnService.ACTION_STOP
            })
        }

        serviceScope.launch {
            stopBinaryProcessGracefully()
            withContext(Dispatchers.Main) {
                NotificationHelper.updateNotification(this@CoreService)
            }
            serviceScope.cancel()
        }

        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    companion object {
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_STOP_BY_USER = "ACTION_STOP_BY_USER"
        const val MAX_RESTARTS = 10
        // getNetworkQuality's whole probe, name lookup included.
        private const val NETWORK_PROBE_TIMEOUT_MS = 2_500L

        fun start(context: Context, cfg: ClientConfig) {
            cfg.getValidationErrorResId()?.let { errorRes ->
                CoreServiceState.setStatus(CoreStatus.Error(context.getString(errorRes)))
                return
            }
            // Starting immediately lets UI/CoreManager know a new attempt began even after an error.
            CoreServiceState.setStatus(CoreStatus.Starting)
            // A deliberate new attempt, not the watchdog retrying - drop any stale restart count.
            CoreServiceState.setRestartAttempt(null)
            context.startForegroundService(Intent(context, CoreService::class.java))
        }

        fun stop(context: Context, byUser: Boolean = false) {
            val intent = Intent(context, CoreService::class.java).apply {
                action = if (byUser) ACTION_STOP_BY_USER else ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
