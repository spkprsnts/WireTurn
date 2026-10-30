package com.wireturn.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

object AppLogsState {

    /**
     * [timeMs] is when the app took the line in, on the device's clock - the app's own lines carry
     * no time, and the kernels' own stamps are in UTC (Go on Android), so neither tells when.
     */
    data class LogEntry(val id: Long, val message: String, val level: LogLevel, val timeMs: Long) {
        /** "HH:mm:ss" in the device's time zone, or "HH:mm:ss.SSS" for a saved file. */
        fun timeLabel(withMillis: Boolean = false): String =
            (if (withMillis) TIME_MS_FORMAT else TIME_FORMAT)
                .format(Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()))
    }

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val TIME_MS_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    // Sized for the "All" level: every kernel runs with its debug output on.
    private const val MAX_LOG_LINES = 2000

    // Lines land in [buffer] as they come; [logs] gets a fresh copy of it at most this often.
    // A kernel at debug level writes hundreds of lines a second, and copying the whole buffer and
    // recomposing the log screen for each one kept the main thread at ~44% of a core.
    private const val PUBLISH_INTERVAL_MS = 100L

    private val ANSI_ESCAPE = Regex("\u001B\\[[;\\d]*[mK]")

    // Go's log stamp ("2026/10/30 00:49:51.478922 "), at the start of a kernel's line or after the
    // tag a service puts in front of it ("* [Xray] 2026/10/30 01:09:42 ..."). LogEntry.timeMs
    // replaces it: it's the same moment in UTC, which next to the device's time only misleads.
    // Only what's stored goes - the kernels parse their raw lines before they get here.
    private val GO_LOG_STAMP = Regex("""^(\* \[[^\]]+] )?\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)? """)

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_LOG_LINES)
    private var nextId = 0L

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Conflated: any number of lines during one interval is one publish. The first line after a
    // quiet spell goes out at once, the rest of a burst follows once the interval is over.
    private val publishRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            for (request in publishRequests) {
                publish()
                delay(PUBLISH_INTERVAL_MS.milliseconds)
            }
        }
    }

    // Lines below this are dropped as they come in rather than stored and hidden - otherwise
    // debug output would push everything else out of the MAX_LOG_LINES buffer. Kept in sync with
    // the saved setting by WireTurnApp.
    @Volatile
    private var minLevel = LogLevel.INFO

    fun setMinLevel(level: LogLevel) {
        minLevel = level
        // Raising the level drops what's already stored below it; lowering can't bring back what
        // was never kept.
        synchronized(lock) {
            if (buffer.removeAll { it.level < level }) publish()
        }
    }

    /** [level] null = guess it from the text, see [LogLevels.detect]. */
    fun addLog(msg: String, level: LogLevel? = null) {
        val cleanMsg = stripAnsi(msg).replaceFirst(GO_LOG_STAMP, "$1")
        val resolvedLevel = level ?: LogLevels.detect(cleanMsg)
        if (resolvedLevel < minLevel) return
        android.util.Log.println(resolvedLevel.logcatPriority(), "WireTurnCore", cleanMsg)
        synchronized(lock) {
            buffer.addLast(LogEntry(nextId++, cleanMsg, resolvedLevel, System.currentTimeMillis()))
            if (buffer.size > MAX_LOG_LINES) buffer.removeFirst()
        }
        publishRequests.trySend(Unit)
    }

    /** Whether anything is stored, including lines [logs] hasn't been given yet. */
    fun isEmpty(): Boolean = synchronized(lock) { buffer.isEmpty() }

    fun stripAnsi(msg: String): String = msg.replace(ANSI_ESCAPE, "")

    fun clearLogs() {
        synchronized(lock) {
            buffer.clear()
            publish()
        }
    }

    // Under [lock] whenever it races with a change, so an older snapshot can't land after a newer one.
    private fun publish() {
        synchronized(lock) { _logs.value = buffer.toList() }
    }

    private fun LogLevel.logcatPriority(): Int = when (this) {
        LogLevel.DEBUG -> android.util.Log.DEBUG
        LogLevel.INFO -> android.util.Log.INFO
        LogLevel.WARN -> android.util.Log.WARN
        LogLevel.ERROR -> android.util.Log.ERROR
    }
}
