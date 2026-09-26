package com.wireturn.app.domain

import java.math.BigInteger
import java.net.InetAddress

/**
 * The tun's routes with LAN bypass on: the whole address space minus local ranges, so traffic
 * to them stays on the underlying network instead of reaching the tunnel server (printers,
 * casting, the router's own page, mDNS/SSDP discovery). Built as a set of routes covering the
 * rest rather than VpnService.Builder.excludeRoute, which only exists from Android 13.
 */
object LanRoutes {
    // Private, link-local and multicast/reserved (incl. broadcast). 100.64.0.0/10 (hev's MapDNS
    // fake addresses) and 198.18.0.0/15 (the VPN's DNS server) are deliberately not here - both
    // must stay inside the tun.
    private val LAN_V4 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/3")
    private val LAN_V6 = listOf("fc00::/7", "fe80::/10", "ff00::/8")

    /** (address, prefix length) routes covering all IPv4 except [LAN_V4]. */
    val ipv4: List<Pair<String, Int>> by lazy { complement(LAN_V4, 32) }

    /** (address, prefix length) routes covering all IPv6 except [LAN_V6]. */
    val ipv6: List<Pair<String, Int>> by lazy { complement(LAN_V6, 128) }

    private fun complement(excluded: List<String>, bits: Int): List<Pair<String, Int>> {
        val ex = excluded.map { cidr ->
            BigInteger(1, InetAddress.getByName(cidr.substringBefore('/')).address) to cidr.substringAfter('/').toInt()
        }
        fun sameNetwork(a: BigInteger, b: BigInteger, len: Int) = a.shiftRight(bits - len) == b.shiftRight(bits - len)

        val routes = mutableListOf<Pair<String, Int>>()
        // Splits a block in halves until each half is either fully excluded (dropped) or
        // overlaps no excluded range at all (kept whole).
        fun walk(base: BigInteger, len: Int) {
            if (ex.any { (eBase, eLen) -> eLen <= len && sameNetwork(base, eBase, eLen) }) return
            if (ex.none { (eBase, eLen) -> eLen > len && sameNetwork(eBase, base, len) }) {
                routes += format(base, bits) to len
                return
            }
            walk(base, len + 1)
            walk(base.setBit(bits - len - 1), len + 1)
        }
        walk(BigInteger.ZERO, 0)
        return routes
    }

    private fun format(value: BigInteger, bits: Int): String {
        val raw = value.toByteArray()
        val bytes = ByteArray(bits / 8)
        // toByteArray() is minimal big-endian, possibly with a leading sign byte - right-align it.
        val n = minOf(raw.size, bytes.size)
        System.arraycopy(raw, raw.size - n, bytes, bytes.size - n, n)
        return InetAddress.getByAddress(bytes).hostAddress!!
    }
}
