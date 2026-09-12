package com.wireturn.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class FreeTurnConfig(
    @SerializedName("provider") val provider: String = "vk",
    @SerializedName("peer") val peer: String = "",
    @SerializedName("links") val links: String = "",
    @SerializedName("n") val n: Int = 10,
    @SerializedName("transport") val transport: String = "tcp",
    @SerializedName("obf_profile") val obfProfile: String = "none",
    @SerializedName("obf_key") val obfKey: String = "",
    @SerializedName("obf_timing") val obfTiming: String = "0",
    @SerializedName("streams_per_cred") val streamsPerCred: Int = 10,
    @SerializedName("manual_captcha") val manualCaptcha: Boolean = false,
    @SerializedName("platform") val platform: String = "desktop",
    @SerializedName("dns_mode") val dnsMode: String = "auto",
    @SerializedName("dns_servers") val dnsServers: String = "",
    @SerializedName("client_id") val clientId: String = "",
    @SerializedName("sub") val sub: String = "",
    @SerializedName("mode") val mode: String = "udp",
    @SerializedName("kcp_nodelay") val kcpNodelay: Int = 1,
    @SerializedName("kcp_interval") val kcpInterval: Int = 20,
    @SerializedName("kcp_resend") val kcpResend: Int = 2,
    @SerializedName("kcp_nc") val kcpNc: Int = 1,
    @SerializedName("kcp_sndwnd") val kcpSndwnd: Int = 512,
    @SerializedName("kcp_rcvwnd") val kcpRcvwnd: Int = 512,
    @SerializedName("kcp_mtu") val kcpMtu: Int = 1200,
    @SerializedName("kcp_acknodelay") val kcpAcknodelay: Boolean = true
) {
    fun isValid(): Boolean = links.isNotBlank() && (peer.isNotBlank() || sub.isNotBlank())

    fun sanitize(): FreeTurnConfig = copy(
        provider = (provider as Any?)?.toString()?.trim()?.take(32) ?: "vk",
        peer = (peer as Any?)?.toString()?.trim()?.take(500) ?: "",
        links = (links as Any?)?.toString()?.trim()?.take(4096) ?: "",
        obfKey = (obfKey as Any?)?.toString()?.trim()?.take(64) ?: "",
        obfTiming = (obfTiming as Any?)?.toString()?.trim()?.take(20) ?: "0",
        dnsServers = (dnsServers as Any?)?.toString()?.trim()?.take(500) ?: "",
        clientId = (clientId as Any?)?.toString()?.trim()?.take(100) ?: "",
        sub = (sub as Any?)?.toString()?.trim()?.take(1000) ?: ""
    )

    fun toUri(profileName: String? = null): String {
        val json = JsonObject().apply {
            addProperty("v", 1)
            addProperty("provider", "vk")
            if (provider != "vk") addProperty("provider", provider)
            if (peer.isNotBlank()) addProperty("peer", peer)
            if (links.isNotBlank()) {
                addProperty("links", links)
                // Mirrored under the official turn-proxy-android client's own key too (single
                // link - it has no notion of our comma-separated multi-account "links"), so a
                // link shared from here still pre-fills the VK link field over there.
                addProperty("vk", links.substringBefore(','))
            }
            if (sub.isNotBlank()) addProperty("sub", sub)
            if (transport != "tcp") addProperty("transport", transport)
            if (obfProfile != "none") {
                addProperty("obf", obfProfile)
                addProperty("key", obfKey)
                if (obfTiming != "0") addProperty("obft", obfTiming)
            }
            if (n != 10) addProperty("n", n)
            if (streamsPerCred != 10) addProperty("spc", streamsPerCred)
            if (clientId.isNotBlank()) addProperty("cid", clientId)
            if (dnsMode != "auto") addProperty("dns", dnsMode)
            if (dnsServers.isNotBlank()) addProperty("dnss", dnsServers)
            if (manualCaptcha) addProperty("mcap", true)
            if (platform != "desktop") addProperty("plt", platform)
            if (mode != "udp") {
                addProperty("mode", mode)
                val default = FreeTurnConfig()
                val kcpChanged = kcpNodelay != default.kcpNodelay || kcpInterval != default.kcpInterval ||
                    kcpResend != default.kcpResend || kcpNc != default.kcpNc ||
                    kcpSndwnd != default.kcpSndwnd || kcpRcvwnd != default.kcpRcvwnd ||
                    kcpMtu != default.kcpMtu || kcpAcknodelay != default.kcpAcknodelay
                // The server applies this object atomically (internal/config/raw.go's applyURI) -
                // any field missing from it becomes Go's zero value, not our default, so all 8
                // fields must always be sent together, never a partial subset.
                if (kcpChanged) {
                    add("kcp", JsonObject().apply {
                        addProperty("nodelay", kcpNodelay)
                        addProperty("interval", kcpInterval)
                        addProperty("resend", kcpResend)
                        addProperty("nc", kcpNc)
                        addProperty("sndwnd", kcpSndwnd)
                        addProperty("rcvwnd", kcpRcvwnd)
                        addProperty("mtu", kcpMtu)
                        addProperty("acknodelay", kcpAcknodelay)
                    })
                }
            }
            if (!profileName.isNullOrBlank()) addProperty("name", profileName)
        }
        val bytes = json.toString().toByteArray()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return "freeturn://$base64"
    }

    companion object {
        /**
         * Masks the middle of a peer host:port for display (e.g. "123.45.67.89:56000" ->
         * "123.***.**.89:56000") - keeps the first/last IPv4 octet and the port visible so the
         * value still means something at a glance, without exposing the full server address.
         */
        fun maskPeer(peer: String): String {
            if (peer.isBlank()) return peer
            val colonIdx = peer.lastIndexOf(':')
            val host = if (colonIdx > 0) peer.substring(0, colonIdx) else peer
            val port = if (colonIdx > 0) peer.substring(colonIdx) else ""
            val octets = host.split(".")
            val maskedHost = if (octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                octets.mapIndexed { i, o -> if (i == 0 || i == octets.lastIndex) o else "*".repeat(o.length) }
                    .joinToString(".")
            } else if (host.length > 4) {
                host.take(2) + "*".repeat(host.length - 4) + host.takeLast(2)
            } else {
                "*".repeat(host.length)
            }
            return maskedHost + port
        }

        fun parse(url: String, current: FreeTurnConfig = FreeTurnConfig()): FreeTurnConfig? {
            if (!url.startsWith("freeturn://", ignoreCase = true)) return null
            return try {
                val base64 = url.substringAfter("freeturn://")
                val jsonStr = String(android.util.Base64.decode(base64, android.util.Base64.URL_SAFE))
                val json = Gson().fromJson(jsonStr, JsonObject::class.java)

                if (json.get("v")?.asInt != 1) return null
                val kcp = json.getAsJsonObject("kcp")

                FreeTurnConfig(
                    provider = json.get("provider")?.asString ?: current.provider,
                    peer = json.get("peer")?.asString ?: current.peer,
                    // "vk" is the official turn-proxy-android client's key for this same value -
                    // read as a last-resort fallback so links it shares still import here.
                    links = json.get("links")?.asString ?: json.get("link")?.asString
                        ?: json.get("vk")?.asString ?: current.links,
                    sub = json.get("sub")?.asString ?: current.sub,
                    obfProfile = json.get("obf")?.asString ?: current.obfProfile,
                    obfKey = json.get("key")?.asString ?: current.obfKey,
                    obfTiming = json.get("obft")?.asString ?: current.obfTiming,
                    n = json.get("n")?.asInt ?: current.n,
                    transport = json.get("transport")?.asString ?: current.transport,
                    streamsPerCred = json.get("spc")?.asInt ?: current.streamsPerCred,
                    clientId = json.get("cid")?.asString ?: current.clientId,
                    dnsMode = json.get("dns")?.asString ?: current.dnsMode,
                    dnsServers = json.get("dnss")?.asString ?: current.dnsServers,
                    manualCaptcha = json.get("mcap")?.asBoolean ?: current.manualCaptcha,
                    platform = json.get("plt")?.asString ?: current.platform,
                    mode = json.get("mode")?.asString ?: current.mode,
                    kcpNodelay = kcp?.get("nodelay")?.asInt ?: current.kcpNodelay,
                    kcpInterval = kcp?.get("interval")?.asInt ?: current.kcpInterval,
                    kcpResend = kcp?.get("resend")?.asInt ?: current.kcpResend,
                    kcpNc = kcp?.get("nc")?.asInt ?: current.kcpNc,
                    kcpSndwnd = kcp?.get("sndwnd")?.asInt ?: current.kcpSndwnd,
                    kcpRcvwnd = kcp?.get("rcvwnd")?.asInt ?: current.kcpRcvwnd,
                    kcpMtu = kcp?.get("mtu")?.asInt ?: current.kcpMtu,
                    kcpAcknodelay = kcp?.get("acknodelay")?.asBoolean ?: current.kcpAcknodelay
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
