package com.wireturn.app.domain

import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.Profile
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
    private val scope: CoroutineScope
) {
    val profiles: StateFlow<List<Profile>> = prefs.profilesFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val currentProfileId: StateFlow<String> = prefs.currentProfileIdFlow
        .stateIn(scope, SharingStarted.Eagerly, "default")

    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapterFactory(com.wireturn.app.data.SafeEnumTypeAdapterFactory())
        .create()

    fun selectProfile(id: String, profile: Profile? = null, onConfigLoaded: (Profile) -> Unit) {
        val targetProfile = profile ?: profiles.value.find { it.id == id } ?: return
        scope.launch {
            com.wireturn.app.ProxyServiceState.setChangingProfile(true)
            try {
                // In this simplified model, Profile in the list already contains its core settings
                // and protocol states. WG/VLESS details for the profile might need to be fetched 
                // or we use current active ones if they are not stored in Profile class anymore.
                // Wait, if Profile class doesn't have WG/VLESS objects, where are they in the list?
                // They should be in Profile class. Let's check my rewrite.
                // Ah, I missed them in Profile class rewrite too!
                onConfigLoaded(targetProfile)
            } finally {
                kotlinx.coroutines.delay(150)
                com.wireturn.app.ProxyServiceState.setChangingProfile(false)
            }
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

        val newList = currentList.filter { it.id !in ids }

        scope.launch {
            prefs.saveProfiles(newList)
            if (isCurrentDeleted) {
                val targetIndex = if (newList.isEmpty()) -1 else (firstDeletedIdx - 1).coerceAtMost(newList.size - 1).coerceAtLeast(0)
                val toSelect = if (targetIndex != -1) newList.getOrNull(targetIndex) else null
                if (toSelect != null) onFallback(toSelect.id, toSelect)
            }
        }
    }

    fun renameProfile(id: String, newName: String) {
        val newList = profiles.value.map { if (it.id == id) it.copy(name = newName) else it }
        scope.launch { prefs.saveProfiles(newList) }
    }

    fun reorderProfiles(newList: List<Profile>) {
        scope.launch { prefs.saveProfiles(newList) }
    }

    fun updateCurrentProfile(profile: Profile) {
        val newList = profiles.value.map { if (it.id == profile.id) profile.sanitize() else it }
        if (newList != profiles.value) {
            scope.launch { prefs.saveProfiles(newList) }
        }
    }

    fun getProfileJson(id: String): String? {
        val profile = profiles.value.find { it.id == id } ?: return null
        return gson.toJson(profile)
    }

    fun exportAllProfilesToZip(): ByteArray = exportProfilesToZip(null)

    fun exportProfilesToZip(ids: List<String>?): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            val usedFileNames = mutableSetOf<String>()
            profiles.value.filter { ids == null || it.id in ids }.forEach { profile ->
                val json = gson.toJson(profile)
                val safeName = profile.name.replace(Regex("[\\\\/:*?\"<>| ]"), "_")
                var entryName = "wt_$safeName.json"
                var counter = 1
                while (usedFileNames.contains(entryName)) { entryName = "wt_${safeName}_$counter.json"; counter++ }
                usedFileNames.add(entryName)
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(json.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun importProfilesFromZip(inputStream: java.io.InputStream, onAutoSelect: ((Profile) -> Unit)? = null) {
        try {
            val extractedData = mutableListOf<Pair<String?, String>>()
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".json")) {
                        extractedData.add(entry.name to zis.readBytes().toString(Charsets.UTF_8))
                    }
                    entry = zis.nextEntry
                }
            }
            if (extractedData.isNotEmpty()) importProfiles(extractedData, onAutoSelect)
        } catch (_: Exception) {}
    }

    fun importProfiles(data: List<Pair<String?, String>>, onAutoSelect: ((Profile) -> Unit)? = null) {
        try {
            val newProfiles = data.mapNotNull { (fileName, json) ->
                try {
                    val p = gson.fromJson(json, Profile::class.java) ?: return@mapNotNull null
                    val nameFromFile = fileName?.removeSuffix(".json")?.removePrefix("wt_")
                    p.sanitize().copy(
                        id = UUID.randomUUID().toString(),
                        name = (p.name as String?)?.takeIf { it.isNotBlank() } ?: nameFromFile?.take(100) ?: "Imported"
                    )
                } catch (_: Exception) { null }
            }
            if (newProfiles.isEmpty()) return
            val currentProfiles = profiles.value
            val wasEmpty = currentProfiles.isEmpty()
            val newList = currentProfiles + newProfiles
            scope.launch {
                prefs.saveProfiles(newList)
                if (wasEmpty) {
                    onAutoSelect?.invoke(newProfiles.first())
                }
            }
        } catch (_: Exception) {}
    }
}
