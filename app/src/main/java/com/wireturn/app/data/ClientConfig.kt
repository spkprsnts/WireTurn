package com.wireturn.app.data

import android.content.Context
import android.net.Uri
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.annotations.SerializedName
import com.wireturn.app.R
import com.wireturn.app.ui.ValidatorUtils

class KernelConfigAdapter : JsonDeserializer<KernelConfig>, JsonSerializer<KernelConfig> {
    override fun serialize(src: KernelConfig, typeOfSrc: java.lang.reflect.Type, context: JsonSerializationContext): JsonElement {
        val jsonObject = JsonObject()
        when (src) {
            is KernelConfig.Turnable -> {
                jsonObject.addProperty("type", "turnable")
                jsonObject.add("config", context.serialize(src.config))
            }
            is KernelConfig.Olcrtc -> {
                jsonObject.addProperty("type", "olcrtc")
                jsonObject.add("config", context.serialize(src.config))
            }
            is KernelConfig.Webdav -> {
                jsonObject.addProperty("type", "webdav")
                jsonObject.add("config", context.serialize(src.config))
            }
            is KernelConfig.FreeTurn -> {
                jsonObject.addProperty("type", "freeturn")
                jsonObject.add("config", context.serialize(src.config))
            }
            is KernelConfig.Qwdtt -> {
                jsonObject.addProperty("type", "qwdtt")
                jsonObject.add("config", context.serialize(src.config))
            }
            is KernelConfig.OpenFlux -> {
                jsonObject.addProperty("type", "openflux")
                jsonObject.add("config", context.serialize(src.config))
            }
        }
        return jsonObject
    }

    override fun deserialize(json: JsonElement, typeOfT: java.lang.reflect.Type, context: JsonDeserializationContext): KernelConfig {
        val jsonObject = try { json.asJsonObject } catch(_: Exception) { return KernelConfig.Turnable() }
        val type = jsonObject.get("type")?.asString ?: "turnable"
        val configElement = jsonObject.get("config")
        return when (type) {
            "turnable" -> KernelConfig.Turnable(context.deserialize(configElement, TurnableConfig::class.java) ?: TurnableConfig())
            "olcrtc" -> KernelConfig.Olcrtc(context.deserialize(configElement, OlcrtcConfig::class.java) ?: OlcrtcConfig())
            "webdav" -> KernelConfig.Webdav(context.deserialize(configElement, WebdavConfig::class.java) ?: WebdavConfig())
            "freeturn" -> KernelConfig.FreeTurn(context.deserialize(configElement, FreeTurnConfig::class.java) ?: FreeTurnConfig())
            "qwdtt" -> KernelConfig.Qwdtt(context.deserialize(configElement, QwdttConfig::class.java) ?: QwdttConfig())
            "openflux" -> KernelConfig.OpenFlux(context.deserialize(configElement, OpenFluxConfig::class.java) ?: OpenFluxConfig())
            else -> KernelConfig.Turnable()
        }
    }
}

enum class KernelVariant {
    TURNABLE, OLCRTC, WEBDAV, FREETURN, QWDTT, OPENFLUX;

    /** OLCRTC, WEBDAV, QWDTT and OPENFLUX already speak SOCKS5 themselves - Xray's WireGuard overlay is
     * neither needed nor offered in the UI for them. */
    val isSocks5Core: Boolean get() = this == OLCRTC || this == WEBDAV || this == QWDTT || this == OPENFLUX
}
enum class XrayConfiguration { WIREGUARD, VLESS }

sealed class KernelConfig {
    data class Turnable(val config: TurnableConfig = TurnableConfig()) : KernelConfig()
    data class Olcrtc(val config: OlcrtcConfig = OlcrtcConfig()) : KernelConfig()
    data class Webdav(val config: WebdavConfig = WebdavConfig()) : KernelConfig()
    data class FreeTurn(val config: FreeTurnConfig = FreeTurnConfig()) : KernelConfig()
    data class Qwdtt(val config: QwdttConfig = QwdttConfig()) : KernelConfig()
    data class OpenFlux(val config: OpenFluxConfig = OpenFluxConfig()) : KernelConfig()

    companion object {
        // The link's own scheme already identifies the kernel, so a single quick-input
        // field can dispatch to the right parser instead of one field per kernel type.
        fun parseUri(uri: String): KernelConfig? {
            val trimmed = uri.trim()
            return when {
                trimmed.startsWith("turnable://", ignoreCase = true) ->
                    TurnableConfig.parse(trimmed)?.let { Turnable(it) }
                trimmed.startsWith("olcrtc://", ignoreCase = true) ->
                    OlcrtcConfig.parse(trimmed)?.let { Olcrtc(it) }
                trimmed.startsWith("webdav://", ignoreCase = true) || trimmed.startsWith("webdavs://", ignoreCase = true) ->
                    WebdavConfig.parse(trimmed)?.let { Webdav(it) }
                trimmed.startsWith("freeturn://", ignoreCase = true) ->
                    FreeTurnConfig.parse(trimmed)?.let { FreeTurn(it) }
                trimmed.startsWith("qwdtt://", ignoreCase = true) || trimmed.startsWith("qwdtt:config", ignoreCase = true) ||
                    trimmed.startsWith("wdtt://", ignoreCase = true) ->
                    QwdttConfig.parse(trimmed)?.let { Qwdtt(it) }
                trimmed.startsWith("openflux://", ignoreCase = true) || trimmed.startsWith("openflux:config", ignoreCase = true) ->
                    OpenFluxConfig.parse(trimmed)?.let { OpenFlux(it) }
                else -> null
            }
        }
    }
}

// ClientConfig and Profile both just hold a KernelConfig - kept as extensions here so neither
// has to duplicate this when() themselves.
val KernelConfig.variant: KernelVariant get() = when (this) {
    is KernelConfig.Turnable -> KernelVariant.TURNABLE
    is KernelConfig.Olcrtc -> KernelVariant.OLCRTC
    is KernelConfig.Webdav -> KernelVariant.WEBDAV
    is KernelConfig.FreeTurn -> KernelVariant.FREETURN
    is KernelConfig.Qwdtt -> KernelVariant.QWDTT
    is KernelConfig.OpenFlux -> KernelVariant.OPENFLUX
}

fun KernelConfig.description(context: Context): String = when (this) {
    is KernelConfig.Turnable -> {
        val route = config.routes.find { it.routeId == config.selectedRouteId }
        val transport = route?.socket?.uppercase()?.ifBlank { null }
        context.getString(R.string.kernel_turnable) + transport?.let { " $it" }.orEmpty()
    }
    is KernelConfig.Olcrtc -> context.getString(R.string.kernel_olcrtc) + " " + config.providerDisplayName
    is KernelConfig.Webdav -> context.getString(R.string.kernel_webdav) + " " + WebdavConfig.formatHost(config.webdav) +
        if (config.backends.isNotEmpty()) " +${config.backends.size}" else ""
    is KernelConfig.FreeTurn -> context.getString(R.string.kernel_freeturn) + " " + config.addressLabel()
    is KernelConfig.Qwdtt -> context.getString(R.string.kernel_qwdtt) + " " + config.addressLabel()
    is KernelConfig.OpenFlux -> context.getString(R.string.kernel_openflux) + " " + config.platformDisplayName
}

data class ClientConfig(
    val listenAddr: String = DEFAULT_LISTEN_ADDR,
    val socksAddr: String = DEFAULT_SOCKS_ADDR,
    val isSocksAuthEnabled: Boolean = true,
    val socksUser: String = "",
    val socksPass: String = "",
    // Shared between OLCRTC and WEBDAV (the two SOCKS5-native kernels, see socksAddr above) -
    // resolves that kernel's own entry-point hostname (olcRTC's signaling provider, or the
    // WebDAV backend) before/outside the tunnel, useful when the OS resolver is
    // unreliable/filtered/blocked. Has no effect on traffic routed *through* the tunnel once
    // it's up - that's always resolved on the other side, never on this client. Optional for
    // both cores - blank means "use the OS resolver" (see NewResolver("") in olcrtc's
    // internal/protect/protect.go, and webdav-tunnel's own -dns docs); DEFAULT_DNS is only ever
    // shown as a placeholder, never silently substituted in.
    val dns: String = "",
    val goDnsGo: Boolean = false,
    val useCustomCerts: Boolean = true,
    val kernelConfig: KernelConfig = KernelConfig.Turnable(),
    // Universal quick-input field: the scheme in the link (turnable://, olcrtc://, webdav(s)://,
    // freeturn://) already says which kernel it is, so one field replaces the four below.
    @SerializedName("uri") val uri: String = "",
    // --- LEGACY: kept only so older exported configs still parse. Prefer `uri` above. ---
    @SerializedName("turnableUrl") val turnableUrl: String = "",
    @SerializedName("olcrtcUrl") val olcrtcUrl: String = "",
    @SerializedName("webdavUrl") val webdavUrl: String = "",
    @SerializedName("freeturnUrl") val freeturnUrl: String = ""
    // --- END LEGACY ---
) {
    val kernelVariant: KernelVariant get() = kernelConfig.variant

    fun fillDefaults(): ClientConfig {
        val cleanedUser = ValidatorUtils.cleanProxyString(socksUser)
        val cleanedPass = socksPass.trim()

        val validListen = if (ValidatorUtils.isValidHostPort(listenAddr)) listenAddr else DEFAULT_LISTEN_ADDR
        val validSocks = if (ValidatorUtils.isValidHostPort(socksAddr)) socksAddr else DEFAULT_SOCKS_ADDR

        var currentKc = kernelConfig
        if (uri.isNotBlank()) {
            KernelConfig.parseUri(uri)?.let { currentKc = it }
        } else if (turnableUrl.isNotBlank()) {
            TurnableConfig.parse(turnableUrl)?.let { currentKc = KernelConfig.Turnable(it) }
        } else if (olcrtcUrl.isNotBlank()) {
            OlcrtcConfig.parse(olcrtcUrl)?.let { currentKc = KernelConfig.Olcrtc(it) }
        } else if (webdavUrl.isNotBlank()) {
            WebdavConfig.parse(webdavUrl)?.let { currentKc = KernelConfig.Webdav(it) }
        } else if (freeturnUrl.isNotBlank()) {
            FreeTurnConfig.parse(freeturnUrl)?.let { currentKc = KernelConfig.FreeTurn(it) }
        }

        var current = this.copy(
            listenAddr = validListen,
            socksAddr = validSocks,
            socksUser = cleanedUser,
            socksPass = cleanedPass,
            goDnsGo = goDnsGo,
            kernelConfig = currentKc,
            uri = "",
            turnableUrl = "",
            olcrtcUrl = "",
            webdavUrl = ""
        )

        if (current.isSocksAuthEnabled && (current.socksUser.isBlank() || current.socksPass.isBlank())) {
            val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9')
            current = current.copy(
                socksUser = current.socksUser.ifBlank { (1..8).map { allowed.random() }.joinToString("") },
                socksPass = current.socksPass.ifBlank { (1..12).map { allowed.random() }.joinToString("") }
            )
        }
        return current.copy(
            kernelConfig = when (val k = current.kernelConfig) {
                is KernelConfig.Turnable -> KernelConfig.Turnable(k.config.sanitize())
                is KernelConfig.Olcrtc -> KernelConfig.Olcrtc(k.config.fillDefaults())
                is KernelConfig.Webdav -> KernelConfig.Webdav(k.config.fillDefaults())
                is KernelConfig.FreeTurn -> KernelConfig.FreeTurn(k.config.sanitize())
                is KernelConfig.Qwdtt -> KernelConfig.Qwdtt(k.config.sanitize())
                is KernelConfig.OpenFlux -> KernelConfig.OpenFlux(k.config.fillDefaults())
            }
        )
    }

    val connectableAddress: String get() = listenAddr.replace("0.0.0.0:", "127.0.0.1:")

    fun getValidationErrorResId(): Int? = when (val k = kernelConfig) {
        is KernelConfig.Turnable -> if (!k.config.isValid()) R.string.error_settings_empty else null
        is KernelConfig.Olcrtc -> socksNativeValidationError(k.config.isValid())
        is KernelConfig.Webdav -> if (!k.config.isValid()) R.string.error_settings_empty else null
        is KernelConfig.FreeTurn -> if (!k.config.isValid()) R.string.error_settings_empty else null
        is KernelConfig.Qwdtt -> socksNativeValidationError(k.config.isValid())
        is KernelConfig.OpenFlux -> socksNativeValidationError(k.config.isValid())
    }

    // Shared by every SOCKS5-native kernel (OLCRTC, qWDTT): besides its own config being filled in,
    // a public (non-loopback) SOCKS5 listen address must have auth enabled.
    private fun socksNativeValidationError(configIsValid: Boolean): Int? = when {
        !configIsValid -> R.string.error_settings_empty
        !isSocksAuthEnabled && !ValidatorUtils.isLoopbackHostPort(socksAddr) -> R.string.error_socks_public_requires_auth
        else -> null
    }

    val isValid: Boolean get() = getValidationErrorResId() == null

    fun getKernelDescription(context: Context): String = kernelConfig.description(context)

    companion object {
        const val DEFAULT_LISTEN_ADDR = "127.0.0.1:9000"
        const val DEFAULT_SOCKS_ADDR = "127.0.0.1:2081"
        // Yandex DNS - stays reachable under allow-list-only restrictions where 1.1.1.1/8.8.8.8
        // typically aren't.
        const val DEFAULT_DNS = "77.88.8.8:53"
    }
}

data class XraySettings(
    val socksBindAddress: String = DEFAULT_SOCKS_BIND_ADDRESS,
    val httpBindAddress: String = "",
    val isProxyAuthEnabled: Boolean = true,
    val proxyUser: String = "",
    val proxyPass: String = "",
    // General, profile-independent xray-client settings - see the "Routing (applies to
    // every mode)" section of external/vless-client/README.md.
    val dns: String = DEFAULT_DNS,
    val routeDirect: String = "",
    val routeBlock: String = "",
    val fakeDns: Boolean = false,
    // Which geoip.dat/geosite.dat variant is currently installed - see GeoAssetsManager.
    val geoVariant: String = "runetfreedom"
) {
    fun fillDefaults(): XraySettings {
        val cleanedUser = ValidatorUtils.cleanProxyString(proxyUser)
        val cleanedPass = proxyPass.trim()

        val validSocks = if (ValidatorUtils.isValidHostPort(socksBindAddress)) socksBindAddress else DEFAULT_SOCKS_BIND_ADDRESS
        val validHttp = if (httpBindAddress.isBlank() || ValidatorUtils.isValidHostPort(httpBindAddress)) {
            httpBindAddress
        } else {
            ""
        }

        var current = this.copy(
            socksBindAddress = validSocks,
            httpBindAddress = validHttp,
            proxyUser = cleanedUser,
            proxyPass = cleanedPass,
            dns = dns.trim().ifBlank { DEFAULT_DNS },
            routeDirect = (routeDirect as Any?)?.toString()?.trim() ?: "",
            routeBlock = (routeBlock as Any?)?.toString()?.trim() ?: ""
        )

        if (current.isProxyAuthEnabled && (current.proxyUser.isBlank() || current.proxyPass.isBlank())) {
            val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9')
            current = current.copy(
                proxyUser = current.proxyUser.ifBlank { (1..8).map { allowed.random() }.joinToString("") },
                proxyPass = current.proxyPass.ifBlank { (1..12).map { allowed.random() }.joinToString("") }
            )
        }
        return current
    }

    val connectableAddress: String get() = socksBindAddress.replace("0.0.0.0:", "127.0.0.1:")

    companion object {
        const val DEFAULT_SOCKS_BIND_ADDRESS = "127.0.0.1:1080"
        const val DEFAULT_HTTP_BIND_ADDRESS = "127.0.0.1:8080"
        const val DEFAULT_DNS = "8.8.8.8,1.1.1.1"
    }
}

data class XrayConfig(
    val enabled: Boolean = false,
    val protocol: XrayConfiguration = XrayConfiguration.WIREGUARD
)

data class VlessConfig(
    @SerializedName("vlessLink") val vlessLink: String = "",
    @SerializedName("isDualRoute") val isDualRoute: Boolean = false,
    @SerializedName("directAddress") val directAddress: String = "",
    @SerializedName("hcInterval") val hcInterval: String = "30",
    @SerializedName("mux") val mux: String = "0",
    // VLESS/Trojan-over-SOCKS5: dials the link's own connection through the socks5-native
    // kernel's local socks5 (dialerProxy) instead of using it as a plain upstream. Only
    // meaningful for socks5-native kernels (olcrtc/webdav) - see XrayService.isSocks5Core.
    @SerializedName("isSocks5Chain") val isSocks5Chain: Boolean = false
) {
    fun isValid(): Boolean = ValidatorUtils.isValidVlessLink(vlessLink)
    fun sanitize(): VlessConfig = copy(
        vlessLink = (vlessLink as Any?)?.toString()?.take(4096) ?: "",
        directAddress = (directAddress as Any?)?.toString()?.take(500) ?: "",
        hcInterval = (hcInterval as Any?)?.toString()?.take(20) ?: "30",
        mux = (mux as Any?)?.toString()?.take(20) ?: "0"
    )

    fun fillDefaults(): VlessConfig {
        var current = this.copy(
            hcInterval = hcInterval.ifBlank { "30" },
            mux = mux.ifBlank { "0" }
        )
        if (current.isDualRoute && current.directAddress.isBlank()) {
            ValidatorUtils.parseVlessAddress(current.vlessLink)?.let {
                current = current.copy(directAddress = it)
            }
        }
        return current
    }
}

data class WgConfig(
    @SerializedName("privateKey") val privateKey: String = "",
    @SerializedName("address") val address: String = "",
    @SerializedName("mtu") val mtu: String = "1280",
    @SerializedName("publicKey") val publicKey: String = "",
    @SerializedName("endpoint") val endpoint: String = "127.0.0.1:9000",
    @SerializedName("persistentKeepalive") val persistentKeepalive: String = "25"
) {
    fun isValid(): Boolean = privateKey.isNotBlank() && address.isNotBlank() && publicKey.isNotBlank()
    fun fillDefaults(): WgConfig = copy(
        mtu = (mtu as Any?)?.toString()?.ifBlank { "1280" } ?: "1280",
        persistentKeepalive = (persistentKeepalive as Any?)?.toString()?.ifBlank { "25" } ?: "25"
    )

    fun toWgString(): String =
        "[Interface]\nPrivateKey = $privateKey\nAddress = $address\nMTU = $mtu\n\n[Peer]\nPublicKey = $publicKey\nEndpoint = $endpoint\nPersistentKeepalive = $persistentKeepalive"

    companion object {
        fun parse(text: String): WgConfig {
            val trimmed = text.trim()
            // Panels like 3x-ui export WireGuard as a single-line URI instead of a wg-quick
            // config: wireguard://<urlencoded-private-key>@host:port?address=..&mtu=..&publickey=..#name
            if (trimmed.startsWith("wireguard://", ignoreCase = true)) {
                parseUri(trimmed)?.let { return it }
            }
            var pk = ""
            var ad = ""
            var m = ""
            var pub = ""
            var ep = ""
            var pkp = ""
            var sec = ""
            text.lineSequence().forEach { l ->
                val t = l.trim()
                if (t.startsWith("[")) {
                    sec = t.lowercase()
                } else if (t.contains("=")) {
                    val k = t.substringBefore("=").trim().lowercase()
                    val v = t.substringAfter("=").trim()
                    if (sec == "[interface]") {
                        when (k) {
                            "privatekey" -> pk = v
                            "address" -> ad = v
                            "mtu" -> m = v
                        }
                    } else if (sec == "[peer]") {
                        when (k) {
                            "publickey" -> pub = v
                            "endpoint" -> ep = v
                            "persistentkeepalive" -> pkp = v
                        }
                    }
                }
            }
            return WgConfig(pk, ad, m, pub, ep, pkp)
        }

        private fun parseUri(uri: String): WgConfig? {
            return try {
                val u = Uri.parse(uri)
                val privateKey = u.encodedUserInfo?.let { Uri.decode(it) } ?: return null
                val host = u.host ?: return null
                val port = u.port.takeIf { it != -1 } ?: return null
                WgConfig(
                    privateKey = privateKey,
                    address = u.getQueryParameter("address") ?: "",
                    mtu = u.getQueryParameter("mtu") ?: "1280",
                    publicKey = u.getQueryParameter("publickey") ?: u.getQueryParameter("public_key") ?: "",
                    endpoint = "$host:$port",
                    persistentKeepalive = u.getQueryParameter("keepalive")
                        ?: u.getQueryParameter("persistentkeepalive") ?: "25"
                )
            } catch (_: Exception) { null }
        }
    }
}

internal data class KernelSnapshot(
    @SerializedName("variant") val variant: String = KernelVariant.TURNABLE.name,
    @SerializedName("turnable") val turnable: TurnableConfig? = null,
    @SerializedName("olcrtc") val olcrtc: OlcrtcConfig? = null,
    @SerializedName("webdav") val webdav: WebdavConfig? = null,
    @SerializedName("freeturn") val freeturn: FreeTurnConfig? = null,
    @SerializedName("qwdtt") val qwdtt: QwdttConfig? = null,
    @SerializedName("openflux") val openflux: OpenFluxConfig? = null
)

internal data class OldClientConfig(
    @SerializedName("kernelVariant") val kernelVariant: KernelVariant = KernelVariant.TURNABLE,
    @SerializedName("turnableConfig") val turnableConfig: TurnableConfig = TurnableConfig(),
    @SerializedName("olcrtcConfig") val olcrtcConfig: OlcrtcConfig = OlcrtcConfig(),
    @SerializedName("webdavConfig") val webdavConfig: WebdavConfig = WebdavConfig()
)

data class Profile(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("kernelConfig") val kernelConfig: KernelConfig = KernelConfig.Turnable(),
    @SerializedName("xrayProtocol", alternate = ["protocol", "xrayConfiguration"]) val xrayProtocol: XrayConfiguration = XrayConfiguration.WIREGUARD,
    @SerializedName("xrayEnabled", alternate = ["enabled"]) val xrayEnabled: Boolean = false,
    @SerializedName("wgConfig") val wgConfig: WgConfig = WgConfig(),
    @SerializedName("vlessConfig") val vlessConfig: VlessConfig = VlessConfig(),
    @SerializedName("subscriptionId") val subscriptionId: String? = null,
    // The subscription server's own id for this profile entry (its "id" field in the bundle), kept
    // around so the next refresh can re-match this local profile by stable server identity instead
    // of by display name - two entries can share a name, but the server's id should stay unique.
    @SerializedName("subscriptionSourceId") val subscriptionSourceId: String? = null
) {
    // --- STABLE INPUT FIELDS (Used for profile generation and deep linking) ---
    // Universal quick-input field: the scheme in the link (turnable://, olcrtc://, webdav(s)://,
    // freeturn://) already identifies the kernel, so this one field replaces the four below.
    @SerializedName("uri") private val uri: String? = null
    // Legacy per-kernel fields, kept only so older exported/generated profiles still parse.
    // Prefer `uri` above for new integrations.
    @SerializedName("turnableUrl") private val turnableUrl: String? = null
    @SerializedName("olcrtcUrl") private val olcrtcUrl: String? = null
    @SerializedName("webdavUrl") private val webdavUrl: String? = null
    @SerializedName("freeturnUrl") private val freeturnUrl: String? = null
    // --- END STABLE INPUT FIELDS ---

    // --- TEMPORARY MIGRATION FIELDS (Will be removed in future versions) ---
    @SerializedName("kernelVariant") private val mKernelVariant: KernelVariant? = null
    @SerializedName("turnableConfig") private val mTurnableConfig: TurnableConfig? = null
    @SerializedName("olcrtcConfig") private val mOlcrtcConfig: OlcrtcConfig? = null
    @SerializedName("webdavConfig") private val mWebdavConfig: WebdavConfig? = null
    @SerializedName("clientConfig") private val oldClientConfig: JsonElement? = null
    @SerializedName("xraySettings") private val oldXraySettings: JsonElement? = null
    @SerializedName("xrayConfig") private val oldXrayConfig: JsonElement? = null
    // --- END TEMPORARY MIGRATION FIELDS ---

    val kernelVariant: KernelVariant get() = kernelConfig.variant

    val turnableConfig: TurnableConfig get() = (kernelConfig as? KernelConfig.Turnable)?.config ?: TurnableConfig()
    val olcrtcConfig: OlcrtcConfig get() = (kernelConfig as? KernelConfig.Olcrtc)?.config ?: OlcrtcConfig()
    val webdavConfig: WebdavConfig get() = (kernelConfig as? KernelConfig.Webdav)?.config ?: WebdavConfig()
    val freeturnConfig: FreeTurnConfig get() = (kernelConfig as? KernelConfig.FreeTurn)?.config ?: FreeTurnConfig()
    val qwdttConfig: QwdttConfig get() = (kernelConfig as? KernelConfig.Qwdtt)?.config ?: QwdttConfig()
    val openFluxConfig: OpenFluxConfig get() = (kernelConfig as? KernelConfig.OpenFlux)?.config ?: OpenFluxConfig()

    fun isEmpty(): Boolean = when (val k = kernelConfig) {
        is KernelConfig.Turnable -> !k.config.isValid()
        is KernelConfig.Olcrtc -> !k.config.isValid()
        is KernelConfig.Webdav -> !k.config.isValid()
        is KernelConfig.FreeTurn -> !k.config.isValid()
        is KernelConfig.Qwdtt -> !k.config.isValid()
        is KernelConfig.OpenFlux -> !k.config.isValid()
    } && !wgConfig.isValid() && !vlessConfig.isValid()

    fun sanitize(defaultName: String = "Profile"): Profile {
        // GSON can bypass Kotlin's null-safety, so we must handle nulls manually
        val safeId = (id as String?) ?: java.util.UUID.randomUUID().toString()
        val safeName = (name as String?) ?: defaultName

        var currentKc = (kernelConfig as KernelConfig?) ?: KernelConfig.Turnable()
        var prot = (xrayProtocol as XrayConfiguration?) ?: XrayConfiguration.WIREGUARD
        var en = (xrayEnabled as Boolean?) ?: false

        val gson = GsonBuilder().registerTypeAdapterFactory(SafeEnumTypeAdapterFactory()).create()

        // 1. INPUT: Profile generation from URLs (STABLE)
        if (uri?.isNotBlank() == true) {
            KernelConfig.parseUri(uri)?.let { currentKc = it }
        } else if (turnableUrl?.isNotBlank() == true) {
            TurnableConfig.parse(turnableUrl)?.let { currentKc = KernelConfig.Turnable(it) }
        } else if (olcrtcUrl?.isNotBlank() == true) {
            OlcrtcConfig.parse(olcrtcUrl)?.let { currentKc = KernelConfig.Olcrtc(it) }
        } else if (webdavUrl?.isNotBlank() == true) {
            WebdavConfig.parse(webdavUrl)?.let { currentKc = KernelConfig.Webdav(it) }
        } else if (freeturnUrl?.isNotBlank() == true) {
            FreeTurnConfig.parse(freeturnUrl)?.let { currentKc = KernelConfig.FreeTurn(it) }
        }
        // --- END INPUT ---

        // 2. MIGRATION: Old top-level fields (TEMPORARY)
        if (mKernelVariant != null && (mTurnableConfig != null || mOlcrtcConfig != null || mWebdavConfig != null)) {
             currentKc = when(mKernelVariant) {
                 KernelVariant.TURNABLE -> KernelConfig.Turnable(mTurnableConfig ?: TurnableConfig())
                 KernelVariant.OLCRTC -> KernelConfig.Olcrtc(mOlcrtcConfig ?: OlcrtcConfig())
                 KernelVariant.WEBDAV -> KernelConfig.Webdav(mWebdavConfig ?: WebdavConfig())
                 KernelVariant.FREETURN -> currentKc // Not migrated from top-level
                 KernelVariant.QWDTT -> currentKc // Not migrated from top-level
                 KernelVariant.OPENFLUX -> currentKc // Not migrated from top-level
             }
        }
        // --- END MIGRATION 2 ---

        // 3. MIGRATION: Old nested ClientConfig format (TEMPORARY)
        if (oldClientConfig != null && oldClientConfig.isJsonObject &&
            (currentKc !is KernelConfig.Turnable || currentKc.config.routes.isEmpty())) {
            try {
                val obj = oldClientConfig.asJsonObject
                val variantStr = obj.get("kernelVariant")?.asString
                val variant = try { KernelVariant.valueOf(variantStr ?: "") } catch (_: Exception) { KernelVariant.TURNABLE }

                currentKc = when (variant) {
                    KernelVariant.TURNABLE -> {
                        val tcElement = obj.get("turnableConfig")
                        KernelConfig.Turnable(gson.fromJson(tcElement, TurnableConfig::class.java) ?: TurnableConfig())
                    }
                    KernelVariant.OLCRTC -> {
                        val ocElement = obj.get("olcrtcConfig")
                        KernelConfig.Olcrtc(gson.fromJson(ocElement, OlcrtcConfig::class.java) ?: OlcrtcConfig())
                    }
                    KernelVariant.WEBDAV -> {
                        val wdcElement = obj.get("webdavConfig")
                        KernelConfig.Webdav(gson.fromJson(wdcElement, WebdavConfig::class.java) ?: WebdavConfig())
                    }
                    KernelVariant.FREETURN -> {
                        val ftcElement = obj.get("freeturnConfig")
                        KernelConfig.FreeTurn(gson.fromJson(ftcElement, FreeTurnConfig::class.java) ?: FreeTurnConfig())
                    }
                    KernelVariant.QWDTT -> {
                        val qcElement = obj.get("qwdttConfig")
                        KernelConfig.Qwdtt(gson.fromJson(qcElement, QwdttConfig::class.java) ?: QwdttConfig())
                    }
                    KernelVariant.OPENFLUX -> {
                        val ofcElement = obj.get("openFluxConfig")
                        KernelConfig.OpenFlux(gson.fromJson(ofcElement, OpenFluxConfig::class.java) ?: OpenFluxConfig())
                    }
                }
            } catch (_: Exception) { }
        }
        // --- END MIGRATION 3 ---

        // 4. MIGRATION: Old nested Xray format - xraySettings (TEMPORARY)
        if (oldXraySettings != null && oldXraySettings.isJsonObject) {
            val obj = oldXraySettings.asJsonObject
            if (!en) {
                en = try { obj.get("xrayEnabled")?.asBoolean ?: obj.get("enabled")?.asBoolean ?: false } catch (_: Exception) { false }
            }
        }
        // --- END MIGRATION 4 ---

        // 5. MIGRATION: Old nested Xray format - xrayConfig (TEMPORARY)
        if (oldXrayConfig != null && oldXrayConfig.isJsonObject && (prot == XrayConfiguration.WIREGUARD)) {
            val obj = oldXrayConfig.asJsonObject
            val oldType = try { obj.get("xrayConfiguration")?.asString ?: obj.get("protocol")?.asString } catch (_: Exception) { null }
            if (oldType != null) try { prot = XrayConfiguration.valueOf(oldType) } catch(_: Exception) {}
        }
        // --- END MIGRATION 5 ---

        // Deep safety for WG and VLESS
        val wgc = (wgConfig as Any? as? WgConfig ?: WgConfig()).fillDefaults()
        val vc = (vlessConfig as Any? as? VlessConfig ?: VlessConfig()).sanitize().fillDefaults()

        val finalName = safeName.takeIf { it.isNotBlank() }?.take(100) ?: defaultName

        val sanitizedKc = when (currentKc) {
            is KernelConfig.Turnable -> KernelConfig.Turnable(currentKc.config.sanitize())
            is KernelConfig.Olcrtc -> {
                val sc = currentKc.config.sanitize()
                KernelConfig.Olcrtc(if (sc.mimo.isBlank()) sc.copy(mimo = finalName) else sc)
            }
            is KernelConfig.Webdav -> KernelConfig.Webdav(currentKc.config.fillDefaults())
            is KernelConfig.FreeTurn -> KernelConfig.FreeTurn(currentKc.config.sanitize())
            is KernelConfig.Qwdtt -> KernelConfig.Qwdtt(currentKc.config.sanitize())
            is KernelConfig.OpenFlux -> KernelConfig.OpenFlux(currentKc.config.sanitize())
        }

        return copy(
            id = safeId,
            name = finalName,
            kernelConfig = sanitizedKc,
            xrayProtocol = prot,
            xrayEnabled = en,
            vlessConfig = vc,
            wgConfig = wgc
        )
    }

    fun getKernelDescription(context: Context): String = kernelConfig.description(context)
}
