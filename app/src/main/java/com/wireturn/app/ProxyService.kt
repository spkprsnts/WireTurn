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
import com.wireturn.app.data.DCType
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import java.io.InterruptedIOException

sealed class StartupResult {
    data object Success : StartupResult()
    data class Failed(val message: String) : StartupResult()
}

class ProxyService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val process = AtomicReference<Process?>(null)
    private val userStopped = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)
    
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
        observeCaptchaForNotification()
        startXraySupervisor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            handleStopAction()
            return START_NOT_STICKY
        }
        
        // Если уже запущено и основной цикл работает — ничего не делаем.
        if (ProxyServiceState.isRunning.value && proxyJob?.isActive == true) return START_STICKY

        initStartup()
        
        proxyJob?.cancel()
        proxyJob = serviceScope.launch {
            mainSupervisor()
        }

        return START_STICKY
    }

    private fun initStartup() {
        ProxyServiceState.setStartupResult(null)
        ProxyServiceState.setRunning(true)
        userStopped.set(false)
        restartCount = 0

        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this))
        NotificationHelper.updateNotification(this)
        ProxyTileService.requestUpdate(this)

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WireTurn::BgLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))

        registerNetworkCallback()
        AppLogsState.addLog(getString(R.string.log_proxy_start))
    }

    private suspend fun mainSupervisor() = coroutineScope {
        val prefs = AppPreferences(applicationContext)
        
        while (isActive && !userStopped.get()) {
            var cfg = prefs.clientConfigFlow.first()
            
            // Cleanup jazz creds if needed
            if (cfg.jazzCreds.contains("@salutejazz.ru", ignoreCase = true)) {
                cfg = cfg.copy(jazzCreds = cfg.jazzCreds.replace("@salutejazz.ru", "", ignoreCase = true))
                prefs.saveClientConfig(cfg)
            }
            
            ProxyServiceState.setRunningConfig(cfg)
            
            val startTime = System.currentTimeMillis()
            runBinary(cfg)
            val duration = System.currentTimeMillis() - startTime
            
            if (userStopped.get()) break

            // Check for rapid failure (e.g. invalid arguments or kernel issue)
            val currentResult = ProxyServiceState.startupResult.value
            if (currentResult !is StartupResult.Success && duration < 2500) {
                AppLogsState.addLog(getString(R.string.log_quick_exit, duration))
                AppLogsState.addLog(getString(R.string.log_startup_failed_no_watchdog))
                ProxyServiceState.setStartupResult(StartupResult.Failed(getString(R.string.error_kernel_or_settings)))
                ProxyServiceState.setRunning(false)
                withContext(Dispatchers.Main) { stopSelf() }
                break
            }

            // Logic for restarts
            restartCount++
            if (restartCount > MAX_RESTARTS) {
                AppLogsState.addLog(getString(R.string.log_watchdog_limit, MAX_RESTARTS))
                ProxyServiceState.setRunning(false)
                ProxyServiceState.emitFailed()
                withContext(Dispatchers.Main) { stopSelf() }
                break
            }

            ProxyServiceState.setWorking(false)
            ProxyTileService.requestUpdate(this@ProxyService)
            
            // Backoff delay: if ran for more than 30s, reset backoff to 1s
            val baseDelay = if (duration > 30_000) 1000L else minOf(1000L * restartCount, 30_000L)
            val delayMs = baseDelay + Random.nextLong(0, 500)
            
            AppLogsState.addLog(getString(R.string.log_watchdog_restart, delayMs, restartCount, MAX_RESTARTS))
            updateNotification(getString(R.string.notification_reconnecting, restartCount, MAX_RESTARTS))
            
            delay(delayMs)
        }
    }

    private suspend fun runBinary(cfg: ClientConfig) = coroutineScope {
        val cmdArgs = buildCommandArgs(cfg)
        
        var startupEmitted = false
        var startupFailed = false
        var captchaActive = false
        var captchaSessionCounter = 0L
        val sessionKillScheduled = AtomicBoolean(false)

        try {
            AppLogsState.addLog(getString(R.string.log_command, cmdArgs.joinToString(" ")))

            val proc = withContext(Dispatchers.IO) {
                ProcessBuilder(cmdArgs).redirectErrorStream(true).start()
            }
            process.set(proc)

            val useCustom = cmdArgs.any { it.contains("custom_vkturn") }
            if (useCustom) {
                ProxyServiceState.setWorking(true)
                ProxyServiceState.setStartupResult(StartupResult.Success)
                startupEmitted = true
                ProxyTileService.requestUpdate(this@ProxyService)
            }

            withContext(Dispatchers.IO) {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (true) {
                        val l = reader.readLine() ?: break
                        AppLogsState.addLog(l)

                        if (!isActive) continue

                        // Process logic
                        if (!useCustom && !l.contains("[xray]") && !l.contains("[tun2socks]")) {
                            if (l.contains("[VK Auth]", ignoreCase = true) || l.contains("joining room", ignoreCase = true)) {
                                if (!startupEmitted) {
                                    ProxyServiceState.setStartupResult(StartupResult.Success)
                                    updateNotification(getString(R.string.proxy_connecting))
                                    startupEmitted = true
                                }
                            }

                            if (l.contains("Established", ignoreCase = true) || 
                                l.contains("listening on", ignoreCase = true) || 
                                l.contains("DataChannel connected", ignoreCase = true)) {
                                ProxyServiceState.setWorking(true)
                                updateNotification(getString(R.string.proxy_active))
                                if (!startupEmitted) {
                                    ProxyServiceState.setStartupResult(StartupResult.Success)
                                    startupEmitted = true
                                }
                                ProxyTileService.requestUpdate(this@ProxyService)
                            }
                            if (l.contains("DataChannel closed", ignoreCase = true)) {
                                ProxyServiceState.setWorking(false)
                                ProxyTileService.requestUpdate(this@ProxyService)

                                if (cfg.dcType == DCType.SALUTE_JAZZ) {
                                    if (!checkJazzAvailability()) {
                                        AppLogsState.addLog(getString(R.string.log_jazz_unavailable))
                                        break // Trigger restart
                                    }
                                }
                            }
                        }

                        // Captcha logic
                        if (l.contains("Triggering manual captcha fallback")) {
                            captchaActive = true
                            if (!startupEmitted) {
                                ProxyServiceState.setStartupResult(StartupResult.Success)
                                startupEmitted = true
                            }
                        }

                        val captchaMatcher = CAPTCHA_URL_REGEX.matcher(l)
                        if (captchaMatcher.find()) {
                            val captchaUrl = captchaMatcher.group(1)!!
                            captchaSessionCounter += 1
                            ProxyServiceState.setCaptchaSession(CaptchaSession(captchaUrl, captchaSessionCounter))
                            captchaActive = true
                            updateNotification(getString(R.string.proxy_captcha_required))
                            ProxyTileService.requestUpdate(this@ProxyService)
                            
                            if (!startupEmitted) {
                                ProxyServiceState.setStartupResult(StartupResult.Success)
                                startupEmitted = true
                            }
                        }

                        if (captchaActive && (
                                l.contains("[VK Auth] Failed") ||
                                l.contains("[VK Auth] Success") ||
                                (l.contains("[Captcha]") && l.contains("failed"))
                            )) {
                            ProxyServiceState.setCaptchaSession(null)
                            updateNotification(getString(R.string.proxy_active))
                            captchaActive = false
                        }

                        // Error detection
                        if (!startupEmitted) {
                            val lower = l.lowercase()
                            if (lower.contains("panic") || lower.contains("fatal") || lower.contains("rate limit")) {
                                ProxyServiceState.setStartupResult(StartupResult.Failed(l))
                                updateNotification(getString(R.string.error_connecting))
                                startupFailed = true
                            }
                        }

                        // Quota error handling
                        if (isQuotaError(l) && sessionKillScheduled.compareAndSet(false, true)) {
                            AppLogsState.addLog(getString(R.string.log_quota_error))
                            launch {
                                delay(2000)
                                sessionKillScheduled.set(false)
                                if (!userStopped.get()) {
                                    stopBinaryProcessGracefully()
                                }
                            }
                        }
                    }
                }
            }

            val exitCode = withContext(Dispatchers.IO) {
                if (proc.waitFor(5, TimeUnit.SECONDS)) proc.exitValue() else -1
            }
            AppLogsState.addLog(getString(R.string.log_process_stopped, exitCode))
            
            if (!startupEmitted && !startupFailed) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(getString(R.string.error_process_no_output, exitCode)))
            }

        } catch (_: InterruptedIOException) {
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            handleProcessException(e)
        } finally {
            ProxyServiceState.setCaptchaSession(null)
            process.set(null)
            // Decisions on what to do next are made in mainSupervisor based on userStopped
        }
    }

    private suspend fun buildCommandArgs(cfg: ClientConfig): List<String> {
        val customBin = File(filesDir, "custom_vkturn")
        val useCustom = customBin.exists()
        val executable = if (useCustom) {
            AppLogsState.addLog(getString(R.string.log_custom_kernel))
            customBin.absolutePath
        } else {
            AppLogsState.addLog(getString(R.string.log_standard_kernel))
            "${applicationInfo.nativeLibraryDir}/libvkturn.so"
        }

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex())
            cmdArgs.add(executable)
            if (parts.isNotEmpty()) cmdArgs.addAll(parts)
        } else {
            cmdArgs.add(executable)
            cmdArgs.add("-listen"); cmdArgs.add(cfg.localPort.ifBlank { ClientConfig.DEFAULT_LOCAL_PORT })
            if(cfg.dcMode) {
                if (cfg.dcType == DCType.SALUTE_JAZZ) {
                    if (!checkJazzAvailability()) {
                        AppLogsState.addLog(getString(R.string.log_jazz_unavailable))
                        ProxyServiceState.setStartupResult(StartupResult.Failed(getString(R.string.error_jazz_unavailable)))
                    }
                    // If unavailable, we still try to run but it will likely fail or we can abort
                    cmdArgs.add("-jazz-room")
                    cmdArgs.add(cfg.jazzCreds)
                } else {
                    cmdArgs.add("-wb-room")
                    cmdArgs.add(cfg.wbstreamUuid)
                }
                if (cfg.vlessMode) cmdArgs.add("-vless")
                cmdArgs.add("-dc")
            } else {
                cmdArgs.add("-peer"); cmdArgs.add(cfg.serverAddress)
                cmdArgs.add("-vk-link"); cmdArgs.add(cfg.vkLink)
                if (cfg.threads > 0) { cmdArgs.add("-n"); cmdArgs.add(cfg.threads.toString()) }
                if (cfg.vlessMode) cmdArgs.add("-vless")
                else if (cfg.useUdp) cmdArgs.add("-udp")
                if (cfg.forceTurnPort443) { cmdArgs.add("-port"); cmdArgs.add("443") }
                if (cfg.manualCaptcha) cmdArgs.add("--manual-captcha")
                if (cfg.noDtls && useCustom) cmdArgs.add("-no-dtls")
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
            ProxyServiceState.setStartupResult(StartupResult.Failed(msg))
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
        xraySupervisorJob = serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            ProxyServiceState.isRunning.collectLatest { running ->
                if (running) {
                    // Ждем первого появления isWorking в рамках текущего запуска сессии
                    ProxyServiceState.isWorking.first { it }
                    
                    // После того как прокси заработал хотя бы раз, следим за конфигом Xray
                    prefs.xrayConfigFlow.collect { cfg ->
                        withContext(Dispatchers.Main) {
                            val currentState = XrayServiceState.state.value
                            if (cfg.xrayEnabled) {
                                if (currentState == XrayState.Idle) {
                                    startForegroundService(Intent(this@ProxyService, XrayService::class.java))
                                }
                            } else {
                                if (currentState != XrayState.Idle) {
                                    stopService(Intent(this@ProxyService, XrayService::class.java))
                                }
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        stopService(Intent(this@ProxyService, XrayService::class.java))
                    }
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
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return

                val handle = network.getNetworkHandle()
                if (handle == lastNetworkHandle) return
                lastNetworkHandle = handle

                if (!networkInitialized) {
                    networkInitialized = true
                    return
                }
                
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    delay(2000)
                    if (!userStopped.get() && process.get() != null) {
                        AppLogsState.addLog(getString(R.string.log_network_change))
                        updateNotification(getString(R.string.notification_network_change))
                        restartCount = 0
                        stopBinaryProcessGracefully()
                    }
                }
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun handleStopAction() {
        if (userStopped.getAndSet(true)) return
        ProxyServiceState.setRunning(false)
        serviceScope.launch {
            stopBinaryProcessGracefully()
            withContext(Dispatchers.Main) {
                stopSelf()
            }
        }
    }

    private suspend fun checkJazzAvailability(): Boolean = withContext(Dispatchers.IO) {
        try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("bk.salutejazz.ru", 443), 3000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun updateNotification(text: String) {
        ProxyServiceState.setStatusText(text)
        NotificationHelper.updateNotification(this)
    }

    private fun isQuotaError(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("quota") || l.contains("allocation quota")
    }

    override fun onDestroy() {
        super.onDestroy()
        isStarted.set(false)
        userStopped.set(true)
        
        ProxyServiceState.setWorking(false)
        ProxyServiceState.setRunning(false)
        NotificationHelper.updateNotification(this)
        ProxyTileService.requestUpdate(this)
        
        stopService(Intent(this, XrayService::class.java))
        
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        AppLogsState.addLog(getString(R.string.log_stop_ui))

        serviceScope.launch {
            stopBinaryProcessGracefully()
            serviceScope.cancel()
        }

        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    companion object {
        const val ACTION_STOP = "com.wireturn.app.ACTION_STOP"
        const val MAX_RESTARTS = 8
        private val CAPTCHA_URL_REGEX = Pattern.compile("""Open this URL in your browser:\s*(https?://\S+)""")

        fun start(context: Context, cfg: ClientConfig) {
            cfg.getValidationErrorResId()?.let { errorRes ->
                ProxyServiceState.setStartupResult(StartupResult.Failed(context.getString(errorRes)))
                return
            }
            val serviceIntent = Intent(context, ProxyService::class.java)
            context.startForegroundService(serviceIntent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
