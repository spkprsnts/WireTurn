package com.wireturn.app.domain

import com.google.gson.JsonParser
import com.wireturn.app.R
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription
import com.wireturn.app.data.variant
import com.wireturn.app.kernel.KernelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    data class KernelConfigDetected(val type: String, val json: String, val source: String) :
        ImportStatus()

    object NetworkError : ImportStatus()

    // The server's TLS certificate wasn't accepted (untrusted, e.g. self-signed, or issued for
    // another name) - not a connectivity problem, so not NetworkError.
    object TlsError : ImportStatus()

    // A new cleartext http:// subscription on a non-local host: its keys and server addresses
    // would travel readable and forgeable, so the user confirms it first - see fetchSubscription.
    data class InsecureHttp(val url: String) : ImportStatus()
    data class ServerError(val code: Int) : ImportStatus()
    object EmptyResponse : ImportStatus()
    object InvalidFormat : ImportStatus()

    // The subscription was deleted while it was downloading - nothing was saved, nothing to report.
    object Cancelled : ImportStatus()
}

/**
 * What a `wireturn://`/`wt://` link resolves to, without importing anything yet - used to show a
 * confirmation dialog before [ProfileManager.importProfiles]/[ProfileManager.fetchSubscription]
 * actually runs, since deep links can be opened from outside the app (browser, messenger, QR
 * scanner) with no prior user action inside WireTurn.
 */
sealed class DeepLinkPreview {
    /** [names] may contain blank entries where a bundled profile has no `name` set; [count] never does. */
    data class Profiles(val count: Int, val names: List<String>, val json: String) :
        DeepLinkPreview()

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
    val decoded = ProfileEncoder.decode(encoded)?.trim()?.takeIf { it.isNotEmpty() }
        ?: return DeepLinkPreview.Invalid

    if (decoded.startsWith("https://", ignoreCase = true) || decoded.startsWith(
            "http://",
            ignoreCase = true
        )
    ) {
        return DeepLinkPreview.SubscriptionLink(decoded)
    }

    return try {
        val element = JsonParser.parseString(decoded)
        val entries = when {
            element.isJsonArray -> element.asJsonArray.mapNotNull {
                try {
                    it.asJsonObject
                } catch (_: Exception) {
                    null
                }
            }

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

// Guards the write step in activeLocalSocksProxy() below so two concurrent callers (ping loop,
// subscription auto-update, AppUpdater) can't interleave their System-property/Authenticator writes
// and leave a torn combination (e.g. one caller's username paired with another's password) visible
// to a third party mid-connection. Deliberately NOT cached/skipped when the target is unchanged from
// last time: XrayServiceState.setSession() writes this exact same global state independently and
// reactively (see the class doc below), so re-asserting unconditionally on every call is what lets
// this function self-heal if that other writer left something stale in between polls - caching would
// silently skip the correction whenever this call's target happens to match what *this* function
// applied last, even if setSession() had since overwritten it with something else.
private val socksAuthLock = Any()

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
            // Kernel TUN mode: the kernel runs no SOCKS5 at all, and the app is excluded from its
            // tun (the kernel shares its UID) - there's no tunnel path for the app's own requests.
            com.wireturn.app.CoreServiceState.nativeTunTraffic.value != null -> null
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

    synchronized(socksAuthLock) {
        if (target.user != null) {
            val user = target.user
            val pass = target.pass ?: ""
            System.setProperty("java.net.socks.username", user)
            System.setProperty("java.net.socks.password", pass)
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication() =
                    java.net.PasswordAuthentication(user, pass.toCharArray())
            })
        } else {
            System.clearProperty("java.net.socks.username")
            System.clearProperty("java.net.socks.password")
            java.net.Authenticator.setDefault(null)
        }
    }

    return try {
        val host = target.addr.substringBeforeLast(':')
        val port = target.addr.substringAfterLast(':').toIntOrNull() ?: 1080
        java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress.createUnresolved(host, port)
        )
    } catch (_: Exception) {
        java.net.Proxy.NO_PROXY
    }
}

/** Android 17+ blocks TCP to LAN/loopback addresses without the ACCESS_LOCAL_NETWORK runtime permission. */
fun isLocalNetworkHost(url: String): Boolean {
    val host = try {
        java.net.URI(url).host
    } catch (_: Exception) {
        null
    } ?: return false
    if (host.equals("localhost", ignoreCase = true)) return true
    val octets = host.split(".")
    if (octets.size != 4) return false
    val nums = octets.map { it.toIntOrNull() ?: return false }
    if (nums.any { it !in 0..255 }) return false
    val (a, b) = nums
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}

// First retry after a failed subscription auto-update; doubles per failure, see ProfileManager.
private const val AUTO_UPDATE_RETRY_BASE_MS = 5 * 60_000L

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

    /** Last known tunnel-exit country (ISO-3166 alpha-2) per profile id, updated after each successful connection. */
    val profileCountries: StateFlow<Map<String, String>> = prefs.profileCountriesFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun saveProfileCountry(profileId: String, countryCode: String) {
        scope.launch { prefs.saveProfileCountry(profileId, countryCode) }
    }

    /**
     * Last ping per profile id - see MainViewModel.checkProxyPing: milliseconds, or [PING_FAILED]
     * when the tunnel was up but nothing got through it.
     */
    val profilePings: StateFlow<Map<String, Long>> = prefs.profilePingsFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun saveProfilePing(profileId: String, ms: Long) {
        scope.launch { prefs.saveProfilePing(profileId, ms) }
    }

    suspend fun saveProfilePings(pings: Map<String, Long>) = prefs.saveProfilePings(pings)

    companion object {
        const val PING_FAILED = -1L

    }

    private val _updatingSubIds = MutableStateFlow<Set<String>>(emptySet())
    val updatingSubIds: StateFlow<Set<String>> = _updatingSubIds.asStateFlow()

    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapterFactory(com.wireturn.app.data.SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, com.wireturn.app.data.KernelConfigAdapter())
        .create()

    private val subscriptionParser = SubscriptionParser(gson)

    private val userAgent: String by lazy {
        val version = try {
            val pInfo = prefs.context.packageManager.getPackageInfo(prefs.context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        "WireTurn/$version"
    }

    // Some subscription providers content-negotiate by User-Agent and return an HTML landing
    // page to unknown clients. With the automatic choice the normal WireTurn request goes first,
    // then only such responses are retried with the official clients' identifiers - see
    // SubscriptionUserAgents. A subscription pinned to one User-Agent is fetched with it alone.
    private val subscriptionFallbackUserAgents = SubscriptionUserAgents.autoFallbacks

    init {
        startAutoUpdateLoop()
    }

    // Failed auto-updates by subscription id: when the last one failed and how many in a row.
    // updatedAt only moves on success, so without this a subscription that kept failing was
    // retried - and logged a timeout - every minute. In memory only: a restart retries at once.
    private data class AutoUpdateFailure(val at: Long, val count: Int)

    private val autoUpdateFailures =
        java.util.concurrent.ConcurrentHashMap<String, AutoUpdateFailure>()

    private fun startAutoUpdateLoop() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(60_000.milliseconds) // Check every minute
                val now = System.currentTimeMillis()
                val curId = currentProfileId.value
                val curProfiles = profiles.value

                subscriptions.value.forEach { sub ->
                    if (sub.autoUpdate && sub.id !in _updatingSubIds.value) {
                        val isSelected =
                            curId != "default" && curProfiles.find { it.id == curId }?.subscriptionId == sub.id
                        val isTunnelActive = activeLocalSocksProxy() != java.net.Proxy.NO_PROXY
                        val shouldUpdate = (!sub.onlyUpdateIfSelected || isSelected) &&
                                (!sub.requireTunnelForUpdate || isTunnelActive)

                        if (shouldUpdate) {
                            val intervalMs = sub.updateIntervalMinutes.toLong() * 60_000L
                            // A success since the last failure (a manual refresh too) clears it.
                            val failure =
                                autoUpdateFailures[sub.id]?.takeIf { it.at > sub.updatedAt }
                            val due = if (failure == null) {
                                now - sub.updatedAt >= intervalMs
                            } else {
                                now - failure.at >= autoUpdateRetryDelayMs(
                                    failure.count,
                                    intervalMs
                                )
                            }
                            if (due) {
                                launch {
                                    val status = fetchSubscription(sub.url, forceId = sub.id)
                                    if (status is ImportStatus.Success || status is ImportStatus.Cancelled) {
                                        autoUpdateFailures.remove(sub.id)
                                    } else {
                                        val count = (failure?.count ?: 0) + 1
                                        autoUpdateFailures[sub.id] =
                                            AutoUpdateFailure(System.currentTimeMillis(), count)
                                        val nextMinutes =
                                            autoUpdateRetryDelayMs(count, intervalMs) / 60_000L
                                        com.wireturn.app.AppLogsState.addLog(
                                            "Subscription auto-update failed (${
                                                subscriptionLogLabel(
                                                    sub.id,
                                                    sub.url
                                                )
                                            }), next try in $nextMinutes min"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // The subscription's name, or the URL's host while it has none yet (a first import).
    private fun subscriptionLogLabel(subId: String?, url: String): String =
        subscriptions.value.find { it.id == subId }?.name?.takeIf { it.isNotBlank() }
            ?: runCatching { URL(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "?"

    // 5, 10, 20, 40, 80 minutes after consecutive failures, never past the subscription's own interval.
    private fun autoUpdateRetryDelayMs(failures: Int, intervalMs: Long): Long =
        (AUTO_UPDATE_RETRY_BASE_MS shl (failures - 1).coerceIn(0, 4)).coerceAtMost(intervalMs)

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
        val sub = subscriptions.value.find { it.id == subId } ?: return
        if (sub.activeProfileId == profileId) return
        updateSubscription(subId) { it.copy(activeProfileId = profileId) }
    }

    fun nextDefaultProfileName(existing: List<Profile> = profiles.value): String {
        val base = prefs.context.getString(R.string.profile_default_name)
        val names = existing.map { it.name }.toSet()
        var n = 1
        while ("$base #$n" in names) n++
        return "$base #$n"
    }

    // Every change below goes through prefs.updateProfiles/updateSubscriptions, applied to what's
    // stored at that moment - not to profiles.value/subscriptions.value, which trail the store and
    // would let two overlapping writes (two subscriptions refreshing at once) drop one's changes.

    fun cloneProfile(id: String, newName: String) {
        scope.launch {
            prefs.updateProfiles { current ->
                val profile = current.find { it.id == id } ?: return@updateProfiles current
                current + profile.copy(
                    id = UUID.randomUUID().toString(),
                    name = newName.takeIf { it.isNotBlank() } ?: nextDefaultProfileName(current),
                    // A clone is a standalone copy, not a member of the source subscription - keeping the
                    // subscriptionId would make the next subscription sync silently delete it as a stale entry.
                    subscriptionId = null,
                    subscriptionSourceId = null
                )
            }
        }
    }

    fun deleteProfiles(ids: List<String>, onFallback: (Profile) -> Unit) {
        val currentProfile = profiles.value.find { it.id == currentProfileId.value }
        val isCurrentDeleted = currentProfile?.id in ids
        val preferredSubId = currentProfile?.subscriptionId

        scope.launch {
            val newList = prefs.updateProfiles { current -> current.filter { it.id !in ids } }
            prefs.removeProfileCountries(ids)
            if (isCurrentDeleted && newList.isNotEmpty()) {
                findBestFallbackProfile(
                    newList,
                    subscriptions.value,
                    preferredSubId
                )?.let { onFallback(it) }
            }
        }
    }

    fun renameProfile(id: String, newName: String) {
        scope.launch {
            prefs.updateProfiles { current ->
                val validatedName =
                    newName.takeIf { it.isNotBlank() } ?: nextDefaultProfileName(current)
                current.map { if (it.id == id) it.copy(name = validatedName) else it }
            }
        }
    }

    /**
     * The order of [newList], applied to the stored profiles: one changed meanwhile (a refresh)
     * keeps its new content, one added meanwhile goes to the end, one deleted meanwhile stays gone.
     */
    fun reorderProfiles(newList: List<Profile>) {
        val order = newList.withIndex().associate { (index, profile) -> profile.id to index }
        scope.launch {
            prefs.updateProfiles { current -> current.sortedBy { order[it.id] ?: Int.MAX_VALUE } }
        }
    }

    fun updateCurrentProfile(profile: Profile) {
        val sanitized = profile.sanitize(prefs.context.getString(R.string.profile_default_name))
        if (profiles.value.find { it.id == profile.id } == sanitized) return
        scope.launch {
            prefs.updateProfiles { current -> current.map { if (it.id == profile.id) sanitized else it } }
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
                while (usedFileNames.contains(entryName)) {
                    entryName = "wt_${safeName}_$counter.json"; counter++
                }
                usedFileNames.add(entryName)
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(json.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    fun importProfilesFromZip(
        inputStream: java.io.InputStream,
        onAutoSelect: ((Profile) -> Unit)? = null
    ): ImportResult {
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
            return if (extractedData.isNotEmpty()) importProfiles(
                extractedData,
                onAutoSelect = onAutoSelect
            ).first else ImportResult()
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
        importParsedProfiles(
            importedProfiles.map { null to it },
            subscriptionId,
            serverActiveId,
            onAutoSelect
        )

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
            val wasSelectedFromThisSub =
                currentProfiles.find { it.id == currentSelectedId }?.subscriptionId == subscriptionId

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
                })
                    ?: existingSubProfiles.find { it.subscriptionSourceId == null && it.name == name && it.id !in usedInThisBatchIds }
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

                val sanitized = p.sanitize(defaultName)
                // Text subscriptions (and JSON entries that just don't bother) carry no Xray config of
                // their own, so `sanitized` comes back with the all-defaults/empty state here. Without
                // this check, every refresh would blow away Xray/dual-route settings the user configured
                // locally on top of the subscription's tunnel config. Only fall back to the previous
                // local values when the subscription didn't actually specify a usable link/wg config -
                // if it did, that takes precedence (including turning Xray off, since a config change
                // implies the entry is being actively managed by the subscription).
                //
                // Deliberately keyed on wgConfig/vlessConfig validity alone, NOT sanitized.xrayEnabled:
                // a subscription entry can't express "explicitly disabled, no link either" any
                // differently from "doesn't mention Xray at all" through this data model (Gson can't
                // tell a present-but-false field from an absent one), so treating a bare xrayEnabled by
                // itself as authoritative would risk the opposite failure mode - a sub entry with
                // xrayEnabled=true but no link would otherwise turn Xray on locally while wiping out
                // the user's actual working link, leaving a broken enabled-but-empty state.
                val subscriptionHasXrayConfig =
                    sanitized.wgConfig.isValid() || sanitized.vlessConfig.isValid()

                // Same overwrite problem as the Xray fields above, for what a kernel's link doesn't
                // carry (FreeTurn's call links, CSQTT's local settings, qWDTT's device id) - each
                // kernel keeps those from the local profile (Kernel.mergeSubscriptionUpdate).
                val mergedKernelConfig = run {
                    val newKc = sanitized.kernelConfig
                    val oldKc = existing?.kernelConfig
                    val kernel = KernelRegistry.get(newKc.variant)
                    if (oldKc != null && oldKc.variant == newKc.variant) kernel.mergeSubscriptionUpdate(
                        oldKc,
                        newKc
                    ) else newKc
                }

                val profile = sanitized.copy(
                    id = newId,
                    name = name,
                    subscriptionId = subscriptionId,
                    subscriptionSourceId = subscriptionId?.let { sourceId },
                    kernelConfig = mergedKernelConfig,
                    xrayEnabled = if (!subscriptionHasXrayConfig && existing != null) existing.xrayEnabled else sanitized.xrayEnabled,
                    xrayProtocol = if (!subscriptionHasXrayConfig && existing != null) existing.xrayProtocol else sanitized.xrayProtocol,
                    wgConfig = if (!subscriptionHasXrayConfig && existing != null) existing.wgConfig else sanitized.wgConfig,
                    vlessConfig = if (!subscriptionHasXrayConfig && existing != null) existing.vlessConfig else sanitized.vlessConfig
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

            scope.launch {
                // Onto what's stored now, not currentProfiles: another subscription refreshing at
                // the same time only touches its own entries, and those must survive this write.
                // A subscription deleted meanwhile (deleteSubscription) gets nothing back.
                if (subscriptionId != null && subscriptionId in deletedSubIds) return@launch
                val newList = prefs.updateProfiles { stored ->
                    if (subscriptionId != null && subscriptionId in deletedSubIds) {
                        stored
                    } else if (subscriptionId != null) {
                        stored.filter { it.subscriptionId != subscriptionId } + importedList
                    } else {
                        stored + importedList
                    }
                }

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

            val removedCount =
                if (subscriptionId != null) existingSubProfiles.size - matchedExistingIds.size else 0
            return Triple(
                ImportResult(addedCount, updatedCount, removedCount, importedList.size),
                importedList,
                resolvedRecommendedLocalId
            )
        } catch (_: Exception) {
            return Triple(ImportResult(), emptyList(), null)
        }
    }

    // Refreshes in flight by subscription id, for deleteSubscription to cancel.
    private val subscriptionFetches =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // Subscriptions deleted this run: a refresh's writes that come after the deletion - its
    // profiles' save runs on [scope], past a cancelled download - leave them deleted.
    private val deletedSubIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The stored subscription with [url], if any - one deleted this run never counts. */
    private suspend fun existingSubscriptionId(url: String): String? =
        prefs.subscriptionsFlow.first().find { it.url == url && it.id !in deletedSubIds }?.id

    // userAgent: the one a new subscription is added with (null - automatic); one already added
    // keeps its own, see Subscription.userAgent.
    suspend fun fetchSubscription(
        url: String,
        forceId: String? = null,
        allowInsecureHttp: Boolean = false,
        userAgent: String? = null,
        onAutoSelect: ((Profile) -> Unit)? = null
    ): ImportStatus {
        // From the store, not subscriptions.value: that one trails it, and right after a deletion
        // would still hand back the deleted subscription's id - the same URL added again would then
        // come back Cancelled, with nothing imported and nothing said.
        val subId = forceId ?: existingSubscriptionId(url)
        ?: return downloadSubscription(url, null, allowInsecureHttp, userAgent, onAutoSelect)
        if (subId in deletedSubIds) return ImportStatus.Cancelled
        // Its own job, so a deletion cancels this download and not the caller's coroutine - the
        // auto-update loop, or a screen refreshing several subscriptions in one.
        return kotlinx.coroutines.coroutineScope {
            val download =
                async { downloadSubscription(url, subId, allowInsecureHttp, userAgent, onAutoSelect) }
            subscriptionFetches[subId] = download
            try {
                download.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The caller itself cancelled: pass it on. Only the deletion ends here.
                ensureActive()
                if (!download.isCancelled) throw e
                com.wireturn.app.AppLogsState.addLog(
                    "Subscription (${
                        subscriptionLogLabel(
                            subId,
                            url
                        )
                    }): deleted while downloading, cancelled"
                )
                ImportStatus.Cancelled
            } finally {
                subscriptionFetches.remove(subId, download)
            }
        }
    }

    private suspend fun downloadSubscription(
        url: String,
        subIdToMark: String?,
        allowInsecureHttp: Boolean,
        requestedUserAgent: String?,
        onAutoSelect: ((Profile) -> Unit)?
    ): ImportStatus = withContext(Dispatchers.IO) {
        subIdToMark?.let { id -> _updatingSubIds.update { it + id } }
        val startTime = android.os.SystemClock.elapsedRealtime()
        // Which subscription a log line is about. Never the URL: it usually carries the access token.
        val logLabel = subscriptionLogLabel(subIdToMark, url)

        // The network security config permits cleartext app-wide (it has to, for arbitrary LAN
        // subscription servers), so a non-local http:// is gated here: a new one needs the user's
        // confirmation (InsecureHttp), one already added - confirmed then, or added before this
        // check existed - updates as before. Panels without a domain (3x-ui on a bare IP) often
        // serve nothing else.
        if (url.startsWith("http://", ignoreCase = true) && !isLocalNetworkHost(url)) {
            if (!allowInsecureHttp && subscriptions.value.none { it.url == url }) {
                com.wireturn.app.AppLogsState.addLog("Subscription ($logLabel): cleartext HTTP to a non-local host needs confirmation")
                subIdToMark?.let { id -> _updatingSubIds.update { it - id } }
                return@withContext ImportStatus.InsecureHttp(url)
            }
            com.wireturn.app.AppLogsState.addLog("Subscription Warning ($logLabel): cleartext HTTP to a non-local host")
        }

        val proxy = activeLocalSocksProxy()

        // The subscription's own choice, or - while adding one - the one it's added with.
        val chosenUserAgent = SubscriptionUserAgents.normalize(
            requestedUserAgent ?: subIdToMark?.let { id ->
                prefs.subscriptionsFlow.first().find { it.id == id }?.userAgent
            }
        )
        val primaryUserAgent = SubscriptionUserAgents.resolve(chosenUserAgent, userAgent)

        fun open(target: String, requestUserAgent: String = primaryUserAgent): HttpURLConnection = try {
            // First attempt with the calculated proxy
            val conn = (URL(target).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", requestUserAgent)
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            // Trigger connection to check if it works
            conn.responseCode
            conn
        } catch (e: Exception) {
            // If proxy failed and it wasn't already NO_PROXY, try direct connection
            if (proxy != java.net.Proxy.NO_PROXY) {
                (URL(target).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", requestUserAgent)
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                }
            } else throw e
        }

        var connection = try {
            // A CSQTT panel's page is fetched as its JSON (csqttPanelJsonUrl); the subscription keeps
            // the URL as the user gave it. Other panels' links can end the same way (Hiddify's
            // ".../<uuid>/sub/"), so one whose JSON twin doesn't answer 200 is fetched as given.
            subscriptionParser.csqttPanelJsonUrl(url)?.let { jsonUrl ->
                runCatching { open(jsonUrl) }.getOrNull()?.takeIf {
                    runCatching { it.responseCode == HttpURLConnection.HTTP_OK }.getOrDefault(false)
                        .also { ok -> if (!ok) it.disconnect() }
                }
            } ?: open(url)
        } catch (e: Exception) {
            // Covers both "no proxy, direct attempt failed" and "proxy failed, direct fallback also failed" -
            // previously these could throw past this point uncaught, permanently killing the auto-update loop.
            com.wireturn.app.AppLogsState.addLog("Subscription Error ($logLabel): ${e.javaClass.simpleName} - ${e.message}")
            subIdToMark?.let { id -> _updatingSubIds.update { it - id } }
            return@withContext failureStatus(e)
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
                    com.wireturn.app.AppLogsState.addLog("Subscription HTTP Error ($logLabel): $responseCode")
                    ImportStatus.ServerError(responseCode)
                } else {
                    var content = connection.inputStream.bufferedReader().use { it.readText() }

                    // Some providers serve a browser landing page to an unknown client, but
                    // return a machine-readable subscription for known app UAs. Retry only HTML
                    // responses so ordinary subscriptions do not incur extra requests and
                    // non-HTML parse failures keep their existing behavior.
                    if (chosenUserAgent == SubscriptionUserAgents.AUTO &&
                        isHtmlSubscriptionResponse(connection, content)
                    ) {
                        val originalConnection = connection
                        originalConnection.disconnect()
                        for (fallbackUserAgent in subscriptionFallbackUserAgents) {
                            val candidate =
                                runCatching { open(url, fallbackUserAgent) }.getOrNull() ?: continue
                            if (candidate.responseCode != HttpURLConnection.HTTP_OK) {
                                candidate.disconnect()
                                continue
                            }
                            val candidateContent = runCatching {
                                candidate.inputStream.bufferedReader().use { it.readText() }
                            }.getOrNull()
                            if (candidateContent.isNullOrBlank()) {
                                candidate.disconnect()
                                continue
                            }
                            if (isHtmlSubscriptionResponse(candidate, candidateContent)) {
                                candidate.disconnect()
                                continue
                            }
                            connection = candidate
                            content = candidateContent
                            break
                        }
                    }
                    if (content.isBlank()) {
                        com.wireturn.app.AppLogsState.addLog("Subscription Error ($logLabel): Empty response")
                        ImportStatus.EmptyResponse
                    } else {
                        kotlinx.coroutines.yield() // Check for cancellation
                        val bundle = when (val parsed = subscriptionParser.parse(content)) {
                            is SubscriptionParser.Result.Parsed -> parsed.bundle
                            SubscriptionParser.Result.QwdttUnusable -> {
                                com.wireturn.app.AppLogsState.addLog("Subscription Error ($logLabel): qWDTT profiles without hashes or password")
                                null
                            }

                            SubscriptionParser.Result.Unrecognized -> null
                        }

                        if (bundle != null) {
                            val subId =
                                subIdToMark ?: existingSubscriptionId(url) ?: UUID.randomUUID()
                                    .toString()
                            // The response headers come first and the body's own fields (sub.md lines,
                            // a panel's JSON, Happ/INCY-style "#announce:" lines) fill in what they
                            // leave out - the order INCY documents for the same headers and lines.
                            val subName = subscriptionParser.decodeProfileTitle(
                                connection.getHeaderField("Profile-Title")
                            ) ?: bundle.name ?: URL(url).host ?: "Subscription"
                            // INCY sends the subscription's description in a header of its own.
                            val headerDescription = subscriptionParser.decodeProfileTitle(
                                connection.getHeaderField("Profile-Description")
                            )

                            val userinfo = connection.getHeaderField("Subscription-Userinfo")
                            val (headerBytesUsed, headerBytesTotal) =
                                subscriptionParser.parseSubscriptionUserinfo(userinfo)
                            val headerExpiresAt = subscriptionParser.parseSubscriptionExpire(userinfo)
                            val headerRefillAt = subscriptionParser.parseRefillDate(
                                connection.getHeaderField("Subscription-Refill-Date")
                            )
                            // Plain or "base64:" like Profile-Title.
                            val headerAnnounce = subscriptionParser.decodeProfileTitle(
                                connection.getHeaderField("Announce")
                            )
                            val headerSupportUrl = subscriptionParser.parseLinkHeader(
                                connection.getHeaderField("Support-Url")
                            )
                            val headerWebPageUrl = subscriptionParser.parseLinkHeader(
                                connection.getHeaderField("Profile-Web-Page-Url")
                            )
                            val headerUpdateIntervalMinutes = subscriptionParser.parseUpdateIntervalHeader(
                                connection.getHeaderField("Profile-Update-Interval")
                            )

                            kotlinx.coroutines.yield()

                            // 1. Import profiles with the recommendation ID from the server
                            val (importResult, importedProfiles, resolvedActiveId) = importProfileObjects(
                                importedProfiles = bundle.profiles,
                                subscriptionId = subId,
                                serverActiveId = bundle.recommendedProfileId,
                                onAutoSelect = onAutoSelect
                            )

                            // 2. The subscription's entry, built on the stored one - so the user's own
                            // settings changed while this was downloading (auto-update, interval) stay.
                            fun subscriptionOf(existingSub: Subscription?): Subscription {
                                val bestActiveId = resolvedActiveId
                                    ?: existingSub?.activeProfileId?.takeIf { id -> importedProfiles.any { it.id == id } }
                                    ?: importedProfiles.firstOrNull()?.id
                                return Subscription(
                                    id = subId,
                                    name = subName,
                                    url = url,
                                    description = headerDescription ?: bundle.description,
                                    updatedAt = System.currentTimeMillis(),
                                    bytesUsed = headerBytesUsed ?: bundle.bytesUsed ?: 0,
                                    bytesTotal = headerBytesTotal ?: bundle.bytesTotal ?: 0,
                                    expiresAt = headerExpiresAt ?: bundle.expiresAt ?: 0,
                                    refillAt = headerRefillAt ?: bundle.refillAt ?: 0,
                                    announce = headerAnnounce ?: bundle.announce?.trim()?.ifBlank { null },
                                    supportUrl = headerSupportUrl
                                        ?: subscriptionParser.parseLinkHeader(bundle.supportUrl),
                                    webPageUrl = headerWebPageUrl
                                        ?: subscriptionParser.parseLinkHeader(bundle.webPageUrl),
                                    activeProfileId = bestActiveId,
                                    autoUpdate = existingSub?.autoUpdate
                                        ?: ((headerUpdateIntervalMinutes
                                            ?: bundle.updateIntervalMinutes) != null),
                                    updateIntervalMinutes = existingSub?.updateIntervalMinutes
                                        ?: (headerUpdateIntervalMinutes
                                            ?: bundle.updateIntervalMinutes)?.coerceAtLeast(20)
                                        ?: 1440,
                                    onlyUpdateIfSelected = existingSub?.onlyUpdateIfSelected
                                        ?: false,
                                    requireTunnelForUpdate = existingSub?.requireTunnelForUpdate
                                        ?: false,
                                    pinned = existingSub?.pinned ?: false,
                                    collapsed = existingSub?.collapsed ?: false,
                                    sortMode = existingSub?.sortMode ?: Subscription.SORT_SERVER,
                                    userAgent = existingSub?.userAgent ?: chosenUserAgent
                                )
                            }

                            // 3. Save subscription info - unless it was deleted in the meantime.
                            prefs.updateSubscriptions { current ->
                                if (subId in deletedSubIds) {
                                    current
                                } else if (current.any { it.id == subId }) {
                                    current.map { if (it.id == subId) subscriptionOf(it) else it }
                                } else {
                                    current + subscriptionOf(null)
                                }
                            }

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
            val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
            if (elapsed < 300) {
                kotlinx.coroutines.delay((300 - elapsed).milliseconds)
            }

            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.wireturn.app.AppLogsState.addLog("Subscription Error ($logLabel): ${e.javaClass.simpleName} - ${e.message}")
            failureStatus(e)
        } finally {
            subIdToMark?.let { id -> _updatingSubIds.update { it - id } }
            connection.disconnect()
        }
    }

    fun deleteSubscription(id: String, onFallback: (Profile) -> Unit = {}) {
        // Marked first: a refresh's write that gets in after this is dropped (see deletedSubIds),
        // one that got in before is removed by the deletion below.
        deletedSubIds.add(id)
        subscriptionFetches[id]?.cancel()
        scope.launch {
            val currentSelectedId = currentProfileId.value
            val isCurrentInDeletedSub =
                profiles.value.find { it.id == currentSelectedId }?.subscriptionId == id

            val newSubs = prefs.updateSubscriptions { current -> current.filter { it.id != id } }
            val newProfiles =
                prefs.updateProfiles { current -> current.filter { it.subscriptionId != id } }

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
                val activeInSub =
                    allProfiles.find { it.id == sub.activeProfileId && it.subscriptionId == sub.id }
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
                val activeInSub =
                    allProfiles.find { it.id == sub.activeProfileId && it.subscriptionId == sub.id }
                if (activeInSub != null) return activeInSub

                val firstInSub = allProfiles.find { it.subscriptionId == sub.id }
                if (firstInSub != null) return firstInSub
            }
        }

        // 4. Just first one ever
        return allProfiles.firstOrNull()
    }

    fun updateAllSubscriptions(transform: (Subscription) -> Subscription) {
        scope.launch { prefs.updateSubscriptions { current -> current.map(transform) } }
    }

    /** Changes the subscription with [id] as it's stored at that moment - see updateProfiles. */
    fun updateSubscription(id: String, transform: (Subscription) -> Subscription) {
        scope.launch {
            prefs.updateSubscriptions { current -> current.map { if (it.id == id) transform(it) else it } }
        }
    }

    private fun isHtmlSubscriptionResponse(
        connection: HttpURLConnection,
        content: String
    ): Boolean {
        val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (contentType == "text/html" || contentType == "application/xhtml+xml") return true
        val prefix = content.trimStart().take(256)
        return prefix.startsWith("<!doctype html", ignoreCase = true) ||
                prefix.startsWith("<html", ignoreCase = true) ||
                prefix.startsWith("<head", ignoreCase = true)
    }

    // A rejected certificate (untrusted issuer, e.g. self-signed, or a name mismatch) surfaces
    // as an SSLHandshakeException/SSLPeerUnverifiedException wrapping these - unlike a handshake
    // merely cut off by the network, which stays a NetworkError.
    private fun failureStatus(e: Throwable): ImportStatus {
        val certificateRejected = generateSequence(e) { it.cause }.any {
            it is java.security.cert.CertificateException ||
                    it is java.security.cert.CertPathValidatorException ||
                    it is javax.net.ssl.SSLPeerUnverifiedException
        }
        return if (certificateRejected) ImportStatus.TlsError else ImportStatus.NetworkError
    }

}
