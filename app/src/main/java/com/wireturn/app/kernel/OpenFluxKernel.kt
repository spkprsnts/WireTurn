package com.wireturn.app.kernel

import android.content.Context
import androidx.core.net.toUri
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.LogLevel
import com.wireturn.app.LogLevels
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.kernel.OpenFluxConfig
import com.wireturn.app.ui.activities.kernel.OpenFluxConfigActivity
import java.io.File

// OpenFlux (external/openflux, upstream p1neappleXpress/OpenFlux). No captcha flow, but no single
// clean "connected" line either - the six transports it wraps (Yandex.Docs, vyandex/"Volga",
// Yandex Boards, MAX/oneme, cups.online, Mail.ru Docs) differ a lot in how much they actually log,
// so parseLogLine's connected/error detection is split by transport below. log.Fatalf hard failures in main.go also
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

    // --debug is always on (see buildCommand) and parseLogLine depends on its output.
    override fun parsesDebugLine(line: String): Boolean = true

    // utils.EnableDebug gives the std logger Lshortfile ("... 15:04:05.000000 main.go:42: msg"),
    // while utils.Debugf writes through a second logger without it ("... 15:04:05.000000 msg").
    // Upstream reports transport failures through Debugf too, so a debug-shaped line only drops to
    // DEBUG when its text doesn't read as a warning/error.
    override fun logLevel(line: String): LogLevel? {
        val prefix = STD_LOG_PREFIX.find(line) ?: return null
        if (prefix.groups[1] != null) return null
        return LogLevels.fromKeywords(line).takeIf { it != LogLevel.INFO } ?: LogLevel.DEBUG
    }

    // Microseconds only appear once EnableDebug has run - earlier lines (deprecated-flag warnings)
    // don't match and fall back to LogLevels.detect.
    private val STD_LOG_PREFIX = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}\.\d+ (\S+\.go:\d+: )?""")

    // Routine socket recycles: Yandex.Docs, Mail.ru and Boards servers close a healthy WebSocket
    // with 1005 every ~20-60s, and the transport is back within a second or two - its first
    // reconnect after a healthy session is always "(attempt 0)", later ones mean real failures.
    // parseLogLine still gets these; they just don't read as errors in the log.
    override fun isNoise(line: String): Boolean =
        ROUTINE_RECYCLE.containsMatchIn(line) || ROUTINE_RECONNECT.containsMatchIn(line)

    private val ROUTINE_RECYCLE =
        Regex("""\[(?:YDOCS] Read error|M-DOCS] Read error|BOARDS] ws error): (?:read: )?websocket: close 1005""")
    private val ROUTINE_RECONNECT = Regex("""\[(?:YDOCS|M-DOCS)] reconnecting in \S+ \(attempt 0\)""")

    // Go's net.DNSError always renders as "... lookup <host>: no such host" - pulls the host back
    // out for error_openflux_dns_lookup_failed (see parseLogLine point 0d) instead of asserting
    // it's the document URL, since a device-level DNS interceptor can be what's actually failing.
    private val DNS_LOOKUP_HOST_REGEX = Regex("lookup ([^:]+): no such host")

    // Lines from the document transports themselves (their own log prefixes, the shared Yandex
    // captcha solver, and main.go's fatal for a synchronous Start() failure) - as opposed to the
    // SOCKS5 inbound's per-connection lines, see parseLogLine point 0d.
    private val TRANSPORT_LOG_MARKERS = listOf(
        "[ydocs]", "[volga]", "[boards]", "[m-docs]", "[captcha]", "failed to start transport"
    )

    // cupsonline.go's reportRooms: "[CUPS] Cups: живых комнат <alive>/<total>".
    private val CUPS_ALIVE_ROOMS_REGEX = Regex("""живых комнат (\d+)/(\d+)""")

    // Same "healthy session" cutoff yandex.go uses before resetting its own reconnect backoff.
    private const val YANDEX_HEALTHY_SESSION_MS = 15_000L

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
        // fetchDocInfo) pointed at a Volga-only document. All six of these are read from the same
        // already-fetched, already-parsed client-config page - structural for this doc (Yandex
        // migrated it off the legacy editor to only_office/volga, see openflux#10/#31/#86), not a
        // transient fetch hiccup - despite each one's own "will reconnect" wording (point 4 below
        // still retries these forever via openFluxYandexFailureCounter, but that's tuned for
        // routine network noise: 8 occurrences with a growing backoff between them means minutes
        // of a falsely "Connected" status - see point 3 - before it finally gives up).
        FastFailRule(setOf("yandex"), R.string.error_openflux_yandex_wrong_doc) {
            "officeactiondata missing" in it || "editor_config nil" in it ||
                "officeactiondata.balancer_url missing" in it || "editor_config.document missing" in it ||
                "editor_config.token missing" in it || "editor_config.document.key missing" in it
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
        },
        // 0f. Yandex answered with a SmartCaptcha (unlike the PoW showcaptchafast one, which the
        // transport solves itself) or a passport.yandex login redirect (document not open to
        // anonymous access). Since 0.0.5 only a negotiated session can hand these to the app over
        // --ipc-socket; in plain single-transport mode yandex.go just re-fetches every 30s
        // forever ("fetchDocInfo needs external help: ...") and vyandex.go fails Start()
        // ("Failed to start transport: auth: ..."). Both carry the sentinel error's own text.
        FastFailRule(setOf("yandex", "vyandex"), R.string.error_openflux_yandex_captcha) {
            "yandex docs: captcha required" in it
        },
        FastFailRule(setOf("yandex", "vyandex"), R.string.error_openflux_yandex_login) {
            "yandex docs: login required" in it
        },
        // 0g. Boards' own take on 0f: boards.go only knows the PoW captcha, so anything its solver
        // can't get through (a SmartCaptcha behind it) fails Start() as "boards auth: captcha
        // solve: ...".
        FastFailRule(setOf("boards"), R.string.error_openflux_yandex_captcha) {
            "boards auth: captcha solve" in it
        },
        // 0h. Every room in the cupsonline room list answered as closed (cupsonline.go's
        // enterRooms: "no rooms joined: all rooms are gone") - only a fresh list from the exit
        // node helps. Rooms that merely didn't answer are point 6's retry instead.
        FastFailRule(setOf("cupsonline"), R.string.error_openflux_cups_rooms_gone) {
            "all rooms are gone" in it
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
        "boards" -> R.drawable.ic_yandex_boards
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

        // 0a/0b/0c/0e/0f (see FAST_FAIL_RULES above for what each one matches and why). Checked before
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
        // hiccup via its own per-room retry (point 6 below owns that transport's failure
        // handling, by how many rooms are still alive) - one room's transient "no such host" isn't fatal to
        // the others, so it must not be intercepted here. Only the transport's own lookups count
        // (TRANSPORT_LOG_MARKERS): every app connection through the SOCKS5 inbound logs the same
        // text for its own dead domain ("[SOCKS5] Dial failed: resolve: lookup <host>: no such
        // host" - e.g. a ColorOS system app probing a nonexistent host, WireTurn#35), which says
        // nothing about the tunnel.
        if (transport != "oneme" && transport != "cupsonline" && lower.contains("no such host") &&
            TRANSPORT_LOG_MARKERS.any { lower.contains(it) }
        ) {
            if (ctx.isNetworkMissingAndHandled()) {
                state.startupFailed = true
                return true
            }
            val host = DNS_LOOKUP_HOST_REGEX.find(line)?.groupValues?.get(1) ?: line
            return failFast(ctx.getString(R.string.error_openflux_dns_lookup_failed, host), state, ctx)
        }

        // 6a. cupsonline couldn't enter any room, but not every one is closed (0h) - a network or
        // cups.online hiccup, so the watchdog retries instead of point 1 failing for good.
        if (transport == "cupsonline" && lower.contains("failed to start transport") && lower.contains("no rooms joined")) {
            if (ctx.isNetworkMissingAndHandled()) {
                state.startupFailed = true
                return true
            }
            ctx.setLastFailureReason(ctx.getString(R.string.error_openflux_cups_rooms_unreachable))
            state.startupEmitted = true
            return true
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
        // boards is like cupsonline in that its own definite signal (point 7) can already have
        // arrived - its WebSocket connects in the background right after the synchronous auth in
        // Start() - so it only moves to Connecting here if it isn't Connected yet.
        if (lower.contains("running as client (socks5 on")) {
            state.startupEmitted = true
            if (transport == "boards") {
                if (CoreServiceState.status.value !is CoreStatus.Connected && canUpdateConnectingStatus()) {
                    markConnecting()
                }
            } else if (transport != "oneme" && transport != "cupsonline" && CoreServiceState.status.value !is CoreStatus.Suppressed) {
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
        if (transport != "oneme" && lower.contains("[ydocs] websocket connected")) {
            state.yandexConnectedAt = System.currentTimeMillis()
        }

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
            // The server recycles a healthy session's WebSocket every ~65-85s ("close 1005") and
            // connectToDoc() reconnects within seconds. Those gaps are shorter than the counter's
            // window, so counting them would never let it reset and the Nth routine recycle would
            // wrongly kill a working tunnel. yandex.go itself treats a session that lasted more
            // than 15s as healthy (attempt reset) - do the same: only a connection that dropped
            // quickly, or a failed fetch/dial, counts as a failure.
            val healthySessionEnded = lower.contains("[ydocs] read error") &&
                state.yandexConnectedAt != 0L &&
                System.currentTimeMillis() - state.yandexConnectedAt > YANDEX_HEALTHY_SESSION_MS
            if (healthySessionEnded) {
                state.openFluxYandexFailureCounter.reset()
            } else if (state.openFluxYandexFailureCounter.recordAndCheckThreshold()) {
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
        // joins happen synchronously inside Start() - a total failure there is 0h/6a above.
        // "[CUPS] transport started" is the definite success signal (at least one room entered),
        // taking over from "Running as CLIENT" at point 3, which this transport is excluded from.
        // Each room's WebSocket then reconnects on its own; after 5 failed connects in a row (or
        // the room page saying so) the room counts as closed and is only re-probed every 1-30 min.
        // Every such change logs "живых комнат N/M" - traffic keeps flowing while N > 0, so that's
        // the status. At 0 it goes to Connecting rather than an error: 5 failed connects can also
        // be a cups.online outage, and the watchdog's restart re-enters the rooms, where 0h/6a
        // tell a closed list from an unreachable one. A plain "[CUPS] ws error" is no signal of
        // its own any more - a single dying room logs 5 of them in seconds while the rest work.
        if (transport == "cupsonline") {
            val rooms = CUPS_ALIVE_ROOMS_REGEX.find(line)
            if (lower.contains("[cups] transport started")) {
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Connected)
                    ctx.updateNotification(ctx.getString(R.string.core_active))
                }
                state.startupEmitted = true
            } else if (rooms != null) {
                if (rooms.groupValues[1] != "0") {
                    if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                        CoreServiceState.setStatus(CoreStatus.Connected)
                        ctx.updateNotification(ctx.getString(R.string.core_active))
                    }
                } else {
                    if (ctx.isNetworkMissingAndHandled()) {
                        state.startupFailed = true
                        return true
                    }
                    ctx.setLastFailureReason(ctx.getString(R.string.error_openflux_cups_rooms_unreachable))
                    if (canUpdateConnectingStatus()) markConnecting()
                }
            } else if (lower.contains("[cups] ws error")) {
                if (ctx.isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                }
            }
        }

        // 7. Yandex Boards transport (transport/yandex/boards.go, --debug required). Auth runs
        // synchronously in Start() (failures trip point 1 / 0g via main.go's "Failed to start
        // transport"), then the WebSocket connects in the background: "[BOARDS] handshake done"
        // is the definite ready signal, and "[BOARDS] ws error" is its reconnect loop's only
        // failure signal, retried forever with backoff. Yandex recycles a healthy board socket
        // every ~20-30s ("close 1005") and it's back within a second or two, so a session that
        // lasted past the same 15s "healthy" cutoff as point 4 is a routine recycle: the counter
        // resets and the status is left alone (showing Connecting for each one just made it flap
        // twice a minute). Only a drop that came quickly, or a dial that never got a session up,
        // counts towards the failure threshold and shows as Connecting until the next handshake.
        if (transport == "boards") {
            if (lower.contains("[boards] handshake done")) {
                state.boardsConnectedAt = System.currentTimeMillis()
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Connected)
                    ctx.updateNotification(ctx.getString(R.string.core_active))
                }
                state.startupEmitted = true
            } else if (lower.contains("[boards] ws error")) {
                if (ctx.isNetworkMissingAndHandled()) {
                    state.startupFailed = true
                    return true
                }
                val healthySessionEnded = state.boardsConnectedAt != 0L &&
                    System.currentTimeMillis() - state.boardsConnectedAt > YANDEX_HEALTHY_SESSION_MS
                state.boardsConnectedAt = 0L
                if (healthySessionEnded) {
                    state.openFluxBoardsFailureCounter.reset()
                } else {
                    if (state.openFluxBoardsFailureCounter.recordAndCheckThreshold()) {
                        CoreServiceState.setStatus(CoreStatus.Error(line))
                        ctx.updateNotification(ctx.getString(R.string.error_connecting))
                        state.startupFailed = true
                        return true
                    }
                    if (CoreServiceState.status.value is CoreStatus.Connected) {
                        markConnecting()
                    }
                }
                state.startupEmitted = true
            }
        }

        return false
    }
}
