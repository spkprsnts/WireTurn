package com.wireturn.app.domain

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * A routing profile in the format Happ introduced and INCY took over: what goes direct, what is
 * blocked, the DNS server and the geo files, as a JSON object - shared as
 * "happ://routing/onadd/<base64 JSON>" (or incy://, or a bare "://routing/..."), as base64, or as
 * the JSON itself. Imported into the Xray settings: see [routeDirect], [routeBlock] and
 * [unsupported] for what doesn't carry over.
 */
data class RoutingProfile(
    val name: String?,
    val directSites: List<String>,
    val directIp: List<String>,
    val blockSites: List<String>,
    val blockIp: List<String>,
    // Plain DNS through the tunnel - the "Remote" resolver.
    val remoteDnsIp: String?,
    val fakeDns: Boolean?,
    // Both or neither - one file alone can't make a geo source.
    val geoipUrl: String?,
    val geositeUrl: String?,
    // The profile's fields WireTurn has no counterpart for, as named in the profile.
    val unsupported: List<String>
) {
    /** For XraySettings.routeDirect / routeBlock: comma-separated, domains first. */
    val routeDirect: String get() = (directSites + directIp).joinToString(",")
    val routeBlock: String get() = (blockSites + blockIp).joinToString(",")

    companion object {
        // "<scheme>://routing/onadd/<payload>", ".../routing/add/...", "://routing/<payload>",
        // "://onadd/<payload>", the autorouting forms - the scheme is the app's (happ, incy) or none.
        private val LINK = Regex(
            """^[a-z0-9+.-]*://(?:auto)?(?:routing/(?:on)?add/|routing/|(?:on)?add/|onadd/)(.+)$""",
            RegexOption.IGNORE_CASE
        )

        /** [text] as a routing profile, or null when it isn't one (or points to a URL). */
        fun parse(text: String): RoutingProfile? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val payload = LINK.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
            // A link to the profile rather than the profile - fetching it is up to the caller.
            if (payload.startsWith("http://", true) || payload.startsWith("https://", true)) return null
            val json = if (payload.startsWith("{")) payload else decodeBase64(payload) ?: return null
            val obj = runCatching { JsonParser.parseString(json) }.getOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return fromJson(obj)
        }

        private fun fromJson(obj: JsonObject): RoutingProfile? {
            // Keys as Happ writes them ("DirectSites", "Geoipurl"), matched regardless of case.
            val fields = obj.entrySet().associate { (k, v) -> k.lowercase() to v }
            fun string(key: String): String? = fields[key.lowercase()]?.asStringOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
            fun bool(key: String): Boolean? = when (string(key)?.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            fun list(key: String): List<String> {
                val v = fields[key.lowercase()] ?: return emptyList()
                val items = when {
                    v.isJsonArray -> v.asJsonArray.mapNotNull { it.asStringOrNull() }
                    else -> v.asStringOrNull()?.split(',').orEmpty()
                }
                return items.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            }
            fun url(key: String): String? = string(key)?.takeIf {
                it.startsWith("https://", true) || it.startsWith("http://", true)
            }

            val known = listOf(
                "Name", "DirectSites", "DirectIp", "BlockSites", "BlockIp", "ProxySites", "ProxyIp",
                "GlobalProxy", "RemoteDNSIP", "RemoteDns", "Geoipurl", "Geositeurl", "FakeDNS"
            )
            // Not a routing profile at all (some other JSON) - nothing of the format in it.
            if (known.none { fields.containsKey(it.lowercase()) }) return null

            val unsupported = buildList {
                // Proxy only the listed sites, the rest direct - the tunnel takes everything else here.
                if (bool("GlobalProxy") == false) add("GlobalProxy: false")
                if (list("ProxySites").isNotEmpty()) add("ProxySites")
                if (list("ProxyIp").isNotEmpty()) add("ProxyIp")
                if (string("RemoteDNSType").equals("DoH", ignoreCase = true) && string("RemoteDNSDomain") != null) {
                    add("RemoteDNSDomain (DoH)")
                }
                if (string("DomesticDNSIP") != null || string("DomesticDns") != null ||
                    string("DomesticDNSDomain") != null
                ) add("DomesticDNS")
                if (fields["dnshosts"]?.let { it.isJsonObject && it.asJsonObject.size() > 0 } == true) {
                    add("DnsHosts")
                }
                string("DomainStrategy")?.takeIf { !it.equals("AsIs", ignoreCase = true) }
                    ?.let { add("DomainStrategy: $it") }
            }

            val geoipUrl = url("Geoipurl")
            val geositeUrl = url("Geositeurl")
            val hasGeo = geoipUrl != null && geositeUrl != null
            return RoutingProfile(
                name = string("Name"),
                directSites = list("DirectSites"),
                directIp = list("DirectIp"),
                blockSites = list("BlockSites"),
                blockIp = list("BlockIp"),
                remoteDnsIp = (string("RemoteDNSIP") ?: string("RemoteDns"))
                    ?.takeIf { it.none(Char::isWhitespace) },
                fakeDns = bool("FakeDNS"),
                geoipUrl = geoipUrl.takeIf { hasGeo },
                geositeUrl = geositeUrl.takeIf { hasGeo },
                unsupported = unsupported
            )
        }

        private fun JsonElement.asStringOrNull(): String? =
            if (isJsonPrimitive) asJsonPrimitive.asString else null

        private fun decodeBase64(value: String): String? = runCatching {
            val cleaned = value.replace(Regex("\\s"), "").replace('-', '+').replace('_', '/')
                .trimEnd('=')
            String(java.util.Base64.getDecoder().decode(cleaned.padEnd((cleaned.length + 3) / 4 * 4, '=')), Charsets.UTF_8)
        }.getOrNull()
    }
}
