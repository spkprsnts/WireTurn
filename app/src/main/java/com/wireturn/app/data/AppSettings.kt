package com.wireturn.app.data

import com.google.gson.annotations.SerializedName

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class VpnSettings(
    val enabled: Boolean = true,
    val hideSystemApps: Boolean = false,
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
    @SerializedName("updateIntervalMinutes") val updateIntervalMinutes: Int = 1440, // Default 24h
    @SerializedName("activeProfileId") val activeProfileId: String? = null,
    @SerializedName("onlyUpdateIfSelected") val onlyUpdateIfSelected: Boolean = false,
    @SerializedName("requireTunnelForUpdate") val requireTunnelForUpdate: Boolean = false
)

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
