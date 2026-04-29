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
            val settings = prefs.xraySettingsFlow.first()
            if (settings.xrayVpnMode) {
                prefs.saveXraySettings(settings.copy(xrayVpnMode = false))
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
            AppLogsState.addLog("[VPN] Service already running")
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
            AppLogsState.addLog("[tun2socks] Establishing tunnel")
            val prefs = AppPreferences(applicationContext)
            val globalVpn = prefs.globalVpnSettingsFlow.first()
            val excludedApps = prefs.excludedAppsFlow.first()
            
            val builder = this.Builder()
                .setSession("wireturn VPN")
                .addAddress("10.0.0.1", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            if (!globalVpn.filteringEnabled) {
                // Если фильтрация выключена, всё идет через VPN (по умолчанию)
                builder.addRoute("0.0.0.0", 0)
                builder.addDisallowedApplication(packageName)
                AppLogsState.addLog("[VPN] App filtering disabled: all traffic through VPN")
            } else if (globalVpn.bypassMode) {
                // Режим исключений (Exclude mode): все через VPN, кроме выбранных
                builder.addRoute("0.0.0.0", 0)
                builder.addDisallowedApplication(packageName)
                excludedApps.forEach { pkg ->
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (e: Exception) {
                        AppLogsState.addLog("[VPN] Could not exclude $pkg: ${e.message}")
                    }
                }
            } else {
                // Режим включения (Include mode): только выбранные через VPN
                if (excludedApps.isNotEmpty()) {
                    builder.addRoute("0.0.0.0", 0)
                    excludedApps.forEach { pkg ->
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (e: Exception) {
                            AppLogsState.addLog("[VPN] Could not include $pkg: ${e.message}")
                        }
                    }
                } else {
                    AppLogsState.addLog("[VPN] Include mode with empty list: no apps will use VPN")
                }
            }

            tunInterface = builder.establish()
            if (tunInterface == null) {
                AppLogsState.addLog("[tun2socks] Failed to establish TUN interface")
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

            AppLogsState.addLog("[tun2socks] starting libtun2socks.so via inherited stdin (FD $fd)")
            
            if (isStopping.get()) {
                AppLogsState.addLog("[tun2socks] Stop requested before binary start")
                return
            }

            try {
                android.system.Os.dup2(tunInterface!!.fileDescriptor, android.system.OsConstants.STDIN_FILENO)
            } catch (e: Exception) {
                AppLogsState.addLog("[tun2socks] dup2 error: ${e.message}")
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
                    AppLogsState.addLog("[tun2socks] $l")
                }
            }
            
            val exitCode = withContext(Dispatchers.IO) {
                proc.waitFor()
            }
            AppLogsState.addLog("[tun2socks] libtun2socks.so exited with code $exitCode")
            disableVpnMode()
        } catch (_: InterruptedIOException) {
            // pass
        } catch (e: Exception) {
            AppLogsState.addLog("[tun2socks] Error: ${e.message}")
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
            AppLogsState.addLog("[tun2socks] Error releasing stdin: ${e.message}")
        }

        try {
            tunInterface?.close()
        } catch (e: Exception) {
            AppLogsState.addLog("[tun2socks] Error closing TUN: ${e.message}")
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
        const val ACTION_STOP = "STOP"
        const val ACTION_STOP_BY_USER = "STOP_BY_USER"
        const val EXTRA_SOCKS5_ADDR = "socks5_addr"
    }
}
