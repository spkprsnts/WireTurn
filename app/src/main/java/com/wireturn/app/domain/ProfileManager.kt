package com.wireturn.app.domain

import android.content.Context
import android.content.Intent
import com.wireturn.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
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

    fun selectProfile(id: String, onConfigLoaded: (ClientConfig, XrayConfig, WgConfig, VlessConfig) -> Unit) {
        val profile = profiles.value.find { it.id == id } ?: return
        scope.launch {
            prefs.setCurrentProfileId(id)
            prefs.saveClientConfig(profile.clientConfig)
            prefs.saveXrayConfig(profile.xrayConfig)
            prefs.saveWgConfig(profile.wgConfig)
            prefs.saveVlessConfig(profile.vlessConfig)
            onConfigLoaded(profile.clientConfig, profile.xrayConfig, profile.wgConfig, profile.vlessConfig)
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
        xrayConfig: XrayConfig,
        wgConfig: WgConfig,
        vlessConfig: VlessConfig
    ) {
        val currentId = currentProfileId.value
        val newList = profiles.value.map {
            if (it.id == currentId) {
                it.copy(
                    clientConfig = clientConfig,
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
