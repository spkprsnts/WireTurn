package com.wireturn.app

/** Severity of one log line - drives both the log level setting and the Logs screen's line colors. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

object LogLevels {
    // Only look near the start of the line: the logger's own level prefix always sits right after
    // the timestamp, anything further in is message text (e.g. an error quoting another log).
    private const val MARKER_SCAN_LENGTH = 64

    private val MARKERS = listOf(
        // turnable (pkg/common/logging.go) and free-turn-proxy (internal/logx): "[LEVEL] msg"
        "[DEBUG]" to LogLevel.DEBUG,
        "[INFO]" to LogLevel.INFO,
        "[WARN]" to LogLevel.WARN,
        "[ERROR]" to LogLevel.ERROR,
        // xray-core
        "[Debug]" to LogLevel.DEBUG,
        "[Info]" to LogLevel.INFO,
        "[Warning]" to LogLevel.WARN,
        "[Error]" to LogLevel.ERROR,
        // pion, routed through olcrtc's PionLeveledLogger: "[scope] LEVEL: msg"
        "] TRACE:" to LogLevel.DEBUG,
        "] DEBUG:" to LogLevel.DEBUG,
        "] INFO:" to LogLevel.INFO,
        "] WARN:" to LogLevel.WARN,
        "] ERROR:" to LogLevel.ERROR
    )

    private val ERROR_KEYWORDS = listOf(
        "ошибка", "error", "критическая", "failed", "fatal", "panic", "did not complete", "could not"
    )
    private val WARN_KEYWORDS = listOf(
        "watchdog", "перезапуск", "quota", "warn", ">>>", "stopped", "connection lost",
        "reconnecting", "restart", "timeout", "captcha", "refused", "offline"
    )

    /** The level from an explicit logger prefix in [line], or null if it has none. */
    fun fromMarker(line: String): LogLevel? {
        val head = line.take(MARKER_SCAN_LENGTH)
        var level: LogLevel? = null
        var levelIndex = Int.MAX_VALUE
        for ((marker, markerLevel) in MARKERS) {
            val i = head.indexOf(marker)
            if (i in 0 until levelIndex) {
                level = markerLevel
                levelIndex = i
            }
        }
        return level
    }

    /**
     * Keyword guess for lines without a level prefix (the app's own messages, and kernels that log
     * without levels - olcrtc, WebDAV, qWDTT). Never returns [LogLevel.DEBUG]: nothing in the text
     * alone says a line is debug-only.
     */
    fun fromKeywords(line: String): LogLevel {
        val lower = line.lowercase()
        return when {
            ERROR_KEYWORDS.any { lower.contains(it) } -> LogLevel.ERROR
            WARN_KEYWORDS.any { lower.contains(it) } -> LogLevel.WARN
            else -> LogLevel.INFO
        }
    }

    fun detect(line: String): LogLevel = fromMarker(line) ?: fromKeywords(line)
}
