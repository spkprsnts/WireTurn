package com.wireturn.app.data

import android.net.Uri
import com.google.gson.annotations.SerializedName

data class OlcrtcConfig(
    @SerializedName("provider", alternate = ["carrier"]) val provider: String = "wbstream",
    @SerializedName("transport") val transport: String = "datachannel",
    @SerializedName("id") val id: String = "",
    @SerializedName("key") val key: String = "",
    @SerializedName("mimo") val mimo: String = "",
    @SerializedName("vp8_fps") val vp8Fps: Int = 60,
    @SerializedName("vp8_batch") val vp8Batch: Int = 64,
    @SerializedName("sei_fps") val seiFps: Int = 60,
    @SerializedName("sei_batch") val seiBatch: Int = 64,
    @SerializedName("sei_frag") val seiFrag: Int = 900,
    @SerializedName("sei_ack_ms") val seiAckMs: Int = 2000,
    @SerializedName("video_codec") val videoCodec: String = "qrcode",
    @SerializedName("video_w") val videoW: Int = 1080,
    @SerializedName("video_h") val videoH: Int = 1080,
    @SerializedName("video_fps") val videoFps: Int = 60,
    @SerializedName("video_qr_recovery") val videoQrRecovery: String = "low",
    @SerializedName("video_qr_size") val videoQrSize: Int = 0,
    @SerializedName("video_tile_module") val videoTileModule: Int = 4,
    @SerializedName("video_tile_rs") val videoTileRs: Int = 20,
    @SerializedName("restart_on_connection_errors") val restartOnConnectionErrors: Boolean = true
) {
    val providerDisplayName: String
        get() = when (provider) {
            "wbstream" -> "WB Stream"
            "telemost" -> "Telemost"
            "jitsi" -> "Jitsi"
            else -> provider
        }

    val transportDisplayName: String
        get() = getTransportDisplayName(transport)

    fun sanitize(): OlcrtcConfig {
        return copy(
            provider = (provider as Any?)?.toString()?.take(100) ?: "wbstream",
            transport = (transport as Any?)?.toString()?.take(100) ?: "datachannel",
            id = (id as Any?)?.toString()?.take(200) ?: "",
            key = (key as Any?)?.toString()?.take(1000) ?: "",
            mimo = (mimo as Any?)?.toString()?.take(500) ?: "",
            videoW = if (videoW <= 0) 1080 else videoW,
            videoH = if (videoH <= 0) 1080 else videoH
        )
    }

    fun isValid(): Boolean {
        if (id.isBlank() || key.isBlank()) return false
        if (transport == "videochannel") {
            if (videoW !in VIDEO_MIN_DIMENSION..VIDEO_MAX_DIMENSION) return false
            if (videoH !in VIDEO_MIN_DIMENSION..VIDEO_MAX_DIMENSION) return false
            if (videoCodec == "qrcode" && videoQrRecovery.isNotBlank() &&
                videoQrRecovery !in VIDEO_QR_RECOVERY_LEVELS
            ) {
                return false
            }
        }
        return true
    }
    fun fillDefaults(): OlcrtcConfig = sanitize()

    fun toUri(profileName: String? = null): String {
        val sb = StringBuilder("olcrtc://").append(provider).append("?").append(transport)
        val params = mutableListOf<String>()
        when (transport) {
            "vp8channel" -> {
                params.add("vp8-fps=$vp8Fps")
                params.add("vp8-batch=$vp8Batch")
            }
            "seichannel" -> {
                params.add("fps=$seiFps")
                params.add("batch=$seiBatch")
                params.add("frag=$seiFrag")
                params.add("ack-ms=$seiAckMs")
            }
            "videochannel" -> {
                params.add("video-w=$videoW")
                params.add("video-h=$videoH")
                params.add("video-fps=$videoFps")
                params.add("video-codec=$videoCodec")
                if (videoCodec == "qrcode") {
                    params.add("video-qr-size=$videoQrSize")
                    params.add("video-qr-recovery=$videoQrRecovery")
                } else if (videoCodec == "tile") {
                    params.add("video-tile-module=$videoTileModule")
                    params.add("video-tile-rs=$videoTileRs")
                }
            }
        }
        if (params.isNotEmpty()) sb.append("<").append(params.joinToString("&")).append(">")
        sb.append("@").append(id)
        if (key.isNotBlank()) sb.append("#").append(key)

        val effectiveMimo = if (mimo.isNotBlank()) {
            mimo
        } else if (!profileName.isNullOrBlank()) {
            profileName
        } else {
            ""
        }
        if (effectiveMimo.isNotBlank()) sb.append("$").append(effectiveMimo)
        return sb.toString()
    }

    companion object {
        // Значения, которые понимает визуальный кодек olcrtc; всё остальное отклоняется при старте.
        val VIDEO_QR_RECOVERY_LEVELS = listOf("low", "medium", "high", "highest")
        const val VIDEO_MIN_DIMENSION = 16
        const val VIDEO_MAX_DIMENSION = 8192

        fun getTransportDisplayName(transport: String, short: Boolean = false): String = when (transport) {
            "datachannel" -> if (short) "DC" else "DataChannel"
            "vp8channel" -> if (short) "VP8C" else "VP8Channel"
            "seichannel" -> if (short) "SEIC" else "SEIChannel"
            "videochannel" -> if (short) "VC" else "VideoChannel"
            else -> transport
        }

        fun parse(url: String, current: OlcrtcConfig = OlcrtcConfig()): OlcrtcConfig? {
            if (!url.startsWith("olcrtc://", ignoreCase = true)) return null
            // The Olcrtc_manager admin panel (https://github.com/Oleglog/Olcrtc_manager) emits its own
            // bespoke deep-link/QR dialect instead of this project's own documented uri.md grammar.
            // Its own store.go unconditionally backfills "core=legacy" onto every URI it persists -
            // including ones an operator pastes in manually without it - specifically so downstream
            // consumers can recognize its dialect; "core" isn't a real olcrtc config/auth field (absent
            // from the actual binary), so this can't collide with a native link. Trust that one marker,
            // the same way olcbox's own parser trusts the native grammar's delimiters without trying to
            // infer anything from the room id's shape - jitsi's native RoomID is itself a URL, which is
            // exactly what made an earlier shape-based heuristic here misfire on real links.
            if (url.contains("core=legacy", ignoreCase = true)) {
                return parseUrlEmbeddedDialect(url.substringAfter("olcrtc://"), current)
            }
            return try {
                val provider = url.substringAfter("olcrtc://").substringBefore("?")
                val transportPart = url.substringAfter("?").substringBefore("@")
                val transport = transportPart.substringBefore("<")
                val payload = if (transportPart.contains("<")) transportPart.substringAfter("<").substringBefore(">") else ""
                val rest = url.substringAfter("@")
                val id = rest.substringBefore("#").substringBefore("$")
                val key = if (rest.contains("#")) rest.substringAfter("#").substringBefore("$") else ""
                val mimo = if (rest.contains("$")) rest.substringAfter("$") else ""
                var cfg = current.copy(
                    provider = provider,
                    transport = transport,
                    id = id,
                    key = key,
                    mimo = mimo
                )
                if (payload.isNotBlank()) {
                    val p = payload.split("&").associate { it.substringBefore("=") to it.substringAfter("=", "") }
                    cfg = when (transport) {
                        "vp8channel" -> cfg.copy(
                            vp8Fps = p["vp8-fps"]?.toIntOrNull() ?: cfg.vp8Fps,
                            vp8Batch = p["vp8-batch"]?.toIntOrNull() ?: cfg.vp8Batch
                        )
                        "seichannel" -> cfg.copy(
                            seiFps = p["fps"]?.toIntOrNull() ?: cfg.seiFps,
                            seiBatch = p["batch"]?.toIntOrNull() ?: cfg.seiBatch,
                            seiFrag = p["frag"]?.toIntOrNull() ?: cfg.seiFrag,
                            seiAckMs = p["ack-ms"]?.toIntOrNull() ?: cfg.seiAckMs
                        )
                        "videochannel" -> cfg.copy(
                            videoW = p["video-w"]?.toIntOrNull() ?: cfg.videoW,
                            videoH = p["video-h"]?.toIntOrNull() ?: cfg.videoH,
                            videoFps = p["video-fps"]?.toIntOrNull() ?: cfg.videoFps,
                            videoCodec = p["video-codec"] ?: cfg.videoCodec,
                            videoQrSize = p["video-qr-size"]?.toIntOrNull() ?: cfg.videoQrSize,
                            videoQrRecovery = p["video-qr-recovery"] ?: cfg.videoQrRecovery,
                            videoTileModule = p["video-tile-module"]?.toIntOrNull() ?: cfg.videoTileModule,
                            videoTileRs = p["video-tile-rs"]?.toIntOrNull() ?: cfg.videoTileRs
                        )
                        else -> cfg
                    }
                }
                cfg
            } catch (_: Exception) {
                null
            }
        }

        // olcrtc://<carrier>@<placeholder>/<room, percent-encoded in the QR form>?<params>#<name>
        //
        // Two variants come out of that panel, both handled here:
        //  - subscription line (buildURIWith):        ...@room/<room>?key=...&transport=...&vp8_fps=...&vp8_batch=...&core=legacy[&client_id=...]#<name>
        //  - QR code (buildCompactURIWith):            ...@r/<room, url.PathEscape'd>?k=...&t=...&f=...&b=...&core=legacy[&c=...][&d=...]#<name, url.QueryEscape'd>
        //
        // "core" is hardcoded to the literal "legacy" unconditionally by both builders - it's a
        // dialect-version tag for the panel's own future use, not a real setting - and "client_id"
        // only ever reaches that panel's own instance bookkeeping (OLCRTC_CLIENT_ID env var). Neither
        // exists anywhere in the actual olcrtc core's config or auth packages, so both are dropped.
        private fun parseUrlEmbeddedDialect(afterScheme: String, current: OlcrtcConfig): OlcrtcConfig? {
            return try {
                val provider = afterScheme.substringBefore("@")
                val afterAt = afterScheme.substringAfter("@")
                if (!afterAt.contains("/")) return null
                val afterPlaceholder = afterAt.substringAfter("/")
                val fragment = if (afterPlaceholder.contains("#")) afterPlaceholder.substringAfter("#") else ""
                val withoutFragment = afterPlaceholder.substringBefore("#")
                val idEncoded = withoutFragment.substringBefore("?")
                val query = if (withoutFragment.contains("?")) withoutFragment.substringAfter("?") else ""
                val params = if (query.isNotBlank()) {
                    query.split("&").associate { it.substringBefore("=") to Uri.decode(it.substringAfter("=", "")) }
                } else emptyMap()

                current.copy(
                    provider = provider,
                    id = Uri.decode(idEncoded),
                    key = params["key"] ?: params["k"] ?: current.key,
                    transport = params["transport"] ?: params["t"] ?: current.transport,
                    vp8Fps = (params["vp8_fps"] ?: params["f"])?.toIntOrNull() ?: current.vp8Fps,
                    vp8Batch = (params["vp8_batch"] ?: params["b"])?.toIntOrNull() ?: current.vp8Batch,
                    mimo = Uri.decode(fragment).ifBlank { current.mimo }
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
