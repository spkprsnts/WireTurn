package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.CaptchaSession
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.ui.activities.kernel.FreeTurnConfigActivity
import java.util.regex.Pattern

object FreeTurnKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.FREETURN
    override val sensitiveCommandFlags: Set<String> = setOf("-obf-key", "-links", "-sub")
    override val displayNameRes: Int = R.string.kernel_freeturn
    override val configActivityClass = FreeTurnConfigActivity::class.java

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.FreeTurn).config
        return context.getString(displayNameRes) + " " + config.addressLabel()
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = R.drawable.ic_vk

    // FreeTurn dropped its tcp tunnel mode entirely (v3.0.0+) - it's udp-only now, unconditionally,
    // unless the config's own `mode` says otherwise.
    override fun requiredTransport(cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.FreeTurn).config
        return config.mode.lowercase().takeIf { it == "tcp" || it == "udp" } ?: "udp"
    }

    override val defaultProfileName: String = "FreeTurn Server"

    override fun decodeUri(uri: String): KernelConfig? =
        com.wireturn.app.data.FreeTurnConfig.parse(uri)?.let { KernelConfig.FreeTurn(it) }

    override fun displayNameFromUri(uri: String): String? = try {
        val base64 = uri.substringAfter("freeturn://")
        val jsonStr = String(android.util.Base64.decode(base64, android.util.Base64.URL_SAFE))
        com.google.gson.JsonParser.parseString(jsonStr).asJsonObject.get("name")?.asString
    } catch (_: Exception) { null }

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val cmdArgs = mutableListOf<String>()
        val o = (cfg.kernelConfig as KernelConfig.FreeTurn).config
        cmdArgs.add("${ctx.nativeLibraryDir}/libfreeturn.so")
        cmdArgs.addAll(listOf(
            "-listen", cfg.listenAddr.ifBlank { ClientConfig.DEFAULT_LISTEN_ADDR },
            "-provider", o.provider,
            "-peer", o.peer,
            "-n", o.n.toString(),
            "-transport", o.transport,
            "-obf-profile", o.obfProfile,
            "-streams-per-cred", o.streamsPerCred.toString(),
            "-dns-mode", o.dnsMode,
            "-platform", o.platform
        ))
        if (o.obfTiming != "0" && o.obfTiming.isNotBlank()) {
            cmdArgs.add("-obf-timing")
            cmdArgs.add(o.obfTiming)
        }
        if (o.links.isNotBlank()) {
            cmdArgs.add("-links")
            cmdArgs.add(o.links)
        }
        if (o.sub.isNotBlank()) {
            cmdArgs.add("-sub")
            cmdArgs.add(o.sub)
        }
        if (o.obfProfile != "none" && o.obfKey.isNotBlank()) {
            cmdArgs.add("-obf-key")
            cmdArgs.add(o.obfKey)
        }
        if (o.dnsServers.isNotBlank()) {
            cmdArgs.add("-dns-servers")
            cmdArgs.add(o.dnsServers)
        }
        if (o.clientId.isNotBlank()) {
            cmdArgs.add("-client-id")
            cmdArgs.add(o.clientId)
        }
        if (o.manualCaptcha) cmdArgs.add("-manual-captcha")
        if (o.mode == "tcp") {
            cmdArgs.add("-mode")
            cmdArgs.add("tcp")
            val default = com.wireturn.app.data.FreeTurnConfig()
            if (o.kcpNodelay != default.kcpNodelay) cmdArgs.addAll(listOf("-kcp-nodelay", o.kcpNodelay.toString()))
            if (o.kcpInterval != default.kcpInterval) cmdArgs.addAll(listOf("-kcp-interval", o.kcpInterval.toString()))
            if (o.kcpResend != default.kcpResend) cmdArgs.addAll(listOf("-kcp-resend", o.kcpResend.toString()))
            if (o.kcpNc != default.kcpNc) cmdArgs.addAll(listOf("-kcp-nc", o.kcpNc.toString()))
            if (o.kcpSndwnd != default.kcpSndwnd) cmdArgs.addAll(listOf("-kcp-sndwnd", o.kcpSndwnd.toString()))
            if (o.kcpRcvwnd != default.kcpRcvwnd) cmdArgs.addAll(listOf("-kcp-rcvwnd", o.kcpRcvwnd.toString()))
            if (o.kcpMtu != default.kcpMtu) cmdArgs.addAll(listOf("-kcp-mtu", o.kcpMtu.toString()))
            if (o.kcpAcknodelay != default.kcpAcknodelay) cmdArgs.addAll(listOf("-kcp-acknodelay", o.kcpAcknodelay.toString()))
        }
        return cmdArgs
    }

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        // 1. Hard Errors
        if (lower.startsWith("panic:") || lower.startsWith("fatal error:") ||
            lower.contains("all vk credentials failed") || lower.contains("fatal_captcha")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Error(line))
                ctx.updateNotification(ctx.getString(R.string.error_connecting))
            }
            state.startupFailed = true
            return true
        }

        // 2. Connected
        // Stay Connected while any session in the pool is up, not just on the exact "connected" line.
        val tcpActiveMatch = TCP_ACTIVE_REGEX.matcher(line)

        // "TURN allocation up" fires once a stream is live; the old "Established DTLS
        // connection" signal moved to Debugf and we don't pass -debug.
        if (lower.contains("] turn allocation up") ||
            (tcpActiveMatch.find() && (tcpActiveMatch.group(1)?.toIntOrNull() ?: 0) > 0)) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
                state.startupEmitted = true
            }
        }

        // 3. Connecting / Progress - without this the binary never leaves CoreStatus.Starting
        // on its own and can exceed CoreManager's startup timeout.
        if (lower.contains("provider=") ||
            lower.contains("[vk auth] connecting identity") ||
            lower.contains("[vk auth] trying credentials") ||
            lower.contains("backing off for") ||
            (lower.contains("[session ") && lower.contains("disconnected") && lower.contains("reconnecting"))
        ) {
            if (canUpdateConnectingStatus()) {
                markConnecting()
                state.startupEmitted = true
            }
        }

        // 4. Captcha
        handleCaptchaEvents(line, lower, state, ctx)

        if (state.captchaActive && (
                lower.contains("[vk auth] failed") ||
                lower.contains("[vk auth] success") ||
                lower.contains("turn allocation up") || // success line is Debugf-only now; this stays Infof
                (lower.contains("[captcha]") && lower.contains("failed"))
            )) {
            CoreServiceState.setCaptchaSession(null)
            ctx.updateNotification(ctx.getString(R.string.core_active))
            state.captchaActive = false
        }

        // 5. Soft Errors / Progress
        if (lower.contains("quota")) {
            // Log it but keep running or let watchdog handles it if it exits
            state.startupEmitted = true
        }

        return false
    }

    private fun handleCaptchaEvents(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext) {
        if (line.contains("Triggering manual captcha fallback")) {
            if (CoreServiceState.status.value !is CoreStatus.CaptchaRequired) {
                state.startupEmitted = true
            }
        }

        val captchaMatcher = CAPTCHA_URL_REGEX.matcher(line)
        val freeTurnMatcher = FREE_TURN_CAPTCHA_REGEX.matcher(line)
        val finalMatcher = if (freeTurnMatcher.find()) freeTurnMatcher else if (captchaMatcher.find()) captchaMatcher else null

        if (finalMatcher != null) {
            val captchaUrl = finalMatcher.group(1)!!
            if (CoreServiceState.captchaSession.value?.url == captchaUrl) return

            state.captchaSessionCounter += 1
            val session = CaptchaSession(captchaUrl, state.captchaSessionCounter)
            CoreServiceState.setCaptchaSession(session)
            state.captchaActive = true
            ctx.updateNotification(ctx.getString(R.string.core_captcha_required))

            // Автоматически открываем окно капчи, если приложение активно
            ctx.launchCaptchaActivityIfForeground(captchaUrl)
        }

        if (state.captchaActive && (
                lower.contains("[vk auth] failed") ||
                lower.contains("[vk auth] success") ||
                lower.contains("turn allocation up") || // success line is Debugf-only now; this stays Infof
                (lower.contains("[captcha]") && lower.contains("failed"))
            )) {
            CoreServiceState.setCaptchaSession(null)
            ctx.updateNotification(ctx.getString(R.string.core_active))
            state.captchaActive = false
        }
    }

    private val TCP_ACTIVE_REGEX = Pattern.compile("""\[session \d+] (?:connected|disconnected) \(active: (\d+)\)""")
    private val CAPTCHA_URL_REGEX = Pattern.compile("""Open this URL in your browser:\s*(https?://\S+)""")
    private val FREE_TURN_CAPTCHA_REGEX = Pattern.compile("""(?:manually open this URL|Open this URL in your browser):\s*(https?://\S+)""")
}
