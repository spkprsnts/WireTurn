package com.wireturn.app.kernel

import android.content.Context
import androidx.core.net.toUri
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.ui.activities.kernel.OpenFluxConfigActivity

// OpenFlux (external/openflux, upstream p1neappleXpress/OpenFlux). No captcha flow, but no single
// clean "connected" line either - the two transports it wraps (Yandex.Docs, MAX/oneme) differ a
// lot in how much they actually log, so parseLogLine's connected/error detection is split by
// transport below. log.Fatalf hard failures in main.go also exit the process, so the generic "no
// output before exit" fallback in CoreService.runBinary would eventually catch those too, but
// matching the line directly gives a much faster, more specific error.
object OpenFluxKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.OPENFLUX
    // --url carries the Yandex.Docs document link, which is effectively the shared secret/
    // rendezvous point for that transport - as sensitive as FreeTurn's -links/-sub.
    override val sensitiveCommandFlags: Set<String> = setOf("--maxToken", "--url")
    override val displayNameRes: Int = R.string.kernel_openflux
    override val configActivityClass = OpenFluxConfigActivity::class.java
    override val wgNotUsedMessageRes: Int = R.string.wg_not_used_with_openflux
    // socks5SupportsAuth comes from the default (KernelVariant.socks5SupportsAuth = variant != OPENFLUX).

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.OpenFlux).config
        return context.getString(displayNameRes) + " " + config.platformDisplayName
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = when ((cfg as KernelConfig.OpenFlux).config.transport) {
        "yandex" -> R.drawable.ic_yandex_docs
        "oneme" -> R.drawable.ic_max
        else -> R.drawable.route_24px
    }

    override val defaultProfileName: String = "OpenFlux Server"

    override fun decodeUri(uri: String): KernelConfig? =
        com.wireturn.app.data.OpenFluxConfig.parse(uri)?.let { KernelConfig.OpenFlux(it) }

    override fun displayNameFromUri(uri: String): String? = try {
        // Mirror OpenFluxConfig.parse's own normalization - the schemeless "openflux:config?..."
        // form has no "//", so Uri treats it as opaque and getQueryParameter() throws on it.
        val normalized = if (uri.startsWith("openflux://", ignoreCase = true)) uri
            else uri.replaceFirst("openflux:", "openflux://", ignoreCase = true)
        normalized.toUri().getQueryParameter("name")
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
        // Needed for real log visibility: main.go's own client/transport lines (banner, "Running
        // as CLIENT", fatal errors) print unconditionally either way, but everything from the
        // Yandex.Docs handshake/reconnect loop (utils.Debugf in transport/yandex) is a no-op
        // without this - see parseLogLine below, which depends on it to detect a dead session.
        cmdArgs.add("--debug")
        return cmdArgs
    }

    // "Running as CLIENT" is printed the instant main.go's trans.Start() call *returns* - but
    // both transports' Start() only kick off their handshake in a background goroutine and
    // return immediately (see external/openflux transport/yandex/yandex.go:68-78 and
    // transport/oneme/max_transport.go:35-55), so it is NOT a reliable "tunnel is actually up"
    // signal for either one. It's kept as the Connected signal only for Yandex, which has no
    // better one at all (see below); MAX gets a real one from its own call-signaling log.
    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        val transport = (cfg.kernelConfig as? KernelConfig.OpenFlux)?.config?.transport ?: "yandex"

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

        // 3. SOCKS5 listener is up - the transport handshake itself may still be running in the
        // background (see the class-level note above). Only a real "Connected" for Yandex, which
        // never logs a definite success of its own - see point 4.
        if (lower.contains("running as client (socks5 on")) {
            state.startupEmitted = true
            if (transport != "oneme" && CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
            } else if (canUpdateConnectingStatus()) {
                markConnecting()
            }
        }

        // 4. Yandex.Docs transport (--debug required, see buildCommand): fetchDocInfo/WebSocket
        // dial/read failures inside transport/yandex.go's connectToDoc() are the only signal this
        // transport ever gives - there's no log line for success, and MaxReconnectAttempts is
        // effectively infinite (999999, no delay) with no "giving up" message either. So a
        // permanently broken doc link would otherwise spin forever behind an already-"Connected"
        // status. Once repeated failures pile up within the window, treat it as dead and force a
        // real process restart (fresh CoreService backoff, fresh attempt=0 on relaunch).
        if (transport != "oneme" && (
                lower.contains("[ydocs] fetchdocinfo failed") ||
                lower.contains("[ydocs] websocket dial failed") ||
                lower.contains("[ydocs] read error")
            )
        ) {
            if (state.openFluxYandexFailureCounter.recordAndCheckThreshold()) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
                state.startupFailed = true
                return true
            }
            if (canUpdateConnectingStatus()) {
                markConnecting()
            }
            state.startupEmitted = true
        }

        // 5. MAX (oneme) transport call signaling (external/openflux transport/oneme/max_call.go,
        // always logged - not behind --debug). "] DC opened" is the actual data-channel-ready
        // signal (the point traffic can flow), not just "Running as CLIENT" above.
        if (transport == "oneme") {
            if (lower.contains("] dc opened")) {
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Connected)
                    ctx.updateNotification(ctx.getString(R.string.core_active))
                    state.startupEmitted = true
                }
            } else if (lower.contains("signaling error") ||
                lower.contains("error creating pc") ||
                lower.contains("error creating dc") ||
                lower.contains("] dc is nil") ||
                lower.contains("receiver connection died")
            ) {
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Error(line))
                    ctx.updateNotification(ctx.getString(R.string.error_connecting))
                }
                state.startupFailed = true
                return true
            } else if (lower.contains("dial error") ||
                lower.contains("signaling disconnected") ||
                lower.contains("] ice error")
            ) {
                // Transient - max_call.go retries these on its own (e.g. "Reconnecting in 1s...").
                if (state.openFluxMaxFailureCounter.recordAndCheckThreshold()) {
                    CoreServiceState.setStatus(CoreStatus.Error(line))
                    ctx.updateNotification(ctx.getString(R.string.error_connecting))
                    state.startupFailed = true
                    return true
                }
                if (canUpdateConnectingStatus()) {
                    markConnecting()
                }
                state.startupEmitted = true
            } else if (lower.contains("accept-call sent") ||
                lower.contains("creating peerconnection") ||
                lower.contains("waiting for calls") ||
                lower.contains("incoming call!") ||
                lower.contains("] calling ") ||
                lower.contains("creating offer") ||
                lower.contains("*** connected! ***")
            ) {
                if (canUpdateConnectingStatus()) {
                    markConnecting()
                }
                state.startupEmitted = true
            }
        }

        return false
    }
}
