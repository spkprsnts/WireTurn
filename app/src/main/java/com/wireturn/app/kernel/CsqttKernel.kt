package com.wireturn.app.kernel

import android.content.Context
import androidx.core.net.toUri
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireturn.app.CaptchaSession
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.LogLevel
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.kernel.CsqttConfig
import com.wireturn.app.ui.activities.kernel.CsqttConfigActivity

// CSQTT (external/csqtt/rust-client): raw IP over VK TURN, and nothing but a TUN on the client
// side (-tun-uds: it listens on an abstract socket for the TUN's descriptor). In VPN mode without
// Xray that's the VPN's own TUN (supportsNativeTun, always); otherwise socks2tun (external/
// socks2tun) runs as the sidecar, hands it the other end of a packet socketpair instead and serves
// SOCKS5 on socksAddr, like any other SOCKS5-native kernel. Russian log vocabulary; CSQTT_EVENTS
// adds machine-readable "__CSQTT_EVENT__|KIND|{json}" lines (rust-client/events.rs), which carry
// the status signals here.
object CsqttKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.CSQTT
    override val sensitiveCommandFlags: Set<String> = setOf("-vk", "-password", "-device-id", "--user", "--pass")
    override val displayNameRes: Int = R.string.kernel_csqtt
    override val configActivityClass = CsqttConfigActivity::class.java
    override val wgNotUsedMessageRes: Int = R.string.wg_not_used_with_csqtt

    // Its TUN MTU isn't negotiated - this is what the official app builds its VPN with.
    private const val TUN_MTU = 1300
    // The official app's defaults - VK's own web client IDs and TLS fingerprint.
    private const val CLIENT_IDS = "8202606,6287487"
    private const val FINGERPRINT = "firefox"

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.Csqtt).config
        return context.getString(displayNameRes) + " " + config.addressLabel()
    }

    override fun profileSummaryExtra(context: Context, cfg: KernelConfig): List<String> {
        val config = (cfg as KernelConfig.Csqtt).config
        val callCount = config.hashList().size
        return listOfNotNull(
            context.getString(R.string.kernel_tag_video_obfs).takeIf { config.obfsMode == "video" },
            context.getString(R.string.kernel_tag_turn_tcp).takeIf { config.turnTcp },
            context.getString(R.string.kernel_tag_manual_captcha).takeIf { config.manualCaptcha },
            context.getString(R.string.kernel_tag_call_count, callCount).takeIf { callCount > 1 }
        )
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = R.drawable.ic_vk

    override val defaultProfileName: String = "CSQTT Server"

    override fun decodeUri(uri: String): KernelConfig? =
        CsqttConfig.parse(uri)?.let { KernelConfig.Csqtt(it) }

    override fun displayNameFromUri(uri: String): String? = try {
        uri.toUri().getQueryParameter("name")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    // Raw IP is all it speaks - any server takes the VPN's TUN directly.
    override fun supportsNativeTun(cfg: KernelConfig): Boolean = cfg is KernelConfig.Csqtt

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val o = (cfg.kernelConfig as KernelConfig.Csqtt).config
        return buildList {
            add("${ctx.nativeLibraryDir}/libcsqtt.so")
            addAll(listOf("-peer", o.peer, "-password", o.password))
            addAll(listOf("-vk", o.hashList().joinToString(","), "-n", o.normalizedWorkers().toString()))
            // The VPN's TUN, or socks2tun's end of the socketpair - see buildSidecarCommand.
            addAll(listOf("-tun-uds", ctx.nativeTunSocket ?: ctx.runSocket))
            addAll(listOf("-obfs", o.obfsMode, "-turn-transport", if (o.turnTcp) "tcp_tls" else "udp"))
            addAll(listOf("-fingerprint", FINGERPRINT, "-client-ids", CLIENT_IDS))
            if (o.manualCaptcha) addAll(listOf("-captcha-mode", "wv"))
            addAll(listOf("-device-id", ctx.deviceId))
            // A new session epoch per run: the server drops the device's previous session for it
            // (rust-server's DeviceEpoch) instead of waiting for it to time out.
            addAll(listOf("-gen", System.currentTimeMillis().toString(), "-salt", newSalt()))
        }
    }

    // Events on; also makes it exit once its stdin closes, i.e. when this app's process is gone.
    override fun environment(cfg: ClientConfig): Map<String, String> =
        mapOf("CSQTT_EVENTS" to "1", "RAYON_NUM_THREADS" to "2")

    // Only when the kernel isn't taking the VPN's TUN itself.
    override fun buildSidecarCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String>? {
        if (ctx.nativeTunSocket != null) return null
        return buildList {
            add("${ctx.nativeLibraryDir}/libsocks2tun.so")
            addAll(listOf("--tun-uds", ctx.runSocket))
            addAll(listOf("--listen", cfg.socksAddr.ifBlank { ClientConfig.DEFAULT_SOCKS_ADDR }))
            addAll(listOf("--mtu", TUN_MTU.toString()))
            // Waits for the kernel as long as this run lasts - both are stopped together anyway.
            addAll(listOf("--uds-timeout", "0"))
            if (cfg.isSocksAuthEnabled) addAll(listOf("--user", cfg.socksUser, "--pass", cfg.socksPass))
        }
    }

    private fun newSalt(): String {
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun logLevel(line: String): LogLevel? = when {
        line.startsWith(EVENT_PREFIX) -> LogLevel.DEBUG
        line.startsWith("[S2T] debug:") -> LogLevel.DEBUG
        else -> null
    }

    // The event lines are parsed below, only shown at debug level.
    override fun parsesDebugLine(line: String): Boolean = line.startsWith(EVENT_PREFIX)

    override fun isNoise(line: String): Boolean = CSQTT_NOISE_MARKERS.any { line.contains(it) }

    private val CSQTT_NOISE_MARKERS = listOf(
        "[СТАТИСТИКА]",
        "[STDIN] ",
        // The server's one-byte 0xFF keepalive, now filtered out before the TUN write - expected,
        // not an error despite being logged as one.
        "Отброшен не-IP пакет"
    )

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        if (line.startsWith(EVENT_PREFIX)) return handleEvent(line.removePrefix(EVENT_PREFIX), state, ctx)

        // 1. The tunnel's own address, once the server hands out its config (again whenever it
        // changes): "[КЛИЕНТ] Tunnel IP: 10.66.66.2/32 | DNS: 1.1.1.1"
        TUNNEL_IP_REGEX.find(line)?.let { m ->
            val ip = m.groupValues[1]
            val dns = m.groupValues[2].split(',').map(String::trim).filter(String::isNotEmpty)
            ctx.onNativeTunConfig(ip, dns, TUN_MTU)
            ctx.writeToSidecar("ip $ip dns ${dns.joinToString(",").ifEmpty { "none" }}")
            return false
        }

        // 2. Hard errors - a wrong password, an expired one, one bound to another device, a
        // server on another protocol version: a restart won't change any of these.
        if (line.contains("FATAL_AUTH") || line.contains("FATAL_PROTOCOL")) {
            val reason = readableFailure(ctx, line)
            ctx.setLastFailureReason(reason)
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(reason))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        // 3. Captcha - the same stdout/stdin protocol as qWDTT's go_client (see QwdttKernel):
        // "CAPTCHA_SOLVE|mode|redirectURI|sessionToken" out, "CAPTCHA_RESULT|<token>" back. Only
        // manual/selected are ours; "auto" is its own ~10s attempt with fallbacks of its own.
        if (line.startsWith("CAPTCHA_SOLVE|")) {
            val parts = line.removePrefix("CAPTCHA_SOLVE|").split("|", limit = 3)
            if (parts.size == 3 && (parts[0].equals("manual", ignoreCase = true) || parts[0].equals("selected", ignoreCase = true))) {
                val redirectUri = parts[1]
                if (redirectUri.isNotBlank() && CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    state.captchaSessionCounter += 1
                    ctx.setPendingCaptchaSessionId(state.captchaSessionCounter)
                    // Worker groups are spread over every -vk hash (worker.rs run_groups) - see
                    // requestCaptcha.
                    requestCaptcha(ctx, CaptchaSession(
                        redirectUri, state.captchaSessionCounter,
                        needsResultToken = true, partial = state.activeWorkers > 0
                    ))
                }
            }
            return false
        }
        // The binary stopped waiting on its own - "[VK Auth] Failed with client_id=...:
        // CAPTCHA_WAIT_REQUIRED: <webview timeout / manual fallback failed>" (auth.rs).
        if (lower.contains("captcha_wait_required: webview captcha") ||
            lower.contains("captcha_wait_required: automatic captcha chain failed")
        ) clearStaleCaptcha(ctx)
        return false
    }

    private fun handleEvent(event: String, state: BinaryOutputState, ctx: KernelLogContext): Boolean {
        val kind = event.substringBefore('|')
        val payload = try {
            JsonParser.parseString(event.substringAfter('|', "{}")).asJsonObject
        } catch (_: Exception) { null }
        when (kind) {
            // A worker got through the handshake - traffic can flow.
            "READY" -> {
                state.activeWorkers = state.activeWorkers.coerceAtLeast(1)
                syncCaptchaWithTunnel(ctx, alive = true)
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Connected)
                    ctx.updateNotification(ctx.getString(R.string.core_active))
                }
                state.startupEmitted = true
            }
            // Getting VK credentials / (re)connecting workers.
            "PROGRESS", "ACTIVE_ZERO" -> {
                if (kind == "ACTIVE_ZERO") {
                    state.activeWorkers = 0
                    syncCaptchaWithTunnel(ctx, alive = false)
                }
                if (CoreServiceState.status.value !is CoreStatus.Connected || kind == "ACTIVE_ZERO") {
                    if (canUpdateConnectingStatus()) markConnecting()
                }
                state.startupEmitted = true
            }
            // Exact byte counters through its TUN - see onNativeTunTraffic.
            "STATS" -> {
                val up = payload?.longOrNull("bytes_up")
                val down = payload?.longOrNull("bytes_down")
                if (up != null && down != null) ctx.onNativeTunTraffic(down, up)
                payload?.longOrNull("active")?.let {
                    state.activeWorkers = it.toInt()
                    syncCaptchaWithTunnel(ctx, alive = it > 0)
                }
            }
            // "Fatal" to the official app, which then stops for good. A rejection is (a password/
            // protocol problem, also logged as FATAL_AUTH/FATAL_PROTOCOL, point 2 above). An
            // unanswered handshake is mostly a wrong password too - the whole exchange is wrapped
            // with a key derived from it, so the server can't even read a wrong one's request and
            // never answers - but it may also be the server being down: once goes to the watchdog,
            // again right after that (this run is already a watchdog retry) is the end.
            "ERROR" -> {
                if (payload?.booleanOrNull("fatal") != true) return false
                val message = readableFailure(ctx, payload.stringOrNull("message").orEmpty())
                ctx.setLastFailureReason(message)
                state.startupEmitted = true
                val code = payload.stringOrNull("code")
                val isRetry = CoreServiceState.restartAttempt.value != null
                if (code == "handshake_rejected" || (code == "handshake_timeout" && isRetry)) {
                    if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                        CoreServiceState.setStatus(CoreStatus.Error(message))
                        ctx.updateNotification(ctx.getString(R.string.error_connecting))
                    }
                    state.startupFailed = true
                }
                return true
            }
        }
        return false
    }

    // The client's own Russian text after its "FATAL_AUTH:"-style tag (rust-client/protocol.rs) -
    // except a password bound to another device, which gets what to do about it: this app's
    // ANDROID_ID differs from the official app's, even on the same phone.
    private fun readableFailure(ctx: KernelLogContext, text: String): String {
        if (text.contains("другому устройству")) return ctx.getString(R.string.error_csqtt_device_mismatch)
        val reason = FATAL_TAG.find(text)?.let { text.substring(it.range.last + 1).trim() } ?: text.trim()
        return reason.replaceFirstChar { it.uppercase() }
    }

    private val FATAL_TAG = Regex("""FATAL_[A-Z]+:""")

    // Gson's asLong/asBoolean/asString throw on a null or a value of another type - an event
    // shaped differently than expected (a newer client) must not end the run from parseLogLine.
    private fun <T> JsonObject.primitiveOrNull(key: String, read: (JsonElement) -> T): T? = try {
        get(key)?.takeIf { it.isJsonPrimitive }?.let(read)
    } catch (_: Exception) { null }

    private fun JsonObject.longOrNull(key: String): Long? = primitiveOrNull(key) { it.asLong }
    private fun JsonObject.booleanOrNull(key: String): Boolean? = primitiveOrNull(key) { it.asBoolean }
    private fun JsonObject.stringOrNull(key: String): String? = primitiveOrNull(key) { it.asString }

    private const val EVENT_PREFIX = "__CSQTT_EVENT__|"

    private val TUNNEL_IP_REGEX = Regex("""Tunnel IP:\s*([0-9.]+)(?:/\d+)?\s*\|\s*DNS:\s*([0-9.,\s]*)""")
}
