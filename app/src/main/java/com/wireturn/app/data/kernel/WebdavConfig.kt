package com.wireturn.app.data

import android.net.Uri
import com.google.gson.annotations.SerializedName

data class WebdavBackend(
    @SerializedName("label") val label: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("login") val login: String = "",
    @SerializedName("password") val password: String = ""
) {
    fun isValid(): Boolean = url.isNotBlank() && login.isNotBlank() && password.isNotBlank()

    // Nested backend form used inside a primary webdav:// URI's repeatable `backend=` query
    // param - see external/webdav-tunnel docs/modes.md#multiple-backends-in-one-uri.
    fun toNestedUri(): String {
        val isHttps = url.startsWith("https://", ignoreCase = true)
        val scheme = if (isHttps) "webdavs" else "webdav"
        val cleanBase = url.replaceFirst("https://", "", ignoreCase = true)
            .replaceFirst("http://", "", ignoreCase = true)
        val builder = Uri.Builder()
            .scheme(scheme)
            .encodedAuthority(buildString {
                append(Uri.encode(login))
                append(":")
                append(Uri.encode(password))
                append("@")
                append(cleanBase.substringBefore("/"))
            })
        val path = cleanBase.substringAfter("/", "")
        if (path.isNotBlank()) builder.path(path)
        return builder.build().toString()
    }

    companion object {
        fun parseNestedUri(raw: String): WebdavBackend? {
            val isWebdavs = raw.startsWith("webdavs://", ignoreCase = true)
            val isWebdav = raw.startsWith("webdav://", ignoreCase = true)
            if (!isWebdav && !isWebdavs) return null
            return try {
                val uri = Uri.parse(raw)
                val userParts = (uri.userInfo ?: "").split(":")
                val login = userParts.getOrNull(0)?.let { Uri.decode(it) } ?: ""
                val password = userParts.getOrNull(1)?.let { Uri.decode(it) } ?: ""
                val scheme = if (isWebdavs) "https" else "http"
                val host = uri.host ?: ""
                val port = if (uri.port != -1) ":${uri.port}" else ""
                val path = uri.path ?: ""
                WebdavBackend(url = "$scheme://$host$port$path", login = login, password = password)
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class WebdavConfig(
    @SerializedName("webdav") val webdav: String = "",
    @SerializedName("login") val login: String = "",
    @SerializedName("password") val password: String = "",
    // Overrides how the WebDAV backend's own hostname is resolved - useful when the OS resolver
    // is unreliable/filtered. Has no effect on SOCKS5-tunneled traffic, which is always resolved
    // server-side. Purely per-profile - unlike olcRTC's dns, this never falls back to the global
    // ConnectionSettingsScreen/ClientConfig.dns setting; blank just means "use the OS resolver".
    @SerializedName("dns") val dns: String = "",
    // Additional WebDAV backends beyond the primary one above - the client rotates new sessions
    // round-robin across all of them, skipping any that are rate-limited/unreachable. See
    // external/webdav-tunnel docs/config.md#multi-backend-rotation.
    @SerializedName("backends") val backends: List<WebdavBackend> = emptyList(),
    @SerializedName("timeout") val timeout: String = "60s",
    @SerializedName("poll_max") val pollMax: String = "500ms",
    @SerializedName("poll_min") val pollMin: String = "200ms",
    @SerializedName("coalesce") val coalesce: String = "10ms",
    @SerializedName("chunk_size") val chunkSize: String = "131071",
    @SerializedName("puts") val puts: String = "8",
    @SerializedName("read_min") val readMin: String = "3",
    @SerializedName("read_max") val readMax: String = "8",
    @SerializedName("encrypt") val encrypt: Boolean = false
) {
    fun isValid(): Boolean = webdav.isNotBlank()
    fun fillDefaults(): WebdavConfig = copy(
        timeout = timeout.ifBlank { "60s" },
        pollMax = pollMax.ifBlank { "500ms" },
        pollMin = pollMin.ifBlank { "200ms" },
        coalesce = coalesce.ifBlank { "10ms" },
        chunkSize = chunkSize.ifBlank { "131071" },
        puts = puts.ifBlank { "8" },
        readMin = readMin.ifBlank { "3" },
        readMax = readMax.ifBlank { "8" },
        backends = backends.filter { it.isValid() }
    )

    fun toUri(profileName: String? = null): String {
        val isHttps = webdav.startsWith("https://", ignoreCase = true)
        val scheme = if (isHttps) "webdavs" else "webdav"

        // Remove existing protocol and parse as URI to get host/port/path
        val cleanBase = webdav.replaceFirst("https://", "", ignoreCase = true)
                              .replaceFirst("http://", "", ignoreCase = true)

        val builder = Uri.Builder()
            .scheme(scheme)
            .encodedAuthority(buildString {
                if (login.isNotBlank()) {
                    append(Uri.encode(login))
                    if (password.isNotBlank()) {
                        append(":")
                        append(Uri.encode(password))
                    }
                    append("@")
                }
                append(cleanBase.substringBefore("/"))
            })

        val path = cleanBase.substringAfter("/", "")
        if (path.isNotBlank()) {
            builder.path(path)
        }

        builder.appendQueryParameter("timeout", timeout)
        builder.appendQueryParameter("poll-min", pollMin)
        builder.appendQueryParameter("poll-max", pollMax)
        builder.appendQueryParameter("coalesce", coalesce)
        builder.appendQueryParameter("chunk-size", chunkSize)
        builder.appendQueryParameter("puts", puts)
        builder.appendQueryParameter("read-min", readMin)
        builder.appendQueryParameter("read-max", readMax)
        if (encrypt) builder.appendQueryParameter("enc", "1")
        if (dns.isNotBlank()) builder.appendQueryParameter("dns", dns)
        for (backend in backends) {
            if (backend.isValid()) builder.appendQueryParameter("backend", backend.toNestedUri())
        }

        if (!profileName.isNullOrBlank()) {
            builder.fragment(profileName)
        }

        return builder.build().toString()
    }

    companion object {
        fun parse(uriStr: String, current: WebdavConfig = WebdavConfig()): WebdavConfig? {
            val isWebdavs = uriStr.startsWith("webdavs://", ignoreCase = true)
            val isWebdav = uriStr.startsWith("webdav://", ignoreCase = true)
            if (!isWebdav && !isWebdavs) return null

            try {
                val uri = Uri.parse(uriStr)
                val userParts = (uri.userInfo ?: "").split(":")
                val login = userParts.getOrNull(0)?.let { Uri.decode(it) } ?: ""
                val password = userParts.getOrNull(1)?.let { Uri.decode(it) } ?: ""

                val webdavScheme = if (isWebdavs) "https" else "http"
                val host = uri.host ?: ""
                val port = if (uri.port != -1) ":${uri.port}" else ""
                val path = uri.path ?: ""

                val webdav = "$webdavScheme://$host$port$path"

                val backends = uri.getQueryParameters("backend")
                    .mapNotNull { WebdavBackend.parseNestedUri(it) }

                return WebdavConfig(
                    webdav = webdav,
                    login = login,
                    password = password,
                    dns = uri.getQueryParameter("dns") ?: current.dns,
                    backends = backends.ifEmpty { current.backends },
                    timeout = uri.getQueryParameter("timeout") ?: current.timeout,
                    pollMin = uri.getQueryParameter("poll-min") ?: current.pollMin,
                    pollMax = uri.getQueryParameter("poll-max") ?: current.pollMax,
                    coalesce = uri.getQueryParameter("coalesce") ?: current.coalesce,
                    chunkSize = uri.getQueryParameter("chunk-size") ?: current.chunkSize,
                    puts = uri.getQueryParameter("puts") ?: current.puts,
                    readMin = uri.getQueryParameter("read-min") ?: current.readMin,
                    readMax = uri.getQueryParameter("read-max") ?: current.readMax,
                    encrypt = uri.getQueryParameter("enc") == "1"
                )
            } catch (_: Exception) {
                return null
            }
        }

        fun formatHost(webdavUrl: String): String {
            val uri = try { Uri.parse(webdavUrl) } catch (_: Exception) { return webdavUrl.take(20) }
            val host = uri.host ?: return webdavUrl.take(20)
            val port = if (uri.port != -1) ":${uri.port}" else ""

            // Simple check for IPv4 or IPv6
            val isIp = host.all { it.isDigit() || it == '.' || it == ':' || it.lowercaseChar() in 'a'..'f' || it == '[' || it == ']' }

            if (isIp) return "$host$port"

            val parts = host.split('.')
            return if (parts.size >= 2) {
                // webdav.yandex.ru -> Yandex
                parts[parts.size - 2].replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() } + port
            } else {
                host.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() } + port
            }
        }
    }
}
