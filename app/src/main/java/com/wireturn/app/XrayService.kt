package com.wireturn.app

import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.IBinder
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.VpnSettings
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.XraySettings
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.viewmodel.XrayState
import kotlinx.coroutines.CancellationException
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
            var lastVpnSettings: VpnSettings? = null

            combine(
                XrayServiceState.state,
                prefs.vpnSettingsFlow,
                XrayServiceState.session,
                VpnServiceState.state
            ) { state, vpnSettings, xraySession, vpnState ->
                DataBundle(xrayState = state, vpnSettings = vpnSettings, runningSettings = xraySession?.settings, vpnState = vpnState)
            }.collect { bundle ->
                withContext(Dispatchers.Main) {
                    val anySettingChanged = lastVpnSettings != null && lastVpnSettings != bundle.vpnSettings
                    lastVpnSettings = bundle.vpnSettings

                    val shouldVpnBeActive = bundle.vpnSettings.enabled && bundle.xrayState != XrayState.Idle

                    if (shouldVpnBeActive) {
                        val runningSettings = bundle.runningSettings
                        val vpnRunning = bundle.vpnState == VpnState.Running
                        val vpnError = bundle.vpnState is VpnState.Error

                        if (runningSettings != null) {
                            // Запускаем если Idle ИЛИ если что-то изменилось (перезапуск)
                            // Если состояние Error — перезапускаем только при изменении настроек
                            val needsStart = bundle.vpnState == VpnState.Idle || (anySettingChanged && (vpnRunning || vpnError))
                            
                            if (needsStart) {
                                if (vpnRunning || vpnError) {
                                    AppLogsState.addLog(getString(R.string.log_vpn_restarting_config))
                                    val stopIntent = Intent(this@XrayService, HevVpnService::class.java).apply {
                                        action = HevVpnService.ACTION_STOP
                                    }
                                    startService(stopIntent)
                                }
                                
                                if (VpnServiceState.state.value != VpnState.Starting) {
                                    val vpnIntent = Intent(this@XrayService, HevVpnService::class.java).apply {
                                        putExtra(HevVpnService.EXTRA_SOCKS5_ADDR, runningSettings.connectableAddress)
                                        if (runningSettings.isProxyAuthEnabled && runningSettings.proxyUser.isNotBlank()) {
                                            putExtra(HevVpnService.EXTRA_SOCKS5_USER, runningSettings.proxyUser)
                                            putExtra(HevVpnService.EXTRA_SOCKS5_PASS, runningSettings.proxyPass)
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
        val vpnSettings: VpnSettings,
        val runningSettings: XraySettings?,
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

            val isConfigValid = if (runningClientConfig.kernelVariant == KernelVariant.OLCRTC) {
                // For OLCRTC, VLESS/WG config is optional, unless DualRoute is enabled
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
            
            if (runningClientConfig.kernelVariant == KernelVariant.OLCRTC) {
                cmdArgs.add("-local-socks5")
                val socksAddr = if (runningClientConfig.isSocksAuthEnabled && runningClientConfig.socksUser.isNotBlank()) {
                    "${runningClientConfig.socksUser}:${runningClientConfig.socksPass}@${runningClientConfig.socksAddr}"
                } else {
                    runningClientConfig.socksAddr
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
                
                val shouldAddLink = if (runningClientConfig.kernelVariant == KernelVariant.OLCRTC) {
                    vlessConfig.isDualRoute && vlessConfig.vlessLink.isNotBlank()
                } else {
                    true
                }

                if (shouldAddLink) {
                    cmdArgs.addAll(listOf("-link", vlessConfig.vlessLink))
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

            AppLogsState.addLog(getString(R.string.log_xray_starting, cmdArgs.joinToString(" ")))
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
