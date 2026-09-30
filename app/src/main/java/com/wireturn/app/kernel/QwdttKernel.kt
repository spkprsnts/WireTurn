package com.wireturn.app.kernel

import android.content.Context
import androidx.core.net.toUri
import com.wireturn.app.CaptchaSession
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.kernel.QwdttConfig
import com.wireturn.app.ui.activities.kernel.QwdttConfigActivity
import java.util.regex.Pattern

// qWDTT (external/proxy-turn-vk-android/go_client): "-mode socks", or "-mode rawtun" taking the
// VPN's TUN itself when the profile has the server's raw port (supportsNativeTun). Its vocabulary
// is Russian and unrelated to free-turn-proxy's despite the shared VK-TURN idea.
object QwdttKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.QWDTT
    override val sensitiveCommandFlags: Set<String> = setOf("-vk", "-password", "-socks-user", "-socks-pass")
    override val displayNameRes: Int = R.string.kernel_qwdtt
    override val configActivityClass = QwdttConfigActivity::class.java
    override val wgNotUsedMessageRes: Int = R.string.wg_not_used_with_qwdtt

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.Qwdtt).config
        return context.getString(displayNameRes) + " " + config.addressLabel()
    }

    override fun profileSummaryExtra(context: Context, cfg: KernelConfig): List<String> {
        val config = (cfg as KernelConfig.Qwdtt).config
        val callCount = config.vkHashes.split(",").count { it.isNotBlank() }
        return listOfNotNull(
            context.getString(R.string.kernel_tag_video_obfs).takeIf { config.obfsMode == "video" },
            context.getString(R.string.kernel_tag_no_tls).takeIf { config.noTls },
            context.getString(R.string.kernel_tag_turn_tcp).takeIf { config.turnTcp },
            context.getString(R.string.kernel_tag_manual_captcha).takeIf { config.manualCaptcha },
            context.getString(R.string.kernel_tag_call_count, callCount).takeIf { callCount > 1 }
        )
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = R.drawable.ic_vk

    override val defaultProfileName: String = "qWDTT Server"

    override fun decodeUri(uri: String): KernelConfig? =
        QwdttConfig.parse(uri)?.let { KernelConfig.Qwdtt(it) }

    override fun displayNameFromUri(uri: String): String? = try {
        // Mirror QwdttConfig.parse's own normalization - the schemeless "qwdtt:config?..." form
        // has no "//", so Uri treats it as opaque and getQueryParameter() throws on it. The
        // "wdtt://..." forms already have "//" and don't start with "qwdtt:", so they pass
        // through unchanged (neither carries a "name" param, same as before this fix).
        val normalized = if (uri.startsWith("qwdtt://", ignoreCase = true)) uri
            else uri.replaceFirst("qwdtt:", "qwdtt://", ignoreCase = true)
        normalized.toUri().getQueryParameter("name")
    } catch (_: Exception) { null }

    // "-mode rawtun": raw IP packets straight through the tunnel, no WireGuard or SOCKS5 - on the
    // server's separate -listen-raw port, so only when the profile has one (QwdttConfig.rawPort).
    override fun supportsNativeTun(cfg: KernelConfig): Boolean =
        (cfg as? KernelConfig.Qwdtt)?.config?.rawPeer() != null

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val cmdArgs = mutableListOf<String>()
        val o = (cfg.kernelConfig as KernelConfig.Qwdtt).config
        cmdArgs.add("${ctx.nativeLibraryDir}/libqwdtt.so")
        val tunSocket = ctx.nativeTunSocket
        val rawPeer = if (tunSocket != null) o.rawPeer() else null
        val rawMode = tunSocket != null && rawPeer != null
        if (tunSocket != null && rawPeer != null) {
            // Gets RAWCONF (address/DNS/MTU) from the server, prints it, then waits on this
            // abstract socket ("@" = abstract namespace for Go's net.ListenUnix) for the TUN.
            cmdArgs.addAll(listOf("-mode", "rawtun", "-peer", rawPeer, "-tun-fd-sock", "@$tunSocket"))
        } else {
            cmdArgs.addAll(listOf(
                "-mode", "socks",
                "-socks", cfg.socksAddr.ifBlank { ClientConfig.DEFAULT_SOCKS_ADDR },
                "-peer", o.peer
            ))
        }
        cmdArgs.addAll(listOf(
            "-vk", o.vkHashes,
            "-password", o.password,
            "-n", o.workers.toString(),
            "-listen", cfg.listenAddr.ifBlank { ClientConfig.DEFAULT_LISTEN_ADDR },
            "-obfs", o.obfsMode
        ))
        if (o.turnTcp) cmdArgs.add("-turn-tcp")
        if (o.noTls) cmdArgs.add("-notls")
        if (o.manualCaptcha) cmdArgs.addAll(listOf("-captcha-mode", "wv"))
        if (o.goDns.isNotBlank() && o.goDns != "yandex") cmdArgs.addAll(listOf("-go-dns", o.goDns))
        if (!rawMode && cfg.isSocksAuthEnabled) {
            cmdArgs.add("-socks-auth")
            cmdArgs.addAll(listOf("-socks-user", cfg.socksUser))
            cmdArgs.addAll(listOf("-socks-pass", cfg.socksPass))
        }
        return cmdArgs
    }

    // go_client has no debug level: its own "[ДЕБАГ]"-tagged lines, periodic stats (still parsed
    // for "Активных:", see below), Raw TUN timing diagnostics, and the per-stream/per-worker steps
    // of every (re)connect - VK Calls auth steps, the TURN URL dump, cached-creds reuse, worker
    // registration and TURN transport choice.
    override fun isNoise(line: String): Boolean =
        QWDTT_NOISE_MARKERS.any { line.contains(it) } ||
            (line.contains("[VKCalls] step") && line.contains(" OK,"))

    private val QWDTT_NOISE_MARKERS = listOf(
        "[ДЕБАГ]",
        "[СТАТИСТИКА]",
        "[RAW-DIAG ",
        "[VK Auth] TURN urls (",
        "[VK Auth]   [",
        "[VK Auth] Using cached credentials",
        "[VK Auth] Throttling",
        "[ДИСП] Воркер #",
        "] TURN UDP (",
        "] TURN TCP ("
    )

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        // Benign SOCKS5 IPv6 routing noise (the official qWDTT client filters the same thing) -
        // not an error, don't touch the status.
        if (lower.contains("socks") && (lower.contains("blocked by rules") || lower.contains("ipv6"))) {
            return false
        }

        // 0. "-mode rawtun" (supportsNativeTun): the server's RAWCONF, printed as a box -
        //   ╔══════════════ RAW Конфиг ══════════════╗
        //   ║ IP = 10.66.0.2                          ║   (the tunnel address, /32)
        //   ║ DNS = 1.1.1.1,8.8.8.8                   ║
        //   ║ MTU = 1280                              ║
        //   ╚══════════════════════════════════════╝
        // after which the binary waits on its -tun-fd-sock for the TUN built from these.
        if (line.contains("RAW Конфиг")) {
            state.rawConfigOpen = true
            state.rawConfigIp = null
            state.rawConfigDns = null
            state.rawConfigMtu = null
            return false
        }
        if (state.rawConfigOpen) {
            RAW_CONFIG_FIELD.find(line)?.let { m ->
                when (m.groupValues[1]) {
                    "IP" -> state.rawConfigIp = m.groupValues[2]
                    "DNS" -> state.rawConfigDns = m.groupValues[2]
                    "MTU" -> state.rawConfigMtu = m.groupValues[2].toIntOrNull()
                }
            }
            if (line.contains("╚")) {
                state.rawConfigOpen = false
                val ip = state.rawConfigIp
                val mtu = state.rawConfigMtu
                if (ip != null && mtu != null) {
                    val dns = state.rawConfigDns.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
                    ctx.onNativeTunConfig(ip, dns, mtu)
                    if (canUpdateConnectingStatus()) markConnecting()
                    state.startupEmitted = true
                }
            }
            return false
        }
        // The TUN reached the binary and traffic flows - rawtun's own "connected", in place of
        // "[SOCKS] listening", which that mode never prints.
        if (lower.contains("[raw] tun подключён")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
            }
            state.startupEmitted = true
            return false
        }

        // 1. Hard errors ("[RAW] Ошибка"/"Некорректный RAWCONF": rawtun got no usable config or
        // socket - a restart asks for the same thing again)
        if (lower.startsWith("panic") || lower.contains("fatal_auth") ||
            lower.contains("нужны -peer и -vk") || lower.contains("нужен -password") ||
            lower.contains("[raw] ошибка") || lower.contains("некорректный rawconf")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        // 2. Captcha - the binary prints "CAPTCHA_SOLVE|mode|redirectURI|sessionToken" and waits
        // for a token back over stdin ("CAPTCHA_RESULT|<token>"). mode=auto is a first, ~10s
        // attempt the binary makes on its own automated chain - not enough time for a human to
        // react, and it has its own internal fallbacks, so we only step in for mode=manual (the
        // final fallback once that whole chain is exhausted) or mode=selected (the binary's own
        // automatic solving is disabled entirely - QwdttConfig.manualCaptcha / "-captcha-mode wv").
        // Either way the binary times out and retries on its own if we never respond, so silently
        // ignoring "auto" here is safe.
        if (line.startsWith("CAPTCHA_SOLVE|")) {
            val parts = line.removePrefix("CAPTCHA_SOLVE|").split("|", limit = 3)
            if (parts.size == 3 && (parts[0].equals("manual", ignoreCase = true) || parts[0].equals("selected", ignoreCase = true))) {
                val redirectUri = parts[1]
                if (redirectUri.isNotBlank() && CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    state.captchaSessionCounter += 1
                    ctx.setPendingCaptchaSessionId(state.captchaSessionCounter)
                    // Worker groups are spread over every -vk hash (go_client/group.go), so the
                    // others may well be carrying the tunnel meanwhile - see requestCaptcha.
                    requestCaptcha(ctx, CaptchaSession(
                        redirectUri, state.captchaSessionCounter,
                        needsResultToken = true, partial = state.activeWorkers > 0
                    ))
                }
            }
            return false
        }
        // The binary stopped waiting on its own (webview timeout, go_client/creds.go) - closing the
        // dialog no longer cancels it, so the pending session would otherwise outlive it.
        if (lower.contains("[captcha] solve failed")) clearStaleCaptcha(ctx)

        // The periodic stats line's totals, every 3s - in rawtun the bytes through the TUN itself,
        // which is what hev would otherwise have counted for the VPN.
        QWDTT_TRAFFIC_REGEX.find(line)?.let { m ->
            val down = m.groupValues[1].toDoubleOrNull()
            val up = m.groupValues[2].toDoubleOrNull()
            if (down != null && up != null) ctx.onNativeTunTraffic(mbToBytes(down), mbToBytes(up))
        }

        // 3. Connected - "[SOCKS] listening" is the definitive signal; the periodic stats line's
        // "Активных: N" (N>0) is a fallback in case that line scrolled past unseen.
        val activeMatch = QWDTT_ACTIVE_REGEX.matcher(line)
        val statsLine = activeMatch.find()
        if (statsLine) {
            state.activeWorkers = activeMatch.group(1)?.toIntOrNull() ?: 0
            syncCaptchaWithTunnel(ctx, alive = state.activeWorkers > 0)
        }
        if (lower.contains("[socks] listening") || (statsLine && state.activeWorkers > 0)) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
                state.startupEmitted = true
            }
        }

        // 4. Connecting / progress - without this the binary never leaves CoreStatus.Starting on
        // its own and can exceed CoreManager's startup timeout. "relay:"/"[dtls] соединение
        // установлено" print once per worker (up to -n of them, staggered over ~1s) - guard on
        // "not already Connected" so a later worker's turn doesn't flap the status back down
        // once "[SOCKS] listening"/active-count already declared the tunnel up.
        if (CoreServiceState.status.value !is CoreStatus.Connected && (
                lower.contains("креды ok") || lower.contains("[wrap]") ||
                (lower.contains("[turn]") && !lower.contains("ошибка") && !lower.contains("не удалось") && !lower.contains("неполный ответ")) ||
                lower.contains("relay:") || lower.contains("[прямой]") ||
                lower.contains("[dtls] соединение установлено")
            )
        ) {
            if (canUpdateConnectingStatus()) {
                markConnecting()
                state.startupEmitted = true
            }
        }

        return false
    }

    // go_client's periodic "[СТАТИСТИКА] Активных: N | ..." line - a fallback Connected signal
    // for when "[SOCKS] listening" was missed (e.g. log ring buffer already rotated past it).
    private val QWDTT_ACTIVE_REGEX = Pattern.compile("""Активных:\s*(\d+)""")

    // "... | ↓12.34 МБ / ↑1.23 МБ" - go_client's stats.go, %.2f of MiB.
    private val QWDTT_TRAFFIC_REGEX = Regex("""↓([\d.]+) МБ / ↑([\d.]+) МБ""")

    private fun mbToBytes(mb: Double): Long = (mb * 1024 * 1024).toLong()

    // One field line of the "RAW Конфиг" box: "║ IP = 10.66.0.2      ║".
    private val RAW_CONFIG_FIELD = Regex("""\b(IP|DNS|MTU) = (\S+)""")
}
