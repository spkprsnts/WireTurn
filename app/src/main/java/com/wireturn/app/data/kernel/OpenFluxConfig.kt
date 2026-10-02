package com.wireturn.app.data.kernel

import android.net.Uri
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

// OpenFlux (external/openflux, upstream https://github.com/p1neappleXpress/OpenFlux) - a plain
// TCP tunnel with pluggable transports. Client mode only here (the app never runs --exit-node);
// its own embedded SOCKS5 server binds cfg.socksAddr directly (see CoreService.buildCommandArgs),
// so like olcRTC/WebDAV/qWDTT it's socks5-native and speaks no auth flags of its own.
data class OpenFluxConfig(
    // "yandex" (Yandex.Docs cursor-message transport, needs `url`), "vyandex" (Yandex.Docs
    // "Volga" realtime-collab transport - same `url`, a rewritten disguise/backend on Yandex's
    // side), "oneme" (MAX WebRTC DataChannel transport, needs maxToken/maxUid), "cupsonline"
    // (cups.online live-coding-room transport - same `url` slot, but it holds the base64 room
    // list the exit node prints at startup, not a document link) or "mailru" (Mail.ru Docs
    // cursor-message transport, same idea as classic yandex but riding a public cloud.mail.ru
    // document instead - `url` holds that document's weblink) or "boards" (Yandex Boards
    // whiteboard transport, `url` holds the board link with its ?hash=) - the exact values
    // OpenFlux's own `-transport` flag accepts (the CLI arg is literally "oneme", not "max").
    @SerializedName("transport") val transport: String = "yandex",
    // Yandex.Docs/Mail.ru document URL for yandex/vyandex/mailru; base64 room list for cupsonline.
    @SerializedName("url") val url: String = "",
    // Client's own MAX account auth token, passed to LoginByToken - required for "oneme".
    @SerializedName("max_token") val maxToken: String = "",
    // MAX user id of the exit-node's account being called - required for "oneme".
    @SerializedName("max_uid") val maxUid: String = "",
    // Optional AES-256-GCM shared secret (>=16 chars) layered on top of whichever transport
    // above - both peers must set the same value. Blank disables it entirely (the binary's own
    // default, unencrypted-at-this-layer behavior); works with all three transports.
    @SerializedName("encryption_key") val encryptionKey: String = "",
    // Forces `--codec=legacy` (old per-packet LZ4, no batching) instead of the binary's own
    // default `batched` (zstd + coalescing, added alongside mailru upstream). The two ends must
    // use the *same* codec - it's not negotiated - so this exists purely for talking to an
    // exit-node that hasn't been updated past that point yet. False keeps the new default.
    @SerializedName("legacy_codec") val legacyCodec: Boolean = false,
    // Explicit encryption context (--session-context), the salt the keys are derived from along
    // with the secret - both peers must use the same one. Blank = the core's own rule, see
    // [derivedContext]; only needed for an exit started with a context of its own. Set by hand
    // or from an openflux://v1/ link whose "context" differs from that rule.
    @SerializedName("session_context") val sessionContext: String = "",
    // The mode without a server (`--mode=stream`, share.ModeStream): the exit is a PHP node on
    // ordinary web hosting (deploy/phpbox), reached over one cups.online room or Mail.ru document,
    // and the core speaks its stream mux instead of IP packets. TCP only, no key or codec of its
    // own - encryptionKey/legacyCodec/sessionContext are kept but don't apply while it's on.
    @SerializedName("stream_mode") val streamMode: Boolean = false
) {
    /** Stream mode as it actually runs: only the carriers a PHP exit has (see [STREAM_TRANSPORTS]). */
    val isStream: Boolean get() = streamMode && transport in STREAM_TRANSPORTS

    val platformDisplayName: String
        get() = when (transport) {
            "oneme" -> "MAX (oneme)"
            "vyandex" -> "Volga Y.Docs"
            "cupsonline" -> "Cups.online"
            "mailru" -> "Mail.ru Docs"
            "boards" -> "Y.Boards"
            else -> "Y.Docs"
        }

    fun isValid(): Boolean = when (transport) {
        "oneme" -> maxToken.isNotBlank() && maxUid.isNotBlank()
        // boards.go's Start() reads the board id from the link's own ?hash= and fails right away
        // without it ("boards: no hash in URL").
        "boards" -> url.contains("hash=")
        else -> url.isNotBlank()
    }

    fun sanitize(): OpenFluxConfig = copy(
        transport = when ((transport as Any?)?.toString()) {
            "oneme" -> "oneme"
            "vyandex" -> "vyandex"
            "cupsonline" -> "cupsonline"
            "mailru" -> "mailru"
            "boards" -> "boards"
            else -> "yandex"
        },
        url = (url as Any?)?.toString()?.trim()?.take(2000) ?: "",
        maxToken = (maxToken as Any?)?.toString()?.trim()?.take(4096) ?: "",
        maxUid = (maxUid as Any?)?.toString()?.trim()?.filter(Char::isDigit)?.take(32) ?: "",
        encryptionKey = (encryptionKey as Any?)?.toString()?.trim()?.take(4096) ?: "",
        sessionContext = (sessionContext as Any?)?.toString()?.trim()?.take(2000) ?: "",
        streamMode = (streamMode as Any?) == true
    )

    /** The context the core derives on its own for this profile - see [derivedContext]. */
    val derivedContext: String get() = derivedContext(transport, url)

    /** The --session-context to pass, if any: only when it differs from [derivedContext]. */
    val effectiveSessionContext: String?
        get() = sessionContext.takeIf { it.isNotBlank() && it != derivedContext }

    fun fillDefaults(): OpenFluxConfig = sanitize()

    // Upstream's own share link (external/openflux share/share.go, what an exit's --share prints):
    // openflux://v1/<base64url(raw deflate(JSON))>, JSON keys in Go's field order. It has no
    // place for MAX - an exit's token belongs to its own account - so oneme still goes out in
    // the legacy format below, the only one that can carry it.
    fun toUri(profileName: String? = null): String {
        if (transport == "oneme") return toLegacyUri(profileName)
        val json = JsonObject().apply {
            if (!profileName.isNullOrBlank()) addProperty("name", profileName)
            if (isStream) {
                // share.validateStream: a stream link carries no secret, session or codec.
                addProperty("mode", MODE_STREAM)
            } else {
                if (legacyCodec) addProperty("codec", "legacy")
                if (encryptionKey.isNotBlank()) {
                    addProperty("secret", encryptionKey)
                    // As the core's own share.Make does, an encrypted link always names its context.
                    addProperty("context", effectiveSessionContext ?: derivedContext)
                }
            }
            add("transports", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", transport)
                    addProperty("url", url)
                })
            })
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        val packed = ByteArrayOutputStream().use { out ->
            deflater.setInput(json.toString().toByteArray())
            deflater.finish()
            val buf = ByteArray(1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
            out.toByteArray()
        }
        return V1_PREFIX + Base64.encodeToString(packed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    // This app's own earlier openflux://config?... format - still read by parse().
    private fun toLegacyUri(profileName: String?): String {
        val builder = Uri.Builder().scheme("openflux").authority("config")
            .appendQueryParameter("transport", transport)
        if (transport == "oneme") {
            builder.appendQueryParameter("token", maxToken)
                .appendQueryParameter("uid", maxUid)
        } else {
            builder.appendQueryParameter("url", url)
        }
        if (encryptionKey.isNotBlank()) {
            builder.appendQueryParameter("enc", encryptionKey)
            effectiveSessionContext?.let { builder.appendQueryParameter("context", it) }
        }
        if (legacyCodec) builder.appendQueryParameter("codec", "legacy")
        if (!profileName.isNullOrBlank()) builder.appendQueryParameter("name", profileName)
        return builder.build().toString()
    }

    companion object {
        const val V1_PREFIX = "openflux://v1/"

        // The core's --url default, and the context of a channel with no document URL.
        const val CONTEXT_PLACEHOLDER = "http://#"

        /**
         * The encryption context the core derives for a single-transport client on its own
         * (external/openflux transport/kdfcontext.go, KDFContexts): the document URL, except
         * where there's none to name the channel - a cupsonline room list (its exit creates the
         * rooms, so it can't know it beforehand) and MAX.
         */
        fun derivedContext(transport: String, url: String): String = when {
            transport == "cupsonline" || transport == "oneme" -> CONTEXT_PLACEHOLDER
            url.isBlank() -> CONTEXT_PLACEHOLDER
            else -> url
        }

        // share.go's maxPayload: bounds the inflated JSON against a crafted link.
        private const val V1_MAX_PAYLOAD = 16 shl 10

        private val V1_TRANSPORTS = setOf("yandex", "vyandex", "boards", "mailru", "cupsonline")

        // share.ModeStream and the carriers a stream-mode exit speaks (share.go's streamTypes).
        const val MODE_STREAM = "stream"
        val STREAM_TRANSPORTS = setOf("cupsonline", "mailru")

        fun parse(url: String, current: OpenFluxConfig = OpenFluxConfig()): OpenFluxConfig? {
            val trimmed = url.trim()
            if (!trimmed.startsWith("openflux://", ignoreCase = true)) return null
            return try {
                if (trimmed.startsWith(V1_PREFIX, ignoreCase = true)) return parseV1(trimmed, current)
                val uri = Uri.parse(trimmed)
                // The legacy toUri() always hardcodes the host to "config" (see above); OlConnect's
                // dialect below uses "yandex"/"mailru" or leaves it blank, so that's a safe way to tell them
                // apart without a dedicated marker param.
                if (uri.authority != "config") return parseOlConnectDialect(uri, current)
                parseNative(uri, current)
            } catch (_: Exception) {
                null
            }
        }

        /** The JSON inside an upstream openflux://v1/ link, or null if [url] isn't one. */
        fun decodeV1(url: String): JsonObject? {
            val trimmed = url.trim()
            if (!trimmed.startsWith(V1_PREFIX, ignoreCase = true)) return null
            return try {
                // As tolerant as the core's share.Read: padding, the standard alphabet,
                // whitespace and line breaks (links wrapped by messengers) are all accepted.
                val payload = trimmed.substring(V1_PREFIX.length)
                    .filterNot(Char::isWhitespace)
                    .replace('+', '-').replace('/', '_')
                    .trimEnd('=')
                val packed = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING)
                val out = ByteArrayOutputStream()
                InflaterInputStream(ByteArrayInputStream(packed), Inflater(true)).use { input ->
                    val buf = ByteArray(1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > V1_MAX_PAYLOAD) return null
                    }
                }
                JsonParser.parseString(out.toString(Charsets.UTF_8.name())).asJsonObject
            } catch (_: Exception) {
                null
            }
        }

        // Only what this client can run: a single transport without --negotiate, in the classic or
        // the stream mode (share.Config.Mode). A negotiated
        // session (always the case with several transports or direct) needs a client in that mode
        // too, which this app deliberately doesn't do - such a link is rejected, not half-imported.
        private fun parseV1(url: String, current: OpenFluxConfig): OpenFluxConfig? {
            val json = decodeV1(url) ?: return null
            if (json.get("negotiate")?.asBoolean == true) return null
            val transports = json.getAsJsonArray("transports") ?: return null
            if (transports.size() != 1) return null
            val t = transports[0].asJsonObject
            val type = t.get("type")?.asString ?: return null
            if (type !in V1_TRANSPORTS) return null
            val docUrl = t.get("url")?.asString.orEmpty()
            if (docUrl.isBlank()) return null
            // A link without a mode is the classic tunnel. Anything else this app doesn't know
            // is rejected rather than run as the classic tunnel against an exit that isn't one.
            when (json.get("mode")?.asString.orEmpty()) {
                "" -> Unit
                MODE_STREAM -> {
                    if (type !in STREAM_TRANSPORTS || json.get("secret")?.asString.orEmpty().isNotEmpty()) return null
                    return current.copy(
                        transport = type, url = docUrl, streamMode = true,
                        encryptionKey = "", legacyCodec = false, sessionContext = ""
                    )
                }
                else -> return null
            }
            val legacyCodec = when (json.get("codec")?.asString) {
                null, "", "batched" -> false
                "legacy" -> true
                else -> return null
            }
            // Kept only when it differs from what the core derives on its own, so an ordinary
            // link leaves the field blank and a later change of document isn't pinned to it.
            val context = json.get("context")?.asString.orEmpty()
            return current.copy(
                transport = type,
                url = docUrl,
                streamMode = false,
                encryptionKey = json.get("secret")?.asString.orEmpty(),
                legacyCodec = legacyCodec,
                sessionContext = context.takeIf { it.isNotBlank() && it != derivedContext(type, docUrl) }.orEmpty()
            )
        }

        private fun parseNative(uri: Uri, current: OpenFluxConfig): OpenFluxConfig? {
            val transport = when (uri.getQueryParameter("transport")) {
                "oneme" -> "oneme"
                "vyandex" -> "vyandex"
                "cupsonline" -> "cupsonline"
                "mailru" -> "mailru"
                "boards" -> "boards"
                else -> "yandex"
            }
            val encryptionKey = uri.getQueryParameter("enc") ?: current.encryptionKey
            val legacyCodec = when (uri.getQueryParameter("codec")) {
                "legacy" -> true
                "batched" -> false
                else -> current.legacyCodec
            }
            val sessionContext = uri.getQueryParameter("context").orEmpty()
            return if (transport == "oneme") {
                val token = uri.getQueryParameter("token") ?: current.maxToken
                val uid = uri.getQueryParameter("uid") ?: current.maxUid
                if (token.isBlank() || uid.isBlank()) return null
                OpenFluxConfig(transport = "oneme", url = current.url, maxToken = token, maxUid = uid, encryptionKey = encryptionKey, legacyCodec = legacyCodec, sessionContext = sessionContext)
            } else {
                val docUrl = uri.getQueryParameter("url") ?: current.url
                if (docUrl.isBlank()) return null
                OpenFluxConfig(transport = transport, url = docUrl, maxToken = current.maxToken, maxUid = current.maxUid, encryptionKey = encryptionKey, legacyCodec = legacyCodec, sessionContext = sessionContext)
            }
        }

        // github.com/Oleglog/OlConnect's own openflux:// dialect (unrelated to this project's
        // scheme), as its app (profile/openflux/OpenFluxUri.kt) and its OlConnect_manager panel
        // write it: openflux://<yandex|mailru>?url=<doc_url>&t=<transport>&c=<codec>&d=<dns>
        // &k=<encryption_key>#<ProfileName>, with "u"/"transport"/"codec"/"dns"/"key" accepted as
        // aliases. Only document transports exist there: "auto"/"yandex"/"vyandex"/"mailru" -
        // "auto" (or none, or anything unrecognized) goes by the host/link instead, Mail.ru if
        // either says so, else plain yandex. "d"/"dns" has no matching field on our side
        // (OpenFlux's binary has no --dns flag at all), so it's dropped on import. The profile
        // name (if any) travels in the fragment, not a query param - see
        // OpenFluxKernel.displayNameFromUri.
        private fun parseOlConnectDialect(uri: Uri, current: OpenFluxConfig): OpenFluxConfig? {
            val docUrl = uri.getQueryParameter("url") ?: uri.getQueryParameter("u") ?: return null
            if (docUrl.isBlank()) return null
            val transport = when ((uri.getQueryParameter("t") ?: uri.getQueryParameter("transport"))?.trim()?.lowercase()) {
                "vyandex" -> "vyandex"
                "yandex" -> "yandex"
                "mailru" -> "mailru"
                else -> if (uri.host == "mailru" || docUrl.contains("mail.ru")) "mailru" else "yandex"
            }
            val encryptionKey = uri.getQueryParameter("k") ?: uri.getQueryParameter("key") ?: current.encryptionKey
            val legacyCodec = when ((uri.getQueryParameter("c") ?: uri.getQueryParameter("codec"))?.trim()?.lowercase()) {
                "legacy" -> true
                "batched" -> false
                else -> current.legacyCodec
            }
            return current.copy(
                transport = transport,
                url = docUrl,
                streamMode = false,
                encryptionKey = encryptionKey,
                legacyCodec = legacyCodec
            )
        }
    }
}
