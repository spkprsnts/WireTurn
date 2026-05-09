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
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.viewmodel.AppLifecycleState
import com.wireturn.app.viewmodel.XrayState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException


class ProxyService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val process = AtomicReference<Process?>()
    private val userStopped = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)
    private val availablePhysicalNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
    
    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    private var lastNetworkHandle: Long = -1
    private var restartCount = 0

    private lateinit var serviceScope: CoroutineScope
    private var proxyJob: Job? = null
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

        // При каждом явном вызове Start — перезапускаем цикл, чтобы подхватить возможные изменения в конфиге
        userStopped.set(false)
        proxyJob?.cancel()
        proxyJob = serviceScope.launch {
            // Очищаем старый процесс перед запуском нового, чтобы избежать конфликтов портов (особенно при мягком перезапуске)
            stopBinaryProcessGracefully()

            val prefs = AppPreferences(applicationContext)
            val cfg = prefs.clientConfigFlow.first()
            val profileName = prefs.currentProfileNameFlow.first()
            val xraySettings = prefs.xraySettingsFlow.first()
            val vlessConfig = prefs.vlessConfigFlow.first()
            val xrayConfig = prefs.xrayConfigFlow.first()

            // Сразу фиксируем работающий конфиг для UI
            ProxyServiceState.setClientConfigSnapshot(cfg)
            ProxyServiceState.setProfileNameSnapshot(profileName)

            if (!cfg.isValid) {
                val errorRes = cfg.getValidationErrorResId() ?: R.string.error_settings_empty
                ProxyServiceState.setStatus(ProxyStatus.Error(getString(errorRes)))
                delay(500)
                withContext(Dispatchers.Main) { stopSelf() }
                return@launch
            }

            try {
                initStartup(vlessConfig, xraySettings, xrayConfig, profileName)
                mainSupervisor(cfg, profileName, xraySettings, vlessConfig, xrayConfig)
            } finally {
                if (!userStopped.get() && isActive) {
                    withContext(Dispatchers.Main) { stopSelf() }
                }
            }
        }

        return START_STICKY
    }

    private suspend fun initStartup(
        vlessConfig: com.wireturn.app.data.VlessConfig,
        @Suppress("UNUSED_PARAMETER") xraySettings: com.wireturn.app.data.XraySettings,
        xrayConfig: com.wireturn.app.data.XrayConfig,
        @Suppress("UNUSED_PARAMETER") profileName: String?
    ) {
        if (AppLogsState.logs.value.isNotEmpty()) {
            AppLogsState.addLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }
        val safeProfileName = profileName?.take(50) ?: "-"
        AppLogsState.addLog(getString(R.string.log_proxy_start, safeProfileName))

        val isXrayVless = xrayConfig.xrayConfiguration == com.wireturn.app.data.XrayConfiguration.VLESS
        val isDualRouteStart = xraySettings.xrayEnabled && isXrayVless && vlessConfig.isDualRoute

        NotificationHelper.cancelErrorNotification(this)
        
        if (isDualRouteStart) {
            // В режиме Dual-route стартуем в паузе, чтобы не запускать бинарник зря
            AppLogsState.addLog(getString(R.string.log_proxy_suppressed))
            ProxyServiceState.setStatus(ProxyStatus.Suppressed)
            ProxyServiceState.setStatusText(getString(R.string.connecting))
        } else {
            ProxyServiceState.setStatus(ProxyStatus.Starting)
        }

        userStopped.set(false)
        restartCount = 0
        
        ProxyTileService.requestUpdate(this)

        withContext(Dispatchers.Main) {
            try {
                val notification = NotificationHelper.buildNotification(this@ProxyService)
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
                AppLogsState.addLog("[Proxy] Failed to start foreground: ${e.message}")
            }
        }

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WireTurn::BgLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))

        registerNetworkCallback()
    }

    private suspend fun mainSupervisor(
        runningCfg: ClientConfig,
        @Suppress("UNUSED_PARAMETER") profileName: String?,
        @Suppress("UNUSED_PARAMETER") initialXraySettings: com.wireturn.app.data.XraySettings,
        @Suppress("UNUSED_PARAMETER") initialVlessConfig: com.wireturn.app.data.VlessConfig,
        @Suppress("UNUSED_PARAMETER") initialXrayConfig: com.wireturn.app.data.XrayConfig
    ) = coroutineScope {
        val prefs = AppPreferences(applicationContext)

        // Реактивное управление состоянием паузы (Suppressed)
        launch {
            combine(
                XrayServiceState.state,
                prefs.xraySettingsFlow,
                prefs.xrayConfigFlow,
                prefs.vlessConfigFlow,
                XrayServiceState.vlessConfigSnapshot
            ) { state, settings, xray, vless, snapshotVless ->
                val isXrayVless = xray.xrayConfiguration == com.wireturn.app.data.XrayConfiguration.VLESS
                
                // Используем снапшот работающего конфига Xray для определения режима Dual-route.
                // Это предотвращает преждевременный запуск бинарника при отключении Dual-route,
                // пока Xray еще не перезагружен с новыми настройками.
                val effectiveVless = if (state != XrayState.Idle) (snapshotVless ?: vless) else vless
                val isDualRoute = settings.xrayEnabled && isXrayVless && effectiveVless.isDualRoute
                
                if (isDualRoute) {
                    when (state) {
                        XrayState.DirectRoute -> {
                            if (ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                                AppLogsState.addLog(getString(R.string.log_proxy_suppressed))
                                ProxyServiceState.setStatus(ProxyStatus.Suppressed)
                            }
                            updateNotification(getString(R.string.vless_direct_active))
                        }
                        XrayState.Running -> {
                            if (ProxyServiceState.status.value is ProxyStatus.Suppressed) {
                                ProxyServiceState.setStatus(ProxyStatus.Connecting)
                            }
                        }
                        // В промежуточных состояниях (Starting, Connecting) держим текущий статус
                        else -> {}
                    }
                } else if (ProxyServiceState.status.value is ProxyStatus.Suppressed) {
                    // Режим Dual-route был выключен — пробуждаем туннель немедленно
                    ProxyServiceState.setStatus(ProxyStatus.Connecting)
                }
            }.collect {}
        }

        launch {
            ProxyServiceState.status.collect { status ->
                if (status is ProxyStatus.Suppressed) {
                    stopBinaryProcessGracefully()
                }
            }
        }

        while (isActive && !userStopped.get()) {
            if (ProxyServiceState.status.value is ProxyStatus.Suppressed) {
                // Если мы в режиме паузы, просто ждем сигнала к пробуждению
                delay(1000)
                continue
            }

            if (ProxyServiceState.status.value is ProxyStatus.WaitingForNetwork) {
                // В режиме ожидания сети мы ничего не делаем, пока NetworkCallback не перезапустит нас
                delay(1000)
                continue
            }

            val startTime = System.currentTimeMillis()
            val startupSuccessful = runBinary(runningCfg)
            val duration = System.currentTimeMillis() - startTime
            
            if (userStopped.get()) break

            // ПРОВЕРКА ПАУЗЫ: Если бинарник был убит супервизором для перехода в DirectRoute,
            // мы НЕ должны запускать логику вотчдога.
            if (ProxyServiceState.status.value is ProxyStatus.Suppressed) {
                restartCount = 0
                continue
            }

            // В ЛЮБОМ СЛУЧАЕ проверяем сеть, если процесс упал не по воле пользователя
            if (isNetworkMissingAndHandled()) {
                continue
            }

            // Check for rapid failure
            val currentStatus = ProxyServiceState.status.value
            if (!startupSuccessful || currentStatus is ProxyStatus.Error) {
                if (currentStatus !is ProxyStatus.Error) {
                    AppLogsState.addLog(getString(R.string.log_quick_exit, duration))
                    AppLogsState.addLog(getString(R.string.log_startup_failed_no_watchdog))
                    ProxyServiceState.setStatus(ProxyStatus.Error(getString(R.string.error_kernel_or_settings)))
                }
                delay(500)
                withContext(Dispatchers.Main) { stopSelf() }
                break
            }

            // Logic for restarts
            restartCount++
            if (restartCount > MAX_RESTARTS) {
                AppLogsState.addLog(getString(R.string.log_watchdog_limit, MAX_RESTARTS))
                ProxyServiceState.emitFailed()
                withContext(Dispatchers.Main) { stopSelf() }
                break
            }

            ProxyServiceState.setStatus(ProxyStatus.Connecting)
            
            var baseDelay = if (duration > 30_000) 1000L else minOf(1000L * restartCount, 30_000L)
            if (isSlowConnection()) {
                AppLogsState.addLog(getString(R.string.log_slow_network_watchdog))
                baseDelay = maxOf(baseDelay, 5000L)
            }
            val delayMs = baseDelay + Random.nextLong(0, 500)
            
            AppLogsState.addLog(getString(R.string.log_watchdog_restart, delayMs, restartCount, MAX_RESTARTS))
            updateNotification(getString(R.string.notification_reconnecting, restartCount, MAX_RESTARTS))
            
            delay(delayMs)
        }
    }

    private suspend fun runBinary(cfg: ClientConfig): Boolean = coroutineScope {
        val cmdArgs = buildCommandArgs(cfg)

        if (ProxyServiceState.status.value is ProxyStatus.Error) {
            return@coroutineScope false
        }

        val state = BinaryOutputState()

        try {
            AppLogsState.addLog(getString(R.string.log_command, cmdArgs.joinToString(" ")))

            val proc = withContext(Dispatchers.IO) {
                ProcessBuilder(cmdArgs).redirectErrorStream(true).start()
            }
            process.set(proc)

            val useCustom = cmdArgs.any { it.contains("custom_core") }
            if (useCustom) {
                ProxyServiceState.setStatus(ProxyStatus.Connected)
                state.startupEmitted = true
            } else if (cfg.kernelVariant == KernelVariant.OLCRTC) {
                ProxyServiceState.setStatus(ProxyStatus.Connecting)
                updateNotification(getString(R.string.connecting))
            }

            withContext(Dispatchers.IO) {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    for (rawLine in reader.lineSequence()) {
                        if (!isActive) break
                        val line = AppLogsState.stripAnsi(rawLine)
                        AppLogsState.addLog(line)
                        if (processOutputLine(line, state)) break
                    }
                }
            }

            val exitCode = withContext(Dispatchers.IO) {
                if (proc.waitFor(5, TimeUnit.SECONDS)) proc.exitValue() else -1
            }
            AppLogsState.addLog(getString(R.string.log_process_stopped, exitCode))

            if (!state.startupEmitted && !state.startupFailed) {
                ProxyServiceState.setStatus(ProxyStatus.Error(getString(R.string.error_process_no_output, exitCode)))
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
            ProxyServiceState.setCaptchaSession(null)
            stopBinaryProcessGracefully()
            process.set(null)
        }
    }

    private suspend fun processOutputLine(line: String, state: BinaryOutputState): Boolean {
        val lower = line.lowercase()

        // 1. Critical Errors & Network Fatalities
        if (handleFatalEvents(line, lower, state)) return true

        // 2. Success / Connectivity Events
        handleConnectivityEvents(lower, state)

        // 3. Status/Progress Triggers
        handleProgressEvents(lower, state)

        // 4. Soft Errors & Quota
        if (handleSoftErrors(lower, state)) return true

        // 5. Captcha
        handleCaptchaEvents(line, lower, state)

        return false
    }

    private suspend fun handleFatalEvents(line: String, lower: String, state: BinaryOutputState): Boolean {
        if (lower.contains("peer reconnected") && isNetworkMissingAndHandled()) {
            state.startupFailed = true
            return true
        }

        val isUnreachable = lower.contains("network is unreachable")
        val isSmuxError = lower.contains("smux open stream error")
        val isPublishDataError = lower.contains("publish data error")

        if (isUnreachable || isSmuxError || isPublishDataError) {
            if (isUnreachable) state.unreachableNetworkCount++
            if (isSmuxError) state.smuxErrorCount++
            if (isPublishDataError) state.publishDataErrorCount++

            if (state.unreachableNetworkCount >= 5 || state.smuxErrorCount >= 20 || state.publishDataErrorCount >= 5) {
                if (isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                } else {
                    state.unreachableNetworkCount = 0
                    state.smuxErrorCount = 0
                    state.publishDataErrorCount = 0
                }
            }
        }

        if (lower.contains("failed to validate connection url")) {
            if (ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                ProxyServiceState.setStatus(ProxyStatus.Error(getString(R.string.error_invalid_turnable_url)))
            }
            state.startupFailed = true
            return true
        }

        if (lower.contains("failed to start vpn client")) {
            val errorPart = line.substringAfterLast(":").trim()
            if (ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                ProxyServiceState.setStatus(ProxyStatus.Error(getString(R.string.error_turnable_failed, errorPart)))
            }
            state.startupFailed = true
            return true
        }

        if (lower.contains("panic") || lower.contains("fatal") || lower.contains("rate limit")) {
            if (ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                ProxyServiceState.setStatus(ProxyStatus.Error(line))
                updateNotification(getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        return false
    }

    private fun handleConnectivityEvents(lower: String, state: BinaryOutputState) {
        if (lower.contains("established") ||
            lower.contains("first data frame received") ||
            lower.contains("datachannel connected") ||
            lower.contains("peer online") ||
            lower.contains("vp8 track receiving") ||
            lower.contains("socks5 server listening on")
        ) {
            state.peerConnectFailedCount = 0
            state.unreachableNetworkCount = 0
            state.smuxErrorCount = 0
            state.publishDataErrorCount = 0
            if (ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                ProxyServiceState.setStatus(ProxyStatus.Connected)
                updateNotification(getString(R.string.proxy_active))
                state.startupEmitted = true
            }
        }
    }

    private fun handleProgressEvents(lower: String, state: BinaryOutputState) {
        if (lower.contains("[vk auth]") || lower.contains("joining room") ||
            lower.contains("starting turnable client") || lower.contains("publisher") ||
            lower.contains("subscriber")
        ) {
            if (!state.startupEmitted && ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                ProxyServiceState.setStatus(ProxyStatus.Connecting)
                updateNotification(getString(R.string.connecting))
                state.startupEmitted = true
            }
        }
    }

    private suspend fun handleSoftErrors(lower: String, state: BinaryOutputState): Boolean {
        if (lower.contains("client link reconnect") || lower.contains("starting full reconnect")) {
            if (ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                if (isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                }
                ProxyServiceState.setStatus(ProxyStatus.Connecting)
                updateNotification(getString(R.string.connecting))
            }
        }

        if (lower.contains("datachannel closed") || lower.contains("remote not ready")) {
            if (ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                if (isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                }

                if (lower.contains("remote not ready")) {
                    val now = System.currentTimeMillis()
                    if (now - state.lastRemoteNotReadyTime > 10_000) {
                        state.remoteNotReadyCount = 1
                        state.lastRemoteNotReadyTime = now
                    } else {
                        state.remoteNotReadyCount++
                        if (state.remoteNotReadyCount >= 10) {
                            AppLogsState.addLog(getString(R.string.log_too_many_remote_not_ready))
                            state.startupEmitted = true // To ensure watchdog triggers
                            return true
                        }
                    }
                } else {
                    ProxyServiceState.setStatus(ProxyStatus.Connecting)
                }
            }
        }

        if (lower.contains("peer connect failed")) {
            state.peerConnectFailedCount++
            if (state.peerConnectFailedCount >= 20) {
                AppLogsState.addLog(getString(R.string.log_too_many_peer_failures))
                state.startupEmitted = true
                return true
            }
            if (ProxyServiceState.status.value is ProxyStatus.Connected) {
                ProxyServiceState.setStatus(ProxyStatus.Connecting)
                updateNotification(getString(R.string.connecting))
            }
        }

        if (lower.contains("failed to connect link")) {
            state.startupEmitted = true
            return true
        }

        if (isQuotaError(lower)) {
            if (ProxyServiceState.status.value is ProxyStatus.Connected) {
                ProxyServiceState.setStatus(ProxyStatus.Connecting)
                updateNotification(getString(R.string.connecting))
            }
        }

        return false
    }

    private fun handleCaptchaEvents(line: String, lower: String, state: BinaryOutputState) {
        if (line.contains("Triggering manual captcha fallback")) {
            state.startupEmitted = true
        }

        val captchaMatcher = CAPTCHA_URL_REGEX.matcher(line)
        if (captchaMatcher.find()) {
            val captchaUrl = captchaMatcher.group(1)!!
            state.captchaSessionCounter += 1
            ProxyServiceState.setCaptchaSession(CaptchaSession(captchaUrl, state.captchaSessionCounter))
            state.captchaActive = true
            updateNotification(getString(R.string.proxy_captcha_required))
            state.startupEmitted = true
        }

        if (state.captchaActive && (
                lower.contains("[vk auth] failed") ||
                lower.contains("[vk auth] success") ||
                (lower.contains("[captcha]") && lower.contains("failed"))
            )) {
            ProxyServiceState.setCaptchaSession(null)
            updateNotification(getString(R.string.proxy_active))
            state.captchaActive = false
        }
    }

    private class BinaryOutputState {
        var startupEmitted = false
        var startupFailed = false
        var captchaActive = false
        var captchaSessionCounter = 0L
        var peerConnectFailedCount = 0
        var unreachableNetworkCount = 0
        var smuxErrorCount = 0
        var publishDataErrorCount = 0
        var remoteNotReadyCount = 0
        var lastRemoteNotReadyTime = 0L
    }

    private fun buildCommandArgs(cfg: ClientConfig): List<String> {
        val customBin = File(filesDir, "custom_core")
        val useCustom = customBin.exists()
        val executable = if (useCustom) {
            AppLogsState.addLog(getString(R.string.log_custom_kernel))
            customBin.absolutePath
        } else {
            AppLogsState.addLog(getString(R.string.log_standard_kernel))
            when (cfg.kernelVariant) {
                KernelVariant.TURNABLE -> "${applicationInfo.nativeLibraryDir}/libturnable.so"
                KernelVariant.OLCRTC -> "${applicationInfo.nativeLibraryDir}/libolcrtc.so"
            }
        }

        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(executable)

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex())
            if (parts.isNotEmpty()) cmdArgs.addAll(parts)
        } else {
            when (cfg.kernelVariant) {
                KernelVariant.TURNABLE -> {
                    cmdArgs.addAll(
                        listOf(
                            "client",
                            "-l", cfg.localPort.ifBlank { ClientConfig.DEFAULT_LOCAL_PORT },
                            cfg.turnableConfig.toUrl(true)
                        )
                    )
                }
                KernelVariant.OLCRTC -> {
                    val o = cfg.olcrtcConfig

                    cmdArgs.addAll(
                        listOf(
                            "-mode", "cnc",
                            "-carrier", o.carrier,
                            "-transport", o.transport,
                            "-id", o.id,
                            "-client-id", o.clientId,
                            "-key", o.key,
                            "-link", "direct",
                            "-data", "data",
                            "-dns", o.dns,
                            "-socks-host", o.socksHost.ifBlank { ClientConfig.DEFAULT_SOCKS_HOST },
                            "-socks-port", o.socksPort.ifBlank { ClientConfig.DEFAULT_SOCKS_PORT }
                        )
                    )

                    // Transport specific flags
                    when (o.transport) {
                        "vp8channel" -> {
                            cmdArgs.add("-vp8-fps")
                            cmdArgs.add(o.vp8Fps.toString())
                            cmdArgs.add("-vp8-batch")
                            cmdArgs.add(o.vp8Batch.toString())
                        }
                        "seichannel" -> {
                            cmdArgs.add("-fps")
                            cmdArgs.add(o.seiFps.toString())
                            cmdArgs.add("-batch")
                            cmdArgs.add(o.seiBatch.toString())
                            cmdArgs.add("-frag")
                            cmdArgs.add(o.seiFrag.toString())
                            cmdArgs.add("-ack-ms")
                            cmdArgs.add(o.seiAckMs.toString())
                        }
                        "videochannel" -> {
                            cmdArgs.add("-video-codec")
                            cmdArgs.add(o.videoCodec)
                            cmdArgs.add("-video-w")
                            cmdArgs.add(o.videoW.toString())
                            cmdArgs.add("-video-h")
                            cmdArgs.add(o.videoH.toString())
                            cmdArgs.add("-video-fps")
                            cmdArgs.add(o.videoFps.toString())
                            cmdArgs.add("-video-bitrate")
                            cmdArgs.add(o.videoBitrate)
                            cmdArgs.add("-video-hw")
                            cmdArgs.add(o.videoHw)
                            if (o.videoCodec == "qrcode") {
                                cmdArgs.add("-video-qr-recovery")
                                cmdArgs.add(o.videoQrRecovery)
                                cmdArgs.add("-video-qr-size")
                                cmdArgs.add(o.videoQrSize.toString())
                            } else if (o.videoCodec == "tile") {
                                cmdArgs.add("-video-tile-module")
                                cmdArgs.add(o.videoTileModule.toString())
                                cmdArgs.add("-video-tile-rs")
                                cmdArgs.add(o.videoTileRs.toString())
                            }
                        }
                    }
                }
            }
        }

        if (useCustom) {
            val linker = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true) "/system/bin/linker64" else "/system/bin/linker"
            cmdArgs.add(0, linker)
        }
        
        return cmdArgs
    }

    private fun handleProcessException(e: Exception) {
        val msg = e.message ?: ""
        if (msg.contains("error=13") || msg.contains("Permission denied")) {
            AppLogsState.addLog(getString(R.string.error_kernel_permission_denied))
            ProxyServiceState.setStatus(ProxyStatus.Error(msg))
        } else {
            AppLogsState.addLog(getString(R.string.error_critical_format, e.message))
        }
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
            combine(
                prefs.xraySettingsFlow,
                ProxyServiceState.status,
                XrayServiceState.state,
                ProxyServiceState.clientConfigSnapshot
            ) { settings, status, xrayState, clientConfig ->
                if (clientConfig == null) return@combine null

                val shouldBeRunning = settings.xrayEnabled && 
                        status !is ProxyStatus.Idle && 
                        status !is ProxyStatus.Error && 
                        status !is ProxyStatus.WaitingForNetwork
                
                val connectionTarget = when (clientConfig.kernelVariant) {
                    KernelVariant.OLCRTC -> "${clientConfig.olcrtcConfig.socksHost}:${clientConfig.olcrtcConfig.socksPort}"
                    else -> clientConfig.localPort
                }

                XraySupervisorBundle(
                    shouldBeRunning = shouldBeRunning,
                    xrayState = xrayState,
                    kernelVariant = clientConfig.kernelVariant,
                    connectionTarget = connectionTarget
                )
            }.filterNotNull()
            .distinctUntilChanged { old: XraySupervisorBundle, new: XraySupervisorBundle ->
                old.shouldBeRunning == new.shouldBeRunning &&
                old.kernelVariant == new.kernelVariant &&
                old.connectionTarget == new.connectionTarget &&
                (if (new.shouldBeRunning) new.xrayState != XrayState.Idle else new.xrayState == XrayState.Idle)
            }
            .collectLatest { data: XraySupervisorBundle ->
                val needsStart = data.shouldBeRunning && data.xrayState == XrayState.Idle
                val needsStop = !data.shouldBeRunning && data.xrayState != XrayState.Idle
                val needsRestart = data.shouldBeRunning && data.xrayState != XrayState.Idle

                if (needsRestart) {
                    AppLogsState.addLog("[Xray] Restarting due to kernel or proxy settings change")
                    withContext(Dispatchers.Main) {
                        stopService(Intent(this@ProxyService, XrayService::class.java))
                        delay(500)
                        startForegroundService(Intent(this@ProxyService, XrayService::class.java))
                    }
                } else if (needsStart) {
                    delay(500) // Debounce during profile switches
                    withContext(Dispatchers.Main) {
                        val currentXrayState = XrayServiceState.state.value
                        val currentStatus = ProxyServiceState.status.value
                        if (currentXrayState == XrayState.Idle && currentStatus !is ProxyStatus.Idle && currentStatus !is ProxyStatus.Error) {
                            startForegroundService(Intent(this@ProxyService, XrayService::class.java))
                        }
                    }
                } else if (needsStop) {
                    withContext(Dispatchers.Main) {
                        stopService(Intent(this@ProxyService, XrayService::class.java))
                    }
                }
            }
        }
    }

    private data class XraySupervisorBundle(
        val shouldBeRunning: Boolean,
        val xrayState: XrayState,
        val kernelVariant: KernelVariant,
        val connectionTarget: String?
    )

    private fun observeErrorForNotification() {
        serviceScope.launch {
            combine(
                ProxyServiceState.status,
                AppLifecycleState.isAppInForeground
            ) { status, isForeground ->
                status to isForeground
            }.collect { (status, isForeground) ->
                if (status is ProxyStatus.Error && !isForeground) {
                    NotificationHelper.notifyError(this@ProxyService, status.message)
                } else if ((status is ProxyStatus.Connected || status is ProxyStatus.Suppressed || status is ProxyStatus.WaitingForNetwork) || isForeground) {
                    NotificationHelper.cancelErrorNotification(this@ProxyService)
                }
            }
        }
    }

    private fun observeCaptchaForNotification() {
        serviceScope.launch {
            combine(
                ProxyServiceState.captchaSession,
                AppLifecycleState.isAppInForeground
            ) { session, isForeground ->
                session to isForeground
            }.collect { (session, isForeground) ->
                if (session != null && !isForeground) {
                    delay(1000)
                    if (ProxyServiceState.captchaSession.value != null && !AppLifecycleState.isAppInForeground.value) {
                        NotificationHelper.notifyCaptcha(this@ProxyService, session.url, session.sessionId.toString())
                    }
                } else {
                    NotificationHelper.cancelCaptchaNotification(this@ProxyService)
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

                val handle = network.getNetworkHandle()
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
                        AppLogsState.addLog(getString(R.string.log_network_change))
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
                if (ProxyServiceState.status.value is ProxyStatus.WaitingForNetwork) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        // Сеть появилась — переходим в Starting, чтобы не сработать повторно, и запускаемся
                        ProxyServiceState.setStatus(ProxyStatus.Starting)
                        val intent = Intent(this@ProxyService, ProxyService::class.java)
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
        ProxyServiceState.setStatus(ProxyStatus.Idle)
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
                stopService(Intent(this@ProxyService, XrayService::class.java))
            }

            stopBinaryProcessGracefully()
            withContext(Dispatchers.Main) {
                NotificationHelper.updateNotification(this@ProxyService)
                stopSelf()
            }
        }
    }

    private suspend fun isNetworkMissingAndHandled(): Boolean {
        if (!isNetworkAvailable()) {
            val prefs = AppPreferences(applicationContext)
            if (prefs.waitForNetworkFlow.first()) {
                if (ProxyServiceState.status.value !is ProxyStatus.WaitingForNetwork) {
                    AppLogsState.addLog(getString(R.string.log_no_network_waiting))
                }
                if (ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                    ProxyServiceState.setStatus(ProxyStatus.WaitingForNetwork)
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

    private fun isSlowConnection(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        
        // If it's cellular, we check the estimated bandwidth.
        // Usually EDGE is < 500 kbps.
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val speed = caps.linkDownstreamBandwidthKbps
            return speed in 1..999 // Threshold: 1 Mbps
        }
        return false
    }

    private fun updateNotification(text: String) {
        ProxyServiceState.setStatusText(text)
    }

    private fun isQuotaError(lowerLine: String): Boolean {
        return lowerLine.contains("quota") || lowerLine.contains("allocation quota")
    }

    override fun onDestroy() {
        super.onDestroy()
        isStarted.set(false)
        userStopped.set(true)
        
        ProxyServiceState.setStatus(ProxyStatus.Idle)
        
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        AppLogsState.addLog(getString(R.string.log_stop_ui))

        serviceScope.launch {
            stopBinaryProcessGracefully()
            withContext(Dispatchers.Main) {
                NotificationHelper.updateNotification(this@ProxyService)
            }
            serviceScope.cancel()
        }

        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    companion object {
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_STOP_BY_USER = "ACTION_STOP_BY_USER"
        const val MAX_RESTARTS = 8
        private val CAPTCHA_URL_REGEX = Pattern.compile("""Open this URL in your browser:\s*(https?://\S+)""")

        fun start(context: Context, cfg: ClientConfig) {
            cfg.getValidationErrorResId()?.let { errorRes ->
                ProxyServiceState.setStatus(ProxyStatus.Error(context.getString(errorRes)))
                return
            }
            // Устанавливаем статус Starting сразу, чтобы UI и LocalProxyManager
            // поняли, что запущен новый процесс попытки подключения, даже если до этого была ошибка.
            ProxyServiceState.setStatus(ProxyStatus.Starting)
            context.startForegroundService(Intent(context, ProxyService::class.java))
        }

        fun stop(context: Context, byUser: Boolean = false) {
            val intent = Intent(context, ProxyService::class.java).apply {
                action = if (byUser) ACTION_STOP_BY_USER else ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
