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
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
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
            else -> KernelConfig.Turnable()
        }
    }
}

enum class KernelVariant { TURNABLE, OLCRTC, WEBDAV, FREETURN }
enum class XrayConfiguration { WIREGUARD, VLESS }
enum class ThemeMode { DARK, LIGHT, SYSTEM }

sealed class KernelConfig {
    data class Turnable(val config: TurnableConfig = TurnableConfig()) : KernelConfig()
    data class Olcrtc(val config: OlcrtcConfig = OlcrtcConfig()) : KernelConfig()
    data class Webdav(val config: WebdavConfig = WebdavConfig()) : KernelConfig()
    data class FreeTurn(val config: FreeTurnConfig = FreeTurnConfig()) : KernelConfig()
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
    @SerializedName("platform_id") val platformId: String = "vk.com",
    @SerializedName("call_id") val callId: String = "",
    @SerializedName("type") val type: String = "relay",
    @SerializedName("encryption") val encryption: String? = "handshake",
    @SerializedName("pub_key") val pubKey: String? = null,
    @SerializedName("peers") val peers: Int = 1,
    @SerializedName("gateway") val gateway: String = "",
    @SerializedName("proto") val proto: String? = "srtp",
    @SerializedName("cloak") val cloak: String? = "none",
    @SerializedName("routes") val routes: List<TurnableRoute> = emptyList(),
    @SerializedName("selected_route_id") val selectedRouteId: String = ""
) {
    fun sanitize(): TurnableConfig = copy(
        userUuid = (userUuid as Any?)?.toString()?.trim()?.take(200),
        platformId = (platformId as Any?)?.toString()?.take(200) ?: "vk.com",
        callId = (callId as Any?)?.toString()?.take(200) ?: "",
        type = (type as Any?)?.toString()?.take(100) ?: "relay",
        encryption = (encryption as Any?)?.toString()?.take(100) ?: "handshake",
        pubKey = (pubKey as Any?)?.toString()?.take(4096),
        gateway = (gateway as Any?)?.toString()?.take(500) ?: "",
        proto = (proto as Any?)?.toString()?.take(100) ?: "srtp",
        cloak = (cloak as Any?)?.toString()?.take(100) ?: "none",
        selectedRouteId = (selectedRouteId as Any?)?.toString()?.take(100) ?: "",
        routes = (routes as List<TurnableRoute>?)?.map { it.sanitize() } ?: emptyList()
    )

    fun isValid(): Boolean = platformId.isNotBlank() &&
            callId.isNotBlank() &&
            gateway.isNotBlank() &&
            routes.isNotEmpty() &&
            !userUuid.isNullOrBlank()


    val platformDisplayName: String
        get() = getPlatformDisplayName(platformId)

    fun toUri(onlySelected: Boolean = false): String {
        val targetRoutes = if (onlySelected && selectedRouteId.isNotBlank()) {
            routes.filter { it.routeId == selectedRouteId }
        } else {
            routes
        }
        val builder = Uri.Builder().scheme("turnable")
        val userInfo = "${Uri.encode(userUuid ?: "")}:${Uri.encode(callId)}"
        builder.encodedAuthority("$userInfo@$platformId")
        // Each route is a single dash-joined path segment: route_id-socket-transport
        targetRoutes.forEach { builder.appendPath("${it.routeId}-${it.socket}-${it.transport ?: ""}") }
        builder.appendQueryParameter("type", type)
        builder.appendQueryParameter("gateway", gateway)
        proto?.let { builder.appendQueryParameter("proto", it) }
        cloak?.let { builder.appendQueryParameter("cloak", it) }
        builder.appendQueryParameter("peers", peers.toString())
        encryption?.let { builder.appendQueryParameter("encryption", it) }
        pubKey?.let { builder.appendQueryParameter("pub_key", it) }
        if (selectedRouteId.isNotBlank()) builder.appendQueryParameter("selected_route_id", selectedRouteId)
        if (targetRoutes.isNotEmpty()) {
            builder.fragment(targetRoutes.joinToString(", ") { it.name.ifBlank { it.routeId } })
        }
        return builder.build().toString()
    }

    companion object {
        fun getPlatformDisplayName(platformId: String): String = when (platformId) {
            "vk.com" -> "VK"
            else -> platformId
        }
        fun parse(url: String, current: TurnableConfig = TurnableConfig()): TurnableConfig? {
            if (!url.startsWith("turnable://", ignoreCase = true)) return null
            return try {
                val uri = Uri.parse(url)
                val userParts = (uri.encodedUserInfo ?: "").split(":").map { Uri.decode(it) }
                val userUuid = if (userParts.size > 1) userParts[0].takeIf { it.isNotBlank() } else null
                val callId = if (userParts.size > 1) userParts[1] else userParts.getOrNull(0) ?: ""
                val pathParts = (uri.encodedPath ?: "").split("/").filter { it.isNotBlank() }.map { Uri.decode(it) }
                // Each path segment is route_id-socket-transport; only route_id may itself contain dashes
                val routeNames = (uri.fragment ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                val routes = pathParts.mapIndexed { index, segment ->
                    val parts = segment.split("-")
                    val routeId: String
                    val socket: String
                    val transport: String?
                    if (parts.size >= 3) {
                        transport = parts.last().ifBlank { null }
                        socket = parts[parts.size - 2]
                        routeId = parts.subList(0, parts.size - 2).joinToString("-")
                    } else {
                        routeId = segment
                        socket = "udp"
                        transport = null
                    }
                    TurnableRoute(
                        routeId = routeId,
                        name = routeNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: routeId,
                        socket = socket,
                        transport = transport
                    )
                }
                current.copy(
                    userUuid = userUuid ?: current.userUuid,
                    callId = callId.ifBlank { current.callId },
                    platformId = uri.host ?: current.platformId,
                    type = uri.getQueryParameter("type") ?: current.type,
                    encryption = uri.getQueryParameter("encryption") ?: current.encryption,
                    pubKey = uri.getQueryParameter("pub_key") ?: current.pubKey,
                    peers = uri.getQueryParameter("peers")?.toIntOrNull() ?: current.peers,
                    gateway = uri.getQueryParameter("gateway") ?: current.gateway,
                    proto = uri.getQueryParameter("proto") ?: current.proto,
                    cloak = uri.getQueryParameter("cloak") ?: current.cloak,
                    routes = routes.ifEmpty { current.routes },
                    selectedRouteId = uri.getQueryParameter("selected_route_id")
                        ?: (if (routes.isNotEmpty()) routes.firstOrNull()?.routeId ?: "" else current.selectedRouteId)
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
    @SerializedName("video_qr_recovery") val videoQrRecovery: String = "low",
    @SerializedName("video_qr_size") val videoQrSize: Int = 0,
    @SerializedName("video_tile_module") val videoTileModule: Int = 4,
    @SerializedName("video_tile_rs") val videoTileRs: Int = 20,
    @SerializedName("restart_on_connection_errors") val restartOnConnectionErrors: Boolean = true
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

    fun isValid(): Boolean {
        if (id.isBlank() || key.isBlank() || dns.isBlank()) return false
        if (transport == "videochannel") {
            if (videoW !in VIDEO_MIN_DIMENSION..VIDEO_MAX_DIMENSION) return false
            if (videoH !in VIDEO_MIN_DIMENSION..VIDEO_MAX_DIMENSION) return false
            if (videoCodec == "qrcode" && videoQrRecovery.isNotBlank() &&
                videoQrRecovery !in VIDEO_QR_RECOVERY_LEVELS
            ) {
                return false
            }
        }
        return true
    }
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
        // Значения, которые понимает визуальный кодек olcrtc; всё остальное отклоняется при старте.
        val VIDEO_QR_RECOVERY_LEVELS = listOf("low", "medium", "high", "highest")
        const val VIDEO_MIN_DIMENSION = 16
        const val VIDEO_MAX_DIMENSION = 8192

        fun getTransportDisplayName(transport: String, short: Boolean = false): String = when (transport) {
            "datachannel" -> if (short) "DC" else "DataChannel"
            "vp8channel" -> if (short) "VP8C" else "VP8Channel"
            "seichannel" -> if (short) "SEIC" else "SEIChannel"
            "videochannel" -> if (short) "VC" else "VideoChannel"
            else -> transport
        }

        fun parse(url: String, current: OlcrtcConfig = OlcrtcConfig()): OlcrtcConfig? {
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
                var cfg = current.copy(
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
    @SerializedName("read_max") val readMax: String = "8",
    @SerializedName("encrypt") val encrypt: Boolean = false
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
        if (encrypt) builder.appendQueryParameter("enc", "1")

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
                    readMax = uri.getQueryParameter("read-max") ?: current.readMax,
                    encrypt = uri.getQueryParameter("enc") == "1"
                )
            } catch (_: Exception) {
                return null
            }
        }

        fun formatHost(webdavUrl: String): String {
            val uri = try { Uri.parse(webdavUrl) } catch (_: Exception) { return webdavUrl.take(20) }
            val host = uri.host ?: return webdavUrl.take(20)
            val port = if (uri.port != -1) ":${uri.port}" else ""

            // Simple check for IPv4 or IPv6
            val isIp = host.all { it.isDigit() || it == '.' || it == ':' || it.lowercaseChar() in 'a'..'f' || it == '[' || it == ']' }

            if (isIp) return "$host$port"

            val parts = host.split('.')
            return if (parts.size >= 2) {
                // webdav.yandex.ru -> Yandex
                parts[parts.size - 2].replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() } + port
            } else {
                host.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() } + port
            }
        }
    }
}

data class FreeTurnConfig(
    @SerializedName("provider") val provider: String = "vk",
    @SerializedName("peer") val peer: String = "",
    @SerializedName("links") val links: String = "",
    @SerializedName("n") val n: Int = 10,
    @SerializedName("transport") val transport: String = "tcp",
    @SerializedName("mode") val mode: String = "udp",
    @SerializedName("bond") val bond: Boolean = false,
    @SerializedName("obf_profile") val obfProfile: String = "none",
    @SerializedName("obf_key") val obfKey: String = "",
    @SerializedName("obf_timing") val obfTiming: String = "0",
    @SerializedName("streams_per_cred") val streamsPerCred: Int = 10,
    @SerializedName("manual_captcha") val manualCaptcha: Boolean = false,
    @SerializedName("platform") val platform: String = "desktop",
    @SerializedName("dns_mode") val dnsMode: String = "auto",
    @SerializedName("dns_servers") val dnsServers: String = "",
    @SerializedName("client_id") val clientId: String = "",
    @SerializedName("sub") val sub: String = ""
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
            if (links.isNotBlank()) addProperty("links", links)
            if (sub.isNotBlank()) addProperty("sub", sub)
            if (transport != "tcp") addProperty("transport", transport)
            if (mode != "udp") addProperty("mode", mode)
            if (bond) addProperty("bond", true)
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
            if (!profileName.isNullOrBlank()) addProperty("name", profileName)
        }
        val bytes = json.toString().toByteArray()
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return "freeturn://$base64"
    }

    companion object {
        fun parse(url: String, current: FreeTurnConfig = FreeTurnConfig()): FreeTurnConfig? {
            if (!url.startsWith("freeturn://", ignoreCase = true)) return null
            return try {
                val base64 = url.substringAfter("freeturn://")
                val jsonStr = String(android.util.Base64.decode(base64, android.util.Base64.URL_SAFE))
                val json = Gson().fromJson(jsonStr, JsonObject::class.java)
                
                if (json.get("v")?.asInt != 1) return null

                FreeTurnConfig(
                    provider = json.get("provider")?.asString ?: current.provider,
                    peer = json.get("peer")?.asString ?: current.peer,
                    links = json.get("links")?.asString ?: json.get("link")?.asString ?: current.links,
                    sub = json.get("sub")?.asString ?: current.sub,
                    obfProfile = json.get("obf")?.asString ?: current.obfProfile,
                    obfKey = json.get("key")?.asString ?: current.obfKey,
                    obfTiming = json.get("obft")?.asString ?: current.obfTiming,
                    n = json.get("n")?.asInt ?: current.n,
                    transport = json.get("transport")?.asString ?: current.transport,
                    mode = json.get("mode")?.asString ?: current.mode,
                    bond = json.get("bond")?.asBoolean ?: current.bond,
                    streamsPerCred = json.get("spc")?.asInt ?: current.streamsPerCred,
                    clientId = json.get("cid")?.asString ?: current.clientId,
                    dnsMode = json.get("dns")?.asString ?: current.dnsMode,
                    dnsServers = json.get("dnss")?.asString ?: current.dnsServers,
                    manualCaptcha = json.get("mcap")?.asBoolean ?: current.manualCaptcha,
                    platform = json.get("plt")?.asString ?: current.platform
                )
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
    val goDnsGo: Boolean = false,
    val useCustomCerts: Boolean = true,
    val kernelConfig: KernelConfig = KernelConfig.Turnable(),
    @SerializedName("turnableUrl") val turnableUrl: String = "",
    @SerializedName("olcrtcUrl") val olcrtcUrl: String = "",
    @SerializedName("webdavUrl") val webdavUrl: String = "",
    @SerializedName("freeturnUrl") val freeturnUrl: String = ""
) {
    val kernelVariant: KernelVariant get() = when (kernelConfig) {
        is KernelConfig.Turnable -> KernelVariant.TURNABLE
        is KernelConfig.Olcrtc -> KernelVariant.OLCRTC
        is KernelConfig.Webdav -> KernelVariant.WEBDAV
        is KernelConfig.FreeTurn -> KernelVariant.FREETURN
    }

    fun fillDefaults(): ClientConfig {
        val cleanedUser = ValidatorUtils.cleanProxyString(socksUser)
        val cleanedPass = socksPass.trim()

        val validListen = if (ValidatorUtils.isValidHostPort(listenAddr)) listenAddr else DEFAULT_LISTEN_ADDR
        val validSocks = if (ValidatorUtils.isValidHostPort(socksAddr)) socksAddr else DEFAULT_SOCKS_ADDR

        var currentKc = kernelConfig
        if (turnableUrl.isNotBlank()) {
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
            }
        )
    }

    val connectableAddress: String get() = listenAddr.replace("0.0.0.0:", "127.0.0.1:")

    fun getValidationErrorResId(): Int? = when (val k = kernelConfig) {
        is KernelConfig.Turnable -> if (!k.config.isValid()) R.string.error_settings_empty else null
        is KernelConfig.Olcrtc -> when {
            !k.config.isValid() -> R.string.error_settings_empty
            !isSocksAuthEnabled && !ValidatorUtils.isLoopbackHostPort(socksAddr) -> R.string.error_olcrtc_socks_public_requires_auth
            else -> null
        }
        is KernelConfig.Webdav -> if (!k.config.isValid()) R.string.error_settings_empty else null
        is KernelConfig.FreeTurn -> if (!k.config.isValid()) R.string.error_settings_empty else null
    }

    val isValid: Boolean get() = getValidationErrorResId() == null

    fun getKernelDescription(context: Context): String = when (val k = kernelConfig) {
        is KernelConfig.Turnable -> {
            val route = k.config.routes.find { it.routeId == k.config.selectedRouteId }
            val routeName = route?.name?.ifBlank { route.routeId } ?: k.config.selectedRouteId
            context.getString(R.string.kernel_turnable) + " r:" + routeName
        }
        is KernelConfig.Olcrtc -> context.getString(R.string.kernel_olcrtc) + " " + k.config.providerDisplayName
        is KernelConfig.Webdav -> context.getString(R.string.kernel_webdav) + " " + WebdavConfig.formatHost(k.config.webdav)
        is KernelConfig.FreeTurn -> context.getString(R.string.kernel_freeturn) + " " + k.config.peer.take(15)
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
    @SerializedName("isDualRoute") val isDualRoute: Boolean = false,
    @SerializedName("directAddress") val directAddress: String = "",
    @SerializedName("hcInterval") val hcInterval: String = "30",
    @SerializedName("mux") val mux: String = "0"
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
    @SerializedName("webdav") val webdav: WebdavConfig? = null,
    @SerializedName("freeturn") val freeturn: FreeTurnConfig? = null
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
    @SerializedName("subscriptionId") val subscriptionId: String? = null
) {
    // --- STABLE INPUT FIELDS (Used for profile generation and deep linking) ---
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

    val kernelVariant: KernelVariant get() = when (kernelConfig) {
        is KernelConfig.Turnable -> KernelVariant.TURNABLE
        is KernelConfig.Olcrtc -> KernelVariant.OLCRTC
        is KernelConfig.Webdav -> KernelVariant.WEBDAV
        is KernelConfig.FreeTurn -> KernelVariant.FREETURN
    }

    val turnableConfig: TurnableConfig get() = (kernelConfig as? KernelConfig.Turnable)?.config ?: TurnableConfig()
    val olcrtcConfig: OlcrtcConfig get() = (kernelConfig as? KernelConfig.Olcrtc)?.config ?: OlcrtcConfig()
    val webdavConfig: WebdavConfig get() = (kernelConfig as? KernelConfig.Webdav)?.config ?: WebdavConfig()
    val freeturnConfig: FreeTurnConfig get() = (kernelConfig as? KernelConfig.FreeTurn)?.config ?: FreeTurnConfig()

    fun isEmpty(): Boolean = when (val k = kernelConfig) {
        is KernelConfig.Turnable -> !k.config.isValid()
        is KernelConfig.Olcrtc -> !k.config.isValid()
        is KernelConfig.Webdav -> !k.config.isValid()
        is KernelConfig.FreeTurn -> !k.config.isValid()
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
        if (turnableUrl?.isNotBlank() == true) {
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
        if (oldXrayConfig != null && oldXrayConfig.isJsonObject && (prot == null || prot == XrayConfiguration.WIREGUARD)) {
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
        }

        return copy(
            id = safeId,
            name = finalName,
            kernelConfig = sanitizedKc,
            xrayProtocol = prot ?: XrayConfiguration.WIREGUARD,
            xrayEnabled = en,
            vlessConfig = vc,
            wgConfig = wgc
        )
    }

    fun getKernelDescription(context: Context): String = when (val k = kernelConfig) {
        is KernelConfig.Turnable -> {
            val route = k.config.routes.find { it.routeId == k.config.selectedRouteId }
            val routeName = route?.name?.ifBlank { route.routeId } ?: k.config.selectedRouteId
            context.getString(R.string.kernel_turnable) + " r:" + routeName
        }

        is KernelConfig.Olcrtc -> context.getString(R.string.kernel_olcrtc) + " " + k.config.providerDisplayName
        is KernelConfig.Webdav -> context.getString(R.string.kernel_webdav) + " " + WebdavConfig.formatHost(k.config.webdav)
        is KernelConfig.FreeTurn -> context.getString(R.string.kernel_freeturn) + " m:" + k.config.mode.uppercase()
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

data class Subscription(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("url") val url: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("updatedAt") val updatedAt: Long = 0,
    @SerializedName("bytesUsed") val bytesUsed: Long = 0,
    @SerializedName("bytesTotal") val bytesTotal: Long = 0,
    @SerializedName("autoUpdate") val autoUpdate: Boolean = false,
    @SerializedName("updateIntervalMin") val updateIntervalMin: Int = 1440 // Default 24h
)

data class ProfileBundle(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("profiles") val profiles: List<Profile> = emptyList(),
    @SerializedName("updatedAt") val updatedAt: Long? = null,
    @SerializedName("bytesUsed") val bytesUsed: Long? = null,
    @SerializedName("bytesTotal") val bytesTotal: Long? = null
)

class AppPreferences(val context: Context) {
    private val appCtx = context.applicationContext
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter())
        .create()

    companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
        val SUBSCRIPTIONS_JSON = stringPreferencesKey("subscriptions_json")
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
        val USE_CUSTOM_CERTS = booleanPreferencesKey("use_custom_certs")

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

    val subscriptionsFlow: Flow<List<Subscription>> = appCtx.internalDataStore.data
        .map { p ->
            val json = p[SUBSCRIPTIONS_JSON] ?: "[]"
            try {
                gson.fromJson<List<Subscription>>(json, object : TypeToken<List<Subscription>>() {}.type) ?: emptyList()
            } catch (e: Exception) {
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
                    KernelVariant.FREETURN -> KernelConfig.FreeTurn(snap.freeturn ?: FreeTurnConfig())
                }
            } ?: run {
                // Migration from legacy keys
                val variant = try { KernelVariant.valueOf(p[LEGACY_KERNEL_VARIANT] ?: KernelVariant.TURNABLE.name) } catch (_: Exception) { KernelVariant.TURNABLE }
                when (variant) {
                    KernelVariant.TURNABLE -> KernelConfig.Turnable(gson.fromJson(p[LEGACY_TURNABLE_JSON] ?: "{}", TurnableConfig::class.java) ?: TurnableConfig())
                    KernelVariant.OLCRTC -> KernelConfig.Olcrtc(gson.fromJson(p[LEGACY_OLCRTC_JSON] ?: "{}", OlcrtcConfig::class.java) ?: OlcrtcConfig())
                    KernelVariant.WEBDAV -> KernelConfig.Webdav(WebdavConfig())
                    KernelVariant.FREETURN -> KernelConfig.FreeTurn(FreeTurnConfig())
                }
            }
            ClientConfig(
                listenAddr = p[CLIENT_LISTEN_ADDR] ?: ClientConfig.DEFAULT_LISTEN_ADDR,
                socksAddr = p[OLCRTC_SOCKS_ADDR] ?: ClientConfig.DEFAULT_SOCKS_ADDR,
                isSocksAuthEnabled = p[OLCRTC_SOCKS_AUTH_ENABLED] ?: true,
                socksUser = p[OLCRTC_SOCKS_USER] ?: "",
                socksPass = p[OLCRTC_SOCKS_PASS] ?: "",
                goDnsGo = p[GO_DNS_GO] ?: false,
                useCustomCerts = p[USE_CUSTOM_CERTS] ?: true,
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

    private fun kernelSnapshotOf(profile: Profile): KernelSnapshot = when (val k = profile.kernelConfig) {
        is KernelConfig.Turnable -> KernelSnapshot(variant = KernelVariant.TURNABLE.name, turnable = k.config)
        is KernelConfig.Olcrtc -> KernelSnapshot(variant = KernelVariant.OLCRTC.name, olcrtc = k.config)
        is KernelConfig.Webdav -> KernelSnapshot(variant = KernelVariant.WEBDAV.name, webdav = k.config)
        is KernelConfig.FreeTurn -> KernelSnapshot(variant = KernelVariant.FREETURN.name, freeturn = k.config)
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

    suspend fun saveSubscriptions(list: List<Subscription>) {
        appCtx.internalDataStore.edit { it[SUBSCRIPTIONS_JSON] = gson.toJson(list) }
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
            it[USE_CUSTOM_CERTS] = c.useCustomCerts
            it[ACTIVE_KERNEL_JSON] = gson.toJson(when (val k = c.kernelConfig) {
                is KernelConfig.Turnable -> KernelSnapshot(variant = KernelVariant.TURNABLE.name, turnable = k.config)
                is KernelConfig.Olcrtc -> KernelSnapshot(variant = KernelVariant.OLCRTC.name, olcrtc = k.config)
                is KernelConfig.Webdav -> KernelSnapshot(variant = KernelVariant.WEBDAV.name, webdav = k.config)
                is KernelConfig.FreeTurn -> KernelSnapshot(variant = KernelVariant.FREETURN.name, freeturn = k.config)
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
