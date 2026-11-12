package com.wireturn.app.data

import com.google.gson.annotations.SerializedName

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class VpnSettings(
    val enabled: Boolean = true,
    val hideSystemApps: Boolean = false,
    val bypassMode: Boolean = true,
    val filteringEnabled: Boolean = true,
    val groupAppsByLetter: Boolean = true,
    val excludedApps: Set<String> = emptySet(),
    val mtu: Int = DEFAULT_MTU,
    val ipv6: Boolean = false,
    val icmpReply: Boolean = true,
    // Local network ranges stay off the tun - see domain/LanRoutes.
    val bypassLan: Boolean = true
) {
    companion object {
        const val DEFAULT_MTU = 1350
        const val MIN_MTU = 576
        const val MAX_MTU = 9000
        const val DEFAULT_IPV6 = false
    }
}

data class AutoLaunchSettings(
    val enabled: Boolean = false,
    val checkUrl: String = "https://www.google.com",
    val intervalMinutes: Int = 15
)

/**
 * The watchdogs' (CoreService, XrayService) restart policy. [eternal]: no attempt limit - past the
 * regular limit the wait between attempts keeps doubling up to [maxIntervalMinutes], and a failed
 * start is retried too instead of ending the session (except what retrying can't fix: an invalid
 * config, a kernel not built for the device).
 */
data class WatchdogSettings(
    val eternal: Boolean = false,
    val maxIntervalMinutes: Int = DEFAULT_MAX_INTERVAL_MINUTES
) {
    val maxIntervalMs: Long get() = maxIntervalMinutes.coerceIn(MIN_MAX_INTERVAL_MINUTES, MAX_MAX_INTERVAL_MINUTES) * 60_000L

    companion object {
        const val DEFAULT_MAX_INTERVAL_MINUTES = 15
        const val MIN_MAX_INTERVAL_MINUTES = 1
        const val MAX_MAX_INTERVAL_MINUTES = 1440

        // Past the regular limit: 30s, 1m, 2m, 4m... up to [maxIntervalMs].
        fun eternalDelayMs(attemptsPastLimit: Int, maxIntervalMs: Long): Long =
            (30_000L shl (attemptsPastLimit - 1).coerceIn(0, 16)).coerceAtMost(maxIntervalMs)
    }
}

data class Subscription(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("url") val url: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("updatedAt") val updatedAt: Long = 0,
    @SerializedName("bytesUsed") val bytesUsed: Long = 0,
    @SerializedName("bytesTotal") val bytesTotal: Long = 0,
    @SerializedName("autoUpdate") val autoUpdate: Boolean = false,
    @SerializedName("updateIntervalMinutes") val updateIntervalMinutes: Int = 1440, // Default 24h
    @SerializedName("activeProfileId") val activeProfileId: String? = null,
    @SerializedName("onlyUpdateIfSelected") val onlyUpdateIfSelected: Boolean = false,
    @SerializedName("requireTunnelForUpdate") val requireTunnelForUpdate: Boolean = false,
    @SerializedName("pinned") val pinned: Boolean = false,
    @SerializedName("collapsed") val collapsed: Boolean = false,
    @SerializedName("sortMode") val sortMode: String = SORT_SERVER
) {
    /**
     * Gson builds this without its constructor, so a field missing from JSON saved by an older
     * version comes back null despite its non-null type and default - copy() then throws on it.
     * Fills those in; the explicit argument keeps copy() from reading the null one.
     */
    @Suppress("SENSELESS_COMPARISON")
    fun withDefaults(): Subscription =
        if (sortMode == null) copy(sortMode = SORT_SERVER) else this

    companion object {
        const val SORT_SERVER = "server"
        const val SORT_PING = "ping"
    }
}

data class ProfileBundle(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("profiles") val profiles: List<Profile> = emptyList(),
    @SerializedName("updatedAt") val updatedAt: Long? = null,
    @SerializedName("bytesUsed") val bytesUsed: Long? = null,
    @SerializedName("bytesTotal") val bytesTotal: Long? = null,
    @SerializedName("recommendedProfileId", alternate = ["activeProfileId"]) val recommendedProfileId: String? = null,
    @SerializedName("updateIntervalMinutes") val updateIntervalMinutes: Int? = null,
    @SerializedName("onlyUpdateIfSelected") val onlyUpdateIfSelected: Boolean? = null
)
