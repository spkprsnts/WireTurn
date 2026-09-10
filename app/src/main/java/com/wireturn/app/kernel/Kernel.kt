package com.wireturn.app.kernel

import android.app.Activity
import android.content.Context
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import java.io.File

enum class NetworkQuality { FAST, SLOW, OFFLINE }

/** What a kernel's [Kernel.buildCommand] needs from the running [com.wireturn.app.CoreService]. */
interface KernelCommandContext {
    val filesDir: File
    val nativeLibraryDir: String
}

/** What a kernel's [Kernel.parseLogLine] needs from the running [com.wireturn.app.CoreService]. */
interface KernelLogContext {
    fun getString(resId: Int, vararg args: Any): String
    fun updateNotification(text: String)
    fun isNetworkAvailable(): Boolean
    suspend fun getNetworkQuality(): NetworkQuality
    suspend fun isNetworkMissingAndHandled(): Boolean
    fun launchCaptchaActivityIfForeground(url: String)
    // Only qWDTT's captcha flow needs this (see QwdttKernel) - a no-op default keeps every other
    // kernel from having to implement it.
    fun setPendingCaptchaSessionId(id: Long) {}
    // Shown instead of the generic core_failed message if the watchdog exhausts MAX_RESTARTS -
    // only WebdavKernel sets this today, a no-op default keeps every other kernel from having to.
    fun setLastFailureReason(reason: String) {}
}

/** Per-run mutable state threaded through repeated [Kernel.parseLogLine] calls for one binary run. */
class BinaryOutputState {
    var startupEmitted = false
    var startupFailed = false
    var captchaActive = false
    var captchaSessionCounter = 0L
    var peerConnectFailedCount = 0
    var connectingSince = 0L

    // "Give up after N repeats" counters for failure patterns that keep recurring without
    // ever surfacing a terminal error on their own.
    val remoteNotReadyCounter = LogOccurrenceCounter(windowMs = 10_000, threshold = 7)
    val webdavConnRefusedCounter = LogOccurrenceCounter(windowMs = 5_000, threshold = 10)
    val vkCaptchaSolveFailCounter = LogOccurrenceCounter(windowMs = Long.MAX_VALUE, threshold = 5)
}

/**
 * Counts repeated occurrences of a log pattern, resetting back to 1 once more than [windowMs]
 * has passed since the last occurrence. [windowMs] = [Long.MAX_VALUE] effectively disables the
 * reset (every occurrence counts, however far apart). Returns true from [recordAndCheckThreshold]
 * once [threshold] occurrences have piled up inside the window - the caller decides what to do
 * then (the counter itself keeps counting past threshold, same as the original ad hoc versions).
 */
class LogOccurrenceCounter(private val windowMs: Long, private val threshold: Int) {
    private var count = 0
    private var lastTime = 0L

    fun recordAndCheckThreshold(): Boolean {
        val now = System.currentTimeMillis()
        count = if (now - lastTime > windowMs) 1 else count + 1
        lastTime = now
        return count >= threshold
    }
}

// Suppressed/CaptchaRequired are their own dedicated states with their own UI - a progress
// marker mid-connect shouldn't override either of them. No CoreService instance state involved,
// so plain top-level functions shared by every kernel's parseLogLine.
fun canUpdateConnectingStatus(): Boolean {
    val status = CoreServiceState.status.value
    return status !is CoreStatus.Suppressed && status !is CoreStatus.CaptchaRequired
}

fun markConnecting() {
    CoreServiceState.setStatus(CoreStatus.Connecting)
    // null, not the literal text - lets a watchdog restart's "Restarting (N/M)" show through
    // instead of being clobbered by a redundant "Connecting".
    CoreServiceState.setStatusText(null)
}

/**
 * One implementation per tunnel backend (Turnable/Olcrtc/Webdav/FreeTurn/Qwdtt/OpenFlux). Adding
 * or removing a kernel means adding/removing one object here and one line in [KernelRegistry] -
 * [com.wireturn.app.CoreService] itself stays generic.
 */
interface Kernel {
    val variant: KernelVariant

    /** Full argv (binary path at index 0) for launching this kernel's binary with [cfg]. */
    fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String>

    /**
     * Handles one line of the binary's stdout, updating [CoreServiceState] as needed via [ctx]
     * and [state]. Returns true to stop reading this run's output (fatal error, or a transient
     * failure the watchdog should retry) - same contract as the original per-kernel handlers.
     */
    suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean

    /** Command-line flags whose values should be redacted in the app's own log (see CoreService). */
    val sensitiveCommandFlags: Set<String> get() = emptySet()

    // --- UI / display metadata (was duplicated per-kernel `when`s across ProfilesUI, XraySetupScreen, CoreTriggerController, ValidatorUtils) ---

    /** Short kernel name, e.g. R.string.kernel_turnable. */
    val displayNameRes: Int

    /** The config screen Activity for editing a profile of this kernel. */
    val configActivityClass: Class<out Activity>

    /** One-line description shown in profile lists/notifications (was `KernelConfig.description()`). */
    fun description(context: Context, cfg: KernelConfig): String

    /** Extra profile-summary text appended after [description], or null if this kernel has none. */
    fun profileSummaryExtra(cfg: KernelConfig): String? = null

    /** Profile list icon for this kernel's config (outlined variant used where the kernel has none of its own). */
    fun iconRes(cfg: KernelConfig, outlined: Boolean): Int

    /** "WireGuard isn't used with X" copy shown for SOCKS5-native kernels on the Xray setup screen. */
    val wgNotUsedMessageRes: Int get() = R.string.wg_not_used_with_webdav

    /**
     * Whether this kernel's own SOCKS5 listener accepts username/password auth - false only for
     * OpenFlux, whose embedded server has no auth flags upstream. Irrelevant for non-SOCKS5-native
     * kernels (Turnable/FreeTurn), which never reach the code paths that check this.
     */
    val socks5SupportsAuth: Boolean get() = true

    /**
     * This kernel's fixed transport requirement ("tcp"/"udp"), if it has one that can conflict
     * with the Xray protocol/link riding on top of it - only Turnable (route-dependent) and
     * FreeTurn (mode-dependent) have one; every other kernel returns null.
     */
    fun requiredTransport(cfg: KernelConfig): String? = null

    // --- Share-link decoding (was the scheme-dispatch `when` in ProfileManager's text-subscription parser) ---

    /** Parses [uri] into this kernel's config, or null if [uri] isn't (validly) this kernel's scheme. */
    fun decodeUri(uri: String): KernelConfig?

    /** Best-effort display name pulled from [uri] itself (e.g. a query param or fragment), if any. */
    fun displayNameFromUri(uri: String): String? = null

    /** Fallback profile name when [displayNameFromUri] found nothing. */
    val defaultProfileName: String
}

object KernelRegistry {
    private val all: List<Kernel> = listOf(
        TurnableKernel, OlcrtcKernel, WebdavKernel, FreeTurnKernel, QwdttKernel, OpenFluxKernel
    )
    private val byVariant = all.associateBy { it.variant }

    fun get(variant: KernelVariant): Kernel = byVariant.getValue(variant)

    /** Tries every kernel's [Kernel.decodeUri] and returns the first match, if any. */
    fun decodeUri(uri: String): Pair<Kernel, KernelConfig>? =
        all.firstNotNullOfOrNull { k -> k.decodeUri(uri)?.let { k to it } }
}
