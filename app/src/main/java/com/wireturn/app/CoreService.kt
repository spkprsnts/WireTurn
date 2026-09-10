package com.wireturn.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.VpnSettings
import com.wireturn.app.kernel.BinaryOutputState
import com.wireturn.app.kernel.KernelCommandContext
import com.wireturn.app.kernel.KernelLogContext
import com.wireturn.app.kernel.KernelRegistry
import com.wireturn.app.kernel.NetworkQuality
import com.wireturn.app.viewmodel.AppLifecycleState
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val availablePhysicalNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    private var lastNetworkHandle: Long = -1
    @Volatile private var caBundlePath: String? = null
    private var restartCount = 0
    // Specific reason for the current failure, if a handler knows one - shown instead of the
    // generic core_failed message if the watchdog exhausts MAX_RESTARTS.
    private var lastKnownFailureReason: String? = null

    private lateinit var serviceScope: CoroutineScope
    private var coreJob: Job? = null
    private var xraySupervisorJob: Job? = null
    private var vpnSupervisorJob: Job? = null
    private var networkDebounceJob: Job? = null

    // Adapters handing kernel/*/*.kt implementations only what they need from this Service,
    // instead of each one needing direct access to CoreService itself.
    private val commandContext = object : KernelCommandContext {
        override val filesDir get() = this@CoreService.filesDir
        override val nativeLibraryDir get() = applicationInfo.nativeLibraryDir
    }
    private val logContext = object : KernelLogContext {
        override fun getString(resId: Int, vararg args: Any) = this@CoreService.getString(resId, *args)
        override fun updateNotification(text: String) = this@CoreService.updateNotification(text)
        override fun isNetworkAvailable() = this@CoreService.isNetworkAvailable()
        override suspend fun getNetworkQuality() = this@CoreService.getNetworkQuality()
        override suspend fun isNetworkMissingAndHandled() = this@CoreService.isNetworkMissingAndHandled()
        override fun launchCaptchaActivityIfForeground(url: String) = this@CoreService.launchCaptchaActivityIfForeground(url)
        override fun setPendingCaptchaSessionId(id: Long) { pendingQwdttCaptchaSessionId.set(id) }
        override fun setLastFailureReason(reason: String) { lastKnownFailureReason = reason }
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
        startXraySupervisor()
        startVpnSupervisor()
        caBundlePath = ensureCaBundle()
    }

    /**
     * Go binaries on Android only trust /system/etc/security/cacerts, which on old/unpatched
     * devices (no OTA updates, no GMS) may be missing CAs that sites rotated in since. Bundling
     * our own up-to-date root store and pointing SSL_CERT_FILE at it sidesteps that entirely.
     */
    private fun ensureCaBundle(): String? {
        val target = java.io.File(filesDir, "cacert.pem")
        return try {
            val assetBytes = assets.open("cacert.pem").use { it.readBytes() }
            if (!target.exists() || target.length() != assetBytes.size.toLong()) {
                target.writeBytes(assetBytes)
            }
            target.absolutePath
        } catch (e: Exception) {
            AppLogsState.addLog("CA bundle extract failed: ${e.message}")
            null
        }
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
            previousJob?.cancelAndJoin()
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
            CoreServiceState.setSession(CoreServiceState.RunningSession(filledCfg, profileName))
            currentRunningCfg.set(filledCfg)

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
        if (AppLogsState.logs.value.isNotEmpty()) {
            AppLogsState.addLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
        val safeProfileName = profileName?.take(50) ?: "-"
        AppLogsState.addLog(getString(R.string.log_core_start, safeProfileName))

        val kernelInfo = when (val k = currentRunningCfg.get()?.kernelConfig) {
            is KernelConfig.Turnable -> "Turnable (${k.config.selectedRouteId})"
            is KernelConfig.Olcrtc -> "Olcrtc (${k.config.provider})"
            is KernelConfig.Webdav -> "WebDAV (${k.config.webdav.take(20)})"
            is KernelConfig.FreeTurn -> "FreeTurn (${k.config.peer})"
            is KernelConfig.Qwdtt -> "qWDTT (${k.config.peer})"
            is KernelConfig.OpenFlux -> "OpenFlux (${k.config.transport})"
            else -> "-"
        }
        val xrayInfo = if (xrayConfig.enabled) {
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

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WireTurn::BgLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))

        registerNetworkCallback()
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

        // Реактивное управление состоянием паузы (Suppressed)
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
                    val newCfg = newCfgRaw.fillDefaults()

                    if (!newCfg.isValid) {
                        val errorRes = newCfg.getValidationErrorResId() ?: R.string.error_settings_empty
                        CoreServiceState.setStatus(CoreStatus.Error(getString(errorRes)))
                        
                        // Stop current binary as we are moving to an invalid state
                        stopBinaryProcessGracefully()

                        delay(3_000.milliseconds)
                        if (isActive && !userStopped.get()) {
                            withContext(Dispatchers.Main) { stopSelf() }
                        }
                        return@collect
                    }

                    val binaryChanged = requiresBinaryRestart(runningCfg, newCfg)
                    currentRunningCfg.set(newCfg)
                    CoreServiceState.setSession(CoreServiceState.RunningSession(newCfg, prefs.currentProfileNameFlow.first().orEmpty()))
                    if (binaryChanged) {
                        // A config/profile switch is a deliberate new attempt, not the watchdog
                        // retrying a failure - drop any restart counter left over from before.
                        restartCount = 0
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
                            stopBinaryProcessGracefully()
                        }
                    }
                }
        }

        while (isActive && !userStopped.get()) {
            if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                // Если мы в режиме паузы, просто ждем сигнала к пробуждению
                delay(1_000.milliseconds)
                continue
            }

            if (CoreServiceState.status.value is CoreStatus.WaitingForNetwork) {
                // В режиме ожидания сети мы ничего не делаем, пока NetworkCallback не перезапустит нас
                delay(1_000.milliseconds)
                continue
            }

            if (CoreServiceState.status.value is CoreStatus.CaptchaRequired) {
                // Ждем решения капчи, не перезапуская бинарник
                delay(1_000.milliseconds)
                continue
            }

            val cfg = currentRunningCfg.get() ?: break
            val startTime = System.currentTimeMillis()
            // Reset so a stale reason from an earlier, unrelated cycle can't outlive it - only
            // this run's own handler (if any) should set it before the failure check below reads it.
            lastKnownFailureReason = null
            val startupSuccessful = runBinary(cfg)
            val duration = System.currentTimeMillis() - startTime
            
            if (userStopped.get()) break

            // ПРОВЕРКА ПАУЗЫ: Если бинарник был убит супервизором для перехода в DirectRoute,
            // мы НЕ должны запускать логику вотчдога.
            if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                restartCount = 0
                continue
            }

            // В ЛЮБОМ СЛУЧАЕ проверяем сеть, если процесс упал не по воле пользователя
            if (isNetworkMissingAndHandled()) {
                continue
            }

            // Check for rapid failure
            val currentStatus = CoreServiceState.status.value
            if (!startupSuccessful || currentStatus is CoreStatus.Error) {
                if (currentStatus !is CoreStatus.Error) {
                    AppLogsState.addLog(getString(R.string.log_core_quick_exit, duration))
                    AppLogsState.addLog(getString(R.string.log_core_startup_failed))
                    CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_kernel_or_settings)))
                }
                delay(3_000.milliseconds)
                if (isActive && !userStopped.get()) {
                    withContext(Dispatchers.Main) { stopSelf() }
                }
                break
            }

            // Logic for restarts
            if (duration > 300_000) {
                restartCount = 0
            }
            restartCount++
            if (restartCount > MAX_RESTARTS) {
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
            
            var baseDelay = if (duration > 30_000) 1000L else minOf(1000L * restartCount, 30_000L)
            if (isSlowConnection()) {
                AppLogsState.addLog(getString(R.string.log_core_slow_network_watchdog))
                baseDelay = maxOf(baseDelay, 5000L)
            }
            val delayMs = baseDelay + Random.nextLong(0, 500)
            
            AppLogsState.addLog(getString(R.string.log_core_watchdog_restart, delayMs, restartCount, MAX_RESTARTS))
            // Clears any stale text left over from the previous run (e.g. "Active") so the
            // restart-attempt text below isn't shadowed by it in the notification/tile.
            CoreServiceState.setStatusText(null)
            CoreServiceState.setRestartAttempt(CoreServiceState.RestartAttempt(restartCount, MAX_RESTARTS))

            delay(delayMs.milliseconds)
        }
    }

    private suspend fun runBinary(cfg: ClientConfig): Boolean = coroutineScope {
        val cmdArgs = buildCommandArgs(cfg)

        if (CoreServiceState.status.value is CoreStatus.Error) {
            return@coroutineScope false
        }

        val state = BinaryOutputState()
        var startedProc: Process? = null

        try {
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

                // Same non-suspending stretch as start() - a suspension point in between risks
                // cancellation orphaning the freshly-spawned process with no reference to clean it up.
                builder.start().also {
                    startedProc = it
                    process.set(it)
                }
            }

            if (cfg.kernelVariant == KernelVariant.OLCRTC) {
                state.startupEmitted = true
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Connecting)
                    // null, not the literal text - lets a watchdog restart's "Restarting (N/M)"
                    // show through instead of being clobbered by a redundant "Connecting".
                    CoreServiceState.setStatusText(null)
                }
            }

            // Watchdog for connection timeout
            val connectionWatchdog = launch {
                while (isActive) {
                    val status = CoreServiceState.status.value
                    if (status is CoreStatus.Connecting) {
                        if (state.connectingSince == 0L) {
                            state.connectingSince = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - state.connectingSince > 120_000) {
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

            try {
                withContext(Dispatchers.IO) {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        for (rawLine in reader.lineSequence()) {
                            if (!isActive) break
                            val line = AppLogsState.stripAnsi(rawLine)
                            AppLogsState.addLog(line)
                            // Process was intentionally killed (hot-reload/stop) — log but don't update status
                            if (process.get() == null) continue
                            if (processOutputLine(line, state, cfg)) break
                        }
                    }
                }
            } finally {
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
        return when (new.kernelConfig) {
            is KernelConfig.Turnable -> old.listenAddr != new.listenAddr
            is KernelConfig.Olcrtc ->
                old.socksAddr != new.socksAddr ||
                old.isSocksAuthEnabled != new.isSocksAuthEnabled ||
                old.socksUser != new.socksUser ||
                old.socksPass != new.socksPass
            is KernelConfig.Webdav ->
                old.socksAddr != new.socksAddr ||
                old.isSocksAuthEnabled != new.isSocksAuthEnabled ||
                old.socksUser != new.socksUser ||
                old.socksPass != new.socksPass
            is KernelConfig.FreeTurn -> old.listenAddr != new.listenAddr
            is KernelConfig.Qwdtt ->
                old.listenAddr != new.listenAddr ||
                old.socksAddr != new.socksAddr ||
                old.isSocksAuthEnabled != new.isSocksAuthEnabled ||
                old.socksUser != new.socksUser ||
                old.socksPass != new.socksPass
            // No SOCKS5 auth flags exist upstream - only socksAddr matters (see buildCommandArgs).
            is KernelConfig.OpenFlux -> old.socksAddr != new.socksAddr
        }
    }

    private fun handleProcessException(e: Exception) {
        AppLogsState.addLog(getString(R.string.error_critical_format, e.message))
    }

    private suspend fun stopBinaryProcessGracefully() = stopMutex.withLock {
        val proc = process.getAndSet(null) ?: return@withLock
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

    private fun startXraySupervisor() {
        xraySupervisorJob?.cancel()
        xraySupervisorJob = serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            val configFlow = combine(
                prefs.xrayConfigFlow,
                prefs.vlessConfigFlow,
                prefs.wgConfigFlow,
                prefs.xraySettingsFlow
            ) { xray, vless, wg, settings ->
                XrayConfigSignals(xray, vless.fillDefaults(), wg.fillDefaults(), settings.fillDefaults())
            }
            combine(
                configFlow,
                CoreServiceState.status,
                XrayServiceState.state,
                CoreServiceState.session
            ) { signals, status, xrayState, coreSession ->
                val clientConfig = coreSession?.clientConfig ?: return@combine null

                val shouldBeRunning = signals.xrayConfig.enabled &&
                        status !is CoreStatus.Idle &&
                        status !is CoreStatus.Error &&
                        status !is CoreStatus.WaitingForNetwork

                val connectionTarget = if (clientConfig.kernelVariant.isSocks5Core) clientConfig.socksAddr else clientConfig.listenAddr
                val connectionAuth = if (clientConfig.kernelVariant.isSocks5Core) {
                    Triple(clientConfig.isSocksAuthEnabled, clientConfig.socksUser, clientConfig.socksPass)
                } else null

                XraySupervisorBundle(
                    shouldBeRunning = shouldBeRunning,
                    xrayState = xrayState,
                    signals = signals,
                    kernelVariant = clientConfig.kernelVariant,
                    connectionTarget = connectionTarget,
                    connectionAuth = connectionAuth
                )
            }
            .filterNotNull()
            .distinctUntilChanged { old, new ->
                if (old.shouldBeRunning != new.shouldBeRunning) return@distinctUntilChanged false
                if (!new.shouldBeRunning) return@distinctUntilChanged new.xrayState == XrayState.Idle
                return@distinctUntilChanged new.xrayState != XrayState.Idle &&
                    old.signals == new.signals &&
                    old.kernelVariant == new.kernelVariant &&
                    old.connectionTarget == new.connectionTarget &&
                    old.connectionAuth == new.connectionAuth
            }
            .collectLatest { data: XraySupervisorBundle ->
                val needsStart = data.shouldBeRunning && data.xrayState == XrayState.Idle
                val needsStop = !data.shouldBeRunning && data.xrayState != XrayState.Idle
                val needsRestart = data.shouldBeRunning && data.xrayState != XrayState.Idle

                if (needsRestart) {
                    AppLogsState.addLog(getString(R.string.log_xray_config_change_restart))
                    withContext(Dispatchers.Main) {
                        stopService(Intent(this@CoreService, XrayService::class.java))
                        delay(500.milliseconds)
                        startForegroundService(Intent(this@CoreService, XrayService::class.java))
                    }
                } else if (needsStart) {
                    delay(500.milliseconds) // Debounce during profile switches
                    withContext(Dispatchers.Main) {
                        val currentXrayState = XrayServiceState.state.value
                        val currentStatus = CoreServiceState.status.value
                        if (currentXrayState == XrayState.Idle && currentStatus !is CoreStatus.Idle && currentStatus !is CoreStatus.Error) {
                            startForegroundService(Intent(this@CoreService, XrayService::class.java))
                        }
                    }
                } else if (needsStop) {
                    withContext(Dispatchers.Main) {
                        stopService(Intent(this@CoreService, XrayService::class.java))
                    }
                }
            }
        }
    }

    // OLCRTC/WEBDAV run their own local SOCKS5 listener, so VPN mode can target those directly
    // when Xray isn't in the picture. Xray keeps priority when running, since it may itself be
    // wrapping one of those cores as a front proxy (see startXraySupervisor() above).
    private data class VpnTarget(val addr: String, val user: String?, val pass: String?)

    // terminalError: the core isn't coming back on its own (watchdog gave up / invalid config),
    // as opposed to a transient gap (mid-retry, switching profiles) that's expected to recover.
    private data class VpnTargetSignal(val target: VpnTarget?, val terminalError: Boolean)

    private data class VpnSupervisorBundle(
        val signal: VpnTargetSignal,
        val vpnSettings: VpnSettings,
        val vpnState: VpnState
    )

    private fun startVpnSupervisor() {
        vpnSupervisorJob?.cancel()
        vpnSupervisorJob = serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            var lastVpnSettings: VpnSettings? = null
            var lastTarget: VpnTarget? = null
            // Debounces tearing the VPN down when the target transiently disappears (e.g.
            // switching profiles) - only actually stops if it doesn't reappear in time.
            var pendingStopJob: Job? = null
            // Retries a failed establish() (e.g. another app still held VPN ownership) on a
            // fixed backoff - without this, VpnState.Error only ever gets retried when settings
            // or target happen to change, and can otherwise sit there forever.
            var pendingVpnRetryJob: Job? = null

            fun stopIntent() = Intent(this@CoreService, HevVpnService::class.java).apply {
                action = HevVpnService.ACTION_STOP
            }
            fun startIntent(target: VpnTarget) = Intent(this@CoreService, HevVpnService::class.java).apply {
                putExtra(HevVpnService.EXTRA_SOCKS5_ADDR, target.addr)
                if (target.user != null) {
                    putExtra(HevVpnService.EXTRA_SOCKS5_USER, target.user)
                    putExtra(HevVpnService.EXTRA_SOCKS5_PASS, target.pass)
                }
            }
            fun retargetIntent(target: VpnTarget) = startIntent(target).apply {
                action = HevVpnService.ACTION_UPDATE_TARGET
            }

            val targetFlow = combine(
                XrayServiceState.state,
                XrayServiceState.session,
                CoreServiceState.status,
                CoreServiceState.session
            ) { xrayState, xraySession, coreStatus, coreSession ->
                if (xrayState != XrayState.Idle && xraySession != null) {
                    val s = xraySession.settings
                    VpnTargetSignal(
                        VpnTarget(
                            s.connectableAddress,
                            s.proxyUser.takeIf { s.isProxyAuthEnabled && it.isNotBlank() },
                            s.proxyPass
                        ),
                        terminalError = false
                    )
                } else if (coreSession != null &&
                    coreSession.clientConfig.kernelVariant.isSocks5Core &&
                    coreStatus !is CoreStatus.Idle && coreStatus !is CoreStatus.Error && coreStatus !is CoreStatus.WaitingForNetwork
                ) {
                    val cc = coreSession.clientConfig
                    VpnTargetSignal(
                        VpnTarget(
                            // socksAddr may be bound to 0.0.0.0; hev needs a literal destination.
                            cc.socksAddr.replace("0.0.0.0:", "127.0.0.1:"),
                            cc.socksUser.takeIf { cc.isSocksAuthEnabled && it.isNotBlank() },
                            cc.socksPass
                        ),
                        terminalError = false
                    )
                } else {
                    // Target-less and no Xray: mid-retry states are still expected to recover,
                    // but a genuine CoreStatus.Error means nothing is coming - drop right away
                    // instead of waiting out the grace period below for nothing.
                    VpnTargetSignal(null, terminalError = coreStatus is CoreStatus.Error)
                }
            }

            combine(targetFlow, prefs.vpnSettingsFlow, VpnServiceState.state) { signal, vpnSettings, vpnState ->
                VpnSupervisorBundle(signal, vpnSettings, vpnState)
            }.collect { bundle ->
                withContext(Dispatchers.Main) {
                    val target = bundle.signal.target
                    val settingsChanged = lastVpnSettings != null && lastVpnSettings != bundle.vpnSettings
                    val targetChanged = lastTarget != null && lastTarget != target
                    lastVpnSettings = bundle.vpnSettings
                    lastTarget = target

                    if (target != null) {
                        pendingStopJob?.cancel()
                        pendingStopJob = null
                    }

                    if (!bundle.vpnSettings.enabled) {
                        // Explicit user opt-out - stop immediately, no grace period.
                        if (bundle.vpnState != VpnState.Idle) startService(stopIntent())
                        return@withContext
                    }

                    if (target == null) {
                        // Enabled, but nothing to point the relay at right now.
                        if (bundle.vpnState != VpnState.Idle) {
                            if (bundle.signal.terminalError) {
                                // The core has definitively given up - nothing to wait for.
                                startService(stopIntent())
                            } else if (pendingStopJob == null) {
                                // Could be a transient gap - give it a moment before tearing down.
                                pendingStopJob = serviceScope.launch {
                                    delay(VPN_TARGET_LOST_GRACE_MS.milliseconds)
                                    withContext(Dispatchers.Main) { startService(stopIntent()) }
                                }
                            }
                        }
                        return@withContext
                    }

                    val vpnRunning = bundle.vpnState == VpnState.Running
                    val vpnError = bundle.vpnState is VpnState.Error

                    if (!vpnError) {
                        pendingVpnRetryJob?.cancel()
                        pendingVpnRetryJob = null
                    }

                    when {
                        bundle.vpnState == VpnState.Idle -> {
                            // Establish as soon as any target exists, before it's even connected -
                            // a kill switch should be up before it's needed.
                            if (VpnServiceState.state.value != VpnState.Starting) {
                                startService(startIntent(target))
                            }
                        }
                        vpnError && (settingsChanged || targetChanged) -> {
                            AppLogsState.addLog(getString(R.string.log_vpn_restarting_config))
                            startService(stopIntent())
                            startService(startIntent(target))
                        }
                        vpnError && pendingVpnRetryJob == null -> {
                            // Nothing about the target/settings changed, so nothing else here
                            // will ever retry this on its own - back off and try again anyway.
                            pendingVpnRetryJob = serviceScope.launch {
                                delay(VPN_ERROR_RETRY_MS.milliseconds)
                                pendingVpnRetryJob = null
                                withContext(Dispatchers.Main) {
                                    if (VpnServiceState.state.value is VpnState.Error) {
                                        startService(stopIntent())
                                        startService(startIntent(target))
                                    }
                                }
                            }
                        }
                        vpnRunning && settingsChanged -> {
                            // Routing config itself changed (filtering/bypass/app list) - that's
                            // baked into the Builder at establish() time, so it must be redone.
                            AppLogsState.addLog(getString(R.string.log_vpn_restarting_config))
                            startService(stopIntent())
                            startService(startIntent(target))
                        }
                        vpnRunning && targetChanged -> {
                            // Only the upstream SOCKS5 hop moved (e.g. switched profiles) - hot-
                            // swap the relay's target without a fresh establish(), so the tun
                            // interface (and the device's default network) never blips.
                            startService(retargetIntent(target))
                        }
                    }
                }
            }
        }
    }

    private data class XrayConfigSignals(
        val xrayConfig: com.wireturn.app.data.XrayConfig,
        val vless: com.wireturn.app.data.VlessConfig,
        val wg: com.wireturn.app.data.WgConfig,
        val settings: com.wireturn.app.data.XraySettings
    )

    private data class XraySupervisorBundle(
        val shouldBeRunning: Boolean,
        val xrayState: XrayState,
        val signals: XrayConfigSignals,
        val kernelVariant: KernelVariant,
        val connectionTarget: String?,
        val connectionAuth: Triple<Boolean, String, String>?
    )


    private fun observeErrorForNotification() {
        serviceScope.launch {
            combine(
                CoreServiceState.status,
                AppLifecycleState.isAppInForeground
            ) { status, isForeground ->
                status to isForeground
            }.collect { (status, isForeground) ->
                if (status is CoreStatus.Error && !isForeground) {
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
                AppLifecycleState.isAppInForeground
            ) { session, isForeground ->
                session to isForeground
            }.collect { (session, isForeground) ->
                if (session != null && !isForeground) {
                    delay(1_000.milliseconds)
                    if (CoreServiceState.captchaSession.value != null && !AppLifecycleState.isAppInForeground.value) {
                        NotificationHelper.notifyCaptcha(this@CoreService, session.url)
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

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        networkInitialized = false
        lastNetworkHandle = -1
        availablePhysicalNetworks.clear()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                availablePhysicalNetworks.add(network)

                val handle = network.networkHandle
                if (handle == lastNetworkHandle) return
                lastNetworkHandle = handle

                if (!networkInitialized) {
                    networkInitialized = true
                    return
                }
                
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    val prefs = AppPreferences(applicationContext)
                    if (!prefs.restartOnNetworkChangeFlow.first()) return@launch

                    delay(2_000.milliseconds)
                    if (!userStopped.get() && process.get() != null) {
                        AppLogsState.addLog(getString(R.string.log_core_network_change))
                        updateNotification(getString(R.string.notification_network_change))
                        restartCount = 0
                        stopBinaryProcessGracefully()
                    }
                }
            }

            override fun onLost(network: Network) {
                availablePhysicalNetworks.remove(network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (CoreServiceState.status.value is CoreStatus.WaitingForNetwork) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        // Сеть появилась — переходим в Starting, чтобы не сработать повторно, и запускаемся
                        CoreServiceState.setStatus(CoreStatus.Starting)
                        val intent = Intent(this@CoreService, CoreService::class.java)
                        startService(intent)
                    }
                }
            }
        }
        networkCallback = cb
        
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, cb)
    }

    private fun unregisterNetworkCallback() {
        availablePhysicalNetworks.clear()
        networkCallback?.let { cb ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun handleStopAction(disableAutoLaunch: Boolean) {
        if (userStopped.getAndSet(true)) return
        xraySupervisorJob?.cancel()
        xraySupervisorJob = null
        vpnSupervisorJob?.cancel()
        vpnSupervisorJob = null
        NotificationHelper.cancelErrorNotification(this)
        CoreServiceState.setStatus(CoreStatus.Stopping)
        NotificationHelper.updateNotification(this)
        serviceScope.launch {
            // Kill the process before cancelAndJoin(), not after: coreJob's runBinary() blocks on
            // a synchronous readLine() that cancellation can't interrupt, so cancelAndJoin() alone
            // would hang forever - killing the process first closes stdout and unblocks it.
            stopBinaryProcessGracefully()
            coreJob?.cancelAndJoin()

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
                if (VpnServiceState.state.value != VpnState.Idle) {
                    startService(Intent(this@CoreService, HevVpnService::class.java).apply {
                        action = HevVpnService.ACTION_STOP
                    })
                }
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
        if (!isNetworkAvailable()) {
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

    private fun isNetworkAvailable(): Boolean {
        return availablePhysicalNetworks.isNotEmpty()
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
        try {
            val start = System.currentTimeMillis()
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("max.ru", 80), 1500)
            }
            val rtt = System.currentTimeMillis() - start
            // Если ответ шел дольше 800мс — считаем сеть медленной для watchdog
            if (rtt > 800) NetworkQuality.SLOW else NetworkQuality.FAST
        } catch (_: Exception) {
            // Если max.ru недоступен совсем, это не "медленная сеть", а отсутствие интернета
            NetworkQuality.OFFLINE
        }
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
        
        CoreServiceState.setStatus(CoreStatus.Idle)

        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        AppLogsState.addLog(getString(R.string.log_core_stop_ui))

        // Safety net: xraySupervisorJob/vpnSupervisorJob normally tear these down reactively, but
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
        private const val VPN_TARGET_LOST_GRACE_MS = 5_000L
        private const val VPN_ERROR_RETRY_MS = 15_000L

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
