package com.wireturn.app.kernel

import android.content.Context
import androidx.core.net.toUri
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.ui.activities.kernel.TurnableConfigActivity
import java.io.File
import java.util.regex.Pattern

object TurnableKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.TURNABLE
    override val displayNameRes: Int = R.string.kernel_turnable
    override val configActivityClass = TurnableConfigActivity::class.java

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.Turnable).config
        val route = config.routes.find { it.routeId == config.selectedRouteId }
        val transport = route?.socket?.uppercase()?.ifBlank { null }
        return context.getString(displayNameRes) + transport?.let { " $it" }.orEmpty()
    }

    override fun profileSummaryExtra(cfg: KernelConfig): String =
        (cfg as KernelConfig.Turnable).config.platformDisplayName

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = when ((cfg as KernelConfig.Turnable).config.platformId) {
        "vk.com" -> R.drawable.ic_vk
        else -> if (outlined) R.drawable.mobile_outlined_24px else R.drawable.mobile_24px
    }

    // Route's own socket type is authoritative (tcp -> VLESS/Trojan, udp -> WireGuard/Hysteria2) -
    // see external/turnable/docs/REFERENCE.md.
    override fun requiredTransport(cfg: KernelConfig): String? {
        val config = (cfg as KernelConfig.Turnable).config
        return config.routes.find { it.routeId == config.selectedRouteId }
            ?.socket?.lowercase()?.takeIf { it == "tcp" || it == "udp" }
    }

    override val defaultProfileName: String = "Turnable Server"

    override fun decodeUri(uri: String): KernelConfig? =
        com.wireturn.app.data.TurnableConfig.parse(uri)?.let { KernelConfig.Turnable(it) }

    override fun displayNameFromUri(uri: String): String? = try {
        uri.toUri().fragment?.split(",")?.firstOrNull()?.trim()
    } catch (_: Exception) { null }

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val k = cfg.kernelConfig as KernelConfig.Turnable
        val cmdArgs = mutableListOf<String>()
        cmdArgs.add("${ctx.nativeLibraryDir}/libturnable.so")
        // Via -config file rather than as a positional arg (both are supported by turnable's
        // client subcommand) so the join link/key doesn't end up in the process command line,
        // which gets written verbatim to the app's own log.
        val configFile = File(ctx.filesDir, "turnable.json")
        configFile.writeText(k.config.toUri(true))
        cmdArgs.addAll(listOf(
            "client",
            "-l", cfg.listenAddr.ifBlank { ClientConfig.DEFAULT_LISTEN_ADDR },
            "-c", configFile.absolutePath
        ))
        return cmdArgs
    }

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        // 1. Hard Errors (Watchdog won't help, needs manual fix)
        if (lower.contains("call not found") || lower.contains("join link is not valid")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(ctx.getString(R.string.error_room_not_found)))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        if (lower.contains("vk signaling connect rejected: not authorized") ||
            lower.contains("failed to validate connection url") ||
            lower.contains("second shutdown signal received") ||
            lower.contains("panic") || lower.contains("fatal")
        ) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        // 2. Soft Errors (Transient network issues, watchdog will restart)
        val isSignalingLoopTerminated = lower.contains("vk signaling loop terminated")
        val isNormalClose = lower.contains("close 1000 (normal)")

        if (lower.contains("vk authorize anonymous flow failed") ||
            lower.contains("vk calls login failed") ||
            lower.contains("vk join conversation failed") ||
            (isSignalingLoopTerminated && !isNormalClose)
        ) {
            // Break reading and let watchdog restart the process
            state.startupEmitted = true
            return true
        }

        // Turnable's PoW-captcha retry loop has no attempt cap and hammers the same broken
        // request forever - surface a clear error after a few failures instead of relying on
        // the generic 120s connecting-timeout watchdog. Gated on network being up so a dropped
        // connection isn't miscounted as a captcha failure.
        if (lower.contains("vk captcha solve failed") && ctx.isNetworkAvailable()) {
            if (state.vkCaptchaSolveFailCounter.recordAndCheckThreshold()) {
                // "pow arguments not found" means the captcha page itself is broken (e.g. VK
                // changed markup) - restarting won't fix that. Anything else is likely transient.
                if (lower.contains("captcha pow arguments not found")) {
                    if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                        CoreServiceState.setStatus(CoreStatus.Error(ctx.getString(R.string.error_turnable_vk_captcha_failed)))
                        ctx.updateNotification(ctx.getString(R.string.error_connecting))
                    }
                    state.startupFailed = true
                } else {
                    state.startupEmitted = true
                }
                return true
            }
        }

        if (lower.contains("failed to start vpn client")) {
            val errorPart = line.substringAfterLast(":").trim()
            val lowerError = errorPart.lowercase()
            if (lowerError.contains("read tcp") || lowerError.contains("timeout") || lowerError.contains("abort")) {
                state.startupEmitted = true
                return true
            }
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(ctx.getString(R.string.error_turnable_failed, errorPart)))
            }
            state.startupFailed = true
            return true
        }

        // 2. Connected
        val onlineCount = getOnlineCount(lower)
        if (lower.contains("turnable client started") ||
            lower.contains("relay client session connected") ||
            lower.contains("direct session connected") ||
            (onlineCount != null && onlineCount >= 1 && lower.contains("peer online"))
        ) {
            state.peerConnectFailedCount = 0
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
                state.startupEmitted = true
            }
        }

        // 3. Connecting / Progress / Retries
        if (lower.contains("starting turnable client") ||
            lower.contains("starting full reconnect") ||
            lower.contains("direct: starting full reconnect") ||
            lower.contains("vk captcha challenge received") ||
            lower.contains("vk captcha solved") ||
            lower.contains("all auto captcha attempts exhausted") ||
            lower.contains("manual captcha solve required") ||
            lower.contains("vk signaling websocket dial failed") ||
            lower.contains("turn candidate failed") ||
            lower.contains("dtls direct connect failed") ||
            lower.contains("srtp direct connect failed") ||
            lower.contains("dtls client handshake started") ||
            lower.contains("srtp client handshake started") ||
            lower.contains("peer connect failed") ||
            lower.contains("peer quota reached") ||
            lower.contains("full reconnect failed") ||
            lower.contains("direct: full reconnect failed") ||
            lower.contains("primary handshake failed") ||
            lower.contains("secondary handshake failed") ||
            lower.contains("peer reconnect failed") ||
            lower.contains("scheduling peer retry") ||
            lower.contains("tinymux client received disconnect") ||
            lower.contains("tinymux client cut off unexpectedly") ||
            lower.contains("quota") ||
            (onlineCount != null && onlineCount == 0 && lower.contains("peer offline"))
        ) {
            if (canUpdateConnectingStatus()) {
                if (ctx.isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                }
                markConnecting()
                state.startupEmitted = true
            }
        }

        return false
    }

    private fun getOnlineCount(lower: String): Int? {
        val matcher = ONLINE_COUNT_REGEX.matcher(lower)
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
    }

    private val ONLINE_COUNT_REGEX = Pattern.compile("""online=(\d+)""")
}
