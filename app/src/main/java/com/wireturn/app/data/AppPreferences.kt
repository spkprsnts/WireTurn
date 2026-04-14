package com.wireturn.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

data class ClientConfig(
    val serverAddress: String = "",
    val vkLink: String = "",
    val threads: Int = 4,
    val useUdp: Boolean = true,
    val noDtls: Boolean = false,
    val manualCaptcha: Boolean = false,
    val localPort: String = "127.0.0.1:9000",
    val isRawMode: Boolean = false,
    val rawCommand: String = "",
    val vlessMode: Boolean = false,
    val telemostDc: Boolean = true,
    val forceTurnPort443: Boolean = false,
    val wireproxyEnabled: Boolean = false,
    val wireproxyVpnMode: Boolean = false
) {
    fun getValidationErrorResId(): Int? {
        return if (isRawMode) {
            if (rawCommand.isBlank()) com.wireturn.app.R.string.error_raw_empty else null
        } else {
            if (serverAddress.isBlank() || vkLink.isBlank()) com.wireturn.app.R.string.error_settings_empty else null
        }
    }
}

data class WgConfig(
    val privateKey: String = "",
    val address: String = "",
    val dns: String = "",
    val mtu: String = "",
    val publicKey: String = "",
    val endpoint: String = "",
    val allowedIps: String = "",
    val persistentKeepalive: String = "",
    val socks5BindAddress: String = DEFAULT_SOCKS5_BIND_ADDRESS,
    val httpBindAddress: String = ""
) {
    fun isValid(): Boolean {
        return privateKey.isNotBlank() && address.isNotBlank() && publicKey.isNotBlank()
    }

    fun fillDefaults(): WgConfig {
        return copy(
            dns = dns.ifBlank { DEFAULT_DNS },
            mtu = mtu.ifBlank { DEFAULT_MTU },
            endpoint = endpoint.ifBlank { DEFAULT_ENDPOINT },
            allowedIps = allowedIps.ifBlank { DEFAULT_ALLOWED_IPS },
            persistentKeepalive = persistentKeepalive.ifBlank { DEFAULT_PERSISTENT_KEEPALIVE },
            socks5BindAddress = socks5BindAddress.ifBlank { DEFAULT_SOCKS5_BIND_ADDRESS }
        )
    }

    fun toWgString(): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = $privateKey\n")
        sb.append("Address = $address\n")
        if (dns.isNotBlank()) sb.append("DNS = $dns\n")
        if (mtu.isNotBlank()) sb.append("MTU = $mtu\n")
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = $publicKey\n")
        sb.append("Endpoint = $endpoint\n")
        sb.append("AllowedIPs = $allowedIps\n")
        if (persistentKeepalive.isNotBlank()) sb.append("PersistentKeepalive = $persistentKeepalive\n")
        if (httpBindAddress.isNotBlank()) {
            sb.append("\n[http]\n")
            sb.append("BindAddress = $httpBindAddress\n")
        }
        if (socks5BindAddress.isNotBlank()) {
            sb.append("\n[Socks5]\n")
            sb.append("BindAddress = $socks5BindAddress\n")
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_DNS = "1.1.1.1"
        const val DEFAULT_MTU = "1280"
        const val DEFAULT_ENDPOINT = "127.0.0.1:9000"
        const val DEFAULT_ALLOWED_IPS = "0.0.0.0/0"
        const val DEFAULT_PERSISTENT_KEEPALIVE = "25"
        const val DEFAULT_SOCKS5_BIND_ADDRESS = "127.0.0.1:2080"

        fun parse(text: String): WgConfig {
            var privateKey = ""; var address = ""; var dns = ""; var mtu = ""
            var publicKey = ""; var endpoint = ""; var allowedIps = ""
            var persistentKeepalive = ""
            var httpBindAddress = ""; var socks5BindAddress = DEFAULT_SOCKS5_BIND_ADDRESS

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
                            "dns" -> dns = value
                            "mtu" -> mtu = value
                        }
                        "peer" -> when (key) {
                            "publickey" -> publicKey = value
                            "endpoint" -> endpoint = value
                            "allowedips" -> allowedIps = value
                            "persistentkeepalive" -> persistentKeepalive = value
                        }
                        "http" -> if (key == "bindaddress") httpBindAddress = value
                        "socks5" -> if (key == "bindaddress") socks5BindAddress = value
                    }
                }
            }
            return WgConfig(privateKey, address, dns, mtu, publicKey, endpoint, allowedIps, persistentKeepalive, socks5BindAddress, httpBindAddress)
        }
    }
}

enum class ThemeMode {
    DARK, LIGHT, SYSTEM
}

// P2-3 / P3-6: всегда используем applicationContext, чтобы lazy-init encryptedPrefs
// не мог сработать на уничтоженном контексте (например Service после onDestroy)
class AppPreferences(context: Context) {
    private val context = context.applicationContext

    companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val CLIENT_SERVER_ADDR = stringPreferencesKey("client_server_addr")
        val CLIENT_VK_LINK = stringPreferencesKey("client_vk_link")
        val CLIENT_THREADS = intPreferencesKey("client_threads")
        val CLIENT_UDP = booleanPreferencesKey("client_udp")
        val CLIENT_NO_DTLS = booleanPreferencesKey("client_no_dtls")
        val CLIENT_MANUAL_CAPTCHA = booleanPreferencesKey("client_manual_captcha")
        val CLIENT_LOCAL_PORT = stringPreferencesKey("client_local_port")
        val CLIENT_IS_RAW = booleanPreferencesKey("client_is_raw")
        val CLIENT_RAW_CMD = stringPreferencesKey("client_raw_cmd")
        val CLIENT_VLESS = booleanPreferencesKey("client_vless")
        val CLIENT_TELEMOST_DC = booleanPreferencesKey("client_telemost_dc")
        val CLIENT_FORCE_PORT_443 = booleanPreferencesKey("client_force_port_443")
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val WIREPROXY_ENABLED = booleanPreferencesKey("wireproxy_enabled")
        val WIREPROXY_VPN_MODE = booleanPreferencesKey("wireproxy_vpn_mode")
        val WIRE_PRIV_KEY = stringPreferencesKey("wire_priv_key")
        val WIRE_ADDRESS = stringPreferencesKey("wire_address")
        val WIRE_DNS = stringPreferencesKey("wire_dns")
        val WIRE_MTU = stringPreferencesKey("wire_mtu")
        val WIRE_PUB_KEY = stringPreferencesKey("wire_pub_key")
        val WIRE_ENDPOINT = stringPreferencesKey("wire_endpoint")
        val WIRE_ALLOWED_IPS = stringPreferencesKey("wire_allowed_ips")
        val WIRE_KEEPALIVE = stringPreferencesKey("wire_keepalive")
        val WIRE_SOCKS5_BIND = stringPreferencesKey("wire_socks5_bind")
        val WIRE_HTTP_BIND = stringPreferencesKey("wire_http_bind")
        val VK_LINK_HISTORY = stringPreferencesKey("vk_link_history")
        val SERVER_ADDR_HISTORY = stringPreferencesKey("server_addr_history")
        val BATTERY_NOTIFICATION_DISMISSED = booleanPreferencesKey("battery_notification_dismissed")
    }

    val clientConfigFlow: Flow<ClientConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            ClientConfig(
                serverAddress = prefs[CLIENT_SERVER_ADDR] ?: "",
                vkLink = prefs[CLIENT_VK_LINK] ?: "",
                threads = prefs[CLIENT_THREADS] ?: 4,
                useUdp = prefs[CLIENT_UDP] ?: true,
                noDtls = prefs[CLIENT_NO_DTLS] ?: false,
                manualCaptcha = prefs[CLIENT_MANUAL_CAPTCHA] ?: false,
                localPort = prefs[CLIENT_LOCAL_PORT] ?: "127.0.0.1:9000",
                isRawMode = prefs[CLIENT_IS_RAW] ?: false,
                rawCommand = prefs[CLIENT_RAW_CMD] ?: "",
                vlessMode = prefs[CLIENT_VLESS] ?: false,
                telemostDc = prefs[CLIENT_TELEMOST_DC] ?: true,
                forceTurnPort443 = prefs[CLIENT_FORCE_PORT_443] ?: false,
                wireproxyEnabled = prefs[WIREPROXY_ENABLED] ?: false,
                wireproxyVpnMode = prefs[WIREPROXY_VPN_MODE] ?: false
            )
        }

    val wgConfigFlow: Flow<WgConfig> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            WgConfig(
                privateKey = prefs[WIRE_PRIV_KEY] ?: "",
                address = prefs[WIRE_ADDRESS] ?: "",
                dns = prefs[WIRE_DNS] ?: "",
                mtu = prefs[WIRE_MTU] ?: "",
                publicKey = prefs[WIRE_PUB_KEY] ?: "",
                endpoint = prefs[WIRE_ENDPOINT] ?: "",
                allowedIps = prefs[WIRE_ALLOWED_IPS] ?: "",
                persistentKeepalive = prefs[WIRE_KEEPALIVE] ?: "",
                socks5BindAddress = prefs[WIRE_SOCKS5_BIND] ?: WgConfig.DEFAULT_SOCKS5_BIND_ADDRESS,
                httpBindAddress = prefs[WIRE_HTTP_BIND] ?: ""
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

    val vkLinkHistoryFlow: Flow<List<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val historyString = prefs[VK_LINK_HISTORY] ?: ""
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

    suspend fun addVkLinkToHistory(link: String) {
        if (link.isBlank()) return
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[VK_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = (listOf(link) + currentHistory.filter { it != link }).take(3)
            prefs[VK_LINK_HISTORY] = newHistory.joinToString("|")
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

    suspend fun removeVkLinkFromHistory(link: String) {
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[VK_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = currentHistory.filter { it != link }
            prefs[VK_LINK_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun removeServerAddressFromHistory(address: String) {
        context.dataStore.edit { prefs ->
            val currentHistory = prefs[SERVER_ADDR_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val newHistory = currentHistory.filter { it != address }
            prefs[SERVER_ADDR_HISTORY] = newHistory.joinToString("|")
        }
    }

    suspend fun saveClientConfig(config: ClientConfig) {
        context.dataStore.edit { prefs ->
            prefs[CLIENT_SERVER_ADDR] = config.serverAddress
            prefs[CLIENT_VK_LINK] = config.vkLink
            prefs[CLIENT_THREADS] = config.threads
            prefs[CLIENT_UDP] = config.useUdp
            prefs[CLIENT_NO_DTLS] = config.noDtls
            prefs[CLIENT_MANUAL_CAPTCHA] = config.manualCaptcha
            prefs[CLIENT_LOCAL_PORT] = config.localPort
            prefs[CLIENT_IS_RAW] = config.isRawMode
            prefs[CLIENT_RAW_CMD] = config.rawCommand
            prefs[CLIENT_VLESS] = config.vlessMode
            prefs[CLIENT_TELEMOST_DC] = config.telemostDc
            prefs[CLIENT_FORCE_PORT_443] = config.forceTurnPort443
            prefs[WIREPROXY_ENABLED] = config.wireproxyEnabled
            prefs[WIREPROXY_VPN_MODE] = config.wireproxyVpnMode
        }
    }

    suspend fun setBatteryNotificationDismissed(dismissed: Boolean) {
        context.dataStore.edit { prefs -> prefs[BATTERY_NOTIFICATION_DISMISSED] = dismissed }
    }

    suspend fun saveWgConfig(config: WgConfig) {
        context.dataStore.edit { prefs ->
            prefs[WIRE_PRIV_KEY] = config.privateKey
            prefs[WIRE_ADDRESS] = config.address
            prefs[WIRE_DNS] = config.dns
            prefs[WIRE_MTU] = config.mtu
            prefs[WIRE_PUB_KEY] = config.publicKey
            prefs[WIRE_ENDPOINT] = config.endpoint
            prefs[WIRE_ALLOWED_IPS] = config.allowedIps
            prefs[WIRE_KEEPALIVE] = config.persistentKeepalive
            prefs[WIRE_SOCKS5_BIND] = config.socks5BindAddress
            prefs[WIRE_HTTP_BIND] = config.httpBindAddress
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
