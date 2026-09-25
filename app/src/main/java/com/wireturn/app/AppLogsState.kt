package com.wireturn.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AppLogsState {

    data class LogEntry(val id: Long, val message: String, val level: LogLevel)

    // Sized for the "All" level: every kernel runs with its debug output on.
    private const val MAX_LOG_LINES = 2000
    private var nextId = 0L
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Lines below this are dropped as they come in rather than stored and hidden - otherwise
    // debug output would push everything else out of the MAX_LOG_LINES buffer. Kept in sync with
    // the saved setting by WireTurnApp.
    @Volatile
    private var minLevel = LogLevel.INFO

    fun setMinLevel(level: LogLevel) {
        minLevel = level
        // Raising the level drops what's already stored below it; lowering can't bring back what
        // was never kept.
        _logs.update { current -> current.filter { it.level >= level } }
    }

    /** [level] null = guess it from the text, see [LogLevels.detect]. */
    fun addLog(msg: String, level: LogLevel? = null) {
        val cleanMsg = stripAnsi(msg)
        val resolvedLevel = level ?: LogLevels.detect(cleanMsg)
        if (resolvedLevel < minLevel) return
        android.util.Log.println(resolvedLevel.logcatPriority(), "WireTurnCore", cleanMsg)
        _logs.update { current ->
            val next = current + LogEntry(nextId++, cleanMsg, resolvedLevel)
            if (next.size > MAX_LOG_LINES) next.drop(next.size - MAX_LOG_LINES) else next
        }
    }

    fun stripAnsi(msg: String): String {
        return msg.replace("\u001B\\[[;\\d]*[mK]".toRegex(), "")
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun LogLevel.logcatPriority(): Int = when (this) {
        LogLevel.DEBUG -> android.util.Log.DEBUG
        LogLevel.INFO -> android.util.Log.INFO
        LogLevel.WARN -> android.util.Log.WARN
        LogLevel.ERROR -> android.util.Log.ERROR
    }
}
