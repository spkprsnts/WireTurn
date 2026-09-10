package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.OlcrtcConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.ui.activities.cores.OlcRtcConfigActivity
import java.io.File

object OlcrtcKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.OLCRTC
    override val displayNameRes: Int = R.string.kernel_olcrtc
    override val configActivityClass = OlcRtcConfigActivity::class.java
    override val wgNotUsedMessageRes: Int = R.string.wg_not_used_with_olcrtc

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.Olcrtc).config
        return context.getString(displayNameRes) + " " + config.providerDisplayName
    }

    override fun profileSummaryExtra(cfg: KernelConfig): String? {
        val config = (cfg as KernelConfig.Olcrtc).config
        return OlcrtcConfig.getTransportDisplayName(config.transport, short = true)
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = when ((cfg as KernelConfig.Olcrtc).config.provider) {
        "wbstream" -> R.drawable.ic_wbstream
        "telemost" -> R.drawable.ic_telemost
        "jitsi" -> R.drawable.ic_jitsi
        else -> if (outlined) R.drawable.mobile_outlined_24px else R.drawable.mobile_24px
    }

    override val defaultProfileName: String = "Olcrtc Server"

    override fun decodeUri(uri: String): KernelConfig? =
        OlcrtcConfig.parse(uri)?.let { KernelConfig.Olcrtc(it) }

    // olcrtc://<Provider>?<Transport>@<RoomID>#<EncryptionKey>$<MIMO> - MIMO doubles as the name.
    override fun displayNameFromUri(uri: String): String? =
        OlcrtcConfig.parse(uri)?.mimo?.takeIf { it.isNotBlank() }

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val cmdArgs = mutableListOf<String>()
        cmdArgs.add("${ctx.nativeLibraryDir}/libolcrtc.so")
        val configFile = File(ctx.filesDir, "olcrtc.yaml")
        configFile.writeText(buildOlcrtcYaml(cfg))
        cmdArgs.add(configFile.absolutePath)
        return cmdArgs
    }

    private fun buildOlcrtcYaml(cfg: ClientConfig): String {
        val o = (cfg.kernelConfig as KernelConfig.Olcrtc).config
        return buildString {
            appendLine("mode: cnc")
            appendLine("auth:")
            appendLine("  provider: ${o.provider}")
            appendLine("room:")
            appendLine("  id: \"${o.id}\"")
            appendLine("crypto:")
            appendLine("  key: \"${o.key}\"")
            appendLine("net:")
            appendLine("  transport: ${o.transport}")
            // Unlike WebDAV's -dns, olcRTC hard-fails startup ("dns server required") if this is
            // empty, so fall back to a default rather than ever handing it a blank value.
            appendLine("  dns: \"${cfg.dns.ifBlank { ClientConfig.DEFAULT_DNS }}\"")
            appendLine("socks:")
            appendLine("  host: \"${cfg.socksAddr.substringBefore(':').ifBlank { "127.0.0.1" }}\"")
            appendLine("  port: ${cfg.socksAddr.substringAfter(':', "9001").ifBlank { "9001" }}")
            if (cfg.isSocksAuthEnabled) {
                appendLine("  user: \"${cfg.socksUser}\"")
                appendLine("  pass: \"${cfg.socksPass}\"")
            }
            when (o.transport) {
                "vp8channel" -> {
                    appendLine("vp8:")
                    appendLine("  fps: ${o.vp8Fps}")
                    appendLine("  batch_size: ${o.vp8Batch}")
                }
                "seichannel" -> {
                    appendLine("sei:")
                    appendLine("  fps: ${o.seiFps}")
                    appendLine("  batch_size: ${o.seiBatch}")
                    appendLine("  fragment_size: ${o.seiFrag}")
                    appendLine("  ack_timeout_ms: ${o.seiAckMs}")
                }
                "videochannel" -> {
                    appendLine("video:")
                    appendLine("  codec: ${o.videoCodec}")
                    appendLine("  width: ${o.videoW}")
                    appendLine("  height: ${o.videoH}")
                    appendLine("  fps: ${o.videoFps}")
                    if (o.videoCodec == "qrcode") {
                        appendLine("  qr_recovery: ${o.videoQrRecovery}")
                        appendLine("  qr_size: ${o.videoQrSize}")
                    } else if (o.videoCodec == "tile") {
                        appendLine("  tile_module: ${o.videoTileModule}")
                        appendLine("  tile_rs: ${o.videoTileRs}")
                    }
                }
            }
        }
    }

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        val olcrtcConfig = (cfg.kernelConfig as? KernelConfig.Olcrtc)?.config ?: OlcrtcConfig()

        if (lower.contains("join room failed: status 404") || lower.contains("guests cannot create rooms")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(ctx.getString(R.string.error_room_not_found)))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        if (lower.contains("socks5 server listening on")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
                state.startupEmitted = true
            }
        }

        if (lower.contains("setupcipher failed")) {
            CoreServiceState.setStatus(CoreStatus.Error(ctx.getString(R.string.error_invalid_auth_key)))
            state.startupFailed = true
            return true
        }

        if (lower.contains("failed to connect link") || lower.contains("failed to create link")) {
            if (ctx.getNetworkQuality() == NetworkQuality.FAST) {
                // Быстрая сеть, но ошибка линка — платформа недоступна
                CoreServiceState.setStatus(CoreStatus.Error(ctx.getString(R.string.error_platform_unavailable)))
                state.startupFailed = true
            } else {
                // Либо медленная, либо лежит совсем — на откуп watchdog
                state.startupEmitted = true
            }
            return true
        }

        if (olcrtcConfig.restartOnConnectionErrors && (lower.contains("remote not ready") || lower.contains("openstream failed"))) {
            if (state.remoteNotReadyCounter.recordAndCheckThreshold()) {
                com.wireturn.app.AppLogsState.addLog(ctx.getString(R.string.log_core_too_many_remote_not_ready))
                state.startupEmitted = true // Trigger watchdog
                return true
            }
        }

        if (lower.contains("client reconnect attempt=2")) {// reason=carrier
            com.wireturn.app.AppLogsState.addLog(ctx.getString(R.string.log_core_reconnect_restart))
            state.startupEmitted = true
            return true
        }

        if (lower.contains("panic") || lower.contains("fatal") || lower.contains("error starting socks5")) {
            CoreServiceState.setStatus(CoreStatus.Error(line))
            state.startupFailed = true
            return true
        }

        return false
    }
}
