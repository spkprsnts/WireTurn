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
import com.wireturn.app.R
import com.wireturn.app.ui.ValidatorUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
                if (reader.peek() != JsonToken.STRING) {
                    reader.skipValue()
                    return constants.firstOrNull()
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

enum class KernelVariant { TURNABLE, OLCRTC, WEBDAV }
enum class XrayConfiguration { WIREGUARD, VLESS }
enum class ThemeMode { DARK, LIGHT, SYSTEM }

sealed class KernelConfig {
    data class Turnable(val config: TurnableConfig = TurnableConfig()) : KernelConfig()
    data class Olcrtc(val config: OlcrtcConfig = OlcrtcConfig()) : KernelConfig()
    data class Webdav(val config: WebdavConfig = WebdavConfig()) : KernelConfig()
}

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
    @SerializedName("encryption") val encryption: String? = "handshake",
    @SerializedName("pub_key") val pubKey: String? = null,
    @SerializedName("peers") val peers: Int = 1,
    @SerializedName("forceturn") val forceTurn: Boolean = false,
    @SerializedName("gateway") val gateway: String = "",
    @SerializedName("proto") val proto: String? = "srtp",
    @SerializedName("routes") val routes: List<TurnableRoute> = emptyList(),
    @SerializedName("selected_route_id") val selectedRouteId: String = ""
) {
    fun sanitize(): TurnableConfig = copy(
        userUuid = (userUuid as Any?)?.toString()?.take(200),
        username = (username as Any?)?.toString()?.take(200) ?: "",
        platformId = (platformId as Any?)?.toString()?.take(200) ?: "vk.com",
        callId = (callId as Any?)?.toString()?.take(200) ?: "",
        type = (type as Any?)?.toString()?.take(100) ?: "relay",
        encryption = (encryption as Any?)?.toString()?.take(100) ?: "handshake",
        pubKey = (pubKey as Any?)?.toString()?.take(4096),
        gateway = (gateway as Any?)?.toString()?.take(500) ?: "",
        proto = (proto as Any?)?.toString()?.take(100) ?: "srtp",
        selectedRouteId = (selectedRouteId as Any?)?.toString()?.take(100) ?: "",
        routes = (routes as List<TurnableRoute>?)?.map { it.sanitize() } ?: emptyList()
    )

    fun isValid(): Boolean = username.isNotBlank() &&
            platformId.isNotBlank() &&
            callId.isNotBlank() &&
            gateway.isNotBlank() &&
            routes.isNotEmpty() &&
            (userUuid.isNullOrBlank() || ValidatorUtils.isValidUuid4(userUuid))


    val platformDisplayName: String
        get() = getPlatformDisplayName(platformId)

    fun toUri(onlySelected: Boolean = false): String {
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
        builder.appendQueryParameter("peers", peers.toString())
        builder.appendQueryParameter("forceturn", forceTurn.toString())
        if (targetRoutes.size == 1) {
            val r = targetRoutes[0]
            builder.appendQueryParameter("socket", r.socket)
            r.transport?.let { builder.appendQueryParameter("transport", it) }
        } else {
            targetRoutes.forEachIndexed { index, r ->
                val idx = index + 1
                builder.appendQueryParameter("socket[$idx]", r.socket)
                r.transport?.let { builder.appendQueryParameter("transport[$idx]", it) }
            }
        }
        if (selectedRouteId.isNotBlank()) builder.appendQueryParameter("selected_route_id", selectedRouteId)
        if (targetRoutes.isNotEmpty()) builder.encodedFragment(targetRoutes.joinToString(",") { Uri.encode(it.name) })
        return builder.build().toString()
    }

    companion object {
        fun getPlatformDisplayName(platformId: String): String = when (platformId) {
            "vk.com" -> "VK"
            else -> platformId
        }
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
    @SerializedName("provider", alternate = ["carrier"]) val provider: String = "wbstream",
    @SerializedName("transport") val transport: String = "datachannel",
    @SerializedName("id") val id: String = "",
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
    val providerDisplayName: String
        get() = when (provider) {
            "wbstream" -> "WB Stream"
            "telemost" -> "Telemost"
            "jitsi" -> "Jitsi"
            else -> provider
        }

    val transportDisplayName: String
        get() = getTransportDisplayName(transport)

    fun sanitize(): OlcrtcConfig {
        return copy(
            provider = (provider as Any?)?.toString()?.take(100) ?: "wbstream",
            transport = (transport as Any?)?.toString()?.take(100) ?: "datachannel",
            id = (id as Any?)?.toString()?.take(200) ?: "",
            key = (key as Any?)?.toString()?.take(1000) ?: "",
            dns = (dns as Any?)?.toString()?.take(200) ?: "1.1.1.1:53",
            mimo = (mimo as Any?)?.toString()?.take(500) ?: "",
            videoW = if (videoW <= 0) 1080 else videoW,
            videoH = if (videoH <= 0) 1080 else videoH
        )
    }

    fun isValid(): Boolean = id.isNotBlank() && key.isNotBlank() && dns.isNotBlank()
    fun fillDefaults(): OlcrtcConfig = sanitize()

    fun toUri(profileName: String? = null): String {
        val sb = StringBuilder("olcrtc://").append(provider).append("?").append(transport)
        val params = mutableListOf<String>()
        when (transport) {
            "vp8channel" -> {
                params.add("vp8-fps=$vp8Fps")
                params.add("vp8-batch=$vp8Batch")
            }
            "seichannel" -> {
                params.add("fps=$seiFps")
                params.add("batch=$seiBatch")
                params.add("frag=$seiFrag")
                params.add("ack-ms=$seiAckMs")
            }
            "videochannel" -> {
                params.add("video-w=$videoW")
                params.add("video-h=$videoH")
                params.add("video-fps=$videoFps")
                params.add("video-bitrate=$videoBitrate")
                params.add("video-hw=$videoHw")
                params.add("video-codec=$videoCodec")
                if (videoCodec == "qrcode") {
                    params.add("video-qr-size=$videoQrSize")
                    params.add("video-qr-recovery=$videoQrRecovery")
                } else if (videoCodec == "tile") {
                    params.add("video-tile-module=$videoTileModule")
                    params.add("video-tile-rs=$videoTileRs")
                }
            }
        }
        if (params.isNotEmpty()) sb.append("<").append(params.joinToString("&")).append(">")
        sb.append("@").append(id)
        if (key.isNotBlank()) sb.append("#").append(key)

        val effectiveMimo = if (mimo.isNotBlank()) {
            mimo
        } else if (!profileName.isNullOrBlank()) {
            profileName
        } else {
            ""
        }
        if (effectiveMimo.isNotBlank()) sb.append("$").append(effectiveMimo)
        return sb.toString()
    }

    companion object {
        fun getTransportDisplayName(transport: String, short: Boolean = false): String = when (transport) {
            "datachannel" -> if (short) "DC" else "DataChannel"
            "vp8channel" -> if (short) "VP8C" else "VP8Channel"
            "seichannel" -> if (short) "SEIC" else "SEIChannel"
            "videochannel" -> if (short) "VC" else "VideoChannel"
            else -> transport
        }

        fun parse(url: String, base: OlcrtcConfig = OlcrtcConfig()): OlcrtcConfig? {
            if (!url.startsWith("olcrtc://", ignoreCase = true)) return null
            return try {
                val provider = url.substringAfter("olcrtc://").substringBefore("?")
                val transportPart = url.substringAfter("?").substringBefore("@")
                val transport = transportPart.substringBefore("<")
                val payload = if (transportPart.contains("<")) transportPart.substringAfter("<").substringBefore(">") else ""
                val rest = url.substringAfter("@")
                val id = rest.substringBefore("#").substringBefore("$")
                val key = if (rest.contains("#")) rest.substringAfter("#").substringBefore("$") else ""
                val mimo = if (rest.contains("$")) rest.substringAfter("$") else ""
                var cfg = base.copy(
                    provider = provider,
                    transport = transport,
                    id = id,
                    key = key,
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

data class WebdavConfig(
    @SerializedName("webdav") val webdav: String = "",
    @SerializedName("login") val login: String = "",
    @SerializedName("password") val password: String = "",
    @SerializedName("timeout") val timeout: String = "60s",
    @SerializedName("poll_max") val pollMax: String = "500ms",
    @SerializedName("poll_min") val pollMin: String = "200ms",
    @SerializedName("coalesce") val coalesce: String = "10ms",
    @SerializedName("chunk_size") val chunkSize: String = "131071",
    @SerializedName("puts") val puts: String = "8",
    @SerializedName("read_min") val readMin: String = "3",
    @SerializedName("read_max") val readMax: String = "8"
) {
    fun isValid(): Boolean = webdav.isNotBlank()
    fun fillDefaults(): WebdavConfig = copy(
        timeout = timeout.ifBlank { "60s" },
        pollMax = pollMax.ifBlank { "500ms" },
        pollMin = pollMin.ifBlank { "200ms" },
        coalesce = coalesce.ifBlank { "10ms" },
        chunkSize = chunkSize.ifBlank { "131071" },
        puts = puts.ifBlank { "8" },
        readMin = readMin.ifBlank { "3" },
        readMax = readMax.ifBlank { "8" }
    )

    fun toUri(profileName: String? = null): String {
        val isHttps = webdav.startsWith("https://", ignoreCase = true)
        val scheme = if (isHttps) "webdavs" else "webdav"
        
        // Remove existing protocol and parse as URI to get host/port/path
        val cleanBase = webdav.replaceFirst("https://", "", ignoreCase = true)
                              .replaceFirst("http://", "", ignoreCase = true)
        
        val builder = Uri.Builder()
            .scheme(scheme)
            .encodedAuthority(buildString {
                if (login.isNotBlank()) {
                    append(Uri.encode(login))
                    if (password.isNotBlank()) {
                        append(":")
                        append(Uri.encode(password))
                    }
                    append("@")
                }
                append(cleanBase.substringBefore("/"))
            })
            
        val path = cleanBase.substringAfter("/", "")
        if (path.isNotBlank()) {
            builder.path(path)
        }

        builder.appendQueryParameter("timeout", timeout)
        builder.appendQueryParameter("poll-min", pollMin)
        builder.appendQueryParameter("poll-max", pollMax)
        builder.appendQueryParameter("coalesce", coalesce)
        builder.appendQueryParameter("chunk-size", chunkSize)
        builder.appendQueryParameter("puts", puts)
        builder.appendQueryParameter("read-min", readMin)
        builder.appendQueryParameter("read-max", readMax)
        
        if (!profileName.isNullOrBlank()) {
            builder.fragment(profileName)
        }
        
        return builder.build().toString()
    }

    companion object {
        fun parse(uriStr: String, current: WebdavConfig = WebdavConfig()): WebdavConfig? {
            val isWebdavs = uriStr.startsWith("webdavs://", ignoreCase = true)
            val isWebdav = uriStr.startsWith("webdav://", ignoreCase = true)
            if (!isWebdav && !isWebdavs) return null
            
            try {
                val uri = Uri.parse(uriStr)
                val userParts = (uri.userInfo ?: "").split(":")
                val login = userParts.getOrNull(0)?.let { Uri.decode(it) } ?: ""
                val password = userParts.getOrNull(1)?.let { Uri.decode(it) } ?: ""
                
                val webdavScheme = if (isWebdavs) "https" else "http"
                val host = uri.host ?: ""
                val port = if (uri.port != -1) ":${uri.port}" else ""
                val path = uri.path ?: ""
                
                val webdav = "$webdavScheme://$host$port$path"

                return WebdavConfig(
                    webdav = webdav,
                    login = login,
                    password = password,
                    timeout = uri.getQueryParameter("timeout") ?: current.timeout,
                    pollMin = uri.getQueryParameter("poll-min") ?: current.pollMin,
                    pollMax = uri.getQueryParameter("poll-max") ?: current.pollMax,
                    coalesce = uri.getQueryParameter("coalesce") ?: current.coalesce,
                    chunkSize = uri.getQueryParameter("chunk-size") ?: current.chunkSize,
                    puts = uri.getQueryParameter("puts") ?: current.puts,
                    readMin = uri.getQueryParameter("read-min") ?: current.readMin,
                    readMax = uri.getQueryParameter("read-max") ?: current.readMax
                )
            } catch (_: Exception) {
                return null
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
    val goDnsGo: Boolean = false,
    val kernelConfig: KernelConfig = KernelConfig.Turnable()
) {
    val kernelVariant: KernelVariant get() = when (kernelConfig) {
        is KernelConfig.Turnable -> KernelVariant.TURNABLE
        is KernelConfig.Olcrtc -> KernelVariant.OLCRTC
        is KernelConfig.Webdav -> KernelVariant.WEBDAV
    }

    fun fillDefaults(): ClientConfig {
        val cleanedUser = ValidatorUtils.cleanProxyString(socksUser)
        val cleanedPass = socksPass.trim()

        val validListen = if (ValidatorUtils.isValidHostPort(listenAddr)) listenAddr else DEFAULT_LISTEN_ADDR
        val validSocks = if (ValidatorUtils.isValidHostPort(socksAddr)) socksAddr else DEFAULT_SOCKS_ADDR

        var current = this.copy(
            listenAddr = validListen,
            socksAddr = validSocks,
            socksUser = cleanedUser,
            socksPass = cleanedPass,
            goDnsGo = goDnsGo
        )

        if (current.isSocksAuthEnabled && (current.socksUser.isBlank() || current.socksPass.isBlank())) {
            val allowed = ('A'..'Z') + ('a'..'z') + ('0'..'9')
            current = current.copy(
                socksUser = current.socksUser.ifBlank { (1..8).map { allowed.random() }.joinToString("") },
                socksPass = current.socksPass.ifBlank { (1..12).map { allowed.random() }.joinToString("") }
            )
        }
        return current.copy(
            kernelConfig = when (val k = kernelConfig) {
                is KernelConfig.Turnable -> KernelConfig.Turnable(k.config.sanitize())
                is KernelConfig.Olcrtc -> KernelConfig.Olcrtc(k.config.fillDefaults())
                is KernelConfig.Webdav -> KernelConfig.Webdav(k.config.fillDefaults())
            }
        )
    }

    val connectableAddress: String get() = listenAddr.replace("0.0.0.0:", "127.0.0.1:")

    fun getValidationErrorResId(): Int? = when (val k = kernelConfig) {
        is KernelConfig.Turnable -> if (!k.config.isValid()) R.string.error_settings_empty else null
        is KernelConfig.Olcrtc -> if (!k.config.isValid()) R.string.error_settings_empty else null
        is KernelConfig.Webdav -> if (!k.config.isValid()) R.string.error_settings_empty else null
    }

    val isValid: Boolean get() = getValidationErrorResId() == null

    fun getKernelDescription(context: Context): String = when (val k = kernelConfig) {
        is KernelConfig.Turnable -> {
            val route = k.config.routes.find { it.routeId == k.config.selectedRouteId }
            val routeName = route?.name?.ifBlank { route.routeId } ?: k.config.selectedRouteId
            context.getString(R.string.kernel_turnable) + " r:" + routeName
        }
        is KernelConfig.Olcrtc -> context.getString(R.string.kernel_olcrtc) + " " + k.config.providerDisplayName
        is KernelConfig.Webdav -> context.getString(R.string.kernel_webdav) + " " + k.config.webdav.replace("https://", "").replace("http://", "").take(20)
    }

    companion object {
        const val DEFAULT_LISTEN_ADDR = "127.0.0.1:9000"
        const val DEFAULT_SOCKS_ADDR = "127.0.0.1:2081"
    }
}

data class XraySettings(
    val socksBindAddress: String = DEFAULT_SOCKS_BIND_ADDRESS,
    val httpBindAddress: String = "",
    val isProxyAuthEnabled: Boolean = true,
    val proxyUser: String = "",
    val proxyPass: String = ""
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
            proxyPass = cleanedPass
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
    }
}

data class XrayConfig(
    val enabled: Boolean = false,
    val protocol: XrayConfiguration = XrayConfiguration.WIREGUARD
)

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

internal data class KernelSnapshot(
    @SerializedName("variant") val variant: String = KernelVariant.TURNABLE.name,
    @SerializedName("turnable") val turnable: TurnableConfig? = null,
    @SerializedName("olcrtc") val olcrtc: OlcrtcConfig? = null,
    @SerializedName("webdav") val webdav: WebdavConfig? = null
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
    @SerializedName("kernelVariant") val kernelVariant: KernelVariant = KernelVariant.TURNABLE,
    @SerializedName("turnableConfig") val turnableConfig: TurnableConfig = TurnableConfig(),
    @SerializedName("olcrtcConfig") val olcrtcConfig: OlcrtcConfig = OlcrtcConfig(),
    @SerializedName("webdavConfig") val webdavConfig: WebdavConfig = WebdavConfig(),
    @SerializedName("xrayProtocol", alternate = ["protocol", "xrayConfiguration"]) val xrayProtocol: XrayConfiguration = XrayConfiguration.WIREGUARD,
    @SerializedName("xrayEnabled", alternate = ["enabled"]) val xrayEnabled: Boolean = false,
    @SerializedName("wgConfig") val wgConfig: WgConfig = WgConfig(),
    @SerializedName("vlessConfig") val vlessConfig: VlessConfig = VlessConfig()
) {
    // Migration fields — set by Gson via reflection, not part of data class equality/copy
    @SerializedName("clientConfig") private val oldClientConfig: OldClientConfig? = null
    @SerializedName("xraySettings") private val oldXraySettings: Any? = null
    @SerializedName("xrayConfig") private val oldXrayConfig: Any? = null
    fun isEmpty(): Boolean = !turnableConfig.isValid() &&
            !olcrtcConfig.isValid() &&
            !webdavConfig.isValid() &&
            !wgConfig.isValid() &&
            !vlessConfig.isValid()

    fun sanitize(defaultName: String = "Profile"): Profile {
        val safeId = (id as Any?) as? String ?: java.util.UUID.randomUUID().toString()
        val safeName = (name as Any?) as? String ?: defaultName
        
        var kv = (kernelVariant as Any?) as? KernelVariant ?: KernelVariant.TURNABLE
        var tc = (turnableConfig as Any?) as? TurnableConfig ?: TurnableConfig()
        var oc = (olcrtcConfig as Any?) as? OlcrtcConfig ?: OlcrtcConfig()
        var wdc = (webdavConfig as Any?) as? WebdavConfig ?: WebdavConfig()
        var prot = (xrayProtocol as Any?) as? XrayConfiguration ?: XrayConfiguration.WIREGUARD
        var en = (xrayEnabled as Any?) as? Boolean ?: false

        // Migration from old nested ClientConfig format
        if (oldClientConfig != null && (tc.routes?.isEmpty() ?: true) && !oc.isValid() && !wdc.isValid()) {
            kv = oldClientConfig.kernelVariant
            tc = oldClientConfig.turnableConfig
            oc = oldClientConfig.olcrtcConfig
            wdc = oldClientConfig.webdavConfig
        }

        // Migration from old nested Xray format (objects)
        if (oldXraySettings != null && !en) {
            en = (oldXraySettings as? Map<*, *>)?.get("xrayEnabled") as? Boolean ?: false
        }
        if (oldXrayConfig != null && prot == XrayConfiguration.WIREGUARD) {
            val oldType = (oldXrayConfig as? Map<*, *>)?.get("xrayConfiguration")?.toString()
            if (oldType != null) try { prot = XrayConfiguration.valueOf(oldType) } catch(_: Exception) {}
        }

        // Deep safety for WG and VLESS
        val wgc = (wgConfig as Any? as? WgConfig ?: WgConfig()).fillDefaults()
        val vc = (vlessConfig as Any? as? VlessConfig ?: VlessConfig()).sanitize()

        val finalName = safeName.takeIf { it.isNotBlank() }?.take(100) ?: defaultName
        val finalOc = oc.sanitize().let {
            if (it.mimo.isBlank()) it.copy(mimo = finalName) else it
        }

        return copy(
            id = safeId,
            name = finalName,
            kernelVariant = kv,
            turnableConfig = tc.sanitize(),
            olcrtcConfig = finalOc,
            webdavConfig = wdc.fillDefaults(),
            xrayProtocol = prot,
            xrayEnabled = en,
            vlessConfig = vc,
            wgConfig = wgc
        )
    }

    fun getKernelDescription(context: Context): String = when (kernelVariant) {
        KernelVariant.TURNABLE -> {
            val route = turnableConfig.routes.find { it.routeId == turnableConfig.selectedRouteId }
            val routeName = route?.name?.ifBlank { route.routeId } ?: turnableConfig.selectedRouteId
            context.getString(R.string.kernel_turnable) + " r:" + routeName
        }

        KernelVariant.OLCRTC -> context.getString(R.string.kernel_olcrtc) + " " + olcrtcConfig.providerDisplayName
        KernelVariant.WEBDAV -> context.getString(R.string.kernel_webdav) + " " + webdavConfig.webdav.replace("https://", "").replace("http://", "").take(20)
    }
}

data class VpnSettings(
    val enabled: Boolean = false,
    val hideSystemApps: Boolean = true,
    val bypassMode: Boolean = true,
    val filteringEnabled: Boolean = true,
    val groupAppsByLetter: Boolean = true,
    val excludedApps: Set<String> = emptySet()
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
        val VPN_EXCLUDED_APPS = stringSetPreferencesKey("proxy_excluded_apps")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
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
        val PRIVACY_MODE = booleanPreferencesKey("privacy_mode")
        val GO_DNS_GO = booleanPreferencesKey("go_dns_go")

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

        val ACTIVE_KERNEL_JSON = stringPreferencesKey("active_kernel_json")
        val ACTIVE_XRAY_CONFIG_TYPE = stringPreferencesKey("active_xray_config_type")
        // Legacy keys — used only for migration on first launch after update
        private val LEGACY_KERNEL_VARIANT = stringPreferencesKey("active_kernel_variant")
        private val LEGACY_TURNABLE_JSON = stringPreferencesKey("active_turnable_json")
        private val LEGACY_OLCRTC_JSON = stringPreferencesKey("active_olcrtc_json")
        val ACTIVE_XRAY_ENABLED = booleanPreferencesKey("active_xray_enabled")
        val ACTIVE_WG_JSON = stringPreferencesKey("active_wg_json")
        val ACTIVE_VLESS_JSON = stringPreferencesKey("active_vless_json")
    }

    private fun <T> Flow<Preferences>.mapPref(key: Preferences.Key<T>, def: T): Flow<T> =
        this.map { it[key] ?: def }.distinctUntilChanged()

    val onboardingDoneFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(ONBOARDING_DONE, false)

    suspend fun hasActiveProfile(): Boolean =
        appCtx.internalDataStore.data.map { it[ACTIVE_KERNEL_JSON] != null || it[LEGACY_KERNEL_VARIANT] != null }.first()

    val themeModeFlow: Flow<ThemeMode> = appCtx.internalDataStore.data
        .map { ThemeMode.valueOf(it[THEME_MODE] ?: ThemeMode.SYSTEM.name) }
        .distinctUntilChanged()

    val dynamicThemeFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(DYNAMIC_THEME, true)
    val vpnSettingsFlow: Flow<VpnSettings> = appCtx.internalDataStore.data
        .map {
            VpnSettings(
                enabled = it[VPN_ENABLED] ?: false,
                hideSystemApps = it[VPN_HIDE_SYSTEM_APPS] ?: true,
                bypassMode = it[VPN_BYPASS_MODE] ?: true,
                filteringEnabled = it[VPN_FILTERING_ENABLED] ?: true,
                groupAppsByLetter = it[VPN_GROUP_APPS_BY_LETTER] ?: true,
                excludedApps = it[VPN_EXCLUDED_APPS] ?: emptySet()
            )
        }.distinctUntilChanged()

    val batteryNotificationDismissedFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(BATTERY_NOTIFICATION_DISMISSED, false)
    val appsExclusionHintShownFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(APPS_EXCLUSION_HINT_SHOWN, false)
    val allowUnstableUpdatesFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(ALLOW_UNSTABLE_UPDATES, false)
    val waitForNetworkFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(WAIT_FOR_NETWORK, true)
    val restartOnNetworkChangeFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(RESTART_ON_NETWORK_CHANGE, false)
    val captchaStyleModFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(CAPTCHA_STYLE_MOD, true)
    val captchaForceTintFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(CAPTCHA_FORCE_TINT, true)
    val privacyModeFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(PRIVACY_MODE, false)
    val appLanguageFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(APP_LANGUAGE, "system")

    val profilesFlow: Flow<List<Profile>> = appCtx.internalDataStore.data
        .map { p ->
            val json = p[PROFILES_JSON] ?: "[]"
            try {
                val list = gson.fromJson<List<Any>>(json, object : TypeToken<List<Any>>() {}.type) ?: emptyList()
                val defaultName = appCtx.getString(R.string.profile_default_name)
                
                list.mapNotNull { item ->
                    when (item) {
                        is Profile -> item.sanitize(defaultName)
                        is Map<*, *> -> {
                            // If TypeToken failed and we got a Map, try to convert it back to Profile
                            try {
                                val itemJson = gson.toJson(item)
                                gson.fromJson(itemJson, Profile::class.java)?.sanitize(defaultName)
                            } catch (_: Exception) { null }
                        }
                        else -> null
                    }
                }
            } catch (e: Exception) {
                com.wireturn.app.AppLogsState.addLog("Error loading profiles: ${e.message}")
                emptyList()
            }
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
            val kernelConfig = p[ACTIVE_KERNEL_JSON]?.let { json ->
                val snap = gson.fromJson(json, KernelSnapshot::class.java) ?: KernelSnapshot()
                val variant = try { KernelVariant.valueOf(snap.variant) } catch (_: Exception) { KernelVariant.TURNABLE }
                when (variant) {
                    KernelVariant.TURNABLE -> KernelConfig.Turnable(snap.turnable ?: TurnableConfig())
                    KernelVariant.OLCRTC -> KernelConfig.Olcrtc(snap.olcrtc ?: OlcrtcConfig())
                    KernelVariant.WEBDAV -> KernelConfig.Webdav(snap.webdav ?: WebdavConfig())
                }
            } ?: run {
                // Migration from legacy keys
                val variant = try { KernelVariant.valueOf(p[LEGACY_KERNEL_VARIANT] ?: KernelVariant.TURNABLE.name) } catch (_: Exception) { KernelVariant.TURNABLE }
                when (variant) {
                    KernelVariant.TURNABLE -> KernelConfig.Turnable(gson.fromJson(p[LEGACY_TURNABLE_JSON] ?: "{}", TurnableConfig::class.java) ?: TurnableConfig())
                    KernelVariant.OLCRTC -> KernelConfig.Olcrtc(gson.fromJson(p[LEGACY_OLCRTC_JSON] ?: "{}", OlcrtcConfig::class.java) ?: OlcrtcConfig())
                    KernelVariant.WEBDAV -> KernelConfig.Webdav(WebdavConfig())
                }
            }
            ClientConfig(
                listenAddr = p[CLIENT_LISTEN_ADDR] ?: ClientConfig.DEFAULT_LISTEN_ADDR,
                socksAddr = p[OLCRTC_SOCKS_ADDR] ?: ClientConfig.DEFAULT_SOCKS_ADDR,
                isSocksAuthEnabled = p[OLCRTC_SOCKS_AUTH_ENABLED] ?: true,
                socksUser = p[OLCRTC_SOCKS_USER] ?: "",
                socksPass = p[OLCRTC_SOCKS_PASS] ?: "",
                goDnsGo = p[GO_DNS_GO] ?: false,
                kernelConfig = kernelConfig
            )
        }.distinctUntilChanged()

    val xraySettingsFlow: Flow<XraySettings> = appCtx.internalDataStore.data
        .map { p ->
            XraySettings(
                socksBindAddress = p[XRAY_SOCKS_BIND] ?: XraySettings.DEFAULT_SOCKS_BIND_ADDRESS,
                httpBindAddress = p[XRAY_HTTP_BIND] ?: "",
                isProxyAuthEnabled = p[XRAY_AUTH_ENABLED] ?: true,
                proxyUser = p[XRAY_USER] ?: "",
                proxyPass = p[XRAY_PASS] ?: ""
            )
        }.distinctUntilChanged()

    val xrayConfigFlow: Flow<XrayConfig> = appCtx.internalDataStore.data
        .map { p ->
            XrayConfig(
                enabled = p[ACTIVE_XRAY_ENABLED] ?: false,
                protocol = try {
                    XrayConfiguration.valueOf(p[ACTIVE_XRAY_CONFIG_TYPE] ?: XrayConfiguration.WIREGUARD.name)
                } catch (_: Exception) {
                    XrayConfiguration.WIREGUARD
                }
            )
        }.distinctUntilChanged()

    val wgConfigFlow: Flow<WgConfig> = appCtx.internalDataStore.data
        .map { (gson.fromJson(it[ACTIVE_WG_JSON] ?: "{}", WgConfig::class.java) ?: WgConfig()) }
        .distinctUntilChanged()

    val vlessConfigFlow: Flow<VlessConfig> = appCtx.internalDataStore.data
        .map { (gson.fromJson(it[ACTIVE_VLESS_JSON] ?: "{}", VlessConfig::class.java) ?: VlessConfig()) }
        .distinctUntilChanged()

    private fun kernelSnapshotOf(profile: Profile): KernelSnapshot = when (profile.kernelVariant) {
        KernelVariant.TURNABLE -> KernelSnapshot(variant = KernelVariant.TURNABLE.name, turnable = profile.turnableConfig)
        KernelVariant.OLCRTC -> KernelSnapshot(variant = KernelVariant.OLCRTC.name, olcrtc = profile.olcrtcConfig)
        KernelVariant.WEBDAV -> KernelSnapshot(variant = KernelVariant.WEBDAV.name, webdav = profile.webdavConfig)
    }

    suspend fun saveFullProfile(id: String, profile: Profile) {
        appCtx.internalDataStore.edit { p ->
            p[CURRENT_PROFILE_ID] = id
            p[ACTIVE_KERNEL_JSON] = gson.toJson(kernelSnapshotOf(profile))
            p[ACTIVE_XRAY_CONFIG_TYPE] = profile.xrayProtocol.name
            p[ACTIVE_XRAY_ENABLED] = profile.xrayEnabled
            p[ACTIVE_WG_JSON] = gson.toJson(profile.wgConfig)
            p[ACTIVE_VLESS_JSON] = gson.toJson(profile.vlessConfig)
            p.remove(LEGACY_KERNEL_VARIANT); p.remove(LEGACY_TURNABLE_JSON); p.remove(LEGACY_OLCRTC_JSON)
        }
    }

    suspend fun saveProfiles(list: List<Profile>) {
        appCtx.internalDataStore.edit { it[PROFILES_JSON] = gson.toJson(list) }
    }

    suspend fun setVpnEnabled(v: Boolean) {
        appCtx.internalDataStore.edit { it[VPN_ENABLED] = v }
    }

    suspend fun saveExcludedApps(s: Set<String>) {
        appCtx.internalDataStore.edit { it[VPN_EXCLUDED_APPS] = s }
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

    suspend fun setPrivacyMode(v: Boolean) {
        appCtx.internalDataStore.edit { it[PRIVACY_MODE] = v }
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

    suspend fun saveVpnSettings(s: VpnSettings) {
        appCtx.internalDataStore.edit {
            it[VPN_ENABLED] = s.enabled
            it[VPN_HIDE_SYSTEM_APPS] = s.hideSystemApps
            it[VPN_BYPASS_MODE] = s.bypassMode
            it[VPN_FILTERING_ENABLED] = s.filteringEnabled
            it[VPN_GROUP_APPS_BY_LETTER] = s.groupAppsByLetter
            it[VPN_EXCLUDED_APPS] = s.excludedApps
        }
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

    suspend fun saveXrayConfig(c: XrayConfig) {
        appCtx.internalDataStore.edit {
            it[ACTIVE_XRAY_ENABLED] = c.enabled
            it[ACTIVE_XRAY_CONFIG_TYPE] = c.protocol.name
        }
    }

    suspend fun saveVlessConfig(c: VlessConfig) {
        appCtx.internalDataStore.edit { it[ACTIVE_VLESS_JSON] = gson.toJson(c) }
    }

    suspend fun saveXraySettings(s: XraySettings) {
        appCtx.internalDataStore.edit {
            it[XRAY_SOCKS_BIND] = s.socksBindAddress
            it[XRAY_HTTP_BIND] = s.httpBindAddress
            it[XRAY_AUTH_ENABLED] = s.isProxyAuthEnabled
            it[XRAY_USER] = s.proxyUser
            it[XRAY_PASS] = s.proxyPass
        }
    }

    suspend fun saveClientConfig(c: ClientConfig) {
        appCtx.internalDataStore.edit {
            it[CLIENT_LISTEN_ADDR] = c.listenAddr
            it[OLCRTC_SOCKS_ADDR] = c.socksAddr
            it[OLCRTC_SOCKS_AUTH_ENABLED] = c.isSocksAuthEnabled
            it[OLCRTC_SOCKS_USER] = c.socksUser
            it[OLCRTC_SOCKS_PASS] = c.socksPass
            it[GO_DNS_GO] = c.goDnsGo
            it[ACTIVE_KERNEL_JSON] = gson.toJson(when (val k = c.kernelConfig) {
                is KernelConfig.Turnable -> KernelSnapshot(variant = KernelVariant.TURNABLE.name, turnable = k.config)
                is KernelConfig.Olcrtc -> KernelSnapshot(variant = KernelVariant.OLCRTC.name, olcrtc = k.config)
                is KernelConfig.Webdav -> KernelSnapshot(variant = KernelVariant.WEBDAV.name, webdav = k.config)
            })
            it.remove(LEGACY_KERNEL_VARIANT); it.remove(LEGACY_TURNABLE_JSON); it.remove(LEGACY_OLCRTC_JSON)
        }
    }

    suspend fun resetAll() {
        appCtx.internalDataStore.edit { it.clear() }
    }

    suspend fun saveActiveProfilePart(profile: Profile) {
        appCtx.internalDataStore.edit { p ->
            p[ACTIVE_KERNEL_JSON] = gson.toJson(kernelSnapshotOf(profile))
            p[ACTIVE_XRAY_CONFIG_TYPE] = profile.xrayProtocol.name
            p[ACTIVE_XRAY_ENABLED] = profile.xrayEnabled
            p[ACTIVE_WG_JSON] = gson.toJson(profile.wgConfig)
            p[ACTIVE_VLESS_JSON] = gson.toJson(profile.vlessConfig)
            p.remove(LEGACY_KERNEL_VARIANT); p.remove(LEGACY_TURNABLE_JSON); p.remove(LEGACY_OLCRTC_JSON)
        }
    }

    suspend fun clearActiveProfile() {
        appCtx.internalDataStore.edit { p ->
            p.remove(ACTIVE_KERNEL_JSON)
            p.remove(ACTIVE_XRAY_CONFIG_TYPE)
            p.remove(ACTIVE_XRAY_ENABLED)
            p.remove(ACTIVE_WG_JSON)
            p.remove(ACTIVE_VLESS_JSON)
            p.remove(CURRENT_PROFILE_ID)
            p.remove(LEGACY_KERNEL_VARIANT); p.remove(LEGACY_TURNABLE_JSON); p.remove(LEGACY_OLCRTC_JSON)
        }
    }
}
