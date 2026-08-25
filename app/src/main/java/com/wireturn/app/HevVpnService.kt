package com.wireturn.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.XraySettings.Companion.DEFAULT_SOCKS_BIND_ADDRESS
import com.wireturn.app.viewmodel.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("unused")
internal class HevSocks5Tunnel {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
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
    private val nativeLock = Mutex()
    private var startJob: kotlinx.coroutines.Job? = null

    private fun disableVpnMode() {
        val context = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = AppPreferences(context)
                if (prefs.vpnSettingsFlow.first().enabled) {
                    prefs.setVpnEnabled(false)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        NotificationHelper.observeStates(this, serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_BY_USER) {
            VpnServiceState.updateStatus(VpnState.Idle)
            NotificationHelper.updateNotification(this)
            disableVpnMode()
            stopVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_UPDATE_TARGET) {
            val defaultSocks = DEFAULT_SOCKS_BIND_ADDRESS
            val socks5Addr = intent.getStringExtra(EXTRA_SOCKS5_ADDR)?.takeIf { it.isNotBlank() } ?: defaultSocks
            val socks5User = intent.getStringExtra(EXTRA_SOCKS5_USER)
            val socks5Pass = intent.getStringExtra(EXTRA_SOCKS5_PASS)
            serviceScope.launch { updateTarget(socks5Addr, socks5User, socks5Pass) }
            return START_STICKY
        }

        val currentState = VpnServiceState.state.value
        val isStarting = startJob?.isActive == true
        if (tunInterface != null || currentState == VpnState.Running || (isStarting && currentState == VpnState.Starting)) {
            AppLogsState.addLog(getString(R.string.log_vpn_service_active, currentState.toString(), isStarting))
            return START_STICKY
        }
        isStopping.set(false)
        VpnServiceState.updateStatus(VpnState.Starting)

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
            AppLogsState.addLog(getString(R.string.log_vpn_foreground_failed, e.message ?: "Unknown"))
        }

        val defaultSocks = DEFAULT_SOCKS_BIND_ADDRESS
        val socks5Addr = intent?.getStringExtra(EXTRA_SOCKS5_ADDR)?.takeIf { it.isNotBlank() } ?: defaultSocks
        val socks5User = intent?.getStringExtra(EXTRA_SOCKS5_USER)
        val socks5Pass = intent?.getStringExtra(EXTRA_SOCKS5_PASS)

        startJob = serviceScope.launch {
            startVpn(socks5Addr, socks5User, socks5Pass)
        }
        return START_STICKY
    }

    private fun buildConfigYaml(socks5Addr: String, socks5User: String?, socks5Pass: String?): String {
        val lastColon = socks5Addr.lastIndexOf(':')
        val socks5Host = if (lastColon > 0) socks5Addr.substring(0, lastColon) else socks5Addr
        val socks5Port = if (lastColon > 0) socks5Addr.substring(lastColon + 1).toIntOrNull() ?: 1080 else 1080

        val authConfig = if (!socks5User.isNullOrBlank() && !socks5Pass.isNullOrBlank()) {
            "\n  username: '$socks5User'\n  password: '$socks5Pass'"
        } else {
            ""
        }

        return """
tunnel:
  mtu: $TUN_MTU
socks5:
  port: $socks5Port
  address: '$socks5Host'
  udp: 'udp'$authConfig
mapdns:
  address: $MAPDNS_ADDRESS
  port: 53
  network: $MAPDNS_NETWORK
  netmask: $MAPDNS_NETMASK
  cache-size: 10000
misc:
  task-stack-size: 24576
  tcp-buffer-size: 4096
  max-session-count: 1200
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 60000
  log-file: stderr
  log-level: warn
""".trimIndent()
    }

    // Repoints the already-running native relay at a new SOCKS5 target (e.g. after switching
    // profiles) WITHOUT touching the Android tun interface - establish() is what switches the
    // device's default network and triggers ERR_NETWORK_CHANGED-style disruption in every app,
    // so avoiding a fresh establish() call here is the whole point: the tun stays up, other apps
    // never see a network change, and nothing falls back to the raw network in between.
    private suspend fun updateTarget(socks5Addr: String, socks5User: String? = null, socks5Pass: String? = null) {
        nativeLock.withLock {
            val fd = synchronized(this@HevVpnService) { tunInterface?.fd }
            if (fd == null) {
                AppLogsState.addLog(getString(R.string.log_vpn_retarget_no_tun))
                return
            }

            if (hevRunning.getAndSet(false)) {
                try {
                    hevTunnel.TProxyStopService()
                } catch (e: Exception) {
                    AppLogsState.addLog(getString(R.string.log_vpn_stop_error, e.message ?: "Unknown"))
                }
            }

            val configFile = File(filesDir, "hev-socks5-tunnel.yaml")
            configFile.writeText(buildConfigYaml(socks5Addr, socks5User, socks5Pass))

            AppLogsState.addLog(getString(R.string.log_vpn_retarget, socks5Addr))
            val success = withContext(Dispatchers.IO) {
                hevTunnel.TProxyStartService(configFile.absolutePath, fd)
            }

            if (success) {
                hevRunning.set(true)
                VpnServiceState.updateStatus(VpnState.Running)
                NotificationHelper.updateNotification(this@HevVpnService)
                AppLogsState.addLog(getString(R.string.log_vpn_start))
            } else {
                // Native relay failed to come back up on the existing tun. Tear everything down
                // (rather than leaving a half-alive tun with no relay behind it) - the supervisor
                // in CoreService sees vpnState go back to Idle and does a full, fresh establish().
                AppLogsState.addLog(getString(R.string.log_vpn_error, "retarget failed"))
                stopVpn()
            }
        }
    }

    private suspend fun startVpn(socks5Addr: String, socks5User: String? = null, socks5Pass: String? = null) {
        try {
            AppLogsState.addLog(getString(R.string.log_vpn_establishing))
            val prefs = AppPreferences(applicationContext)
            val vpnSettings = prefs.vpnSettingsFlow.first()

            val builder = this.Builder()
                .setSession("wireturn VPN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4_ADDRESS, 24)
                // hev-socks5-tunnel is IPv4/IPv6 dual-stack (LWIP_IPV6 is on), but without an
                // IPv6 address/route here Android never captures IPv6 traffic into the tun at
                // all - it leaks straight out over the underlying network instead. For any
                // dual-stack site that resolves an AAAA record, Chrome's Happy Eyeballs then
                // races a real (unprotected) IPv6 connection against the tunneled IPv4 one for
                // every new navigation; on a flaky/filtered IPv6 path that shows up as a
                // transient ERR_NETWORK_CHANGED before it falls back to IPv4. Routing IPv6 into
                // the tun closes the leak - even where the upstream SOCKS5 hop can't actually
                // relay an IPv6 destination, that now fails inside the tunnel as an ordinary
                // connection error instead of as a system-wide network change outside it.
                .addAddress(TUN_IPV6_ADDRESS, 128)
                .addDnsServer(MAPDNS_ADDRESS)

            if (!vpnSettings.filteringEnabled) {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                builder.addDisallowedApplication(packageName)
                AppLogsState.addLog(getString(R.string.log_vpn_filtering_disabled))
            } else if (vpnSettings.bypassMode) {
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
                builder.addDisallowedApplication(packageName)
                vpnSettings.excludedApps.forEach { pkg ->
                    try { builder.addDisallowedApplication(pkg) }
                    catch (e: Exception) { AppLogsState.addLog(getString(R.string.log_vpn_exclude_failed, pkg, e.message ?: "Unknown")) }
                }
            } else {
                if (vpnSettings.excludedApps.isNotEmpty()) {
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)
                    vpnSettings.excludedApps.forEach { pkg ->
                        try { builder.addAllowedApplication(pkg) }
                        catch (e: Exception) { AppLogsState.addLog(getString(R.string.log_vpn_include_failed, pkg, e.message ?: "Unknown")) }
                    }
                } else {
                    AppLogsState.addLog(getString(R.string.log_vpn_include_empty))
                }
            }

            val established = builder.establish()
            if (established == null) {
                AppLogsState.addLog(getString(R.string.log_vpn_tun_failed))
                VpnServiceState.updateStatus(VpnState.Error(getString(R.string.error_connecting)))
                NotificationHelper.updateNotification(this@HevVpnService)
                disableVpnMode()
                stopSelf()
                return
            }

            val tunFd = established.fd
            val configFile = File(filesDir, "hev-socks5-tunnel.yaml")
            configFile.writeText(buildConfigYaml(socks5Addr, socks5User, socks5Pass))

            nativeLock.withLock {
                if (isStopping.get()) {
                    try { established.close() } catch (_: Exception) {}
                    if (VpnServiceState.state.value == VpnState.Starting) {
                        VpnServiceState.updateStatus(VpnState.Idle)
                    }
                    return
                }
                
                synchronized(this@HevVpnService) {
                    tunInterface = established
                }

                AppLogsState.addLog(getString(R.string.log_vpn_starting, tunFd, socks5Addr))
                val success = withContext(Dispatchers.IO) {
                    hevTunnel.TProxyStartService(configFile.absolutePath, tunFd)
                }
                
                if (!success) {
                    throw RuntimeException("Native hev-socks5-tunnel failed to start")
                }
                
                hevRunning.set(true)
                VpnServiceState.updateStatus(VpnState.Running)
                NotificationHelper.updateNotification(this@HevVpnService)
                AppLogsState.addLog(getString(R.string.log_vpn_start))
            }

        } catch (e: Exception) {
            AppLogsState.addLog(getString(R.string.log_vpn_error, e.message ?: "Unknown"))
            if (e !is kotlinx.coroutines.CancellationException) {
                VpnServiceState.updateStatus(VpnState.Error(e.message ?: "Unknown error"))
            } else if (VpnServiceState.state.value == VpnState.Starting) {
                VpnServiceState.updateStatus(VpnState.Idle)
            }
            stopVpn()
        }
    }

    private fun stopVpn() {
        isStopping.set(true)
        startJob?.cancel()
        val wasRunning = hevRunning.getAndSet(false)
        
        serviceScope.launch {
            nativeLock.withLock {
                if (wasRunning) {
                    try {
                        hevTunnel.TProxyStopService()
                        AppLogsState.addLog(getString(R.string.log_vpn_stopped))
                    } catch (e: Exception) {
                        AppLogsState.addLog(getString(R.string.log_vpn_stop_error, e.message ?: "Unknown"))
                    }
                }

                synchronized(this@HevVpnService) {
                    tunInterface?.let { try { it.close() } catch (_: Exception) {} }
                    tunInterface = null
                }
            }

            withContext(Dispatchers.Main) {
                if (!isStopping.get()) return@withContext // New start already happened

                val currentState = VpnServiceState.state.value
                if (currentState !is VpnState.Error) {
                    VpnServiceState.updateStatus(VpnState.Idle)
                }
                NotificationHelper.updateNotification(this@HevVpnService)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // stopVpn() only launches its cleanup on serviceScope and returns immediately - cancelling
        // the scope right after (the old behavior here) killed that coroutine before it ever ran,
        // so VpnServiceState never got reset to Idle and the notification (whose only other update
        // path is each service's own observeStates collector, also just killed) was left showing
        // the last "running" state forever. Mirror CoreService.onDestroy(): do the reset and final
        // notification refresh inside the same coroutine, and cancel the scope as its last step.
        isStopping.set(true)
        startJob?.cancel()
        val wasRunning = hevRunning.getAndSet(false)

        serviceScope.launch {
            nativeLock.withLock {
                if (wasRunning) {
                    try {
                        hevTunnel.TProxyStopService()
                        AppLogsState.addLog(getString(R.string.log_vpn_stopped))
                    } catch (e: Exception) {
                        AppLogsState.addLog(getString(R.string.log_vpn_stop_error, e.message ?: "Unknown"))
                    }
                }
                synchronized(this@HevVpnService) {
                    tunInterface?.let { try { it.close() } catch (_: Exception) {} }
                    tunInterface = null
                }
            }

            val currentState = VpnServiceState.state.value
            if (currentState !is VpnState.Error) {
                VpnServiceState.updateStatus(VpnState.Idle)
            }
            NotificationHelper.updateNotification(this@HevVpnService)
            serviceScope.cancel()
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    companion object {
        const val ACTION_STOP = "STOP"
        const val ACTION_STOP_BY_USER = "STOP_BY_USER"
        // Repoints the native relay at a new SOCKS5 target on an already-established tun -
        // see updateTarget(). Only valid while the tun is up; falls back silently otherwise.
        const val ACTION_UPDATE_TARGET = "UPDATE_TARGET"
        const val EXTRA_SOCKS5_ADDR = "socks5_addr"
        const val EXTRA_SOCKS5_USER = "socks5_user"
        const val EXTRA_SOCKS5_PASS = "socks5_pass"

        private const val TUN_MTU = 1280
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        // ULA (RFC 4193), same convention hev-socks5-tunnel's own README example uses.
        private const val TUN_IPV6_ADDRESS = "fc00::1"
        // Deliberately NOT a real public resolver (e.g. 1.1.1.1/8.8.8.8): Chrome silently
        // auto-upgrades to DNS-over-HTTPS when it recognizes the system DNS server as a known
        // provider, which bypasses hev-socks5-tunnel's local fake-DNS responder (hev-mapped-dns.c)
        // entirely - that responder is what keeps AAAA lookups from ever returning a real answer.
        // Once Chrome gets real AAAA records via DoH instead, every navigation races a genuine
        // IPv6 connect through the tunnel, which can still stall/fail against an IPv4-only
        // WireGuard outbound. 198.18.0.1 sits in the RFC 2544 benchmarking range - never publicly
        // routed and not on Chrome's built-in DoH provider list - so it can't trigger the upgrade.
        private const val MAPDNS_ADDRESS = "198.18.0.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
    }
}
