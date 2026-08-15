package com.wireturn.app.domain

import com.wireturn.app.R
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription
import com.wireturn.app.data.ProfileBundle
import com.wireturn.app.data.KernelConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri

data class ImportResult(
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val total: Int = 0
)

sealed class ImportStatus {
    data class Success(val summary: ImportResult? = null) : ImportStatus()
    object NetworkError : ImportStatus()
    data class ServerError(val code: Int) : ImportStatus()
    object EmptyResponse : ImportStatus()
    object InvalidFormat : ImportStatus()
}

class ProfileManager(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope
) {
    var autoSelectListener: ((Profile) -> Unit)? = null

    val profiles: StateFlow<List<Profile>> = prefs.profilesFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val currentProfileId: StateFlow<String> = prefs.currentProfileIdFlow
        .stateIn(scope, SharingStarted.Eagerly, "default")

    val subscriptions: StateFlow<List<Subscription>> = prefs.subscriptionsFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _updatingSubIds = MutableStateFlow<Set<String>>(emptySet())
    val updatingSubIds: StateFlow<Set<String>> = _updatingSubIds.asStateFlow()

    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapterFactory(com.wireturn.app.data.SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, com.wireturn.app.data.KernelConfigAdapter())
        .create()

    private val userAgent: String by lazy {
        val version = try {
            val pInfo = prefs.context.packageManager.getPackageInfo(prefs.context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
        "WireTurn/$version"
    }

    init {
        startAutoUpdateLoop()
    }

    private fun startAutoUpdateLoop() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(60_000.milliseconds) // Check every minute
                val now = System.currentTimeMillis()
                val curId = currentProfileId.value
                val curProfiles = profiles.value
                
                subscriptions.value.forEach { sub ->
                    if (sub.autoUpdate) {
                        val isSelected = curId != "default" && curProfiles.find { it.id == curId }?.subscriptionId == sub.id
                        val shouldUpdate = !sub.onlyUpdateIfSelected || isSelected
                        
                        if (shouldUpdate) {
                            val intervalMs = sub.updateIntervalMinutes * 60 * 1000L
                            if (now - sub.updatedAt >= intervalMs) {
                                fetchSubscription(sub.url, forceId = sub.id)
                            }
                        }
                    }
                }
            }
        }
    }

    fun selectProfile(id: String, profile: Profile? = null, onConfigLoaded: (Profile) -> Unit) {
        val targetProfile = profile ?: profiles.value.find { it.id == id } ?: return
        
        targetProfile.subscriptionId?.let { subId ->
            updateSubscriptionActiveProfile(subId, targetProfile.id)
        }

        scope.launch {
            onConfigLoaded(targetProfile)
        }
    }

    private fun updateSubscriptionActiveProfile(subId: String, profileId: String) {
        val currentSubs = subscriptions.value
        val sub = currentSubs.find { it.id == subId } ?: return
        if (sub.activeProfileId == profileId) return
        
        val newSubs = currentSubs.map { 
            if (it.id == subId) it.copy(activeProfileId = profileId) else it 
        }
        scope.launch { prefs.saveSubscriptions(newSubs) }
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

    fun deleteProfiles(ids: List<String>, onFallback: (Profile) -> Unit) {
        val currentList = profiles.value
        val currentProfile = currentList.find { it.id == currentProfileId.value }
        val isCurrentDeleted = currentProfile?.id in ids
        val preferredSubId = currentProfile?.subscriptionId

        val newList = currentList.filter { it.id !in ids }

        scope.launch {
            prefs.saveProfiles(newList)
            if (isCurrentDeleted && newList.isNotEmpty()) {
                findBestFallbackProfile(newList, subscriptions.value, preferredSubId)?.let { onFallback(it) }
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

    fun getProfilesJson(ids: List<String>): String {
        val selected = profiles.value.filter { it.id in ids }
        return gson.toJson(selected)
    }

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

    fun importProfilesFromZip(inputStream: java.io.InputStream, onAutoSelect: ((Profile) -> Unit)? = null): ImportResult {
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
            return if (extractedData.isNotEmpty()) importProfiles(extractedData, onAutoSelect = onAutoSelect).first else ImportResult()
        } catch (e: Exception) {
            com.wireturn.app.AppLogsState.addLog("ZIP Import Error: ${e.message}")
            return ImportResult()
        }
    }

    /**
     * @return Triple of result summary, List of Profile objects with FINAL local IDs, and the local ID of the recommended profile.
     */
    fun importProfiles(
        data: List<Pair<String?, String>>, 
        subscriptionId: String? = null, 
        serverActiveId: String? = null,
        onAutoSelect: ((Profile) -> Unit)? = null
    ): Triple<ImportResult, List<Profile>, String?> {
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
            
            var addedCount = 0
            var updatedCount = 0
            var resolvedRecommendedLocalId: String? = null
            
            val matchedExistingIds = mutableSetOf<String>()
            val usedInThisBatchIds = mutableSetOf<String>()

            data.forEach { (fileName, json) ->
                try {
                    val element = JsonParser.parseString(json)
                    val profilesToImport = if (element.isJsonArray) {
                        gson.fromJson(element, Array<Profile>::class.java)?.toList() ?: emptyList()
                    } else {
                        listOf(gson.fromJson(element, Profile::class.java))
                    }

                    profilesToImport.forEach { p ->
                        val nameFromFile = fileName?.removeSuffix(".json")?.removePrefix("wt_")
                        val name = (p.name as String?)?.takeIf { it.isNotBlank() }
                            ?: nameFromFile?.takeIf { it.isNotBlank() }?.take(100)
                            ?: nextDefaultProfileName(accumulating)

                        // 1. Try to preserve local ID by matching name within the same subscription
                        val existing = existingSubProfiles.find { it.name == name && it.id !in usedInThisBatchIds }
                        val matchedOldId = existing?.id
                        
                        val newId = if (matchedOldId != null && matchedOldId !in usedInThisBatchIds) {
                            matchedOldId
                        } else {
                            UUID.randomUUID().toString()
                        }
                        
                        // 2. Map ID: check if it's the server's recommendation
                        if (p.id == serverActiveId) {
                            resolvedRecommendedLocalId = newId
                        }
                        
                        usedInThisBatchIds.add(newId)

                        val profile = p.sanitize(defaultName).copy(
                            id = newId,
                            name = name,
                            subscriptionId = subscriptionId
                        )
                        
                        if (existing != null) {
                            matchedExistingIds.add(existing.id)
                            if (existing != profile) updatedCount++
                        } else {
                            addedCount++
                        }
                        
                        accumulating.add(profile)
                        importedList.add(profile)
                    }
                } catch (_: Exception) {}
            }

            if (importedList.isEmpty()) return Triple(ImportResult(), emptyList(), null)
            val wasEmpty = currentProfiles.isEmpty()
            
            val newList = if (subscriptionId != null) {
                currentProfiles.filter { it.subscriptionId != subscriptionId } + importedList
            } else {
                currentProfiles + importedList
            }

            scope.launch {
                prefs.saveProfiles(newList)
                
                val callback = onAutoSelect ?: autoSelectListener
                val oldVersionOfCurrent = currentProfiles.find { it.id == currentSelectedId }
                val updatedVersionOfCurrent = importedList.find { it.id == currentSelectedId }
                val isStillSelected = newList.any { it.id == currentSelectedId }
                
                if (updatedVersionOfCurrent != null) {
                    // Only trigger update if content actually changed
                    if (updatedVersionOfCurrent != oldVersionOfCurrent) {
                        callback?.invoke(updatedVersionOfCurrent)
                    }
                } else if (wasEmpty || (wasSelectedFromThisSub && !isStillSelected)) {
                    // Auto-select based on recommendation OR fallback to first
                    val bestToSelect = importedList.find { it.id == resolvedRecommendedLocalId } 
                        ?: importedList.firstOrNull()
                    bestToSelect?.let { callback?.invoke(it) }
                } else if (!isStillSelected && newList.isNotEmpty()) {
                    val fallback = findBestFallbackProfile(newList, subscriptions.value)
                    fallback?.let { callback?.invoke(it) }
                }
            }
            
            val removedCount = if (subscriptionId != null) existingSubProfiles.size - matchedExistingIds.size else 0
            return Triple(ImportResult(addedCount, updatedCount, removedCount, importedList.size), importedList, resolvedRecommendedLocalId)
        } catch (_: Exception) {
            return Triple(ImportResult(), emptyList(), null)
        }
    }

    suspend fun fetchSubscription(url: String, forceId: String? = null, onAutoSelect: ((Profile) -> Unit)? = null): ImportStatus = withContext(Dispatchers.IO) {
        val subIdToMark = forceId ?: subscriptions.value.find { it.url == url }?.id
        subIdToMark?.let { id -> _updatingSubIds.update { it + id } }
        val startTime = System.currentTimeMillis()

        val proxy = try {
            val xraySess = com.wireturn.app.XrayServiceState.session.value
            val xrayState = com.wireturn.app.XrayServiceState.state.value
            val coreSess = com.wireturn.app.CoreServiceState.session.value
            val coreIsWorking = com.wireturn.app.CoreServiceState.isWorking.value

            val proxyAddr = when {
                xrayState == com.wireturn.app.viewmodel.XrayState.Running && xraySess != null -> 
                    xraySess.settings.socksBindAddress
                coreIsWorking && coreSess != null && 
                    (coreSess.clientConfig.kernelVariant == com.wireturn.app.data.KernelVariant.OLCRTC || 
                     coreSess.clientConfig.kernelVariant == com.wireturn.app.data.KernelVariant.WEBDAV) -> 
                    coreSess.clientConfig.socksAddr
                else -> null
            }

            if (proxyAddr != null) {
                val host = proxyAddr.substringBeforeLast(':').let { if (it == "0.0.0.0") "127.0.0.1" else it }
                val port = proxyAddr.substringAfterLast(':').toIntOrNull() ?: 1080
                java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(host, port))
            } else java.net.Proxy.NO_PROXY
        } catch (_: Exception) {
            java.net.Proxy.NO_PROXY
        }

        val connection = try {
            // First attempt with the calculated proxy
            val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", userAgent)
            }
            // Trigger connection to check if it works
            conn.responseCode
            conn
        } catch (e: Exception) {
            // If proxy failed and it wasn't already NO_PROXY, try direct connection
            if (proxy != java.net.Proxy.NO_PROXY) {
                (URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", userAgent)
                }
            } else throw e
        }
        
        try {
            // Register cancellation listener to disconnect the connection
            val job = launch {
                try {
                    kotlinx.coroutines.delay(Long.MAX_VALUE.milliseconds)
                } finally {
                    connection.disconnect()
                }
            }

            val result = try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    com.wireturn.app.AppLogsState.addLog("Subscription HTTP Error: $responseCode")
                    ImportStatus.ServerError(responseCode)
                } else {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }
                    if (content.isBlank()) {
                        com.wireturn.app.AppLogsState.addLog("Subscription Error: Empty response")
                        ImportStatus.EmptyResponse
                    } else {
                        kotlinx.coroutines.yield() // Check for cancellation
                        val bundle = try {
                            gson.fromJson(content, ProfileBundle::class.java)
                        } catch (_: Exception) {
                            try {
                                val profiles = gson.fromJson<List<Profile>>(content, object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type)
                                ProfileBundle(profiles = profiles)
                            } catch (_: Exception) {
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
                                    tryParseTextSubscription(content)
                                }
                            }
                        }

                        if (bundle != null) {
                            val subId = subIdToMark ?: subscriptions.value.find { it.url == url }?.id ?: UUID.randomUUID().toString()
                            val existingSub = subscriptions.value.find { it.id == subId }
                            val subName = bundle.name ?: connection.getHeaderField("Profile-Title") ?: URL(url).host ?: "Subscription"
                            
                            kotlinx.coroutines.yield()

                            // 1. Import profiles with the recommendation ID from the server
                            val bundleJsonList = bundle.profiles.map { null to gson.toJson(it) }
                            val (importResult, importedProfiles, resolvedActiveId) = importProfiles(
                                data = bundleJsonList, 
                                subscriptionId = subId, 
                                serverActiveId = bundle.recommendedProfileId,
                                onAutoSelect = onAutoSelect
                            )

                            // 2. Determine final active profile ID to save in subscription
                            val bestActiveId = resolvedActiveId ?: 
                                existingSub?.activeProfileId?.takeIf { id -> importedProfiles.any { it.id == id } } ?:
                                importedProfiles.firstOrNull()?.id

                            val newSubscription = Subscription(
                                id = subId,
                                name = subName,
                                url = url,
                                description = bundle.description,
                                updatedAt = System.currentTimeMillis(),
                                bytesUsed = bundle.bytesUsed ?: 0,
                                bytesTotal = bundle.bytesTotal ?: 0,
                                activeProfileId = bestActiveId,
                                autoUpdate = existingSub?.autoUpdate ?: (bundle.updateIntervalMinutes != null),
                                updateIntervalMinutes = existingSub?.updateIntervalMinutes 
                                    ?: bundle.updateIntervalMinutes?.coerceAtLeast(20) 
                                    ?: 1440
                            )

                            // 3. Save subscription info
                            val currentSubs = subscriptions.value
                            val newSubs = if (currentSubs.any { it.id == subId }) {
                                currentSubs.map { if (it.id == subId) newSubscription else it }
                            } else {
                                currentSubs + newSubscription
                            }
                            prefs.saveSubscriptions(newSubs)
                            
                            ImportStatus.Success(importResult)
                        } else {
                            ImportStatus.InvalidFormat
                        }
                    }
                }
            } finally {
                job.cancel()
            }
            
            // Ensure minimum visible loading time
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 300) {
                kotlinx.coroutines.delay((300 - elapsed).milliseconds)
            }
            
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.wireturn.app.AppLogsState.addLog("Subscription Error: ${e.javaClass.simpleName} - ${e.message}")
            ImportStatus.NetworkError
        } finally {
            subIdToMark?.let { id -> _updatingSubIds.update { it - id } }
            connection.disconnect()
        }
    }

    fun deleteSubscription(id: String, onFallback: (Profile) -> Unit = {}) {
        scope.launch {
            val currentSelectedId = currentProfileId.value
            val isCurrentInDeletedSub = profiles.value.find { it.id == currentSelectedId }?.subscriptionId == id

            val newSubs = subscriptions.value.filter { it.id != id }
            prefs.saveSubscriptions(newSubs)
            
            val newProfiles = profiles.value.filter { it.subscriptionId != id }
            prefs.saveProfiles(newProfiles)

            if (isCurrentInDeletedSub && newProfiles.isNotEmpty()) {
                findBestFallbackProfile(newProfiles, newSubs)?.let { onFallback(it) }
            }
        }
    }

    private fun findBestFallbackProfile(
        allProfiles: List<Profile>,
        allSubs: List<Subscription> = subscriptions.value,
        preferredSubId: String? = null
    ): Profile? {
        if (allProfiles.isEmpty()) return null

        // 1. If we were in a sub, try to stay in it
        if (preferredSubId != null) {
            val sub = allSubs.find { it.id == preferredSubId }
            if (sub != null) {
                val activeInSub = allProfiles.find { it.id == sub.activeProfileId && it.subscriptionId == sub.id }
                if (activeInSub != null) return activeInSub
                
                val firstInSub = allProfiles.find { it.subscriptionId == sub.id }
                if (firstInSub != null) return firstInSub
            }
        }

        // 2. Try first standalone profile
        val standalone = allProfiles.find { it.subscriptionId == null }
        if (standalone != null) return standalone

        // 3. Try active profile from any subscription
        allSubs.forEach { sub ->
            if (sub.id != preferredSubId) {
                val activeInSub = allProfiles.find { it.id == sub.activeProfileId && it.subscriptionId == sub.id }
                if (activeInSub != null) return activeInSub
                
                val firstInSub = allProfiles.find { it.subscriptionId == sub.id }
                if (firstInSub != null) return firstInSub
            }
        }

        // 4. Just first one ever
        return allProfiles.firstOrNull()
    }

    fun updateSubscription(sub: Subscription) {
        val currentSubs = subscriptions.value
        val newSubs = currentSubs.map { if (it.id == sub.id) sub else it }
        scope.launch { prefs.saveSubscriptions(newSubs) }
    }

    private fun tryParseTextSubscription(text: String): ProfileBundle? {
        if (!text.contains("freeturn://") && !text.contains("olcrtc://") && 
            !text.contains("turnable://") && !text.contains("webdav://") && 
            !text.contains("webdavs://") && !text.contains("#name:")) return null

        val lines = text.lines()
        var subName: String? = null
        var subDescription: String? = null
        var subInterval: Int? = null

        val profiles = mutableListOf<Profile>()
        var currentProfile: Profile? = null
        var currentKernelConfig: KernelConfig? = null

        fun parseInterval(v: String): Int? {
            val num = v.filter { it.isDigit() }.toIntOrNull() ?: return null
            return when {
                v.endsWith("s") -> num / 60
                v.endsWith("m") -> num
                v.endsWith("h") -> num * 60
                v.endsWith("d") -> num * 1440
                else -> num
            }
        }

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
                val nameFromUri = try {
                    trimmed.toUri().fragment?.split(",")?.firstOrNull()?.trim() } catch(_: Exception) { null }

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
                val nameFromUri = try {
                    trimmed.toUri().fragment } catch(_: Exception) { null }

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
                        "refresh" -> subInterval = parseInterval(value)
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
            profiles = profiles,
            updateIntervalMinutes = subInterval
        )
    }
}
