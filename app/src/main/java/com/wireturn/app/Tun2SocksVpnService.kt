package com.wireturn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.data.WgConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference

@Suppress("VpnServicePolicy")
class Tun2SocksVpnService : VpnService() {
    private val process = AtomicReference<Process?>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunInterface: ParcelFileDescriptor? = null
    private val isStopping = java.util.concurrent.atomic.AtomicBoolean(false)

    fun disableVpnMode() {
        serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            val config = prefs.clientConfigFlow.first()
            if (config.wireproxyVpnMode) {
                prefs.saveClientConfig(config.copy(wireproxyVpnMode = false))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Tun2Socks", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            isStopping.set(true)
            stopVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_BY_USER) {
            isStopping.set(true)
            disableVpnMode()
            stopVpn()
            return START_NOT_STICKY
        }

        if (tunInterface != null) {
            ProxyServiceState.addLog("[VPN] Service already running")
            return START_STICKY
        }
        isStopping.set(false)

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        val stopIntent = Intent(this, Tun2SocksVpnService::class.java).apply {
            setAction(ACTION_STOP_BY_USER)
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_mode))
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.vpn_stop),
                stopPendingIntent
            )
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val defaultSocks = WgConfig.DEFAULT_SOCKS5_BIND_ADDRESS
        val defaultMtu = WgConfig.DEFAULT_MTU.toIntOrNull() ?: 1280

        val socks5Addr = intent?.getStringExtra(EXTRA_SOCKS5_ADDR)?.takeIf { it.isNotBlank() } ?: defaultSocks
        val mtu = intent?.getIntExtra(EXTRA_MTU, defaultMtu)?.takeIf { it > 0 } ?: defaultMtu
        
        VpnServiceState.updateStatus(VpnState.Starting)
        serviceScope.launch {
            startVpn(socks5Addr, mtu)
        }
        return START_STICKY
    }

    private suspend fun startVpn(socks5Addr: String, mtu: Int) {
        try {
            ProxyServiceState.addLog("[VPN] Establishing tunnel (MTU: $mtu, excluding $packageName)")
            val builder = Builder()
                .setSession("wireturnWP VPN")
                .setMtu(mtu)
                .addAddress("10.0.0.1", 32)
                .addRoute("0.0.0.0", 0)
                // IPv6 временно отключаем для стабильности, так как прокси может его не поддерживать
                // .addAddress("fd00::1", 128)
                // .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName)

            tunInterface = builder.establish()
            if (tunInterface == null) {
                ProxyServiceState.addLog("[VPN] Failed to establish TUN interface")
                disableVpnMode()
                stopSelf()
                return
            }

            val fd = tunInterface!!.fd
            val executable = "${applicationInfo.nativeLibraryDir}/libtun2socks.so"
            
            val cmdArgs = mutableListOf(
                executable,
                "--device", "fd://0",
                "--proxy", "socks5://$socks5Addr",
                "--loglevel", "warn"
            )

            ProxyServiceState.addLog("[VPN] starting tun2socks via inherited stdin (FD $fd)")
            
            if (isStopping.get()) {
                ProxyServiceState.addLog("[VPN] Stop requested before binary start")
                return
            }

            try {
                android.system.Os.dup2(tunInterface!!.fileDescriptor, android.system.OsConstants.STDIN_FILENO)
            } catch (e: Exception) {
                ProxyServiceState.addLog("[VPN] dup2 error: ${e.message}")
            }

            val proc = withContext(Dispatchers.IO) {
                ProcessBuilder(cmdArgs)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectErrorStream(true)
                    .start()
            }
            process.set(proc)
            VpnServiceState.updateStatus(VpnState.Running)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    ProxyServiceState.addLog("[VPN] $l")
                }
            }
            
            val exitCode = withContext(Dispatchers.IO) {
                proc.waitFor()
            }
            ProxyServiceState.addLog("[VPN] tun2socks exited with code $exitCode")
        } catch (_: InterruptedIOException) {
            // pass
        } catch (e: Exception) {
            ProxyServiceState.addLog("[VPN] Error: ${e.message}")
            VpnServiceState.updateStatus(VpnState.Error(e.message ?: "Unknown error"))
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        process.get()?.let { proc ->
            proc.destroyForcibly()
            try {
                proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {}
        }
        process.set(null)

        // Important: release FD 0 (stdin) and redirect it to /dev/null.
        // This stops holding the TUN file descriptor reference in our process's stdin slot,
        // allowing the system to fully tear down the VPN interface and remove the key icon.
        try {
            val devNull = android.system.Os.open("/dev/null", android.system.OsConstants.O_RDONLY, 0)
            android.system.Os.dup2(devNull, android.system.OsConstants.STDIN_FILENO)
            android.system.Os.close(devNull)
        } catch (e: Exception) {
            ProxyServiceState.addLog("[VPN] Error releasing stdin: ${e.message}")
        }

        try {
            tunInterface?.close()
        } catch (e: Exception) {
            ProxyServiceState.addLog("[VPN] Error closing TUN: ${e.message}")
        }
        tunInterface = null
        VpnServiceState.updateStatus(VpnState.Idle)
        stopSelf()
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
        const val ACTION_STOP = "com.wireturn.app.vpn.STOP"
        const val ACTION_STOP_BY_USER = "com.wireturn.app.vpn.STOP_BY_USER"
        const val EXTRA_SOCKS5_ADDR = "socks5_addr"
        const val EXTRA_MTU = "mtu"
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "VPNChannel"
    }
}

