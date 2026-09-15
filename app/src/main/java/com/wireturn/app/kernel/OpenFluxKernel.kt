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
import java.io.File

// OpenFlux (external/openflux, upstream p1neappleXpress/OpenFlux). No captcha flow, but no single
// clean "connected" line either - the four transports it wraps (Yandex.Docs, vyandex/"Volga",
// MAX/oneme, cups.online) differ a lot in how much they actually log, so parseLogLine's
// connected/error detection is split by transport below. log.Fatalf hard failures in main.go also
// exit the process, so the generic "no output before exit" fallback in CoreService.runBinary
// would eventually catch those too, but matching the line directly gives a much faster, more
// specific error.
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

    override fun profileSummaryExtra(context: Context, cfg: KernelConfig): List<String> {
        val config = (cfg as KernelConfig.OpenFlux).config
        return listOfNotNull(
            context.getString(R.string.kernel_tag_encrypted).takeIf { config.encryptionKey.isNotBlank() }
        )
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = when ((cfg as KernelConfig.OpenFlux).config.transport) {
        "yandex", "vyandex" -> R.drawable.ic_yandex_docs
        "oneme" -> R.drawable.ic_max
        "cupsonline" -> R.drawable.ic_cupsonline
        else -> R.drawable.route_24px
    }

    override val defaultProfileName: String = "OpenFlux Server"

    override fun decodeUri(uri: String): KernelConfig? =
        com.wireturn.app.data.OpenFluxConfig.parse(uri)?.let { KernelConfig.OpenFlux(it) }

    override fun displayNameFromUri(uri: String): String? = try {
        uri.toUri().getQueryParameter("name")
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
        // Optional end-to-end encryption on top of the transport (--encryption-key-file, added
        // upstream alongside vyandex) - the flag takes a file path, not the secret itself, so it
        // gets written out fresh on every start rather than passed inline like -maxToken/-url.
        if (o.encryptionKey.isNotBlank()) {
            val keyFile = File(ctx.filesDir, "openflux_key.txt")
            keyFile.writeText(o.encryptionKey)
            cmdArgs.addAll(listOf("--encryption-key-file", keyFile.absolutePath))
        }
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
        // never logs a definite success of its own - see point 4. oneme has its own definite
        // success signal (point 5) that always logs *after* this line, so marking Connecting here
        // in the meantime is safe for it. cupsonline is different: trans.Start() (which is where
        // its "[CUPS] transport started" success line - point 6 - comes from) runs *before* this
        // "Running as CLIENT" line in main.go, so by the time this line arrives cupsonline may
        // already be Connected - calling markConnecting() unconditionally here would stomp that
        // back to Connecting with no later line to ever set it again. So cupsonline is left alone
        // entirely at this point; state.startupEmitted is already true from point 2 either way.
        if (lower.contains("running as client (socks5 on")) {
            state.startupEmitted = true
            if (transport != "oneme" && transport != "cupsonline" && CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
            } else if (transport != "cupsonline" && canUpdateConnectingStatus()) {
                markConnecting()
            }
        }

        // 4. Yandex.Docs transport (--debug required, see buildCommand): fetchDocInfo/WebSocket
        // dial/read failures inside transport/yandex.go's connectToDoc() are the only signal this
        // transport ever gives - there's no log line for success, and MaxReconnectAttempts is
        // effectively infinite (999999, no delay) with no "giving up" message either. A "Read
        // error" here is routine - the doc's WebSocket session gets recycled periodically (e.g.
        // "close 1005 (no status)") and connectToDoc() reconnects transparently, traffic keeps
        // flowing right through it - so this must NOT touch CoreStatus below the threshold: since
        // there is no "reconnected" line to ever bring it back from Connecting, doing so would
        // leave the status stuck on Connecting forever after the very first routine reconnect.
        // Only once repeated failures pile up within the window (a genuinely dead doc link, not a
        // routine recycle) do we intervene, forcing a real process restart (fresh CoreService
        // backoff, fresh attempt=0 on relaunch).
        if (transport != "oneme" && (
                lower.contains("[ydocs] fetchdocinfo failed") ||
                lower.contains("[ydocs] websocket dial failed") ||
                lower.contains("[ydocs] read error")
            )
        ) {
            // Check real device connectivity before burning through the failure counter above -
            // if the phone has no network at all, this is not "the doc link is dead", it's just
            // offline, and isNetworkMissingAndHandled() already flips CoreStatus to
            // WaitingForNetwork itself (see TurnableKernel for the same pattern). Otherwise the
            // outer CoreService watchdog loop would eventually reach the same conclusion on its
            // own (it re-checks network state once this process exits either way), but only after
            // the full ~90s threshold window above has already elapsed for nothing.
            if (ctx.isNetworkMissingAndHandled()) {
                state.startupFailed = true
                return true
            }
            if (state.openFluxYandexFailureCounter.recordAndCheckThreshold()) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
                state.startupFailed = true
                return true
            }
            state.startupEmitted = true
        }

        // 4b. vyandex ("Volga") transport: Start() itself is synchronous (auth happens before
        // "Running as CLIENT" ever prints), so an auth failure is already caught by point 1
        // above via main.go's own "failed to start transport" fatal line - no heuristic needed
        // for that part, unlike classic Yandex. Once running, though, its WS listener reconnects
        // forever on its own with backoff, same silent-spin risk as classic Yandex's read-error
        // loop above.
        if (transport == "vyandex" && (
                lower.contains("[volga] ws error") ||
                lower.contains("[volga] batch send failed")
            )
        ) {
            if (ctx.isNetworkMissingAndHandled()) {
                state.startupFailed = true
                return true
            }
            if (state.openFluxVolgaFailureCounter.recordAndCheckThreshold()) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
                state.startupFailed = true
                return true
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
                if (ctx.isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                }
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

        // 6. cups.online transport (external/openflux transport/cupsonline/cupsonline.go). Room
        // joins happen synchronously inside Start(): if every room fails, Start() returns "no
        // rooms joined" and main.go's own log.Fatalf already trips point 1 above via "failed to
        // start transport" - no heuristic needed for a total failure. "[CUPS] transport started"
        // is the definite success signal (all requested channels are up and dialing), taking over
        // from "Running as CLIENT" at point 3, which this transport is excluded from above.
        // Each channel's own WebSocket then reconnects forever with backoff (up to 10s) on its
        // own, same silent-spin risk as Yandex/vyandex above - "[CUPS] ws error" is that loop's
        // only signal, so it gets its own counter tuned to the faster backoff cap.
        if (transport == "cupsonline") {
            if (lower.contains("[cups] transport started")) {
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Connected)
                    ctx.updateNotification(ctx.getString(R.string.core_active))
                }
                state.startupEmitted = true
            } else if (lower.contains("[cups] ws error")) {
                if (ctx.isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                }
                if (state.openFluxCupsFailureCounter.recordAndCheckThreshold()) {
                    CoreServiceState.setStatus(CoreStatus.Error(line))
                    ctx.updateNotification(ctx.getString(R.string.error_connecting))
                    state.startupFailed = true
                    return true
                }
                state.startupEmitted = true
            }
        }

        return false
    }
}
