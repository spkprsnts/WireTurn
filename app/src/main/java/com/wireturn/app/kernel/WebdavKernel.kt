package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.AppLogsState
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.WebdavConfig
import com.wireturn.app.ui.activities.kernel.WebdavConfigActivity
import java.io.File

object WebdavKernel : Kernel {
    override val variant: KernelVariant = KernelVariant.WEBDAV
    override val displayNameRes: Int = R.string.kernel_webdav
    override val configActivityClass = WebdavConfigActivity::class.java

    override fun description(context: Context, cfg: KernelConfig): String {
        val config = (cfg as KernelConfig.Webdav).config
        return context.getString(displayNameRes) + " " + WebdavConfig.formatHost(config.webdav) +
            if (config.backends.isNotEmpty()) " +${config.backends.size}" else ""
    }

    override fun profileSummaryExtra(cfg: KernelConfig): String? {
        val login = (cfg as KernelConfig.Webdav).config.login
        return login.takeIf { it.isNotBlank() }?.substringBefore('@')
    }

    override fun iconRes(cfg: KernelConfig, outlined: Boolean): Int = R.drawable.ic_dav

    override val defaultProfileName: String = "WebDAV Server"

    override fun decodeUri(uri: String): KernelConfig? =
        WebdavConfig.parse(uri)?.let { KernelConfig.Webdav(it) }

    override fun displayNameFromUri(uri: String): String? = try {
        android.net.Uri.parse(uri).fragment
    } catch (_: Exception) { null }

    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val cmdArgs = mutableListOf<String>()
        cmdArgs.add("${ctx.nativeLibraryDir}/libwebdav.so")
        val configFile = File(ctx.filesDir, "webdav.yaml")
        configFile.writeText(buildWebdavYaml(cfg))
        cmdArgs.addAll(listOf("-config", configFile.absolutePath))
        return cmdArgs
    }

    private fun buildWebdavYaml(cfg: ClientConfig): String {
        val o = (cfg.kernelConfig as KernelConfig.Webdav).config
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            appendLine("mode: client")
            appendLine("socks-listen: \"${esc(cfg.socksAddr.ifBlank { ClientConfig.DEFAULT_SOCKS_ADDR })}\"")
            if (cfg.isSocksAuthEnabled) {
                appendLine("socks-user: \"${esc(cfg.socksUser)}\"")
                appendLine("socks-pass: \"${esc(cfg.socksPass)}\"")
            }
            if (o.encrypt) appendLine("enc: true")
            appendLine("timeout: \"${esc(o.timeout)}\"")
            if (cfg.dns.isNotBlank()) appendLine("dns: \"${esc(cfg.dns)}\"")

            appendLine("backends:")
            appendLine("  - url: \"${esc(o.webdav)}\"")
            appendLine("    login: \"${esc(o.login)}\"")
            appendLine("    password: \"${esc(o.password)}\"")
            for (backend in o.backends) {
                // BackendConfig (external/webdav-tunnel config.go) only has url/login/password -
                // the label is purely a local display name, not sent to the tunnel binary.
                appendLine("  - url: \"${esc(backend.url)}\"")
                appendLine("    login: \"${esc(backend.login)}\"")
                appendLine("    password: \"${esc(backend.password)}\"")
            }

            appendLine("tuning:")
            appendLine("  poll-min: \"${esc(o.pollMin)}\"")
            appendLine("  poll-max: \"${esc(o.pollMax)}\"")
            appendLine("  coalesce: \"${esc(o.coalesce)}\"")
            appendLine("  chunk-size: ${o.chunkSize.toIntOrNull() ?: 131071}")
            appendLine("  puts: ${o.puts.toIntOrNull() ?: 8}")
            appendLine("  read-min: ${o.readMin.toIntOrNull() ?: 3}")
            appendLine("  read-max: ${o.readMax.toIntOrNull() ?: 8}")
        }
    }

    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        // pingBackends() logs one "connection failed" per dead backend before the final "all N
        // unreachable" fatal - swallow those (false, not true: true would break the read loop
        // here and we'd never see that final line).
        if (lower.contains("webdav backend") && lower.contains("connection failed")) {
            return false
        }

        // pingBackends() logs "OK (<ms>)" per reachable backend - earliest real progress signal.
        if (lower.contains("webdav backend") && lower.contains("ok (")) {
            if (canUpdateConnectingStatus()) {
                markConnecting()
            }
        }

        // Marks the process as genuinely started so a later crash goes through the normal
        // watchdog retry instead of the "no output" hard-stop path.
        if (lower.contains("socks5 proxy listening on")) {
            state.startupEmitted = true
        }

        // All configured backends unreachable - treat as transient (rate limit, brief outage)
        // and let the normal watchdog retry rather than hard-failing.
        if (lower.contains("webdav backend(s) unreachable")) {
            AppLogsState.addLog(ctx.getString(R.string.log_core_webdav_backends_unreachable))
            ctx.setLastFailureReason(ctx.getString(R.string.error_webdav_backend_unreachable))
            state.startupEmitted = true
            return true
        }

        if (lower.contains("server connection lost") || lower.contains("server has not picked up the session")) {
            if (ctx.getNetworkQuality() == NetworkQuality.FAST) {
                if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                    CoreServiceState.setStatus(CoreStatus.Error(ctx.getString(R.string.error_webdav_server_unavailable)))
                    ctx.updateNotification(ctx.getString(R.string.error_connecting))
                }
                state.startupFailed = true
            } else {
                state.startupEmitted = true
            }
            return true
        }

        if (lower.contains("server connected")) {
            if (CoreServiceState.status.value !is CoreStatus.Suppressed) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
                state.startupEmitted = true
            }
        }

        if (lower.contains("panic") || lower.contains("fatal") || lower.contains("error starting socks5")) {
            CoreServiceState.setStatus(CoreStatus.Error(line))
            state.startupFailed = true
            return true
        }

        if (lower.contains("connection refused")) {
            if (state.webdavConnRefusedCounter.recordAndCheckThreshold()) {
                AppLogsState.addLog(ctx.getString(R.string.log_core_webdav_too_many_refused))
                state.startupEmitted = true // Trigger watchdog
                return true
            }
        }

        return false
    }
}
