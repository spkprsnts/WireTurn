package com.wireturn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock

/**
 * The physical networks (NOT_VPN, with internet) the device has while a session runs, and which
 * of them traffic goes out through. CoreService decides what a change means for the tunnel - see
 * [Listener].
 */
class NetworkMonitor(private val context: Context, private val listener: Listener) {

    interface Listener {
        /**
         * Traffic now goes out through another network: Wi-Fi <-> mobile, the other SIM, a
         * reconnect after a drop. [previousLabel] is the one before - [primaryLabel] the new one.
         * Not called for the first pick, nor while the networks already up settle after [register].
         */
        fun onPrimaryNetworkChanged(previousLabel: String)

        /** Any physical network's capabilities changed - e.g. one became usable again. */
        fun onNetworkCapabilitiesChanged(capabilities: NetworkCapabilities)
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    // Every usable physical network with its latest capabilities - "wait for network" checks it's
    // non-empty, "restart on network change" picks the primary one out of it.
    private val physicalNetworks =
        java.util.concurrent.ConcurrentHashMap<Network, NetworkCapabilities>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // The physical network traffic goes out through, as picked by onPhysicalNetworksChanged(); -1 = none.
    @Volatile
    private var primaryNetworkHandle: Long = -1

    // Until then the networks already up keep arriving one by one after registering - the pick
    // settles silently instead of reporting each as a change.
    @Volatile
    private var primaryNetworkSettledAt = 0L

    /** The primary network's type (and for mobile, SIM and operator), blank before the first pick. */
    @Volatile
    var primaryLabel: String = ""
        private set

    /** Whether any usable physical network is up. */
    val isAvailable: Boolean get() = physicalNetworks.isNotEmpty()

    fun register() {
        unregister()
        primaryNetworkHandle = -1
        primaryLabel = ""
        primaryNetworkSettledAt = SystemClock.elapsedRealtime() + NETWORK_SETTLE_MS
        // Every usable physical network (NOT_VPN). The network change is worked out from these
        // rather than from registerDefaultNetworkCallback: with our own VPN up, the system doesn't
        // report a switch underneath it (seen: Wi-Fi -> mobile arrived only once the VPN was down).
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                physicalNetworks[network] = capabilities
                onPhysicalNetworksChanged()
            }

            override fun onLost(network: Network) {
                physicalNetworks.remove(network)
                onPhysicalNetworksChanged()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                // Validation comes in here (a Wi-Fi only becomes primary once it has internet).
                if (physicalNetworks.containsKey(network)) {
                    physicalNetworks[network] = networkCapabilities
                    onPhysicalNetworksChanged()
                }
                listener.onNetworkCapabilitiesChanged(networkCapabilities)
            }
        }
        networkCallback = cb

        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, cb)
    }

    fun unregister() {
        physicalNetworks.clear()
        networkCallback?.let { cb ->
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    /**
     * Picks the network traffic goes out through the way the system ranks them - a validated one
     * first, then Ethernet > Wi-Fi > mobile, the current one kept on a tie (two mobile networks
     * while data moves to the other SIM) - and treats a new pick as a network change: Wi-Fi <->
     * mobile either way, the other SIM, a reconnect after a drop.
     */
    @Synchronized
    private fun onPhysicalNetworksChanged() {
        val current = physicalNetworks.keys.firstOrNull { it.networkHandle == primaryNetworkHandle }
        val best = physicalNetworks.entries.maxWithOrNull(
            compareBy<Map.Entry<Network, NetworkCapabilities>> { networkRank(it.value) }
                .thenBy { if (it.key == current) 1 else 0 }
        )
        // Nothing left: no switch yet - the next network that comes up is compared to the last one.
        if (best == null || best.key.networkHandle == primaryNetworkHandle) return

        val previousLabel = primaryLabel
        val isFirst = primaryNetworkHandle == -1L
        primaryNetworkHandle = best.key.networkHandle
        primaryLabel = networkLabel(best.value)
        AppLogsState.addLog(
            context.getString(R.string.log_core_default_network, primaryLabel),
            LogLevel.DEBUG
        )
        if (isFirst || SystemClock.elapsedRealtime() < primaryNetworkSettledAt) return

        listener.onPrimaryNetworkChanged(previousLabel)
    }

    /**
     * DNS servers of the physical network traffic goes out through - the primary one, or right
     * after start (before the callback has reported any) the best-ranked non-VPN network the
     * system has. Loopback/link-local dropped, IPv4 first: the operator's IPv6 resolver may have
     * no route. Read when a kernel's command is built, so a run after a network change gets the
     * new network's.
     */
    fun dnsServers(): List<String> {
        val network = physicalNetworks.keys.firstOrNull { it.networkHandle == primaryNetworkHandle }
            ?: runCatching {
                @Suppress("DEPRECATION")
                cm.allNetworks.mapNotNull { n ->
                    val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) return@mapNotNull null
                    n to caps
                }.maxByOrNull { networkRank(it.second) }?.first
            }.getOrNull()
            ?: return emptyList()
        val servers =
            runCatching { cm.getLinkProperties(network)?.dnsServers }.getOrNull().orEmpty()
        return servers
            .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
            .sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
    }

    private fun networkRank(capabilities: NetworkCapabilities): Int {
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        }
        val validated =
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0
        return validated + transport
    }

    // The network's type - and for cellular, which SIM and operator: since Android 11 the network
    // names its subscription itself, and neither the slot nor the operator name needs a permission.
    private fun networkLabel(capabilities: NetworkCapabilities?): String {
        val type = context.getString(
            when {
                capabilities == null -> R.string.local_address_other
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.local_address_wifi
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.local_address_mobile
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.string.local_address_ethernet
                else -> R.string.local_address_other
            }
        )
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) return type
        val subId =
            (capabilities.networkSpecifier as? android.net.TelephonyNetworkSpecifier)?.subscriptionId
                ?: return type
        val details = listOfNotNull(
            android.telephony.SubscriptionManager.getSlotIndex(subId).takeIf { it >= 0 }
                ?.let { context.getString(R.string.network_sim_slot, it + 1) },
            try {
                context.getSystemService(android.telephony.TelephonyManager::class.java)
                    ?.createForSubscriptionId(subId)?.networkOperatorName
            } catch (_: Exception) {
                null
            }?.takeIf { it.isNotBlank() }
        )
        return if (details.isEmpty()) type else "$type (${details.joinToString(", ")})"
    }

    private companion object {
        // After registering the network callback, the networks already up (and their validation)
        // arrive within this - the primary-network pick settles over it without reporting changes.
        const val NETWORK_SETTLE_MS = 1_500L
    }
}
