package com.wireturn.app.data

import android.net.Uri
import com.google.gson.annotations.SerializedName

// OpenFlux (external/openflux, upstream https://github.com/p1neappleXpress/OpenFlux) - a plain
// TCP tunnel with pluggable transports. Client mode only here (the app never runs --exit-node);
// its own embedded SOCKS5 server binds cfg.socksAddr directly (see CoreService.buildCommandArgs),
// so like olcRTC/WebDAV/qWDTT it's socks5-native and speaks no auth flags of its own.
data class OpenFluxConfig(
    // "yandex" (Yandex.Docs cursor-message transport, needs `url`), "vyandex" (Yandex.Docs
    // "Volga" realtime-collab transport - same `url`, a rewritten disguise/backend on Yandex's
    // side), "oneme" (MAX WebRTC DataChannel transport, needs maxToken/maxUid) or "cupsonline"
    // (cups.online live-coding-room transport - same `url` slot, but it holds the base64 room
    // list the exit node prints at startup, not a document link) - the exact values OpenFlux's
    // own `-transport` flag accepts (the CLI arg is literally "oneme", not "max").
    @SerializedName("transport") val transport: String = "yandex",
    // Yandex.Docs document URL for yandex/vyandex; base64 room list for cupsonline.
    @SerializedName("url") val url: String = "",
    // Client's own MAX account auth token, passed to LoginByToken - required for "oneme".
    @SerializedName("max_token") val maxToken: String = "",
    // MAX user id of the exit-node's account being called - required for "oneme".
    @SerializedName("max_uid") val maxUid: String = "",
    // Optional AES-256-GCM shared secret (>=16 chars) layered on top of whichever transport
    // above - both peers must set the same value. Blank disables it entirely (the binary's own
    // default, unencrypted-at-this-layer behavior); works with all three transports.
    @SerializedName("encryption_key") val encryptionKey: String = ""
) {
    val platformDisplayName: String
        get() = when (transport) {
            "oneme" -> "MAX (oneme)"
            "vyandex" -> "Volga Y.Docs"
            "cupsonline" -> "Cups.online"
            else -> "Y.Docs"
        }

    fun isValid(): Boolean = when (transport) {
        "oneme" -> maxToken.isNotBlank() && maxUid.isNotBlank()
        else -> url.isNotBlank()
    }

    fun sanitize(): OpenFluxConfig = copy(
        transport = when ((transport as Any?)?.toString()) {
            "oneme" -> "oneme"
            "vyandex" -> "vyandex"
            "cupsonline" -> "cupsonline"
            else -> "yandex"
        },
        url = (url as Any?)?.toString()?.trim()?.take(2000) ?: "",
        maxToken = (maxToken as Any?)?.toString()?.trim()?.take(4096) ?: "",
        maxUid = (maxUid as Any?)?.toString()?.trim()?.filter(Char::isDigit)?.take(32) ?: "",
        encryptionKey = (encryptionKey as Any?)?.toString()?.trim()?.take(4096) ?: ""
    )

    fun fillDefaults(): OpenFluxConfig = sanitize()

    fun toUri(profileName: String? = null): String {
        val builder = Uri.Builder().scheme("openflux").authority("config")
            .appendQueryParameter("transport", transport)
        if (transport == "oneme") {
            builder.appendQueryParameter("token", maxToken)
                .appendQueryParameter("uid", maxUid)
        } else {
            builder.appendQueryParameter("url", url)
        }
        if (encryptionKey.isNotBlank()) builder.appendQueryParameter("enc", encryptionKey)
        if (!profileName.isNullOrBlank()) builder.appendQueryParameter("name", profileName)
        return builder.build().toString()
    }

    companion object {
        fun parse(url: String, current: OpenFluxConfig = OpenFluxConfig()): OpenFluxConfig? {
            val trimmed = url.trim()
            if (!trimmed.startsWith("openflux://", ignoreCase = true)) return null
            return try {
                val uri = Uri.parse(trimmed)
                // Our own toUri() always hardcodes the host to "config" (see above); OlConnect's
                // dialect below uses "yandex" or leaves it blank, so that's a safe way to tell them
                // apart without a dedicated marker param.
                if (uri.authority != "config") return parseOlConnectDialect(uri, current)
                val transport = when (uri.getQueryParameter("transport")) {
                    "oneme" -> "oneme"
                    "vyandex" -> "vyandex"
                    "cupsonline" -> "cupsonline"
                    else -> "yandex"
                }
                val encryptionKey = uri.getQueryParameter("enc") ?: current.encryptionKey
                if (transport == "oneme") {
                    val token = uri.getQueryParameter("token") ?: current.maxToken
                    val uid = uri.getQueryParameter("uid") ?: current.maxUid
                    if (token.isBlank() || uid.isBlank()) return null
                    OpenFluxConfig(transport = "oneme", url = current.url, maxToken = token, maxUid = uid, encryptionKey = encryptionKey)
                } else {
                    val docUrl = uri.getQueryParameter("url") ?: current.url
                    if (docUrl.isBlank()) return null
                    OpenFluxConfig(transport = transport, url = docUrl, maxToken = current.maxToken, maxUid = current.maxUid, encryptionKey = encryptionKey)
                }
            } catch (_: Exception) {
                null
            }
        }

        // github.com/Oleglog/OlConnect's own openflux:// dialect (unrelated to this project's
        // scheme): openflux://yandex?url=<doc_url>&t=<transport>&d=<dns>#<ProfileName>, with "u"/
        // "transport" accepted as aliases for "url"/"t". Only the Yandex.Docs family is
        // representable there ("auto"/"yandex"/"vyandex" - "auto" and anything unrecognized
        // collapse to plain "yandex"); it has no oneme/cupsonline equivalent, and its "d"/"dns"
        // param has no matching field on our side (OpenFlux's binary has no --dns flag at all), so
        // it's dropped on import. The profile name (if any) travels in the fragment, not a query
        // param - see OpenFluxKernel.displayNameFromUri.
        private fun parseOlConnectDialect(uri: Uri, current: OpenFluxConfig): OpenFluxConfig? {
            val docUrl = uri.getQueryParameter("url") ?: uri.getQueryParameter("u") ?: return null
            if (docUrl.isBlank()) return null
            val transportParam = uri.getQueryParameter("t") ?: uri.getQueryParameter("transport")
            val transport = if (transportParam == "vyandex") "vyandex" else "yandex"
            return current.copy(transport = transport, url = docUrl, maxToken = current.maxToken, maxUid = current.maxUid)
        }
    }
}
