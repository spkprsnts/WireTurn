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

