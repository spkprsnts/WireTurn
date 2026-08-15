package com.wireturn.app.domain

import com.wireturn.app.R
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription
import com.wireturn.app.data.ProfileBundle
import com.wireturn.app.data.FreeTurnConfig
import com.wireturn.app.data.KernelConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
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

    val subscriptions: StateFlow<List<Subscription>> = prefs.subscriptionsFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapterFactory(com.wireturn.app.data.SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(com.wireturn.app.data.KernelConfig::class.java, com.wireturn.app.data.KernelConfigAdapter())
        .create()

    fun selectProfile(id: String, profile: Profile? = null, onConfigLoaded: (Profile) -> Unit) {
        val targetProfile = profile ?: profiles.value.find { it.id == id } ?: return
        scope.launch {
            onConfigLoaded(targetProfile)
        }
    }

    fun nextDefaultProfileName(existing: List<Profile> = profiles.value): String {
        val base = prefs.context.getString(R.string.profile_default_name)
        val names = existing.map { it.name }.toSet()
        var n = 1
        while ("$base #$n" in names) n++
        return "$base #$n"
    }

    fun cloneProfile(id: String, newName: String) {
        val currentList = profiles.value
        val profile = currentList.find { it.id == id } ?: return
        val validatedName = newName.takeIf { it.isNotBlank() } ?: nextDefaultProfileName(currentList)
        val clonedProfile = profile.copy(
            id = UUID.randomUUID().toString(),
            name = validatedName
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
        val validatedName = newName.takeIf { it.isNotBlank() } ?: nextDefaultProfileName()
        val newList = profiles.value.map { if (it.id == id) it.copy(name = validatedName) else it }
        scope.launch { prefs.saveProfiles(newList) }
    }

    fun reorderProfiles(newList: List<Profile>) {
        scope.launch { prefs.saveProfiles(newList) }
    }

    fun updateCurrentProfile(profile: Profile) {
        val defaultName = prefs.context.getString(R.string.profile_default_name)
        val newList = profiles.value.map { if (it.id == profile.id) profile.sanitize(defaultName) else it }
        if (newList != profiles.value) {
            scope.launch { prefs.saveProfiles(newList) }
        }
    }

    fun getProfileJson(id: String): String? {
        val profile = profiles.value.find { it.id == id } ?: return null
        return gson.toJson(profile)
    }

    fun getProfilesJson(ids: List<String>): String {
        val selected = profiles.value.filter { it.id in ids }
        return gson.toJson(selected)
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

    fun importProfilesFromZip(inputStream: java.io.InputStream, onAutoSelect: ((Profile) -> Unit)? = null): Int {
        try {
            val extractedData = mutableListOf<Pair<String?, String>>()
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".json")) {
                        // Read only current entry into memory
                        val bos = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var read: Int
                        while (zis.read(buffer).also { read = it } != -1) {
                            bos.write(buffer, 0, read)
                        }
                        extractedData.add(entry.name to bos.toString("UTF-8"))
                    }
                    entry = zis.nextEntry
                }
            }
            return if (extractedData.isNotEmpty()) importProfiles(extractedData, onAutoSelect = onAutoSelect) else 0
        } catch (e: Exception) {
            com.wireturn.app.AppLogsState.addLog("ZIP Import Error: ${e.message}")
            return 0
        }
    }

    fun importProfiles(data: List<Pair<String?, String>>, subscriptionId: String? = null, onAutoSelect: ((Profile) -> Unit)? = null): Int {
        try {
            val defaultName = prefs.context.getString(R.string.profile_default_name)
            val currentProfiles = profiles.value
            val currentSelectedId = currentProfileId.value
            val wasSelectedFromThisSub = currentProfiles.find { it.id == currentSelectedId }?.subscriptionId == subscriptionId
            
            val existingSubProfiles = if (subscriptionId != null) {
                currentProfiles.filter { it.subscriptionId == subscriptionId }
            } else emptyList()

            val accumulating = currentProfiles.toMutableList()
            val importedList = mutableListOf<Profile>()

            data.forEach { (fileName, json) ->
                try {
                    val element = JsonParser.parseString(json)
                    val profilesToImport = if (element.isJsonArray) {
                        gson.fromJson(element, Array<Profile>::class.java)?.toList() ?: emptyList()
                    } else {
                        listOf(gson.fromJson(element, Profile::class.java))
                    }

                    profilesToImport.filterNotNull().forEach { p ->
                        val nameFromFile = fileName?.removeSuffix(".json")?.removePrefix("wt_")
                        val name = (p.name as String?)?.takeIf { it.isNotBlank() }
                            ?: nameFromFile?.takeIf { it.isNotBlank() }?.take(100)
                            ?: nextDefaultProfileName(accumulating)

                        // Try to preserve ID by matching name within the same subscription
                        val matchedOldId = existingSubProfiles.find { it.name == name }?.id
                        val newId = matchedOldId ?: UUID.randomUUID().toString()

                        val profile = p.sanitize(defaultName).copy(
                            id = newId,
                            name = name,
                            subscriptionId = subscriptionId
                        )
                        accumulating.add(profile)
                        importedList.add(profile)
                    }
                } catch (_: Exception) {}
            }

            if (importedList.isEmpty()) return 0
            val wasEmpty = currentProfiles.isEmpty()
            
            val newList = if (subscriptionId != null) {
                currentProfiles.filter { it.subscriptionId != subscriptionId } + importedList
            } else {
                currentProfiles + importedList
            }

            scope.launch {
                prefs.saveProfiles(newList)
                
                val updatedVersionOfCurrent = importedList.find { it.id == currentSelectedId }
                val isStillSelected = newList.any { it.id == currentSelectedId }
                
                if (updatedVersionOfCurrent != null) {
                    // Sync active config if the currently selected profile was updated
                    onAutoSelect?.invoke(updatedVersionOfCurrent)
                } else if (wasEmpty || (wasSelectedFromThisSub && !isStillSelected)) {
                    // Auto-select first from imported if nothing was selected or selected one disappeared
                    onAutoSelect?.invoke(importedList.first())
                } else if (!isStillSelected && newList.isNotEmpty()) {
                    // Fallback to first ever if current is gone for some other reason
                    onAutoSelect?.invoke(newList.first())
                }
            }
            return importedList.size
        } catch (_: Exception) {
            return 0
        }
    }

    suspend fun fetchSubscription(url: String, onAutoSelect: ((Profile) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "WireTurn/1.0")
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                com.wireturn.app.AppLogsState.addLog("Subscription HTTP Error: $responseCode")
                return@withContext false
            }

            val content = connection.inputStream.bufferedReader().use { it.readText() }
            if (content.isBlank()) {
                com.wireturn.app.AppLogsState.addLog("Subscription Error: Empty response")
                return@withContext false
            }
            val bundle = try {
                gson.fromJson(content, ProfileBundle::class.java)
            } catch (e: Exception) {
                // Try if it's a direct array of profiles
                try {
                    val profiles = gson.fromJson<List<Profile>>(content, object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type)
                    ProfileBundle(profiles = profiles)
                } catch (e2: Exception) {
                    // Try if it's a wireturn:// link body
                    val decoded = ProfileEncoder.decode(content)
                    if (decoded != null) {
                        val element = JsonParser.parseString(decoded)
                        val profiles = if (element.isJsonArray) {
                            gson.fromJson<List<Profile>>(element, object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type)
                        } else {
                            listOf(gson.fromJson(element, Profile::class.java))
                        }
                        ProfileBundle(profiles = profiles)
                    } else {
                        // Try if it's a legacy text subscription format (Free Turn / olcrtc)
                        tryParseTextSubscription(content)
                    }
                }
            } ?: return@withContext false

            val subId = subscriptions.value.find { it.url == url }?.id ?: UUID.randomUUID().toString()
            val subName = bundle.name ?: connection.getHeaderField("Profile-Title") ?: URL(url).host ?: "Subscription"
            
            val newSubscription = Subscription(
                id = subId,
                name = subName,
                url = url,
                description = bundle.description,
                updatedAt = bundle.updatedAt ?: System.currentTimeMillis(),
                bytesUsed = bundle.bytesUsed ?: 0,
                bytesTotal = bundle.bytesTotal ?: 0
            )

            // Update subscriptions list preserving order
            val currentSubs = subscriptions.value
            val newSubs = if (currentSubs.any { it.id == subId }) {
                currentSubs.map { if (it.id == subId) newSubscription else it }
            } else {
                currentSubs + newSubscription
            }
            prefs.saveSubscriptions(newSubs)

            // Import profiles with auto-select support
            val bundleJsonList = bundle.profiles.map { null to gson.toJson(it) }
            importProfiles(bundleJsonList, subscriptionId = subId, onAutoSelect = onAutoSelect)
            true
        } catch (e: Exception) {
            com.wireturn.app.AppLogsState.addLog("Subscription Error: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    fun deleteSubscription(id: String) {
        scope.launch {
            val newSubs = subscriptions.value.filter { it.id != id }
            prefs.saveSubscriptions(newSubs)
            
            val newProfiles = profiles.value.filter { it.subscriptionId != id }
            prefs.saveProfiles(newProfiles)
        }
    }

    private fun tryParseTextSubscription(text: String): ProfileBundle? {
        if (!text.contains("freeturn://") && !text.contains("olcrtc://") && 
            !text.contains("turnable://") && !text.contains("webdav://") && 
            !text.contains("webdavs://") && !text.contains("#name:")) return null

        val lines = text.lines()
        var subName: String? = null
        var subDescription: String? = null

        val profiles = mutableListOf<Profile>()
        var currentProfile: Profile? = null
        var currentKernelConfig: KernelConfig? = null

        fun flush() {
            val p = currentProfile ?: return
            val kc = currentKernelConfig ?: return
            profiles.add(p.copy(kernelConfig = kc))
            currentProfile = null
            currentKernelConfig = null
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("freeturn://")) {
                flush()
                val config = com.wireturn.app.data.FreeTurnConfig.parse(trimmed) ?: continue
                
                // Try to extract name from URI if possible
                val nameFromUri = try {
                    val base64 = trimmed.substringAfter("freeturn://")
                    val jsonStr = String(android.util.Base64.decode(base64, android.util.Base64.URL_SAFE))
                    JsonParser.parseString(jsonStr).asJsonObject.get("name")?.asString
                } catch(_: Exception) { null }

                currentKernelConfig = KernelConfig.FreeTurn(config)
                currentProfile = Profile(
                    id = UUID.randomUUID().toString(),
                    name = nameFromUri ?: "FreeTurn Server",
                    kernelConfig = KernelConfig.FreeTurn(config)
                )
            } else if (trimmed.startsWith("olcrtc://")) {
                flush()
                val config = com.wireturn.app.data.OlcrtcConfig.parse(trimmed) ?: continue
                
                // olcrtc://<Provider>?<Transport>@<RoomID>#<EncryptionKey>$<MIMO>
                // Use MIMO as name if it's there
                val nameFromMimo = config.mimo.takeIf { it.isNotBlank() }

                currentKernelConfig = KernelConfig.Olcrtc(config)
                currentProfile = Profile(
                    id = UUID.randomUUID().toString(),
                    name = nameFromMimo ?: "Olcrtc Server",
                    kernelConfig = KernelConfig.Olcrtc(config)
                )
            } else if (trimmed.startsWith("turnable://")) {
                flush()
                val config = com.wireturn.app.data.TurnableConfig.parse(trimmed) ?: continue
                
                // Use fragment as name if it's there
                val nameFromUri = try { android.net.Uri.parse(trimmed).fragment?.split(",")?.firstOrNull()?.trim() } catch(_: Exception) { null }

                currentKernelConfig = KernelConfig.Turnable(config)
                currentProfile = Profile(
                    id = UUID.randomUUID().toString(),
                    name = nameFromUri ?: "Turnable Server",
                    kernelConfig = KernelConfig.Turnable(config)
                )
            } else if (trimmed.startsWith("webdav://") || trimmed.startsWith("webdavs://")) {
                flush()
                val config = com.wireturn.app.data.WebdavConfig.parse(trimmed) ?: continue
                
                // Use fragment as name if it's there
                val nameFromUri = try { android.net.Uri.parse(trimmed).fragment } catch(_: Exception) { null }

                currentKernelConfig = KernelConfig.Webdav(config)
                currentProfile = Profile(
                    id = UUID.randomUUID().toString(),
                    name = nameFromUri ?: "WebDAV Server",
                    kernelConfig = KernelConfig.Webdav(config)
                )
            } else if (trimmed.startsWith("##")) {
                if (currentProfile == null) continue
                val parts = trimmed.substring(2).split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    when (key) {
                        "name" -> currentProfile = currentProfile?.copy(name = value)
                        "ip" -> {
                            val kc = currentKernelConfig
                            if (kc is KernelConfig.FreeTurn) {
                                currentKernelConfig = KernelConfig.FreeTurn(kc.config.copy(peer = value))
                            }
                        }
                        "provider" -> {
                            val kc = currentKernelConfig
                            if (kc is KernelConfig.FreeTurn) {
                                currentKernelConfig = KernelConfig.FreeTurn(kc.config.copy(provider = value))
                            } else if (kc is KernelConfig.Olcrtc) {
                                currentKernelConfig = KernelConfig.Olcrtc(kc.config.copy(provider = value))
                            }
                        }
                        "comment" -> {
                            if (currentProfile?.name?.contains("Server") == true) {
                                currentProfile = currentProfile?.copy(name = value)
                            }
                        }
                    }
                }
            } else if (trimmed.startsWith("#")) {
                val parts = trimmed.substring(1).split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    when (key) {
                        "name" -> subName = value
                        "description" -> subDescription = value
                        "comment" -> if (subDescription == null) subDescription = value
                    }
                }
            }
        }
        flush()

        if (profiles.isEmpty()) return null
        return ProfileBundle(
            name = subName,
            description = subDescription,
            profiles = profiles
        )
    }
}
