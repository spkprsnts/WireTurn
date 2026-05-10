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
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProfileManager(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val defaultProfileName: String = "Primary profile"
) {
    val profiles: StateFlow<List<Profile>> = prefs.profilesFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val currentProfileId: StateFlow<String> = prefs.currentProfileIdFlow
        .stateIn(scope, SharingStarted.Eagerly, "default")

    fun selectProfile(id: String, profile: Profile? = null, onConfigLoaded: (ClientConfig, XraySettings, XrayConfig, WgConfig, VlessConfig) -> Unit) {
        val targetProfile = profile ?: profiles.value.find { it.id == id } ?: return
        scope.launch {
            com.wireturn.app.ProxyServiceState.setChangingProfile(true)
            try {
                prefs.saveFullProfile(
                    id = id,
                    clientConfig = targetProfile.clientConfig,
                    wgConfig = targetProfile.wgConfig,
                    vlessConfig = targetProfile.vlessConfig,
                    xraySettings = targetProfile.xraySettings,
                    xrayConfig = targetProfile.xrayConfig
                )
                onConfigLoaded(
                    targetProfile.clientConfig,
                    targetProfile.xraySettings,
                    targetProfile.xrayConfig,
                    targetProfile.wgConfig,
                    targetProfile.vlessConfig
                )
            } finally {
                kotlinx.coroutines.delay(150) // Fast enough but prevents blink
                com.wireturn.app.ProxyServiceState.setChangingProfile(false)
            }
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
        val currentList = profiles.value
        val profile = currentList.find { it.id == id } ?: return
        val clonedProfile = profile.copy(
            id = UUID.randomUUID().toString(),
            name = newName
        )
        val newList = currentList + clonedProfile
        scope.launch { prefs.saveProfiles(newList) }
    }

    fun deleteProfiles(ids: List<String>, onFallback: (String, Profile?) -> Unit) {
        val currentList = profiles.value
        val isCurrentDeleted = currentProfileId.value in ids
        val firstDeletedIdx = currentList.indexOfFirst { it.id in ids }

        var newList = currentList.filter { it.id !in ids }
        var forcedSelection: Profile? = null
        
        if (newList.isEmpty()) {
            val defaultProfile = Profile(
                id = UUID.randomUUID().toString(),
                name = defaultProfileName
            )
            newList = listOf(defaultProfile)
            forcedSelection = defaultProfile
        }

        scope.launch {
            prefs.saveProfiles(newList)
            if (forcedSelection != null || isCurrentDeleted) {
                val toSelect = if (forcedSelection != null) {
                    forcedSelection
                } else {
                    // Try to select a neighbor (preferably the one above the deleted block)
                    val targetIndex = (firstDeletedIdx - 1).coerceIn(0, newList.size - 1)
                    newList.getOrNull(targetIndex)
                }

                if (toSelect != null) {
                    onFallback(toSelect.id, toSelect)
                }
            }
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
                ).sanitize()
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

    fun exportAllProfilesToZip(): ByteArray {
        return exportProfilesToZip(null)
    }

    fun exportProfilesToZip(ids: List<String>?): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            val usedFileNames = mutableSetOf<String>()
            profiles.value.filter { ids == null || it.id in ids }.forEach { profile ->
                val json = com.google.gson.Gson().toJson(profile)
                val safeName = profile.name.replace(Regex("[\\\\/:*?\"<>| ]"), "_")
                var entryName = "wt_$safeName.json"
                var counter = 1
                while (usedFileNames.contains(entryName)) {
                    entryName = "wt_${safeName}_$counter.json"
                    counter++
                }
                usedFileNames.add(entryName)
                
                val entry = ZipEntry(entryName)
                zos.putNextEntry(entry)
                zos.write(json.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun importProfilesFromZip(inputStream: java.io.InputStream, onAutoSelect: ((String) -> Unit)? = null) {
        try {
            val extractedData = mutableListOf<Pair<String?, String>>()
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".json")) {
                        val content = zis.readBytes().toString(Charsets.UTF_8)
                        extractedData.add(entry.name to content)
                    }
                    entry = zis.nextEntry
                }
            }
            if (extractedData.isNotEmpty()) {
                importProfiles(extractedData, onAutoSelect)
            }
        } catch (_: Exception) {}
    }

    fun importProfiles(data: List<Pair<String?, String>>, onAutoSelect: ((String) -> Unit)? = null) {
        try {
            val gson = com.google.gson.Gson()
            val newProfiles = data.mapNotNull { (fileName, json) ->
                try {
                    val p = gson.fromJson(json, Profile::class.java) ?: return@mapNotNull null
                    val sanitized = p.sanitize()

                    val nameFromFile = fileName?.removeSuffix(".json")?.removePrefix("wt_")

                    sanitized.copy(
                        id = UUID.randomUUID().toString(),
                        name = sanitized.name.ifBlank { nameFromFile?.take(100) ?: "Imported" }
                    )
                } catch (_: Exception) {
                    null
                }
            }
            if (newProfiles.isEmpty()) return

            val currentProfiles = profiles.value
            val shouldReplace = currentProfiles.size == 1 && currentProfiles[0].isEmpty()

            val newList = if (shouldReplace) newProfiles else currentProfiles + newProfiles

            scope.launch {
                prefs.saveProfiles(newList)
                if (shouldReplace) {
                    onAutoSelect?.invoke(newProfiles.first().id)
                }
            }
        } catch (_: Exception) {}
    }
}
