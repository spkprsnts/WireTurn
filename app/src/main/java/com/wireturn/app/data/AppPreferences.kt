package com.wireturn.app.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.wireturn.app.ui.ValidatorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringReader

private val Context.internalDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

class SafeEnumTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val rawType = type.rawType
        if (!rawType.isEnum) return null
        val constants = rawType.enumConstants as Array<T>
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) = delegate.write(out, value)
            override fun read(reader: JsonReader): T? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }
                val name = reader.nextString()
                return try {
                    delegate.read(JsonReader(StringReader("\"$name\"")))
                } catch (_: Exception) {
                    constants.firstOrNull()
                }
            }
        }.nullSafe()
    }
}

enum class KernelVariant { TURNABLE, OLCRTC }
enum class XrayConfiguration { WIREGUARD, VLESS }
enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class TurnableRoute(
    @SerializedName("route_id") val routeId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("socket") val socket: String = "",
    @SerializedName("transport") val transport: String? = null
) {
    fun sanitize(): TurnableRoute = copy(
        routeId = (routeId as Any?)?.toString()?.take(100) ?: "",
        name = (name as Any?)?.toString()?.take(100) ?: "",
        socket = (socket as Any?)?.toString()?.take(100) ?: "udp",
        transport = (transport as Any?)?.toString()?.take(100)
    )
}

data class TurnableConfig(
    @SerializedName("user_uuid") val userUuid: String? = null,
    @SerializedName("username") val username: String = "",
    @SerializedName("platform_id") val platformId: String = "vk.com",
    @SerializedName("call_id") val callId: String = "",
    @SerializedName("type") val type: String = "relay",
    @SerializedName("encryption") val encryption: String? = null,
    @SerializedName("pub_key") val pubKey: String? = null,
    @SerializedName("peers") val peers: Int = 1,
    @SerializedName("forceturn") val forceTurn: Boolean = false,
    @SerializedName("gateway") val gateway: String = "",
    @SerializedName("proto") val proto: String? = null,
    @SerializedName("routes") val routes: List<TurnableRoute> = emptyList(),
    @SerializedName("selected_route_id") val selectedRouteId: String = ""
) {
    fun sanitize(): TurnableConfig = copy(
        userUuid = (userUuid as Any?)?.toString()?.take(200),
        username = (username as Any?)?.toString()?.take(200) ?: "",
        platformId = (platformId as Any?)?.toString()?.take(200) ?: "vk.com",
        callId = (callId as Any?)?.toString()?.take(200) ?: "",
        type = (type as Any?)?.toString()?.take(100) ?: "relay",
        encryption = (encryption as Any?)?.toString()?.take(100),
        pubKey = (pubKey as Any?)?.toString()?.take(4096),
        gateway = (gateway as Any?)?.toString()?.take(500) ?: "",
        proto = (proto as Any?)?.toString()?.take(100),
        selectedRouteId = (selectedRouteId as Any?)?.toString()?.take(100) ?: "",
        routes = (routes as List<TurnableRoute>?)?.map { it.sanitize() } ?: emptyList()
    )

    fun isValid(): Boolean = username.isNotBlank() &&
            platformId.isNotBlank() &&
            callId.isNotBlank() &&
            gateway.isNotBlank() &&
            routes.isNotEmpty()

    fun toUrl(onlySelected: Boolean = false): String {
        val targetRoutes = if (onlySelected && selectedRouteId.isNotBlank()) {
            routes.filter { it.routeId == selectedRouteId }
        } else {
            routes
        }
        val builder = Uri.Builder().scheme("turnable")
        val userInfo = if (!userUuid.isNullOrBlank()) {
            "${Uri.encode(userUuid)}:${Uri.encode(callId)}"
        } else {
            Uri.encode(callId)
        }
        builder.encodedAuthority("$userInfo@$platformId")
        targetRoutes.forEach { builder.appendPath(it.routeId) }
        builder.appendQueryParameter("username", username)
        builder.appendQueryParameter("gateway", gateway)
        builder.appendQueryParameter("type", type)
        encryption?.let { builder.appendQueryParameter("encryption", it) }
        pubKey?.let { builder.appendQueryParameter("pub_key", it) }
        proto?.let { builder.appendQueryParameter("proto", it) }
        if (peers != 1) builder.appendQueryParameter("peers", peers.toString())
        if (forceTurn) builder.appendQueryParameter("forceturn", "true")
        if (targetRoutes.size == 1) {
            val r = targetRoutes[0]
            if (r.socket != "udp") builder.appendQueryParameter("socket", r.socket)
            r.transport?.let { builder.appendQueryParameter("transport", it) }
        } else {
            targetRoutes.forEachIndexed { index, r ->
                val idx = index + 1
                if (r.socket != "udp") builder.appendQueryParameter("socket[$idx]", r.socket)
                r.transport?.let { builder.appendQueryParameter("transport[$idx]", it) }
            }
        }
        if (selectedRouteId.isNotBlank()) builder.appendQueryParameter("selected_route_id", selectedRouteId)
        if (targetRoutes.isNotEmpty()) builder.encodedFragment(targetRoutes.joinToString(",") { Uri.encode(it.name) })
        return builder.build().toString()
    }

    companion object {
        fun parse(url: String, base: TurnableConfig = TurnableConfig()): TurnableConfig? {
            if (!url.startsWith("turnable://", ignoreCase = true)) return null
            return try {
                val uri = Uri.parse(url)
                val userParts = (uri.encodedUserInfo ?: "").split(":").map { Uri.decode(it) }
                val userUuid = if (userParts.size > 1) userParts[0] else null
                val callId = if (userParts.size > 1) userParts[1] else userParts.getOrNull(0) ?: ""
                val pathParts = (uri.encodedPath ?: "").split("/").filter { it.isNotBlank() }.map { Uri.decode(it) }
                val routeNames = (uri.encodedFragment ?: "").split(",").map { Uri.decode(it) }
                val routes = pathParts.mapIndexed { index, routeId ->
                    TurnableRoute(
                        routeId = routeId,
                        name = routeNames.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() } ?: routeId,
                        socket = uri.getQueryParameter("socket[${index + 1}]")
                            ?: (if (index == 0) uri.getQueryParameter("socket") else null) ?: "udp",
                        transport = uri.getQueryParameter("transport[${index + 1}]")
                            ?: (if (index == 0) uri.getQueryParameter("transport") else null)
                    )
                }
                base.copy(
                    userUuid = userUuid ?: base.userUuid,
                    callId = callId.ifBlank { base.callId },
                    platformId = uri.host ?: base.platformId,
                    username = uri.getQueryParameter("username") ?: base.username,
                    type = uri.getQueryParameter("type") ?: "relay",
                    encryption = uri.getQueryParameter("encryption") ?: base.encryption,
                    pubKey = uri.getQueryParameter("pub_key") ?: base.pubKey,
                    peers = uri.getQueryParameter("peers")?.toIntOrNull() ?: base.peers,
                    forceTurn = uri.getQueryParameter("forceturn")?.toBoolean() ?: base.forceTurn,
                    gateway = uri.getQueryParameter("gateway") ?: base.gateway,
                    proto = uri.getQueryParameter("proto") ?: base.proto,
                    routes = routes.ifEmpty { base.routes },
                    selectedRouteId = uri.getQueryParameter("selected_route_id")
                        ?: (if (routes.isNotEmpty()) routes.firstOrNull()?.routeId ?: "" else base.selectedRouteId)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class OlcrtcConfig(
    @SerializedName("carrier") val carrier: String = "wbstream",
    @SerializedName("transport") val transport: String = "datachannel",
    @SerializedName("id") val id: String = "",
    @SerializedName("client_id") val clientId: String = "wireturn",
    @SerializedName("key") val key: String = "",
    @SerializedName("dns") val dns: String = "1.1.1.1:53",
    @SerializedName("mimo") val mimo: String = "",
    @SerializedName("vp8_fps") val vp8Fps: Int = 60,
    @SerializedName("vp8_batch") val vp8Batch: Int = 64,
    @SerializedName("sei_fps") val seiFps: Int = 60,
    @SerializedName("sei_batch") val seiBatch: Int = 64,
    @SerializedName("sei_frag") val seiFrag: Int = 900,
    @SerializedName("sei_ack_ms") val seiAckMs: Int = 2000,
    @SerializedName("video_codec") val videoCodec: String = "qrcode",
    @SerializedName("video_w") val videoW: Int = 1080,
    @SerializedName("video_h") val videoH: Int = 1080,
    @SerializedName("video_fps") val videoFps: Int = 60,
    @SerializedName("video_bitrate") val videoBitrate: String = "5000k",
    @SerializedName("video_hw") val videoHw: String = "none",
    @SerializedName("video_qr_recovery") val videoQrRecovery: String = "low",
    @SerializedName("video_qr_size") val videoQrSize: Int = 0,
    @SerializedName("video_tile_module") val videoTileModule: Int = 4,
    @SerializedName("video_tile_rs") val videoTileRs: Int = 20
) {
    fun sanitize(): OlcrtcConfig = copy(
        carrier = (carrier as Any?)?.toString()?.take(100) ?: "wbstream",
        transport = (transport as Any?)?.toString()?.take(100) ?: "datachannel",
        id = (id as Any?)?.toString()?.take(200) ?: "",
        clientId = (clientId as Any?)?.toString()?.take(200) ?: "wireturn",
        key = (key as Any?)?.toString()?.take(1000) ?: "",
        dns = (dns as Any?)?.toString()?.take(200) ?: "1.1.1.1:53",
        mimo = (mimo as Any?)?.toString()?.take(500) ?: ""
    )

    fun isValid(): Boolean = id.isNotBlank() && clientId.isNotBlank() && key.isNotBlank() && dns.isNotBlank()
    fun fillDefaults(): OlcrtcConfig = copy(videoW = if (videoW <= 0) 1080 else videoW, videoH = if (videoH <= 0) 1080 else videoH)

    fun toUri(): String {
        val sb = StringBuilder("olcrtc://").append(carrier).append("?").append(transport)
        val params = mutableListOf<String>()
        when (transport) {
            "vp8channel" -> {
                if (vp8Fps != 60) params.add("vp8-fps=$vp8Fps")
                if (vp8Batch != 64) params.add("vp8-batch=$vp8Batch")
            }
            "seichannel" -> {
                if (seiFps != 60) params.add("fps=$seiFps")
                if (seiBatch != 64) params.add("batch=$seiBatch")
                if (seiFrag != 900) params.add("frag=$seiFrag")
                if (seiAckMs != 2000) params.add("ack-ms=$seiAckMs")
            }
            "videochannel" -> {
                if (videoW != 1080) params.add("video-w=$videoW")
                if (videoH != 1080) params.add("video-h=$videoH")
                if (videoFps != 60) params.add("video-fps=$videoFps")
                if (videoBitrate != "5000k") params.add("video-bitrate=$videoBitrate")
                if (videoHw != "none") params.add("video-hw=$videoHw")
                if (videoCodec != "qrcode") params.add("video-codec=$videoCodec")
                if (videoCodec == "qrcode") {
                    if (videoQrSize != 0) params.add("video-qr-size=$videoQrSize")
                    if (videoQrRecovery != "low") params.add("video-qr-recovery=$videoQrRecovery")
                } else if (videoCodec == "tile") {
                    if (videoTileModule != 4) params.add("video-tile-module=$videoTileModule")
                    if (videoTileRs != 20) params.add("video-tile-rs=$videoTileRs")
                }
            }
        }
        if (params.isNotEmpty()) sb.append("<").append(params.joinToString("&")).append(">")
        sb.append("@").append(id)
        if (key.isNotBlank()) sb.append("#").append(key)
        if (clientId.isNotBlank()) sb.append("%").append(clientId)
        if (mimo.isNotBlank()) sb.append("$").append(mimo)
        return sb.toString()
    }

    companion object {
        fun parse(url: String, base: OlcrtcConfig = OlcrtcConfig()): OlcrtcConfig? {
            if (!url.startsWith("olcrtc://", ignoreCase = true)) return null
            return try {
                val carrier = url.substringAfter("olcrtc://").substringBefore("?")
                val transportPart = url.substringAfter("?").substringBefore("@")
                val transport = transportPart.substringBefore("<")
                val payload = if (transportPart.contains("<")) transportPart.substringAfter("<").substringBefore(">") else ""
                val rest = url.substringAfter("@")
                val id = rest.substringBefore("#").substringBefore("%").substringBefore("$")
                val key = if (rest.contains("#")) rest.substringAfter("#").substringBefore("%").substringBefore("$") else ""
                val clientId = if (rest.contains("%")) rest.substringAfter("%").substringBefore("$") else ""
                val mimo = if (rest.contains("$")) rest.substringAfter("$") else ""
                var cfg = base.copy(
                    carrier = carrier,
                    transport = transport,
                    id = id,
                    key = key,
                    clientId = clientId.ifBlank { "wireturn" },
                    mimo = mimo
                )
                if (payload.isNotBlank()) {
                    val p = payload.split("&").associate { it.substringBefore("=") to it.substringAfter("=", "") }
                    cfg = when (transport) {
                        "vp8channel" -> cfg.copy(
                            vp8Fps = p["vp8-fps"]?.toIntOrNull() ?: cfg.vp8Fps,
                            vp8Batch = p["vp8-batch"]?.toIntOrNull() ?: cfg.vp8Batch
                        )
                        "seichannel" -> cfg.copy(
                            seiFps = p["fps"]?.toIntOrNull() ?: cfg.seiFps,
                            seiBatch = p["batch"]?.toIntOrNull() ?: cfg.seiBatch,
                            seiFrag = p["frag"]?.toIntOrNull() ?: cfg.seiFrag,
                            seiAckMs = p["ack-ms"]?.toIntOrNull() ?: cfg.seiAckMs
                        )
                        "videochannel" -> cfg.copy(
                            videoW = p["video-w"]?.toIntOrNull() ?: cfg.videoW,
                            videoH = p["video-h"]?.toIntOrNull() ?: cfg.videoH,
                            videoFps = p["video-fps"]?.toIntOrNull() ?: cfg.videoFps,
                            videoBitrate = p["video-bitrate"] ?: cfg.videoBitrate,
                            videoHw = p["video-hw"] ?: cfg.videoHw,
                            videoCodec = p["video-codec"] ?: cfg.videoCodec,
                            videoQrSize = p["video-qr-size"]?.toIntOrNull() ?: cfg.videoQrSize,
                            videoQrRecovery = p["video-qr-recovery"] ?: cfg.videoQrRecovery,
                            videoTileModule = p["video-tile-module"]?.toIntOrNull() ?: cfg.videoTileModule,
                            videoTileRs = p["video-tile-rs"]?.toIntOrNull() ?: cfg.videoTileRs
                        )
                        else -> cfg
                    }
                }
                cfg
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class ClientConfig(
    val listenAddr: String = DEFAULT_LISTEN_ADDR,
    val socksAddr: String = DEFAULT_SOCKS_ADDR,
    val isSocksAuthEnabled: Boolean = true,
    val socksUser: String = "",
    val socksPass: String = "",
    val isRawMode: Boolean = false,
    val rawCommand: String = "",
    val turnableConfig: TurnableConfig = TurnableConfig(),
    val olcrtcConfig: OlcrtcConfig = OlcrtcConfig(),
    val kernelVariant: KernelVariant = KernelVariant.TURNABLE
) {
    fun fillDefaults(): ClientConfig {
        var current = this
        if (current.isSocksAuthEnabled && (socksUser.isBlank() || socksPass.isBlank())) {
            val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9')
            current = current.copy(
                socksUser = socksUser.ifBlank { (1..8).map { allowed.random() }.joinToString("") },
                socksPass = socksPass.ifBlank { (1..12).map { allowed.random() }.joinToString("") }
            )
        }
        return current.copy(
            turnableConfig = turnableConfig.sanitize(),
            olcrtcConfig = olcrtcConfig.fillDefaults()
        )
    }

    val connectableAddress: String get() = listenAddr.replace("0.0.0.0:", "127.0.0.1:")
    fun getValidationErrorResId(): Int? = if (isRawMode && rawCommand.isBlank()) {
        com.wireturn.app.R.string.error_raw_empty
    } else if (!isRawMode && kernelVariant == KernelVariant.TURNABLE && !turnableConfig.isValid()) {
        com.wireturn.app.R.string.error_settings_empty
    } else if (!isRawMode && kernelVariant == KernelVariant.OLCRTC && !olcrtcConfig.isValid()) {
        com.wireturn.app.R.string.error_settings_empty
    } else {
        null
    }

    val isValid: Boolean get() = getValidationErrorResId() == null
    fun getKernelDescription(context: Context): String = when (kernelVariant) {
        KernelVariant.TURNABLE -> context.getString(com.wireturn.app.R.string.kernel_turnable) + " " + turnableConfig.selectedRouteId
        KernelVariant.OLCRTC -> context.getString(com.wireturn.app.R.string.kernel_olcrtc) + " " + olcrtcConfig.carrier
    }

    companion object {
        const val DEFAULT_LISTEN_ADDR = "127.0.0.1:9000"
        const val DEFAULT_SOCKS_ADDR = "127.0.0.1:9001"
    }
}

data class XrayConfig(
    val socksBindAddress: String = DEFAULT_SOCKS_BIND_ADDRESS,
    val httpBindAddress: String = "",
    val xrayConfiguration: XrayConfiguration = XrayConfiguration.WIREGUARD,
    val isProxyAuthEnabled: Boolean = true,
    val proxyUser: String = "",
    val proxyPass: String = ""
) {
    fun fillDefaults(): XrayConfig {
        var current = this
        if (current.isProxyAuthEnabled && (proxyUser.isBlank() || proxyPass.isBlank())) {
            val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9')
            current = current.copy(
                proxyUser = proxyUser.ifBlank { (1..8).map { allowed.random() }.joinToString("") },
                proxyPass = proxyPass.ifBlank { (1..12).map { allowed.random() }.joinToString("") }
            )
        }
        return current
    }

    val connectableAddress: String get() = socksBindAddress.replace("0.0.0.0:", "127.0.0.1:")

    companion object {
        const val DEFAULT_SOCKS_BIND_ADDRESS = "127.0.0.1:1080"
    }
}

data class XraySettings(val xrayEnabled: Boolean = false)

data class VlessConfig(
    @SerializedName("vlessLink") val vlessLink: String = "",
    @SerializedName("vlessUseLocalAddress") val vlessUseLocalAddress: Boolean = true,
    @SerializedName("isDualRoute") val isDualRoute: Boolean = false,
    @SerializedName("directAddress") val directAddress: String = "",
    @SerializedName("hcInterval") val hcInterval: String = "30"
) {
    fun isValid(): Boolean = ValidatorUtils.isValidVlessLink(vlessLink)
    fun sanitize(): VlessConfig = copy(
        vlessLink = (vlessLink as Any?)?.toString()?.take(4096) ?: "",
        directAddress = (directAddress as Any?)?.toString()?.take(500) ?: "",
        hcInterval = (hcInterval as Any?)?.toString()?.take(20) ?: "30"
    )

    fun fillDefaults(): VlessConfig = copy(hcInterval = hcInterval.ifBlank { "30" })
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
    }
}

data class Profile(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("kernelVariant") val kernelVariant: KernelVariant = KernelVariant.TURNABLE,
    @SerializedName("isRawMode") val isRawMode: Boolean = false,
    @SerializedName("rawCommand") val rawCommand: String = "",
    @SerializedName("turnableConfig") val turnableConfig: TurnableConfig = TurnableConfig(),
    @SerializedName("olcrtcConfig") val olcrtcConfig: OlcrtcConfig = OlcrtcConfig(),
    @SerializedName("xrayConfiguration") val xrayConfiguration: XrayConfiguration = XrayConfiguration.WIREGUARD,
    @SerializedName("xrayEnabled") val xrayEnabled: Boolean = false,
    @SerializedName("wgConfig") val wgConfig: WgConfig = WgConfig(),
    @SerializedName("vlessConfig") val vlessConfig: VlessConfig = VlessConfig(),
    @SerializedName("clientConfig") private val oldClientConfig: ClientConfig? = null,
    @SerializedName("xraySettings") private val oldXraySettings: XraySettings? = null,
    @SerializedName("xrayConfig") private val oldXrayConfig: XrayConfig? = null
) {
    fun isEmpty(): Boolean = (rawCommand as Any?)?.toString().isNullOrBlank() &&
            !turnableConfig.isValid() &&
            !olcrtcConfig.isValid() &&
            !wgConfig.isValid() &&
            !vlessConfig.isValid()

    fun sanitize(): Profile {
        @Suppress("SENSELESS_COMPARISON")
        var kv = if ((kernelVariant as Any?) == null) KernelVariant.TURNABLE else kernelVariant
        var raw = isRawMode ?: false
        var cmd = (rawCommand as Any?)?.toString() ?: ""
        var tc = (turnableConfig ?: TurnableConfig())
        var oc = (olcrtcConfig ?: OlcrtcConfig())

        @Suppress("SENSELESS_COMPARISON")
        var xc = if ((xrayConfiguration as Any?) == null) XrayConfiguration.WIREGUARD else xrayConfiguration
        var xe = xrayEnabled ?: false

        // Migration from old nested ClientConfig format
        if (oldClientConfig != null && ((tc.routes as List<*>?)?.isEmpty() != false) && !oc.isValid()) {
            kv = oldClientConfig.kernelVariant
            raw = oldClientConfig.isRawMode
            cmd = oldClientConfig.rawCommand
            tc = oldClientConfig.turnableConfig
            oc = oldClientConfig.olcrtcConfig
        }

        // Migration from old nested Xray format
        if (oldXraySettings != null && (xe as Any?) == false) {
            xe = oldXraySettings.xrayEnabled
        }
        if (oldXrayConfig != null && (xc as Any?) == XrayConfiguration.WIREGUARD && (xrayConfiguration as Any?) == null) {
            xc = oldXrayConfig.xrayConfiguration
        }

        // Deep safety for WG and VLESS
        val wc = (wgConfig ?: WgConfig()).fillDefaults()
        val vc = (vlessConfig ?: VlessConfig()).sanitize()

        return copy(
            id = (id as Any?)?.toString()?.take(100) ?: java.util.UUID.randomUUID().toString(),
            name = (name as Any?)?.toString()?.take(100) ?: "Unnamed",
            kernelVariant = kv,
            isRawMode = raw,
            rawCommand = cmd,
            turnableConfig = tc.sanitize(),
            olcrtcConfig = oc.sanitize(),
            xrayConfiguration = xc,
            xrayEnabled = xe,
            vlessConfig = vc,
            wgConfig = wc
        )
    }
}

data class GlobalVpnSettings(
    val hideSystemApps: Boolean = true,
    val bypassMode: Boolean = true,
    val filteringEnabled: Boolean = true,
    val groupAppsByLetter: Boolean = true
)

data class AutoLaunchSettings(
    val enabled: Boolean = false,
    val checkUrl: String = "https://www.google.com",
    val intervalMinutes: Int = 15
)

class AppPreferences(val context: Context) {
    private val appCtx = context.applicationContext
    private val gson = GsonBuilder().registerTypeAdapterFactory(SafeEnumTypeAdapterFactory()).create()

    companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
        val CURRENT_PROFILE_ID = stringPreferencesKey("current_profile_id")
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val VPN_ENABLED = booleanPreferencesKey("proxy_vpn_mode")
        val VPN_HIDE_SYSTEM_APPS = booleanPreferencesKey("vpn_hide_system_apps")
        val VPN_BYPASS_MODE = booleanPreferencesKey("vpn_bypass_mode")
        val VPN_FILTERING_ENABLED = booleanPreferencesKey("vpn_filtering_enabled")
        val VPN_GROUP_APPS_BY_LETTER = booleanPreferencesKey("vpn_group_apps_by_letter")
        val XRAY_EXCLUDED_APPS = stringSetPreferencesKey("proxy_excluded_apps")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val SHOW_FLOATING_ACTION_BUTTON = booleanPreferencesKey("show_floating_action_button")
        val AUTO_LAUNCH_ENABLED = booleanPreferencesKey("auto_launch_enabled")
        val AUTO_LAUNCH_URL = stringPreferencesKey("auto_launch_url")
        val AUTO_LAUNCH_INTERVAL = intPreferencesKey("auto_launch_interval")
        val VLESS_LINK_HISTORY = stringPreferencesKey("vless_link_history")
        val BATTERY_NOTIFICATION_DISMISSED = booleanPreferencesKey("battery_notification_dismissed")
        val APPS_EXCLUSION_HINT_SHOWN = booleanPreferencesKey("apps_exclusion_hint_shown")
        val ALLOW_UNSTABLE_UPDATES = booleanPreferencesKey("allow_unstable_updates")
        val WAIT_FOR_NETWORK = booleanPreferencesKey("wait_for_network")
        val RESTART_ON_NETWORK_CHANGE = booleanPreferencesKey("restart_on_network_change")
        val CAPTCHA_STYLE_MOD = booleanPreferencesKey("captcha_style_mod")
        val CAPTCHA_FORCE_TINT = booleanPreferencesKey("captcha_force_tint")

        val CLIENT_LISTEN_ADDR = stringPreferencesKey("client_listen_addr")
        val OLCRTC_SOCKS_ADDR = stringPreferencesKey("olcrtc_socks_addr")
        val OLCRTC_SOCKS_AUTH_ENABLED = booleanPreferencesKey("olcrtc_socks_auth_enabled")
        val OLCRTC_SOCKS_USER = stringPreferencesKey("olcrtc_socks_user")
        val OLCRTC_SOCKS_PASS = stringPreferencesKey("olcrtc_socks_pass")
        val XRAY_SOCKS_BIND = stringPreferencesKey("xray_socks_bind")
        val XRAY_HTTP_BIND = stringPreferencesKey("xray_http_bind")
        val XRAY_AUTH_ENABLED = booleanPreferencesKey("xray_auth_enabled")
        val XRAY_USER = stringPreferencesKey("xray_user")
        val XRAY_PASS = stringPreferencesKey("xray_pass")

        val ACTIVE_KERNEL_VARIANT = stringPreferencesKey("active_kernel_variant")
        val ACTIVE_IS_RAW = booleanPreferencesKey("active_is_raw")
        val ACTIVE_RAW_CMD = stringPreferencesKey("active_raw_cmd")
        val ACTIVE_TURNABLE_JSON = stringPreferencesKey("active_turnable_json")
        val ACTIVE_OLCRTC_JSON = stringPreferencesKey("active_olcrtc_json")
        val ACTIVE_XRAY_CONFIG_TYPE = stringPreferencesKey("active_xray_config_type")
        val ACTIVE_XRAY_ENABLED = booleanPreferencesKey("active_xray_enabled")
        val ACTIVE_WG_JSON = stringPreferencesKey("active_wg_json")
        val ACTIVE_VLESS_JSON = stringPreferencesKey("active_vless_json")
    }

    private fun <T> Flow<Preferences>.mapPref(key: Preferences.Key<T>, def: T): Flow<T> =
        this.map { it[key] ?: def }.distinctUntilChanged()

    val onboardingDoneFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(ONBOARDING_DONE, false)

    suspend fun hasActiveProfile(): Boolean =
        appCtx.internalDataStore.data.map { it[ACTIVE_KERNEL_VARIANT] }.first() != null

    val themeModeFlow: Flow<ThemeMode> = appCtx.internalDataStore.data
        .map { ThemeMode.valueOf(it[THEME_MODE] ?: ThemeMode.DARK.name) }
        .distinctUntilChanged()

    val dynamicThemeFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(DYNAMIC_THEME, true)
    val vpnEnabledFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(VPN_ENABLED, false)
    val batteryNotificationDismissedFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(BATTERY_NOTIFICATION_DISMISSED, false)
    val appsExclusionHintShownFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(APPS_EXCLUSION_HINT_SHOWN, false)
    val allowUnstableUpdatesFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(ALLOW_UNSTABLE_UPDATES, false)
    val waitForNetworkFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(WAIT_FOR_NETWORK, true)
    val restartOnNetworkChangeFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(RESTART_ON_NETWORK_CHANGE, false)
    val captchaStyleModFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(CAPTCHA_STYLE_MOD, true)
    val captchaForceTintFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(CAPTCHA_FORCE_TINT, true)
    val showFloatingActionButtonFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(SHOW_FLOATING_ACTION_BUTTON, true)
    val appLanguageFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(APP_LANGUAGE, "system")

    val globalVpnSettingsFlow: Flow<GlobalVpnSettings> = appCtx.internalDataStore.data
        .map {
            GlobalVpnSettings(
                it[VPN_HIDE_SYSTEM_APPS] ?: true,
                it[VPN_BYPASS_MODE] ?: true,
                it[VPN_FILTERING_ENABLED] ?: true,
                it[VPN_GROUP_APPS_BY_LETTER] ?: true
            )
        }.distinctUntilChanged()

    val excludedAppsFlow: Flow<Set<String>> = appCtx.internalDataStore.data
        .map { it[XRAY_EXCLUDED_APPS] ?: emptySet() }
        .distinctUntilChanged()

    val profilesFlow: Flow<List<Profile>> = appCtx.internalDataStore.data
        .map { p ->
            val json = p[PROFILES_JSON] ?: "[]"
            val list = try {
                gson.fromJson<List<Profile>>(json, object : TypeToken<List<Profile>>() {}.type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            list.map { it.sanitize() }
        }.distinctUntilChanged()

    val currentProfileIdFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(CURRENT_PROFILE_ID, "default")
    val currentProfileNameFlow: Flow<String?> = combine(profilesFlow, currentProfileIdFlow) { profiles, id ->
        profiles.find { it.id == id }?.name
    }

    val vlessLinkHistoryFlow: Flow<List<String>> = appCtx.internalDataStore.data
        .map { p ->
            (p[VLESS_LINK_HISTORY] ?: "").split("|").filter { it.isNotBlank() }
        }

    val olcrtcSocksAddrFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(OLCRTC_SOCKS_ADDR, ClientConfig.DEFAULT_SOCKS_ADDR)
    val olcrtcSocksAuthEnabledFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(OLCRTC_SOCKS_AUTH_ENABLED, true)
    val olcrtcSocksUserFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(OLCRTC_SOCKS_USER, "")
    val olcrtcSocksPassFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(OLCRTC_SOCKS_PASS, "")

    val autoLaunchSettingsFlow: Flow<AutoLaunchSettings> = appCtx.internalDataStore.data
        .map {
            AutoLaunchSettings(
                it[AUTO_LAUNCH_ENABLED] ?: false,
                it[AUTO_LAUNCH_URL] ?: "https://www.google.com",
                it[AUTO_LAUNCH_INTERVAL] ?: 15
            )
        }.distinctUntilChanged()

    val clientConfigFlow: Flow<ClientConfig> = appCtx.internalDataStore.data
        .map { p ->
            ClientConfig(
                listenAddr = p[CLIENT_LISTEN_ADDR] ?: ClientConfig.DEFAULT_LISTEN_ADDR,
                socksAddr = p[OLCRTC_SOCKS_ADDR] ?: ClientConfig.DEFAULT_SOCKS_ADDR,
                isSocksAuthEnabled = p[OLCRTC_SOCKS_AUTH_ENABLED] ?: true,
                socksUser = p[OLCRTC_SOCKS_USER] ?: "",
                socksPass = p[OLCRTC_SOCKS_PASS] ?: "",
                isRawMode = p[ACTIVE_IS_RAW] ?: false,
                rawCommand = p[ACTIVE_RAW_CMD] ?: "",
                kernelVariant = try {
                    KernelVariant.valueOf(p[ACTIVE_KERNEL_VARIANT] ?: KernelVariant.TURNABLE.name)
                } catch (_: Exception) {
                    KernelVariant.TURNABLE
                },
                turnableConfig = gson.fromJson(p[ACTIVE_TURNABLE_JSON] ?: "{}", TurnableConfig::class.java) ?: TurnableConfig(),
                olcrtcConfig = gson.fromJson(p[ACTIVE_OLCRTC_JSON] ?: "{}", OlcrtcConfig::class.java) ?: OlcrtcConfig()
            ).fillDefaults()
        }.distinctUntilChanged()

    val xrayConfigFlow: Flow<XrayConfig> = appCtx.internalDataStore.data
        .map { p ->
            XrayConfig(
                socksBindAddress = p[XRAY_SOCKS_BIND] ?: XrayConfig.DEFAULT_SOCKS_BIND_ADDRESS,
                httpBindAddress = p[XRAY_HTTP_BIND] ?: "",
                isProxyAuthEnabled = p[XRAY_AUTH_ENABLED] ?: true,
                proxyUser = p[XRAY_USER] ?: "",
                proxyPass = p[XRAY_PASS] ?: "",
                xrayConfiguration = try {
                    XrayConfiguration.valueOf(p[ACTIVE_XRAY_CONFIG_TYPE] ?: XrayConfiguration.WIREGUARD.name)
                } catch (_: Exception) {
                    XrayConfiguration.WIREGUARD
                }
            ).fillDefaults()
        }.distinctUntilChanged()

    val xraySettingsFlow: Flow<XraySettings> = appCtx.internalDataStore.data
        .map { XraySettings(it[ACTIVE_XRAY_ENABLED] ?: false) }
        .distinctUntilChanged()

    val wgConfigFlow: Flow<WgConfig> = appCtx.internalDataStore.data
        .map { (gson.fromJson(it[ACTIVE_WG_JSON] ?: "{}", WgConfig::class.java) ?: WgConfig()).fillDefaults() }
        .distinctUntilChanged()

    val vlessConfigFlow: Flow<VlessConfig> = appCtx.internalDataStore.data
        .map { (gson.fromJson(it[ACTIVE_VLESS_JSON] ?: "{}", VlessConfig::class.java) ?: VlessConfig()).fillDefaults() }
        .distinctUntilChanged()

    suspend fun saveFullProfile(id: String, profile: Profile) {
        appCtx.internalDataStore.edit { p ->
            p[CURRENT_PROFILE_ID] = id
            p[ACTIVE_KERNEL_VARIANT] = profile.kernelVariant.name
            p[ACTIVE_IS_RAW] = profile.isRawMode
            p[ACTIVE_RAW_CMD] = profile.rawCommand
            p[ACTIVE_TURNABLE_JSON] = gson.toJson(profile.turnableConfig)
            p[ACTIVE_OLCRTC_JSON] = gson.toJson(profile.olcrtcConfig)
            p[ACTIVE_XRAY_CONFIG_TYPE] = profile.xrayConfiguration.name
            p[ACTIVE_XRAY_ENABLED] = profile.xrayEnabled
            p[ACTIVE_WG_JSON] = gson.toJson(profile.wgConfig)
            p[ACTIVE_VLESS_JSON] = gson.toJson(profile.vlessConfig)
        }
    }

    suspend fun saveOlcrtcSocks(addr: String, auth: Boolean, user: String, pass: String) {
        appCtx.internalDataStore.edit { p ->
            p[OLCRTC_SOCKS_ADDR] = addr
            p[OLCRTC_SOCKS_AUTH_ENABLED] = auth
            p[OLCRTC_SOCKS_USER] = user
            p[OLCRTC_SOCKS_PASS] = pass
        }
    }

    suspend fun saveXrayGlobal(socks: String, http: String, auth: Boolean, user: String, pass: String) {
        appCtx.internalDataStore.edit { p ->
            p[XRAY_SOCKS_BIND] = socks
            p[XRAY_HTTP_BIND] = http
            p[XRAY_AUTH_ENABLED] = auth
            p[XRAY_USER] = user
            p[XRAY_PASS] = pass
        }
    }

    suspend fun saveClientListenAddr(addr: String) {
        appCtx.internalDataStore.edit { it[CLIENT_LISTEN_ADDR] = addr }
    }

    suspend fun saveProfiles(list: List<Profile>) {
        appCtx.internalDataStore.edit { it[PROFILES_JSON] = gson.toJson(list) }
    }

    suspend fun setVpnEnabled(v: Boolean) {
        appCtx.internalDataStore.edit { it[VPN_ENABLED] = v }
    }

    suspend fun setDynamicTheme(v: Boolean) {
        appCtx.internalDataStore.edit { it[DYNAMIC_THEME] = v }
    }

    suspend fun setThemeMode(m: ThemeMode) {
        appCtx.internalDataStore.edit { it[THEME_MODE] = m.name }
    }

    suspend fun setOnboardingDone(v: Boolean) {
        appCtx.internalDataStore.edit { it[ONBOARDING_DONE] = v }
    }

    suspend fun setAppLanguage(l: String) {
        appCtx.internalDataStore.edit { it[APP_LANGUAGE] = l }
    }

    suspend fun setShowFloatingActionButton(v: Boolean) {
        appCtx.internalDataStore.edit { it[SHOW_FLOATING_ACTION_BUTTON] = v }
    }

    suspend fun setBatteryNotificationDismissed(v: Boolean) {
        appCtx.internalDataStore.edit { it[BATTERY_NOTIFICATION_DISMISSED] = v }
    }

    suspend fun setAppsExclusionHintShown(v: Boolean) {
        appCtx.internalDataStore.edit { it[APPS_EXCLUSION_HINT_SHOWN] = v }
    }

    suspend fun setAllowUnstableUpdates(v: Boolean) {
        appCtx.internalDataStore.edit { it[ALLOW_UNSTABLE_UPDATES] = v }
    }

    suspend fun setWaitForNetwork(v: Boolean) {
        appCtx.internalDataStore.edit { it[WAIT_FOR_NETWORK] = v }
    }

    suspend fun setRestartOnNetworkChange(v: Boolean) {
        appCtx.internalDataStore.edit { it[RESTART_ON_NETWORK_CHANGE] = v }
    }

    suspend fun setCaptchaStyleMod(v: Boolean) {
        appCtx.internalDataStore.edit { it[CAPTCHA_STYLE_MOD] = v }
    }

    suspend fun setCaptchaForceTint(v: Boolean) {
        appCtx.internalDataStore.edit { it[CAPTCHA_FORCE_TINT] = v }
    }

    suspend fun addVlessLinkToHistory(l: String) {
        appCtx.internalDataStore.edit { p ->
            val h = p[VLESS_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            p[VLESS_LINK_HISTORY] = (listOf(l) + h.filter { it != l }).take(3).joinToString("|")
        }
    }

    suspend fun removeVlessLinkFromHistory(l: String) {
        appCtx.internalDataStore.edit { p ->
            p[VLESS_LINK_HISTORY] = (p[VLESS_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() && it != l } ?: emptyList())
                .joinToString("|")
        }
    }

    suspend fun saveGlobalVpnSettings(s: GlobalVpnSettings) {
        appCtx.internalDataStore.edit {
            it[VPN_HIDE_SYSTEM_APPS] = s.hideSystemApps
            it[VPN_BYPASS_MODE] = s.bypassMode
            it[VPN_FILTERING_ENABLED] = s.filteringEnabled
            it[VPN_GROUP_APPS_BY_LETTER] = s.groupAppsByLetter
        }
    }

    suspend fun saveExcludedApps(s: Set<String>) {
        appCtx.internalDataStore.edit { it[XRAY_EXCLUDED_APPS] = s }
    }

    suspend fun updateAutoLaunchSettings(s: AutoLaunchSettings) {
        appCtx.internalDataStore.edit {
            it[AUTO_LAUNCH_ENABLED] = s.enabled
            it[AUTO_LAUNCH_URL] = s.checkUrl
            it[AUTO_LAUNCH_INTERVAL] = s.intervalMinutes
        }
    }

    suspend fun saveWgConfig(c: WgConfig) {
        appCtx.internalDataStore.edit { it[ACTIVE_WG_JSON] = gson.toJson(c) }
    }

    suspend fun saveXraySettings(s: XraySettings) {
        appCtx.internalDataStore.edit { it[ACTIVE_XRAY_ENABLED] = s.xrayEnabled }
    }

    suspend fun saveVlessConfig(c: VlessConfig) {
        appCtx.internalDataStore.edit { it[ACTIVE_VLESS_JSON] = gson.toJson(c) }
    }

    suspend fun saveXrayConfig(c: XrayConfig) {
        appCtx.internalDataStore.edit {
            it[XRAY_SOCKS_BIND] = c.socksBindAddress
            it[XRAY_HTTP_BIND] = c.httpBindAddress
            it[XRAY_AUTH_ENABLED] = c.isProxyAuthEnabled
            it[XRAY_USER] = c.proxyUser
            it[XRAY_PASS] = c.proxyPass
            it[ACTIVE_XRAY_CONFIG_TYPE] = c.xrayConfiguration.name
        }
    }

    suspend fun saveClientConfig(c: ClientConfig) {
        appCtx.internalDataStore.edit {
            it[CLIENT_LISTEN_ADDR] = c.listenAddr
            it[OLCRTC_SOCKS_ADDR] = c.socksAddr
            it[OLCRTC_SOCKS_AUTH_ENABLED] = c.isSocksAuthEnabled
            it[OLCRTC_SOCKS_USER] = c.socksUser
            it[OLCRTC_SOCKS_PASS] = c.socksPass
            it[ACTIVE_KERNEL_VARIANT] = c.kernelVariant.name
            it[ACTIVE_IS_RAW] = c.isRawMode
            it[ACTIVE_RAW_CMD] = c.rawCommand
            it[ACTIVE_TURNABLE_JSON] = gson.toJson(c.turnableConfig)
            it[ACTIVE_OLCRTC_JSON] = gson.toJson(c.olcrtcConfig)
        }
    }

    suspend fun resetAll() {
        appCtx.internalDataStore.edit { it.clear() }
        withContext(Dispatchers.IO) {
            File(appCtx.filesDir, "custom_core").delete()
        }
    }

    suspend fun saveActiveProfilePart(profile: Profile) {
        appCtx.internalDataStore.edit { p ->
            p[ACTIVE_KERNEL_VARIANT] = profile.kernelVariant.name
            p[ACTIVE_IS_RAW] = profile.isRawMode
            p[ACTIVE_RAW_CMD] = profile.rawCommand
            p[ACTIVE_TURNABLE_JSON] = gson.toJson(profile.turnableConfig)
            p[ACTIVE_OLCRTC_JSON] = gson.toJson(profile.olcrtcConfig)
            p[ACTIVE_XRAY_CONFIG_TYPE] = profile.xrayConfiguration.name
            p[ACTIVE_XRAY_ENABLED] = profile.xrayEnabled
            p[ACTIVE_WG_JSON] = gson.toJson(profile.wgConfig)
            p[ACTIVE_VLESS_JSON] = gson.toJson(profile.vlessConfig)
        }
    }
}
