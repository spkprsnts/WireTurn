package com.wireturn.app

import android.app.PendingIntent
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
import java.io.InterruptedIOException

sealed class StartupResult {
    data object Success : StartupResult()
    data class Failed(val message: String) : StartupResult()
}

class ProxyService : Service() { 
    private var wakeLock: PowerManager.WakeLock? = null
    private var openAppIntent: PendingIntent? = null

    private val process = AtomicReference<Process?>(null)
    private val userStopped = AtomicBoolean(false)
    private val sessionKillScheduled = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    private var lastNetworkHandle: Long = -1
    private var restartCount = 0

    private lateinit var serviceScope: CoroutineScope

    private val isStarted = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        NotificationHelper.createChannel(this)
        startSupervisor()
        observeCaptchaForNotification()
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
                    // Даем небольшую задержку, чтобы избежать "мигания" уведомления 
                    // при закрытии CaptchaActivity до того, как лог об успехе будет обработан
                    delay(1000)
                    if (ProxyServiceState.captchaSession.value != null && !AppLifecycleState.isAppInForeground.value) {
                        NotificationHelper.notifyCaptcha(
                            this@ProxyService,
                            session.url,
                            session.sessionId.toString()
                        )
                    }
                } else {
                    NotificationHelper.cancelCaptchaNotification(this@ProxyService)
                }
            }
        }
    }

    private fun startSupervisor() {
        serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            combine(
                ProxyServiceState.isRunning,
                prefs.xrayConfigFlow
            ) { isRunning, cfg ->
                isRunning && cfg.xrayEnabled
            }.collect { shouldRunProxy ->
                withContext(Dispatchers.Main) {
                    if (shouldRunProxy) {
                        if (XrayServiceState.state.value == XrayState.Idle) {
                            val intent = Intent(this@ProxyService, XrayService::class.java)
                            startForegroundService(intent)
                        }
                    } else {
                        if (XrayServiceState.state.value != XrayState.Idle) {
                            stopService(Intent(this@ProxyService, XrayService::class.java))
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isStarted.getAndSet(true)) return START_STICKY

        openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this))

        ProxyServiceState.setRunning(true)
        NotificationHelper.updateNotification(this)
        ProxyTileService.requestUpdate(this)
        userStopped.set(false)
        restartCount = 0

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))

        registerNetworkCallback()

        ProxyServiceState.addLog(getString(R.string.log_proxy_start))
        serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            var cfg = prefs.clientConfigFlow.first()
            if (cfg.jazzCreds.contains("@salutejazz.ru", ignoreCase = true)) {
                cfg = cfg.copy(jazzCreds = cfg.jazzCreds.replace("@salutejazz.ru", "", ignoreCase = true))
                prefs.saveClientConfig(cfg)
            }
            ProxyServiceState.setRunningConfig(cfg)
            startBinaryProcess(cfg)
        }

        return START_STICKY
    }

    private suspend fun startBinaryProcess(cfg: com.wireturn.app.data.ClientConfig) {
        if (userStopped.get()) return

        val customBin = File(filesDir, "custom_vkturn")
        val useCustom = customBin.exists()
        val executable = if (useCustom) {
            ProxyServiceState.addLog(getString(R.string.log_custom_kernel))
            customBin.absolutePath
        } else {
            ProxyServiceState.addLog(getString(R.string.log_standard_kernel))
            "${applicationInfo.nativeLibraryDir}/libvkturn.so"
        }

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex())
            cmdArgs.add(executable)
            if (parts.isNotEmpty()) cmdArgs.addAll(parts.subList(0, parts.size))
        } else {
            cmdArgs.add(executable)
            cmdArgs.add("-listen"); cmdArgs.add(cfg.localPort)
            if(cfg.dcMode) {
                if (cfg.isJazz) {
                    if (!checkJazzAvailability()) {
                        ProxyServiceState.addLog(getString(R.string.log_jazz_unavailable))
                        ProxyServiceState.setStartupResult(StartupResult.Failed(getString(R.string.error_jazz_unavailable)))
                        withContext(Dispatchers.Main) {
                            userStopped.set(true)
                            stopSelf()
                        }
                        return
                    }

                    cmdArgs.add("-jazz-room")
                    cmdArgs.add(cfg.jazzCreds)
                } else {
                    cmdArgs.add("-yandex-link")
                    cmdArgs.add(cfg.telemostLink)
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
            val linker = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true) {
                "/system/bin/linker64"
            } else {
                "/system/bin/linker"
            }
            cmdArgs.add(0, linker)
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        var startupEmitted = false
        var startupFailed = false
        var captchaActive = false
        var captchaSessionCounter = 0L
        try {
            ProxyServiceState.addLog(getString(R.string.log_command, cmdArgs.joinToString(" ")))

            val proc = withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(cmdArgs)
                builder.redirectErrorStream(true)

                builder.start()
            }
            process.set(proc)

            if (useCustom) {
                ProxyServiceState.setWorking(true)
                ProxyTileService.requestUpdate(this)
            }

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    ProxyServiceState.addLog(l)

                    if (!useCustom && !l.contains("[xray]")) {
                        if (l.contains("Established", ignoreCase = true) || l.contains("listening on") || l.contains("DataChannel connected")) {
                            ProxyServiceState.setWorking(true)
                            ProxyTileService.requestUpdate(this)
                        }
                        if (l.contains("DataChannel closed", ignoreCase = true)) {
                            ProxyServiceState.setWorking(false)
                            ProxyTileService.requestUpdate(this)

                            if (cfg.isJazz) {
                                serviceScope.launch {
                                    if (!checkJazzAvailability()) {
                                        ProxyServiceState.addLog(getString(R.string.log_jazz_unavailable))
                                        userStopped.set(true)
                                        process.get()?.destroyForcibly()
                                        withContext(Dispatchers.Main) {
                                            stopSelf()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (l.contains("Triggering manual captcha fallback")) {
                        captchaActive = true
                    }

                    val captchaMatcher = CAPTCHA_URL_REGEX.matcher(l)
                    if (captchaMatcher.find()) {
                        val captchaUrl = captchaMatcher.group(1)!!
                        captchaSessionCounter += 1
                        ProxyServiceState.setCaptchaSession(
                            CaptchaSession(captchaUrl, captchaSessionCounter)
                        )
                        captchaActive = true
                        updateNotification(getString(R.string.proxy_captcha_required))
                        ProxyTileService.requestUpdate(this)
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

                    if (!startupEmitted) {
                        val lower = l.lowercase()
                        if (lower.contains("panic") || lower.contains("fatal") ||
                            lower.contains("rate limit")) {
                            ProxyServiceState.setStartupResult(StartupResult.Failed(l))
                            updateNotification(getString(R.string.error_connecting))
                            startupFailed = true
                        } else {
                            ProxyServiceState.setStartupResult(StartupResult.Success)
                            updateNotification(getString(R.string.proxy_active))
                        }
                        startupEmitted = true
                    }

                    if (isQuotaError(l) && sessionKillScheduled.compareAndSet(false, true)) {
                        ProxyServiceState.addLog(getString(R.string.log_quota_error))
                        handler.postDelayed({
                            sessionKillScheduled.set(false)
                            if (!userStopped.get()) {
                                restartCount = 0
                                process.get()?.destroyForcibly()
                            }
                        }, 2_000)
                    }
                }
            }

            exitCode = if (withContext(Dispatchers.IO) {
                    proc.waitFor(5, TimeUnit.MINUTES)
                }) proc.exitValue() else -1
            ProxyServiceState.addLog(getString(R.string.log_process_stopped, exitCode))
            if (!startupEmitted) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(
                    getString(R.string.error_process_no_output, exitCode)))
            }

        } catch (_: InterruptedIOException) {
            // pass
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("error=13") || msg.contains("Permission denied")) {
                ProxyServiceState.addLog(getString(R.string.error_kernel_permission_denied))
                ProxyServiceState.setStartupResult(StartupResult.Failed(msg))
                startupFailed = true
            } else {
                ProxyServiceState.addLog(getString(R.string.error_critical_format, e.message))
            }
        } finally {
            ProxyServiceState.setCaptchaSession(null)
            process.set(null)
            when {
                userStopped.get() -> {
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                startupFailed -> {
                    ProxyServiceState.addLog(getString(R.string.log_startup_failed_no_watchdog))
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                exitCode == 0 -> {
                    val uptime = System.currentTimeMillis() - startedAt
                    if (uptime < 5_000L) {
                        ProxyServiceState.addLog(getString(R.string.log_quick_exit, uptime))
                    } else {
                        ProxyServiceState.addLog(getString(R.string.log_session_finished))
                    }
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                else -> scheduleWatchdogRestart()
            }
        }
    }

    private fun scheduleWatchdogRestart() {
        restartCount++
        if (restartCount > MAX_RESTARTS) {
            ProxyServiceState.addLog(getString(R.string.log_watchdog_limit, MAX_RESTARTS))
            ProxyServiceState.setRunning(false)
            ProxyServiceState.emitFailed()
            stopSelf()
            return
        }
        ProxyServiceState.setWorking(false)
        ProxyTileService.requestUpdate(this)
        val baseDelay = minOf(1_000L * restartCount, 30_000L)
        val jitter = Random.nextLong(0, 500)
        val delay = baseDelay + jitter
        ProxyServiceState.addLog(getString(R.string.log_watchdog_restart, delay, restartCount, MAX_RESTARTS))
        updateNotification(getString(R.string.notification_reconnecting, restartCount, MAX_RESTARTS))
        handler.postDelayed({
            if (!userStopped.get()) serviceScope.launch { 
                val cfg = AppPreferences(applicationContext).clientConfigFlow.first()
                ProxyServiceState.setRunningConfig(cfg)
                startBinaryProcess(cfg) 
            }
        }, delay)
    }

    private var networkDebounceJob: Job? = null

    private fun registerNetworkCallback() {
        networkInitialized = false
        lastNetworkHandle = -1
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    return
                }

                val handle = network.getNetworkHandle()
                if (handle == lastNetworkHandle) {
                    return
                }
                lastNetworkHandle = handle

                if (!networkInitialized) {
                    networkInitialized = true
                    return
                }
                
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    delay(2000)
                    if (!userStopped.get() && process.get() != null) {
                        ProxyServiceState.addLog(getString(R.string.log_network_change))
                        updateNotification(getString(R.string.notification_network_change))
                        restartCount = 0
                        val p = process.get()
                        p?.destroyForcibly()
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
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
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
        return l.contains("486") || l.contains("quota") || l.contains("allocation quota")
    }

    override fun onDestroy() {
        super.onDestroy()
        isStarted.set(false)
        userStopped.set(true)
        ProxyServiceState.setWorking(false)
        ProxyServiceState.setRunning(false)
        NotificationHelper.updateNotification(this)

        stopService(Intent(this, XrayService::class.java))

        ProxyTileService.requestUpdate(this)
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        ProxyServiceState.addLog(getString(R.string.log_stop_ui))
        process.get()?.destroyForcibly()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
    
    companion object {
        const val MAX_RESTARTS = 8
        private val CAPTCHA_URL_REGEX =
            Pattern.compile("""Open this URL in your browser:\s*(https?://\S+)""")

        fun start(context: Context, cfg: com.wireturn.app.data.ClientConfig) {
            cfg.getValidationErrorResId()?.let { errorRes ->
                ProxyServiceState.setStartupResult(StartupResult.Failed(context.getString(errorRes)))
                return
            }

            ProxyServiceState.clearLogs()
            ProxyServiceState.setStartupResult(null)

            val serviceIntent = Intent(context, ProxyService::class.java)
            context.startForegroundService(serviceIntent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
