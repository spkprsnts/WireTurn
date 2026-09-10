package com.wireturn.app.kernel

import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.ClientConfig
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
}

object KernelRegistry {
    private val all: List<Kernel> = listOf(
        TurnableKernel, OlcrtcKernel, WebdavKernel, FreeTurnKernel, QwdttKernel, OpenFluxKernel
    )
    private val byVariant = all.associateBy { it.variant }

    fun get(variant: KernelVariant): Kernel = byVariant.getValue(variant)
}
