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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

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
        return copy(
            userUuid = userUuid?.take(200),
            username = username.take(200),
            platformId = platformId.take(200),
            callId = callId.take(200),
            type = type.take(100),
            encryption = encryption?.take(100),
            pubKey = pubKey?.take(500),
            gateway = gateway.take(500),
            proto = proto?.take(100),
            selectedRouteId = selectedRouteId.take(100),
            routes = routes.map { it.copy(
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

        val builder = StringBuilder("turnable://")
        if (!userUuid.isNullOrBlank()) {
            builder.append(android.net.Uri.encode(userUuid)).append(":")
        }
        builder.append(android.net.Uri.encode(callId)).append("@").append(platformId)

        targetRoutes.forEach {
            builder.append("/").append(android.net.Uri.encode(it.routeId))
        }

        val params = mutableListOf<String>()
        params.add("username=${android.net.Uri.encode(username)}")
        params.add("gateway=${android.net.Uri.encode(gateway)}")
        params.add("type=${android.net.Uri.encode(type)}")
        encryption?.let { params.add("encryption=${android.net.Uri.encode(it)}") }
        pubKey?.let { params.add("pub_key=${android.net.Uri.encode(it)}") }
        proto?.let { params.add("proto=${android.net.Uri.encode(it)}") }
        if (peers != 1) params.add("peers=$peers")
        if (forceTurn) params.add("forceturn=true")

        if (targetRoutes.size == 1) {
            val r = targetRoutes[0]
            if (r.socket != "udp") params.add("socket=${android.net.Uri.encode(r.socket)}")
            r.transport?.let { params.add("transport=${android.net.Uri.encode(it)}") }
        } else {
            targetRoutes.forEachIndexed { index, r ->
                val idx = index + 1
                if (r.socket != "udp") params.add("socket[$idx]=${android.net.Uri.encode(r.socket)}")
                r.transport?.let { params.add("transport[$idx]=${android.net.Uri.encode(it)}") }
            }
        }

        if (params.isNotEmpty()) {
            builder.append("?").append(params.joinToString("&"))
        }

        if (targetRoutes.isNotEmpty()) {
            builder.append("#").append(targetRoutes.joinToString(",") { android.net.Uri.encode(it.name) })
        }

        return builder.toString()
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
        fun parse(url: String): TurnableConfig? {
            if (!url.startsWith("turnable://", ignoreCase = true)) return null
            return try {
                val uri = url.toUri()
                val userInfo = uri.userInfo ?: ""
                val userParts = userInfo.split(":")

                val pathParts = uri.path?.split("/")?.filter { it.isNotBlank() } ?: emptyList()
                val fragment = uri.fragment ?: ""
                val routeNames = fragment.split(",").map { it.trim() }.filter { it.isNotBlank() }

                val type = uri.getQueryParameter("type") ?: "relay"

                val routes = pathParts.mapIndexed { index, routeId ->
                    val idx = index + 1
                    TurnableRoute(
                        routeId = routeId,
                        name = routeNames.getOrNull(index) ?: routeId,
                        socket = uri.getQueryParameter("socket[$idx]")
                            ?: (if (index == 0) uri.getQueryParameter("socket") else null)
                            ?: "udp",
                        transport = uri.getQueryParameter("transport[$idx]")
                            ?: (if (index == 0) uri.getQueryParameter("transport") else null)
                    )
                }

                TurnableConfig(
                    userUuid = userParts.getOrNull(0),
                    callId = userParts.getOrNull(1) ?: "",
                    platformId = uri.host ?: "",
                    username = uri.getQueryParameter("username") ?: "",
                    type = type,
                    encryption = uri.getQueryParameter("encryption"),
                    pubKey = uri.getQueryParameter("pub_key"),
                    peers = uri.getQueryParameter("peers")?.toIntOrNull() ?: 1,
                    forceTurn = uri.getQueryParameter("forceturn")?.toBoolean() ?: false,
                    gateway = uri.getQueryParameter("gateway") ?: "",
                    proto = uri.getQueryParameter("proto"),
                    routes = routes,
                    selectedRouteId = routes.firstOrNull()?.routeId ?: ""
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

    // vp8channel
    @SerializedName("vp8_fps") val vp8Fps: Int = 25,
    @SerializedName("vp8_batch") val vp8Batch: Int = 1,

    // seichannel
    @SerializedName("sei_fps") val seiFps: Int = 60,
    @SerializedName("sei_batch") val seiBatch: Int = 64,
    @SerializedName("sei_frag") val seiFrag: Int = 900,
    @SerializedName("sei_ack_ms") val seiAckMs: Int = 2000,

    // videochannel
    @SerializedName("video_codec") val videoCodec: String = "qrcode",
    @SerializedName("video_w") val videoW: Int = 1920,
    @SerializedName("video_h") val videoH: Int = 1080,
    @SerializedName("video_fps") val videoFps: Int = 30,
    @SerializedName("video_bitrate") val videoBitrate: String = "2M",
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
            videoCodec = videoCodec.take(100),
            videoBitrate = videoBitrate.take(20),
            videoHw = videoHw.take(50),
            videoQrRecovery = videoQrRecovery.take(20)
        )
    }

    fun isValid(): Boolean {
        return id.isNotBlank() && clientId.isNotBlank() && key.isNotBlank() && dns.isNotBlank()
    }
}

data class ClientConfig(
    @SerializedName("localPort") val localPort: String = DEFAULT_LOCAL_PORT,
    @SerializedName("isRawMode") val isRawMode: Boolean = false,
    @SerializedName("rawCommand") val rawCommand: String = "",
    @SerializedName("turnableUrl") val turnableUrl: String = "",
    @SerializedName("turnableConfig") val turnableConfig: TurnableConfig = TurnableConfig(),
    @SerializedName("olcrtcConfig") val olcrtcConfig: OlcrtcConfig = OlcrtcConfig(),
    @SerializedName("kernelVariant") val kernelVariant: KernelVariant = KernelVariant.TURNABLE
) {
    /** GSON can leave fields null if they are missing/invalid in JSON. This ensures safety. */
    fun migrateAndSanitize(): ClientConfig {
        var current = copy(
            localPort = (localPort ?: DEFAULT_LOCAL_PORT).take(100),
            rawCommand = (rawCommand ?: "").take(2000),
            turnableUrl = (turnableUrl ?: "").take(2000),
            turnableConfig = (turnableConfig ?: TurnableConfig()).sanitize(),
            olcrtcConfig = (olcrtcConfig ?: OlcrtcConfig()).sanitize(),
            kernelVariant = kernelVariant ?: KernelVariant.TURNABLE
        )
        
        // Migration logic: if we have a URL but no valid config, parse it once
        if (!current.turnableConfig.isValid() && current.turnableUrl.isNotBlank()) {
            val parsed = TurnableConfig.parse(current.turnableUrl)
            if (parsed != null) {
                current = current.copy(
                    turnableConfig = parsed,
                    turnableUrl = "" // Clear the URL after successful migration
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

    fun fillDefaults(): ClientConfig {
        return copy(
            localPort = localPort.ifBlank { DEFAULT_LOCAL_PORT }
        ).migrateAndSanitize()
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
    @SerializedName("directAddress") val directAddress: String = ""
) {
    fun isValid(): Boolean = vlessLink.isNotBlank() && com.wireturn.app.ui.ValidatorUtils.isValidVlessLink(vlessLink)

    fun sanitize(): VlessConfig {
        return copy(
            vlessLink = vlessLink.take(4096),
            directAddress = directAddress.take(500)
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
    @SerializedName("xrayConfiguration") val xrayConfiguration: XrayConfiguration = XrayConfiguration.WIREGUARD
) {
    fun fillDefaults(): XrayConfig {
        val isValid = socksBindAddress.isNotBlank() && com.wireturn.app.ui.ValidatorUtils.isValidHostPort(socksBindAddress)
        return if (isValid) this else copy(socksBindAddress = DEFAULT_SOCKS_BIND_ADDRESS)
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
    @SerializedName("mtu") val mtu: String = "",
    @SerializedName("publicKey") val publicKey: String = "",
    @SerializedName("endpoint") val endpoint: String = "",
    @SerializedName("persistentKeepalive") val persistentKeepalive: String = ""
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
        const val DEFAULT_ENDPOINT = "127.0.0.1:9000"
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
        return clientConfig == ClientConfig() &&
                wgConfig == WgConfig() &&
                vlessConfig == VlessConfig() &&
                xraySettings == XraySettings() &&
                xrayConfig == XrayConfig()
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
    private val gson = com.google.gson.Gson()

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
        val VLESS_LINK_HISTORY = stringPreferencesKey("vless_link_history")
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val XRAY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val XRAY_VPN_MODE = booleanPreferencesKey("proxy_vpn_mode")
        val VPN_HIDE_SYSTEM_APPS = booleanPreferencesKey("vpn_hide_system_apps")
        val VPN_BYPASS_MODE = booleanPreferencesKey("vpn_bypass_mode")
        val VPN_FILTERING_ENABLED = booleanPreferencesKey("vpn_filtering_enabled")
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
        val CLIENT_KERNEL_VARIANT = stringPreferencesKey("client_kernel_variant")
        val BATTERY_NOTIFICATION_DISMISSED = booleanPreferencesKey("battery_notification_dismissed")
        val APPS_EXCLUSION_HINT_SHOWN = booleanPreferencesKey("apps_exclusion_hint_shown")
        val ALLOW_UNSTABLE_UPDATES = booleanPreferencesKey("allow_unstable_updates")
        val WAIT_FOR_NETWORK = booleanPreferencesKey("wait_for_network")
        val RESTART_ON_NETWORK_CHANGE = booleanPreferencesKey("restart_on_network_change")
        val CAPTCHA_STYLE_MOD = booleanPreferencesKey("captcha_style_mod")
        val CAPTCHA_FORCE_TINT = booleanPreferencesKey("captcha_force_tint")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
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
            if (json.isBlank()) emptyList()
            else try {
                val type = object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type
                val rawList = gson.fromJson<List<Profile>>(json, type) ?: emptyList()
                rawList.map { it.sanitize() }
            } catch (_: Exception) { emptyList() }
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
                localPort = prefs[CLIENT_LOCAL_PORT] ?: ClientConfig.DEFAULT_LOCAL_PORT,
                isRawMode = prefs[CLIENT_IS_RAW] ?: false,
                rawCommand = prefs[CLIENT_RAW_CMD] ?: "",
                turnableUrl = prefs[CLIENT_TURNABLE_URL] ?: "",
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
                mtu = prefs[WIRE_MTU] ?: "",
                publicKey = prefs[WIRE_PUB_KEY] ?: "",
                endpoint = prefs[WIRE_ENDPOINT] ?: "",
                persistentKeepalive = prefs[WIRE_KEEPALIVE] ?: ""
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
                filteringEnabled = prefs[VPN_FILTERING_ENABLED] ?: true
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
                socksBindAddress = prefs[SOCKS_BIND] ?: XrayConfig.DEFAULT_SOCKS_BIND_ADDRESS,
                httpBindAddress = prefs[HTTP_BIND] ?: "",
                xrayConfiguration = XrayConfiguration.valueOf(prefs[XRAY_CONFIGURATION] ?: XrayConfiguration.WIREGUARD.name)
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
                directAddress = prefs[CLIENT_VLESS_DIRECT_ADDRESS] ?: ""
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

            // Xray Settings
            prefs[XRAY_ENABLED] = xraySettings.xrayEnabled
            prefs[XRAY_VPN_MODE] = xraySettings.xrayVpnMode

            // Xray Config
            prefs[SOCKS_BIND] = xrayConfig.socksBindAddress
            prefs[HTTP_BIND] = xrayConfig.httpBindAddress
            prefs[XRAY_CONFIGURATION] = xrayConfig.xrayConfiguration.name
        }
    }

    suspend fun saveClientConfig(config: ClientConfig) {
        val c = (config as ClientConfig?) ?: ClientConfig()
        context.dataStore.edit { prefs ->
            prefs[CLIENT_LOCAL_PORT] = (c.localPort as String?) ?: ClientConfig.DEFAULT_LOCAL_PORT
            prefs[CLIENT_IS_RAW] = (c.isRawMode as Boolean?) ?: false
            prefs[CLIENT_RAW_CMD] = (c.rawCommand as String?) ?: ""
            prefs[CLIENT_TURNABLE_URL] = (c.turnableUrl as String?) ?: ""
            prefs[CLIENT_TURNABLE_CONFIG] = gson.toJson(c.turnableConfig)
            prefs[CLIENT_OLCRTC_CONFIG] = gson.toJson(c.olcrtcConfig)
            prefs[CLIENT_KERNEL_VARIANT] = ((c.kernelVariant as KernelVariant?) ?: KernelVariant.TURNABLE).name
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

    suspend fun setAppLanguage(lang: String) {
        context.dataStore.edit { prefs -> prefs[APP_LANGUAGE] = lang }
    }

    suspend fun saveWgConfig(config: WgConfig) {
        val c = (config as WgConfig?) ?: WgConfig()
        context.dataStore.edit { prefs ->
            prefs[WIRE_PRIV_KEY] = (c.privateKey as String?) ?: ""
            prefs[WIRE_ADDRESS] = (c.address as String?) ?: ""
            prefs[WIRE_MTU] = (c.mtu as String?) ?: ""
            prefs[WIRE_PUB_KEY] = (c.publicKey as String?) ?: ""
            prefs[WIRE_ENDPOINT] = (c.endpoint as String?) ?: ""
            prefs[WIRE_KEEPALIVE] = (c.persistentKeepalive as String?) ?: ""
        }
    }

    suspend fun saveXraySettings(settings: XraySettings) {
        val s = (settings as XraySettings?) ?: XraySettings()
        context.dataStore.edit { prefs ->
            prefs[XRAY_ENABLED] = (s.xrayEnabled as Boolean?) ?: false
            prefs[XRAY_VPN_MODE] = (s.xrayVpnMode as Boolean?) ?: false
        }
    }

    suspend fun saveGlobalVpnSettings(settings: GlobalVpnSettings) {
        val s = (settings as GlobalVpnSettings?) ?: GlobalVpnSettings()
        context.dataStore.edit { prefs ->
            prefs[VPN_HIDE_SYSTEM_APPS] = (s.hideSystemApps as Boolean?) ?: true
            prefs[VPN_BYPASS_MODE] = (s.bypassMode as Boolean?) ?: true
            prefs[VPN_FILTERING_ENABLED] = (s.filteringEnabled as Boolean?) ?: true
        }
    }

    suspend fun saveExcludedApps(excludedApps: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[XRAY_EXCLUDED_APPS] = excludedApps
        }
    }

    suspend fun saveXrayConfig(config: XrayConfig) {
        val c = (config as XrayConfig?) ?: XrayConfig()
        context.dataStore.edit { prefs ->
            prefs[SOCKS_BIND] = (c.socksBindAddress as String?) ?: XrayConfig.DEFAULT_SOCKS_BIND_ADDRESS
            prefs[HTTP_BIND] = (c.httpBindAddress as String?) ?: ""
            prefs[XRAY_CONFIGURATION] = ((c.xrayConfiguration as XrayConfiguration?) ?: XrayConfiguration.WIREGUARD).name
        }
    }

    suspend fun saveVlessConfig(config: VlessConfig) {
        val c = (config as VlessConfig?) ?: VlessConfig()
        context.dataStore.edit { prefs ->
            prefs[CLIENT_VLESS_LINK] = (c.vlessLink as String?) ?: ""
            prefs[CLIENT_VLESS_USE_LOCAL_ADDRESS] = (c.vlessUseLocalAddress as Boolean?) ?: true
            prefs[CLIENT_VLESS_IS_DUAL_ROUTE] = (c.isDualRoute as Boolean?) ?: false
            prefs[CLIENT_VLESS_DIRECT_ADDRESS] = (c.directAddress as String?) ?: ""
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
}
