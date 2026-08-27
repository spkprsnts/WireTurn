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
import com.wireturn.app.data.OlcrtcConfig
import com.wireturn.app.data.VpnSettings
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
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds


class CoreService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val process = AtomicReference<Process?>()
    private val userStopped = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)
    private val currentRunningCfg = AtomicReference<ClientConfig?>(null)
    // Serializes stopBinaryProcessGracefully() across all its callers (onStartCommand's per-request
    // coroutines, the Suppressed/hot-reload/network-change collectors, handleStopAction, runBinary's
    // own finally). Rapid successive profile switches each spawn a sibling coroutine under
    // serviceScope's SupervisorJob - previousJob?.cancelAndJoin() only awaits its immediate
    // predecessor, so under 3+ switches within the ~2s SIGTERM grace period that chain can be cut
    // short and a new binary could start before the old one's process actually exited. This mutex
    // makes every stop attempt wait for whichever one is already in flight, so no caller can start a
    // new process until the previous one is confirmed dead, no matter how many requests overlap.
    private val stopMutex = Mutex()
    private val availablePhysicalNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    private var lastNetworkHandle: Long = -1
    @Volatile private var caBundlePath: String? = null
    private var restartCount = 0

    private lateinit var serviceScope: CoroutineScope
    private var coreJob: Job? = null
    private var xraySupervisorJob: Job? = null
    private var vpnSupervisorJob: Job? = null
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
            // Дожидаемся полного завершения предыдущего цикла (включая его finally-очистку) —
            // иначе он может конкурентно тронуть process (см. runBinary) и убить/потерять
            // ссылку на только что запущенный новый процесс, из-за чего старый бинарник
            // не освобождает порт и следующий запуск падает с "address already in use".
            previousJob?.cancelAndJoin()
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
                            updateNotification(getString(R.string.direct_route_active))
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
                    // A deliberate stop (e.g. deleting every profile) clears the active config right
                    // around when handleStopAction() runs - without this guard, that transient
                    // "no config" emission races the real stop and gets reported as a validation
                    // error ("client settings not filled") instead of the clean Idle the user asked for.
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
            AppLogsState.addLog(getString(R.string.log_core_command, cmdArgs.joinToString(" ")))

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

                // Record the reference in the same non-suspending stretch as start() - a
                // suspension point between spawning and recording it (e.g. the withContext
                // hop back to the caller) is a window where cancellation (from a concurrent
                // profile switch / restart) can slip in and orphan the freshly-spawned OS
                // process: it'd keep running and holding its port with no reference anywhere
                // for stopBinaryProcessGracefully()'s cleanup to find.
                builder.start().also {
                    startedProc = it
                    process.set(it)
                }
            }

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
            // NonCancellable: this finally routinely runs because the coroutine itself was
            // cancelled (e.g. previousJob.cancelAndJoin() on a profile switch/restart). Without
            // it, stopBinaryProcessGracefully()'s withContext(Dispatchers.IO) hop would throw
            // CancellationException immediately on entry - skipping the SIGTERM/waitFor kill
            // and leaving the old binary alive still holding its port, with the wrong reference
            // for it silently dropped by the compareAndSet below.
            withContext(NonCancellable) {
                stopBinaryProcessGracefully()
                // compareAndSet, а не set: если это исполнение уже устарело (отменено извне,
                // пока доигрывал finally) и process успел стать ссылкой на процесс НОВОГО
                // запуска, безусловный set(null) стёр бы её и оставил новый процесс "потерянным".
                process.compareAndSet(startedProc, null)
            }
        }
    }

    private suspend fun processOutputLine(line: String, state: BinaryOutputState, cfg: ClientConfig): Boolean {
        val lower = line.lowercase()

        return when (cfg.kernelVariant) {
            KernelVariant.TURNABLE -> handleTurnableLog(line, lower, state)
            KernelVariant.OLCRTC -> handleOlcrtcLog(line, lower, state, (cfg.kernelConfig as? KernelConfig.Olcrtc)?.config ?: OlcrtcConfig())
            KernelVariant.WEBDAV -> handleWebdavLog(line, lower, state)
            KernelVariant.FREETURN -> handleFreeTurnLog(line, lower, state)
        }
    }

    private fun handleFreeTurnLog(line: String, lower: String, state: BinaryOutputState): Boolean {
        // 1. Hard Errors
        if (lower.startsWith("panic:") || lower.startsWith("fatal error:") || 
            lower.contains("all vk credentials failed") || lower.contains("fatal_captcha")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                updateNotification(getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        // 2. Connected
        // TCP_ACTIVE_REGEX stays a regex (unlike every other check here, incl. the udp-mode one
        // right before it) because it needs the active-session count as a number: a session
        // dropping while others in the pool are still active should stay Connected, only an
        // empty pool shouldn't - a plain .contains() can't express that comparison.
        val tcpActiveMatch = TCP_ACTIVE_REGEX.matcher(line)

        // udp mode's old signal, "Established DTLS connection", dropped to Debugf in
        // free-turn-proxy v3.2.0 and we don't pass -debug; "TURN allocation up" stayed Infof
        // and fires right after ConnectedStreams.Add(1), so it's the live replacement.
        if (lower.contains("] turn allocation up") ||
            (tcpActiveMatch.find() && (tcpActiveMatch.group(1)?.toIntOrNull() ?: 0) > 0)) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                updateNotification(getString(R.string.core_active))
                state.startupEmitted = true
                CoreServiceState.setRestarting(false)
            }
        }

        // 3. Connecting / Progress — the gap between process launch and the first
        // established stream (VK auth, TURN allocation, provider backoff) can easily
        // exceed CoreManager's startup timeout with no status update otherwise, since
        // the binary never leaves CoreStatus.Starting on its own.
        if (lower.contains("provider=") ||
            lower.contains("[vk auth] connecting identity") ||
            lower.contains("[vk auth] trying credentials") ||
            lower.contains("backing off for") ||
            (lower.contains("[session ") && lower.contains("disconnected") && lower.contains("reconnecting"))
        ) {
            val currentStatus = CoreServiceState.status.value
            if (currentStatus !is CoreStatus.Suppressed && currentStatus !is CoreStatus.CaptchaRequired) {
                CoreServiceState.setStatus(CoreStatus.Connecting)
                updateNotification(getString(R.string.connecting))
                state.startupEmitted = true
            }
        }

        // 4. Captcha
        handleFreeTurnCaptchaEvents(line, lower, state)

        if (state.captchaActive && (
                lower.contains("[vk auth] failed") ||
                lower.contains("[vk auth] success") ||
                lower.contains("turn allocation up") || // FreeTurn: "success" is Debugf-only since v3.2.0, this stays Infof
                (lower.contains("[captcha]") && lower.contains("failed"))
            )) {
            CoreServiceState.setCaptchaSession(null)
            updateNotification(getString(R.string.core_active))
            state.captchaActive = false
        }

        // 5. Soft Errors / Progress
        if (lower.contains("quota")) {
            // Log it but keep running or let watchdog handles it if it exits
            state.startupEmitted = true
        }

        return false
    }

    private suspend fun handleTurnableLog(line: String, lower: String, state: BinaryOutputState): Boolean {
        // 1. Hard Errors (Watchdog won't help, needs manual fix)
        if (lower.contains("call not found") || lower.contains("join link is not valid")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_room_not_found)))
                updateNotification(getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

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
//        if (lower.contains("delay=30s")) {
//            state.startupEmitted = true
//            return true
//        }

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

        // Turnable's own PoW-captcha retry loop has no attempt cap and keeps hammering
        // the same broken request forever (~every 0.5-5s) instead of giving up — without
        // this, the only thing that ever stops it is the generic 120s connecting-timeout
        // watchdog, silently restarting the whole process for up to 10 attempts. Surface a
        // clear captcha error after a few failures instead of hanging that long.
        // Gated on network being up so a dropped connection isn't misreported as a captcha
        // failure — these attempts only happen after the challenge page was fetched
        // successfully, but skip counting them if the network has since gone away.
        if (lower.contains("vk captcha solve failed") && isNetworkAvailable()) {
            if (state.vkCaptchaSolveFailCounter.recordAndCheckThreshold()) {
                // "captcha pow arguments not found" means the captcha page itself is structurally
                // broken (e.g. VK changed markup) - restarting Turnable won't fix that, so stop
                // for good with a clear error. Any other captcha error (e.g. a transient "captcha
                // init json not found" from a bad page fetch) is more likely to clear up on its
                // own, so just kill this run and let the normal watchdog restart it fresh.
                if (lower.contains("captcha pow arguments not found")) {
                    if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                        CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_turnable_vk_captcha_failed)))
                        updateNotification(getString(R.string.error_connecting))
                    }
                    state.startupFailed = true
                } else {
                    // Not a hard failure - just kill this run and let the normal watchdog restart it.
                    // Without marking startupEmitted, the no-output fallback below would misreport this
                    // as CoreStatus.Error and the outer watchdog would stop the whole service instead.
                    state.startupEmitted = true
                }
                return true
            }
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
            val currentStatus = CoreServiceState.status.value
            if (currentStatus !is CoreStatus.Suppressed && currentStatus !is CoreStatus.CaptchaRequired) {
                if (isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                }
                CoreServiceState.setStatus(CoreStatus.Connecting)
                updateNotification(getString(R.string.connecting))
                state.startupEmitted = true
            }
        }

        return false
    }

    private suspend fun handleWebdavLog(line: String, lower: String, state: BinaryOutputState): Boolean {
        // A single backend's ping failing doesn't fail startup by itself - pingBackends()
        // (external/webdav-tunnel main.go) only gives up once every configured backend is
        // unreachable, logging each failure individually along the way as
        // "WebDAV backend <label>: connection failed: <err>". Swallow those per-backend
        // lines here and only react to the final "all N WebDAV backend(s) unreachable" fatal.
        if (lower.contains("webdav backend") && lower.contains("connection failed")) {
            return true
        }

        // Marks the process as having genuinely started, same as Olcrtc's "socks5 server
        // listening on" branch below. Without this, WebDAV only gets marked started at
        // "server connected" - if the binary exits before that (e.g. crashing on a later,
        // otherwise-harmless per-backend health-check failure like the one swallowed above)
        // the exit falls through to runBinary()'s "process produced no output" branch, which
        // sets CoreStatus.Error and makes the watchdog stop the whole service instead of
        // retrying with backoff like it does for a proper post-startup crash.
        if (lower.contains("socks5 proxy listening on")) {
            state.startupEmitted = true
        }

        if (lower.contains("webdav backend(s) unreachable")) {
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

        if (lower.contains("server connected")) {
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
            if (state.webdavConnRefusedCounter.recordAndCheckThreshold()) {
                AppLogsState.addLog(getString(R.string.log_core_webdav_too_many_refused))
                state.startupEmitted = true // Trigger watchdog
                return true
            }
        }

        return false
    }

    private suspend fun handleOlcrtcLog(line: String, lower: String, state: BinaryOutputState, olcrtcConfig: OlcrtcConfig): Boolean {
        if (lower.contains("join room failed: status 404") || lower.contains("guests cannot create rooms")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(getString(R.string.error_room_not_found)))
                updateNotification(getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

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

        if (olcrtcConfig.restartOnConnectionErrors && (lower.contains("remote not ready") || lower.contains("openstream failed"))) {
            if (state.remoteNotReadyCounter.recordAndCheckThreshold()) {
                AppLogsState.addLog(getString(R.string.log_core_too_many_remote_not_ready))
                state.startupEmitted = true // Trigger watchdog
                return true
            }
        }

        if (lower.contains("client reconnect attempt=2")) {// reason=carrier
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

    private fun handleFreeTurnCaptchaEvents(line: String, lower: String, state: BinaryOutputState) {
        if (line.contains("Triggering manual captcha fallback")) {
            if (CoreServiceState.status.value !is CoreStatus.CaptchaRequired) {
                state.startupEmitted = true
            }
        }

        val captchaMatcher = CAPTCHA_URL_REGEX.matcher(line)
        val freeTurnMatcher = FREE_TURN_CAPTCHA_REGEX.matcher(line)
        val finalMatcher = if (freeTurnMatcher.find()) freeTurnMatcher else if (captchaMatcher.find()) captchaMatcher else null

        if (finalMatcher != null) {
            val captchaUrl = finalMatcher.group(1)!!
            if (CoreServiceState.captchaSession.value?.url == captchaUrl) return

            state.captchaSessionCounter += 1
            val session = CaptchaSession(captchaUrl, state.captchaSessionCounter)
            CoreServiceState.setCaptchaSession(session)
            state.captchaActive = true
            updateNotification(getString(R.string.core_captcha_required))
            
            // Автоматически открываем окно капчи, если приложение активно
            if (AppLifecycleState.isAppInForeground.value) {
                val intent = Intent(this, com.wireturn.app.ui.activities.CaptchaActivity::class.java).apply {
                    putExtra("CAPTCHA_URL", captchaUrl)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
        }

        if (state.captchaActive && (
                lower.contains("[vk auth] failed") ||
                lower.contains("[vk auth] success") ||
                lower.contains("turn allocation up") || // FreeTurn: "success" is Debugf-only since v3.2.0, this stays Infof
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

        // "Give up after N repeats of this log line" counters for per-core failure patterns that
        // keep recurring without ever surfacing a terminal error on their own. One shared mechanism
        // (window + threshold) instead of a bespoke ad hoc counter per pattern, so the next one added
        // doesn't reinvent this a fourth time with yet another slightly different reset rule.
        val remoteNotReadyCounter = LogOccurrenceCounter(windowMs = 10_000, threshold = 7)
        val webdavConnRefusedCounter = LogOccurrenceCounter(windowMs = 5_000, threshold = 10)
        val vkCaptchaSolveFailCounter = LogOccurrenceCounter(windowMs = Long.MAX_VALUE, threshold = 5)
    }

    /**
     * Counts repeated occurrences of a log pattern, resetting back to 1 once more than [windowMs]
     * has passed since the last occurrence. [windowMs] = [Long.MAX_VALUE] effectively disables the
     * reset (every occurrence counts, however far apart). Returns true from [recordAndCheckThreshold]
     * once [threshold] occurrences have piled up inside the window - the caller decides what to do
     * then (the counter itself keeps counting past threshold, same as the original ad hoc versions).
     */
    private class LogOccurrenceCounter(private val windowMs: Long, private val threshold: Int) {
        private var count = 0
        private var lastTime = 0L

        fun recordAndCheckThreshold(): Boolean {
            val now = System.currentTimeMillis()
            count = if (now - lastTime > windowMs) 1 else count + 1
            lastTime = now
            return count >= threshold
        }
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
                val configFile = java.io.File(filesDir, "olcrtc.yaml")
                configFile.writeText(buildOlcrtcYaml(cfg))
                cmdArgs.add(configFile.absolutePath)
            }
            is KernelConfig.Webdav -> {
                cmdArgs.add("${applicationInfo.nativeLibraryDir}/libwebdav.so")
                val configFile = java.io.File(filesDir, "webdav.yaml")
                configFile.writeText(buildWebdavYaml(cfg))
                cmdArgs.addAll(listOf("-config", configFile.absolutePath))
            }
            is KernelConfig.FreeTurn -> {
                val o = k.config
                cmdArgs.add("${applicationInfo.nativeLibraryDir}/libfreeturn.so")
                cmdArgs.addAll(listOf(
                    "-listen", cfg.listenAddr.ifBlank { ClientConfig.DEFAULT_LISTEN_ADDR },
                    "-provider", o.provider,
                    "-peer", o.peer,
                    "-n", o.n.toString(),
                    "-transport", o.transport,
                    "-obf-profile", o.obfProfile,
                    "-streams-per-cred", o.streamsPerCred.toString(),
                    "-dns-mode", o.dnsMode,
                    "-platform", o.platform
                ))
                if (o.obfTiming != "0" && o.obfTiming.isNotBlank()) {
                    cmdArgs.add("-obf-timing")
                    cmdArgs.add(o.obfTiming)
                }
                if (o.links.isNotBlank()) {
                    cmdArgs.add("-links")
                    cmdArgs.add(o.links)
                }
                if (o.sub.isNotBlank()) {
                    cmdArgs.add("-sub")
                    cmdArgs.add(o.sub)
                }
                if (o.obfProfile != "none" && o.obfKey.isNotBlank()) {
                    cmdArgs.add("-obf-key")
                    cmdArgs.add(o.obfKey)
                }
                if (o.dnsServers.isNotBlank()) {
                    cmdArgs.add("-dns-servers")
                    cmdArgs.add(o.dnsServers)
                }
                if (o.clientId.isNotBlank()) {
                    cmdArgs.add("-client-id")
                    cmdArgs.add(o.clientId)
                }
                if (o.manualCaptcha) cmdArgs.add("-manual-captcha")
                if (o.mode == "tcp") {
                    cmdArgs.add("-mode")
                    cmdArgs.add("tcp")
                    val default = com.wireturn.app.data.FreeTurnConfig()
                    if (o.kcpNodelay != default.kcpNodelay) cmdArgs.addAll(listOf("-kcp-nodelay", o.kcpNodelay.toString()))
                    if (o.kcpInterval != default.kcpInterval) cmdArgs.addAll(listOf("-kcp-interval", o.kcpInterval.toString()))
                    if (o.kcpResend != default.kcpResend) cmdArgs.addAll(listOf("-kcp-resend", o.kcpResend.toString()))
                    if (o.kcpNc != default.kcpNc) cmdArgs.addAll(listOf("-kcp-nc", o.kcpNc.toString()))
                    if (o.kcpSndwnd != default.kcpSndwnd) cmdArgs.addAll(listOf("-kcp-sndwnd", o.kcpSndwnd.toString()))
                    if (o.kcpRcvwnd != default.kcpRcvwnd) cmdArgs.addAll(listOf("-kcp-rcvwnd", o.kcpRcvwnd.toString()))
                    if (o.kcpMtu != default.kcpMtu) cmdArgs.addAll(listOf("-kcp-mtu", o.kcpMtu.toString()))
                    if (o.kcpAcknodelay != default.kcpAcknodelay) cmdArgs.addAll(listOf("-kcp-acknodelay", o.kcpAcknodelay.toString()))
                }
            }
        }
        return cmdArgs
    }

    private fun buildOlcrtcYaml(cfg: ClientConfig): String {
        val o = (cfg.kernelConfig as KernelConfig.Olcrtc).config
        return buildString {
            appendLine("mode: cnc")
            appendLine("auth:")
            appendLine("  provider: ${o.provider}")
            appendLine("room:")
            appendLine("  id: \"${o.id}\"")
            appendLine("crypto:")
            appendLine("  key: \"${o.key}\"")
            appendLine("net:")
            appendLine("  transport: ${o.transport}")
            // Unlike WebDAV's -dns, this one isn't optional: olcRTC's own config validation
            // (internal/app/session/validate.go, validateCommon) hard-fails startup with
            // "dns server required" if net.dns is empty, for every mode - so even though the
            // shared ClientConfig.dns field itself stays blank-friendly (for WebDAV, where it
            // really is optional), fall back to the same default the field's placeholder shows
            // right here rather than ever handing olcRTC an empty value.
            appendLine("  dns: \"${cfg.dns.ifBlank { ClientConfig.DEFAULT_DNS }}\"")
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

    private fun buildWebdavYaml(cfg: ClientConfig): String {
        val o = (cfg.kernelConfig as KernelConfig.Webdav).config
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            appendLine("mode: client")
            appendLine("socks-listen: \"${esc(cfg.socksAddr.ifBlank { ClientConfig.DEFAULT_SOCKS_ADDR })}\"")
            if (cfg.isSocksAuthEnabled) {
                appendLine("socks-user: \"${esc(cfg.socksUser)}\"")
                appendLine("socks-pass: \"${esc(cfg.socksPass)}\"")
            }
            if (o.encrypt) appendLine("enc: true")
            appendLine("timeout: \"${esc(o.timeout)}\"")
            if (cfg.dns.isNotBlank()) appendLine("dns: \"${esc(cfg.dns)}\"")

            appendLine("backends:")
            appendLine("  - url: \"${esc(o.webdav)}\"")
            appendLine("    login: \"${esc(o.login)}\"")
            appendLine("    password: \"${esc(o.password)}\"")
            for (backend in o.backends) {
                // BackendConfig (external/webdav-tunnel config.go) only has url/login/password -
                // the label is purely a local display name, not sent to the tunnel binary.
                appendLine("  - url: \"${esc(backend.url)}\"")
                appendLine("    login: \"${esc(backend.login)}\"")
                appendLine("    password: \"${esc(backend.password)}\"")
            }

            appendLine("tuning:")
            appendLine("  poll-min: \"${esc(o.pollMin)}\"")
            appendLine("  poll-max: \"${esc(o.pollMax)}\"")
            appendLine("  coalesce: \"${esc(o.coalesce)}\"")
            appendLine("  chunk-size: ${o.chunkSize.toIntOrNull() ?: 131071}")
            appendLine("  puts: ${o.puts.toIntOrNull() ?: 8}")
            appendLine("  read-min: ${o.readMin.toIntOrNull() ?: 3}")
            appendLine("  read-max: ${o.readMax.toIntOrNull() ?: 8}")
        }
    }

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

                val connectionTarget = if (clientConfig.kernelVariant.isSocks5Native) clientConfig.socksAddr else clientConfig.listenAddr
                val connectionAuth = if (clientConfig.kernelVariant.isSocks5Native) {
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

    /**
     * VPN mode used to be reachable only through Xray. OLCRTC/WEBDAV run their own local SOCKS5
     * listener (ClientConfig.socksAddr) that hev-socks5-tunnel can point at just as well, so VPN
     * mode should work with those cores directly when Xray isn't in the picture. Xray keeps
     * priority when it IS running - it may itself be wrapping an OLCRTC/WEBDAV core as a front
     * proxy (see startXraySupervisor() above), so its socks address is always the hop actually
     * closest to the outside world.
     */
    private data class VpnTarget(val addr: String, val user: String?, val pass: String?)

    // terminalError marks a target-less state the core isn't coming back from on its own (its
    // own watchdog gave up, or the config was invalid outright) - as opposed to a transient gap
    // (mid-retry, switching profiles) where target is momentarily null too but something is
    // still expected to make it valid again shortly.
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
            // Debounces tearing the VPN down when the target transiently disappears (e.g. the
            // brief gap between the old core session ending and a newly-selected profile's
            // starting) - a kill switch shouldn't drop to the raw network for that, only if no
            // target reappears within the grace window below.
            var pendingStopJob: Job? = null

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
                    coreSession.clientConfig.kernelVariant.isSocks5Native &&
                    coreStatus !is CoreStatus.Idle && coreStatus !is CoreStatus.Error && coreStatus !is CoreStatus.WaitingForNetwork
                ) {
                    val cc = coreSession.clientConfig
                    VpnTargetSignal(
                        VpnTarget(
                            // socksAddr can be bound to 0.0.0.0 (e.g. to also serve LAN clients) -
                            // hev connects to this as a literal destination, so it needs the
                            // loopback form, same normalization activeLocalSocksProxy() applies
                            // for HTTP requests.
                            cc.socksAddr.replace("0.0.0.0:", "127.0.0.1:"),
                            cc.socksUser.takeIf { cc.isSocksAuthEnabled && it.isNotBlank() },
                            cc.socksPass
                        ),
                        terminalError = false
                    )
                } else {
                    // Xray not in the picture and the raw core is target-less: WaitingForNetwork
                    // and mid-retry states (Starting/Connecting/Suppressed with no session yet)
                    // are still expected to recover on their own, so keep waiting. Only a
                    // genuine CoreStatus.Error - the core's own watchdog giving up, or an
                    // outright invalid config - means nothing is coming and the tunnel should
                    // drop right away instead of waiting out the grace period below for nothing.
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
                                // The core has definitively given up (watchdog exhausted, or an
                                // outright invalid config) - nothing is coming, so there's
                                // nothing to wait for. Drop right away.
                                startService(stopIntent())
                            } else if (pendingStopJob == null) {
                                // Otherwise this could be a transient gap - mid-retry, switching
                                // profiles - that's still expected to resolve on its own shortly.
                                // Give it a moment before tearing the tunnel down.
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

                    when {
                        bundle.vpnState == VpnState.Idle -> {
                            // Establish as soon as any target exists - don't wait for the hop to
                            // finish connecting. A kill switch should be up *before* it's needed;
                            // packets simply have nowhere to go until the hop catches up, rather
                            // than the alternative of briefly routing over the raw network.
                            if (VpnServiceState.state.value != VpnState.Starting) {
                                startService(startIntent(target))
                            }
                        }
                        vpnError && (settingsChanged || targetChanged) -> {
                            AppLogsState.addLog(getString(R.string.log_vpn_restarting_config))
                            startService(stopIntent())
                            startService(startIntent(target))
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
        private const val VPN_TARGET_LOST_GRACE_MS = 5_000L
        private val CAPTCHA_URL_REGEX = Pattern.compile("""Open this URL in your browser:\s*(https?://\S+)""")
        private val FREE_TURN_CAPTCHA_REGEX = Pattern.compile("""(?:manually open this URL|Open this URL in your browser):\s*(https?://\S+)""")
        private val TCP_ACTIVE_REGEX = Pattern.compile("""\[session \d+] (?:connected|disconnected) \(active: (\d+)\)""")
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
