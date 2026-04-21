package com.wireturn.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.viewmodel.VpnState
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.WgConfig.Companion.DEFAULT_SOCKS5_BIND_ADDRESS
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
        NotificationHelper.createChannel(this)
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
            VpnServiceState.setManuallyDisabled(true)
            disableVpnMode()
            stopVpn()
            return START_NOT_STICKY
        }

        if (tunInterface != null) {
            ProxyServiceState.addLog("[VPN] Service already running")
            return START_STICKY
        }
        isStopping.set(false)

        val notification = NotificationHelper.buildNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }

        val defaultSocks = DEFAULT_SOCKS5_BIND_ADDRESS
        val defaultMtu = WgConfig.DEFAULT_MTU.toIntOrNull() ?: 1280

        val socks5Addr = intent?.getStringExtra(EXTRA_SOCKS5_ADDR)?.takeIf { it.isNotBlank() } ?: defaultSocks
        val mtu = intent?.getIntExtra(EXTRA_MTU, defaultMtu)?.takeIf { it > 0 } ?: defaultMtu
        
        VpnServiceState.updateStatus(VpnState.Starting)
        NotificationHelper.updateNotification(this)
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

            ProxyServiceState.addLog("[VPN] starting libtun2socks.so via inherited stdin (FD $fd)")
            
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
            NotificationHelper.updateNotification(this@Tun2SocksVpnService)

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
            ProxyServiceState.addLog("[VPN] libtun2socks.so exited with code $exitCode")
            disableVpnMode()
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
        NotificationHelper.updateNotification(this)
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
    }
}
