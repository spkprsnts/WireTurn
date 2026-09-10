package com.wireturn.app.data

import android.content.Context
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
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.wireturn.app.R
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
        val PROFILE_COUNTRIES_JSON = stringPreferencesKey("profile_countries_json")
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
        val CLIENT_DNS = stringPreferencesKey("client_dns")
        val CLIENT_SOCKS_ADDR = stringPreferencesKey("client_socks_addr")
        val CLIENT_SOCKS_AUTH_ENABLED = booleanPreferencesKey("client_socks_auth_enabled")
        val CLIENT_SOCKS_USER = stringPreferencesKey("client_socks_user")
        val CLIENT_SOCKS_PASS = stringPreferencesKey("client_socks_pass")
        // Legacy keys — shared by every SOCKS5-native kernel (OLCRTC/WEBDAV/QWDTT), not OLCRTC-only;
        // renamed to CLIENT_SOCKS_* above. Used only for migration on first launch after update.
        private val LEGACY_OLCRTC_SOCKS_ADDR = stringPreferencesKey("olcrtc_socks_addr")
        private val LEGACY_OLCRTC_SOCKS_AUTH_ENABLED = booleanPreferencesKey("olcrtc_socks_auth_enabled")
        private val LEGACY_OLCRTC_SOCKS_USER = stringPreferencesKey("olcrtc_socks_user")
        private val LEGACY_OLCRTC_SOCKS_PASS = stringPreferencesKey("olcrtc_socks_pass")
        val XRAY_SOCKS_BIND = stringPreferencesKey("xray_socks_bind")
        val XRAY_HTTP_BIND = stringPreferencesKey("xray_http_bind")
        val XRAY_AUTH_ENABLED = booleanPreferencesKey("xray_auth_enabled")
        val XRAY_USER = stringPreferencesKey("xray_user")
        val XRAY_PASS = stringPreferencesKey("xray_pass")
        val XRAY_DNS = stringPreferencesKey("xray_dns")
        val XRAY_ROUTE_DIRECT = stringPreferencesKey("xray_route_direct")
        val XRAY_ROUTE_BLOCK = stringPreferencesKey("xray_route_block")
        val XRAY_FAKEDNS = booleanPreferencesKey("xray_fakedns")
        val XRAY_GEO_VARIANT = stringPreferencesKey("xray_geo_variant")

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
                enabled = it[VPN_ENABLED] ?: true,
                hideSystemApps = it[VPN_HIDE_SYSTEM_APPS] ?: false,
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
            } catch (_: Exception) {
                emptyList()
            }
        }.distinctUntilChanged()

    /** Last known tunnel-exit country (ISO-3166 alpha-2) per profile id, from the most recent successful connection. */
    val profileCountriesFlow: Flow<Map<String, String>> = appCtx.internalDataStore.data
        .map { p ->
            try {
                gson.fromJson<Map<String, String>>(
                    p[PROFILE_COUNTRIES_JSON] ?: "{}",
                    object : TypeToken<Map<String, String>>() {}.type
                ) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
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
                    KernelVariant.QWDTT -> KernelConfig.Qwdtt(snap.qwdtt ?: QwdttConfig())
                    KernelVariant.OPENFLUX -> KernelConfig.OpenFlux(snap.openflux ?: OpenFluxConfig())
                }
            } ?: run {
                // Migration from legacy keys
                val variant = try { KernelVariant.valueOf(p[LEGACY_KERNEL_VARIANT] ?: KernelVariant.TURNABLE.name) } catch (_: Exception) { KernelVariant.TURNABLE }
                when (variant) {
                    KernelVariant.TURNABLE -> KernelConfig.Turnable(gson.fromJson(p[LEGACY_TURNABLE_JSON] ?: "{}", TurnableConfig::class.java) ?: TurnableConfig())
                    KernelVariant.OLCRTC -> KernelConfig.Olcrtc(gson.fromJson(p[LEGACY_OLCRTC_JSON] ?: "{}", OlcrtcConfig::class.java) ?: OlcrtcConfig())
                    KernelVariant.WEBDAV -> KernelConfig.Webdav(WebdavConfig())
                    KernelVariant.FREETURN -> KernelConfig.FreeTurn(FreeTurnConfig())
                    KernelVariant.QWDTT -> KernelConfig.Qwdtt(QwdttConfig())
                    KernelVariant.OPENFLUX -> KernelConfig.OpenFlux(OpenFluxConfig())
                }
            }
            ClientConfig(
                listenAddr = p[CLIENT_LISTEN_ADDR] ?: ClientConfig.DEFAULT_LISTEN_ADDR,
                socksAddr = p[CLIENT_SOCKS_ADDR] ?: p[LEGACY_OLCRTC_SOCKS_ADDR] ?: ClientConfig.DEFAULT_SOCKS_ADDR,
                isSocksAuthEnabled = p[CLIENT_SOCKS_AUTH_ENABLED] ?: p[LEGACY_OLCRTC_SOCKS_AUTH_ENABLED] ?: true,
                socksUser = p[CLIENT_SOCKS_USER] ?: p[LEGACY_OLCRTC_SOCKS_USER] ?: "",
                socksPass = p[CLIENT_SOCKS_PASS] ?: p[LEGACY_OLCRTC_SOCKS_PASS] ?: "",
                dns = p[CLIENT_DNS] ?: "",
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
                proxyPass = p[XRAY_PASS] ?: "",
                dns = p[XRAY_DNS] ?: XraySettings.DEFAULT_DNS,
                routeDirect = p[XRAY_ROUTE_DIRECT] ?: "",
                routeBlock = p[XRAY_ROUTE_BLOCK] ?: "",
                fakeDns = p[XRAY_FAKEDNS] ?: false,
                geoVariant = p[XRAY_GEO_VARIANT] ?: "runetfreedom"
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
        is KernelConfig.Qwdtt -> KernelSnapshot(variant = KernelVariant.QWDTT.name, qwdtt = k.config)
        is KernelConfig.OpenFlux -> KernelSnapshot(variant = KernelVariant.OPENFLUX.name, openflux = k.config)
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

    suspend fun saveProfileCountry(profileId: String, countryCode: String) {
        appCtx.internalDataStore.edit { p ->
            val current = try {
                gson.fromJson<Map<String, String>>(
                    p[PROFILE_COUNTRIES_JSON] ?: "{}",
                    object : TypeToken<Map<String, String>>() {}.type
                ) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
            p[PROFILE_COUNTRIES_JSON] = gson.toJson(current + (profileId to countryCode))
        }
    }

    suspend fun removeProfileCountries(profileIds: List<String>) {
        if (profileIds.isEmpty()) return
        appCtx.internalDataStore.edit { p ->
            val current = try {
                gson.fromJson<Map<String, String>>(
                    p[PROFILE_COUNTRIES_JSON] ?: "{}",
                    object : TypeToken<Map<String, String>>() {}.type
                ) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
            p[PROFILE_COUNTRIES_JSON] = gson.toJson(current - profileIds.toSet())
        }
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
            it[XRAY_DNS] = s.dns
            it[XRAY_ROUTE_DIRECT] = s.routeDirect
            it[XRAY_ROUTE_BLOCK] = s.routeBlock
            it[XRAY_FAKEDNS] = s.fakeDns
            it[XRAY_GEO_VARIANT] = s.geoVariant
        }
    }

    suspend fun saveClientConfig(c: ClientConfig) {
        appCtx.internalDataStore.edit {
            it[CLIENT_LISTEN_ADDR] = c.listenAddr
            it[CLIENT_SOCKS_ADDR] = c.socksAddr
            it[CLIENT_SOCKS_AUTH_ENABLED] = c.isSocksAuthEnabled
            it[CLIENT_SOCKS_USER] = c.socksUser
            it[CLIENT_SOCKS_PASS] = c.socksPass
            it.remove(LEGACY_OLCRTC_SOCKS_ADDR); it.remove(LEGACY_OLCRTC_SOCKS_AUTH_ENABLED)
            it.remove(LEGACY_OLCRTC_SOCKS_USER); it.remove(LEGACY_OLCRTC_SOCKS_PASS)
            it[CLIENT_DNS] = c.dns
            it[GO_DNS_GO] = c.goDnsGo
            it[USE_CUSTOM_CERTS] = c.useCustomCerts
            it[ACTIVE_KERNEL_JSON] = gson.toJson(when (val k = c.kernelConfig) {
                is KernelConfig.Turnable -> KernelSnapshot(variant = KernelVariant.TURNABLE.name, turnable = k.config)
                is KernelConfig.Olcrtc -> KernelSnapshot(variant = KernelVariant.OLCRTC.name, olcrtc = k.config)
                is KernelConfig.Webdav -> KernelSnapshot(variant = KernelVariant.WEBDAV.name, webdav = k.config)
                is KernelConfig.FreeTurn -> KernelSnapshot(variant = KernelVariant.FREETURN.name, freeturn = k.config)
                is KernelConfig.Qwdtt -> KernelSnapshot(variant = KernelVariant.QWDTT.name, qwdtt = k.config)
                is KernelConfig.OpenFlux -> KernelSnapshot(variant = KernelVariant.OPENFLUX.name, openflux = k.config)
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
