package com.wireturn.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.VpnSettings
import com.wireturn.app.data.XraySettings.Companion.DEFAULT_SOCKS_BIND_ADDRESS
import com.wireturn.app.domain.LanRoutes
import com.wireturn.app.viewmodel.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

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

    private var underlyingNetworkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var vpnEstablished = false

    // Kept live (not just set once at establish()) so the system's view of the VPN's dependency
    // stays accurate across e.g. a Wi-Fi/cellular handover.
    @Volatile
    private var underlyingNetwork: Network? = null
        set(value) {
            if (field == value) return
            field = value
            if (vpnEstablished) {
                try {
                    setUnderlyingNetworks(value?.let { arrayOf(it) })
                } catch (_: Exception) {}
            }
        }

    private fun registerUnderlyingNetworkTracking() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                underlyingNetwork = network
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (network == underlyingNetwork) underlyingNetwork = network
            }

            override fun onLost(network: Network) {
                if (network == underlyingNetwork) underlyingNetwork = null
            }
        }
        underlyingNetworkCallback = callback

        try {
            val handler = Handler(Looper.getMainLooper())
            when {
                // registerDefaultNetworkCallback() alone returns the VPN's own network once
                // established (since Android P), so request NOT_VPN explicitly instead.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    cm.registerBestMatchingNetworkCallback(request, callback, handler)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    cm.requestNetwork(request, callback, handler)
                else ->
                    cm.registerDefaultNetworkCallback(callback, handler)
            }
        } catch (e: Exception) {
            AppLogsState.addLog(getString(R.string.log_vpn_error, "underlying network tracking: ${e.message}"))
            underlyingNetworkCallback = null
        }
    }

    private fun unregisterUnderlyingNetworkTracking() {
        underlyingNetworkCallback?.let { cb ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        underlyingNetworkCallback = null
        underlyingNetwork = null
    }

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
        registerUnderlyingNetworkTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            // An explicit external stop should always clear a stale Error, unlike stopVpn()'s
            // own guard which preserves one set by a failed establish() in the same run.
            if (VpnServiceState.state.value is VpnState.Error) {
                VpnServiceState.updateStatus(VpnState.Idle)
            }
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
            val mapDns = intent.getBooleanExtra(EXTRA_MAP_DNS, true)
            serviceScope.launch { updateTarget(socks5Addr, socks5User, socks5Pass, mapDns) }
            return START_STICKY
        }

        // Kernel TUN mode: build (or keep) the tun from the kernel's assignment and hand it over.
        // Ahead of the "already active" check below - switching over from a hev tun, or handing
        // the same tun to a restarted kernel, both happen on a running service.
        if (action == ACTION_START_NATIVE) {
            val socketName = intent.getStringExtra(EXTRA_NATIVE_SOCKET)
            val address = intent.getStringExtra(EXTRA_NATIVE_ADDRESS)
            val mtu = intent.getIntExtra(EXTRA_NATIVE_MTU, 0)
            if (socketName.isNullOrBlank() || address.isNullOrBlank() || mtu <= 0) return START_NOT_STICKY
            val dns = intent.getStringArrayExtra(EXTRA_NATIVE_DNS)?.toList().orEmpty()
            isStopping.set(false)
            goForeground()
            val previous = startJob
            startJob = serviceScope.launch {
                previous?.cancelAndJoin()
                startNativeVpn(NativeTun(address, dns, mtu), socketName)
            }
            return START_STICKY
        }

        val currentState = VpnServiceState.state.value
        val isStarting = startJob?.isActive == true
        if (tunInterface != null || currentState == VpnState.Running || (isStarting && currentState == VpnState.Starting)) {
            AppLogsState.addLog(getString(R.string.log_vpn_service_active, currentState.toString(), isStarting))
            return START_STICKY
        }

        // Not from CoreService's VPN supervisor, so no relay target: always-on VPN (boot, the
        // system's "disconnected" notification, the system VPN settings) sends SERVICE_INTERFACE,
        // a sticky restart after the process died a null intent. A tun on the default address
        // would have nothing behind it - bring up the core instead, and the supervisor
        // establishes the VPN once it has a target.
        if (intent == null) {
            // CoreService restarts itself too (START_STICKY), and its supervisor brings this back.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (action == SERVICE_INTERFACE) {
            startFromSystem()
            return START_NOT_STICKY
        }

        isStopping.set(false)
        VpnServiceState.updateStatus(VpnState.Starting)
        goForeground()

        val defaultSocks = DEFAULT_SOCKS_BIND_ADDRESS
        val socks5Addr = intent.getStringExtra(EXTRA_SOCKS5_ADDR)?.takeIf { it.isNotBlank() } ?: defaultSocks
        val socks5User = intent.getStringExtra(EXTRA_SOCKS5_USER)
        val socks5Pass = intent.getStringExtra(EXTRA_SOCKS5_PASS)
        val mapDns = intent.getBooleanExtra(EXTRA_MAP_DNS, true)

        startJob = serviceScope.launch {
            startVpn(socks5Addr, socks5User, socks5Pass, mapDns)
        }
        return START_STICKY
    }

    // mapDns: hev answers the tun's DNS itself with fake 100.64.0.0/10 addresses and connects by
    // name - needed in front of a kernel's own SOCKS5, which may not relay UDP. Its table lives only
    // in this hev run, though, so apps that cache DNS past the 1s TTL (Instagram) keep dialing
    // addresses the next run doesn't know, and UDP to those goes nowhere. So with Xray behind hev
    // it's off: the queries reach Xray as plain UDP/53, which it answers itself (dns-out).
    private fun buildConfigYaml(vpnSettings: VpnSettings, socks5Addr: String, socks5User: String?, socks5Pass: String?, mapDns: Boolean): String {
        val lastColon = socks5Addr.lastIndexOf(':')
        val socks5Host = if (lastColon > 0) socks5Addr.substring(0, lastColon) else socks5Addr
        val socks5Port = if (lastColon > 0) socks5Addr.substring(lastColon + 1).toIntOrNull() ?: 1080 else 1080

        val authConfig = if (!socks5User.isNullOrBlank() && !socks5Pass.isNullOrBlank()) {
            "\n  username: '$socks5User'\n  password: '$socks5Pass'"
        } else {
            ""
        }

        val mapDnsConfig = if (mapDns) {
            "\nmapdns:\n  address: $MAPDNS_ADDRESS\n  port: 53\n  network: $MAPDNS_NETWORK\n  netmask: $MAPDNS_NETMASK\n  cache-size: 10000"
        } else {
            ""
        }

        return """
tunnel:
  mtu: ${vpnSettings.mtu}
  icmp: '${if (vpnSettings.icmpReply) "reply" else "off"}'
socks5:
  port: $socks5Port
  address: '$socks5Host'
  udp: 'udp'$authConfig$mapDnsConfig
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

    private fun goForeground() {
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
    }

    // The system brought the VPN up on its own - does what the app's Start button does. Android
    // temp-allowlists an always-on VPN app while launching it, so starting CoreService's own
    // foreground service from here is allowed even with the app in the background.
    private fun startFromSystem() {
        AppLogsState.addLog(getString(R.string.log_vpn_always_on_start))
        goForeground()
        serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            // Always-on means the user wants the VPN - without VPN mode the supervisor would
            // just tear it down again.
            if (!prefs.vpnSettingsFlow.first().enabled) prefs.setVpnEnabled(true)
            val status = CoreServiceState.status.value
            if (status is CoreStatus.Idle || status is CoreStatus.Error) {
                try {
                    CoreService.start(applicationContext, prefs.clientConfigFlow.first())
                } catch (e: Exception) {
                    AppLogsState.addLog(getString(R.string.log_vpn_error, "always-on core start: ${e.message}"))
                }
            }
            // Nothing ever gets established if the core can't start or its profile has no VPN
            // target (Turnable/FreeTurn without Xray) - don't linger as an empty foreground service.
            delay(ALWAYS_ON_TARGET_TIMEOUT_MS.milliseconds)
            if (tunInterface == null && startJob?.isActive != true) {
                AppLogsState.addLog(getString(R.string.log_vpn_always_on_no_target))
                withContext(Dispatchers.Main) { stopSelf() }
            }
        }
    }

    // Everything into the tun, or with LAN bypass on everything but the local ranges, which then
    // stay on the underlying network (see LanRoutes).
    private fun addTunRoutes(builder: Builder, vpnSettings: VpnSettings) {
        if (vpnSettings.bypassLan) {
            LanRoutes.ipv4.forEach { (address, prefix) -> builder.addRoute(address, prefix) }
            if (vpnSettings.ipv6) LanRoutes.ipv6.forEach { (address, prefix) -> builder.addRoute(address, prefix) }
        } else {
            builder.addRoute("0.0.0.0", 0)
            if (vpnSettings.ipv6) builder.addRoute("::", 0)
        }
    }

    // Repoints the relay at a new SOCKS5 target without touching the tun interface - a fresh
    // establish() would switch the device's default network and disrupt every other app.
    private suspend fun updateTarget(socks5Addr: String, socks5User: String?, socks5Pass: String?, mapDns: Boolean) {
        nativeLock.withLock {
            // A kernel-TUN tun has no hev to repoint - leaving that mode goes through a full restart.
            if (nativeTun != null) return
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
            val vpnSettings = AppPreferences(applicationContext).vpnSettingsFlow.first()
            configFile.writeText(buildConfigYaml(vpnSettings, socks5Addr, socks5User, socks5Pass, mapDns))

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

    // Shared by both VPN modes: underlying network, routes (LAN bypass) and the per-app filter.
    private fun configureRoutingAndApps(builder: Builder, vpnSettings: VpnSettings) {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        (underlyingNetwork ?: cm.activeNetwork)?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        // false: metered only while the underlying network is, so it follows a Wi-Fi <-> mobile
        // switch. The active network's meteredness at establish() would stay put until the next
        // one - a VPN brought up on mobile data stayed "metered" on Wi-Fi.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (!vpnSettings.filteringEnabled) {
            addTunRoutes(builder, vpnSettings)
            builder.addDisallowedApplication(packageName)
            AppLogsState.addLog(getString(R.string.log_vpn_filtering_disabled))
        } else if (vpnSettings.bypassMode) {
            addTunRoutes(builder, vpnSettings)
            builder.addDisallowedApplication(packageName)
            vpnSettings.excludedApps.forEach { pkg ->
                try { builder.addDisallowedApplication(pkg) }
                catch (e: Exception) { AppLogsState.addLog(getString(R.string.log_vpn_exclude_failed, pkg, e.message ?: "Unknown")) }
            }
        } else {
            if (vpnSettings.excludedApps.isNotEmpty()) {
                addTunRoutes(builder, vpnSettings)
                vpnSettings.excludedApps.forEach { pkg ->
                    try { builder.addAllowedApplication(pkg) }
                    catch (e: Exception) { AppLogsState.addLog(getString(R.string.log_vpn_include_failed, pkg, e.message ?: "Unknown")) }
                }
            } else {
                AppLogsState.addLog(getString(R.string.log_vpn_include_empty))
            }
        }
    }

    // establish() can transiently return null right after another app releases VPN ownership;
    // the handover window isn't predictable, so retry with backoff.
    private suspend fun establishWithRetry(builder: Builder): ParcelFileDescriptor? {
        var established = builder.establish()
        for (retryDelayMs in longArrayOf(300L, 700L, 1500L, 3000L)) {
            if (established != null || isStopping.get()) break
            delay(retryDelayMs.milliseconds)
            established = builder.establish()
        }
        return established
    }

    // Kernel TUN mode (Kernel.supportsNativeTun): the tun is built from the address/DNS/MTU the
    // kernel was assigned and handed to it - no hev in between. Non-null while the current tun is
    // one of those.
    private data class NativeTun(val address: String, val dns: List<String>, val mtu: Int)
    @Volatile private var nativeTun: NativeTun? = null

    private suspend fun startNativeVpn(params: NativeTun, socketName: String) {
        try {
            val tun = nativeLock.withLock {
                if (isStopping.get()) return
                val current = synchronized(this@HevVpnService) { tunInterface }
                // The same assignment again (the kernel restarted): keep the tun, just hand it over
                // again - no blip in the device's network.
                if (current != null && nativeTun == params) return@withLock current

                if (VpnServiceState.state.value != VpnState.Running) VpnServiceState.updateStatus(VpnState.Starting)
                // A hev tun, or another assignment: the address is part of the interface itself.
                if (hevRunning.getAndSet(false)) {
                    try { hevTunnel.TProxyStopService() } catch (_: Exception) {}
                }
                synchronized(this@HevVpnService) {
                    tunInterface?.let { try { it.close() } catch (_: Exception) {} }
                    tunInterface = null
                }
                nativeTun = null
                vpnEstablished = false

                AppLogsState.addLog(getString(R.string.log_vpn_native_establishing, params.address, params.mtu))
                val vpnSettings = AppPreferences(applicationContext).vpnSettingsFlow.first()
                val builder = this@HevVpnService.Builder()
                    .setSession("wireturn VPN")
                    .setMtu(params.mtu)
                    .addAddress(params.address, 32)
                params.dns.forEach { dns ->
                    try { builder.addDnsServer(dns) } catch (_: Exception) {}
                }
                // The raw path is IPv4-only: no v6 address, so v6 never enters the tun.
                configureRoutingAndApps(builder, vpnSettings.copy(ipv6 = false))

                val established = establishWithRetry(builder)
                if (established == null) {
                    AppLogsState.addLog(getString(R.string.log_vpn_tun_failed))
                    VpnServiceState.updateStatus(VpnState.Error(getString(R.string.error_connecting)))
                    NotificationHelper.updateNotification(this@HevVpnService)
                    stopSelf()
                    return
                }
                synchronized(this@HevVpnService) { tunInterface = established }
                nativeTun = params
                vpnEstablished = true
                established
            }

            if (sendTunFd(socketName, tun)) {
                VpnServiceState.updateStatus(VpnState.Running)
                NotificationHelper.updateNotification(this@HevVpnService)
                AppLogsState.addLog(getString(R.string.log_vpn_native_handed))
            } else {
                // The tun stays up (nothing leaks around it); the kernel waits for it until it's
                // restarted, which asks for it again.
                AppLogsState.addLog(getString(R.string.log_vpn_error, "TUN handover to the kernel failed"))
                VpnServiceState.updateStatus(VpnState.Error(getString(R.string.error_connecting)))
                NotificationHelper.updateNotification(this@HevVpnService)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogsState.addLog(getString(R.string.log_vpn_error, e.message ?: "Unknown"))
            VpnServiceState.updateStatus(VpnState.Error(e.message ?: "Unknown error"))
            stopVpn()
        }
    }

    // The kernel listens on an abstract Unix socket and takes the tun over SCM_RIGHTS: a child
    // process inherits no fds from here otherwise. It may still be getting there, so retry a while.
    private suspend fun sendTunFd(socketName: String, tun: ParcelFileDescriptor): Boolean = withContext(Dispatchers.IO) {
        repeat(TUN_HANDOVER_ATTEMPTS) {
            if (isStopping.get()) return@withContext false
            try {
                android.net.LocalSocket().use { socket ->
                    socket.connect(android.net.LocalSocketAddress(socketName, android.net.LocalSocketAddress.Namespace.ABSTRACT))
                    socket.setFileDescriptorsForSend(arrayOf(tun.fileDescriptor))
                    // The byte itself doesn't matter - it carries the fd as ancillary data.
                    socket.outputStream.write(1)
                    socket.outputStream.flush()
                }
                return@withContext true
            } catch (_: Exception) {
                delay(TUN_HANDOVER_RETRY_MS.milliseconds)
            }
        }
        false
    }

    private suspend fun startVpn(socks5Addr: String, socks5User: String?, socks5Pass: String?, mapDns: Boolean) {
        // The established tun until it's tunInterface's - closed here if this attempt ends before
        // that, or it would outlive it (and keep the device's VPN up) with nothing to close it.
        var pending: ParcelFileDescriptor? = null
        try {
            AppLogsState.addLog(getString(R.string.log_vpn_establishing))
            val prefs = AppPreferences(applicationContext)
            val vpnSettings = prefs.vpnSettingsFlow.first()

            val builder = this.Builder()
                .setSession("wireturn VPN")
                .setMtu(vpnSettings.mtu)
                .addAddress(TUN_IPV4_ADDRESS, 24)
                .addDnsServer(MAPDNS_ADDRESS)
            // Without an IPv6 address Android never captures IPv6 into the tun - it goes straight
            // out over the underlying network instead, unprotected. That's what turning IPv6 off
            // in the settings means.
            if (vpnSettings.ipv6) builder.addAddress(TUN_IPV6_ADDRESS, 128)

            configureRoutingAndApps(builder, vpnSettings)

            val established = establishWithRetry(builder)
            if (established == null) {
                AppLogsState.addLog(getString(R.string.log_vpn_tun_failed))
                VpnServiceState.updateStatus(VpnState.Error(getString(R.string.error_connecting)))
                NotificationHelper.updateNotification(this@HevVpnService)
                // No disableVpnMode() here - this is a failed attempt, not the user opting out;
                // leave VPN mode on. CoreService's VPN supervisor periodically retries establish()
                // itself while state stays Error (see startVpnSupervisor()'s pendingVpnRetryJob).
                stopSelf()
                return
            }
            pending = established
            vpnEstablished = true

            val tunFd = established.fd
            val configFile = File(filesDir, "hev-socks5-tunnel.yaml")
            configFile.writeText(buildConfigYaml(vpnSettings, socks5Addr, socks5User, socks5Pass, mapDns))

            nativeLock.withLock {
                if (isStopping.get()) {
                    pending = null
                    try { established.close() } catch (_: Exception) {}
                    if (VpnServiceState.state.value == VpnState.Starting) {
                        VpnServiceState.updateStatus(VpnState.Idle)
                    }
                    return
                }

                synchronized(this@HevVpnService) {
                    tunInterface = established
                }
                pending = null
                nativeTun = null

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

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Superseded, not failed: by a stop (stopVpn/onDestroy, which clean up themselves) or
            // by a kernel-TUN start (ACTION_START_NATIVE, which replaces the tun). stopVpn() here
            // would cancel that newer startJob too and flag the service as stopping - the kernel's
            // tun would then never come up, with VPN mode still on. Only this attempt's own tun.
            pending?.let { try { it.close() } catch (_: Exception) {} }
            if (VpnServiceState.state.value == VpnState.Starting) {
                VpnServiceState.updateStatus(VpnState.Idle)
            }
            throw e
        } catch (e: Exception) {
            pending?.let { try { it.close() } catch (_: Exception) {} }
            AppLogsState.addLog(getString(R.string.log_vpn_error, e.message ?: "Unknown"))
            VpnServiceState.updateStatus(VpnState.Error(e.message ?: "Unknown error"))
            stopVpn()
        }
    }

    private fun stopVpn() {
        isStopping.set(true)
        startJob?.cancel()
        vpnEstablished = false
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
                nativeTun = null
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
        // Do the Idle reset and final notification refresh inline (not via stopVpn(), whose
        // cleanup coroutine cancelling the scope right after would kill before it runs).
        isStopping.set(true)
        startJob?.cancel()
        vpnEstablished = false
        unregisterUnderlyingNetworkTracking()
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
        // How long an always-on start waits for the supervisor to establish before giving up
        // (see startFromSystem) - a kernel's own startup plus Xray's, with room to spare.
        private const val ALWAYS_ON_TARGET_TIMEOUT_MS = 60_000L
        // Kernel TUN handover: 25 × 200 ms, the same window the official qWDTT client gives it.
        private const val TUN_HANDOVER_ATTEMPTS = 25
        private const val TUN_HANDOVER_RETRY_MS = 200L
        // Kernel TUN mode (Kernel.supportsNativeTun) - see startNativeVpn.
        const val ACTION_START_NATIVE = "START_NATIVE"
        const val EXTRA_NATIVE_SOCKET = "native_socket"
        const val EXTRA_NATIVE_ADDRESS = "native_address"
        const val EXTRA_NATIVE_DNS = "native_dns"
        const val EXTRA_NATIVE_MTU = "native_mtu"
        const val ACTION_STOP = "STOP"
        const val ACTION_STOP_BY_USER = "STOP_BY_USER"
        // Repoints the native relay at a new SOCKS5 target on an already-established tun -
        // see updateTarget(). Only valid while the tun is up; falls back silently otherwise.
        const val ACTION_UPDATE_TARGET = "UPDATE_TARGET"
        const val EXTRA_SOCKS5_ADDR = "socks5_addr"
        const val EXTRA_SOCKS5_USER = "socks5_user"
        const val EXTRA_SOCKS5_PASS = "socks5_pass"
        // false when the target is Xray, which resolves the tun's DNS itself - see buildConfigYaml.
        const val EXTRA_MAP_DNS = "map_dns"

        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        // ULA (RFC 4193), same convention hev-socks5-tunnel's own README example uses.
        private const val TUN_IPV6_ADDRESS = "fc00::1"
        // Not a real public resolver: Chrome auto-upgrades to DoH for known providers, which
        // would bypass the local fake-DNS responder. 198.18.0.1 (RFC 2544 benchmark range)
        // isn't on Chrome's built-in DoH provider list, so it can't trigger that.
        private const val MAPDNS_ADDRESS = "198.18.0.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
    }
}
