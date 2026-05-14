package com.wireturn.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.wireturn.app.ui.ValidatorUtils
import java.io.StringReader

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

/**
 * GSON Adapter Factory that prevents crashes when an enum value is removed or renamed.
 * It will fallback to the first enum constant if the value in JSON is unknown.
 */
class SafeEnumTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val rawType = type.rawType
        if (!rawType.isEnum) return null

        val constants = rawType.enumConstants as Array<T>
        val delegate = gson.getDelegateAdapter(this, type)

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                delegate.write(out, value)
            }

            override fun read(reader: JsonReader): T? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }
                val name = reader.nextString()
                return try {
                    val tempReader = JsonReader(StringReader("\"$name\""))
                    delegate.read(tempReader)
                } catch (_: Exception) {
                    constants.firstOrNull()
                }
            }
        }.nullSafe()
    }
}

enum class KernelVariant {
    TURNABLE, OLCRTC
}

enum class XrayConfiguration {
    WIREGUARD, VLESS
}

data class TurnableRoute(
    @SerializedName("route_id") val routeId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("socket") val socket: String = "",
    @SerializedName("transport") val transport: String? = null
)

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
    fun sanitize(): TurnableConfig {
        val rts = routes
        return copy(
            userUuid = userUuid?.take(200),
            username = username.take(200),
            platformId = platformId.take(200),
            callId = callId.take(200),
            type = type.take(100),
            encryption = encryption?.take(100),
            pubKey = pubKey?.take(4096),
            gateway = gateway.take(500),
            proto = proto?.take(100),
            selectedRouteId = selectedRouteId.take(100),
            routes = rts.map { it.copy(
                routeId = it.routeId.take(100),
                name = it.name.take(100),
                socket = it.socket.take(100),
                transport = it.transport?.take(100)
            ) }
        )
    }

    fun toUrl(onlySelected: Boolean = false): String {
        val targetRoutes = if (onlySelected && selectedRouteId.isNotBlank()) {
            routes.filter { it.routeId == selectedRouteId }
        } else {
            routes
        }

        val uriBuilder = android.net.Uri.Builder()
            .scheme("turnable")

        val userInfo = if (!userUuid.isNullOrBlank()) {
            "${android.net.Uri.encode(userUuid)}:${android.net.Uri.encode(callId)}"
        } else {
            android.net.Uri.encode(callId)
        }
        uriBuilder.encodedAuthority("$userInfo@$platformId")

        targetRoutes.forEach {
            uriBuilder.appendPath(it.routeId)
        }

        uriBuilder.appendQueryParameter("username", username)
        uriBuilder.appendQueryParameter("gateway", gateway)
        uriBuilder.appendQueryParameter("type", type)
        encryption?.let { uriBuilder.appendQueryParameter("encryption", it) }
        pubKey?.let { uriBuilder.appendQueryParameter("pub_key", it) }
        proto?.let { uriBuilder.appendQueryParameter("proto", it) }
        if (peers != 1) uriBuilder.appendQueryParameter("peers", peers.toString())
        if (forceTurn) uriBuilder.appendQueryParameter("forceturn", "true")

        if (targetRoutes.size == 1) {
            val r = targetRoutes[0]
            if (r.socket != "udp") uriBuilder.appendQueryParameter("socket", r.socket)
            r.transport?.let { uriBuilder.appendQueryParameter("transport", it) }
        } else {
            targetRoutes.forEachIndexed { index, r ->
                val idx = index + 1
                if (r.socket != "udp") uriBuilder.appendQueryParameter("socket[$idx]", r.socket)
                r.transport?.let { uriBuilder.appendQueryParameter("transport[$idx]", it) }
            }
        }

        if (selectedRouteId.isNotBlank()) {
            uriBuilder.appendQueryParameter("selected_route_id", selectedRouteId)
        }

        if (targetRoutes.isNotEmpty()) {
            uriBuilder.encodedFragment(targetRoutes.joinToString(",") { android.net.Uri.encode(it.name) })
        }

        return uriBuilder.build().toString()
    }

    fun isValid(): Boolean {
        if (username.isBlank() || platformId.isBlank() || callId.isBlank() || gateway.isBlank() || routes.isEmpty()) return false
        if (selectedRouteId.isNotBlank() && routes.none { it.routeId == selectedRouteId }) return false
        return when (type) {
            "relay" -> {
                !userUuid.isNullOrBlank() && !encryption.isNullOrBlank() && !pubKey.isNullOrBlank() && !proto.isNullOrBlank()
            }
            "direct" -> {
                proto == "none" && routes.all { it.socket == "udp" && (it.transport == null || it.transport == "none") }
            }
            else -> false
        }
    }

    companion object {
        fun parse(url: String, base: TurnableConfig = TurnableConfig()): TurnableConfig? {
            if (!url.startsWith("turnable://", ignoreCase = true)) return null
            return try {
                val uri = url.toUri()
                
                val encodedUserInfo = uri.encodedUserInfo ?: ""
                val userParts = encodedUserInfo.split(":").map { android.net.Uri.decode(it) }
                val userUuid = if (userParts.size > 1) userParts[0] else null
                val callId = if (userParts.size > 1) userParts[1] else userParts.getOrNull(0) ?: ""

                val encodedPath = uri.encodedPath ?: ""
                val pathParts = encodedPath.split("/").filter { it.isNotBlank() }.map { android.net.Uri.decode(it) }
                
                val encodedFragment = uri.encodedFragment ?: ""
                val routeNames = encodedFragment.split(",").map { android.net.Uri.decode(it) }

                val type = uri.getQueryParameter("type") ?: "relay"

                val routes = pathParts.mapIndexed { index, routeId ->
                    val idx = index + 1
                    TurnableRoute(
                        routeId = routeId,
                        name = routeNames.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() } ?: routeId,
                        socket = uri.getQueryParameter("socket[$idx]")
                            ?: (if (index == 0) uri.getQueryParameter("socket") else null)
                            ?: "udp",
                        transport = uri.getQueryParameter("transport[$idx]")
                            ?: (if (index == 0) uri.getQueryParameter("transport") else null)
                    )
                }

                val host = uri.host ?: ""
                val port = uri.port
                val platformId = if (port != -1) "$host:$port" else host

                base.copy(
                    userUuid = userUuid ?: base.userUuid,
                    callId = callId.ifBlank { base.callId },
                    platformId = platformId.ifBlank { base.platformId },
                    username = uri.getQueryParameter("username") ?: base.username,
                    type = type,
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
    @SerializedName("socks_host") val socksHost: String = "127.0.0.1",
    @SerializedName("socks_port") val socksPort: String = "9000",
    @SerializedName("socks_auth_enabled") val isSocksAuthEnabled: Boolean = true,
    @SerializedName("socks_user") val socksUser: String = "",
    @SerializedName("socks_pass") val socksPass: String = "",
    @SerializedName("mimo") val mimo: String = "",

    // vp8channel
    @SerializedName("vp8_fps") val vp8Fps: Int = 60,
    @SerializedName("vp8_batch") val vp8Batch: Int = 64,

    // seichannel
    @SerializedName("sei_fps") val seiFps: Int = 60,
    @SerializedName("sei_batch") val seiBatch: Int = 64,
    @SerializedName("sei_frag") val seiFrag: Int = 900,
    @SerializedName("sei_ack_ms") val seiAckMs: Int = 2000,

    // videochannel
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
    fun sanitize(): OlcrtcConfig {
        return copy(
            carrier = carrier.take(100),
            transport = transport.take(100),
            id = id.take(200),
            clientId = clientId.take(200),
            key = key.take(1000),
            dns = dns.take(200),
            socksHost = socksHost.take(200),
            socksPort = socksPort.take(10),
            socksUser = socksUser.take(100),
            socksPass = socksPass.take(100),
            mimo = mimo.take(500),
            videoCodec = videoCodec.take(100),
            videoBitrate = videoBitrate.take(20),
            videoHw = videoHw.take(50),
            videoQrRecovery = videoQrRecovery.take(20)
        )
    }

    fun isValid(): Boolean {
        return id.isNotBlank() && clientId.isNotBlank() && key.isNotBlank() && dns.isNotBlank()
    }

    fun fillDefaults(): OlcrtcConfig {
        var current = copy(
            videoW = if (videoW <= 0) 1080 else videoW,
            videoH = if (videoH <= 0) 1080 else videoH,
            socksHost = if (ValidatorUtils.isValidHost(socksHost)) socksHost else ClientConfig.DEFAULT_SOCKS_HOST,
            socksPort = if (ValidatorUtils.isValidPort(socksPort)) socksPort else ClientConfig.DEFAULT_SOCKS_PORT
        )

        if (current.isSocksAuthEnabled) {
            var nextUser = ValidatorUtils.cleanProxyString(current.socksUser)
            var nextPass = ValidatorUtils.cleanProxyString(current.socksPass)

            if (!ValidatorUtils.isValidProxyUser(nextUser)) {
                val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
                nextUser = (1..8).map { allowedChars.random() }.joinToString("")
            }
            if (!ValidatorUtils.isValidProxyPass(nextPass)) {
                val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
                nextPass = (1..12).map { allowedChars.random() }.joinToString("")
            }
            current = current.copy(socksUser = nextUser, socksPass = nextPass)
        }

        return current
    }

    fun toUri(): String {
        val sb = StringBuilder("olcrtc://")
        sb.append(carrier)
        sb.append("?").append(transport)

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
                if (videoW != 0 && videoW != 1080) params.add("video-w=$videoW")
                if (videoH != 0 && videoH != 1080) params.add("video-h=$videoH")
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

        if (params.isNotEmpty()) {
            sb.append("<").append(params.joinToString("&")).append(">")
        }

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
                val afterCarrier = url.substringAfter("?", "")
                if (afterCarrier.isEmpty()) return null

                val transportPart = afterCarrier.substringBefore("@")
                val transport = transportPart.substringBefore("<")
                val payload = if (transportPart.contains("<")) transportPart.substringAfter("<").substringBefore(">") else ""

                val rest = afterCarrier.substringAfter("@", "")
                val id = rest.substringBefore("#").substringBefore("%").substringBefore("$")

                val afterId = if (rest.contains("#")) rest.substringAfter("#") else ""
                val key = afterId.substringBefore("%").substringBefore("$")

                val afterKey = if (rest.contains("%")) rest.substringAfter("%") else ""
                val clientId = afterKey.substringBefore("$")

                val mimo = if (rest.contains("$")) rest.substringAfter("$") else ""

                var config = base.copy(
                    carrier = carrier.ifBlank { base.carrier },
                    transport = transport.ifBlank { base.transport },
                    id = id.ifBlank { base.id },
                    key = key.ifBlank { base.key },
                    clientId = clientId.ifBlank { base.clientId }.ifBlank { "wireturn" },
                    mimo = mimo.ifBlank { base.mimo }
                )

                if (payload.isNotBlank()) {
                    val params = payload.split("&").associate {
                        val parts = it.split("=", limit = 2)
                        parts[0] to (parts.getOrNull(1) ?: "")
                    }
                    config = when (transport) {
                        "vp8channel" -> config.copy(
                            vp8Fps = params["vp8-fps"]?.toIntOrNull() ?: config.vp8Fps,
                            vp8Batch = params["vp8-batch"]?.toIntOrNull() ?: config.vp8Batch
                        )
                        "seichannel" -> config.copy(
                            seiFps = params["fps"]?.toIntOrNull() ?: config.seiFps,
                            seiBatch = params["batch"]?.toIntOrNull() ?: config.seiBatch,
                            seiFrag = params["frag"]?.toIntOrNull() ?: config.seiFrag,
                            seiAckMs = params["ack-ms"]?.toIntOrNull() ?: config.seiAckMs
                        )
                        "videochannel" -> config.copy(
                            videoW = params["video-w"]?.toIntOrNull() ?: config.videoW,
                            videoH = params["video-h"]?.toIntOrNull() ?: config.videoH,
                            videoFps = params["video-fps"]?.toIntOrNull() ?: config.videoFps,
                            videoBitrate = params["video-bitrate"] ?: config.videoBitrate,
                            videoHw = params["video-hw"] ?: config.videoHw,
                            videoCodec = params["video-codec"] ?: config.videoCodec,
                            videoQrSize = params["video-qr-size"]?.toIntOrNull() ?: config.videoQrSize,
                            videoQrRecovery = params["video-qr-recovery"] ?: config.videoQrRecovery,
                            videoTileModule = params["video-tile-module"]?.toIntOrNull() ?: config.videoTileModule,
                            videoTileRs = params["video-tile-rs"]?.toIntOrNull() ?: config.videoTileRs
                        )
                        else -> config
                    }
                }
                config
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class ClientConfig(
    @SerializedName("localPort") val localPort: String = DEFAULT_LOCAL_PORT,
    @SerializedName("isRawMode") val isRawMode: Boolean = false,
    @SerializedName("rawCommand") val rawCommand: String = "",
    @SerializedName("turnableUrl") val turnableUrl: String = "",
    @SerializedName("olcrtcUrl") val olcrtcUrl: String = "",
    @SerializedName("turnableConfig") val turnableConfig: TurnableConfig = TurnableConfig(),
    @SerializedName("olcrtcConfig") val olcrtcConfig: OlcrtcConfig = OlcrtcConfig(),
    @SerializedName("kernelVariant") val kernelVariant: KernelVariant = KernelVariant.TURNABLE
) {
    fun fillDefaults(): ClientConfig {
        return copy(
            localPort = localPort.ifBlank { DEFAULT_LOCAL_PORT },
            olcrtcConfig = olcrtcConfig.fillDefaults(),
            turnableConfig = turnableConfig
        )
    }

    /** GSON can leave fields null if they are missing/invalid in JSON. This ensures safety. */
    fun migrateAndSanitize(): ClientConfig {
        var current = copy(
            localPort = localPort.take(100),
            rawCommand = rawCommand.take(2000),
            turnableUrl = turnableUrl.take(2000),
            olcrtcUrl = olcrtcUrl.take(2000),
            turnableConfig = turnableConfig.sanitize(),
            olcrtcConfig = olcrtcConfig.sanitize(),
            kernelVariant = kernelVariant
        )
        
        // Migration logic: if we have a URL but no valid config, parse it once
        if (!current.turnableConfig.isValid() && current.turnableUrl.isNotBlank()) {
            val parsed = TurnableConfig.parse(current.turnableUrl, current.turnableConfig)
            if (parsed != null) {
                current = current.copy(
                    turnableConfig = parsed,
                    turnableUrl = "" // Clear the URL after successful migration
                )
            }
        }

        if (!current.olcrtcConfig.isValid() && current.olcrtcUrl.isNotBlank()) {
            val parsed = OlcrtcConfig.parse(current.olcrtcUrl, current.olcrtcConfig)
            if (parsed != null) {
                current = current.copy(
                    olcrtcConfig = parsed,
                    olcrtcUrl = "" // Clear the URL after successful migration
                )
            }
        }
        return current
    }

    fun getValidationErrorResId(): Int? {
        val c = migrateAndSanitize()
        if (c.isRawMode) {
            return if (c.rawCommand.isBlank()) com.wireturn.app.R.string.error_raw_empty else null
        }

        return when (c.kernelVariant) {
            KernelVariant.TURNABLE -> {
                if (c.turnableUrl.isBlank() && !c.turnableConfig.isValid()) com.wireturn.app.R.string.error_settings_empty else null
            }
            KernelVariant.OLCRTC -> {
                if (!c.olcrtcConfig.isValid()) com.wireturn.app.R.string.error_settings_empty else null
            }
        }
    }

    val isValid: Boolean get() = getValidationErrorResId() == null

    val connectableAddress: String
        get() {
            val port = localPort.ifBlank { DEFAULT_LOCAL_PORT }
            return if (port.startsWith("0.0.0.0:")) {
                port.replace("0.0.0.0:", "127.0.0.1:")
            } else {
                port
            }
        }

    fun getKernelDescription(context: Context): String {
        return when (kernelVariant) {
            KernelVariant.TURNABLE -> {
                val base = context.getString(com.wireturn.app.R.string.kernel_turnable)
                val selectedRoute = turnableConfig.routes.find { it.routeId == turnableConfig.selectedRouteId }
                    ?: turnableConfig.routes.firstOrNull()
                if (selectedRoute != null) {
                    "$base r:${selectedRoute.name.ifBlank { selectedRoute.routeId }}"
                } else {
                    base
                }
            }
            KernelVariant.OLCRTC -> {
                val base = context.getString(com.wireturn.app.R.string.kernel_olcrtc)
                val config = olcrtcConfig
                val carrier = when (config.carrier) {
                    "wbstream" -> "WB Stream"
                    "telemost" -> "Telemost"
                    "jazz" -> "Jazz"
                    else -> config.carrier
                }
                val transport = when (config.transport) {
                    "datachannel" -> "DC"
                    "vp8channel" -> "VP8C"
                    "seichannel" -> "SEIC"
                    "videochannel" -> "VC"
                    else -> config.transport
                }
                "$base • $carrier • $transport"
            }
        }
    }

    companion object {
        const val DEFAULT_LOCAL_PORT = "127.0.0.1:9000"
        val DEFAULT_SOCKS_HOST: String get() = DEFAULT_LOCAL_PORT.substringBefore(':')
        val DEFAULT_SOCKS_PORT: String get() = DEFAULT_LOCAL_PORT.substringAfter(':')
    }
}

data class VlessConfig(
    @SerializedName("vlessLink") val vlessLink: String = "",
    @SerializedName("vlessUseLocalAddress") val vlessUseLocalAddress: Boolean = true,
    @SerializedName("isDualRoute") val isDualRoute: Boolean = false,
    @SerializedName("directAddress") val directAddress: String = "",
    @SerializedName("hcInterval") val hcInterval: String = "30"
) {
    fun isValid(): Boolean = vlessLink.isNotBlank() && ValidatorUtils.isValidVlessLink(vlessLink)

    fun fillDefaults(): VlessConfig {
        return copy(
            hcInterval = hcInterval.ifBlank { "30" }
        )
    }

    fun sanitize(): VlessConfig {
        return copy(
            vlessLink = vlessLink.take(4096),
            directAddress = directAddress.take(500),
            hcInterval = hcInterval.take(20)
        )
    }
}

data class XraySettings(
    @SerializedName("xrayEnabled") val xrayEnabled: Boolean = false,
    @SerializedName("xrayVpnMode") val xrayVpnMode: Boolean = false
)

data class GlobalVpnSettings(
    @SerializedName("hideSystemApps") val hideSystemApps: Boolean = true,
    @SerializedName("bypassMode") val bypassMode: Boolean = true,
    @SerializedName("filteringEnabled") val filteringEnabled: Boolean = true,
    @SerializedName("groupAppsByLetter") val groupAppsByLetter: Boolean = true
)

data class AutoLaunchSettings(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("checkUrl") val checkUrl: String = "https://www.google.com",
    @SerializedName("intervalMinutes") val intervalMinutes: Int = 15
)

data class XrayConfig(
    @SerializedName("socksBindAddress") val socksBindAddress: String = DEFAULT_SOCKS_BIND_ADDRESS,
    @SerializedName("httpBindAddress") val httpBindAddress: String = "",
    @SerializedName("xrayConfiguration") val xrayConfiguration: XrayConfiguration = XrayConfiguration.WIREGUARD,
    @SerializedName("isProxyAuthEnabled") val isProxyAuthEnabled: Boolean = true,
    @SerializedName("proxyUser") val proxyUser: String = "",
    @SerializedName("proxyPass") val proxyPass: String = ""
) {
    fun fillDefaults(): XrayConfig {
        var current = this
        val isSocksValid = socksBindAddress.isNotBlank() && ValidatorUtils.isValidHostPort(socksBindAddress)
        if (!isSocksValid) {
            current = current.copy(socksBindAddress = DEFAULT_SOCKS_BIND_ADDRESS)
        }

        if (current.isProxyAuthEnabled) {
            var nextUser = ValidatorUtils.cleanProxyString(current.proxyUser)
            var nextPass = ValidatorUtils.cleanProxyString(current.proxyPass)

            if (!ValidatorUtils.isValidProxyUser(nextUser)) {
                val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
                nextUser = (1..8).map { allowedChars.random() }.joinToString("")
            }
            if (!ValidatorUtils.isValidProxyPass(nextPass)) {
                val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
                nextPass = (1..12).map { allowedChars.random() }.joinToString("")
            }
            current = current.copy(proxyUser = nextUser, proxyPass = nextPass)
        }

        return current
    }

    val connectableAddress: String
        get() = if (socksBindAddress.startsWith("0.0.0.0:")) {
            socksBindAddress.replace("0.0.0.0:", "127.0.0.1:")
        } else {
            socksBindAddress
        }

    companion object {
        const val DEFAULT_SOCKS_BIND_ADDRESS = "127.0.0.1:1080"
    }
}

data class WgConfig(
    @SerializedName("privateKey") val privateKey: String = "",
    @SerializedName("address") val address: String = "",
    @SerializedName("mtu") val mtu: String = DEFAULT_MTU,
    @SerializedName("publicKey") val publicKey: String = "",
    @SerializedName("endpoint") val endpoint: String = DEFAULT_ENDPOINT,
    @SerializedName("persistentKeepalive") val persistentKeepalive: String = DEFAULT_PERSISTENT_KEEPALIVE
) {
    fun isValid(): Boolean {
        return privateKey.isNotBlank() && address.isNotBlank() && publicKey.isNotBlank()
    }

    fun fillDefaults(): WgConfig {
        return copy(
            privateKey = privateKey.take(500),
            address = address.take(500),
            mtu = mtu.ifBlank { DEFAULT_MTU }.take(20),
            publicKey = publicKey.take(500),
            endpoint = endpoint.ifBlank { DEFAULT_ENDPOINT }.take(500),
            persistentKeepalive = persistentKeepalive.ifBlank { DEFAULT_PERSISTENT_KEEPALIVE }.take(20)
        )
    }

    fun toWgString(): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = $privateKey\n")
        sb.append("Address = $address\n")
        if (mtu.isNotBlank()) sb.append("MTU = $mtu\n")
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = $publicKey\n")
        sb.append("Endpoint = $endpoint\n")
        if (persistentKeepalive.isNotBlank()) sb.append("PersistentKeepalive = $persistentKeepalive\n")
        return sb.toString()
    }

    companion object {
        const val DEFAULT_MTU = "1280"
        const val DEFAULT_ENDPOINT = ClientConfig.DEFAULT_LOCAL_PORT
        const val DEFAULT_PERSISTENT_KEEPALIVE = "25"

        fun parse(text: String): WgConfig {
            var privateKey = ""; var address = ""; var mtu = ""
            var publicKey = ""; var endpoint = ""; var persistentKeepalive = ""

            var currentSection = ""
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(1, trimmed.length - 1).lowercase()
                } else if (trimmed.contains("=")) {
                    val parts = trimmed.split("=", limit = 2)
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    when (currentSection) {
                        "interface" -> when (key) {
                            "privatekey" -> privateKey = value
                            "address" -> address = value
                            "mtu" -> mtu = value
                        }
                        "peer" -> when (key) {
                            "publickey" -> publicKey = value
                            "endpoint" -> endpoint = value
                            "persistentkeepalive" -> persistentKeepalive = value
                        }
                    }
                }
            }
            return WgConfig(privateKey, address, mtu, publicKey, endpoint, persistentKeepalive)
        }
    }
}

enum class ThemeMode {
    DARK, LIGHT, SYSTEM
}

data class Profile(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("clientConfig") val clientConfig: ClientConfig = ClientConfig(),
    @SerializedName("xraySettings") val xraySettings: XraySettings = XraySettings(),
    @SerializedName("xrayConfig") val xrayConfig: XrayConfig = XrayConfig(),
    @SerializedName("wgConfig") val wgConfig: WgConfig = WgConfig(),
    @SerializedName("vlessConfig") val vlessConfig: VlessConfig = VlessConfig()
) {
    fun isEmpty(): Boolean {
        return clientConfig.turnableUrl.isBlank() &&
                clientConfig.olcrtcUrl.isBlank() &&
                clientConfig.rawCommand.isBlank() &&
                !clientConfig.turnableConfig.isValid() &&
                !clientConfig.olcrtcConfig.isValid() &&
                !wgConfig.isValid() &&
                !vlessConfig.isValid()
    }

    fun fillDefaults(): Profile {
        return copy(
            clientConfig = clientConfig.fillDefaults(),
            wgConfig = wgConfig.fillDefaults(),
            xrayConfig = xrayConfig.fillDefaults(),
            vlessConfig = vlessConfig.fillDefaults()
        )
    }

    fun sanitize(): Profile {
        return copy(
            id = id.ifBlank { java.util.UUID.randomUUID().toString() }.take(100),
            name = name.ifBlank { "Unnamed" }.take(100),
            clientConfig = clientConfig.migrateAndSanitize(),
            wgConfig = wgConfig.fillDefaults(),
            xrayConfig = xrayConfig.fillDefaults(),
            vlessConfig = vlessConfig.sanitize()
        )
    }
}

// P2-3 / P3-6: всегда используем applicationContext, чтобы lazy-init encryptedPrefs
class AppPreferences(context: Context) {
    private val context = context.applicationContext
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .create()

    companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
        val CURRENT_PROFILE_ID = stringPreferencesKey("current_profile_id")
        val CLIENT_LOCAL_PORT = stringPreferencesKey("client_local_port")
        val CLIENT_IS_RAW = booleanPreferencesKey("client_is_raw")
        val CLIENT_RAW_CMD = stringPreferencesKey("client_raw_cmd")
        val CLIENT_VLESS_LINK = stringPreferencesKey("client_vless_link")
        val CLIENT_VLESS_USE_LOCAL_ADDRESS = booleanPreferencesKey("client_vless_use_local_address")
        val CLIENT_VLESS_IS_DUAL_ROUTE = booleanPreferencesKey("client_vless_is_dual_route")
        val CLIENT_VLESS_DIRECT_ADDRESS = stringPreferencesKey("client_vless_direct_address")
        val CLIENT_VLESS_HC_INTERVAL = stringPreferencesKey("client_vless_hc_interval")
        val VLESS_LINK_HISTORY = stringPreferencesKey("vless_link_history")
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val XRAY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val XRAY_VPN_MODE = booleanPreferencesKey("proxy_vpn_mode")
        val VPN_HIDE_SYSTEM_APPS = booleanPreferencesKey("vpn_hide_system_apps")
        val VPN_BYPASS_MODE = booleanPreferencesKey("vpn_bypass_mode")
        val VPN_FILTERING_ENABLED = booleanPreferencesKey("vpn_filtering_enabled")
        val VPN_GROUP_APPS_BY_LETTER = booleanPreferencesKey("vpn_group_apps_by_letter")
        val XRAY_EXCLUDED_APPS = stringSetPreferencesKey("proxy_excluded_apps")
        val WIRE_PRIV_KEY = stringPreferencesKey("wire_priv_key")
        val WIRE_ADDRESS = stringPreferencesKey("wire_address")
        val WIRE_MTU = stringPreferencesKey("wire_mtu")
        val WIRE_PUB_KEY = stringPreferencesKey("wire_pub_key")
        val WIRE_ENDPOINT = stringPreferencesKey("wire_endpoint")
        val WIRE_KEEPALIVE = stringPreferencesKey("wire_keepalive")
        val SOCKS_BIND = stringPreferencesKey("socks_bind")
        val HTTP_BIND = stringPreferencesKey("http_bind")
        val XRAY_CONFIGURATION = stringPreferencesKey("xray_configuration")
        val CLIENT_TURNABLE_URL = stringPreferencesKey("client_turnable_url")
        val CLIENT_OLCRTC_URL = stringPreferencesKey("client_olcrtc_url")
        val CLIENT_KERNEL_VARIANT = stringPreferencesKey("client_kernel_variant")
        val BATTERY_NOTIFICATION_DISMISSED = booleanPreferencesKey("battery_notification_dismissed")
        val APPS_EXCLUSION_HINT_SHOWN = booleanPreferencesKey("apps_exclusion_hint_shown")
        val XRAY_PROXY_AUTH_ENABLED = booleanPreferencesKey("xray_proxy_auth_enabled")
        val XRAY_PROXY_USER = stringPreferencesKey("xray_proxy_user")
        val XRAY_PROXY_PASS = stringPreferencesKey("xray_proxy_pass")
        val ALLOW_UNSTABLE_UPDATES = booleanPreferencesKey("allow_unstable_updates")
        val WAIT_FOR_NETWORK = booleanPreferencesKey("wait_for_network")
        val RESTART_ON_NETWORK_CHANGE = booleanPreferencesKey("restart_on_network_change")
        val CAPTCHA_STYLE_MOD = booleanPreferencesKey("captcha_style_mod")
        val CAPTCHA_FORCE_TINT = booleanPreferencesKey("captcha_force_tint")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val SHOW_FLOATING_ACTION_BUTTON = booleanPreferencesKey("show_floating_action_button")
        val CLIENT_TURNABLE_CONFIG = stringPreferencesKey("client_turnable_config")
        val CLIENT_OLCRTC_CONFIG = stringPreferencesKey("client_olcrtc_config")
        val AUTO_LAUNCH_ENABLED = booleanPreferencesKey("auto_launch_enabled")
        val AUTO_LAUNCH_URL = stringPreferencesKey("auto_launch_url")
        val AUTO_LAUNCH_INTERVAL = intPreferencesKey("auto_launch_interval")
    }

    val profilesFlow: Flow<List<Profile>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val json = prefs[PROFILES_JSON] ?: ""
            if (json.isBlank()) return@map emptyList()
            
            try {
                val type = object : TypeToken<List<Profile>>() {}.type
                val rawList = gson.fromJson<List<Profile>>(json, type) ?: emptyList()
                rawList.map { it.sanitize() }
            } catch (_: Exception) {
                // If parsing the whole list fails, try to rescue profiles one by one
                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(json).asJsonArray
                    val rescued = mutableListOf<Profile>()
                    jsonArray.forEach { 
                        try {
                            rescued.add(gson.fromJson(it, Profile::class.java).sanitize())
                        } catch (_: Exception) {}
                    }
                    rescued
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    val currentProfileIdFlow: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[CURRENT_PROFILE_ID] ?: "default" }
        .distinctUntilChanged()

    val currentProfileNameFlow: Flow<String?> = combine(profilesFlow, currentProfileIdFlow) { profiles, id ->
        profiles.find { it.id == id }?.name
    }

    suspend fun updateAutoLaunchSettings(settings: AutoLaunchSettings) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_LAUNCH_ENABLED] = settings.enabled
            prefs[AUTO_LAUNCH_URL] = settings.checkUrl
            prefs[AUTO_LAUNCH_INTERVAL] = settings.intervalMinutes
        }
    }

    suspend fun saveProfiles(profiles: List<Profile>) {
        context.dataStore.edit { prefs ->
            prefs[PROFILES_JSON] = gson.toJson(profiles)
        }
    }

    suspend fun setCurrentProfileId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[CURRENT_PROFILE_ID] = id
        }
    }

    val clientConfigFlow: Flow<ClientConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            ClientConfig(
                localPort = prefs.getStringSafe(CLIENT_LOCAL_PORT, ClientConfig.DEFAULT_LOCAL_PORT),
                isRawMode = prefs[CLIENT_IS_RAW] ?: false,
                rawCommand = prefs[CLIENT_RAW_CMD] ?: "",
                turnableUrl = prefs[CLIENT_TURNABLE_URL] ?: "",
                olcrtcUrl = prefs[CLIENT_OLCRTC_URL] ?: "",
                turnableConfig = prefs[CLIENT_TURNABLE_CONFIG]?.let {
                    try { gson.fromJson(it, TurnableConfig::class.java) } catch (_: Exception) { null }
                } ?: TurnableConfig(),
                olcrtcConfig = prefs[CLIENT_OLCRTC_CONFIG]?.let {
                    try { gson.fromJson(it, OlcrtcConfig::class.java) } catch (_: Exception) { null }
                } ?: OlcrtcConfig(),
                kernelVariant = try {
                    KernelVariant.valueOf(prefs[CLIENT_KERNEL_VARIANT] ?: KernelVariant.TURNABLE.name)
                } catch (_: Exception) {
                    KernelVariant.TURNABLE
                }
            ).migrateAndSanitize()
        }
        .distinctUntilChanged()

    val wgConfigFlow: Flow<WgConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            WgConfig(
                privateKey = prefs[WIRE_PRIV_KEY] ?: "",
                address = prefs[WIRE_ADDRESS] ?: "",
                mtu = prefs.getStringSafe(WIRE_MTU, WgConfig.DEFAULT_MTU),
                publicKey = prefs[WIRE_PUB_KEY] ?: "",
                endpoint = prefs.getStringSafe(WIRE_ENDPOINT, WgConfig.DEFAULT_ENDPOINT),
                persistentKeepalive = prefs.getStringSafe(WIRE_KEEPALIVE, WgConfig.DEFAULT_PERSISTENT_KEEPALIVE)
            )
        }
        .distinctUntilChanged()

    val xraySettingsFlow: Flow<XraySettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            XraySettings(
                xrayEnabled = prefs[XRAY_ENABLED] ?: false,
                xrayVpnMode = prefs[XRAY_VPN_MODE] ?: false
            )
        }
        .distinctUntilChanged()

    val globalVpnSettingsFlow: Flow<GlobalVpnSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            GlobalVpnSettings(
                hideSystemApps = prefs[VPN_HIDE_SYSTEM_APPS] ?: true,
                bypassMode = prefs[VPN_BYPASS_MODE] ?: true,
                filteringEnabled = prefs[VPN_FILTERING_ENABLED] ?: true,
                groupAppsByLetter = prefs[VPN_GROUP_APPS_BY_LETTER] ?: true
            )
        }
        .distinctUntilChanged()

    val excludedAppsFlow: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[XRAY_EXCLUDED_APPS] ?: emptySet() }
        .distinctUntilChanged()

    val xrayConfigFlow: Flow<XrayConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            XrayConfig(
                socksBindAddress = prefs.getStringSafe(SOCKS_BIND, XrayConfig.DEFAULT_SOCKS_BIND_ADDRESS),
                httpBindAddress = prefs.getStringSafe(HTTP_BIND),
                xrayConfiguration = XrayConfiguration.valueOf(prefs[XRAY_CONFIGURATION] ?: XrayConfiguration.WIREGUARD.name),
                isProxyAuthEnabled = prefs[XRAY_PROXY_AUTH_ENABLED] ?: true,
                proxyUser = prefs[XRAY_PROXY_USER] ?: "",
                proxyPass = prefs[XRAY_PROXY_PASS] ?: ""
            )
        }
        .distinctUntilChanged()

    val vlessConfigFlow: Flow<VlessConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            VlessConfig(
                vlessLink = prefs[CLIENT_VLESS_LINK] ?: "",
                vlessUseLocalAddress = prefs[CLIENT_VLESS_USE_LOCAL_ADDRESS] ?: true,
                isDualRoute = prefs[CLIENT_VLESS_IS_DUAL_ROUTE] ?: false,
                directAddress = prefs[CLIENT_VLESS_DIRECT_ADDRESS] ?: "",
                hcInterval = prefs.getStringSafe(CLIENT_VLESS_HC_INTERVAL, "30")
            )
        }
        .distinctUntilChanged()

    val onboardingDoneFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[ONBOARDING_DONE] ?: false }
        .distinctUntilChanged()

    val dynamicThemeFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[DYNAMIC_THEME] ?: true }
        .distinctUntilChanged()

    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            ThemeMode.valueOf(prefs[THEME_MODE] ?: ThemeMode.DARK.name)
        }
        .distinctUntilChanged()

    val batteryNotificationDismissedFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[BATTERY_NOTIFICATION_DISMISSED] ?: false }
        .distinctUntilChanged()

    val appsExclusionHintShownFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[APPS_EXCLUSION_HINT_SHOWN] ?: false }
        .distinctUntilChanged()

    val allowUnstableUpdatesFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[ALLOW_UNSTABLE_UPDATES] ?: false }
        .distinctUntilChanged()

    val waitForNetworkFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[WAIT_FOR_NETWORK] ?: true }
        .distinctUntilChanged()

    val restartOnNetworkChangeFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[RESTART_ON_NETWORK_CHANGE] ?: false }
        .distinctUntilChanged()

    val captchaStyleModFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[CAPTCHA_STYLE_MOD] ?: true }
        .distinctUntilChanged()

    val captchaForceTintFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[CAPTCHA_FORCE_TINT] ?: true }
        .distinctUntilChanged()

    val showFloatingActionButtonFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[SHOW_FLOATING_ACTION_BUTTON] ?: true }
        .distinctUntilChanged()

    val appLanguageFlow: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[APP_LANGUAGE] ?: "system" }
        .distinctUntilChanged()

    val autoLaunchSettingsFlow: Flow<AutoLaunchSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            AutoLaunchSettings(
                enabled = prefs[AUTO_LAUNCH_ENABLED] ?: false,
                checkUrl = prefs[AUTO_LAUNCH_URL] ?: "https://www.google.com",
                intervalMinutes = prefs[AUTO_LAUNCH_INTERVAL] ?: 15
            )
        }
        .distinctUntilChanged()

    val vlessLinkHistoryFlow: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val historyString = prefs[VLESS_LINK_HISTORY] ?: ""
            if (historyString.isBlank()) emptyList()
            else historyString.split("|").filter { it.isNotBlank() }
        }

    suspend fun addVlessLinkToHistory(link: String) {
        if (link.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[VLESS_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = (listOf(link) + currentHistory.filter { it != link }).take(3)
            prefs[VLESS_LINK_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun removeVlessLinkFromHistory(link: String) {
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[VLESS_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = currentHistory.filter { it != link }
            prefs[VLESS_LINK_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun saveFullProfile(
        id: String,
        clientConfig: ClientConfig,
        wgConfig: WgConfig,
        vlessConfig: VlessConfig,
        xraySettings: XraySettings,
        xrayConfig: XrayConfig
    ) {
        context.dataStore.edit { prefs ->
            // Current ID
            prefs[CURRENT_PROFILE_ID] = id

            // Client Config
            prefs[CLIENT_LOCAL_PORT] = clientConfig.localPort
            prefs[CLIENT_IS_RAW] = clientConfig.isRawMode
            prefs[CLIENT_RAW_CMD] = clientConfig.rawCommand
            prefs[CLIENT_TURNABLE_URL] = clientConfig.turnableUrl
            prefs[CLIENT_OLCRTC_URL] = clientConfig.olcrtcUrl
            prefs[CLIENT_TURNABLE_CONFIG] = gson.toJson(clientConfig.turnableConfig)
            prefs[CLIENT_OLCRTC_CONFIG] = gson.toJson(clientConfig.olcrtcConfig)
            prefs[CLIENT_KERNEL_VARIANT] = clientConfig.kernelVariant.name

            // WG
            prefs[WIRE_PRIV_KEY] = wgConfig.privateKey
            prefs[WIRE_ADDRESS] = wgConfig.address
            prefs[WIRE_MTU] = wgConfig.mtu
            prefs[WIRE_PUB_KEY] = wgConfig.publicKey
            prefs[WIRE_ENDPOINT] = wgConfig.endpoint
            prefs[WIRE_KEEPALIVE] = wgConfig.persistentKeepalive

            // VLESS
            prefs[CLIENT_VLESS_LINK] = vlessConfig.vlessLink
            prefs[CLIENT_VLESS_USE_LOCAL_ADDRESS] = vlessConfig.vlessUseLocalAddress
            prefs[CLIENT_VLESS_IS_DUAL_ROUTE] = vlessConfig.isDualRoute
            prefs[CLIENT_VLESS_DIRECT_ADDRESS] = vlessConfig.directAddress
            prefs[CLIENT_VLESS_HC_INTERVAL] = vlessConfig.hcInterval

            // Xray Settings
            prefs[XRAY_ENABLED] = xraySettings.xrayEnabled
            prefs[XRAY_VPN_MODE] = xraySettings.xrayVpnMode

            // Xray Config
            prefs[SOCKS_BIND] = xrayConfig.socksBindAddress
            prefs[HTTP_BIND] = xrayConfig.httpBindAddress
            prefs[XRAY_CONFIGURATION] = xrayConfig.xrayConfiguration.name
            prefs[XRAY_PROXY_AUTH_ENABLED] = xrayConfig.isProxyAuthEnabled
            prefs[XRAY_PROXY_USER] = xrayConfig.proxyUser
            prefs[XRAY_PROXY_PASS] = xrayConfig.proxyPass
        }
    }

    suspend fun saveClientConfig(config: ClientConfig) {
        context.dataStore.edit { prefs ->
            prefs[CLIENT_LOCAL_PORT] = config.localPort
            prefs[CLIENT_IS_RAW] = config.isRawMode
            prefs[CLIENT_RAW_CMD] = config.rawCommand
            prefs[CLIENT_TURNABLE_URL] = config.turnableUrl
            prefs[CLIENT_OLCRTC_URL] = config.olcrtcUrl
            prefs[CLIENT_TURNABLE_CONFIG] = gson.toJson(config.turnableConfig)
            prefs[CLIENT_OLCRTC_CONFIG] = gson.toJson(config.olcrtcConfig)
            prefs[CLIENT_KERNEL_VARIANT] = config.kernelVariant.name
        }
    }

    suspend fun setBatteryNotificationDismissed(dismissed: Boolean) {
        context.dataStore.edit { prefs -> prefs[BATTERY_NOTIFICATION_DISMISSED] = dismissed }
    }

    suspend fun setAppsExclusionHintShown(shown: Boolean) {
        context.dataStore.edit { prefs -> prefs[APPS_EXCLUSION_HINT_SHOWN] = shown }
    }

    suspend fun setAllowUnstableUpdates(allow: Boolean) {
        context.dataStore.edit { prefs -> prefs[ALLOW_UNSTABLE_UPDATES] = allow }
    }

    suspend fun setWaitForNetwork(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[WAIT_FOR_NETWORK] = enabled }
    }

    suspend fun setRestartOnNetworkChange(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[RESTART_ON_NETWORK_CHANGE] = enabled }
    }

    suspend fun setCaptchaStyleMod(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[CAPTCHA_STYLE_MOD] = enabled }
    }

    suspend fun setCaptchaForceTint(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[CAPTCHA_FORCE_TINT] = enabled }
    }

    suspend fun setShowFloatingActionButton(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SHOW_FLOATING_ACTION_BUTTON] = enabled }
    }

    suspend fun setAppLanguage(lang: String) {
        context.dataStore.edit { prefs -> prefs[APP_LANGUAGE] = lang }
    }

    suspend fun saveWgConfig(config: WgConfig) {
        context.dataStore.edit { prefs ->
            prefs[WIRE_PRIV_KEY] = config.privateKey
            prefs[WIRE_ADDRESS] = config.address
            prefs[WIRE_MTU] = config.mtu
            prefs[WIRE_PUB_KEY] = config.publicKey
            prefs[WIRE_ENDPOINT] = config.endpoint
            prefs[WIRE_KEEPALIVE] = config.persistentKeepalive
        }
    }

    suspend fun saveXraySettings(settings: XraySettings) {
        context.dataStore.edit { prefs ->
            prefs[XRAY_ENABLED] = settings.xrayEnabled
            prefs[XRAY_VPN_MODE] = settings.xrayVpnMode
        }
    }

    suspend fun saveGlobalVpnSettings(settings: GlobalVpnSettings) {
        context.dataStore.edit { prefs ->
            prefs[VPN_HIDE_SYSTEM_APPS] = settings.hideSystemApps
            prefs[VPN_BYPASS_MODE] = settings.bypassMode
            prefs[VPN_FILTERING_ENABLED] = settings.filteringEnabled
            prefs[VPN_GROUP_APPS_BY_LETTER] = settings.groupAppsByLetter
        }
    }

    suspend fun saveExcludedApps(excludedApps: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[XRAY_EXCLUDED_APPS] = excludedApps
        }
    }

    suspend fun saveXrayConfig(config: XrayConfig) {
        context.dataStore.edit { prefs ->
            prefs[SOCKS_BIND] = config.socksBindAddress
            prefs[HTTP_BIND] = config.httpBindAddress
            prefs[XRAY_CONFIGURATION] = config.xrayConfiguration.name
            prefs[XRAY_PROXY_AUTH_ENABLED] = config.isProxyAuthEnabled
            prefs[XRAY_PROXY_USER] = config.proxyUser
            prefs[XRAY_PROXY_PASS] = config.proxyPass
        }
    }

    suspend fun saveVlessConfig(config: VlessConfig) {
        context.dataStore.edit { prefs ->
            prefs[CLIENT_VLESS_LINK] = config.vlessLink
            prefs[CLIENT_VLESS_USE_LOCAL_ADDRESS] = config.vlessUseLocalAddress
            prefs[CLIENT_VLESS_IS_DUAL_ROUTE] = config.isDualRoute
            prefs[CLIENT_VLESS_DIRECT_ADDRESS] = config.directAddress
            prefs[CLIENT_VLESS_HC_INTERVAL] = config.hcInterval
        }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.dataStore.edit { prefs -> prefs[ONBOARDING_DONE] = done }
    }

    suspend fun setDynamicTheme(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DYNAMIC_THEME] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[THEME_MODE] = mode.name }
    }

    /** Полный сброс: DataStore + кастомный бинарник */
    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
        withContext(Dispatchers.IO) {
            File(context.filesDir, "custom_core").delete()
        }
    }

    private fun Preferences.getStringSafe(key: Preferences.Key<String>, default: String = ""): String {
        return try {
            this[key] ?: default
        } catch (_: ClassCastException) {
            this[intPreferencesKey(key.name)]?.toString() ?: default
        }
    }
}
