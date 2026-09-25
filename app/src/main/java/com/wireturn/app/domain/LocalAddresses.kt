package com.wireturn.app.domain

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * What a local network interface is, guessed from its kernel name - names vary by vendor, so these
 * are the common patterns. Declared in the order they're offered: the likely ways another device
 * reaches this one first, mobile data (almost always behind carrier NAT) last.
 */
enum class LocalInterfaceKind { WIFI, HOTSPOT, USB, BLUETOOTH, ETHERNET, OTHER, MOBILE }

data class LocalAddress(val interfaceName: String, val kind: LocalInterfaceKind, val ip: String)

object LocalAddresses {
    /**
     * The device's IPv4 addresses, one entry per interface address. Plain interface enumeration
     * (getifaddrs) - no root, no permission, and unlike ConnectivityManager it also sees the
     * hotspot/tethering interfaces, the main reason to share a proxy bound to 0.0.0.0. Skips
     * loopback, VPN tunnels (our own included) and 464XLAT's placeholder IPv4.
     */
    fun list(): List<LocalAddress> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                val kind = kindOf(nif.name) ?: return@flatMap emptyList()
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
                    .mapNotNull { addr -> addr.hostAddress?.let { LocalAddress(nif.name, kind, it) } }
            }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.interfaceName }))
    } catch (_: Exception) {
        emptyList()
    }

    private fun kindOf(name: String): LocalInterfaceKind? = when {
        name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("lo") ||
            name.startsWith("dummy") || name.startsWith("v4-") || name.startsWith("clat") -> null
        name == "wlan0" -> LocalInterfaceKind.WIFI
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
            name.startsWith("wlan") -> LocalInterfaceKind.HOTSPOT
        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> LocalInterfaceKind.USB
        name.startsWith("bt") -> LocalInterfaceKind.BLUETOOTH
        name.startsWith("eth") -> LocalInterfaceKind.ETHERNET
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("seth") -> LocalInterfaceKind.MOBILE
        else -> LocalInterfaceKind.OTHER
    }
}
