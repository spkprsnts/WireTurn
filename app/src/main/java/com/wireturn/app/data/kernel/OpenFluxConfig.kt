package com.wireturn.app.data

import android.net.Uri
import com.google.gson.annotations.SerializedName

// OpenFlux (external/openflux, upstream https://github.com/p1neappleXpress/OpenFlux) - a plain
// TCP tunnel with pluggable transports. Client mode only here (the app never runs --exit-node);
// its own embedded SOCKS5 server binds cfg.socksAddr directly (see CoreService.buildCommandArgs),
// so like olcRTC/WebDAV/qWDTT it's socks5-native and speaks no auth flags of its own.
data class OpenFluxConfig(
    // "yandex" (Yandex.Docs cursor-message transport, needs `url`) or "oneme" (MAX WebRTC
    // DataChannel transport, needs maxToken/maxUid) - the exact values OpenFlux's own
    // `-transport` flag accepts (the CLI arg is literally "oneme", not "max").
    @SerializedName("transport") val transport: String = "yandex",
    @SerializedName("url") val url: String = "",
    // Client's own MAX account auth token, passed to LoginByToken - required for "oneme".
    @SerializedName("max_token") val maxToken: String = "",
    // MAX user id of the exit-node's account being called - required for "oneme".
    @SerializedName("max_uid") val maxUid: String = ""
) {
    val platformDisplayName: String
        get() = when (transport) {
            "oneme" -> "MAX (oneme)"
            else -> "Y.Docs"
        }

    fun isValid(): Boolean = when (transport) {
        "oneme" -> maxToken.isNotBlank() && maxUid.isNotBlank()
        else -> url.isNotBlank()
    }

    fun sanitize(): OpenFluxConfig = copy(
        transport = if ((transport as Any?)?.toString() == "oneme") "oneme" else "yandex",
        url = (url as Any?)?.toString()?.trim()?.take(2000) ?: "",
        maxToken = (maxToken as Any?)?.toString()?.trim()?.take(4096) ?: "",
        maxUid = (maxUid as Any?)?.toString()?.trim()?.filter(Char::isDigit)?.take(32) ?: ""
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
        if (!profileName.isNullOrBlank()) builder.appendQueryParameter("name", profileName)
        return builder.build().toString()
    }

    companion object {
        fun parse(url: String, current: OpenFluxConfig = OpenFluxConfig()): OpenFluxConfig? {
            val trimmed = url.trim()
            if (!trimmed.startsWith("openflux://", ignoreCase = true)) return null
            return try {
                val uri = Uri.parse(trimmed)
                val transport = if (uri.getQueryParameter("transport") == "oneme") "oneme" else "yandex"
                if (transport == "oneme") {
                    val token = uri.getQueryParameter("token") ?: current.maxToken
                    val uid = uri.getQueryParameter("uid") ?: current.maxUid
                    if (token.isBlank() || uid.isBlank()) return null
                    OpenFluxConfig(transport = "oneme", url = current.url, maxToken = token, maxUid = uid)
                } else {
                    val docUrl = uri.getQueryParameter("url") ?: current.url
                    if (docUrl.isBlank()) return null
                    OpenFluxConfig(transport = "yandex", url = docUrl, maxToken = current.maxToken, maxUid = current.maxUid)
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
