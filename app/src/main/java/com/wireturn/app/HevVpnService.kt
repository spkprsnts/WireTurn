package com.wireturn.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.data.XrayConfig.Companion.DEFAULT_SOCKS_BIND_ADDRESS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("unused")
internal class HevSocks5Tunnel {
    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray

    companion object {
        init {
            System.loadLibrary("hevsocks5")
        }
    }
}

@Suppress("VpnServicePolicy")
class HevVpnService : VpnService() {
    private val hevTunnel = HevSocks5Tunnel()
    private val hevRunning = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunInterface: ParcelFileDescriptor? = null
    private val isStopping = AtomicBoolean(false)

    private fun disableVpnMode() {
        val context = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = AppPreferences(context)
                val settings = prefs.xraySettingsFlow.first()
                if (settings.xrayVpnMode) {
                    prefs.saveXraySettings(settings.copy(xrayVpnMode = false))
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_BY_USER) {
            disableVpnMode()
            stopVpn()
            return START_NOT_STICKY
        }

        val currentState = VpnServiceState.state.value
        if (tunInterface != null || currentState == VpnState.Starting || currentState == VpnState.Running) {
            AppLogsState.addLog("[VPN] Service already running or starting")
            return START_STICKY
        }
        isStopping.set(false)

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
            AppLogsState.addLog("[VPN] Failed to start foreground: ${e.message}")
        }

        val defaultSocks = DEFAULT_SOCKS_BIND_ADDRESS
        val socks5Addr = intent?.getStringExtra(EXTRA_SOCKS5_ADDR)?.takeIf { it.isNotBlank() } ?: defaultSocks

        VpnServiceState.updateStatus(VpnState.Starting)
        NotificationHelper.updateNotification(this)
        serviceScope.launch {
            startVpn(socks5Addr)
        }
        return START_STICKY
    }

    private suspend fun startVpn(socks5Addr: String) {
        try {
            AppLogsState.addLog("[hev-socks5-tunnel] Establishing tunnel")
            val prefs = AppPreferences(applicationContext)
            val globalVpn = prefs.globalVpnSettingsFlow.first()
            val excludedApps = prefs.excludedAppsFlow.first()

            val builder = this.Builder()
                .setSession("wireturn VPN")
                .addAddress("10.0.0.1", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            if (!globalVpn.filteringEnabled) {
                builder.addRoute("0.0.0.0", 0)
                builder.addDisallowedApplication(packageName)
                AppLogsState.addLog("[VPN] App filtering disabled: all traffic through VPN")
            } else if (globalVpn.bypassMode) {
                builder.addRoute("0.0.0.0", 0)
                builder.addDisallowedApplication(packageName)
                excludedApps.forEach { pkg ->
                    try { builder.addDisallowedApplication(pkg) }
                    catch (e: Exception) { AppLogsState.addLog("[VPN] Could not exclude $pkg: ${e.message}") }
                }
            } else {
                if (excludedApps.isNotEmpty()) {
                    builder.addRoute("0.0.0.0", 0)
                    excludedApps.forEach { pkg ->
                        try { builder.addAllowedApplication(pkg) }
                        catch (e: Exception) { AppLogsState.addLog("[VPN] Could not include $pkg: ${e.message}") }
                    }
                } else {
                    AppLogsState.addLog("[VPN] Include mode with empty list: no apps will use VPN")
                }
            }

            val established = builder.establish()
            if (established == null) {
                AppLogsState.addLog("[hev-socks5-tunnel] Failed to establish TUN interface")
                disableVpnMode()
                stopSelf()
                return
            }

            val tunFd = established.fd
            synchronized(this) {
                if (isStopping.get()) {
                    try { established.close() } catch (_: Exception) {}
                    return
                }
                tunInterface = established
                hevRunning.set(true)
            }

            val lastColon = socks5Addr.lastIndexOf(':')
            val socks5Host = if (lastColon > 0) socks5Addr.substring(0, lastColon) else socks5Addr
            val socks5Port = if (lastColon > 0) socks5Addr.substring(lastColon + 1).toIntOrNull() ?: 1080 else 1080

            val configFile = File(filesDir, "hev-socks5-tunnel.yaml")
            configFile.writeText(
                """
tunnel:
  mtu: 8500
socks5:
  port: $socks5Port
  address: '$socks5Host'
  udp: 'udp'
misc:
  log-file: stderr
  log-level: warn
""".trimIndent()
            )

            AppLogsState.addLog("[hev-socks5-tunnel] Starting (fd=$tunFd, proxy=$socks5Addr)")
            VpnServiceState.updateStatus(VpnState.Running)
            NotificationHelper.updateNotification(this@HevVpnService)

            withContext(Dispatchers.IO) {
                hevTunnel.TProxyStartService(configFile.absolutePath, tunFd)
            }
            AppLogsState.addLog("[hev-socks5-tunnel] Tunnel start command sent")

        } catch (e: Exception) {
            AppLogsState.addLog("[hev-socks5-tunnel] Error: ${e.message}")
            VpnServiceState.updateStatus(VpnState.Error(e.message ?: "Unknown error"))
            stopVpn()
        }
    }

    private fun stopVpn() {
        isStopping.set(true)
        val wasRunning = hevRunning.getAndSet(false)
        
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            if (wasRunning) {
                try {
                    hevTunnel.TProxyStopService()
                    AppLogsState.addLog("[hev-socks5-tunnel] Tunnel stopped")
                } catch (e: Exception) {
                    AppLogsState.addLog("[hev-socks5-tunnel] Stop error: ${e.message}")
                }
            }

            synchronized(this@HevVpnService) {
                tunInterface?.let { try { it.close() } catch (_: Exception) {} }
                tunInterface = null
            }

            withContext(Dispatchers.Main) {
                if (!isStopping.get()) return@withContext // New start already happened, don't set to Idle

                val currentState = VpnServiceState.state.value
                if (currentState !is VpnState.Error) {
                    VpnServiceState.updateStatus(VpnState.Idle)
                }
                NotificationHelper.updateNotification(this@HevVpnService)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        serviceScope.cancel()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    companion object {
        const val ACTION_STOP = "STOP"
        const val ACTION_STOP_BY_USER = "STOP_BY_USER"
        const val EXTRA_SOCKS5_ADDR = "socks5_addr"
    }
}
