package com.wireturn.app

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.wireturn.app.viewmodel.XrayState
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.XraySettings
import com.wireturn.app.data.GlobalVpnSettings
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference

class XrayService : Service() {

    private val process = AtomicReference<Process?>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var xrayJob: kotlinx.coroutines.Job? = null
    private val userStopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private var restartCount = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        NotificationHelper.observeStates(this, serviceScope)
        startXraySupervisor()
    }

    // Removed observeLifecycle() to prevent race conditions during ProxyService restarts.
    // ProxyService is now solely responsible for managing XrayService lifecycle.

    private fun startXraySupervisor() {
        serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            var lastExcludedApps: Set<String>? = null
            var lastBypassMode: Boolean? = null
            var lastFilteringEnabled: Boolean? = null
            var lastVpnEnabled: Boolean? = null

            combine(
                listOf(
                    XrayServiceState.state,
                    prefs.xraySettingsFlow,
                    prefs.globalVpnSettingsFlow,
                    prefs.excludedAppsFlow,
                    XrayServiceState.xrayConfigSnapshot,
                    VpnServiceState.state
                )
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                val state = args[0] as XrayState
                @Suppress("UNCHECKED_CAST")
                val xraySettings = args[1] as XraySettings
                @Suppress("UNCHECKED_CAST")
                val globalVpn = args[2] as GlobalVpnSettings
                @Suppress("UNCHECKED_CAST")
                val excludedApps = args[3] as Set<String>
                @Suppress("UNCHECKED_CAST")
                val runningXray = args[4] as XrayConfig?
                @Suppress("UNCHECKED_CAST")
                val vpnState = args[5] as VpnState

                DataBundle(
                    xrayState = state,
                    vpnEnabled = xraySettings.xrayVpnMode,
                    bypassMode = globalVpn.bypassMode,
                    filteringEnabled = globalVpn.filteringEnabled,
                    excludedApps = excludedApps,
                    runningXray = runningXray,
                    vpnState = vpnState
                )
            }.collect { bundle ->
                withContext(Dispatchers.Main) {
                    val excludedChanged = lastExcludedApps != null && lastExcludedApps != bundle.excludedApps
                    val bypassModeChanged = lastBypassMode != null && lastBypassMode != bundle.bypassMode
                    val filteringChanged = lastFilteringEnabled != null && lastFilteringEnabled != bundle.filteringEnabled
                    val vpnEnabledChanged = lastVpnEnabled != null && lastVpnEnabled != bundle.vpnEnabled
                    
                    lastExcludedApps = bundle.excludedApps
                    lastBypassMode = bundle.bypassMode
                    lastFilteringEnabled = bundle.filteringEnabled
                    lastVpnEnabled = bundle.vpnEnabled

                    val shouldVpnBeActive = bundle.vpnEnabled && bundle.xrayState != XrayState.Idle

                    if (shouldVpnBeActive) {
                        val runningXray = bundle.runningXray
                        val vpnRunning = bundle.vpnState == VpnState.Running
                        val vpnError = bundle.vpnState is VpnState.Error
                        val anySettingChanged = excludedChanged || bypassModeChanged || filteringChanged || vpnEnabledChanged

                        if (runningXray != null) {
                            // Запускаем если Idle ИЛИ если что-то изменилось (перезапуск)
                            // Если состояние Error — перезапускаем только при изменении настроек
                            val needsStart = bundle.vpnState == VpnState.Idle || (anySettingChanged && (vpnRunning || vpnError))
                            
                            if (needsStart) {
                                if (vpnRunning || vpnError) {
                                    AppLogsState.addLog("[VPN] Restarting due to settings change or recovery")
                                    val stopIntent = Intent(this@XrayService, HevVpnService::class.java).apply {
                                        action = HevVpnService.ACTION_STOP
                                    }
                                    startService(stopIntent)
                                }
                                
                                if (VpnServiceState.state.value != VpnState.Starting) {
                                    val vpnIntent = Intent(this@XrayService, HevVpnService::class.java).apply {
                                        putExtra(HevVpnService.EXTRA_SOCKS5_ADDR, runningXray.connectableAddress)
                                        if (runningXray.isProxyAuthEnabled && runningXray.proxyUser.isNotBlank()) {
                                            putExtra(HevVpnService.EXTRA_SOCKS5_USER, runningXray.proxyUser)
                                            putExtra(HevVpnService.EXTRA_SOCKS5_PASS, runningXray.proxyPass)
                                        }
                                    }
                                    startService(vpnIntent)
                                }
                            }
                        }
                    } else {
                        if (bundle.vpnState != VpnState.Idle) {
                            val stopIntent = Intent(this@XrayService, HevVpnService::class.java).apply {
                                action = HevVpnService.ACTION_STOP
                            }
                            startService(stopIntent)
                        }
                    }
                }
            }
        }
    }

    private data class DataBundle(
        val xrayState: XrayState,
        val vpnEnabled: Boolean,
        val bypassMode: Boolean,
        val filteringEnabled: Boolean,
        val excludedApps: Set<String>,
        val runningXray: XrayConfig?,
        val vpnState: VpnState
    )

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
            AppLogsState.addLog("[Xray] Failed to start foreground: ${e.message}")
        }

        xrayJob = serviceScope.launch {
            val prefs = AppPreferences(this@XrayService)
            val snapshot = XrayConfigsSnapshot(
                wg = prefs.wgConfigFlow.first(),
                xray = prefs.xrayConfigFlow.first(),
                vless = prefs.vlessConfigFlow.first(),
                client = ProxyServiceState.clientConfigSnapshot.value ?: prefs.clientConfigFlow.first()
            )
            startXray(snapshot)
        }

        return START_STICKY
    }

    private data class XrayConfigsSnapshot(
        val wg: WgConfig,
        val xray: XrayConfig,
        val vless: VlessConfig,
        val client: ClientConfig
    )

    private suspend fun startXray(snapshot: XrayConfigsSnapshot) {
        val executable = "${applicationInfo.nativeLibraryDir}/libxray.so"

        try {
            val rawWgConfig = snapshot.wg
            val rawXrayConfig = snapshot.xray
            val rawVlessConfig = snapshot.vless
            val runningClientConfig = snapshot.client
            
            val isXrayVless = rawXrayConfig.xrayConfiguration == com.wireturn.app.data.XrayConfiguration.VLESS

            val isConfigValid = if (runningClientConfig.kernelVariant == KernelVariant.OLCRTC) {
                // For OLCRTC, VLESS/WG config is optional, unless DualRoute is enabled
                if (isXrayVless && rawVlessConfig.isDualRoute) {
                    rawVlessConfig.isValid()
                } else {
                    true
                }
            } else {
                if (isXrayVless) {
                    rawVlessConfig.isValid()
                } else {
                    rawWgConfig.isValid()
                }
            }

            if (!isConfigValid) {
                AppLogsState.addLog(getString(R.string.log_proxy_invalid_config))
                stopSelf()
                return
            }

            val wgConfig = rawWgConfig.fillDefaults()
            val xrayConfig = rawXrayConfig.fillDefaults()
            val vlessConfig = rawVlessConfig.fillDefaults()

            // Save filled defaults back to preferences if they changed,
            // so UI doesn't see a difference between "raw" and "running" configs.
            val prefs = AppPreferences(this@XrayService)
            if (!isXrayVless && wgConfig != rawWgConfig) {
                prefs.saveWgConfig(wgConfig)
            }
            if (isXrayVless && vlessConfig != rawVlessConfig) {
                prefs.saveVlessConfig(vlessConfig)
            }
            if (xrayConfig != rawXrayConfig) {
                prefs.saveXrayConfig(xrayConfig)
            }
            
            // Фиксируем только тот конфиг, который реально запускаем
            XrayServiceState.setConfigsSnapshot(
                wg = if (isXrayVless) null else wgConfig,
                xray = xrayConfig,
                vless = if (isXrayVless) vlessConfig else null
            )
            
            val socketName = "sys.ipc.${java.util.UUID.randomUUID().toString().replace("-", "").take(12)}"

            val cmdArgs = mutableListOf(
                executable,
                "-listen", xrayConfig.socksBindAddress,
                "-stats-socket", socketName
            )

            if (xrayConfig.httpBindAddress.isNotBlank()) {
                cmdArgs.add("-http")
                cmdArgs.add(xrayConfig.httpBindAddress)
            }

            if (xrayConfig.isProxyAuthEnabled && xrayConfig.proxyUser.isNotBlank()) {
                cmdArgs.add("-proxy-user")
                cmdArgs.add(xrayConfig.proxyUser)
                cmdArgs.add("-proxy-pass")
                cmdArgs.add(xrayConfig.proxyPass)
            }
            
            if (runningClientConfig.kernelVariant == KernelVariant.OLCRTC) {
                cmdArgs.add("-local-socks5")
                val o = runningClientConfig.olcrtcConfig
                cmdArgs.add("${o.socksHost}:${o.socksPort}")
            } else {
                // For other kernels, always use local proxy address
                cmdArgs.add("-local-address")
                cmdArgs.add(runningClientConfig.connectableAddress)
            }

            if (isXrayVless) {
                if (rawVlessConfig.vlessLink.isNotBlank()) {
                    prefs.addVlessLinkToHistory(rawVlessConfig.vlessLink)
                }
                
                val shouldAddLink = if (runningClientConfig.kernelVariant == KernelVariant.OLCRTC) {
                    rawVlessConfig.isDualRoute && rawVlessConfig.vlessLink.isNotBlank()
                } else {
                    true
                }

                if (shouldAddLink) {
                    cmdArgs.addAll(listOf("-link", rawVlessConfig.vlessLink))
                }

                if (vlessConfig.isDualRoute && vlessConfig.directAddress.isNotBlank()) {
                    cmdArgs.add("-direct-address")
                    cmdArgs.add(vlessConfig.directAddress)
                    cmdArgs.add("-hc-interval")
                    cmdArgs.add(vlessConfig.hcInterval)
                }
            } else if (runningClientConfig.kernelVariant != KernelVariant.OLCRTC) {
                cmdArgs.addAll(listOf(
                    "-wg-private-key", wgConfig.privateKey,
                    "-wg-public-key", wgConfig.publicKey,
                    "-wg-endpoint", runningClientConfig.connectableAddress,
                    "-wg-address", wgConfig.address,
                    "-wg-mtu", wgConfig.mtu,
                    "-wg-keepalive", wgConfig.persistentKeepalive)
                )
            }

            AppLogsState.addLog("[Xray] starting: ${cmdArgs.joinToString(" ")}")
            val proc = withContext(Dispatchers.IO) {
                ProcessBuilder(cmdArgs)
                    .redirectErrorStream(true)
                    .start()
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
                    AppLogsState.addLog("[Xray] $cleanLine")
                    linesProcessed++

                    if (!started && (cleanLine.contains("Xray started") || 
                        cleanLine.contains("proxy on") || 
                        cleanLine.contains("Listening"))) {
                        started = true
                        
                        if (!(isXrayVless && rawVlessConfig.isDualRoute)) {
                            XrayServiceState.updateStatus(XrayState.Running)
                        } else {
                            XrayServiceState.updateStatus(XrayState.Connecting)
                        }
                    }

                    if (isXrayVless && rawVlessConfig.isDualRoute) {
                        handleDualRouteLog(cleanLine, socketName)
                    }
                }
            }
            val exitCode = withContext(Dispatchers.IO) {
                proc.waitFor()
            }
            AppLogsState.addLog("[Xray] process exited with code $exitCode")
        } catch (_: InterruptedIOException) {
            // pass
        } catch (_: CancellationException) {
            // normal cancellation on restart
        } catch (e: Exception) {
            AppLogsState.addLog("[Xray] Error: ${e.message}")
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
                AppLogsState.addLog("[Xray] stopped by user")
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
                if (ProxyServiceState.isRunning.value && ProxyServiceState.status.value !is ProxyStatus.Suppressed) {
                    AppLogsState.addLog("[DualRoute] Direct connection established, suppressing tunnel")
                    ProxyServiceState.setStatus(ProxyStatus.Suppressed)
                }
            }
            line.contains("active route: local") -> {
                XrayServiceState.updateStatus(XrayState.Running)
                
                val directUnreachable = line.contains("direct unreachable")
                val bothUnreachable = line.contains("both unreachable")
                
                if (directUnreachable || bothUnreachable) {
                    if (ProxyServiceState.status.value is ProxyStatus.Suppressed) {
                        AppLogsState.addLog("[DualRoute] Direct connection lost, unsuppressing tunnel")
                        ProxyServiceState.setStatus(ProxyStatus.Connecting)
                    }
                    
                    if (!ProxyServiceState.isRunning.value) {
                        AppLogsState.addLog("[DualRoute] Routes unreachable, starting tunnel")
                        serviceScope.launch {
                            val prefs = AppPreferences(applicationContext)
                            val cfg = prefs.clientConfigFlow.first()
                            ProxyService.start(this@XrayService, cfg)
                            
                            // Force Xray to check connection after tunnel starts
                            ProxyServiceState.isWorking.first { it }
                            delay(500)
                            try {
                                withContext(Dispatchers.IO) {
                                    val socket = LocalSocket()
                                    socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                                    socket.outputStream.write("check 3\n".toByteArray())
                                    socket.inputStream.bufferedReader().readLine()
                                    socket.close()
                                }
                            } catch (e: Exception) {
                                AppLogsState.addLog("[DualRoute] Failed to trigger check: ${e.message}")
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
            AppLogsState.addLog("[Xray] watchdog limit reached ($MAX_RESTARTS)")
            stopSelf()
            return
        }
        
        XrayServiceState.updateStatus(XrayState.Starting)
        val delay = minOf(1000L * restartCount, 10000L)
        AppLogsState.addLog("[Xray] restarting in ${delay}ms (attempt $restartCount/$MAX_RESTARTS)")
        
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

        // Explicitly stop child VPN service
        val stopIntent = Intent(this, HevVpnService::class.java).apply {
            action = HevVpnService.ACTION_STOP
        }
        startService(stopIntent)

        serviceScope.cancel()
        XrayServiceState.updateStatus(XrayState.Idle)
    }

    companion object {
        private const val MAX_RESTARTS = 3
    }
}
