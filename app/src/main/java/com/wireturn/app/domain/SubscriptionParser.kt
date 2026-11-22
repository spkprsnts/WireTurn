package com.wireturn.app.domain

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.ProfileBundle
import com.wireturn.app.data.kernel.OlcrtcConfig
import com.wireturn.app.data.kernel.QwdttConfig
import com.wireturn.app.kernel.KernelRegistry
import java.util.UUID

/**
 * What a subscription's body is - every format WireTurn reads - turned into a [ProfileBundle].
 * Pure parsing: fetching it (and its HTTP headers' meaning) is ProfileManager's.
 */
internal class SubscriptionParser(private val gson: Gson) {

    sealed interface Result {
        data class Parsed(val bundle: ProfileBundle) : Result

        /** A qWDTT subscription none of whose entries WireTurn can run (no hashes or password). */
        data object QwdttUnusable : Result
        data object Unrecognized : Result
    }

    fun parse(content: String): Result {
        // The body as base64, decoded at most once: both a wrapped qWDTT subscription and a
        // base64 text one (3x-ui) need it, and a large subscription is costly to decode twice.
        val base64Decoded by lazy(LazyThreadSafetyMode.NONE) { decodeLenientBase64(content) }

        // A qWDTT subscription with no entry WireTurn can run ends here: Gson would take its
        // "profiles" as empty profiles.
        tryParseQwdttPanelSubscription(content) { base64Decoded }?.let { qwdtt ->
            return if (qwdtt.profiles.isEmpty()) Result.QwdttUnusable else Result.Parsed(qwdtt)
        }
        // A CSQTT panel's JSON before WireTurn's own: it's an object too, and ProfileBundle (every
        // field defaulted, so Gson gets a no-arg constructor) takes any object as a bundle with
        // empty profiles - its "name" included - rather than failing on it.
        val bundle = tryParseCsqttPanelSubscription(content) ?: try {
            gson.fromJson(content, ProfileBundle::class.java)?.also {
                @Suppress("SENSELESS_COMPARISON")
                if (it.profiles == null) throw com.google.gson.JsonSyntaxException("missing profiles")
            }
        } catch (_: Exception) {
            try {
                val profiles = gson.fromJson<List<Profile>>(
                    content,
                    object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type
                )
                ProfileBundle(profiles = profiles)
            } catch (_: Exception) {
                tryParseOlcboxBundle(content) ?: run {
                    val decoded = ProfileEncoder.decode(content)
                    if (decoded != null) {
                        val element = JsonParser.parseString(decoded)
                        val profiles = if (element.isJsonArray) {
                            gson.fromJson(
                                element,
                                object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type
                            )
                        } else {
                            listOf(gson.fromJson(element, Profile::class.java))
                        }
                        ProfileBundle(profiles = profiles)
                    } else {
                        // Raw line-based subscription first (as-is, the common case), then as a
                        // last resort try decoding it as plain (non-deflated) base64 - some panels
                        // (e.g. 3x-ui) base64-encode their line-based subscription body directly.
                        tryParseTextSubscription(content)
                            ?: base64Decoded?.let(::tryParseTextSubscription)
                    }
                }
            }
        }
        return bundle?.let { Result.Parsed(it) } ?: Result.Unrecognized
    }

    // Reads olcbox's (github.com/alananisimov/olcbox) native LocationBundleV4 JSON subscription/export
    // format, e.g. {"version":5,"active_location_id":"...","locations":[{"storage_id":"...",
    // "name":"...","endpoint":{"room_id":"...","key":"..."},"auth_provider":"jitsi",
    // "transport":{"type":"vp8channel","vp8":{"fps":60,"batch":64}},"dns_server":"..."}]}.
    // "transport" may also be a bare string (its legacy compact form). Unlike WireTurn's own
    // ProfileBundle, every field here is olcbox-specific, so this is a distinct entry point rather
    // than something the OlcrtcConfig/Profile Gson adapters could be taught to also accept.
    private fun tryParseOlcboxBundle(content: String): ProfileBundle? {
        val root = try {
            JsonParser.parseString(content).asJsonObject
        } catch (_: Exception) {
            return null
        }
        val locationsArray = try {
            root.getAsJsonArray("locations")
        } catch (_: Exception) {
            null
        } ?: return null

        val profiles = locationsArray.mapNotNull { element ->
            val item = try {
                element.asJsonObject
            } catch (_: Exception) {
                return@mapNotNull null
            }
            parseOlcboxLocationEntry(item)
        }
        if (profiles.isEmpty()) return null

        val activeStorageId = (root.get("active_location_id") ?: root.get("activeLocationId"))
            ?.takeIf { it.isJsonPrimitive }?.asString

        return ProfileBundle(profiles = profiles, recommendedProfileId = activeStorageId)
    }

    private fun parseOlcboxLocationEntry(item: JsonObject): Profile? {
        fun str(vararg keys: String): String? {
            for (k in keys) {
                val v = item.get(k)
                if (v != null && v.isJsonPrimitive) return v.asString
            }
            return null
        }

        fun obj(key: String): JsonObject? =
            item.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        val endpoint = obj("endpoint")
        val provider = str(
            "auth_provider",
            "authProvider",
            "carrier",
            "bypass_provider",
            "bypassProvider",
            "provider"
        )
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
            transportObj != null -> transportObj.get("type")
                ?.takeIf { it.isJsonPrimitive }?.asString

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

        var config =
            OlcrtcConfig(provider = provider, transport = transport, id = roomId, key = key)
        vp8Fps?.let { config = config.copy(vp8Fps = it) }
        vp8Batch?.let { config = config.copy(vp8Batch = it) }
        dns?.takeIf { it.isNotBlank() }?.let { config = config.copy(dns = it) }

        return Profile(id = storageId, name = name, kernelConfig = KernelConfig.Olcrtc(config))
    }

    // Text-subscription entries have no server-supplied stable id the way JSON ProfileBundle entries
    // do, so importParsedProfiles' id-based re-matching (subscriptionSourceId) would otherwise never
    // work past the very first sync - a fresh UUID.randomUUID() here would look "new" on every single
    // refresh, permanently losing the local profile's identity (and with it, any locally-configured
    // Xray settings, see subscriptionHasXrayConfig above) starting from the second refresh onward.
    // Deriving a deterministic id from the entry's own raw URI line instead keeps the same identity
    // across refreshes as long as the URI itself doesn't change, matching the "survives renames"
    // intent of the id-matching comment in importParsedProfiles (renames go through name-only fallback
    // matching, unrelated to this).
    private fun stableTextSubEntryId(rawUriLine: String): String =
        UUID.nameUUIDFromBytes(rawUriLine.toByteArray(Charsets.UTF_8)).toString()

    /**
     * The official qWDTT subscription JSON, read as its client's SubscriptionImport.parsePayload
     * does: an object with a "profiles" (or "servers") array, or a bare array of profiles, either
     * one optionally base64-wrapped. Each entry is the client's compact profile ({"peer", "hashes",
     * "workers", "pass", "dk", ...}), not WireTurn's kernelConfig wrapper, so it isn't left to Gson.
     *
     * Null when the content isn't that shape. A bundle with no profiles when it is, but no entry
     * is usable here: the official client fills a blank "hashes" from its global hashes setting,
     * WireTurn has none, so such an entry can't run.
     *
     * [base64Decoded] is the body decoded as base64 - only asked for when it isn't JSON as is.
     */
    private fun tryParseQwdttPanelSubscription(
        content: String,
        base64Decoded: () -> String?
    ): ProfileBundle? {
        val trimmed = content.trim()
        val json = if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed
        else base64Decoded()?.trim()?.takeIf { it.startsWith("{") || it.startsWith("[") }
            ?: return null
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        val rootObj = root.takeIf { it.isJsonObject }?.asJsonObject

        fun array(obj: JsonObject?, key: String) =
            obj?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray

        val entries = (if (root.isJsonArray) root.asJsonArray else array(rootObj, "profiles")
            ?: array(rootObj, "servers"))
            ?.mapNotNull { item -> item.takeIf { it.isJsonObject }?.asJsonObject }
            ?: return null

        // A qWDTT entry has a top-level "peer" plus a field of its own. WireTurn's bundle profiles
        // keep "peer" inside "kernelConfig", a CSQTT panel's entries carry a "link".
        val qwdttKeys = listOf(
            "hashes",
            "vkHashes",
            "workers",
            "workersPerHash",
            "pass",
            "password",
            "dk",
            "diagKey"
        )
        val qwdttEntries = entries.filter { obj ->
            obj.has("peer") && !obj.has("kernelConfig") && !obj.has("link") && qwdttKeys.any(obj::has)
        }
        if (qwdttEntries.isEmpty()) return null

        fun string(obj: JsonObject?, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }

        // A panel's malformed number falls back to the default rather than failing the whole import.
        fun number(obj: JsonObject?, vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { key ->
                obj?.get(key)?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asDouble }.getOrNull() }
            }

        val profiles = qwdttEntries.mapNotNull { obj ->
            val rawPeer = string(obj, "peer") ?: return@mapNotNull null
            val hashes = string(obj, "hashes", "vkHashes") ?: return@mapNotNull null
            val password = string(obj, "password", "pass") ?: return@mapNotNull null
            val config = QwdttConfig(
                peer = QwdttConfig.peerWithPort(rawPeer),
                vkHashes = hashes,
                password = password,
                workers = number(obj, "workers", "workersPerHash")?.toInt()
                    ?: QwdttConfig().workers,
                diagKey = string(obj, "diagKey", "dk").orEmpty(),
                deviceId = QwdttConfig.THIS_DEVICE
            ).sanitize()
            Profile(
                // From the peer as the panel sends it, not with the default port added: profiles
                // imported before that normalization got this id, and a different one would make
                // a refresh replace them - and their device id with it (QwdttConfig.deviceId).
                id = stableTextSubEntryId("qwdtt-sub:$rawPeer:$hashes"),
                name = string(obj, "name")
                    ?: com.wireturn.app.kernel.QwdttKernel.defaultProfileName,
                kernelConfig = KernelConfig.Qwdtt(config)
            )
        }

        fun megabytes(vararg keys: String): Long? =
            number(rootObj, *keys)?.takeIf { it >= 0 }?.let { (it * 1024 * 1024).toLong() }
        return ProfileBundle(
            version = number(rootObj, "version")?.toInt() ?: 1,
            // A bare array names its group in its first entry, as the official client reads it.
            name = string(rootObj, "subscriptionName", "groupName") ?: string(
                entries.firstOrNull(),
                "groupName"
            ),
            description = string(rootObj, "description", "info"),
            profiles = profiles,
            bytesUsed = megabytes("trafficUsedMb", "trafficMb"),
            bytesTotal = megabytes("trafficLimitMb", "trafficLimit")
        )
    }

    /**
     * The JSON twin of a CSQTT 2.5.0 server panel's subscription page, or null for any other URL.
     * The panel (rust-server/web_panel.rs) hands out "<base>/<uuid>/sub" for a client and
     * "<base>/<uuid>/sub-group" for a group - HTML pages for a browser, the link buried in markup
     * - and serves both as JSON at "<base>/<uuid>/json", which tells a client's uuid from a
     * group's itself.
     */
    fun csqttPanelJsonUrl(url: String): String? {
        val match = CSQTT_PANEL_SUB_PATH.find(url) ?: return null
        return url.substring(0, match.range.first) + "/" + match.groupValues[1] + "/json"
    }

    /**
     * A CSQTT panel's subscription JSON (see csqttPanelJsonUrl): a client's
     * {"ok", "name", "link", "sub_uuid"}, or a group's {"ok", "group": {"name", ...},
     * "clients": [{"name", "link", "sub_uuid"}]}. Each client's sub_uuid, or else its decrypted
     * link, is its stable id.
     */
    private fun tryParseCsqttPanelSubscription(content: String): ProfileBundle? {
        val trimmed = content.trim()
        if (!(trimmed.startsWith("{") || trimmed.startsWith("[")) || !trimmed.contains("csqtt://")) return null
        data class Entry(val name: String?, val link: String, val subUuid: String?, val power: Int?)

        fun entryOf(obj: JsonObject): Entry? {
            fun str(key: String) =
                obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

            val link =
                str("link")?.takeIf { it.startsWith("csqtt://", ignoreCase = true) } ?: return null
            val power =
                com.wireturn.app.data.kernel.CsqttConfig.linkPower(str("power") ?: str("workers"))
            return Entry(str("name"), link, str("sub_uuid"), power)
        }

        fun entriesOf(array: com.google.gson.JsonArray): List<Entry> =
            array.mapNotNull { item -> item.takeIf { it.isJsonObject }?.asJsonObject?.let(::entryOf) }

        // The panel's own shapes ({"clients": [...], "group": {...}}, or one client's object), and
        // the others the official app's importer (2.5.3's SubscriptionImporter) takes: a group's
        // "items" or "clients", top-level "items", or a bare array.
        val root = runCatching { JsonParser.parseString(trimmed) }.getOrNull() ?: return null
        val obj = root.takeIf { it.isJsonObject }?.asJsonObject
        val group = obj?.get("group")?.takeIf { it.isJsonObject }?.asJsonObject
        val groupName = group?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        fun array(o: JsonObject?, key: String) = o?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        val entries = when {
            root.isJsonArray -> entriesOf(root.asJsonArray)
            else -> (array(group, "items") ?: array(group, "clients") ?: array(obj, "clients")
            ?: array(obj, "items"))
                ?.let(::entriesOf)
                ?: listOfNotNull(obj?.let(::entryOf))
        }

        val profiles = entries.mapNotNull { entry ->
            val parsed =
                com.wireturn.app.data.kernel.CsqttConfig.parse(entry.link) ?: return@mapNotNull null
            // A node's own count over its link's, as the official app reads it.
            val config = entry.power?.let { parsed.copy(workers = it) } ?: parsed
            val seed = entry.subUuid?.let { "csqtt-sub:$it" }
                ?: com.wireturn.app.data.kernel.CsqttConfig.decryptLink(entry.link) ?: entry.link
            Profile(
                id = stableTextSubEntryId(seed),
                name = entry.name ?: com.wireturn.app.kernel.CsqttKernel.defaultProfileName,
                kernelConfig = KernelConfig.Csqtt(config)
            )
        }
        if (profiles.isEmpty()) return null
        return ProfileBundle(name = groupName ?: entries.singleOrNull()?.name, profiles = profiles)
    }

    private fun tryParseTextSubscription(text: String): ProfileBundle? {
        if (!text.contains("freeturn://") && !text.contains("olcrtc://") && !text.contains("olconnect://") &&
            !text.contains("turnable://") && !text.contains("webdav://") &&
            !text.contains("webdavs://") && !text.contains("qwdtt://") && !text.contains("qwdtt:config") && !text.contains(
                "wdtt://"
            ) &&
            !text.contains("openflux://") && !text.contains("csqtt://") &&
            // A plain v2ray subscription (3x-ui, Marzban, Remnawave): Xray-only profiles, see XrayKernel.
            !text.contains("vless://") && !text.contains("trojan://") &&
            !text.contains("hysteria2://") && !text.contains("hy2://") &&
            !text.contains("wireturn://") && !text.contains("wt://") && !text.contains("#name:")
        ) return null

        val lines = text.lines()
        var subName: String? = null
        var subDescription: String? = null
        var subInterval: Int? = null
        var subBytesUsed: Long? = null
        var subBytesTotal: Long? = null

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

        // Parses sizes like "10mb", "1.1gb", "500 KB" (binary units: 1kb = 1024b) as used by the
        // olcRTC/free-turn-proxy sub.md convention's #used/#available fields.
        fun parseByteSize(v: String): Long? {
            val match =
                Regex("(?i)^\\s*([0-9]*\\.?[0-9]+)\\s*(b|kb|mb|gb|tb)?\\s*$").find(v) ?: return null
            val num = match.groupValues[1].toDoubleOrNull() ?: return null
            val multiplier = when (match.groupValues[2].lowercase()) {
                "", "b" -> 1.0
                "kb" -> 1024.0
                "mb" -> 1024.0 * 1024
                "gb" -> 1024.0 * 1024 * 1024
                "tb" -> 1024.0 * 1024 * 1024 * 1024
                else -> return null
            }
            return (num * multiplier).toLong()
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

            // An OpenFlux node's link carries several carriers: a profile for each
            // (OpenFluxConfig.parseV1All), each with a stable id of its own off this line. The
            // last stays current, so ##tags that follow still apply to one of them.
            val openfluxCarriers = com.wireturn.app.data.kernel.OpenFluxConfig.parseV1All(trimmed)
            if (openfluxCarriers.size > 1) {
                flush()
                val baseName = com.wireturn.app.kernel.OpenFluxKernel.displayNameFromUri(trimmed)
                    ?: com.wireturn.app.kernel.OpenFluxKernel.defaultProfileName
                val carrierProfiles = openfluxCarriers.map { carrier ->
                    Profile(
                        id = stableTextSubEntryId("$trimmed#${carrier.transport}#${carrier.url}"),
                        name = "$baseName · ${carrier.platformDisplayName}",
                        kernelConfig = KernelConfig.OpenFlux(carrier)
                    )
                }
                profiles.addAll(carrierProfiles.dropLast(1))
                currentProfile = carrierProfiles.last()
                currentKernelConfig = carrierProfiles.last().kernelConfig
                continue
            }

            val kernelMatch = KernelRegistry.decodeUri(trimmed)
            if (kernelMatch != null) {
                flush()
                val (kernel, kernelConfig) = kernelMatch
                val nameFromUri = kernel.displayNameFromUri(trimmed)?.takeIf { it.isNotBlank() }

                currentKernelConfig = kernelConfig
                currentProfile = Profile(
                    id = stableTextSubEntryId(trimmed),
                    name = nameFromUri ?: kernel.defaultProfileName,
                    kernelConfig = kernelConfig
                )
                // A freeturn:// link from the official client may carry the WireGuard config too.
                if (kernelConfig is KernelConfig.FreeTurn) {
                    com.wireturn.app.data.kernel.FreeTurnConfig.parseWg(trimmed)?.let { wg ->
                        currentProfile = currentProfile?.copy(
                            xrayEnabled = true,
                            xrayProtocol = com.wireturn.app.data.XrayConfiguration.WIREGUARD,
                            wgConfig = wg
                        )
                    }
                }
            } else if (trimmed.startsWith("wireturn://") || trimmed.startsWith("wt://")) {
                // A wireturn:// container carries a full Profile (or several) as-is - kernelConfig,
                // xrayEnabled/xrayProtocol, vlessConfig/wgConfig and its own stable `id`, all together.
                // Unlike the other schemes above, there's nothing to reconstruct: just decode and use
                // it directly, which also means its own id (not a hash of this line) drives matching
                // on refresh, and any Xray config it carries takes precedence the same way a JSON
                // subscription entry's would (see subscriptionHasXrayConfig in importParsedProfiles).
                flush()
                val encoded = trimmed.substringAfter("://")
                val decodedJson = ProfileEncoder.decode(encoded)
                val decodedProfiles = decodedJson?.let {
                    try {
                        val element = JsonParser.parseString(it)
                        if (element.isJsonArray) {
                            gson.fromJson(
                                element,
                                object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type
                            )
                        } else {
                            listOf(gson.fromJson(element, Profile::class.java))
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                if (decodedProfiles.isNullOrEmpty()) continue

                // A hand-crafted blob might omit `id` - Profile.sanitize() would then assign a fresh
                // random one on every single parse (its own fallback has no way to be deterministic),
                // silently reintroducing the identity-churn problem stableTextSubEntryId exists to
                // avoid for the other schemes. Only apply this fallback when the blob really didn't
                // supply an id - one that does should keep driving matching, same as a JSON sub entry.
                fun withStableIdIfMissing(p: Profile, fallbackSeed: String): Profile {
                    val sanitized = p.sanitize()
                    return if (p.id.isBlank()) sanitized.copy(id = stableTextSubEntryId(fallbackSeed)) else sanitized
                }

                if (decodedProfiles.size == 1) {
                    // Keep it as the "current" profile so any ##tags on following lines can still
                    // adjust it, same as every other scheme above.
                    val p = withStableIdIfMissing(decodedProfiles[0], trimmed)
                    currentKernelConfig = p.kernelConfig
                    currentProfile = p
                } else {
                    // Multiple profiles in one blob - push them all now, nothing left to be "current".
                    // Fallback seed includes the index so distinct id-less entries in the same blob
                    // don't collide onto the same stable id.
                    decodedProfiles.forEachIndexed { index, p ->
                        profiles.add(withStableIdIfMissing(p, "$trimmed#$index"))
                    }
                }
            } else if (trimmed.startsWith("##")) {
                if (currentProfile == null) continue
                val parts = trimmed.substring(2).split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    when (key) {
                        "name" -> currentProfile = currentProfile?.copy(name = value)
                        "id" -> {
                            // Overrides the default identity (a hash of the entry's own raw URI line,
                            // see stableTextSubEntryId) with an author-supplied stable id - needed when
                            // the URI's own content is expected to change between refreshes (rotating
                            // peer/port/obfuscation etc.) but it's still logically the same entry and
                            // should keep its locally-configured Xray settings across that change.
                            if (value.isNotBlank()) {
                                currentProfile = currentProfile?.copy(id = value)
                            }
                        }

                        "ip" -> {
                            val kc = currentKernelConfig
                            if (kc is KernelConfig.FreeTurn) {
                                currentKernelConfig =
                                    KernelConfig.FreeTurn(kc.config.copy(peer = value))
                            }
                        }

                        "provider" -> {
                            val kc = currentKernelConfig
                            if (kc is KernelConfig.FreeTurn) {
                                currentKernelConfig =
                                    KernelConfig.FreeTurn(kc.config.copy(provider = value))
                            } else if (kc is KernelConfig.Olcrtc) {
                                currentKernelConfig =
                                    KernelConfig.Olcrtc(kc.config.copy(provider = value))
                            }
                        }

                        "comment" -> {
                            if (currentProfile?.name?.contains("Server") == true) {
                                currentProfile = currentProfile?.copy(name = value)
                            }
                        }

                        "xray" -> {
                            // A raw VLESS/Trojan/Hysteria2 link for the current profile's Xray leg.
                            if (com.wireturn.app.ui.ValidatorUtils.isValidVlessLink(value)) {
                                currentProfile = currentProfile?.copy(
                                    xrayEnabled = true,
                                    xrayProtocol = com.wireturn.app.data.XrayConfiguration.VLESS,
                                    vlessConfig = com.wireturn.app.data.VlessConfig(vlessLink = value)
                                )
                            }
                        }

                        "xray-wg" -> {
                            // WireGuard is a multi-line [Interface]/[Peer] config, so it can't be a bare
                            // link like ##xray - the value is that config text, base64-encoded to fit on
                            // one line (plain, not URL-safe - matches how panels commonly wrap wg-quick).
                            val decoded = try {
                                String(
                                    android.util.Base64.decode(
                                        value,
                                        android.util.Base64.DEFAULT
                                    ), Charsets.UTF_8
                                )
                            } catch (_: Exception) {
                                null
                            }
                            val wg = decoded?.let { com.wireturn.app.data.WgConfig.parse(it) }
                            if (wg != null && wg.isValid()) {
                                currentProfile = currentProfile?.copy(
                                    xrayEnabled = true,
                                    xrayProtocol = com.wireturn.app.data.XrayConfiguration.WIREGUARD,
                                    wgConfig = wg
                                )
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
                        "used" -> {
                            // "10mb/10gb" (used/total) or a bare "10mb" (used only)
                            val parts = value.split("/", limit = 2)
                            parseByteSize(parts[0])?.let { subBytesUsed = it }
                            if (parts.size == 2) parseByteSize(parts[1])?.let { subBytesTotal = it }
                        }

                        "available" -> {
                            // Remaining quota - total = whatever's used so far (0 if #used wasn't
                            // given yet) + what's left, regardless of which field comes first in the file.
                            parseByteSize(value)?.let { subBytesTotal = (subBytesUsed ?: 0L) + it }
                        }
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
            updateIntervalMinutes = subInterval,
            bytesUsed = subBytesUsed,
            bytesTotal = subBytesTotal
        )
    }

    /** Standard or URL-safe base64, padded or not, whitespace and line breaks ignored. */
    private fun decodeLenientBase64(content: String): String? = try {
        val cleaned =
            content.replace(Regex("\\s"), "").replace('-', '+').replace('_', '/').trimEnd('=')
        String(android.util.Base64.decode(cleaned, android.util.Base64.NO_PADDING), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    /**
     * The "Profile-Title" header v2ray panels (Marzban, Remnawave, 3x-ui) send - either plain or,
     * for non-ASCII names, "base64:<encoded name>".
     */
    fun decodeProfileTitle(header: String?): String? {
        val title = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!title.startsWith("base64:", ignoreCase = true)) return title
        return decodeLenientBase64(title.substring("base64:".length))?.trim()
            ?.takeIf { it.isNotEmpty() }
    }


    /**
     * Parses the "Subscription-Userinfo" header some panels (e.g. 3x-ui) send for traffic quota:
     * "upload=X; download=Y; total=Z; expire=E" (bytes). Returns (used, total) - either half may
     * be null if that number wasn't present. "used" is upload+download combined, matching how
     * this header is interpreted across the V2Ray/Xray-adjacent subscription ecosystem.
     */
    fun parseSubscriptionUserinfo(header: String?): Pair<Long?, Long?> {
        if (header == null) return null to null
        val fields = header.split(";").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim().toLongOrNull() else null
        }.toMap()
        val upload = fields["upload"]
        val download = fields["download"]
        val used =
            if (upload != null || download != null) (upload ?: 0L) + (download ?: 0L) else null
        return used to fields["total"]
    }

    /**
     * The "expire" of the same "Subscription-Userinfo" header - Unix seconds - as epoch millis, or
     * null when it's missing or 0 (the panels' "never").
     */
    fun parseSubscriptionExpire(header: String?): Long? {
        if (header == null) return null
        val expire = header.split(";").firstNotNullOfOrNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].trim().equals("expire", ignoreCase = true)) {
                kv[1].trim().toLongOrNull()
            } else null
        }
        return expire?.takeIf { it > 0 }?.let { it * 1000 }
    }

    private companion object {
        // ".../<uuid>/sub" or ".../<uuid>/sub-group" at the end of a URL - see csqttPanelJsonUrl.
        val CSQTT_PANEL_SUB_PATH = Regex(
            """/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/sub(?:-group)?/?$"""
        )
    }
}
