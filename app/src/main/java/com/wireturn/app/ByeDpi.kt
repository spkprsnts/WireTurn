package com.wireturn.app

import java.net.InetAddress
import java.net.ServerSocket

/**
 * ByeDPI (external/byedpi, `ciadpi`, built as libbyedpi.so): a local SOCKS5 proxy that goes
 * straight out from the device but splits/fakes packets so DPI can't throttle them. XrayService
 * runs it next to Xray when XraySettings.dpiBypass is on, and vless-client sends the
 * dpiBypassRoute entries through it (-dpi-socks/-route-dpi) - falling back to the tunnel by
 * itself whenever its check finds the direct path unusable (whitelist-only mobile internet).
 *
 * It needs no VpnService.protect(): it runs under this app's own UID, which the VPN leaves out
 * (see HevVpnService), the same as Xray's own direct route.
 */
object ByeDpi {
    // From ByeByeDPI's own strategy list (its proxy test, assets/proxytest_strategies.list): the
    // request cut into alternating disorder/split pieces up to 35 bytes past the SNI, a TLS record
    // split at the SNI, and the fake's TCP MD5 option set (-S). On an SSL error (-As) the same
    // cuts without the record split. Picked on a real network - ByeByeDPI's tester finds others.
    const val DEFAULT_ARGS =
        "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 " +
                "-As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1"

    // Blank: -route-dpi is left out and vless-client routes its own built-in YouTube and Discord list,
    // which unlike geosite:youtube needs no geo files downloaded.
    const val DEFAULT_ROUTE = ""

    // Options this app sets itself (where it listens), or that don't fit a supervised child
    // process: daemonizing, a pidfile, transparent mode, help/version output, and --cache-file,
    // whose loader reads a 64-bit value into a 32-bit time_t on armeabi-v7a/x86.
    private val OWN_OPTIONS_WITH_VALUE =
        setOf("-i", "--ip", "-p", "--port", "-w", "--pidfile", "-y", "--cache-file")
    private val OWN_FLAGS =
        setOf("-D", "--daemon", "-E", "--transparent", "-h", "--help", "-v", "--version")

    /** The user's strategy as ciadpi arguments, without the options above. */
    fun userArgs(raw: String): List<String> {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        var skipValue = false
        for (token in tokens) {
            if (skipValue) {
                skipValue = false
                continue
            }
            val name = token.substringBefore('=')
            when {
                token in OWN_FLAGS -> Unit
                token in OWN_OPTIONS_WITH_VALUE -> skipValue = true
                // --ip=..., --port=...
                name in OWN_OPTIONS_WITH_VALUE && token.contains('=') -> Unit
                // -p1080, -i127.0.0.1: getopt's short option with the value attached.
                OWN_OPTIONS_WITH_VALUE.any { it.length == 2 && token.startsWith(it) && token.length > 2 } -> Unit
                else -> result += token
            }
        }
        return result
    }

    /** A free loopback port for ciadpi to listen on. */
    fun freeLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    /** A fixed port the user may set: below 1024 an app can't bind at all. */
    fun isValidPort(value: String): Boolean = value.toIntOrNull()?.let { it in 1024..65535 } == true

    /** The port for this start: the fixed one if set, otherwise a free one. */
    fun listenPort(fixed: String): Int =
        fixed.trim().takeIf(::isValidPort)?.toInt() ?: freeLoopbackPort()
}
