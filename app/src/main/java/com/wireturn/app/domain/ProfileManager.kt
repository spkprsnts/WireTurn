package com.wireturn.app.domain

import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.VlessConfig
import com.wireturn.app.data.WgConfig
import com.wireturn.app.data.XrayConfig
import com.wireturn.app.data.XraySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class ProfileManager(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope
) {
    val profiles: StateFlow<List<Profile>> = prefs.profilesFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val currentProfileId: StateFlow<String> = prefs.currentProfileIdFlow
        .stateIn(scope, SharingStarted.Eagerly, "default")

    fun selectProfile(id: String, onConfigLoaded: (ClientConfig, XraySettings, XrayConfig, WgConfig, VlessConfig) -> Unit) {
        val profile = profiles.value.find { it.id == id } ?: return
        scope.launch {
            prefs.setCurrentProfileId(id)
            prefs.saveClientConfig(profile.clientConfig)
            prefs.saveXraySettings(profile.xraySettings)
            prefs.saveXrayConfig(profile.xrayConfig)
            prefs.saveWgConfig(profile.wgConfig)
            prefs.saveVlessConfig(profile.vlessConfig)
            onConfigLoaded(profile.clientConfig, profile.xraySettings, profile.xrayConfig, profile.wgConfig, profile.vlessConfig)
        }
    }

    fun createProfile(name: String) {
        val newProfile = Profile(
            id = UUID.randomUUID().toString(),
            name = name
        )
        val newList = profiles.value + newProfile
        scope.launch { prefs.saveProfiles(newList) }
    }

    fun cloneProfile(id: String, newName: String) {
        val profile = profiles.value.find { it.id == id } ?: return
        val clonedProfile = profile.copy(
            id = UUID.randomUUID().toString(),
            name = newName
        )
        val newList = profiles.value + clonedProfile
        scope.launch { prefs.saveProfiles(newList) }
    }

    fun deleteProfile(id: String, onDefaultSelected: () -> Unit) {
        if (id == "default") return
        val newList = profiles.value.filter { it.id != id }
        scope.launch {
            if (currentProfileId.value == id) {
                onDefaultSelected()
            }
            prefs.saveProfiles(newList)
        }
    }

    fun renameProfile(id: String, newName: String) {
        val newList = profiles.value.map {
            if (it.id == id) it.copy(name = newName) else it
        }
        scope.launch { prefs.saveProfiles(newList) }
    }

    fun reorderProfiles(newList: List<Profile>) {
        scope.launch { prefs.saveProfiles(newList) }
    }

    fun updateCurrentProfile(
        clientConfig: ClientConfig,
        xraySettings: XraySettings,
        xrayConfig: XrayConfig,
        wgConfig: WgConfig,
        vlessConfig: VlessConfig
    ) {
        val currentId = currentProfileId.value
        val newList = profiles.value.map {
            if (it.id == currentId) {
                it.copy(
                    clientConfig = clientConfig,
                    xraySettings = xraySettings,
                    xrayConfig = xrayConfig,
                    wgConfig = wgConfig,
                    vlessConfig = vlessConfig
                )
            } else it
        }
        if (newList != profiles.value) {
            scope.launch { prefs.saveProfiles(newList) }
        }
    }

    fun getProfileJson(id: String): String? {
        val profile = profiles.value.find { it.id == id } ?: return null
        return com.google.gson.Gson().toJson(profile)
    }

    fun importProfile(json: String) {
        try {
            val profile = com.google.gson.Gson().fromJson(json, Profile::class.java)
            val newProfile = profile.copy(id = UUID.randomUUID().toString())
            val newList = profiles.value + newProfile
            scope.launch { prefs.saveProfiles(newList) }
        } catch (_: Exception) {}
    }
}
