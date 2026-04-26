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

    fun selectProfile(id: String, profile: Profile? = null, onConfigLoaded: (ClientConfig, XraySettings, XrayConfig, WgConfig, VlessConfig) -> Unit) {
        val targetProfile = profile ?: profiles.value.find { it.id == id } ?: return
        scope.launch {
            prefs.setCurrentProfileId(id)
            prefs.saveClientConfig(targetProfile.clientConfig)
            prefs.saveXraySettings(targetProfile.xraySettings)
            prefs.saveXrayConfig(targetProfile.xrayConfig)
            prefs.saveWgConfig(targetProfile.wgConfig)
            prefs.saveVlessConfig(targetProfile.vlessConfig)
            onConfigLoaded(targetProfile.clientConfig, targetProfile.xraySettings, targetProfile.xrayConfig, targetProfile.wgConfig, targetProfile.vlessConfig)
        }
    }

    fun createProfile(name: String, onSelected: (String, Profile) -> Unit) {
        val newProfile = Profile(
            id = UUID.randomUUID().toString(),
            name = name
        )
        val newList = profiles.value + newProfile
        scope.launch {
            prefs.saveProfiles(newList)
            onSelected(newProfile.id, newProfile)
        }
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

    fun deleteProfile(id: String, onFallback: (String) -> Unit) {
        val currentList = profiles.value
        if (currentList.size <= 1) return
        val newList = currentList.filter { it.id != id }
        scope.launch {
            if (currentProfileId.value == id) {
                onFallback(newList.first().id)
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

    fun importProfiles(data: List<Pair<String?, String>>) {
        try {
            val gson = com.google.gson.Gson()
            val newProfiles = data.mapNotNull { (fileName, json) ->
                try {
                    val p = gson.fromJson(json, Profile::class.java) ?: return@mapNotNull null
                    
                    val nameFromFile = fileName?.removeSuffix(".json")?.removePrefix("wt_")

                    Profile(
                        id = UUID.randomUUID().toString(),
                        name = (p.name as String?).takeIf { !it.isNullOrBlank() } ?: nameFromFile ?: "Imported",
                        clientConfig = (p.clientConfig as ClientConfig?) ?: ClientConfig(),
                        xraySettings = (p.xraySettings as XraySettings?) ?: XraySettings(),
                        xrayConfig = (p.xrayConfig as XrayConfig?) ?: XrayConfig(),
                        wgConfig = (p.wgConfig as WgConfig?) ?: WgConfig(),
                        vlessConfig = (p.vlessConfig as VlessConfig?) ?: VlessConfig()
                    )
                } catch (_: Exception) {
                    null
                }
            }
            if (newProfiles.isEmpty()) return
            
            val newList = profiles.value + newProfiles
            scope.launch { prefs.saveProfiles(newList) }
        } catch (_: Exception) {}
    }
}
