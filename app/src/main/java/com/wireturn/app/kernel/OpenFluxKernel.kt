package com.wireturn.app.kernel

import android.content.Context
import androidx.core.net.toUri
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.kernel.OpenFluxConfig
import com.wireturn.app.ui.activities.kernel.OpenFluxConfigActivity
import java.io.File

// OpenFlux (external/openflux, upstream p1neappleXpress/OpenFlux). No captcha flow, but no single
// clean "connected" line either - the five transports it wraps (Yandex.Docs, vyandex/"Volga",
// MAX/oneme, cups.online, Mail.ru Docs) differ a lot in how much they actually log, so parseLogLine's
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

    // Go's net.DNSError always renders as "... lookup <host>: no such host" - pulls the host back
    // out for error_openflux_dns_lookup_failed (see parseLogLine point 0d) instead of asserting
    // it's the document URL, since a device-level DNS interceptor can be what's actually failing.
    private val DNS_LOOKUP_HOST_REGEX = Regex("lookup ([^:]+): no such host")

    // One entry per deterministic "this exact link/config can never work" signal - matched against
    // an already-lowercased log line, scoped to the transport(s) whose own Go source actually
    // produces that wording, so a new transport just adds a row here instead of a new if-block.
    // Doesn't cover 0d (DNS failure - needs isNetworkMissingAndHandled() first and a dynamic host
    // in the message) or point 1 (generic panics - message is the raw line, not a resource lookup).
    private class FastFailRule(val transports: Set<String>, val messageRes: Int, val matcher: (String) -> Boolean)

    private val FAST_FAIL_RULES = listOf(
        // 0a. Volga auth failure (transport/yandex/vyandex.go): "action_url missing" means the
        // shared link is a classic Yandex.Docs document with no Volga real-time-editor backend -
        // vyandex can never authenticate against it, no matter how long it retries.
        FastFailRule(setOf("vyandex"), R.string.error_openflux_vyandex_wrong_doc) {
            "action_url missing" in it
        },
        // 0b. The mirror image of 0a: classic yandex transport (transport/yandex/yandex.go
        // fetchDocInfo) pointed at a Volga-only document. "officeActionData"/"editor_config"/
        // "balancer_url" missing are all read from the same already-fetched, already-parsed page -
        // structural for this doc, not a transient fetch hiccup - despite each one's own "will
        // reconnect" wording (point 4 below still retries these forever via
        // openFluxYandexFailureCounter, but that's tuned for routine network noise: 8 occurrences
        // with a growing backoff between them means minutes of a falsely "Connected" status - see
        // point 3 - before it finally gives up).
        FastFailRule(setOf("yandex"), R.string.error_openflux_yandex_wrong_doc) {
            "officeactiondata missing" in it || "editor_config nil" in it || "officeactiondata.balancer_url missing" in it
        },
        // 0c. Doc URL points nowhere valid: yandex.go's fetchDocInfo says "config not found: ...",
        // vyandex.go's says "client-config not found in ...", both cases where the page fetched
        // successfully but wasn't a real Yandex.Docs/Volga document page (deleted, private, wrong
        // link entirely).
        FastFailRule(setOf("yandex", "vyandex"), R.string.error_openflux_doc_not_found) {
            "config not found" in it
        },
        // 0e. Mail.ru's own analogue of 0c: mailru.go's fetchDocInfo wraps ANY non-200 response
        // from cloud.mail.ru/api/v4/r7/edit as "API returned status %d" - most of that range is
        // routine and stays on the counter below (429/5xx are legitimately transient), but 400
        // (malformed weblink) and 404 (deleted/wrong id) are deterministic given this exact link.
        FastFailRule(setOf("mailru"), R.string.error_openflux_doc_not_found) {
            "api returned status 400" in it || "api returned status 404" in it
        }
    )

    // Common tail for every deterministic, non-retriable failure below: sets CoreStatus.Error (skip
    // if a higher-priority Suppressed state is already active), marks the run failed so
    // CoreService's watchdog does NOT retry it, and tells the log-reading loop to stop.
    private fun failFast(message: String, state: BinaryOutputState, ctx: KernelLogContext): Boolean {
        if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
            CoreServiceState.setStatus(CoreStatus.Error(message))
            ctx.updateNotification(ctx.getString(R.string.error_connecting))
        }
        state.startupFailed = true
        return true
    }

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.OpenFlux).config
        return context.getString(displayNameRes) + " " + config.platformDisplayName
    }

    override fun profileSummaryExtra(context: Context, cfg: KernelConfig): List<String> {
        val config = (cfg as KernelConfig.OpenFlux).config
        return listOfNotNull(
            context.getString(R.string.kernel_tag_encrypted).takeIf { config.encryptionKey.isNotBlank() },
            context.getString(R.string.kernel_tag_legacy_codec).takeIf { config.legacyCodec }
        )
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = when ((cfg as KernelConfig.OpenFlux).config.transport) {
        "yandex", "vyandex" -> R.drawable.ic_yandex_docs
        "oneme" -> R.drawable.ic_max
        "cupsonline" -> R.drawable.ic_cupsonline
        "mailru" -> R.drawable.ic_mailru
        else -> R.drawable.route_24px
    }

    override val defaultProfileName: String = "OpenFlux Server"

    override fun decodeUri(uri: String): KernelConfig? =
        OpenFluxConfig.parse(uri)?.let { KernelConfig.OpenFlux(it) }

    override fun displayNameFromUri(uri: String): String? = try {
        val u = uri.toUri()
        // Our own scheme carries the name in a "name" query param; OlConnect's dialect (see
        // OpenFluxConfig.parseOlConnectDialect) puts it in the URL-encoded fragment instead
        // ("+" for spaces, like java.net.URLEncoder), so fall back to that.
        u.getQueryParameter("name") ?: u.encodedFragment?.replace("+", "%20")?.let(android.net.Uri::decode)?.takeIf(String::isNotBlank)
    } catch (_: Exception) { null }

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val cmdArgs = mutableListOf<String>()
        val o = (cfg.kernelConfig as KernelConfig.OpenFlux).config
        cmdArgs.add("${ctx.nativeLibraryDir}/libopenflux.so")
        cmdArgs.addAll(listOf(
            // "--client" still works (kept as a deprecated alias for one release upstream) but is
            // slated for removal in v2 - "--role client" is the flag that replaces it.
            "--role", "client",
            // "--inbound" deliberately left unset: it defaults to socks5 on every non-macOS
            // platform (Android included), which is what cfg.socksAddr below expects.
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
        // The binary's own default codec is "batched" (zstd + coalescing) - both ends of the
        // tunnel must use the same one, it isn't negotiated, so this only gets passed to fall
        // back to the old per-packet-LZ4 behavior for an exit-node that hasn't been updated past
        // the point batching was introduced.
        if (o.legacyCodec) {
            cmdArgs.addAll(listOf("--codec", "legacy"))
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

        // 0a/0b/0c/0e (see FAST_FAIL_RULES above for what each one matches and why). Checked before
        // the generic "failed to start transport" catch-all further below, which would otherwise
        // show the raw log line (doc's office-metadata key dump, HTTP status, ...) as the error.
        for (rule in FAST_FAIL_RULES) {
            if (transport in rule.transports && rule.matcher(lower)) {
                return failFast(ctx.getString(rule.messageRes), state, ctx)
            }
        }

        // 0d. Some hostname failed to resolve - Go's net.DNSError text is always "... lookup
        // <host>: no such host". Deterministic *given a real HTTP round-trip attempt* (retrying
        // won't make a nonexistent hostname start resolving) - but the same text is exactly what a
        // device with no network at all produces too (DNS server unreachable), same as every other
        // network-shaped failure below, so check isNetworkMissingAndHandled() first like they do -
        // otherwise a brief connectivity blip (Wi-Fi/mobile handoff) shows a misleading permanent
        // error instead of the WaitingForNetwork state every sibling check falls back to. Excludes
        // cupsonline: it joins several rooms in parallel and tolerates any single room's own DNS
        // hiccup via its own per-room retry (state.openFluxCupsFailureCounter below already owns
        // that transport's failure handling) - one room's transient "no such host" isn't fatal to
        // the others, so it must not be intercepted here.
        if (transport != "oneme" && transport != "cupsonline" && lower.contains("no such host")) {
            if (ctx.isNetworkMissingAndHandled()) {
                state.startupFailed = true
                return true
            }
            val host = DNS_LOOKUP_HOST_REGEX.find(line)?.groupValues?.get(1) ?: line
            return failFast(ctx.getString(R.string.error_openflux_dns_lookup_failed, host), state, ctx)
        }

        // 1. Hard errors (log.Fatalf in main.go - prints then exits)
        if (lower.startsWith("panic:") ||
            lower.contains("failed to start transport") ||
            lower.contains("unknown transport type")) {
            return failFast(line, state, ctx)
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

        // NOTE: "[BATCH] decode error" (transport/batched.go's BatchedTransport, the --codec
        // batched layer) was tried as a fast-fail signal for a client/exit-node --codec mismatch
        // here, but turned out unreliable specifically for vyandex: that transport has its own,
        // older internal batching (transport/yandex/vyandex.go's own decodeBatch, predating the
        // shared --codec layer) with a fallback that can hand a stray leftover fragment up to the
        // outer BatchedTransport as if it were a whole frame - triggering this same log line on a
        // perfectly healthy, already-transmitting session (observed: real traffic flowing per
        // [VOLGA-STATS] for several seconds, then one single-byte fragment tripped it and killed
        // the tunnel). Since that fallback can produce any of decodeBatch's error variants
        // (not just this one), there's no substring here that's safe to treat as fatal - removed.

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

        // 4c. Mail.ru Docs transport (transport/mailru/mailru.go, --debug required): same shape
        // as classic Yandex above - fetchDocInfo/WebSocket dial/read failures inside connectToDoc()
        // are the only signal, no definite "connected" line of its own beyond "Running as CLIENT"
        // (point 3), and it reconnects forever on its own with backoff - same silent-spin risk.
        if (transport == "mailru" && (
                lower.contains("[m-docs] fetchdocinfo failed") ||
                lower.contains("[m-docs] websocket dial failed") ||
                lower.contains("[m-docs] read error")
            )
        ) {
            if (ctx.isNetworkMissingAndHandled()) {
                state.startupFailed = true
                return true
            }
            if (state.openFluxMailruFailureCounter.recordAndCheckThreshold()) {
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
