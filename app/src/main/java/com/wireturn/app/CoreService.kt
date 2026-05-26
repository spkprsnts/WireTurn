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
import com.wireturn.app.viewmodel.AppLifecycleState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.random.Random


class CoreService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val process = AtomicReference<Process?>()
    private val userStopped = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)
    private val currentRunningCfg = AtomicReference<ClientConfig?>(null)
    private val availablePhysicalNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    private var lastNetworkHandle: Long = -1
    private var restartCount = 0

    private lateinit var serviceScope: CoroutineScope
    private var coreJob: Job? = null
    private var xraySupervisorJob: Job? = null
    private var networkDebounceJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        NotificationHelper.createChannel(this)
        NotificationHelper.observeStates(this, serviceScope)
        observeCaptchaForNotification()
        observeErrorForNotification()
        startXraySupervisor()
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
        coreJob?.cancel()
        coreJob = serviceScope.launch {
            // Очищаем старый процесс перед запуском нового, чтобы избежать конфликтов портов (особенно при мягком перезапуске)
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
                delay(3000)
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
            CoreServiceState.setStatusText(getString(R.string.connecting))
        } else {
            CoreServiceState.setStatus(CoreStatus.Starting)
        }

        userStopped.set(false)
        restartCount = 0
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
                            updateNotification(getString(R.string.vless_direct_active))
                            CoreServiceState.setRestarting(false)
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
                    val runningCfg = currentRunningCfg.get() ?: return@collect
                    val newCfg = newCfgRaw.fillDefaults()
                    
                    if (!newCfg.isValid) {
                        val errorRes = newCfg.getValidationErrorResId() ?: R.string.error_settings_empty
                        CoreServiceState.setStatus(CoreStatus.Error(getString(errorRes)))
                        
                        // Stop current binary as we are moving to an invalid state
                        stopBinaryProcessGracefully()

                        delay(3000)
                        if (isActive && !userStopped.get()) {
                            withContext(Dispatchers.Main) { stopSelf() }
                        }
                        return@collect
                    }

                    val binaryChanged = requiresBinaryRestart(runningCfg, newCfg)
                    currentRunningCfg.set(newCfg)
                    CoreServiceState.setSession(CoreServiceState.RunningSession(newCfg, prefs.currentProfileNameFlow.first().orEmpty()))
                    if (binaryChanged) {
                        val xrayConfig = prefs.xrayConfigFlow.first()
                        val vlessConfig = prefs.vlessConfigFlow.first()
                        val isDualRoute = xrayConfig.enabled &&
                            xrayConfig.protocol == com.wireturn.app.data.XrayConfiguration.VLESS &&
                            vlessConfig.isDualRoute
                        if (isDualRoute && CoreServiceState.status.value !is CoreStatus.Idle) {
                            AppLogsState.addLog(getString(R.string.log_core_dual_route_config_changed))
                            CoreServiceState.setStatus(CoreStatus.Suppressed)
                        } else {
                            restartCount = 0
                            AppLogsState.addLog(getString(R.string.log_core_config_changed))
                            CoreServiceState.setStatusText(null)
                            CoreServiceState.setRestarting(true)
                            CoreServiceState.setStatus(CoreStatus.Stopping)
                            stopBinaryProcessGracefully()
                        }
                    }
                }
        }

        while (isActive && !userStopped.get()) {
            if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                // Если мы в режиме паузы, просто ждем сигнала к пробуждению
                delay(1000)
                continue
            }

            if (CoreServiceState.status.value is CoreStatus.WaitingForNetwork) {
                // В режиме ожидания сети мы ничего не делаем, пока NetworkCallback не перезапустит нас
                delay(1000)
                continue
            }

            val cfg = currentRunningCfg.get() ?: break
            val startTime = System.currentTimeMillis()
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
                delay(3000)
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
                val errorMsg = getString(R.string.core_failed)
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
            updateNotification(getString(R.string.notification_restart, restartCount, MAX_RESTARTS))
            
            CoreServiceState.setRestarting(true)
            delay(delayMs)
        }
    }

    private suspend fun runBinary(cfg: ClientConfig): Boolean = coroutineScope {
        val cmdArgs = buildCommandArgs(cfg)

        if (CoreServiceState.status.value is CoreStatus.Error) {
            return@coroutineScope false
        }

        val state = BinaryOutputState()

        try {
            AppLogsState.addLog(getString(R.string.log_core_command, cmdArgs.joinToString(" ")))

            val proc = withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(cmdArgs)
                    .directory(filesDir)
                    .redirectErrorStream(true)
                val env = builder.environment()
                val nativeLibDir = applicationInfo.nativeLibraryDir
                
                // Essential for ffmpeg to find its shared libraries (libavcodec, etc.)
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
                
                builder.start()
            }
            process.set(proc)

            if (cfg.kernelVariant == KernelVariant.OLCRTC) {
                state.startupEmitted = true
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Connecting)
                    updateNotification(getString(R.string.connecting))
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
                    delay(1000)
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
                            if (processOutputLine(line, state, cfg.kernelVariant)) break
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
            stopBinaryProcessGracefully()
            process.set(null)
        }
    }

    private suspend fun processOutputLine(line: String, state: BinaryOutputState, kernel: KernelVariant): Boolean {
        val lower = line.lowercase()

        return when (kernel) {
            KernelVariant.TURNABLE -> handleTurnableLog(line, lower, state)
            KernelVariant.OLCRTC -> handleOlcrtcLog(line, lower, state)
            KernelVariant.WEBDAV -> handleWebdavLog(line, lower, state)
        }
    }

    private suspend fun handleTurnableLog(line: String, lower: String, state: BinaryOutputState): Boolean {
        // 1. Hard Errors (Watchdog won't help, needs manual fix)
        if (lower.contains("vk signaling connect rejected: not authorized") ||
            lower.contains("failed to validate connection url") ||
            lower.contains("second shutdown signal received") ||
            lower.contains("panic") || lower.contains("fatal")
        ) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                updateNotification(getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        // 2. Soft Errors (Transient network issues, watchdog will restart)
        val isSignalingLoopTerminated = lower.contains("vk signaling loop terminated")
        val isNormalClose = lower.contains("close 1000 (normal)")

        if (lower.contains("vk authorize anonymous flow failed") ||
            lower.contains("vk calls login failed") ||
            lower.contains("vk join conversation failed") ||
            (isSignalingLoopTerminated && !isNormalClose)
        ) {
            // Break reading and let watchdog restart the process
            state.startupEmitted = true
            return true
        }

        if (lower.contains("failed to start vpn client")) {
            val errorPart = line.substringAfterLast(":").trim()
            val lowerError = errorPart.lowercase()
            if (lowerError.contains("read tcp") || lowerError.contains("timeout") || lowerError.contains("abort")) {
                state.startupEmitted = true
                return true
            }
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_turnable_failed, errorPart)))
            }
            state.startupFailed = true
            return true
        }

        // 2. Connected
        val onlineCount = getOnlineCount(lower)
        if (lower.contains("turnable client started") ||
            lower.contains("relay client session connected") ||
            lower.contains("direct session connected") ||
            (onlineCount != null && onlineCount >= 1 && lower.contains("peer online"))
        ) {
            state.peerConnectFailedCount = 0
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                updateNotification(getString(R.string.core_active))
                state.startupEmitted = true
                CoreServiceState.setRestarting(false)
            }
        }

        // 3. Connecting / Progress / Retries
        if (lower.contains("starting turnable client") ||
            lower.contains("starting full reconnect") ||
            lower.contains("direct: starting full reconnect") ||
            lower.contains("vk captcha challenge received") ||
            lower.contains("vk captcha solved") ||
            lower.contains("all auto captcha attempts exhausted") ||
            lower.contains("manual captcha solve required") ||
            lower.contains("vk signaling websocket dial failed") ||
            lower.contains("turn candidate failed") ||
            lower.contains("dtls direct connect failed") ||
            lower.contains("srtp direct connect failed") ||
            lower.contains("dtls client handshake started") ||
            lower.contains("srtp client handshake started") ||
            lower.contains("peer connect failed") ||
            lower.contains("peer quota reached") ||
            lower.contains("full reconnect failed") ||
            lower.contains("direct: full reconnect failed") ||
            lower.contains("primary handshake failed") ||
            lower.contains("secondary handshake failed") ||
            lower.contains("peer reconnect failed") ||
            lower.contains("scheduling peer retry") ||
            lower.contains("tinymux client received disconnect") ||
            lower.contains("tinymux client cut off unexpectedly") ||
            lower.contains("quota") ||
            (onlineCount != null && onlineCount == 0 && lower.contains("peer offline"))
        ) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                if (isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                }
                CoreServiceState.setStatus(CoreStatus.Connecting)
                updateNotification(getString(R.string.connecting))
                state.startupEmitted = true
            }
        }

        // 4. Captcha
        handleCaptchaEvents(line, lower, state)

        return false
    }

    private suspend fun handleWebdavLog(line: String, lower: String, state: BinaryOutputState): Boolean {
        if (lower.contains("webdav: connecting to")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connecting)
                updateNotification(getString(R.string.connecting))
            }
        }

        if (lower.contains("webdav: connection failed")) {
            if (getNetworkQuality() == NetworkQuality.FAST) {
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_webdav_unavailable)))
                    updateNotification(getString(R.string.error_connecting))
                }
                state.startupFailed = true
            } else {
                state.startupEmitted = true
            }
            return true
        }

        if (lower.contains("server connection lost") || lower.contains("server has not picked up the session")) {
            if (getNetworkQuality() == NetworkQuality.FAST) {
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_webdav_server_unavailable)))
                    updateNotification(getString(R.string.error_connecting))
                }
                state.startupFailed = true
            } else {
                state.startupEmitted = true
            }
            return true
        }

        if (lower.contains("mux ready")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                updateNotification(getString(R.string.core_active))
                state.startupEmitted = true
                CoreServiceState.setRestarting(false)
            }
        }

        if (lower.contains("panic") || lower.contains("fatal") || lower.contains("error starting socks5")) {
            CoreServiceState.setStatus(CoreStatus.Error(line))
            state.startupFailed = true
            return true
        }

        if (lower.contains("connection refused")) {
            val now = System.currentTimeMillis()
            if (now - state.lastWebdavConnRefusedTime > 5_000) {
                state.webdavConnRefusedCount = 1
                state.lastWebdavConnRefusedTime = now
            } else {
                state.webdavConnRefusedCount++
                if (state.webdavConnRefusedCount >= 10) {
                    AppLogsState.addLog(getString(R.string.log_core_webdav_too_many_refused))
                    state.startupEmitted = true // Trigger watchdog
                    return true
                }
            }
        }

        return false
    }

    private suspend fun handleOlcrtcLog(line: String, lower: String, state: BinaryOutputState): Boolean {
        if (lower.contains("socks5 server listening on")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                updateNotification(getString(R.string.core_active))
                state.startupEmitted = true
                CoreServiceState.setRestarting(false)
            }
        }

        if (lower.contains("setupcipher failed")) {
            CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_invalid_auth_key)))
            state.startupFailed = true
            return true
        }

        if (lower.contains("failed to connect link") || lower.contains("failed to create link")) {
            if (getNetworkQuality() == NetworkQuality.FAST) {
                // Быстрая сеть, но ошибка линка — платформа недоступна
                CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_platform_unavailable)))
                state.startupFailed = true
            } else {
                // Либо медленная, либо лежит совсем — на откуп watchdog
                state.startupEmitted = true
            }
            return true
        }

        if (lower.contains("remote not ready") || lower.contains("openstream failed")) {
            val now = System.currentTimeMillis()
            if (now - state.lastRemoteNotReadyTime > 10_000) {
                state.remoteNotReadyCount = 1
                state.lastRemoteNotReadyTime = now
            } else {
                state.remoteNotReadyCount++
                if (state.remoteNotReadyCount >= 7) {
                    AppLogsState.addLog(getString(R.string.log_core_too_many_remote_not_ready))
                    state.startupEmitted = true // Trigger watchdog
                    return true
                }
            }
        }

        if (lower.contains("client reconnect attempt=1")) {// reason=carrier
            AppLogsState.addLog(getString(R.string.log_core_reconnect_restart))
            state.startupEmitted = true
            return true
        }

        if (lower.contains("panic") || lower.contains("fatal") || lower.contains("error starting socks5")) {
            CoreServiceState.setStatus(CoreStatus.Error(line))
            state.startupFailed = true
            return true
        }

        return false
    }

    private fun getOnlineCount(lower: String): Int? {
        val matcher = ONLINE_COUNT_REGEX.matcher(lower)
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
    }

    private fun handleCaptchaEvents(line: String, lower: String, state: BinaryOutputState) {
        if (line.contains("Triggering manual captcha fallback")) {
            state.startupEmitted = true
        }

        val captchaMatcher = CAPTCHA_URL_REGEX.matcher(line)
        if (captchaMatcher.find()) {
            val captchaUrl = captchaMatcher.group(1)!!
            state.captchaSessionCounter += 1
            CoreServiceState.setCaptchaSession(CaptchaSession(captchaUrl, state.captchaSessionCounter))
            state.captchaActive = true
            updateNotification(getString(R.string.core_captcha_required))
            state.startupEmitted = true
        }

        if (state.captchaActive && (
                lower.contains("[vk auth] failed") ||
                lower.contains("[vk auth] success") ||
                (lower.contains("[captcha]") && lower.contains("failed"))
            )) {
            CoreServiceState.setCaptchaSession(null)
            updateNotification(getString(R.string.core_active))
            state.captchaActive = false
        }
    }

    private class BinaryOutputState {
        var startupEmitted = false
        var startupFailed = false
        var captchaActive = false
        var captchaSessionCounter = 0L
        var peerConnectFailedCount = 0
        var connectingSince = 0L
        var remoteNotReadyCount = 0
        var lastRemoteNotReadyTime = 0L
        var webdavConnRefusedCount = 0
        var lastWebdavConnRefusedTime = 0L
    }

    private fun buildCommandArgs(cfg: ClientConfig): List<String> {
        val cmdArgs = mutableListOf<String>()
        when (val k = cfg.kernelConfig) {
            is KernelConfig.Turnable -> {
                cmdArgs.add("${applicationInfo.nativeLibraryDir}/libturnable.so")
                cmdArgs.addAll(listOf(
                    "client",
                    "-l", cfg.listenAddr.ifBlank { ClientConfig.DEFAULT_LISTEN_ADDR },
                    k.config.toUri(true)
                ))
            }
            is KernelConfig.Olcrtc -> {
                cmdArgs.add("${applicationInfo.nativeLibraryDir}/libolcrtc.so")
                val dataDir = java.io.File(filesDir, "data")
                if (!dataDir.exists()) dataDir.mkdirs()
                val configFile = java.io.File(filesDir, "olcrtc.yaml")
                configFile.writeText(buildOlcrtcYaml(cfg))
                cmdArgs.add(configFile.absolutePath)
            }
            is KernelConfig.Webdav -> {
                val o = k.config
                cmdArgs.add("${applicationInfo.nativeLibraryDir}/libwebdav.so")
                cmdArgs.addAll(listOf(
                    "-mode", "client",
                    "-webdav", o.webdav,
                    "-login", o.login,
                    "-password", o.password,
                    "-timeout", o.timeout,
                    "-poll-max", o.pollMax,
                    "-poll-min", o.pollMin,
                    "-coalesce", o.coalesce,
                    "-chunk-size", o.chunkSize,
                    "-puts", o.puts,
                    "-read-min", o.readMin,
                    "-read-max", o.readMax,
                    "-socks-listen", cfg.socksAddr.ifBlank { ClientConfig.DEFAULT_SOCKS_ADDR }
                ))
                if (cfg.isSocksAuthEnabled) {
                    cmdArgs.add("-socks-user")
                    cmdArgs.add(cfg.socksUser)
                    cmdArgs.add("-socks-pass")
                    cmdArgs.add(cfg.socksPass)
                }
            }
        }
        return cmdArgs
    }

    private fun buildOlcrtcYaml(cfg: ClientConfig): String {
        val o = (cfg.kernelConfig as KernelConfig.Olcrtc).config
        return buildString {
            appendLine("mode: cnc")
            appendLine("data: data")
            appendLine("ffmpeg: \"${applicationInfo.nativeLibraryDir}/libffmpeg.so\"")
            appendLine("auth:")
            appendLine("  provider: ${o.provider}")
            appendLine("room:")
            appendLine("  id: \"${o.id}\"")
            appendLine("crypto:")
            appendLine("  key: \"${o.key}\"")
            appendLine("net:")
            appendLine("  transport: ${o.transport}")
            appendLine("  dns: \"${o.dns}\"")
            appendLine("socks:")
            appendLine("  host: \"${cfg.socksAddr.substringBefore(':').ifBlank { "127.0.0.1" }}\"")
            appendLine("  port: ${cfg.socksAddr.substringAfter(':', "9001").ifBlank { "9001" }}")
            if (cfg.isSocksAuthEnabled) {
                appendLine("  user: \"${cfg.socksUser}\"")
                appendLine("  pass: \"${cfg.socksPass}\"")
            }
            when (o.transport) {
                "vp8channel" -> {
                    appendLine("vp8:")
                    appendLine("  fps: ${o.vp8Fps}")
                    appendLine("  batch_size: ${o.vp8Batch}")
                }
                "seichannel" -> {
                    appendLine("sei:")
                    appendLine("  fps: ${o.seiFps}")
                    appendLine("  batch_size: ${o.seiBatch}")
                    appendLine("  fragment_size: ${o.seiFrag}")
                    appendLine("  ack_timeout_ms: ${o.seiAckMs}")
                }
                "videochannel" -> {
                    appendLine("video:")
                    appendLine("  codec: ${o.videoCodec}")
                    appendLine("  width: ${o.videoW}")
                    appendLine("  height: ${o.videoH}")
                    appendLine("  fps: ${o.videoFps}")
                    appendLine("  bitrate: \"${o.videoBitrate}\"")
                    appendLine("  hw: ${o.videoHw}")
                    if (o.videoCodec == "qrcode") {
                        appendLine("  qr_recovery: ${o.videoQrRecovery}")
                        appendLine("  qr_size: ${o.videoQrSize}")
                    } else if (o.videoCodec == "tile") {
                        appendLine("  tile_module: ${o.videoTileModule}")
                        appendLine("  tile_rs: ${o.videoTileRs}")
                    }
                }
            }
        }
    }

    private fun requiresBinaryRestart(old: ClientConfig, new: ClientConfig): Boolean {
        if (old.kernelConfig != new.kernelConfig) return true
        if (old.goDnsGo != new.goDnsGo) return true
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
        }
    }

    private fun handleProcessException(e: Exception) {
        AppLogsState.addLog(getString(R.string.error_critical_format, e.message))
    }

    private suspend fun stopBinaryProcessGracefully() {
        val proc = process.getAndSet(null) ?: return
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

                val connectionTarget = when (clientConfig.kernelVariant) {
                    KernelVariant.OLCRTC -> clientConfig.socksAddr
                    KernelVariant.WEBDAV -> clientConfig.socksAddr
                    else -> clientConfig.listenAddr
                }
                val connectionAuth = if (clientConfig.kernelVariant == KernelVariant.OLCRTC || clientConfig.kernelVariant == KernelVariant.WEBDAV) {
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
                        delay(500)
                        startForegroundService(Intent(this@CoreService, XrayService::class.java))
                    }
                } else if (needsStart) {
                    delay(500) // Debounce during profile switches
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
                    delay(1000)
                    if (CoreServiceState.captchaSession.value != null && !AppLifecycleState.isAppInForeground.value) {
                        NotificationHelper.notifyCaptcha(this@CoreService, session.url, session.sessionId.toString())
                    }
                } else {
                    NotificationHelper.cancelCaptchaNotification(this@CoreService)
                }
            }
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

                    delay(2000)
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
        NotificationHelper.cancelErrorNotification(this)
        CoreServiceState.setRestarting(false)
        CoreServiceState.setStatus(CoreStatus.Stopping)
        NotificationHelper.updateNotification(this)
        serviceScope.launch {
            if (disableAutoLaunch) {
                val prefs = AppPreferences(applicationContext)
                val autoLaunch = prefs.autoLaunchSettingsFlow.first()
                if (autoLaunch.enabled) {
                    prefs.updateAutoLaunchSettings(autoLaunch.copy(enabled = false))
                }
            }
            
            // Explicitly stop Xray when tunnel stops
            withContext(Dispatchers.Main) {
                stopService(Intent(this@CoreService, XrayService::class.java))
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

    private enum class NetworkQuality { FAST, SLOW, OFFLINE }

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
        private val CAPTCHA_URL_REGEX = Pattern.compile("""Open this URL in your browser:\s*(https?://\S+)""")
        private val ONLINE_COUNT_REGEX = Pattern.compile("""online=(\d+)""")

        fun start(context: Context, cfg: ClientConfig) {
            cfg.getValidationErrorResId()?.let { errorRes ->
                CoreServiceState.setStatus(CoreStatus.Error(context.getString(errorRes)))
                return
            }
            // Устанавливаем статус Starting сразу, чтобы UI и LocalCoreManager
            // поняли, что запущен новый процесс попытки подключения, даже если до этого была ошибка.
            CoreServiceState.setStatus(CoreStatus.Starting)
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
