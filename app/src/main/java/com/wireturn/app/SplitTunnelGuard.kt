package com.wireturn.app

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Keeps apps that split tunneling leaves out of the VPN from using it anyway. Android routes by
 * UID only by default: an excluded app can still bind a socket to tun0 (SO_BINDTODEVICE) and its
 * traffic then lands in hev like anyone else's - out through the server, whose IP it learns
 * (RKNHardering's split tunneling check, issue #52). hev asks [allows] for every new TCP
 * connection and UDP flow (see HevSocks5Tunnel.checkConnection) and drops those whose owner the
 * VPN doesn't cover.
 *
 * Only on Android 10+, where [ConnectivityManager.getConnectionOwnerUid] exists - a VPN app may
 * look up the owners of its own tun's connections there. Older versions are let through as before.
 */
internal object SplitTunnelGuard {
    /** [denied] is always out; with [allowed] set (the allow list) so is every app not on it. */
    data class Policy(val allowed: Set<Int>?, val denied: Set<Int>)

    @Volatile
    private var policy: Policy? = null

    @Volatile
    private var connectivity: ConnectivityManager? = null

    // Matches what configureRoutingAndApps told the VpnService.Builder; null turns checks off
    // (every app is in the VPN, nobody to keep out).
    fun update(context: Context, newPolicy: Policy?) {
        connectivity = context.getSystemService(ConnectivityManager::class.java)
        policy = newPolicy
    }

    fun uidOf(context: Context, pkg: String): Int? = try {
        context.packageManager.getPackageUid(pkg, 0)
    } catch (_: Exception) {
        null
    }

    fun allows(proto: Int, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Boolean {
        val p = policy ?: return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val cm = connectivity ?: return true
        val uid = try {
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(InetAddress.getByAddress(src), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dst), dstPort)
            )
        } catch (_: Exception) {
            Process.INVALID_UID
        }
        return decide(p, uid, Process.myUid(), proto, dstPort)
    }

    internal fun decide(p: Policy, uid: Int, myUid: Int, proto: Int, dstPort: Int): Boolean {
        if (uid == Process.INVALID_UID) {
            // No owner found: closed, except DNS - a lookup the resolver made on an app's behalf.
            return proto == IPPROTO_UDP && dstPort == 53
        }
        if (uid == myUid || uid in p.denied) return false
        // System services (netd answering DNS for an allowed app, etc.) aren't on any list.
        if (uid % PER_USER_RANGE < Process.FIRST_APPLICATION_UID) return true
        return p.allowed?.contains(uid) ?: true
    }

    private const val PER_USER_RANGE = 100000

    // hev passes lwIP's protocol numbers - the IANA ones, as OsConstants has them.
    private const val IPPROTO_UDP = 17
}
