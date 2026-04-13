package com.wireturn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wireturn.app.viewmodel.WireproxyState
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.data.AppPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference

class WireproxyService : Service() {

    private val process = AtomicReference<Process?>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val userStopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private var restartCount = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Wireproxy", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startVpnSupervisor()
    }

    private fun startVpnSupervisor() {
        serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            combine(
                WireproxyServiceState.state,
                prefs.clientConfigFlow,
                prefs.wgConfigFlow
            ) { state, cfg, wgCfg ->
                Triple(state == WireproxyState.Running, cfg.wireproxyVpnMode, wgCfg)
            }.collect { (isRunning, vpnEnabled, wgCfg) ->
                withContext(Dispatchers.Main) {
                    if (isRunning && vpnEnabled) {
                        if (VpnServiceState.state.value == VpnState.Idle) {
                            val vpnIntent = Intent(this@WireproxyService, Tun2SocksVpnService::class.java).apply {
                                putExtra(Tun2SocksVpnService.EXTRA_SOCKS5_ADDR, wgCfg.socks5BindAddress)
                                putExtra(Tun2SocksVpnService.EXTRA_MTU, wgCfg.mtu.toIntOrNull() ?: 1280)
                            }
                            startService(vpnIntent)
                        }
                    } else {
                        if (VpnServiceState.state.value != VpnState.Idle) {
                            val stopIntent = Intent(this@WireproxyService, Tun2SocksVpnService::class.java).apply {
                                action = Tun2SocksVpnService.ACTION_STOP
                            }
                            startService(stopIntent)
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userStopped.set(false)
        restartCount = 0
        WireproxyServiceState.updateStatus(WireproxyState.Starting)
        
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireproxy")
            .setContentText("WireGuard tunnel is active")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            startWireproxy()
        }

        return START_STICKY
    }

    private suspend fun startWireproxy() {
        val executable = "${applicationInfo.nativeLibraryDir}/libwireproxy.so"
        val configFile = File(filesDir, "wg.conf")

        try {
            val prefs = AppPreferences(this)
            val rawConfig = prefs.wgConfigFlow.first()
            
            if (!rawConfig.isValid()) {
                ProxyServiceState.addLog(getString(R.string.log_wireproxy_invalid_config))
                stopSelf()
                return
            }

            val wgConfig = rawConfig.fillDefaults()
            WireproxyServiceState.setRunningConfig(wgConfig)
            prefs.saveWgConfig(wgConfig)
            
            withContext(Dispatchers.IO) {
                configFile.writeText(wgConfig.toWgString())
            }

            val randomPort = withContext(Dispatchers.IO) {
                try {
                    java.net.ServerSocket(0).use { it.localPort }
                } catch (_: Exception) {
                    (10000..60000).random()
                }
            }

            val cmdArgs = mutableListOf(
                executable,
                "--config", configFile.absolutePath,
                "--silent",
                "-i", "127.0.0.1:$randomPort"
            )

            ProxyServiceState.addLog("[Wireproxy] starting: ${cmdArgs.joinToString(" ")}")
            val proc = withContext(Dispatchers.IO) {
                ProcessBuilder(cmdArgs)
                    .redirectErrorStream(true)
                    .start()
            }
            process.set(proc)
            WireproxyServiceState.updateMetricsPort(randomPort)
            WireproxyServiceState.updateStatus(WireproxyState.Running)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line?.contains("Health metric request") ?: false) continue
                    ProxyServiceState.addLog("[Wireproxy] $line")
                }
            }
            val exitCode = withContext(Dispatchers.IO) {
                proc.waitFor()
            }
            ProxyServiceState.addLog("[Wireproxy] process exited with code $exitCode")
        } catch (_: InterruptedIOException) {
            // pass
        } catch (e: Exception) {
            ProxyServiceState.addLog("[Wireproxy] Error: ${e.message}")
        } finally {
            process.set(null)
            WireproxyServiceState.updateMetricsPort(null)
            WireproxyServiceState.updateStatus(WireproxyState.Idle)
            if (userStopped.get()) {
                ProxyServiceState.addLog("[Wireproxy] stopped by user")
                stopSelf()
            } else {
                scheduleWatchdogRestart()
            }
        }
    }

    private fun scheduleWatchdogRestart() {
        restartCount++
        if (restartCount > MAX_RESTARTS) {
            ProxyServiceState.addLog("[Wireproxy] watchdog limit reached ($MAX_RESTARTS)")
            stopSelf()
            return
        }
        
        WireproxyServiceState.updateStatus(WireproxyState.Starting)
        val delay = minOf(1000L * restartCount, 10000L)
        ProxyServiceState.addLog("[Wireproxy] restarting in ${delay}ms (attempt $restartCount/$MAX_RESTARTS)")
        
        handler.postDelayed({
            if (!userStopped.get()) {
                serviceScope.launch { startWireproxy() }
            }
        }, delay)
    }

    override fun onDestroy() {
        super.onDestroy()
        userStopped.set(true)
        handler.removeCallbacksAndMessages(null)
        process.get()?.destroyForcibly()
        WireproxyServiceState.updateMetricsPort(null)

        // Explicitly stop child VPN service
        val stopIntent = Intent(this, Tun2SocksVpnService::class.java).apply {
            action = Tun2SocksVpnService.ACTION_STOP
        }
        startService(stopIntent)

        serviceScope.cancel()
        WireproxyServiceState.updateStatus(WireproxyState.Idle)
    }

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "WireProxyChannel"
        private const val MAX_RESTARTS = 3
    }
}

