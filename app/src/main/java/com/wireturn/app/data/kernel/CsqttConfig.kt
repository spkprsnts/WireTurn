package com.wireturn.app.data.kernel

import android.net.Uri
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// CSQTT (external/csqtt, upstream https://github.com/amurcanov/csqtt): raw IP over VK TURN, with
// no SOCKS5 or WireGuard of its own - its client only ever takes a TUN (-tun-uds). That's the
// VPN's own TUN when VPN mode runs without Xray (Kernel.supportsNativeTun), and socks2tun's
// packet socket otherwise, which exposes it as a local SOCKS5 (socksAddr/auth on ClientConfig).
// Fields mirror what the official app's csqtt:// link and its connection settings carry.
data class CsqttConfig(
    // "host:port" of the server's peer (UDP) port - the link's host + peer fields.
    @SerializedName("peer") val peer: String = "",
    @SerializedName("password") val password: String = "",
    // VK call hashes, comma separated - up to MAX_HASHES are used, see the client's -vk.
    @SerializedName("hashes") val vkHashes: String = "",
    // Total workers (-n): whole groups of WORKERS_PER_GROUP - normalized the official app's way
    // in [normalizedWorkers].
    @SerializedName("workers") val workers: Int = DEFAULT_WORKERS,
    @SerializedName("obfs") val obfsMode: String = "video",
    // TURN over TLS on TCP ("-turn-transport tcp_tls") instead of plain UDP.
    @SerializedName("turn_tcp") val turnTcp: Boolean = false,
    // "-captcha-mode wv": straight to our WebView, skipping the client's own solving chain.
    @SerializedName("manual_captcha") val manualCaptcha: Boolean = false
) {
    fun isValid(): Boolean = peer.isNotBlank() && password.isNotBlank() && hashList().isNotEmpty()

    // Whole call links are fine too - the part after /call/join/ is what counts (as in the
    // official app's stripVkUrl).
    fun hashList(): List<String> = vkHashes.split(Regex("[,\\s]+"))
        .map(::stripVkCallUrl).filter(String::isNotEmpty).distinct().take(MAX_HASHES)

    /**
     * [workers] as the client gets it (2.5.0's WorkerCountPolicy.normalize, the same as the
     * client's own normalize_worker_count): whole groups up to MAX_WORKERS, MIN_WORKERS at the
     * least. No cap per hash any more - the official app passes its count whatever the hashes.
     */
    fun normalizedWorkers(): Int {
        if (workers <= MIN_WORKERS) return MIN_WORKERS
        val rounded = workers.coerceIn(MIN_WORKERS, MAX_WORKERS) / WORKERS_PER_GROUP * WORKERS_PER_GROUP
        return rounded.coerceAtLeast(MIN_WORKERS)
    }

    fun addressLabel(): String = FreeTurnConfig.maskPeer(peer)

    fun sanitize(): CsqttConfig = copy(
        peer = (peer as Any?)?.toString()?.trim()?.take(500) ?: "",
        password = (password as Any?)?.toString()?.trim()?.take(256) ?: "",
        vkHashes = (vkHashes as Any?)?.toString()?.trim()?.take(2000) ?: "",
        workers = workers.coerceIn(MIN_WORKERS, MAX_WORKERS),
        obfsMode = if (obfsMode == "audio") "audio" else "video"
    )

    // The official app's v2 link, in the encrypted form it reads since 2.5.0 (see encryptLink) -
    // obfs/transport/captcha aren't part of it, they only travel between WireTurn profiles via the
    // regular kernelConfig JSON. Its hashes are joined by a literal '+' (each one escaped on its
    // own) - the official app, its server's panel and swgPanel all split on that, so an encoded
    // comma would read as one long hash there. The name rides inside too: the official app skips
    // a parameter it doesn't know, and the payload is all the link has.
    fun toUri(profileName: String? = null): String {
        val host = peer.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = peer.substringAfterLast(':', "")
        val query = buildString {
            append("connect?v=2&host=").append(Uri.encode(host, ":"))
            append("&peer=").append(port)
            append("&password=").append(Uri.encode(password))
            val hashes = hashList()
            if (hashes.isNotEmpty()) append("&hashes=").append(hashes.joinToString("+") { Uri.encode(it) })
            if (!profileName.isNullOrBlank()) append("&name=").append(Uri.encode(profileName))
        }
        return "csqtt://" + encryptLink(query)
    }

    companion object {
        // rust-client/worker.rs and main.rs since 2.5.0 (9 and 126 before, with 3 groups per hash).
        const val WORKERS_PER_GROUP = 10
        const val MIN_WORKERS = 5
        const val MAX_HASHES = 6
        const val MAX_WORKERS = 100
        // The official app's own default (18 per profile, normalized the same way).
        const val DEFAULT_WORKERS = 18
        const val DEFAULT_PEER_PORT = 46000

        // The official app's encrypted "csqtt://<base64url>" (2.5.0+, see decryptLink), its plain
        // "csqtt://connect?v=2&host=&peer=<port>&password=[&hashes=|&token=]" from before that,
        // or the older "csqtt://<password>@<host>:<port>". None needs hashes - a link without
        // them keeps the ones already in the profile. A token link (2.5.0's VK access token, for
        // its Auto JS mode fed from the official app's own WebView) brings no hashes either.
        fun parse(url: String, current: CsqttConfig = CsqttConfig()): CsqttConfig? {
            val trimmed = decryptLink(url) ?: url.trim()
            if (!trimmed.startsWith("csqtt://", ignoreCase = true)) return null
            return try {
                val uri = Uri.parse(trimmed)
                if (uri.host.equals("connect", ignoreCase = true)) {
                    if (uri.getQueryParameter("v") != "2") return null
                    val host = uri.getQueryParameter("host")?.trim()
                    val port = uri.getQueryParameter("peer")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
                    val password = uri.getQueryParameter("password")?.trim()
                    if (host.isNullOrBlank() || port == null || password.isNullOrBlank()) return null
                    // '+'-joined (see toUri) - getQueryParameter already turns those into spaces.
                    val hashes = uri.getQueryParameter("hashes")
                        ?.let { CsqttConfig(vkHashes = it).hashList().joinToString(", ") }
                        ?.takeIf { it.isNotBlank() }
                    current.copy(peer = "${bracketed(host)}:$port", password = password, vkHashes = hashes ?: current.vkHashes)
                } else {
                    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
                    val port = uri.port.takeIf { it in 1..65535 } ?: return null
                    val password = uri.userInfo?.takeIf { it.isNotBlank() } ?: return null
                    current.copy(peer = "${bracketed(host)}:$port", password = password)
                }
            } catch (_: Exception) {
                null
            }
        }

        // 2.5.0's link encryption (app/.../ui/utils/UiUtils.kt): AES-256-GCM over the plain v2
        // query, keyed with SHA-256 of a seed baked into the app - it hides the password from a
        // glance, not from anyone with the app - nonce = HMAC-SHA256(key, query)[0..12], the link
        // being base64url(nonce + ciphertext + tag), unpadded.
        private const val LINK_KEY_SEED = "csqtt-chiper-key"
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private val linkKey: SecretKeySpec by lazy {
            SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(LINK_KEY_SEED.toByteArray(Charsets.UTF_8)), "AES")
        }

        private fun encryptLink(query: String): String {
            val plain = query.toByteArray(Charsets.UTF_8)
            val nonce = Mac.getInstance("HmacSHA256").run {
                init(linkKey)
                doFinal(plain).copyOf(GCM_NONCE_BYTES)
            }
            val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, linkKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
                doFinal(plain)
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + sealed)
        }

        /**
         * The plain "csqtt://connect?..." inside an encrypted link, or null for any other text -
         * a plain link included. Its payload holds the query with or without "connect?", the way
         * the official app's parseCsqttLink takes it.
         */
        fun decryptLink(url: String): String? {
            val trimmed = url.trim()
            if (!trimmed.startsWith("csqtt://", ignoreCase = true)) return null
            val payload = trimmed.substring("csqtt://".length).trim()
            if (payload.isEmpty() || payload.any { it == '?' || it == '@' || it == ':' || it == '&' }) return null
            return try {
                val clean = payload.filterNot(Char::isWhitespace)
                val bytes = runCatching { Base64.getUrlDecoder().decode(clean) }
                    .getOrElse { Base64.getDecoder().decode(clean) }
                if (bytes.size < GCM_NONCE_BYTES + GCM_TAG_BITS / 8) return null
                val query = Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, linkKey, GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_NONCE_BYTES))
                    String(doFinal(bytes, GCM_NONCE_BYTES, bytes.size - GCM_NONCE_BYTES), Charsets.UTF_8)
                }
                when {
                    query.startsWith("csqtt://connect?", ignoreCase = true) -> query
                    query.startsWith("connect?", ignoreCase = true) -> "csqtt://$query"
                    query.startsWith("csqtt://", ignoreCase = true) -> "csqtt://connect?" + query.substring("csqtt://".length)
                    else -> "csqtt://connect?$query"
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun bracketed(host: String): String =
            if (host.contains(':') && !host.startsWith('[')) "[$host]" else host

        private val VK_CALL_PREFIX = Regex("""^(?:https?://)?(?:m\.)?vk\.(?:com|ru)/call/join/""", RegexOption.IGNORE_CASE)

        fun stripVkCallUrl(input: String): String =
            input.trim().replace(VK_CALL_PREFIX, "")
                .substringBefore('?').substringBefore('#').trimEnd('/')
    }
}
