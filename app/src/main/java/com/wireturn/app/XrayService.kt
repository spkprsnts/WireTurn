package com.wireturn.app

import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.IBinder
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.XraySettings
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

class XrayService : Service() {

    private val process = AtomicReference<Process?>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var xrayJob: kotlinx.coroutines.Job? = null
    private val userStopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private var restartCount = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var caBundlePath: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        NotificationHelper.observeStates(this, serviceScope)
        caBundlePath = ensureCaBundle()
    }

    // See CoreService.ensureCaBundle() — same rationale: don't depend on the
    // device's (possibly stale) system CA trust store for TLS verification.
    // Masks credentials/keys for the app's own log, which the user may end up sharing for
    // support - the actual cmdArgs passed to the process are untouched.
    private fun redactedCommandLog(cmdArgs: List<String>): String {
        val sensitiveFlags = setOf("-proxy-user", "-proxy-pass", "-local-socks5", "-link", "-wg-private-key")
        return CommandLogRedactor.redact(cmdArgs, sensitiveFlags)
    }

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

    // Removed observeLifecycle() to prevent race conditions during ProxyService restarts.
    // ProxyService is now solely responsible for managing XrayService lifecycle.

    // VPN mode (HevVpnService) is supervised centrally from CoreService.startVpnSupervisor() -
    // CoreService's lifecycle spans the whole "any core running" duration, whereas XrayService is
    // only alive while Xray itself is enabled, so it can't be the sole authority once VPN mode also
    // needs to work directly against an OLCRTC/WEBDAV core's own socks5 with no Xray involved.

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val currentState = XrayServiceState.state.value
        if ((currentState == XrayState.Starting || currentState == XrayState.Running) && xrayJob?.isActive == true) {
            return START_STICKY
        }

        userStopped.set(false)
        restartCount = 0
        
        // Предотвращаем запуск нескольких процессов одновременно
        xrayJob?.cancel()
        process.getAndSet(null)?.destroyForcibly()
        
        XrayServiceState.updateStatus(XrayState.Starting)

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
            AppLogsState.addLog(getString(R.string.log_xray_foreground_failed, e.message ?: "Unknown"))
        }

        xrayJob = serviceScope.launch {
            val prefs = AppPreferences(this@XrayService)
            val rawWg = prefs.wgConfigFlow.first()
            val rawXray = prefs.xrayConfigFlow.first()
            val rawVless = prefs.vlessConfigFlow.first()
            val rawClient = CoreServiceState.session.value?.clientConfig ?: prefs.clientConfigFlow.first()
            val rawXraySettings = prefs.xraySettingsFlow.first()

            val wgConfig = rawWg.fillDefaults()
            val vlessConfig = rawVless.fillDefaults()
            val clientConfig = rawClient.fillDefaults()
            val xraySettings = rawXraySettings.fillDefaults()

            prefs.saveWgConfig(wgConfig)
            prefs.saveXrayConfig(rawXray)
            prefs.saveVlessConfig(vlessConfig)
            prefs.saveClientConfig(clientConfig)
            prefs.saveXraySettings(xraySettings)

            val snapshot = XrayConfigsSnapshot(
                wg = wgConfig,
                xray = rawXray,
                vless = vlessConfig,
                client = clientConfig,
                settings = xraySettings
            )
            startXray(snapshot)
        }

        return START_STICKY
    }

    private data class XrayConfigsSnapshot(
        val wg: WgConfig,
        val xray: XrayConfig,
        val vless: VlessConfig,
        val client: ClientConfig,
        val settings: XraySettings
    )

    private suspend fun startXray(snapshot: XrayConfigsSnapshot) {
        val executable = "${applicationInfo.nativeLibraryDir}/libxray.so"

        try {
            val wgConfig = snapshot.wg
            val xrayConfig = snapshot.xray
            val vlessConfig = snapshot.vless
            val runningClientConfig = snapshot.client
            val xraySettings = snapshot.settings
            
            val isXrayVless = xrayConfig.protocol == com.wireturn.app.data.XrayConfiguration.VLESS

            val isSocks5Core = runningClientConfig.kernelVariant.isSocks5Native

            val isConfigValid = if (isSocks5Core) {
                // For OLCRTC/WebDAV, VLESS/WG config is optional, unless DualRoute is enabled
                if (isXrayVless && vlessConfig.isDualRoute) {
                    vlessConfig.isValid()
                } else {
                    true
                }
            } else {
                if (isXrayVless) {
                    vlessConfig.isValid()
                } else {
                    wgConfig.isValid()
                }
            }

            if (!isConfigValid) {
                AppLogsState.addLog(getString(R.string.log_xray_invalid_config))
                stopSelf()
                return
            }

            // Фиксируем только тот конфиг, который реально запускаем
            XrayServiceState.setSession(XrayServiceState.RunningSession(
                wg = if (isXrayVless) null else wgConfig,
                xray = xrayConfig,
                vless = if (isXrayVless) vlessConfig else null,
                settings = xraySettings
            ))
            
            val prefs = AppPreferences(this@XrayService)
            val socketName = "sys.ipc.${java.util.UUID.randomUUID().toString().replace("-", "").take(12)}"

            val cmdArgs = mutableListOf(
                executable,
                "-listen", xraySettings.socksBindAddress,
                "-stats-socket", socketName
            )

            if (xraySettings.httpBindAddress.isNotBlank()) {
                cmdArgs.add("-http")
                cmdArgs.add(xraySettings.httpBindAddress)
            }

            if (xraySettings.isProxyAuthEnabled && xraySettings.proxyUser.isNotBlank()) {
                cmdArgs.add("-proxy-user")
                cmdArgs.add(xraySettings.proxyUser)
                cmdArgs.add("-proxy-pass")
                cmdArgs.add(xraySettings.proxyPass)
            }
            
            if (isSocks5Core) {
                cmdArgs.add("-local-socks5")
                // socksAddr can be bound to 0.0.0.0 (e.g. to also serve LAN clients) - Xray connects
                // to this as a literal destination, so it needs the loopback form, same as the
                // -local-address branch below already does via connectableAddress.
                val connectableSocksAddr = runningClientConfig.socksAddr.replace("0.0.0.0:", "127.0.0.1:")
                val socksAddr = if (runningClientConfig.isSocksAuthEnabled && runningClientConfig.socksUser.isNotBlank()) {
                    "${runningClientConfig.socksUser}:${runningClientConfig.socksPass}@$connectableSocksAddr"
                } else {
                    connectableSocksAddr
                }
                cmdArgs.add(socksAddr)
            } else {
                // For other kernels, always use local proxy address
                cmdArgs.add("-local-address")
                cmdArgs.add(runningClientConfig.connectableAddress)
            }

            if (isXrayVless) {
                if (vlessConfig.vlessLink.isNotBlank()) {
                    prefs.addVlessLinkToHistory(vlessConfig.vlessLink)
                }
                
                val shouldAddLink = if (isSocks5Core) {
                    vlessConfig.isDualRoute && vlessConfig.vlessLink.isNotBlank()
                } else {
                    true
                }

                if (shouldAddLink) {
                    cmdArgs.addAll(listOf("-link", vlessConfig.vlessLink))
                }

                if (vlessConfig.mux != "0" && vlessConfig.mux.isNotBlank()) {
                    cmdArgs.add("-mux")
                    cmdArgs.add(vlessConfig.mux)
                }

                if (vlessConfig.isDualRoute && vlessConfig.directAddress.isNotBlank()) {
                    cmdArgs.add("-direct-address")
                    cmdArgs.add(vlessConfig.directAddress)
                    cmdArgs.add("-hc-interval")
                    cmdArgs.add(vlessConfig.hcInterval)
                }
            } else if (!isSocks5Core) {
                cmdArgs.addAll(listOf(
                    "-wg-private-key", wgConfig.privateKey,
                    "-wg-public-key", wgConfig.publicKey,
                    "-wg-endpoint", runningClientConfig.connectableAddress,
                    "-wg-address", wgConfig.address,
                    "-wg-mtu", wgConfig.mtu,
                    "-wg-keepalive", wgConfig.persistentKeepalive)
                )
            }

            AppLogsState.addLog(getString(R.string.log_xray_starting, redactedCommandLog(cmdArgs)))
            val proc = withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(cmdArgs).redirectErrorStream(true)
                if (snapshot.client.useCustomCerts) {
                    caBundlePath?.let { builder.environment()["SSL_CERT_FILE"] = it }
                }
                builder.start()
            }
            process.set(proc)
            XrayServiceState.updateStatsSocketName(socketName)
            XrayServiceState.updateStatus(XrayState.Starting)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var started = false
                var linesProcessed = 0
                while (true) {
                    val rawLine = reader.readLine() ?: break
                    val cleanLine = AppLogsState.stripAnsi(rawLine)
                    AppLogsState.addLog("* [Xray] $cleanLine")
                    linesProcessed++

                    if (!started && (cleanLine.contains("Xray started") || 
                        cleanLine.contains("proxy on") || 
                        cleanLine.contains("Listening"))) {
                        started = true
                        
                        if (!(isXrayVless && vlessConfig.isDualRoute)) {
                            XrayServiceState.updateStatus(XrayState.Running)
                        } else {
                            XrayServiceState.updateStatus(XrayState.Connecting)
                        }
                    }

                    if (isXrayVless && vlessConfig.isDualRoute) {
                        handleDualRouteLog(cleanLine, socketName)
                    }
                }
            }
            val exitCode = withContext(Dispatchers.IO) {
                proc.waitFor()
            }
            AppLogsState.addLog(getString(R.string.log_xray_exited, exitCode))
        } catch (_: InterruptedIOException) {
            // pass
        } catch (_: CancellationException) {
            // normal cancellation on restart
        } catch (e: Exception) {
            AppLogsState.addLog(getString(R.string.log_xray_error, e.message ?: "Unknown"))
        } finally {
            process.set(null)
            XrayServiceState.updateStatsSocketName(null)
            
            // Only set Idle if we are NOT being cancelled by a new start command
            val isJobActive = try {
                currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true
            } catch (_: Exception) {
                false
            }
            if (isJobActive) {
                XrayServiceState.updateStatus(XrayState.Idle)
            }
            
            NotificationHelper.updateNotification(this@XrayService)
            if (userStopped.get()) {
                AppLogsState.addLog(getString(R.string.log_xray_stopped_by_user))
                NotificationHelper.updateNotification(this@XrayService)
                stopSelf()
            } else if (isJobActive) {
                scheduleWatchdogRestart(snapshot)
            }
        }
    }

    private fun handleDualRouteLog(line: String, socketName: String) {
        when {
            line.contains("active route: direct") -> {
                XrayServiceState.updateStatus(XrayState.DirectRoute)
                if (CoreServiceState.isRunning.value && CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    AppLogsState.addLog(getString(R.string.log_dual_route_direct_established))
                    CoreServiceState.setStatus(CoreStatus.Suppressed)
                }
            }
            line.contains("active route: local") -> {
                XrayServiceState.updateStatus(XrayState.Running)
                
                val directUnreachable = line.contains("direct unreachable")
                val bothUnreachable = line.contains("both unreachable")
                
                if (directUnreachable || bothUnreachable) {
                    if (CoreServiceState.status.value is CoreStatus.Suppressed) {
                        AppLogsState.addLog(getString(R.string.log_dual_route_direct_lost))
                        CoreServiceState.setStatus(CoreStatus.Connecting)
                    }
                    
                    if (!CoreServiceState.isRunning.value) {
                        AppLogsState.addLog(getString(R.string.log_dual_route_unreachable_start_tunnel))
                        serviceScope.launch {
                            val prefs = AppPreferences(applicationContext)
                            val cfg = prefs.clientConfigFlow.first()
                            CoreService.start(this@XrayService, cfg)
                            
                            // Force Xray to check connection after tunnel starts
                            CoreServiceState.isWorking.first { it }
                            delay(500.milliseconds)
                            try {
                                withContext(Dispatchers.IO) {
                                    val socket = LocalSocket()
                                    socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                                    socket.outputStream.write("check 3\n".toByteArray())
                                    socket.inputStream.bufferedReader().readLine()
                                    socket.close()
                                }
                            } catch (e: Exception) {
                                AppLogsState.addLog(getString(R.string.log_dual_route_check_failed, e.message ?: "Unknown"))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scheduleWatchdogRestart(snapshot: XrayConfigsSnapshot) {
        restartCount++
        if (restartCount > MAX_RESTARTS) {
            AppLogsState.addLog(getString(R.string.log_xray_watchdog_limit, MAX_RESTARTS))
            stopSelf()
            return
        }
        
        XrayServiceState.updateStatus(XrayState.Starting)
        val delay = minOf(1000L * restartCount, 10000L)
        AppLogsState.addLog(getString(R.string.log_xray_watchdog_restart, delay.toInt(), restartCount, MAX_RESTARTS))
        
        handler.postDelayed({
            if (!userStopped.get()) {
                serviceScope.launch { startXray(snapshot) }
            }
        }, delay)
    }

    override fun onDestroy() {
        super.onDestroy()
        userStopped.set(true)
        handler.removeCallbacksAndMessages(null)
        xrayJob?.cancel()
        process.getAndSet(null)?.destroyForcibly()

        // CoreService.startVpnSupervisor() reacts to this and either falls back to the
        // OLCRTC/WEBDAV core's own socks5 (if eligible) or stops VPN mode - not decided here.
        // Must run, and the notification refresh below with it, BEFORE the scope is cancelled:
        // this service's own observeStates collector (started in onCreate) lives on that scope,
        // so cancelling first meant nothing here ever reacted to the Idle transition, leaving a
        // stale "running" notification up whenever this was the last piece to shut down.
        XrayServiceState.updateStatus(XrayState.Idle)
        NotificationHelper.updateNotification(this)
        serviceScope.cancel()
    }

    companion object {
        private const val MAX_RESTARTS = 3
    }
}
