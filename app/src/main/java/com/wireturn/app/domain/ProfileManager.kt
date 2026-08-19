package com.wireturn.app.domain

import androidx.core.net.toUri
import com.google.gson.JsonParser
import com.wireturn.app.R
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.OlcrtcConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.ProfileBundle
import com.wireturn.app.data.Subscription
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

data class ImportResult(
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val total: Int = 0
)

sealed class ImportStatus {
    data class Success(val id: String? = null, val summary: ImportResult? = null) : ImportStatus()
    data class KernelConfigDetected(val type: String, val json: String, val source: String) : ImportStatus()
    object NetworkError : ImportStatus()
    data class ServerError(val code: Int) : ImportStatus()
    object EmptyResponse : ImportStatus()
    object InvalidFormat : ImportStatus()
}

/**
 * What a `wireturn://`/`wt://` link resolves to, without importing anything yet - used to show a
 * confirmation dialog before [ProfileManager.importProfiles]/[ProfileManager.fetchSubscription]
 * actually runs, since deep links can be opened from outside the app (browser, messenger, QR
 * scanner) with no prior user action inside WireTurn.
 */
sealed class DeepLinkPreview {
    /** [names] may contain blank entries where a bundled profile has no `name` set; [count] never does. */
    data class Profiles(val count: Int, val names: List<String>, val json: String) : DeepLinkPreview()
    data class SubscriptionLink(val url: String) : DeepLinkPreview()
    object Invalid : DeepLinkPreview()
}

/**
 * Decodes a `wireturn://`/`wt://` link and classifies its payload. The container itself is
 * scheme-agnostic (see [ProfileEncoder]): if the decoded text is a bare http(s) URL, the link
 * is offered as a subscription source; otherwise it's parsed as a Profile/ProfileBundle JSON.
 */
fun previewDeepLink(link: String): DeepLinkPreview {
    val trimmed = link.trim()
    val encoded = when {
        trimmed.startsWith("wireturn://", ignoreCase = true) -> trimmed.substringAfter("://")
        trimmed.startsWith("wt://", ignoreCase = true) -> trimmed.substringAfter("://")
        else -> return DeepLinkPreview.Invalid
    }
    val decoded = ProfileEncoder.decode(encoded)?.trim()?.takeIf { it.isNotEmpty() } ?: return DeepLinkPreview.Invalid

    if (decoded.startsWith("https://", ignoreCase = true) || decoded.startsWith("http://", ignoreCase = true)) {
        return DeepLinkPreview.SubscriptionLink(decoded)
    }

    return try {
        val element = JsonParser.parseString(decoded)
        val entries = when {
            element.isJsonArray -> element.asJsonArray.mapNotNull { try { it.asJsonObject } catch (_: Exception) { null } }
            element.isJsonObject -> listOf(element.asJsonObject)
            else -> return DeepLinkPreview.Invalid
        }
        if (entries.isEmpty()) return DeepLinkPreview.Invalid
        val names = entries.map { it.get("name")?.asString?.takeIf(String::isNotBlank) ?: "" }
        DeepLinkPreview.Profiles(entries.size, names, decoded)
    } catch (_: Exception) {
        DeepLinkPreview.Invalid
    }
}

private data class ActiveSocksTarget(val addr: String, val user: String?, val pass: String?)

/**
 * SOCKS proxy for whichever local core (Xray, or OLCRTC/WEBDAV via CoreService) is currently
 * running, or [java.net.Proxy.NO_PROXY] if none is. Shared by [ProfileManager.fetchSubscription],
 * [AppUpdater] and [com.wireturn.app.viewmodel.MainViewModel]'s ping check so update/subscription/
 * ping requests route through the active tunnel.
 *
 * Also brings the JVM-wide SOCKS5 [java.net.Authenticator] in line with whichever target this
 * resolves to. XrayServiceState.setSession() already does this reactively for the Xray case; the
 * OLCRTC/WEBDAV-direct case has no equivalent hook (CoreServiceState.setSession() only stores the
 * session), so it's covered here at the point of use instead - callers like the ping check that
 * poll this every second keep it correct even as the active source changes underneath them.
 */
fun activeLocalSocksProxy(): java.net.Proxy {
    val target = try {
        val xraySess = com.wireturn.app.XrayServiceState.session.value
        val xrayState = com.wireturn.app.XrayServiceState.state.value
        val coreSess = com.wireturn.app.CoreServiceState.session.value
        val coreIsWorking = com.wireturn.app.CoreServiceState.isWorking.value

        when {
            // Same "is Xray in the picture at all" test CoreService.startVpnSupervisor() uses to pick
            // VPN's target - Xray keeps priority any time it isn't Idle (Starting/Connecting included),
            // not just once fully Running, so ping/subscription requests always follow where traffic
            // is actually routed instead of racing ahead of it during startup.
            xrayState != com.wireturn.app.viewmodel.XrayState.Idle && xraySess != null -> {
                val s = xraySess.settings
                ActiveSocksTarget(
                    s.connectableAddress,
                    s.proxyUser.takeIf { s.isProxyAuthEnabled && it.isNotBlank() },
                    s.proxyPass
                )
            }
            coreIsWorking && coreSess != null && coreSess.clientConfig.kernelVariant.isSocks5Native -> {
                val cc = coreSess.clientConfig
                ActiveSocksTarget(
                    cc.socksAddr.replace("0.0.0.0:", "127.0.0.1:"),
                    cc.socksUser.takeIf { cc.isSocksAuthEnabled && it.isNotBlank() },
                    cc.socksPass
                )
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    } ?: return java.net.Proxy.NO_PROXY

    if (target.user != null) {
        val user = target.user
        val pass = target.pass ?: ""
        System.setProperty("java.net.socks.username", user)
        System.setProperty("java.net.socks.password", pass)
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication() = java.net.PasswordAuthentication(user, pass.toCharArray())
        })
    } else {
        System.clearProperty("java.net.socks.username")
        System.clearProperty("java.net.socks.password")
        java.net.Authenticator.setDefault(null)
    }

    return try {
        val host = target.addr.substringBeforeLast(':')
        val port = target.addr.substringAfterLast(':').toIntOrNull() ?: 1080
        java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved(host, port))
    } catch (_: Exception) {
        java.net.Proxy.NO_PROXY
    }
}

/** Android 17+ blocks TCP to LAN/loopback addresses without the ACCESS_LOCAL_NETWORK runtime permission. */
fun isLocalNetworkHost(url: String): Boolean {
    val host = try { java.net.URI(url).host } catch (_: Exception) { null } ?: return false
    if (host.equals("localhost", ignoreCase = true)) return true
    val octets = host.split(".")
    if (octets.size != 4) return false
    val nums = octets.map { it.toIntOrNull() ?: return false }
    if (nums.any { it !in 0..255 }) return false
    val (a, b) = nums
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
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
                        val isTunnelActive = activeLocalSocksProxy() != java.net.Proxy.NO_PROXY
                        val shouldUpdate = (!sub.onlyUpdateIfSelected || isSelected) &&
                                           (!sub.requireTunnelForUpdate || isTunnelActive)
                        
                        if (shouldUpdate) {
                            val intervalMs = sub.updateIntervalMinutes.toLong() * 60_000L
                            if (now - sub.updatedAt >= intervalMs) {
                                launch { fetchSubscription(sub.url, forceId = sub.id) }
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
            name = validatedName,
            // A clone is a standalone copy, not a member of the source subscription - keeping the
            // subscriptionId would make the next subscription sync silently delete it as a stale entry.
            subscriptionId = null,
            subscriptionSourceId = null
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
        val parsed = data.flatMap { (fileName, json) ->
            try {
                val element = JsonParser.parseString(json)
                val profilesToImport = if (element.isJsonArray) {
                    gson.fromJson(element, Array<Profile>::class.java)?.toList() ?: emptyList()
                } else {
                    listOf(gson.fromJson(element, Profile::class.java))
                }
                profilesToImport.map { fileName to it }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return importParsedProfiles(parsed, subscriptionId, serverActiveId, onAutoSelect)
    }

    /**
     * Same as [importProfiles] but takes already-deserialized profiles (e.g. from a subscription's
     * JSON bundle), skipping a redundant serialize-to-JSON-then-reparse round trip.
     * (Named distinctly rather than overloaded: List<Pair<String?, String>> and List<Profile> erase
     * to the same JVM signature.)
     */
    fun importProfileObjects(
        importedProfiles: List<Profile>,
        subscriptionId: String? = null,
        serverActiveId: String? = null,
        onAutoSelect: ((Profile) -> Unit)? = null
    ): Triple<ImportResult, List<Profile>, String?> =
        importParsedProfiles(importedProfiles.map { null to it }, subscriptionId, serverActiveId, onAutoSelect)

    private fun importParsedProfiles(
        data: List<Pair<String?, Profile>>,
        subscriptionId: String?,
        serverActiveId: String?,
        onAutoSelect: ((Profile) -> Unit)?
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

            data.forEach { (fileName, p) ->
                val nameFromFile = fileName?.removeSuffix(".json")?.removePrefix("wt_")
                val name = (p.name as String?)?.takeIf { it.isNotBlank() }
                    ?: nameFromFile?.takeIf { it.isNotBlank() }?.take(100)
                    ?: nextDefaultProfileName(accumulating)

                // 1. Try to preserve local ID by matching the server's own stable per-profile id first
                // (survives renames and doesn't collide when two entries share a display name). Only
                // fall back to matching by name for local profiles that have no recorded source id -
                // i.e. the subscription didn't send a stable "id" for that entry on a previous sync.
                // A local profile that HAS a source id must never be re-matched by name alone, or a
                // same-named-but-different new entry would silently steal its local id (and with it,
                // "is this still selected" continuity) out from under it.
                val sourceId = p.id.takeIf { it.isNotBlank() }
                val existing = (sourceId?.let { sid ->
                    existingSubProfiles.find { it.subscriptionSourceId == sid && it.id !in usedInThisBatchIds }
                }) ?: existingSubProfiles.find { it.subscriptionSourceId == null && it.name == name && it.id !in usedInThisBatchIds }
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
                    subscriptionId = subscriptionId,
                    subscriptionSourceId = subscriptionId?.let { sourceId }
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

        // Defense in depth: the network security config permits cleartext app-wide (it has to, for
        // arbitrary LAN subscription servers), so enforce HTTPS for non-local hosts here instead.
        if (url.startsWith("http://") && !isLocalNetworkHost(url)) {
            com.wireturn.app.AppLogsState.addLog("Subscription Error: refusing cleartext HTTP to non-local host")
            subIdToMark?.let { id -> _updatingSubIds.update { it - id } }
            return@withContext ImportStatus.NetworkError
        }

        val proxy = activeLocalSocksProxy()

        val connection = try {
            try {
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
        } catch (e: Exception) {
            // Covers both "no proxy, direct attempt failed" and "proxy failed, direct fallback also failed" -
            // previously these could throw past this point uncaught, permanently killing the auto-update loop.
            com.wireturn.app.AppLogsState.addLog("Subscription Error: ${e.javaClass.simpleName} - ${e.message}")
            subIdToMark?.let { id -> _updatingSubIds.update { it - id } }
            return@withContext ImportStatus.NetworkError
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
                            gson.fromJson(content, ProfileBundle::class.java)?.also {
                                @Suppress("SENSELESS_COMPARISON")
                                if (it.profiles == null) throw com.google.gson.JsonSyntaxException("missing profiles")
                            }
                        } catch (_: Exception) {
                            try {
                                val profiles = gson.fromJson<List<Profile>>(content, object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type)
                                ProfileBundle(profiles = profiles)
                            } catch (_: Exception) {
                                tryParseOlcboxBundle(content) ?: run {
                                    val decoded = ProfileEncoder.decode(content)
                                    if (decoded != null) {
                                        val element = JsonParser.parseString(decoded)
                                        val profiles = if (element.isJsonArray) {
                                            gson.fromJson(element, object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type)
                                        } else {
                                            listOf(gson.fromJson(element, Profile::class.java))
                                        }
                                        ProfileBundle(profiles = profiles)
                                    } else {
                                        tryParseTextSubscription(content)
                                    }
                                }
                            }
                        }

                        if (bundle != null) {
                            val subId = subIdToMark ?: subscriptions.value.find { it.url == url }?.id ?: UUID.randomUUID().toString()
                            val existingSub = subscriptions.value.find { it.id == subId }
                            val subName = bundle.name ?: connection.getHeaderField("Profile-Title") ?: URL(url).host ?: "Subscription"
                            
                            kotlinx.coroutines.yield()

                            // 1. Import profiles with the recommendation ID from the server
                            val (importResult, importedProfiles, resolvedActiveId) = importProfileObjects(
                                importedProfiles = bundle.profiles,
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
                                    ?: 1440,
                                onlyUpdateIfSelected = existingSub?.onlyUpdateIfSelected ?: false,
                                requireTunnelForUpdate = existingSub?.requireTunnelForUpdate ?: false
                            )

                            // 3. Save subscription info
                            val currentSubs = subscriptions.value
                            val newSubs = if (currentSubs.any { it.id == subId }) {
                                currentSubs.map { if (it.id == subId) newSubscription else it }
                            } else {
                                currentSubs + newSubscription
                            }
                            prefs.saveSubscriptions(newSubs)
                            
                            ImportStatus.Success(subId, importResult)
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

    // Reads olcbox's (github.com/alananisimov/olcbox) native LocationBundleV4 JSON subscription/export
    // format, e.g. {"version":5,"active_location_id":"...","locations":[{"storage_id":"...",
    // "name":"...","endpoint":{"room_id":"...","key":"..."},"auth_provider":"jitsi",
    // "transport":{"type":"vp8channel","vp8":{"fps":60,"batch":64}},"dns_server":"..."}]}.
    // "transport" may also be a bare string (its legacy compact form). Unlike WireTurn's own
    // ProfileBundle, every field here is olcbox-specific, so this is a distinct entry point rather
    // than something the OlcrtcConfig/Profile Gson adapters could be taught to also accept.
    private fun tryParseOlcboxBundle(content: String): ProfileBundle? {
        val root = try { JsonParser.parseString(content).asJsonObject } catch (_: Exception) { return null }
        val locationsArray = try { root.getAsJsonArray("locations") } catch (_: Exception) { null } ?: return null

        val profiles = locationsArray.mapNotNull { element ->
            val item = try { element.asJsonObject } catch (_: Exception) { return@mapNotNull null }
            parseOlcboxLocationEntry(item)
        }
        if (profiles.isEmpty()) return null

        val activeStorageId = (root.get("active_location_id") ?: root.get("activeLocationId"))
            ?.takeIf { it.isJsonPrimitive }?.asString

        return ProfileBundle(profiles = profiles, recommendedProfileId = activeStorageId)
    }

    private fun parseOlcboxLocationEntry(item: com.google.gson.JsonObject): Profile? {
        fun str(vararg keys: String): String? {
            for (k in keys) {
                val v = item.get(k)
                if (v != null && v.isJsonPrimitive) return v.asString
            }
            return null
        }
        fun obj(key: String): com.google.gson.JsonObject? =
            item.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        val endpoint = obj("endpoint")
        val provider = str("auth_provider", "authProvider", "carrier", "bypass_provider", "bypassProvider", "provider")
            ?: "wbstream"
        val roomId = endpoint?.get("room_id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: str("id", "room_id", "server")
        val key = endpoint?.get("key")?.takeIf { it.isJsonPrimitive }?.asString
            ?: str("key", "password")
        if (roomId.isNullOrBlank() || key.isNullOrBlank()) return null

        val transportElement = item.get("transport")
        val transportObj = transportElement?.takeIf { it.isJsonObject }?.asJsonObject
        val transport = when {
            transportElement != null && transportElement.isJsonPrimitive -> transportElement.asString
            transportObj != null -> transportObj.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            else -> null
        } ?: "datachannel"
        val vp8 = transportObj?.get("vp8")?.takeIf { it.isJsonObject }?.asJsonObject
        val vp8Fps = (vp8?.get("fps")?.takeIf { it.isJsonPrimitive }?.asInt)
            ?: str("vp8_fps", "vp8Fps")?.toIntOrNull()
        val vp8Batch = (vp8?.get("batch")?.takeIf { it.isJsonPrimitive }?.asInt)
            ?: str("vp8_batch", "vp8Batch")?.toIntOrNull()
        val dns = str("dns_server", "dnsServer")

        val storageId = str("storage_id", "storageId") ?: ""
        val metadataName = obj("metadata")?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        val name = str("name")?.takeIf { it.isNotBlank() }
            ?: metadataName?.takeIf { it.isNotBlank() }
            ?: roomId

        var config = OlcrtcConfig(provider = provider, transport = transport, id = roomId, key = key)
        vp8Fps?.let { config = config.copy(vp8Fps = it) }
        vp8Batch?.let { config = config.copy(vp8Batch = it) }
        dns?.takeIf { it.isNotBlank() }?.let { config = config.copy(dns = it) }

        return Profile(id = storageId, name = name, kernelConfig = KernelConfig.Olcrtc(config))
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
                val config = OlcrtcConfig.parse(trimmed) ?: continue
                
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
