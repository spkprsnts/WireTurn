package com.wireturn.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.wireturn.app.viewmodel.XrayState
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.data.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference

class XrayService : Service() {

    private val process = AtomicReference<Process?>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val userStopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private var restartCount = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        startXraySupervisor()
    }

    private fun startXraySupervisor() {
        serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            combine(
                XrayServiceState.state,
                prefs.xrayConfigFlow,
                prefs.wgConfigFlow
            ) { state, xrayCfg, wgCfg ->
                DataBundle(state == XrayState.Running, xrayCfg.xrayVpnMode, wgCfg, xrayCfg)
            }.collect { bundle ->
                withContext(Dispatchers.Main) {
                    if (bundle.isRunning && bundle.vpnEnabled) {
                        if (VpnServiceState.state.value == VpnState.Idle) {
                            val vpnIntent = Intent(this@XrayService, Tun2SocksVpnService::class.java).apply {
                                putExtra(Tun2SocksVpnService.EXTRA_SOCKS5_ADDR, bundle.xrayCfg.mixedBindAddress)
                                putExtra(Tun2SocksVpnService.EXTRA_MTU, bundle.wgCfg.mtu.toIntOrNull() ?: 1280)
                            }
                            startService(vpnIntent)
                        }
                    } else {
                        if (VpnServiceState.state.value != VpnState.Idle) {
                            val stopIntent = Intent(this@XrayService, Tun2SocksVpnService::class.java).apply {
                                action = Tun2SocksVpnService.ACTION_STOP
                            }
                            startService(stopIntent)
                        }
                    }
                }
            }
        }
    }

    private data class DataBundle(
        val isRunning: Boolean,
        val vpnEnabled: Boolean,
        val wgCfg: com.wireturn.app.data.WgConfig,
        val xrayCfg: com.wireturn.app.data.XrayConfig
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userStopped.set(false)
        restartCount = 0
        XrayServiceState.updateStatus(XrayState.Starting)
        NotificationHelper.updateNotification(this)

        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this))

        serviceScope.launch {
            startXray()
        }

        return START_STICKY
    }

    private suspend fun startXray() {
        val executable = "${applicationInfo.nativeLibraryDir}/libxray.so"

        try {
            val prefs = AppPreferences(this)
            val rawWgConfig = prefs.wgConfigFlow.first()
            val rawXrayConfig = prefs.xrayConfigFlow.first()
            
            if (!rawWgConfig.isValid()) {
                ProxyServiceState.addLog(getString(R.string.log_proxy_invalid_config))
                stopSelf()
                return
            }

            val wgConfig = rawWgConfig.fillDefaults()
            XrayServiceState.setRunningConfigs(wgConfig, rawXrayConfig)
            prefs.saveWgConfig(wgConfig)
            
            val randomMetricsPort = withContext(Dispatchers.IO) {
                try {
                    java.net.ServerSocket(0).use { it.localPort }
                } catch (_: Exception) {
                    (10000..60000).random()
                }
            }

            val cmdArgs = mutableListOf(
                executable,
                "-wg-private-key", wgConfig.privateKey,
                "-wg-public-key", wgConfig.publicKey,
                "-wg-endpoint", wgConfig.endpoint,
                "-wg-address", wgConfig.address,
                "-wg-mtu", wgConfig.mtu,
                "-wg-keepalive", wgConfig.persistentKeepalive,
                "-listen", rawXrayConfig.mixedBindAddress,
                "-metrics", "127.0.0.1:$randomMetricsPort"
            )

            if (rawXrayConfig.httpBindAddress.isNotBlank()) {
                cmdArgs.add("-http")
                cmdArgs.add(rawXrayConfig.httpBindAddress)
            }

            ProxyServiceState.addLog("[Xray] starting: ${cmdArgs.joinToString(" ")}")
            val proc = withContext(Dispatchers.IO) {
                ProcessBuilder(cmdArgs)
                    .redirectErrorStream(true)
                    .start()
            }
            process.set(proc)
            XrayServiceState.updateMetricsPort(randomMetricsPort)
            XrayServiceState.updateStatus(XrayState.Running)
            NotificationHelper.updateNotification(this@XrayService)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ProxyServiceState.addLog("[Xray] $line")
                }
            }
            val exitCode = withContext(Dispatchers.IO) {
                proc.waitFor()
            }
            ProxyServiceState.addLog("[Xray] process exited with code $exitCode")
        } catch (_: InterruptedIOException) {
            // pass
        } catch (e: Exception) {
            ProxyServiceState.addLog("[Xray] Error: ${e.message}")
        } finally {
            process.set(null)
            XrayServiceState.updateMetricsPort(null)
            XrayServiceState.updateStatus(XrayState.Idle)
            NotificationHelper.updateNotification(this@XrayService)
            if (userStopped.get()) {
                ProxyServiceState.addLog("[Xray] stopped by user")
                stopSelf()
            } else {
                scheduleWatchdogRestart()
            }
        }
    }

    private fun scheduleWatchdogRestart() {
        restartCount++
        if (restartCount > MAX_RESTARTS) {
            ProxyServiceState.addLog("[Xray] watchdog limit reached ($MAX_RESTARTS)")
            stopSelf()
            return
        }
        
        XrayServiceState.updateStatus(XrayState.Starting)
        val delay = minOf(1000L * restartCount, 10000L)
        ProxyServiceState.addLog("[Xray] restarting in ${delay}ms (attempt $restartCount/$MAX_RESTARTS)")
        
        handler.postDelayed({
            if (!userStopped.get()) {
                serviceScope.launch { startXray() }
            }
        }, delay)
    }

    override fun onDestroy() {
        super.onDestroy()
        userStopped.set(true)
        handler.removeCallbacksAndMessages(null)
        process.get()?.destroyForcibly()

        // Explicitly stop child VPN service
        val stopIntent = Intent(this, Tun2SocksVpnService::class.java).apply {
            action = Tun2SocksVpnService.ACTION_STOP
        }
        startService(stopIntent)

        serviceScope.cancel()
        XrayServiceState.updateStatus(XrayState.Idle)
    }

    companion object {
        private const val MAX_RESTARTS = 3
    }
}
