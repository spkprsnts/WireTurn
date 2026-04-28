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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

enum class DCType {
    SALUTE_JAZZ, WB_STREAM
}

enum class KernelVariant {
    VK_TURN_PROXY, TURNABLE
}

enum class XrayConfiguration {
    WIREGUARD, VLESS
}

data class ClientConfig(
    @SerializedName("serverAddress") val serverAddress: String = "",
    @SerializedName("vkLink") val vkLink: String = "",
    @SerializedName("wbstreamUuid") val wbstreamUuid: String = "",
    @SerializedName("threads") val threads: Int = 4,
    @SerializedName("useUdp") val useUdp: Boolean = false,
    @SerializedName("noDtls") val noDtls: Boolean = false,
    @SerializedName("manualCaptcha") val manualCaptcha: Boolean = false,
    @SerializedName("localPort") val localPort: String = DEFAULT_LOCAL_PORT,
    @SerializedName("isRawMode") val isRawMode: Boolean = false,
    @SerializedName("rawCommand") val rawCommand: String = "",
    @SerializedName("vlessMode") val vlessMode: Boolean = false,
    @SerializedName("dcMode") val dcMode: Boolean = false,
    @SerializedName("forceTurnPort443") val forceTurnPort443: Boolean = false,
    @SerializedName("dcType") val dcType: DCType = DCType.SALUTE_JAZZ,
    @SerializedName("jazzCreds") val jazzCreds: String = "",
    @SerializedName("turnableUrl") val turnableUrl: String = "",
    @SerializedName("kernelVariant") val kernelVariant: KernelVariant = KernelVariant.VK_TURN_PROXY
) {
    fun getValidationErrorResId(): Int? {
        if (isRawMode) {
            return if (rawCommand.isBlank()) com.wireturn.app.R.string.error_raw_empty else null
        }

        return if (dcMode) {
            when (dcType) {
                DCType.SALUTE_JAZZ -> {
                    if (jazzCreds.isBlank()) com.wireturn.app.R.string.error_settings_empty else null
                }
                DCType.WB_STREAM -> {
                    if (wbstreamUuid.isNotBlank()) null
                    else com.wireturn.app.R.string.error_settings_empty
                }
            }
        } else {
            when (kernelVariant) {
                KernelVariant.VK_TURN_PROXY -> {
                    if (serverAddress.isBlank() || vkLink.isBlank()) com.wireturn.app.R.string.error_settings_empty else null
                }
                KernelVariant.TURNABLE -> {
                    if (turnableUrl.isBlank()) com.wireturn.app.R.string.error_settings_empty else null
                }
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
        )
    }

    companion object {
        const val DEFAULT_LOCAL_PORT = "127.0.0.1:9000"
    }
}

data class VlessConfig(
    @SerializedName("vlessLink") val vlessLink: String = "",
    @SerializedName("vlessUseLocalAddress") val vlessUseLocalAddress: Boolean = true
) {
    fun isValid(): Boolean = vlessLink.isNotBlank() && com.wireturn.app.ui.ValidatorUtils.isValidVlessLink(vlessLink)
}

data class XraySettings(
    @SerializedName("xrayEnabled") val xrayEnabled: Boolean = false,
    @SerializedName("xrayVpnMode") val xrayVpnMode: Boolean = false
)

data class GlobalVpnSettings(
    @SerializedName("hideSystemApps") val hideSystemApps: Boolean = true,
    @SerializedName("bypassMode") val bypassMode: Boolean = true,
    @SerializedName("filteringEnabled") val filteringEnabled: Boolean = true
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
            mtu = mtu.ifBlank { DEFAULT_MTU },
            endpoint = endpoint.ifBlank { DEFAULT_ENDPOINT },
            persistentKeepalive = persistentKeepalive.ifBlank { DEFAULT_PERSISTENT_KEEPALIVE }
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
)

// P2-3 / P3-6: всегда используем applicationContext, чтобы lazy-init encryptedPrefs
class AppPreferences(context: Context) {
    private val context = context.applicationContext
    private val gson = com.google.gson.Gson()

    companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
        val CURRENT_PROFILE_ID = stringPreferencesKey("current_profile_id")
        val CLIENT_SERVER_ADDR = stringPreferencesKey("client_server_addr")
        val CLIENT_VK_LINK = stringPreferencesKey("client_vk_link")
        val CLIENT_WBSTREAM_UUID = stringPreferencesKey("client_wbstream_uuid")
        val CLIENT_THREADS = intPreferencesKey("client_threads")
        val CLIENT_UDP = booleanPreferencesKey("client_udp")
        val CLIENT_NO_DTLS = booleanPreferencesKey("client_no_dtls")
        val CLIENT_MANUAL_CAPTCHA = booleanPreferencesKey("client_manual_captcha")
        val CLIENT_LOCAL_PORT = stringPreferencesKey("client_local_port")
        val CLIENT_IS_RAW = booleanPreferencesKey("client_is_raw")
        val CLIENT_RAW_CMD = stringPreferencesKey("client_raw_cmd")
        val CLIENT_VLESS = booleanPreferencesKey("client_vless")
        val CLIENT_VLESS_LINK = stringPreferencesKey("client_vless_link")
        val CLIENT_VLESS_USE_LOCAL_ADDRESS = booleanPreferencesKey("client_vless_use_local_address")
        val VLESS_LINK_HISTORY = stringPreferencesKey("vless_link_history")
        val CLIENT_DC_MODE = booleanPreferencesKey("client_dc_mode")
        val CLIENT_FORCE_PORT_443 = booleanPreferencesKey("client_force_port_443")
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
        val VK_LINK_HISTORY = stringPreferencesKey("vk_link_history")
        val WBSTREAM_UUID_HISTORY = stringPreferencesKey("wbstream_uuid_history")
        val SERVER_ADDR_HISTORY = stringPreferencesKey("server_addr_history")
        val JAZZ_CREDS_HISTORY = stringPreferencesKey("jazz_creds_history")
        val CLIENT_DC_TYPE = stringPreferencesKey("client_dc_type")
        val CLIENT_JAZZ_CREDS = stringPreferencesKey("client_jazz_creds")
        val CLIENT_TURNABLE_URL = stringPreferencesKey("client_turnable_url")
        val CLIENT_KERNEL_VARIANT = stringPreferencesKey("client_kernel_variant")
        val TURNABLE_URL_HISTORY = stringPreferencesKey("turnable_url_history")
        val BATTERY_NOTIFICATION_DISMISSED = booleanPreferencesKey("battery_notification_dismissed")
        val APPS_EXCLUSION_HINT_SHOWN = booleanPreferencesKey("apps_exclusion_hint_shown")
        val ALLOW_UNSTABLE_UPDATES = booleanPreferencesKey("allow_unstable_updates")
    }

    val profilesFlow: Flow<List<Profile>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val json = prefs[PROFILES_JSON] ?: ""
            if (json.isBlank()) emptyList()
            else try {
                val type = object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type
                val rawList = gson.fromJson<List<Profile>>(json, type) ?: emptyList()
                rawList.map { p ->
                    // GSON can bypass Kotlin's null-safety if fields are missing in JSON.
                    // We sanitize the object here to ensure all fields are non-null.
                    Profile(
                        id = (p.id as String?) ?: java.util.UUID.randomUUID().toString(),
                        name = (p.name as String?) ?: "Unnamed",
                        clientConfig = (p.clientConfig as ClientConfig?) ?: ClientConfig(),
                        xraySettings = (p.xraySettings as XraySettings?) ?: XraySettings(),
                        xrayConfig = (p.xrayConfig as XrayConfig?) ?: XrayConfig(),
                        wgConfig = (p.wgConfig as WgConfig?) ?: WgConfig(),
                        vlessConfig = (p.vlessConfig as VlessConfig?) ?: VlessConfig()
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

    val currentProfileIdFlow: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[CURRENT_PROFILE_ID] ?: "default" }

    val currentProfileNameFlow: Flow<String?> = combine(profilesFlow, currentProfileIdFlow) { profiles, id ->
        profiles.find { it.id == id }?.name
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
                serverAddress = prefs[CLIENT_SERVER_ADDR] ?: "",
                vkLink = prefs[CLIENT_VK_LINK] ?: "",
                wbstreamUuid = prefs[CLIENT_WBSTREAM_UUID] ?: "",
                threads = prefs[CLIENT_THREADS] ?: 4,
                useUdp = prefs[CLIENT_UDP] ?: false,
                noDtls = prefs[CLIENT_NO_DTLS] ?: false,
                manualCaptcha = prefs[CLIENT_MANUAL_CAPTCHA] ?: false,
                localPort = prefs[CLIENT_LOCAL_PORT] ?: ClientConfig.DEFAULT_LOCAL_PORT,
                isRawMode = prefs[CLIENT_IS_RAW] ?: false,
                rawCommand = prefs[CLIENT_RAW_CMD] ?: "",
                vlessMode = prefs[CLIENT_VLESS] ?: false,
                dcMode = prefs[CLIENT_DC_MODE] ?: false,
                forceTurnPort443 = prefs[CLIENT_FORCE_PORT_443] ?: false,
                dcType = DCType.valueOf(prefs[CLIENT_DC_TYPE] ?: DCType.SALUTE_JAZZ.name),
                jazzCreds = prefs[CLIENT_JAZZ_CREDS] ?: "",
                turnableUrl = prefs[CLIENT_TURNABLE_URL] ?: "",
                kernelVariant = KernelVariant.valueOf(prefs[CLIENT_KERNEL_VARIANT] ?: KernelVariant.VK_TURN_PROXY.name)
            ).fillDefaults()
        }

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

    val xraySettingsFlow: Flow<XraySettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            XraySettings(
                xrayEnabled = prefs[XRAY_ENABLED] ?: false,
                xrayVpnMode = prefs[XRAY_VPN_MODE] ?: false
            )
        }

    val globalVpnSettingsFlow: Flow<GlobalVpnSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            GlobalVpnSettings(
                hideSystemApps = prefs[VPN_HIDE_SYSTEM_APPS] ?: true,
                bypassMode = prefs[VPN_BYPASS_MODE] ?: true,
                filteringEnabled = prefs[VPN_FILTERING_ENABLED] ?: true
            )
        }

    val excludedAppsFlow: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[XRAY_EXCLUDED_APPS] ?: emptySet() }

    val xrayConfigFlow: Flow<XrayConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            XrayConfig(
                socksBindAddress = prefs[SOCKS_BIND] ?: XrayConfig.DEFAULT_SOCKS_BIND_ADDRESS,
                httpBindAddress = prefs[HTTP_BIND] ?: "",
                xrayConfiguration = XrayConfiguration.valueOf(prefs[XRAY_CONFIGURATION] ?: XrayConfiguration.WIREGUARD.name)
            )
        }

    val vlessConfigFlow: Flow<VlessConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            VlessConfig(
                vlessLink = prefs[CLIENT_VLESS_LINK] ?: "",
                vlessUseLocalAddress = prefs[CLIENT_VLESS_USE_LOCAL_ADDRESS] ?: true
            )
        }

    val onboardingDoneFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[ONBOARDING_DONE] ?: false }

    val dynamicThemeFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[DYNAMIC_THEME] ?: true }

    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            ThemeMode.valueOf(prefs[THEME_MODE] ?: ThemeMode.DARK.name)
        }

    val batteryNotificationDismissedFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[BATTERY_NOTIFICATION_DISMISSED] ?: false }

    val appsExclusionHintShownFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[APPS_EXCLUSION_HINT_SHOWN] ?: false }

    val allowUnstableUpdatesFlow: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> prefs[ALLOW_UNSTABLE_UPDATES] ?: false }

    val vkLinkHistoryFlow: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val historyString = prefs[VK_LINK_HISTORY] ?: ""
            if (historyString.isBlank()) emptyList()
            else historyString.split("|").filter { it.isNotBlank() }
        }

    val wbstreamUuidHistoryFlow: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val historyString = prefs[WBSTREAM_UUID_HISTORY] ?: ""
            if (historyString.isBlank()) emptyList()
            else historyString.split("|").filter { it.isNotBlank() }
        }

    val serverAddressHistoryFlow: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val historyString = prefs[SERVER_ADDR_HISTORY] ?: ""
            if (historyString.isBlank()) emptyList()
            else historyString.split("|").filter { it.isNotBlank() }
        }

    val jazzCredsHistoryFlow: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val historyString = prefs[JAZZ_CREDS_HISTORY] ?: ""
            if (historyString.isBlank()) emptyList()
            else historyString.split("|").filter { it.isNotBlank() }
        }

    val turnableUrlHistoryFlow: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val historyString = prefs[TURNABLE_URL_HISTORY] ?: ""
            if (historyString.isBlank()) emptyList()
            else historyString.split("|").filter { it.isNotBlank() }
        }

    val vlessLinkHistoryFlow: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val historyString = prefs[VLESS_LINK_HISTORY] ?: ""
            if (historyString.isBlank()) emptyList()
            else historyString.split("|").filter { it.isNotBlank() }
        }

    suspend fun addVkLinkToHistory(link: String) {
        if (link.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[VK_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = (listOf(link) + currentHistory.filter { it != link }).take(3)
            prefs[VK_LINK_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun addWbstreamUuidToHistory(uuid: String) {
        if (uuid.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[WBSTREAM_UUID_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = (listOf(uuid) + currentHistory.filter { it != uuid }).take(3)
            prefs[WBSTREAM_UUID_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun addServerAddressToHistory(address: String) {
        if (address.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[SERVER_ADDR_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = (listOf(address) + currentHistory.filter { it != address }).take(3)
            prefs[SERVER_ADDR_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun addJazzCredsToHistory(creds: String) {
        if (creds.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[JAZZ_CREDS_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = (listOf(creds) + currentHistory.filter { it != creds }).take(3)
            prefs[JAZZ_CREDS_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun addTurnableUrlToHistory(url: String) {
        if (url.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[TURNABLE_URL_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = (listOf(url) + currentHistory.filter { it != url }).take(3)
            prefs[TURNABLE_URL_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun addVlessLinkToHistory(link: String) {
        if (link.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[VLESS_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = (listOf(link) + currentHistory.filter { it != link }).take(3)
            prefs[VLESS_LINK_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun removeVkLinkFromHistory(link: String) {
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[VK_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = currentHistory.filter { it != link }
            prefs[VK_LINK_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun removeWbstreamUuidFromHistory(uuid: String) {
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[WBSTREAM_UUID_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = currentHistory.filter { it != uuid }
            prefs[WBSTREAM_UUID_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun removeServerAddressFromHistory(address: String) {
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[SERVER_ADDR_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = currentHistory.filter { it != address }
            prefs[SERVER_ADDR_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun removeJazzCredsFromHistory(creds: String) {
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[JAZZ_CREDS_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = currentHistory.filter { it != creds }
            prefs[JAZZ_CREDS_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun removeTurnableUrlFromHistory(url: String) {
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[TURNABLE_URL_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = currentHistory.filter { it != url }
            prefs[TURNABLE_URL_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun removeVlessLinkFromHistory(link: String) {
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[VLESS_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = currentHistory.filter { it != link }
            prefs[VLESS_LINK_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun saveClientConfig(config: ClientConfig) {
        val c = (config as ClientConfig?) ?: ClientConfig()
        context.dataStore.edit { prefs ->
            prefs[CLIENT_SERVER_ADDR] = (c.serverAddress as String?) ?: ""
            prefs[CLIENT_VK_LINK] = (c.vkLink as String?) ?: ""
            prefs[CLIENT_WBSTREAM_UUID] = (c.wbstreamUuid as String?) ?: ""
            prefs[CLIENT_THREADS] = (c.threads as Int?) ?: 4
            prefs[CLIENT_UDP] = (c.useUdp as Boolean?) ?: false
            prefs[CLIENT_NO_DTLS] = (c.noDtls as Boolean?) ?: false
            prefs[CLIENT_MANUAL_CAPTCHA] = (c.manualCaptcha as Boolean?) ?: false
            prefs[CLIENT_LOCAL_PORT] = (c.localPort as String?) ?: ClientConfig.DEFAULT_LOCAL_PORT
            prefs[CLIENT_IS_RAW] = (c.isRawMode as Boolean?) ?: false
            prefs[CLIENT_RAW_CMD] = (c.rawCommand as String?) ?: ""
            prefs[CLIENT_VLESS] = (c.vlessMode as Boolean?) ?: false
            prefs[CLIENT_DC_MODE] = (c.dcMode as Boolean?) ?: false
            prefs[CLIENT_FORCE_PORT_443] = (c.forceTurnPort443 as Boolean?) ?: false
            prefs[CLIENT_DC_TYPE] = ((c.dcType as DCType?) ?: DCType.SALUTE_JAZZ).name
            prefs[CLIENT_JAZZ_CREDS] = (c.jazzCreds as String?) ?: ""
            prefs[CLIENT_TURNABLE_URL] = (c.turnableUrl as String?) ?: ""
            prefs[CLIENT_KERNEL_VARIANT] = ((c.kernelVariant as KernelVariant?) ?: KernelVariant.VK_TURN_PROXY).name
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
            File(context.filesDir, "custom_vkturn").delete()
        }
    }
}
