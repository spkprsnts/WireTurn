package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.ui.activities.kernel.OpenFluxConfigActivity

// OpenFlux (external/openflux, upstream p1neappleXpress/OpenFlux). Plain Go log.Printf output, no
// captcha/multi-step auth flow to handle - just a startup banner, a final "ready" line once
// trans.Start() has already succeeded, and log.Fatalf on hard failure (which also exits the
// process, so the generic "no output before exit" fallback in CoreService.runBinary would
// eventually catch it too, but matching the line directly gives a much faster, more specific error).
object OpenFluxKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.OPENFLUX
    override val sensitiveCommandFlags: Set<String> = setOf("--maxToken")
    override val displayNameRes: Int = R.string.kernel_openflux
    override val configActivityClass = OpenFluxConfigActivity::class.java
    override val wgNotUsedMessageRes: Int = R.string.wg_not_used_with_openflux
    // OpenFlux's embedded SOCKS5 server has no auth flags upstream - never offer credentials.
    override val socks5SupportsAuth: Boolean = false

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.OpenFlux).config
        return context.getString(displayNameRes) + " " + config.platformDisplayName
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = R.drawable.route_24px

    override val defaultProfileName: String = "OpenFlux Server"

    override fun decodeUri(uri: String): KernelConfig? =
        com.wireturn.app.data.OpenFluxConfig.parse(uri)?.let { KernelConfig.OpenFlux(it) }

    override fun displayNameFromUri(uri: String): String? = try {
        android.net.Uri.parse(uri).getQueryParameter("name")
    } catch (_: Exception) { null }

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val cmdArgs = mutableListOf<String>()
        val o = (cfg.kernelConfig as KernelConfig.OpenFlux).config
        cmdArgs.add("${ctx.nativeLibraryDir}/libopenflux.so")
        cmdArgs.addAll(listOf(
            "--client",
            "--socks5", cfg.socksAddr.ifBlank { ClientConfig.DEFAULT_SOCKS_ADDR },
            "--transport", o.transport
        ))
        // No SOCKS5 auth flags exist upstream - cfg.isSocksAuthEnabled/socksUser/socksPass
        // don't apply to this kernel, unlike Qwdtt.
        if (o.transport == "oneme") {
            cmdArgs.addAll(listOf("--maxToken", o.maxToken, "--maxUid", o.maxUid))
        } else {
            cmdArgs.addAll(listOf("--url", o.url))
        }
        cmdArgs.add("--debug")
        return cmdArgs
    }

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        // 1. Hard errors (log.Fatalf in main.go - prints then exits)
        if (lower.startsWith("panic:") ||
            lower.contains("failed to start transport") ||
            lower.contains("unknown transport type")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        // 2. Connecting - first banner line, printed before the transport handshake starts.
        if (lower.contains("=== universal bypass tool ===")) {
            if (canUpdateConnectingStatus()) {
                markConnecting()
            }
            state.startupEmitted = true
        }

        // 3. Connected - the last line main.go prints, only reached once trans.Start() (the
        // Yandex.Docs / MAX login+call handshake) has already returned successfully.
        if (lower.contains("running as client (socks5 on")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
                state.startupEmitted = true
            }
        }

        return false
    }
}
