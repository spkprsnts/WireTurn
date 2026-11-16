package com.wireturn.app.data.kernel

import android.net.Uri
import com.google.gson.annotations.SerializedName

// Mirrors the official qWDTT client's (github.com/SpaceNeuroX/proxy-turn-vk-android) own
// `qwdtt://config?...` share-link fields 1:1, since paid ready-made configs for that client are
// what this kernel exists to accept. Only `-mode socks` is supported (see go_client/main.go) - it
// terminates the VK-TURN-relayed userspace WireGuard tunnel in-process and exposes it as a plain
// local SOCKS5, same shape as OLCRTC/WEBDAV, so socksAddr/auth live on ClientConfig, not here.
// The link's own `port` field (its local UDP listen port) isn't kept either - like Turnable/
// FreeTurn, that's just ClientConfig.listenAddr, a local machine setting, never shared in a link.
data class QwdttConfig(
    @SerializedName("peer") val peer: String = "",
    @SerializedName("hashes") val vkHashes: String = "",
    @SerializedName("password") val password: String = "",
    @SerializedName("workers") val workers: Int = 9,
    @SerializedName("obfs") val obfsMode: String = "audio",
    @SerializedName("turn_tcp") val turnTcp: Boolean = false,
    @SerializedName("go_dns") val goDns: String = "yandex",
    // -notls: direct mode, RTP-obfs AEAD without DTLS on top of TURN. Only works if the server was
    // itself started with -listen-direct - it's a compatibility switch, not a speed/privacy knob,
    // so it must match whatever the peer server actually expects.
    @SerializedName("no_tls") val noTls: Boolean = false,
    // Maps to "-captcha-mode wv": skips the binary's own automatic captcha-solving chain and
    // always requests our WebView (see CoreService.handleQwdttLog's "selected" branch). Trades
    // away the cases auto-solving would have handled silently for a captcha prompt every time.
    @SerializedName("manual_captcha") val manualCaptcha: Boolean = false,
    // The server's raw-IP port (its -listen-raw, a separate listener next to the regular one;
    // the official client's default is 56003). Set = the server does raw IP, so a VPN without
    // Xray runs "-mode rawtun" there and hands the kernel the TUN itself (Kernel.supportsNativeTun);
    // blank = no raw mode, always "-mode socks" as before. Not part of the official link format.
    @SerializedName("raw_port") val rawPort: String = "",
    // The link's `dk` (qWDTT 1.4.5+): the server's diagnostics key, passed as -diag-key so the
    // binary's connection check can also query the server side. Blank = client-side checks only.
    @SerializedName("diag_key") val diagKey: String = ""
) {
    fun isValid(): Boolean = peer.isNotBlank() && vkHashes.isNotBlank() && password.isNotBlank()

    /** Valid raw-IP port, if any - see [rawPort]. */
    val rawPortNumber: Int? get() = rawPort.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** [peer] with its port replaced by [rawPortNumber] - where "-mode rawtun" connects. */
    fun rawPeer(): String? {
        val port = rawPortNumber ?: return null
        val host = peer.substringBeforeLast(':').ifBlank { return null }
        return "$host:$port"
    }

    fun addressLabel(): String = FreeTurnConfig.maskPeer(peer)

    fun sanitize(): QwdttConfig = copy(
        peer = (peer as Any?)?.toString()?.trim()?.take(500) ?: "",
        vkHashes = (vkHashes as Any?)?.toString()?.trim()?.take(2000) ?: "",
        password = (password as Any?)?.toString()?.trim()?.take(256) ?: "",
        workers = workers.coerceIn(1, MAX_WORKERS),
        obfsMode = if (obfsMode == "video") "video" else "audio",
        goDns = ((goDns as Any?)?.toString()?.trim()?.take(100)).let { if (it.isNullOrBlank()) "yandex" else it },
        rawPort = (rawPort as Any?)?.toString()?.filter(Char::isDigit)?.take(5) ?: "",
        diagKey = (diagKey as Any?)?.toString()?.trim()?.take(512) ?: ""
    )

    // Deliberately only the official scheme's own fields (name/peer/hashes/workers/pass) - `port`
    // is read on import (below) but never re-emitted, and obfs/turnTcp/goDns/noTls aren't part of
    // that link format at all - they only travel between WireTurn profiles via the regular
    // kernelConfig JSON (wireturn:// container / ProfileBundle).
    fun toUri(profileName: String? = null): String {
        val builder = Uri.Builder().scheme("qwdtt").authority("config")
            .appendQueryParameter("peer", peer)
            .appendQueryParameter("hashes", vkHashes)
            .appendQueryParameter("workers", workers.toString())
            .appendQueryParameter("pass", password)
        if (!profileName.isNullOrBlank()) builder.appendQueryParameter("name", profileName)
        if (diagKey.isNotBlank()) builder.appendQueryParameter("dk", diagKey)
        return builder.build().toString()
    }

    companion object {
        private const val DEFAULT_DTLS_PORT = 56000

        // The binary's own cap since 1.4.5 (27 per hash with all six hashes); it clamps -n to it.
        const val MAX_WORKERS = 162

        /**
         * [peerRaw] as "host:port": some generators send a bare host, with the port as a separate
         * field or not at all - mirrors the official client's PeerAddress.ensurePort, where a port
         * already in the peer always wins over [fallbackPort], and the standard DTLS port is the last
         * resort.
         */
        fun peerWithPort(peerRaw: String, fallbackPort: Int? = null): String {
            val peer = peerRaw.trim()
            if (peer.isBlank()) return peer
            val hasExplicitPort = peer.substringAfterLast(':', "").let { it.isNotEmpty() && it.all(Char::isDigit) }
            return if (hasExplicitPort) peer
                else "$peer:${fallbackPort?.coerceIn(1, 65535) ?: DEFAULT_DTLS_PORT}"
        }

        // Accepts both "qwdtt://config?..." and the schemeless "qwdtt:config?..." variant some
        // sellers' tools emit (same query-string shape either way).
        fun parse(url: String, current: QwdttConfig = QwdttConfig()): QwdttConfig? {
            val trimmed = url.trim()

            // WDTT Plus's own modern link format (a different WDTT-family fork, unrelated to the
            // qwdtt://config scheme below): wdtt://connect?v=1&host=&dtls=&wg=&local=&password=&hashes=
            // [&name=][&max_workers=]. Must be checked before the legacy positional "wdtt://" scheme
            // right below, which would otherwise misparse this as garbled positional fields.
            // wg_port/local_port are accepted but not used, same reasoning as the legacy scheme.
            if (trimmed.startsWith("wdtt://connect?", ignoreCase = true)) {
                val uri = Uri.parse(trimmed)
                if (uri.getQueryParameter("v") != "1") return null
                val host = uri.getQueryParameter("host")
                val dtlsPort = uri.getQueryParameter("dtls")
                val password = uri.getQueryParameter("password")
                val hashes = uri.getQueryParameter("hashes")
                if (host.isNullOrBlank() || dtlsPort.isNullOrBlank() || password == null || hashes.isNullOrBlank()) return null
                return QwdttConfig(
                    peer = "$host:$dtlsPort",
                    vkHashes = hashes,
                    password = password,
                    workers = uri.getQueryParameter("max_workers")?.toIntOrNull() ?: current.workers,
                    obfsMode = current.obfsMode,
                    turnTcp = current.turnTcp,
                    goDns = current.goDns,
                    noTls = current.noTls,
                    manualCaptcha = current.manualCaptcha,
                    rawPort = current.rawPort,
                    diagKey = current.diagKey
                )
            }

            // Legacy scheme from the original (pre-qWDTT) WDTT client - the official client's own
            // importer still recognizes it too. Positional, not query-string:
            // wdtt://<server_ip>:<dtls_port>:<wg_port>:<local_port>:<password>:<vk_hash>
            // wg_port/local_port are parsed there but never actually used (local_port maps to
            // their own per-profile listen-port field, which we don't keep either - see the
            // ClientConfig.listenAddr note on this class above), so both are simply skipped here.
            if (trimmed.startsWith("wdtt://", ignoreCase = true)) {
                val parts = trimmed.substringAfter("://").split(":")
                if (parts.size < 6) return null
                val ip = parts[0]
                val dtlsPort = parts[1]
                if (ip.isBlank() || dtlsPort.isBlank()) return null
                val password = parts[4]
                val hash = parts.drop(5).joinToString(":")
                if (hash.isBlank()) return null
                return QwdttConfig(
                    peer = "$ip:$dtlsPort",
                    vkHashes = hash,
                    password = password,
                    workers = current.workers,
                    obfsMode = current.obfsMode,
                    turnTcp = current.turnTcp,
                    goDns = current.goDns,
                    noTls = current.noTls,
                    manualCaptcha = current.manualCaptcha,
                    rawPort = current.rawPort,
                    diagKey = current.diagKey
                )
            }

            if (!trimmed.startsWith("qwdtt://", ignoreCase = true) && !trimmed.startsWith("qwdtt:config", ignoreCase = true)) return null
            return try {
                val normalized = if (trimmed.startsWith("qwdtt://", ignoreCase = true)) trimmed
                    else trimmed.replaceFirst("qwdtt:", "qwdtt://", ignoreCase = true)
                val uri = Uri.parse(normalized)
                // Some third-party generators send peer as host-only, with the port as a separate
                // dtls_port/server_port param (or none at all: ildarmaga/wdtt's panel) - see peerWithPort.
                val peer = peerWithPort(
                    uri.getQueryParameter("peer") ?: current.peer,
                    (uri.getQueryParameter("dtls_port") ?: uri.getQueryParameter("server_port"))?.toIntOrNull()
                )
                val hashes = uri.getQueryParameter("hashes") ?: current.vkHashes
                if (peer.isBlank() || hashes.isBlank()) return null
                QwdttConfig(
                    peer = peer,
                    vkHashes = hashes,
                    password = uri.getQueryParameter("pass") ?: uri.getQueryParameter("password") ?: current.password,
                    workers = uri.getQueryParameter("workers")?.toIntOrNull() ?: current.workers,
                    obfsMode = current.obfsMode,
                    turnTcp = current.turnTcp,
                    goDns = current.goDns,
                    noTls = current.noTls,
                    manualCaptcha = current.manualCaptcha,
                    rawPort = current.rawPort,
                    diagKey = uri.getQueryParameter("dk")?.trim() ?: current.diagKey
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
