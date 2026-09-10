package com.wireturn.app.kernel

import com.wireturn.app.CaptchaSession
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import java.util.regex.Pattern

// qWDTT (external/proxy-turn-vk-android/go_client, -mode socks only - see docs). Its vocabulary
// is Russian and unrelated to free-turn-proxy's despite the shared VK-TURN idea.
object QwdttKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.QWDTT
    override val sensitiveCommandFlags: Set<String> = setOf("-vk", "-password", "-socks-user", "-socks-pass")

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val cmdArgs = mutableListOf<String>()
        val o = (cfg.kernelConfig as KernelConfig.Qwdtt).config
        cmdArgs.add("${ctx.nativeLibraryDir}/libqwdtt.so")
        cmdArgs.addAll(listOf(
            "-mode", "socks",
            "-socks", cfg.socksAddr.ifBlank { ClientConfig.DEFAULT_SOCKS_ADDR },
            "-peer", o.peer,
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
        if (cfg.isSocksAuthEnabled) {
            cmdArgs.add("-socks-auth")
            cmdArgs.addAll(listOf("-socks-user", cfg.socksUser))
            cmdArgs.addAll(listOf("-socks-pass", cfg.socksPass))
        }
        return cmdArgs
    }

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        // Benign SOCKS5 IPv6 routing noise (the official qWDTT client filters the same thing) -
        // not an error, don't touch the status.
        if (lower.contains("socks") && (lower.contains("blocked by rules") || lower.contains("ipv6"))) {
            return false
        }

        // 1. Hard errors
        if (lower.startsWith("panic") || lower.contains("fatal_auth") ||
            lower.contains("нужны -peer и -vk") || lower.contains("нужен -password")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                ctx.updateNotification(ctx.getString(com.wireturn.app.R.string.error_connecting))
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
                    CoreServiceState.setCaptchaSession(
                        CaptchaSession(redirectUri, state.captchaSessionCounter, needsResultToken = true)
                    )

                    ctx.launchCaptchaActivityIfForeground(redirectUri)
                }
            }
            return false
        }

        // 3. Connected - "[SOCKS] listening" is the definitive signal; the periodic stats line's
        // "Активных: N" (N>0) is a fallback in case that line scrolled past unseen.
        val activeMatch = QWDTT_ACTIVE_REGEX.matcher(line)
        if (lower.contains("[socks] listening") ||
            (activeMatch.find() && (activeMatch.group(1)?.toIntOrNull() ?: 0) > 0)) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(com.wireturn.app.R.string.core_active))
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
}
